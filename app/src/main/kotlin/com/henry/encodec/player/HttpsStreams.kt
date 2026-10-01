package com.henry.encodec.player

import cl.cuy.emergencyradio.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

internal object HttpsStreams {
    private const val MAX_REDIRECTS = 5
    private const val MAX_OPEN_ATTEMPTS = 3
    private val LIVE_CONNECT_TIMEOUT_MS = BuildConfig.LIVE_CONNECT_TIMEOUT_MS
    private val LIVE_READ_TIMEOUT_MS = BuildConfig.LIVE_READ_TIMEOUT_MS

    /** Finite playlist URLs remain HTTPS-only at their initial address. */
    fun open(url: String): InputStream {
        var lastError: IOException? = null
        repeat(MAX_OPEN_ATTEMPTS) { attempt ->
            try {
                return openOnce(url, noCache = false, allowInitialHttp = false)
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_OPEN_ATTEMPTS - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Could not open the stream")
    }

    fun openRange(url: String, startByte: Long): InputStream {
        require(startByte >= 0)
        return openOnce(
            url = url,
            noCache = false,
            allowInitialHttp = false,
            rangeStartInclusive = startByte,
            disconnectOnClose = true,
        )
    }

    /** Reads only enough of a static file to inspect its ECDC header. */
    fun readPrefix(url: String, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        var lastError: IOException? = null
        repeat(MAX_OPEN_ATTEMPTS) { attempt ->
            try {
                openOnce(
                    url = url,
                    noCache = false,
                    allowInitialHttp = false,
                    rangeEndInclusive = maxBytes - 1,
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
                    return output.toByteArray()
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_OPEN_ATTEMPTS - 1) Thread.sleep(500L * (attempt + 1))
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
        acceptGzip: Boolean = false,
    ): ByteArray =
        withContext(dispatcher) {
            require(maxBytes > 0)
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val cancellation = coroutineContext.job.invokeOnCompletion {
                activeConnection.getAndSet(null)?.disconnect()
            }
            try {
                var lastError: IOException? = null
                repeat(MAX_OPEN_ATTEMPTS) { attempt ->
                    coroutineContext.ensureActive()
                    val attemptStarted = LiveDiagnostics.nowMs()
                    try {
                        val input = openOnce(
                            url = url,
                            noCache = noCache,
                            allowInitialHttp = true,
                            onConnection = activeConnection::set,
                            connectTimeoutMs = LIVE_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = LIVE_READ_TIMEOUT_MS,
                            forceFreshConnection = attempt > 0,
                            acceptGzip = acceptGzip,
                        )
                        val headersMs = LiveDiagnostics.nowMs() - attemptStarted
                        input.use {
                            val declaredLength = activeConnection.get()?.contentLengthLong ?: -1L
                            if (declaredLength > maxBytes) {
                                throw IOException("HTTP response exceeds $maxBytes bytes")
                            }
                            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > maxBytes) {
                                    throw IOException("HTTP response exceeds $maxBytes bytes")
                                }
                                output.write(buffer, 0, read)
                            }
                            val result = output.toByteArray()
                            // A fully consumed response can return its socket to
                            // HttpURLConnection's keep-alive pool. This avoids a
                            // fresh DNS lookup and TLS handshake per segment.
                            activeConnection.set(null)
                            LiveDiagnostics.info(
                                "http complete attempt=${attempt + 1} headersMs=$headersMs " +
                                    "bodyMs=${LiveDiagnostics.nowMs() - attemptStarted - headersMs} " +
                                    "bytes=${result.size} fresh=${attempt > 0}",
                            )
                            return@withContext result
                        }
                    } catch (error: IOException) {
                        coroutineContext.ensureActive()
                        activeConnection.getAndSet(null)?.disconnect()
                        lastError = error
                        LiveDiagnostics.warn(
                            "http failed attempt=${attempt + 1} elapsedMs=" +
                                "${LiveDiagnostics.nowMs() - attemptStarted} " +
                                "error=${error::class.java.simpleName}: ${error.message}",
                        )
                        if (attempt < MAX_OPEN_ATTEMPTS - 1) delay(200L * (attempt + 1))
                    }
                }
                throw lastError ?: IOException("Could not download the response")
            } finally {
                cancellation.dispose()
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

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
        acceptGzip: Boolean = false,
    ): InputStream {
        var current = URI(url)
        require(
            current.scheme.equals("https", ignoreCase = true) ||
                (allowInitialHttp && current.scheme.equals("http", ignoreCase = true)),
        ) { if (allowInitialHttp) "Only HTTP and HTTPS URLs are supported" else "Only HTTPS URLs are supported" }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(
                current.scheme.equals("https", ignoreCase = true) ||
                    current.scheme.equals("http", ignoreCase = true),
            ) { "Redirect uses an unsupported protocol" }
            val connection = current.toURL().openConnection() as HttpURLConnection
            onConnection(connection)
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", if (acceptGzip) "gzip" else "identity")
            connection.setRequestProperty("User-Agent", "EnCodec-Android-Player/0.10.7")
            if (forceFreshConnection) connection.setRequestProperty("Connection", "close")
            if (rangeStartInclusive != null || rangeEndInclusive != null) {
                connection.setRequestProperty(
                    "Range",
                    "bytes=${rangeStartInclusive ?: 0}-${rangeEndInclusive ?: ""}",
                )
            }
            if (noCache) {
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("Pragma", "no-cache")
            }

            val status = connection.responseCode
            if (status in 200..299) {
                if (rangeStartInclusive != null && rangeStartInclusive > 0 && status != 206) {
                    connection.disconnect()
                    throw IOException("Server ignored the ECDC byte-range request")
                }
                val response = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
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
                current = current.resolve(location)
                return@repeat
            }
            val message = connection.responseMessage
            connection.disconnect()
            throw IOException("Server returned HTTP $status${message?.let { ": $it" } ?: ""}")
        }
        throw IOException("Too many HTTP redirects")
    }

    private class KeepAliveInputStream(source: InputStream) : FilterInputStream(source)

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
