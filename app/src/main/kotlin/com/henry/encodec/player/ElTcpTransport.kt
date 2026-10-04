package com.henry.encodec.player

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

data class ElTcpEndpoint(val host: String, val port: Int) {
    companion object {
        fun parse(value: String?): ElTcpEndpoint? {
            val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val uri = runCatching { java.net.URI("tcp://$raw") }.getOrNull() ?: return null
            val host = uri.host?.takeIf(String::isNotBlank) ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            if (uri.rawPath.orEmpty().isNotEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
            return ElTcpEndpoint(host, port)
        }
    }
}

internal class ElTcpConnectException(message: String, cause: IOException) : IOException(message, cause)
private class ElTcpReplyTimeoutException(cause: SocketTimeoutException) : IOException(cause)
internal class ElTcpSegmentUnavailableException(
    val sequence: Long,
    val freshManifest: ByteArray,
) : IOException("ELTCP absolute segment is stale or unavailable")
internal class ElTcpFetchUnsupportedException : IOException("ELTCP server does not support absolute-sequence fetch")

internal sealed interface ElTcpFetchResult {
    data class Ready(val bytes: ByteArray, val crc32c: Long, val discontinuity: Boolean) : ElTcpFetchResult
    data object NotPublished : ElTcpFetchResult
}

internal object TcpTransportSettings {
    private const val PREFS = "emergency_radio_settings"
    private const val KEY = "custom_tcp_transport_enabled"
    @Volatile private var selected = true

    fun initialize(context: Context) {
        selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun current(): Boolean = selected

    fun set(context: Context, enabled: Boolean) {
        selected = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        LiveDiagnostics.info("custom TCP transport enabled=$enabled")
    }
}

/** ELTCP v1 client: one request at a time on a persistent, uncompressed TCP socket. */
internal class ElTcpSession(private val endpoint: ElTcpEndpoint) : Closeable {
    private val lock = Any()
    private val socketRef = AtomicReference<Socket?>(null)
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ondabaja-eltcp").apply { isDaemon = true }
    }
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var initialized = false
    private var lastManifest: ByteArray? = null
    private var lastHeartbeatAtMs = 0L
    // A server heartbeat during a manifest poll means we can use a shorter
    // inactivity timeout than the 15-second no-heartbeat long-poll limit.
    private var serverHeartbeatsObserved = false
    @Volatile private var legacySegmentRequests = false
    @Volatile private var connectedOnce = false
    // Pin DNS results for this playback session so reconnects don't depend on
    // another resolver round trip (or a temporarily unavailable resolver).
    private var resolvedAddresses: List<InetAddress>? = null

