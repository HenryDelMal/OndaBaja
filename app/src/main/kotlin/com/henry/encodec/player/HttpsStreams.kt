package com.henry.encodec.player

import cl.cuy.emergencyradio.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.brotli.dec.BrotliInputStream
import android.net.TrafficStats
import android.os.Process
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object HttpsStreams {
    private val requestIds = AtomicLong(0)
    // URLConnection can remain blocked in DNS or Cronet despite disconnect().
    // Isolate it from the segment producer and bound abandoned work as well.
    private val requestWorkers = ThreadPoolExecutor(
        8, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, "ondabaja-http-worker").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    internal suspend fun <T> boundedRequest(timeoutMs: Long?, request: () -> T): T {
        suspend fun awaitRequest(): T = suspendCancellableCoroutine { continuation ->
            try {
                val task = requestWorkers.submit {
                    try {
                        val result = request()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    task.cancel(true)
                    (task as? Runnable)?.let(requestWorkers::remove)
                }
            } catch (error: RejectedExecutionException) {
                continuation.resumeWithException(IOException("Network workers are still recovering", error))
            }
        }
        return try {
            if (timeoutMs == null) awaitRequest() else withTimeout(timeoutMs) { awaitRequest() }
        } catch (error: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            LiveDiagnostics.warn("http watchdog expired deadlineMs=$timeoutMs " +
                "activeWorkers=${requestWorkers.activeCount} pendingWorkers=${requestWorkers.queue.size}")
            throw java.net.SocketTimeoutException("HTTP request exceeded ${timeoutMs}ms total deadline")
                .also { it.initCause(error) }
        }
    }

    suspend fun readLivePrefix(url: String, maxBytes: Int): ByteArray {
        val timeoutMs = (LIVE_CONNECT_TIMEOUT_MS + LIVE_READ_TIMEOUT_MS).toLong()
        return boundedRequest(timeoutMs) {
            readPrefix(url, maxBytes,
                connectTimeoutMs = LIVE_CONNECT_TIMEOUT_MS,
                readTimeoutMs = LIVE_READ_TIMEOUT_MS,
                maxAttempts = 1,
                wallClockTimeoutMs = timeoutMs,
            )
        }
    }
    private val requestDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ondabaja-http-deadlines").apply { isDaemon = true }
    }
    private const val MAX_REDIRECTS = 5
    private const val MAX_OPEN_ATTEMPTS = 3
    private val LIVE_CONNECT_TIMEOUT_MS = BuildConfig.LIVE_CONNECT_TIMEOUT_MS
    private val LIVE_READ_TIMEOUT_MS = BuildConfig.LIVE_READ_TIMEOUT_MS

    /** Reads a finite response using the currently selected network scheme and transport. */
    fun open(url: String): InputStream {
        var lastError: IOException? = null
        repeat(MAX_OPEN_ATTEMPTS) { attempt ->
            try {
                return openOnce(
                    url,
                    noCache = false,
                    allowInitialHttp = true,
                    transport = transportForAttempt(attempt),
                )
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_OPEN_ATTEMPTS - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Could not open the stream")
    }

    fun openRange(
        url: String,
        startByte: Long,
        connectTimeoutMs: Int = BuildConfig.FILE_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = BuildConfig.FILE_READ_TIMEOUT_MS,
        maxAttempts: Int = MAX_OPEN_ATTEMPTS,
    ): InputStream {
        require(startByte >= 0)
        require(maxAttempts > 0)
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return openOnce(
                    url = url,
                    noCache = false,
                    allowInitialHttp = true,
                    rangeStartInclusive = startByte,
                    disconnectOnClose = true,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    diagnosticRequestId = requestIds.incrementAndGet(),
                    transport = transportForAttempt(attempt),
                )
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Could not open the requested range")
    }

    /** Reads only enough of a static file to inspect its ECDC header. */
    fun readPrefix(
        url: String,
        maxBytes: Int,
        connectTimeoutMs: Int = BuildConfig.FILE_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = BuildConfig.FILE_READ_TIMEOUT_MS,
        maxAttempts: Int = MAX_OPEN_ATTEMPTS,
        wallClockTimeoutMs: Long? = null,
    ): ByteArray {
        require(maxBytes > 0)
        require(maxAttempts > 0)
        var lastError: IOException? = null
        val requestId = requestIds.incrementAndGet()
        val requestUri = NetworkProtocolSettings.applyScheme(java.net.URL(url)).toURI()
        repeat(maxAttempts) { attempt ->
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val wireBodyBytes = AtomicLong(0)
            val attemptStarted = LiveDiagnostics.nowMs()
            val transport = transportForAttempt(attempt)
            val deadlineExpired = java.util.concurrent.atomic.AtomicBoolean(false)
            val deadline = wallClockTimeoutMs?.let { timeoutMs ->
                requestDeadlineExecutor.schedule({
                    deadlineExpired.set(true)
                    activeConnection.get()?.disconnect()
                }, timeoutMs, TimeUnit.MILLISECONDS)
            }
            LiveDiagnostics.info(
                "http range probe start id=$requestId host=${requestUri.host ?: "unknown"} " +
                    "path=${requestUri.rawPath} attempt=${attempt + 1} " +
                    "requestedRange=bytes=0-${maxBytes - 1} connectTimeoutMs=$connectTimeoutMs " +
                    "readTimeoutMs=$readTimeoutMs transport=${CronetTransports.displayName(transport, requestUri.toURL())}",
            )
            try {
                openOnce(
                    url = url,
                    noCache = false,
                    allowInitialHttp = true,
                    onConnection = { connection ->
                        activeConnection.set(connection)
                        if (deadlineExpired.get()) {
                            connection.disconnect()
                            throw java.net.SocketTimeoutException("ECDC probe deadline expired")
                        }
                    },
                    rangeEndInclusive = maxBytes - 1,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    diagnosticRequestId = requestId,
                    wireBodyBytes = wireBodyBytes,
                    transport = transport,
                ).use { input ->
                    val output = ByteArrayOutputStream(maxBytes)
                    val buffer = ByteArray(8 * 1024)
                    while (output.size() < maxBytes) {
                        val read = input.read(
                            buffer,
                            0,
                            minOf(buffer.size, maxBytes - output.size()),
                        )
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    if (deadlineExpired.get()) throw java.net.SocketTimeoutException(
                        "ECDC probe exceeded ${wallClockTimeoutMs}ms wall-clock deadline",
                    )
                    val connection = activeConnection.get()
                    LiveDiagnostics.info(
                        "http range probe complete id=$requestId attempt=${attempt + 1} " +
                            "status=${connection?.responseCode ?: -1} " +
                            "contentRange=${connection?.getHeaderField("Content-Range") ?: "-"} " +
                            "contentLength=${connection?.contentLengthLong ?: -1L} " +
                            "wireBodyBytes=${wireBodyBytes.get()} receivedBytes=${output.size()} " +
                            "elapsedMs=${LiveDiagnostics.nowMs() - attemptStarted}",
                    )
                    return output.toByteArray()
                }
            } catch (error: IOException) {
                lastError = error
                LiveDiagnostics.warn(
                    "http range probe failed id=$requestId attempt=${attempt + 1} " +
                        "receivedBytes=${wireBodyBytes.get()} elapsedMs=${LiveDiagnostics.nowMs() - attemptStarted} " +
                        "error=${error::class.java.simpleName}",
                )
                activeConnection.getAndSet(null)?.disconnect()
                if (attempt < maxAttempts - 1) Thread.sleep(500L * (attempt + 1))
            } finally {
                deadline?.cancel(false)
            }
        }
        throw lastError ?: IOException("Could not inspect the remote file")
    }

    /** Cancellable, bounded HTTP(S) download used by live manifests and segments. */
    suspend fun readBytes(
        url: String,
        maxBytes: Int,
        noCache: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        accept: String = "*/*",
        ifNoneMatch: String? = null,
        cachedResponse: ByteArray? = null,
        onEtag: (String?) -> Unit = {},
        wallClockTimeoutMs: Long? = null,
    ): ByteArray = withContext(dispatcher) {
        boundedRequest(wallClockTimeoutMs) {
            runBlocking {
                readBytesInternal(url, maxBytes, noCache, Dispatchers.Unconfined, accept,
                    ifNoneMatch, cachedResponse, onEtag, wallClockTimeoutMs)
            }
        }
    }

    private suspend fun readBytesInternal(
        url: String,
        maxBytes: Int,
        noCache: Boolean,
        dispatcher: CoroutineDispatcher,
        accept: String,
        ifNoneMatch: String?,
        cachedResponse: ByteArray?,
        onEtag: (String?) -> Unit,
        wallClockTimeoutMs: Long?,
    ): ByteArray =
        withContext(dispatcher) {
            require(maxBytes > 0)
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val cancellation = coroutineContext.job.invokeOnCompletion {
                activeConnection.getAndSet(null)?.disconnect()
            }
            try {
                var lastError: IOException? = null
                val requestId = requestIds.incrementAndGet()
                val requestUri = NetworkProtocolSettings.applyScheme(java.net.URL(url)).toURI()
                // A manifest retry is useful only when it changes transport.
                // Plain HTTP and HTTPS modes have one transport, so let the
                // source-level recovery loop retry after refreshing state.
                val attemptCount = if (noCache) {
                    CronetTransports.transportsForCurrentMode().distinct().size.coerceAtLeast(1)
                } else MAX_OPEN_ATTEMPTS
                repeat(attemptCount) { attempt ->
                    coroutineContext.ensureActive()
                    val attemptStarted = LiveDiagnostics.nowMs()
                    val payloadBytes = AtomicReference(0)
                    val wireBodyBytes = AtomicLong(0)
                    val deadlineExpired = AtomicReference(false)
                    val deadline = wallClockTimeoutMs?.let { timeoutMs ->
                        requestDeadlineExecutor.schedule({
                            deadlineExpired.set(true)
                            // Cronet's URLConnection adapter may not enforce
                            // readTimeout while waiting for response headers.
                            // Disconnect from a timer thread to bound that wait.
                            activeConnection.get()?.disconnect()
                        }, timeoutMs, TimeUnit.MILLISECONDS)
                    }
                    val rxBefore = TrafficStats.getUidRxBytes(Process.myUid())
                    val txBefore = TrafficStats.getUidTxBytes(Process.myUid())
                    val host = requestUri.host ?: "unknown"
                    val path = requestUri.rawPath
                    val transport = transportForAttempt(attempt)
                    var stage = "connect_or_headers"
                    LiveDiagnostics.info(
                        "http start id=$requestId host=$host path=$path attempt=${attempt + 1} " +
                            "purpose=${if (noCache) "manifest" else "segment-or-file"} " +
                            "connectTimeoutMs=$LIVE_CONNECT_TIMEOUT_MS readTimeoutMs=$LIVE_READ_TIMEOUT_MS " +
                            "headerDeadlineMs=${wallClockTimeoutMs ?: "none"} " +
                        "mode=${NetworkProtocolSettings.current().name} scheme=${requestUri.scheme} " +
                            "acceptEncoding=${HttpCompressionSettings.current().acceptEncoding} " +
                            "transport=${CronetTransports.displayName(transport, requestUri.toURL())} " +
                            "dnsMetrics=${if (transport == HttpTransport.PLATFORM) "not_exposed_by_platform_transport" else "cronet_request_finished"} " +
                            "connectionPolicy=" +
                            if (transport == HttpTransport.PLATFORM && attempt > 0) "force_close" else "managed_pool",
                    )
                    try {
                        val input = openOnce(
                            url = url,
                            noCache = noCache,
                            allowInitialHttp = true,
                            onConnection = activeConnection::set,
                            connectTimeoutMs = LIVE_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = LIVE_READ_TIMEOUT_MS,
                            forceFreshConnection = attempt > 0,
                            diagnosticRequestId = requestId,
                            wireBodyBytes = wireBodyBytes,
                            transport = transport,
                            accept = accept,
                            ifNoneMatch = ifNoneMatch,
                            // Keep the deadline active through body decoding,
                            // not just until response headers arrive.
                        )
                        coroutineContext.ensureActive()
                        val headersMs = LiveDiagnostics.nowMs() - attemptStarted
                        val responseConnection = activeConnection.get()
                        val responseEtag = responseConnection?.getHeaderField("ETag")
                        if (responseConnection?.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            val unchanged = cachedResponse
                                ?: throw IOException("Server returned 304 without a cached manifest")
                            onEtag(responseEtag)
                            activeConnection.set(null)
                            responseConnection.disconnect()
                            LiveDiagnostics.info(
                                "http complete id=$requestId host=$host path=$path scheme=${requestUri.scheme} " +
                                    "transport=${CronetTransports.displayName(transport, requestUri.toURL())} " +
                                    "attempt=${attempt + 1} status=304 unchanged=true wireBodyBytes=0 " +
                                    "compressedBytes=0 payloadBytes=0 cachedPayloadBytes=${unchanged.size} elapsedMs=" +
                                    "${LiveDiagnostics.nowMs() - attemptStarted} etagPresent=${responseEtag != null}",
                            )
                            return@withContext unchanged
                        }
                        stage = "body"
                        var firstByteMs = -1L
                        var longestReadMs = 0L
                        var reads = 0
                        input.use {
                            val declaredLength = responseConnection?.contentLengthLong ?: -1L
                            val contentEncoding = responseConnection?.contentEncoding ?: "identity"
                            if (declaredLength > maxBytes) {
                                throw IOException("HTTP response exceeds $maxBytes bytes")
                            }
                            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val readStarted = LiveDiagnostics.nowMs()
                                val read = input.read(buffer)
                                longestReadMs = maxOf(longestReadMs, LiveDiagnostics.nowMs() - readStarted)
                                if (read > 0) {
                                    reads++
                                    if (firstByteMs < 0) firstByteMs = LiveDiagnostics.nowMs() - attemptStarted
                                }
                                if (read < 0) break
                                if (output.size() + read > maxBytes) {
                                    throw IOException("HTTP response exceeds $maxBytes bytes")
                                }
                                output.write(buffer, 0, read)
                                payloadBytes.set(output.size())
                            }
                            val result = output.toByteArray()
                            coroutineContext.ensureActive()
                            if (deadlineExpired.get()) throw java.net.SocketTimeoutException(
                                "HTTP response exceeded ${wallClockTimeoutMs}ms total deadline",
                            )
                            onEtag(responseEtag)
                            // A fully consumed response can return its socket to
                            // HttpURLConnection's keep-alive pool. This avoids a
                            // fresh DNS lookup and TLS handshake per segment.
                            activeConnection.set(null)
                            val elapsedMs = LiveDiagnostics.nowMs() - attemptStarted
                            val rxDelta = trafficDelta(TrafficStats.getUidRxBytes(Process.myUid()), rxBefore)
                            val txDelta = trafficDelta(TrafficStats.getUidTxBytes(Process.myUid()), txBefore)
                            val payloadKbps = if (elapsedMs > 0) result.size * 8.0 / elapsedMs else 0.0
                            LiveDiagnostics.info(
                                "http complete id=$requestId host=$host path=$path scheme=${requestUri.scheme} " +
                                    "transport=${CronetTransports.displayName(transport, requestUri.toURL())} " +
                                    "attempt=${attempt + 1} responseMs=$headersMs " +
                                    "firstByteMs=$firstByteMs longestReadMs=$longestReadMs bodyReads=$reads " +
                                    "contentEncoding=$contentEncoding declaredWireBodyBytes=$declaredLength " +
                                    "wireBodyBytes=${wireBodyBytes.get()} " +
                                    "compressedBytes=${if (contentEncoding.isBlank() || contentEncoding.equals("identity", true)) -1 else wireBodyBytes.get()} " +
                                    "bodyMs=${elapsedMs - headersMs} elapsedMs=$elapsedMs " +
                                    "payloadBytes=${result.size} payloadKbps=${"%.2f".format(Locale.US, payloadKbps)} " +
                                    "uidRxDeltaBytes=$rxDelta uidTxDeltaBytes=$txDelta freshRequested=${attempt > 0}",
                            )
                            return@withContext result
                        }
                    } catch (error: IOException) {
                        deadline?.cancel(false)
                        coroutineContext.ensureActive()
                        activeConnection.getAndSet(null)?.disconnect()
                        lastError = error
                        LiveDiagnostics.warn(
                            "http failed id=$requestId host=$host path=$path scheme=${requestUri.scheme} " +
                                "transport=${CronetTransports.displayName(transport, requestUri.toURL())} " +
                                "attempt=${attempt + 1} stage=$stage elapsedMs=" +
                                "${LiveDiagnostics.nowMs() - attemptStarted} " +
                                "payloadBytes=${payloadBytes.get()} " +
                                "wireBodyBytes=${wireBodyBytes.get()} " +
                                "uidRxDeltaBytes=${trafficDelta(TrafficStats.getUidRxBytes(Process.myUid()), rxBefore)} " +
                                "uidTxDeltaBytes=${trafficDelta(TrafficStats.getUidTxBytes(Process.myUid()), txBefore)} " +
                                "error=${error::class.java.simpleName}",
                        )
                        if (deadlineExpired.get()) {
                            throw java.net.SocketTimeoutException(
                                "HTTP request exceeded ${wallClockTimeoutMs}ms wall-clock deadline",
                            ).also { it.initCause(error) }
                        }
                        if (attempt < attemptCount - 1) {
                            val retryMs = 200L * (attempt + 1)
                            LiveDiagnostics.warn(
                                "http transport fallback id=$requestId scheme=${requestUri.scheme} " +
                                    "from=${CronetTransports.displayName(transport, requestUri.toURL())} " +
                                    "to=${CronetTransports.displayName(transportForAttempt(attempt + 1), requestUri.toURL())} " +
                                    "error=${error::class.java.simpleName}",
                            )
                            LiveDiagnostics.info("http retry wait id=$requestId waitMs=$retryMs nextAttempt=${attempt + 2}")
                            delay(retryMs)
                        }
                    } finally {
                        deadline?.cancel(false)
                    }
                }
                throw lastError ?: IOException("Could not download the response")
            } finally {
                cancellation.dispose()
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

    private fun trafficDelta(after: Long, before: Long): Long =
        if (after < 0 || before < 0) -1L else (after - before).coerceAtLeast(0L)

    private fun openOnce(
        url: String,
        noCache: Boolean,
        allowInitialHttp: Boolean,
        onConnection: (HttpURLConnection) -> Unit = {},
        rangeStartInclusive: Long? = null,
        rangeEndInclusive: Int? = null,
        disconnectOnClose: Boolean = false,
        connectTimeoutMs: Int = BuildConfig.FILE_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = BuildConfig.FILE_READ_TIMEOUT_MS,
        forceFreshConnection: Boolean = false,
        diagnosticRequestId: Long? = null,
        wireBodyBytes: AtomicLong? = null,
        transport: HttpTransport = HttpTransport.PLATFORM,
        accept: String = "*/*",
        ifNoneMatch: String? = null,
        onResponseHeaders: () -> Unit = {},
    ): InputStream {
        var current = NetworkProtocolSettings.applyScheme(java.net.URL(url)).toURI()
        require(
            current.scheme.equals("https", ignoreCase = true) ||
                (allowInitialHttp && current.scheme.equals("http", ignoreCase = true)),
        ) { if (allowInitialHttp) "Only HTTP and HTTPS URLs are supported" else "Only HTTPS URLs are supported" }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(
                current.scheme.equals("https", ignoreCase = true) ||
                    current.scheme.equals("http", ignoreCase = true),
            ) { "Redirect uses an unsupported protocol" }
            val connectionTransport = if (current.scheme.equals("https", ignoreCase = true)) {
                transport
            } else {
                HttpTransport.PLATFORM
            }
            val connection = CronetTransports.openConnection(current.toURL(), connectionTransport)
            onConnection(connection)
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val isRangeRequest = rangeStartInclusive != null || rangeEndInclusive != null
            connection.setRequestProperty(
                "Accept-Encoding",
                if (isRangeRequest) "identity" else HttpCompressionSettings.current().acceptEncoding,
            )
            connection.setRequestProperty("User-Agent", "EnCodec-Android-Player/0.10.7")
            connection.setRequestProperty("Accept", accept)
            if (!ifNoneMatch.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            if (forceFreshConnection && connectionTransport == HttpTransport.PLATFORM) {
                connection.setRequestProperty("Connection", "close")
            }
            if (rangeStartInclusive != null || rangeEndInclusive != null) {
                connection.setRequestProperty(
                    "Range",
                    "bytes=${rangeStartInclusive ?: 0}-${rangeEndInclusive ?: ""}",
                )
            }
            if (noCache) {
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
            }

            val headerStarted = LiveDiagnostics.nowMs()
            val status = connection.responseCode
            if (diagnosticRequestId != null) LiveDiagnostics.info(
                "http headers id=$diagnosticRequestId scheme=${current.scheme} redirect=$redirectCount status=$status " +
                    "headerWaitMs=${LiveDiagnostics.nowMs() - headerStarted} " +
                    "contentEncoding=${connection.contentEncoding ?: "identity"} " +
                    "contentLength=${connection.contentLengthLong} " +
                    "contentRange=${connection.getHeaderField("Content-Range") ?: "-"} " +
                    "requestRange=${if (isRangeRequest) "bytes=${rangeStartInclusive ?: 0}-${rangeEndInclusive ?: ""}" else "-"} " +
                    "cacheStatus=${connection.getHeaderField("CF-Cache-Status")} " +
                    "age=${connection.getHeaderField("Age")} " +
                    // Cronet's URLConnection adapter does not promise support for
                    // getHeaderField(null), which can dereference the null name.
                    "protocol=${if (connectionTransport == HttpTransport.PLATFORM) {
                        connection.getHeaderField(null) ?: "unknown"
                    } else {
                        "cronet_metrics_pending"
                    }} transport=${CronetTransports.displayName(connectionTransport, current.toURL())}",
            )
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && ifNoneMatch != null) {
                onResponseHeaders()
                return ByteArrayInputStream(ByteArray(0))
            }
            if (status in 200..299) {
                onResponseHeaders()
                if (rangeStartInclusive != null && rangeStartInclusive > 0 && status != 206) {
                    connection.disconnect()
                    throw IOException("Server ignored the ECDC byte-range request")
                }
                val countedResponse = CountingInputStream(connection.inputStream) { count ->
                    wireBodyBytes?.set(count)
                }
                val response = HttpContentDecoding.decode(countedResponse, connection.contentEncoding)
                return if (disconnectOnClose) {
                    DisconnectingInputStream(response, connection)
                } else {
                    KeepAliveInputStream(response)
                }
            }
            if (status in 300..399 && redirectCount < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP redirect has no destination")
                connection.disconnect()
            current = NetworkProtocolSettings.applyScheme(current.resolve(location).toURL()).toURI()
                return@repeat
            }
            onResponseHeaders()
            val message = connection.responseMessage
            connection.disconnect()
            throw IOException("Server returned HTTP $status${message?.let { ": $it" } ?: ""}")
        }
        throw IOException("Too many HTTP redirects")
    }

    private class KeepAliveInputStream(source: InputStream) : FilterInputStream(source)

    /** Counts response entity bytes before gzip decoding, excluding network protocol overhead. */
    private class CountingInputStream(
        source: InputStream,
        private val onCount: (Long) -> Unit,
    ) : FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) record(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) record(read)
            return read
        }

        private fun record(bytes: Int) {
            count += bytes
            onCount(count)
        }
    }

    private fun transportForAttempt(attempt: Int): HttpTransport =
        CronetTransports.transportsForCurrentMode().let { it[attempt.coerceAtMost(it.lastIndex)] }

    private class DisconnectingInputStream(
        source: InputStream,
        private val connection: HttpURLConnection,
    ) : FilterInputStream(source) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal object HttpContentDecoding {
    fun decode(source: InputStream, contentEncoding: String?): InputStream {
        var decoded = source
        val encodings = contentEncoding.orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        for (encoding in encodings.asReversed()) {
            decoded = when (encoding.lowercase(Locale.ROOT)) {
                "identity" -> decoded
                "gzip", "x-gzip" -> GZIPInputStream(decoded)
                "br" -> BrotliInputStream(decoded)
                else -> throw IOException("Unsupported HTTP content encoding: $encoding")
            }
        }
        return decoded
    }
}