    suspend fun requestManifest(): ByteArray = cancellableIo {
        connectIfNeeded()
        if (!initialized) {
            writeCommand(CMD_INIT)
            output!!.flush()
            when (val reply = readReply()) {
                is Reply.Manifest -> {
                    initialized = true
                    lastManifest = reply.payload
                    LiveDiagnostics.info("tcp initialized host=${endpoint.host} port=${endpoint.port} manifestBytes=${reply.payload.size}")
                    reply.payload
                }
                is Reply.Status -> throw statusError(reply.type)
                else -> throw IOException("ELTCP init did not return a manifest")
            }
        } else {
            probeIfDue()
            val pollStarted = LiveDiagnostics.nowMs()
            val manifestReadTimeoutMs = if (serverHeartbeatsObserved) {
                HEARTBEAT_MANIFEST_READ_TIMEOUT_MS
            } else {
                MANIFEST_READ_TIMEOUT_MS
            }
            setReadTimeout(manifestReadTimeoutMs)
            LiveDiagnostics.info(
                "tcp manifest request command=m host=${endpoint.host} port=${endpoint.port} " +
                    "readTimeoutMs=$manifestReadTimeoutMs " +
                    "heartbeatMode=${if (serverHeartbeatsObserved) "active" else "not_observed"}",
            )
            writeCommand(CMD_MANIFEST)
            output!!.flush()
            while (true) {
                val reply = try {
                    readReply()
                } catch (_: ElTcpReplyTimeoutException) {
                    if (serverHeartbeatsObserved) {
                        LiveDiagnostics.warn(
                            "tcp manifest heartbeat inactivity timeout idleTimeoutMs=$manifestReadTimeoutMs; " +
                                "reconnecting without probing the pending manifest",
                        )
                        throw SocketTimeoutException(
                            "No server heartbeat or manifest response for ${manifestReadTimeoutMs}ms",
                        )
                    }
                    // The server's manifest long-poll is capped at 15 seconds,
                    // so the 18-second client timeout is a safe request boundary
                    // at which an echo probe can test the existing socket.
                    when (val probeReply = probeAfterManifestTimeout(pollStarted)) {
                        Reply.Heartbeat -> {
                            writeCommand(CMD_MANIFEST)
                            output!!.flush()
                            continue
                        }
                        else -> probeReply
                    }
                }
                when (reply) {
                    is Reply.Manifest -> {
                        lastManifest = reply.payload
                        LiveDiagnostics.info("tcp manifest updated bytes=${reply.payload.size} waitMs=${LiveDiagnostics.nowMs() - pollStarted}")
                        return@cancellableIo reply.payload
                    }
                    Reply.Heartbeat -> {
                        lastHeartbeatAtMs = LiveDiagnostics.nowMs()
                        serverHeartbeatsObserved = true
                        LiveDiagnostics.info("tcp heartbeat received while waiting_for_manifest elapsedMs=${lastHeartbeatAtMs - pollStarted}")
                    }
                    is Reply.Unchanged -> {
                        LiveDiagnostics.info("tcp manifest unchanged waitMs=${LiveDiagnostics.nowMs() - pollStarted}")
                        return@cancellableIo lastManifest
                            ?: throw IOException("ELTCP returned unchanged before sending a manifest")
                    }
                    is Reply.Status -> throw statusError(reply.type)
                    is Reply.Segment -> throw IOException("ELTCP returned a segment to a manifest request")
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }
    }

    suspend fun fetchSegment(manifest: LiveManifest, segment: LiveSegmentInfo): ByteArray = cancellableIo {
        check(initialized) { "ELTCP connection is not initialized" }
        val index = segment.sequence - manifest.mediaSequence
        if (index < 0 || index >= manifest.segments.size || manifest.segments[index.toInt()].sequence != segment.sequence) {
            throw IOException("ELTCP segment is outside the active manifest")
        }
        probeIfDue()
        val started = LiveDiagnostics.nowMs()
        setReadTimeout(SEGMENT_READ_TIMEOUT_MS)
        LiveDiagnostics.info(
            "tcp segment request seq=${segment.sequence} index=$index expectedBytes=${segment.byteLength} " +
                "readTimeoutMs=$SEGMENT_READ_TIMEOUT_MS",
        )
        sendSegmentRequest(index)
        var retries = 0
        var sameSocketRetries = 0
        while (true) {
            val reply = try {
                readReply()
            } catch (_: ElTcpReplyTimeoutException) {
                if (sameSocketRetries >= MAX_SAME_SOCKET_SEGMENT_RETRIES) throw SocketTimeoutException(
                    "No segment response after $sameSocketRetries same-socket retries",
                )
                when (val recovered = probeAfterSegmentTimeout(segment.sequence)) {
                    Reply.Heartbeat -> {
                        sameSocketRetries++
                        LiveDiagnostics.warn(
                            "tcp segment retry on same socket seq=${segment.sequence} index=$index " +
                                "retry=$sameSocketRetries maxRetries=$MAX_SAME_SOCKET_SEGMENT_RETRIES",
                        )
                        setReadTimeout(SEGMENT_READ_TIMEOUT_MS)
                        sendSegmentRequest(index)
                        continue
                    }
                    else -> recovered
                }
            }
            when (reply) {
                is Reply.Segment -> {
                    if (reply.payload.size != segment.byteLength) {
                        throw IOException("ELTCP segment ${segment.sequence} length ${reply.payload.size}, expected ${segment.byteLength}")
                    }
                    if (segment.crc32c == null || crc32c(reply.payload) != segment.crc32c) {
                        if (segment.crc32c == null) throw IOException("ELTCP manifest has no CRC32C for segment ${segment.sequence}")
                        if (retries >= MAX_CRC_RETRIES) throw IOException("ELTCP CRC32C retries exhausted for segment ${segment.sequence}")
                        retries++
                        LiveDiagnostics.warn("tcp segment checksum mismatch seq=${segment.sequence} retry=$retries")
                        writeCommand(CMD_CRC_ERROR)
                        output!!.flush()
                        continue
                    }
                    LiveDiagnostics.info(
                        "tcp segment complete seq=${segment.sequence} index=$index bytes=${reply.payload.size} " +
                            "crc32c=valid retries=$retries elapsedMs=${LiveDiagnostics.nowMs() - started}",
                    )
                    return@cancellableIo reply.payload
                }
                is Reply.Status -> {
                    if (reply.type == RESPONSE_GONE) {
                        throw IOException("ELTCP segment ${segment.sequence} expired or is unavailable")
                    }
                    throw statusError(reply.type)
                }
                else -> throw IOException("ELTCP returned an unexpected response for a segment")
            }
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    /** Fetches one absolute sequence with `f`; it does not depend on manifest-relative indexes. */
    suspend fun fetchSequence(sequence: Long, expectedByteLength: Int? = null): ElTcpFetchResult = cancellableIo {
        require(sequence >= 0) { "ELTCP sequence must be an unsigned uint64" }
        check(initialized) { "ELTCP connection is not initialized" }
        check(!legacySegmentRequests) { "ELTCP absolute-sequence fetch is disabled for this server" }

        val started = LiveDiagnostics.nowMs()
        var crcRetries = 0
        var previousMetadata: FetchMetadata? = null
        try {
            setReadTimeout(SEGMENT_READ_TIMEOUT_MS)
            writeCommand(CMD_FETCH)
            writeVarint(sequence)
            output?.flush() ?: throw IOException("ELTCP output is closed")
            LiveDiagnostics.info(
                "tcp fetch request seq=$sequence expectedBytes=${expectedByteLength ?: "unknown"} " +
                    "readTimeoutMs=$SEGMENT_READ_TIMEOUT_MS",
            )

            while (true) {
                when (val reply = readFetchReply()) {
                    is FetchReply.Ready -> {
                        if (reply.bytes.isEmpty() || reply.bytes.size > LiveManifestParser.MAX_SEGMENT_BYTES) {
                            throw IOException("ELTCP S frame has invalid ECDC length ${reply.bytes.size}")
                        }
                        if (expectedByteLength != null && reply.bytes.size != expectedByteLength) {
                            throw IOException(
                                "ELTCP S frame length ${reply.bytes.size} disagrees with manifest length $expectedByteLength",
                            )
                        }
                        if (reply.flags and FLAGS_RESERVED_MASK != 0) {
                            throw IOException("ELTCP S frame has reserved flag bits set: ${reply.flags}")
                        }
                        val actualCrc = crc32c(reply.bytes)
                        if (actualCrc != reply.crc32c) {
                            if (previousMetadata != null && previousMetadata != reply.metadata) {
                                throw IOException("ELTCP CRC retry changed S-frame metadata for sequence $sequence")
                            }
                            if (crcRetries >= MAX_CRC_RETRIES) {
                                throw IOException("ELTCP CRC32C retries exhausted for sequence $sequence")
                            }
                            previousMetadata = reply.metadata
                            crcRetries++
                            LiveDiagnostics.warn(
                                "tcp fetch checksum mismatch seq=$sequence retry=$crcRetries " +
                                    "bytes=${reply.bytes.size}; requesting same S frame again",
                            )
                            // The complete S frame has been consumed and failed CRC validation.
                            writeCommand(CMD_CRC_ERROR)
                            output?.flush() ?: throw IOException("ELTCP output is closed")
                            continue
                        }
                        if (previousMetadata != null && previousMetadata != reply.metadata) {
                            throw IOException("ELTCP CRC retry changed S-frame metadata for sequence $sequence")
                        }
                        LiveDiagnostics.info(
                            "tcp fetch complete seq=$sequence bytes=${reply.bytes.size} " +
                                "crc32c=valid retries=$crcRetries discontinuity=${reply.flags and FLAG_DISCONTINUITY != 0} " +
                                "elapsedMs=${LiveDiagnostics.nowMs() - started}",
                        )
                        return@cancellableIo ElTcpFetchResult.Ready(
                            bytes = reply.bytes,
                            crc32c = reply.crc32c,
                            discontinuity = reply.flags and FLAG_DISCONTINUITY != 0,
                        )
                    }
                    FetchReply.NotPublished -> {
                        if (crcRetries > 0) throw IOException("ELTCP did not resend the S frame after e for sequence $sequence")
                        LiveDiagnostics.info("tcp fetch not_published seq=$sequence retryDelayMs=$FETCH_NOT_PUBLISHED_DELAY_MS")
                        return@cancellableIo ElTcpFetchResult.NotPublished
                    }
                    FetchReply.Gone -> {
                        if (crcRetries > 0) throw IOException("ELTCP returned g instead of retrying S after e for sequence $sequence")
                        val manifest = requestInitManifestOnCurrentConnection()
                        LiveDiagnostics.warn("tcp fetch stale seq=$sequence refreshedWithInit=true bytes=${manifest.size}")
                        throw ElTcpSegmentUnavailableException(sequence, manifest)
                    }
                    FetchReply.Unsupported -> {
                        if (crcRetries > 0) throw IOException("ELTCP returned p instead of retrying S after e for sequence $sequence")
                        legacySegmentRequests = true
                        closeSocket()
                        LiveDiagnostics.warn("tcp fetch unsupported; reconnecting and switching to legacy m/s requests")
                        throw ElTcpFetchUnsupportedException()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") ElTcpFetchResult.NotPublished
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ElTcpSegmentUnavailableException) {
            throw error
        } catch (error: ElTcpFetchUnsupportedException) {
            throw error
        } catch (error: IOException) {
            // A partial or malformed frame leaves response framing uncertain.
            resetConnection()
            throw error
        }
    }

    fun supportsAbsoluteSequenceFetch(): Boolean = !legacySegmentRequests

    private fun requestInitManifestOnCurrentConnection(): ByteArray {
        setReadTimeout(MANIFEST_READ_TIMEOUT_MS)
        writeCommand(CMD_INIT)
        output?.flush() ?: throw IOException("ELTCP output is closed")
        while (true) {
            when (val reply = readReply()) {
                is Reply.Manifest -> {
                    initialized = true
                    lastManifest = reply.payload
                    return reply.payload
                }
                Reply.Heartbeat -> {
                    serverHeartbeatsObserved = true
                    lastHeartbeatAtMs = LiveDiagnostics.nowMs()
                    LiveDiagnostics.info("tcp heartbeat received while refreshing_manifest")
                }
                is Reply.Status -> throw statusError(reply.type)
                else -> throw IOException("ELTCP i refresh did not return a manifest")
            }
        }
    }

    private fun connectIfNeeded() {
        val current = socketRef.get()
        if (current?.isConnected == true && !current.isClosed) return
        closeSocket()
        val addresses = resolvedAddresses ?: run {
            val dnsStarted = LiveDiagnostics.nowMs()
            val resolved = try {
                InetAddress.getAllByName(endpoint.host).toList()
            } catch (error: IOException) {
                LiveDiagnostics.warn(
                    "tcp DNS resolution failed host=${endpoint.host} elapsedMs=${LiveDiagnostics.nowMs() - dnsStarted} " +
                        "error=${error.javaClass.simpleName} message=${error.message}",
                )
                throw ElTcpConnectException("Unable to resolve TCP endpoint ${endpoint.host}", error)
            }
            if (resolved.isEmpty()) {
                throw ElTcpConnectException(
                    "No addresses resolved for ${endpoint.host}",
                    IOException("DNS returned no addresses"),
                )
            }
            resolvedAddresses = resolved
            LiveDiagnostics.info(
                "tcp DNS resolved host=${endpoint.host} addresses=${resolved.joinToString(",") { it.hostAddress }} " +
                    "elapsedMs=${LiveDiagnostics.nowMs() - dnsStarted} cachedForSession=true",
            )
            resolved
        }

        var lastError: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val connectTimeoutMs = if (connectedOnce) RECONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(address, endpoint.port), connectTimeoutMs)
                socket.soTimeout = MANIFEST_READ_TIMEOUT_MS
                connectedOnce = true
                socketRef.set(socket)
                input = BufferedInputStream(socket.getInputStream())
                output = BufferedOutputStream(socket.getOutputStream())
                LiveDiagnostics.info(
                    "tcp connected host=${endpoint.host} address=${address.hostAddress} port=${endpoint.port} " +
                        "connectTimeoutMs=$connectTimeoutMs manifestReadTimeoutMs=$MANIFEST_READ_TIMEOUT_MS " +
                        "segmentReadTimeoutMs=$SEGMENT_READ_TIMEOUT_MS compression=none",
                )
                return
            } catch (error: IOException) {
                lastError = error
                runCatching { socket.close() }
                LiveDiagnostics.warn(
                    "tcp connect failed host=${endpoint.host} address=${address.hostAddress} port=${endpoint.port} " +
                        "error=${error.javaClass.simpleName} message=${error.message}",
                )
            }
        }
        val cause = lastError ?: IOException("No address accepted a TCP connection")
        throw ElTcpConnectException(
            "Unable to connect to ${endpoint.host}:${endpoint.port}: ${cause.message}",
            cause,
        )
    }

    private fun writeCommand(command: Byte) {
        output?.write(command.toInt()) ?: throw IOException("ELTCP output is closed")
    }

    private fun sendSegmentRequest(index: Long) {
        writeCommand(CMD_SEGMENT)
        writeVarint(index)
        output?.flush() ?: throw IOException("ELTCP output is closed")
    }

    private fun setReadTimeout(timeoutMs: Int) {
        val socket = socketRef.get() ?: throw IOException("ELTCP socket is not connected")
        socket.soTimeout = timeoutMs
    }

    /** Send a one-byte echo probe only between request/response exchanges. */
    private fun probeIfDue() {
        if (!initialized) return
        val now = LiveDiagnostics.nowMs()
        if (lastHeartbeatAtMs != 0L && now - lastHeartbeatAtMs < HEARTBEAT_PROBE_INTERVAL_MS) return
        LiveDiagnostics.info(
            "tcp heartbeat probe start host=${endpoint.host} port=${endpoint.port} " +
                "intervalMs=$HEARTBEAT_PROBE_INTERVAL_MS",
        )
        setReadTimeout(HEARTBEAT_READ_TIMEOUT_MS)
        writeCommand(CMD_HEARTBEAT)
        output?.flush() ?: throw IOException("ELTCP output is closed")
        when (readReply()) {
            Reply.Heartbeat -> {
                lastHeartbeatAtMs = LiveDiagnostics.nowMs()
                LiveDiagnostics.info("tcp heartbeat probe complete elapsedMs=${lastHeartbeatAtMs - now}")
            }
            else -> throw IOException("ELTCP heartbeat probe did not receive the one-byte h echo")
        }
    }

    /**
     * A timed-out manifest poll is longer than the server's maximum poll wait.
     * Probe at that boundary before discarding a socket that may still be alive.
     * A late m/n response is also a valid completion of the old poll.
     */
    private fun probeAfterManifestTimeout(pollStarted: Long): Reply {
        LiveDiagnostics.warn(
            "tcp manifest read timeout elapsedMs=${LiveDiagnostics.nowMs() - pollStarted}; " +
                "probing existing socket before reconnect",
        )
        var probesSent = 0
        var probesReceived = 0
        var completedManifest: Reply? = null
        var sendNextProbe = true

        while (true) {
            if (sendNextProbe && probesSent < MAX_RECOVERY_ECHO_PROBES) {
                writeCommand(CMD_HEARTBEAT)
                output?.flush() ?: throw IOException("ELTCP output is closed")
                probesSent++
                sendNextProbe = false
                LiveDiagnostics.warn(
                    "tcp manifest recovery echo probe attempt=$probesSent/$MAX_RECOVERY_ECHO_PROBES",
                )
            }

            setReadTimeout(HEARTBEAT_READ_TIMEOUT_MS)
            val reply = try {
                readReply()
            } catch (_: ElTcpReplyTimeoutException) {
                if (probesSent >= MAX_RECOVERY_ECHO_PROBES) {
                    throw SocketTimeoutException("ELTCP manifest recovery echo probes timed out")
                }
                sendNextProbe = true
                continue
            }

            when (reply) {
                Reply.Heartbeat -> {
                    lastHeartbeatAtMs = LiveDiagnostics.nowMs()
                    if (completedManifest == null) {
                        // Heartbeats already in flight from the timed-out m
                        // poll precede its final m/n response on this socket.
                        LiveDiagnostics.info("tcp heartbeat received while draining timed_out_manifest")
                    } else {
                        probesReceived++
                        LiveDiagnostics.info(
                            "tcp manifest recovery echo received=${probesReceived}/$probesSent",
                        )
                        if (probesReceived == probesSent) {
                            setReadTimeout(MANIFEST_READ_TIMEOUT_MS)
                            LiveDiagnostics.info(
                                "tcp manifest recovery socket_alive=true sameSocket=true " +
                                    "completed=${completedManifest.javaClass.simpleName}",
                            )
                            return completedManifest
                        }
                    }
                }
                is Reply.Manifest -> {
                    if (completedManifest != null) throw IOException("ELTCP returned multiple manifests during recovery")
                    lastManifest = reply.payload
                    completedManifest = reply
                    LiveDiagnostics.info("tcp manifest late response after timeout bytes=${reply.payload.size}")
                }
                Reply.Unchanged -> {
                    if (completedManifest != null) throw IOException("ELTCP returned multiple manifest statuses during recovery")
                    completedManifest = reply
                    LiveDiagnostics.info("tcp manifest late unchanged response after timeout")
                }
                is Reply.Status -> throw statusError(reply.type)
                is Reply.Segment -> throw IOException("ELTCP returned a segment during manifest recovery")
            }

            if (completedManifest != null && probesReceived == probesSent) {
                setReadTimeout(MANIFEST_READ_TIMEOUT_MS)
                LiveDiagnostics.info("tcp manifest recovery complete sameSocket=true")
                return completedManifest
            }
        }
    }

    /**
     * After an s-response timeout, use h as a synchronization fence. The
     * server processes requests in order, so a delayed s/g response precedes
     * the echo. If the echo arrives first, the previous s has no response and
     * the caller can safely retry it on this socket.
     */
    private fun probeAfterSegmentTimeout(sequence: Long): Reply {
        var probesSent = 0
        var probesReceived = 0
        var responseBeforeEcho: Reply? = null
        var sendNextProbe = true

        while (true) {
            if (sendNextProbe && probesSent < MAX_RECOVERY_ECHO_PROBES) {
                writeCommand(CMD_HEARTBEAT)
                output?.flush() ?: throw IOException("ELTCP output is closed")
                probesSent++
                sendNextProbe = false
                LiveDiagnostics.warn(
                    "tcp segment timeout echo probe seq=$sequence attempt=$probesSent/$MAX_RECOVERY_ECHO_PROBES",
                )
            }

            setReadTimeout(HEARTBEAT_READ_TIMEOUT_MS)
            val reply = try {
                readReply()
            } catch (_: ElTcpReplyTimeoutException) {
                if (probesSent >= MAX_RECOVERY_ECHO_PROBES) {
                    throw SocketTimeoutException(
                        "ELTCP echo probe failed after $MAX_RECOVERY_ECHO_PROBES attempts for segment $sequence",
                    )
                }
                sendNextProbe = true
                continue
            }

            when (reply) {
                Reply.Heartbeat -> {
                    probesReceived++
                    lastHeartbeatAtMs = LiveDiagnostics.nowMs()
                    LiveDiagnostics.info(
                        "tcp segment recovery echo received seq=$sequence received=$probesReceived/$probesSent",
                    )
                    if (probesReceived == probesSent) {
                        setReadTimeout(SEGMENT_READ_TIMEOUT_MS)
                        val pending = responseBeforeEcho
                        if (pending != null) {
                            LiveDiagnostics.info(
                                "tcp delayed segment response recovered seq=$sequence " +
                                    "response=${pending.javaClass.simpleName} sameSocket=true",
                            )
                            return pending
                        }
                        LiveDiagnostics.info(
                            "tcp segment recovery socket_alive=true seq=$sequence " +
                                "retry_request=true sameSocket=true",
                        )
                        return Reply.Heartbeat
                    }
                }
                is Reply.Segment, is Reply.Status -> {
                    if (responseBeforeEcho != null) {
                        throw IOException("ELTCP sent multiple responses for segment $sequence before probe echoes")
                    }
                    responseBeforeEcho = reply
                    LiveDiagnostics.info(
                        "tcp segment response arrived before echo seq=$sequence " +
                            "response=${reply.javaClass.simpleName}; draining probe echoes",
                    )
                }
                else -> throw IOException("ELTCP returned an unexpected response during segment recovery")
            }

            if (probesReceived < probesSent && probesSent < MAX_RECOVERY_ECHO_PROBES) {
                // A previous echo may be delayed. Wait once more before
                // sending another probe, so completed echoes are not left in
                // the stream to be mistaken for the next segment response.
                sendNextProbe = false
            }
        }
    }

    private fun readReply(): Reply {
        val stream = input ?: throw IOException("ELTCP input is closed")
        val type = try {
            stream.read()
        } catch (timeout: SocketTimeoutException) {
            // Only a timeout before the response starts is a safe point to
            // probe. Timeouts while reading a frame must not inject h into it.
            throw ElTcpReplyTimeoutException(timeout)
        }
        if (type < 0) throw EOFException("ELTCP server closed the connection")
        return when (type.toByte()) {
            RESPONSE_MANIFEST, RESPONSE_SEGMENT -> {
                val length = readVarint(stream)
                if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("ELTCP frame exceeds $MAX_FRAME_BYTES bytes")
                val payload = ByteArray(length.toInt())
                var offset = 0
                while (offset < payload.size) {
                    val count = stream.read(payload, offset, payload.size - offset)
                    if (count < 0) throw EOFException("Truncated Eltcp frame")
                    offset += count
                }
                if (type.toByte() == RESPONSE_MANIFEST) Reply.Manifest(payload) else Reply.Segment(payload)
            }
            RESPONSE_UNCHANGED -> Reply.Unchanged
            RESPONSE_HEARTBEAT -> Reply.Heartbeat
            RESPONSE_GONE, RESPONSE_BUSY, RESPONSE_PROTOCOL, RESPONSE_RETRY_LIMIT -> Reply.Status(type.toByte())
            else -> throw IOException("Unknown ELTCP response byte $type")
        }
    }

    private fun statusError(type: Byte): IOException = when (type) {
        RESPONSE_GONE -> IOException("ELTCP segment expired or is unavailable")
        RESPONSE_BUSY -> IOException("ELTCP server is at client capacity")
        RESPONSE_PROTOCOL -> IOException("ELTCP protocol error")
        RESPONSE_RETRY_LIMIT -> IOException("ELTCP CRC retry limit exhausted")
        else -> IOException("Unexpected ELTCP status ${type.toInt().toChar()}")
    }

    private fun writeVarint(value: Long) {
        if (value < 0) throw IOException("ELTCP integer is outside the supported uint64 range")
        var remaining = value
        val stream = output ?: throw IOException("ELTCP output is closed")
        while (remaining > 0x7f) {
            stream.write(((remaining.toInt() and 0x7f) or 0x80))
            remaining = remaining ushr 7
        }
        stream.write(remaining.toInt())
    }

    private fun readVarint(stream: BufferedInputStream): Long {
        var value = 0L
        var shift = 0
        var count = 0
        while (count < 10) {
            val byte = stream.read()
            if (byte < 0) throw EOFException("Truncated ELTCP length varint")
            count++
            if (count == 10 && byte > 1) throw IOException("ELTCP varint overflows uint64")
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) {
                if (count > 1 && byte and 0x7f == 0) throw IOException("Noncanonical ELTCP varint")
                return value
            }
            shift += 7
        }
        throw IOException("ELTCP varint exceeds 10 bytes")
    }

    private fun crc32c(bytes: ByteArray): Long {
        var state = -1
        for (byte in bytes) state = (state ushr 8) xor CRC_TABLE[(state xor byte.toInt()) and 0xff]
        return state.inv().toLong() and 0xffff_ffffL
    }

    private fun closeSocket() {
        runCatching { socketRef.getAndSet(null)?.close() }
        input = null
        output = null
        initialized = false
        lastManifest = null
        lastHeartbeatAtMs = 0L
    }

    /** Drop a broken socket but retain this session's resolved DNS addresses. */
    fun resetConnection() {
        closeSocket()
    }

    fun hasConnected(): Boolean = connectedOnce

    override fun close() {
        closeSocket()
        io.shutdownNow()
    }

    private suspend fun <T> cancellableIo(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val futureRef = AtomicReference<Future<*>?>(null)
        futureRef.set(io.submit {
            try {
                val result = synchronized(lock) { block() }
                continuation.resumeIfActive(result)
            } catch (error: Throwable) {
                if (error is IOException && error !is ElTcpSegmentUnavailableException) resetConnection()
                continuation.resumeExceptionIfActive(error)
            }
        })
        continuation.invokeOnCancellation {
            close()
            futureRef.get()?.cancel(true)
        }
    }

    private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }

    private fun CancellableContinuation<*>.resumeExceptionIfActive(error: Throwable) {
        if (isActive) resumeWithException(error)
    }

    private sealed interface Reply {
        data class Manifest(val payload: ByteArray) : Reply
        data class Segment(val payload: ByteArray) : Reply
        data class Status(val type: Byte) : Reply
        data object Unchanged : Reply
        data object Heartbeat : Reply
    }

    private data class FetchMetadata(val length: Int, val crc32c: Long, val flags: Int)

    private sealed interface FetchReply {
        data class Ready(val bytes: ByteArray, val metadata: FetchMetadata) : FetchReply {
            val crc32c: Long get() = metadata.crc32c
            val flags: Int get() = metadata.flags
        }
        data object NotPublished : FetchReply
        data object Gone : FetchReply
        data object Unsupported : FetchReply
    }

    private fun readFetchReply(): FetchReply {
        val stream = input ?: throw IOException("ELTCP input is closed")
        val status = stream.read()
        if (status < 0) throw EOFException("ELTCP server closed the connection during f request")
        return when (status.toByte()) {
            RESPONSE_FETCH_SEGMENT -> {
                val length = readFetchLength(stream)
                if (length !in 1..LiveManifestParser.MAX_SEGMENT_BYTES) {
                    throw IOException("ELTCP S frame has invalid ECDC length $length")
                }
                val fixed = readExactly(stream, 5)
                val crc = ((fixed[0].toLong() and 0xff) shl 24) or
                    ((fixed[1].toLong() and 0xff) shl 16) or
                    ((fixed[2].toLong() and 0xff) shl 8) or
                    (fixed[3].toLong() and 0xff)
                val flags = fixed[4].toInt() and 0xff
                val bytes = readExactly(stream, length)
                FetchReply.Ready(bytes, FetchMetadata(length, crc, flags))
            }
            RESPONSE_NOT_PUBLISHED -> FetchReply.NotPublished
            RESPONSE_GONE -> FetchReply.Gone
            RESPONSE_FETCH_UNSUPPORTED -> FetchReply.Unsupported
            else -> throw IOException("Unexpected ELTCP f response byte $status")
        }
    }

    private fun readFetchLength(stream: BufferedInputStream): Int {
        var value = 0L
        var shift = 0
        var count = 0
        while (count < MAX_FETCH_LENGTH_VARINT_BYTES) {
            val byte = stream.read()
            if (byte < 0) throw EOFException("Truncated ELTCP S-frame length")
            count++
            if (count == MAX_FETCH_LENGTH_VARINT_BYTES && byte and 0xf0 != 0) {
                throw IOException("ELTCP S-frame length varint overflows uint32")
            }
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) {
                if (count > 1 && byte and 0x7f == 0) {
                    throw IOException("Noncanonical ELTCP S-frame length varint")
                }
                if (value > LiveManifestParser.MAX_SEGMENT_BYTES) {
                    throw IOException("ELTCP S frame exceeds ${LiveManifestParser.MAX_SEGMENT_BYTES} bytes")
                }
                return value.toInt()
            }
            shift += 7
        }
        throw IOException("ELTCP S-frame length varint exceeds five bytes")
    }

    private fun readExactly(stream: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = stream.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("Truncated ELTCP frame: received $offset of $length bytes")
            offset += count
        }
        return bytes
    }

    private companion object {
        const val INITIAL_CONNECT_TIMEOUT_MS = 1_500
        const val RECONNECT_TIMEOUT_MS = 2_000
        // The server may long-poll for 15 seconds; allow network margin.
        const val MANIFEST_READ_TIMEOUT_MS = 18_000
        // With periodic heartbeats observed, two missed 3-second heartbeats
        // plus margin indicate a stalled connection while a manifest is pending.
        const val HEARTBEAT_MANIFEST_READ_TIMEOUT_MS = 7_500
        const val SEGMENT_READ_TIMEOUT_MS = 3_000
        const val HEARTBEAT_READ_TIMEOUT_MS = 1_500
        const val HEARTBEAT_PROBE_INTERVAL_MS = 30_000L
        const val MAX_RECOVERY_ECHO_PROBES = 3
        const val MAX_SAME_SOCKET_SEGMENT_RETRIES = 2
        const val MAX_FRAME_BYTES = 1 shl 20
        const val MAX_CRC_RETRIES = 2
        const val CMD_INIT = 'i'.code.toByte()
        const val CMD_MANIFEST = 'm'.code.toByte()
        const val CMD_SEGMENT = 's'.code.toByte()
        const val CMD_FETCH = 'f'.code.toByte()
        const val CMD_CRC_ERROR = 'e'.code.toByte()
        const val CMD_HEARTBEAT = 'h'.code.toByte()
        const val RESPONSE_MANIFEST = 'm'.code.toByte()
        const val RESPONSE_SEGMENT = 's'.code.toByte()
        const val RESPONSE_FETCH_SEGMENT = 'S'.code.toByte()
        const val RESPONSE_NOT_PUBLISHED = 'n'.code.toByte()
        const val RESPONSE_FETCH_UNSUPPORTED = 'p'.code.toByte()
        const val RESPONSE_UNCHANGED = 'n'.code.toByte()
        const val RESPONSE_HEARTBEAT = 'h'.code.toByte()
        const val RESPONSE_GONE = 'g'.code.toByte()
        const val RESPONSE_BUSY = 'b'.code.toByte()
        const val RESPONSE_PROTOCOL = 'p'.code.toByte()
        const val RESPONSE_RETRY_LIMIT = 'r'.code.toByte()
        const val FLAG_DISCONTINUITY = 0x01
        const val FLAGS_RESERVED_MASK = 0xfe
        const val MAX_FETCH_LENGTH_VARINT_BYTES = 5
        const val FETCH_NOT_PUBLISHED_DELAY_MS = 1_000L
        val CRC_TABLE = IntArray(256) { entry ->
            var value = entry
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0x82f63b78.toInt() else value ushr 1
            }
            value
        }
    }
}
