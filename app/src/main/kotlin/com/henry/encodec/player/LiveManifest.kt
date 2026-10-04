package com.henry.encodec.player

import cl.cuy.emergencyradio.BuildConfig
import android.util.Log
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

internal object LiveDiagnostics {
    @Volatile var enabled: Boolean = false
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024
    private const val LOG_FILE_NAME = "ondabaja-network-diagnostics.log"
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .withZone(ZoneId.systemDefault())
    private var logFile: File? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun configure(context: Context, shouldEnable: Boolean) {
        if (shouldEnable == enabled) return
        val appContext = context.applicationContext
        if (shouldEnable) {
            logFile = File(appContext.filesDir, LOG_FILE_NAME)
            enabled = true
            info("diagnostics enabled file=files/$LOG_FILE_NAME version=${BuildConfig.VERSION_NAME} " +
                "versionCode=${BuildConfig.VERSION_CODE} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} " +
                "connectTimeoutMs=${BuildConfig.LIVE_CONNECT_TIMEOUT_MS} " +
                "readTimeoutMs=${BuildConfig.LIVE_READ_TIMEOUT_MS}")
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    info("network available id=${network.networkHandle}")
                }

                override fun onLost(network: Network) {
                    warn("network lost id=${network.networkHandle}")
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val transports = buildList {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
                    }.joinToString(",").ifEmpty { "other" }
                    info(
                        "network capabilities id=${network.networkHandle} transports=$transports " +
                            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                            "metered=${!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} " +
                            "downstreamKbps=${capabilities.linkDownstreamBandwidthKbps} " +
                            "upstreamKbps=${capabilities.linkUpstreamBandwidthKbps}",
                    )
                }
            }
            connectivityManager = manager
            networkCallback = callback
            runCatching { manager.registerDefaultNetworkCallback(callback) }
                .onFailure { warn("network callback registration failed type=${it.javaClass.simpleName}") }
        } else {
            info("diagnostics disabled")
            networkCallback?.let { callback ->
                runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            }
            networkCallback = null
            connectivityManager = null
            enabled = false
        }
    }

    fun nowMs(): Long = System.nanoTime() / 1_000_000L
    fun info(message: String) {
        write("INFO", message)
    }
    fun warn(message: String) {
        write("WARN", message)
    }

    private fun write(level: String, message: String) {
        if (!enabled) return
        val line = "${timestampFormat.format(Instant.now())} $level $message"
        runCatching {
            if (level == "WARN") Log.w("EnCodecLive", message) else Log.i("EnCodecLive", message)
        }
        synchronized(this) {
            runCatching {
                val file = logFile ?: return
                if (file.length() >= MAX_LOG_BYTES) {
                    val previous = File(file.parentFile, "$LOG_FILE_NAME.1")
                    previous.delete()
                    file.renameTo(previous)
                }
                FileOutputStream(file, true).use { output ->
                    val writer = OutputStreamWriter(output, Charsets.UTF_8)
                    writer.appendLine(line)
                    writer.flush()
                    output.fd.sync()
                }
            }.onFailure { error ->
                runCatching { Log.w("EnCodecLive", "diagnostic file write failed: ${error.javaClass.simpleName}") }
            }
        }
    }
}

data class LiveCodecInit(
    val variant: EncodecVariant,
    val bandwidthKbps: Double,
    val codebooks: Int,
)

data class LiveSegmentInfo(
    val sequence: Long,
    val url: String,
    val duration: Double,
    val sampleCount: Long?,
    val epoch: String,
    val discontinuity: Boolean,
    val byteLength: Int,
    val sha256: String? = null,
    val crc32c: Long? = null,
)

data class LiveManifest(
    val mediaSequence: Long,
    val targetDuration: Double,
    val segments: List<LiveSegmentInfo>,
    val title: String? = null,
)

data class LiveSelection(val segment: LiveSegmentInfo, val discontinuity: Boolean)

data class DownloadedLiveSegment(
    val input: InputStream,
    val sequence: Long,
    val discontinuity: Boolean,
    val codebooks: Int,
    val bandwidthKbps: Double,
    val durationSeconds: Double,
    val downloadMillis: Long,
    val reachedManifestEdge: Boolean,
    val paceProducerAfterEnqueue: Boolean = reachedManifestEdge,
)

/** Small CRC-32C accumulator (Castagnoli polynomial), compatible with older Android APIs. */
private class Crc32c {
    private var state = -1

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (index in offset until offset + length) {
            state = (state ushr 8) xor TABLE[(state xor bytes[index].toInt()) and 0xff]
        }
    }

    val value: Long get() = (state.inv().toLong() and 0xffff_ffffL)

    private companion object {
        val TABLE = IntArray(256) { entry ->
            var value = entry
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0x82f63b78.toInt() else value ushr 1
            }
            value
        }
    }
}

class LiveProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class LiveInitializationChangedException(
    val previous: LiveCodecInit,
    val current: LiveCodecInit,
) : Exception("Live codec initialization changed")

object LiveManifestParser {
    fun parse(json: String, manifestUrl: String): LiveManifest = try {
        val base = URI(manifestUrl)
        protocol(base.isAbsolute && base.scheme.isHttp()) { "Manifest URL must use HTTP or HTTPS" }
        val root = JSONObject(json)
        protocol(root.getString("format") == "encodec-live-v1") { "Not an EnCodec live v1 manifest" }
        protocol(root.getInt("version") == 1) { "Unsupported live manifest version" }
        protocol(root.getBoolean("independent_segments")) { "Live segments are not independent" }
        val title = root.optString("title", "").trim().takeIf { it.isNotEmpty() }
        protocol(title == null || title.length <= MAX_TITLE_LENGTH) { "Invalid live stream title" }
        val mediaSequence = root.getLong("media_sequence")
        protocol(mediaSequence >= 0) { "Invalid manifest sequence" }
        val targetDuration = root.getDouble("target_duration")
        protocol(targetDuration.isFinite() && targetDuration in 0.25..60.0) { "Invalid target duration" }

        val array = root.getJSONArray("segments")
        val segments = buildList {
            var previous = Long.MIN_VALUE
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sequence = item.getLong("sequence")
                protocol(sequence >= 0 && sequence > previous) { "Live segment sequences are not strictly increasing" }
                previous = sequence
                val duration = item.getDouble("duration")
                val sampleCount = if (item.has("sample_count")) item.getLong("sample_count") else null
                protocol(duration.isFinite() && duration > 0.0 && (sampleCount == null || sampleCount > 0)) {
                    "Invalid live segment duration or sample count"
                }
                val epoch = item.getString("epoch")
                UUID.fromString(epoch)
                val byteLength = item.getInt("byte_length")
                protocol(byteLength in 1..MAX_SEGMENT_BYTES) { "Invalid live segment byte length" }
                val sha256 = item.getString("sha256").lowercase()
                protocol(SHA256_REGEX.matches(sha256)) { "Invalid live segment SHA-256" }
                val resolved = base.resolve(item.getString("uri"))
                protocol(resolved.isAbsolute && resolved.scheme.isHttp()) { "Segment URI must use HTTP or HTTPS" }
                add(
                    LiveSegmentInfo(
                        sequence, resolved.toString(), duration, sampleCount,
                        epoch, item.getBoolean("discontinuity"),
                        byteLength, sha256,
                    ),
                )
            }
        }
        protocol(segments.firstOrNull()?.sequence?.let { it == mediaSequence } ?: true) {
            "media_sequence does not match the first segment"
        }
        LiveManifest(mediaSequence, targetDuration, segments, title)
    } catch (error: LiveProtocolException) {
        throw error
    } catch (error: Exception) {
        throw LiveProtocolException("Malformed live manifest: ${error.message ?: "invalid JSON"}", error)
    }

    /** Parses the proto3 StreamManifest wire format without pulling generated protobuf
     * runtimes into the APK. Unknown fields are skipped as required by protobuf. */
    fun parseProtobuf(bytes: ByteArray, manifestUrl: String): LiveManifest {
        if (bytes.size > MAX_MANIFEST_BYTES) throw LiveProtocolException("Manifest exceeds $MAX_MANIFEST_BYTES bytes")
        return try {
            val root = ProtoMessage(bytes)
            if (root.wireType(1) == 0) return parseProtobufV2(root, manifestUrl)
            val json = JSONObject()
                .put("format", root.string(1))
                .put("version", root.uint(2))
                .put("media_sequence", root.uint64(4))
                .put("target_duration", root.double(6))
                .put("independent_segments", root.bool(7))
            if (root.has(10)) json.put("title", root.string(10))
            val segments = org.json.JSONArray()
            root.repeatedBytes(9).forEach { encoded ->
                val segment = ProtoMessage(encoded)
                segments.put(JSONObject()
                    .put("sequence", segment.uint64(1))
                    .put("uri", segment.string(2))
                    .put("duration", segment.double(3))
                    .put("epoch", segment.string(7))
                    .put("discontinuity", segment.bool(8))
                    .put("byte_length", segment.uint64(9))
                    .put("sha256", segment.string(10)).apply {
                        if (segment.has(4)) put("sample_count", segment.uint64(4))
                    })
            }
            json.put("segments", segments)
            parse(json.toString(), manifestUrl)
        } catch (error: LiveProtocolException) {
            throw error
        } catch (error: Exception) {
            throw LiveProtocolException("Malformed protobuf live manifest: ${error.message ?: "invalid protobuf"}", error)
        }
    }

    private fun parseProtobufV2(root: ProtoMessage, manifestUrl: String): LiveManifest {
        protocol(root.uint(1) == 2) { "Unsupported stream manifest schema version" }
        val base = URI(manifestUrl)
        protocol(base.isAbsolute && base.scheme.isHttp()) { "Manifest URL must use HTTP or HTTPS" }
        val mediaSequence = root.uint64(2)
        val durationSamples = root.uint(3)
        protocol(durationSamples in 1..(SUPPORTED_SAMPLE_RATE * 60)) { "Invalid fixed segment duration" }
        val duration = durationSamples.toDouble() / SUPPORTED_SAMPLE_RATE
        val title = if (root.has(4)) root.string(4).trim().takeIf(String::isNotEmpty) else null
        protocol(title == null || title.length <= MAX_TITLE_LENGTH) { "Invalid live stream title" }

        var sequence = mediaSequence
        val segments = buildList {
            root.repeatedBytes(5).forEach { encodedGroup ->
                val group = ProtoMessage(encodedGroup)
                val epoch = group.uint64(1).toString()
                val firstDiscontinuity = group.bool(2)
                group.repeatedBytes(3).forEachIndexed { index, encodedSegment ->
                    val segment = ProtoMessage(encodedSegment)
                    val byteLength = segment.uint(1)
                    protocol(byteLength in 1..MAX_SEGMENT_BYTES) { "Invalid live segment byte length" }
                    protocol(segment.has(2)) { "Missing live segment CRC32C" }
                    val sampleCount = if (segment.has(3)) segment.uint64(3) else null
                    if (sampleCount != null) {
                        protocol(sampleCount > 0) { "Invalid ECDC sample count" }
                        protocol(sampleCount == durationSamples.toLong()) {
                            "Segment sample count disagrees with manifest duration"
                        }
                    }
                    val uri = "segment-${sequence.toString().padStart(12, '0')}.ecdc"
                    val resolved = base.resolve(uri)
                    protocol(resolved.isAbsolute && resolved.scheme.isHttp()) { "Segment URI must use HTTP or HTTPS" }
                    add(
                        LiveSegmentInfo(
                            sequence = sequence,
                            url = resolved.toString(),
                            duration = duration,
                            sampleCount = sampleCount,
                            epoch = epoch,
                            discontinuity = index == 0 && firstDiscontinuity,
                            byteLength = byteLength,
                            crc32c = segment.fixed32(2).toLong() and 0xffff_ffffL,
                        ),
                    )
                    if (sequence == Long.MAX_VALUE) throw LiveProtocolException("Segment sequence overflow")
                    sequence++
                }
            }
        }
        return LiveManifest(
            mediaSequence = mediaSequence,
            targetDuration = duration,
            segments = segments,
            title = title,
        )
    }

    private class ProtoMessage(private val data: ByteArray) {
        private val fields = mutableMapOf<Int, MutableList<Pair<Int, Any>>>()
        init {
            var offset = 0
            while (offset < data.size) {
                val (tag, next) = varint(offset); offset = next
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == 0) throw IOException("Invalid protobuf field tag")
                val value: Any = when (wire) {
                    0 -> { val (v, n) = varint(offset); offset = n; v }
                    1 -> { requireRemaining(offset, 8); val v = java.nio.ByteBuffer.wrap(data, offset, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long; offset += 8; v }
                    2 -> { val (length, n) = varint(offset); offset = n; require(length in 0..(data.size - offset).toLong()); data.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() } }
                    5 -> { requireRemaining(offset, 4); val v = java.nio.ByteBuffer.wrap(data, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int; offset += 4; v }
                    else -> throw IOException("Unsupported protobuf wire type $wire")
                }
                fields.getOrPut(field) { mutableListOf() }.add(wire to value)
            }
        }
        fun has(field: Int) = fields.containsKey(field)
        fun wireType(field: Int): Int? = fields[field]?.firstOrNull()?.first
        private fun value(field: Int, wire: Int): Any = fields[field]?.firstOrNull()?.let {
            if (it.first != wire) throw IOException("Unexpected wire type for field $field") else it.second
        } ?: when (wire) { 0 -> 0L; 1 -> 0L; 2 -> ByteArray(0); 5 -> 0; else -> throw IOException("Invalid protobuf wire type") }
        fun string(field: Int) = String(value(field, 2) as ByteArray, Charsets.UTF_8)
        fun bytes(field: Int) = value(field, 2) as ByteArray
        fun repeatedBytes(field: Int) = fields[field].orEmpty().map { it.second as ByteArray }
        fun uint64(field: Int) = (value(field, 0) as Long).also { require(it >= 0) { "Unsigned value exceeds supported range" } }
        fun uint(field: Int) = uint64(field).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun bool(field: Int) = uint64(field) != 0L
        fun double(field: Int) = java.lang.Double.longBitsToDouble(value(field, 1) as Long)
        fun fixed32(field: Int) = value(field, 5) as Int
        private fun requireRemaining(offset: Int, count: Int) { require(offset <= data.size - count) { "Truncated protobuf field" } }
        private fun varint(start: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var pos = start
            while (pos < data.size && shift < 64) {
                val byte = data[pos++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result to pos
                shift += 7
            }
            throw IOException("Invalid or truncated protobuf varint")
        }
    }

    private fun String?.isHttp(): Boolean =
        equals("http", ignoreCase = true) || equals("https", ignoreCase = true)

    private fun protocol(condition: Boolean, message: () -> String) {
        if (!condition) throw LiveProtocolException(message())
    }

    internal const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    internal const val MAX_MANIFEST_BYTES = 1024 * 1024
    internal const val MAX_TITLE_LENGTH = 200
    private const val SUPPORTED_SAMPLE_RATE = 24_000
    internal fun supportedCodebooks(variant: EncodecVariant): Set<Int> = when (variant) {
        EncodecVariant.MONO_24_KHZ -> setOf(2, 4, 8, 16, 32)
        EncodecVariant.STEREO_48_KHZ -> setOf(2, 4, 8, 16)
    }
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}

class LiveSequenceTracker(
    private val startupLookbackMs: Int = BuildConfig.LIVE_STARTUP_LOOKBACK_MS,
) {
    private var nextSequence: Long? = null
    private var previousEpoch: String? = null
    private var forceNextDiscontinuity = false
    private var recoverySelectionPending = false

    fun select(manifest: LiveManifest): LiveSegmentInfo? {
        if (manifest.segments.isEmpty()) return null
        val safeEdgeIndex = manifest.segments.lastIndex
        val safeEdge = manifest.segments[safeEdgeIndex]
        val expected = nextSequence
        if (!recoverySelectionPending && expected != null && expected < manifest.segments.first().sequence) {
            val nearestAvailable = manifest.segments.first()
            LiveDiagnostics.info(
                "sequence select reason=expired expected=$expected chosen=${nearestAvailable.sequence} " +
                    "edge=${safeEdge.sequence} available=${manifest.segments.size} policy=first_available",
            )
            return nearestAvailable
        }
        if (expected == null || recoverySelectionPending) {
            // Keep startup safety in audio time even when segment durations vary.
            // Leave a deletion margin when the manifest has a useful window.
            // Small windows still expose all their available audio.
            val retentionMargin = if (manifest.segments.size >= 8) 2 else 0
            val lookbackIndex = maxOf(retentionMargin,
                startupIndex(manifest.segments, startupLookbackMs))
            // A recovery must never enqueue a segment accepted earlier in this
            // session, including segments still buffered in the audio sink.
            val nextIndex = if (expected == null) 0 else
                manifest.segments.indexOfFirst { it.sequence >= expected }
            if (nextIndex < 0) {
                LiveDiagnostics.info("sequence recovery wait expected=$expected edge=${safeEdge.sequence} " +
                    "reason=no_unconsumed_segments")
                return null
            }
            val startupIndex = maxOf(lookbackIndex, nextIndex)
            val lookbackMs = manifest.segments.subList(startupIndex, safeEdgeIndex + 1)
                .sumOf { (it.duration * 1_000).toLong() }
            LiveDiagnostics.info("sequence select reason=${if (recoverySelectionPending) "recovery_safe_edge" else "start"} " +
                "chosen=${manifest.segments[startupIndex].sequence} " +
                "edge=${safeEdge.sequence} available=${manifest.segments.size} " +
                "lookbackSegments=${safeEdgeIndex - startupIndex + 1} " +
                "lookbackMs=$lookbackMs targetLookbackMs=$startupLookbackMs " +
                "retentionMarginSegments=$retentionMargin")
            return manifest.segments[startupIndex]
        }
        return manifest.segments
            .take(safeEdgeIndex + 1)
            .firstOrNull { it.sequence >= expected }
    }

    fun accept(segment: LiveSegmentInfo, serverDiscontinuity: Boolean? = null): LiveSelection {
        val expected = nextSequence
        val discontinuity = forceNextDiscontinuity || segment.discontinuity || serverDiscontinuity == true ||
            (expected != null && segment.sequence != expected) ||
            (previousEpoch != null && previousEpoch != segment.epoch)
        nextSequence = segment.sequence + 1
        previousEpoch = segment.epoch
        forceNextDiscontinuity = false
        recoverySelectionPending = false
        return LiveSelection(segment, discontinuity)
    }

    /** Recover at the safe lookback point while preserving forward progress. */
    fun jumpToSafeLivePosition() {
        forceNextDiscontinuity = true
        recoverySelectionPending = true
    }

    fun skipUnavailable(sequence: Long) {
        val expected = nextSequence
        val skippedThrough = maxOf(expected ?: sequence, sequence)
        nextSequence = if (skippedThrough == Long.MAX_VALUE) Long.MAX_VALUE else skippedThrough + 1
        forceNextDiscontinuity = true
        recoverySelectionPending = true
    }

    fun reset() {
        nextSequence = null
        previousEpoch = null
        forceNextDiscontinuity = false
        recoverySelectionPending = false
    }

    internal fun expectedSequence(): Long? = nextSequence

    companion object {
        internal fun startupIndex(segments: List<LiveSegmentInfo>, lookbackMs: Int): Int {
            if (segments.isEmpty()) return 0
            val targetMs = lookbackMs.coerceAtLeast(1)
            var index = segments.lastIndex
            var bufferedMs = (segments[index].duration * 1_000).toLong()
            while (index > 0 && bufferedMs < targetMs) {
                index--
                bufferedMs += (segments[index].duration * 1_000).toLong()
            }
            return index
        }
    }
}

class LiveStreamSource(
    private val manifestUrl: String,
    private val networkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fetchManifestBytes: (suspend () -> ByteArray)? = null,
    private val fetchSegmentBytes: (suspend (String) -> ByteArray)? = null,
    startupLookbackMs: Int = BuildConfig.LIVE_STARTUP_LOOKBACK_MS,
    preferProtobuf: Boolean? = null,
    tcpEndpoint: ElTcpEndpoint? = null,
) {
    private val tracker = LiveSequenceTracker(startupLookbackMs)
    private var retryCount = 0
    private var cachedManifest: LiveManifest? = null
    private val manifestEtags = mutableMapOf<String, String>()
    private val manifestBodies = mutableMapOf<String, ByteArray>()
    private var streamInit: LiveCodecInit? = null
    private var pendingProbeUrl: String? = null
    private var pendingProbeBytes: ByteArray? = null
    private var pendingProbeCrc32c: Long? = null
    private var pendingProbeDiscontinuity: Boolean? = null
    private val prefetchedSegments = mutableMapOf<String, Deferred<Result<ByteArray>>>()
    private val tcpSession = tcpEndpoint?.let(::ElTcpSession)
    private var tcpActive = tcpSession != null
    private val protobufManifestUrl: String? = when {
        manifestUrl.substringBefore('?').substringBefore('#').endsWith(".pb") -> manifestUrl
        else -> protobufSiblingUrl(manifestUrl)
    }
    private var manifestFormat: ManifestFormat? = if (preferProtobuf == false) {
        ManifestFormat.JSON
    } else if (manifestUrl.substringBefore('?').substringBefore('#').endsWith(".pb")) {
        ManifestFormat.PROTOBUF
    } else null
    var streamTitle: String? = null
        private set

    suspend fun initialize(onStatus: (String) -> Unit): LiveCodecInit {
        streamInit?.let { return it }
        while (currentCoroutineContext().isActive) {
            try {
                onStatus(if (retryCount == 0) "Checking stream format…" else "Reconnecting…")
                val manifest = cachedManifest ?: fetchAndCacheManifest()
                val firstSegment = tracker.select(manifest)
                if (firstSegment == null) {
                    onStatus("Waiting for live segments…")
                    delay(pollDelayMillis(manifest))
                    cachedManifest = null
                    continue
                }
                val probeStarted = LiveDiagnostics.nowMs()
                val probeUri = URI(firstSegment.url)
                LiveDiagnostics.info(
                    "segment header probe start seq=${firstSegment.sequence} host=${probeUri.host} " +
                        "path=${probeUri.rawPath} expectedBytes=${firstSegment.byteLength} " +
                        "connectTimeoutMs=${BuildConfig.LIVE_CONNECT_TIMEOUT_MS} " +
                        "readTimeoutMs=${BuildConfig.LIVE_READ_TIMEOUT_MS}",
                )
                var probeCrc32c: Long? = null
                var probeDiscontinuity: Boolean? = null
                val probeBytes = if (tcpActive && tcpSession!!.supportsAbsoluteSequenceFetch()) {
                    val fetched = fetchTcpSequence(firstSegment.sequence, firstSegment.byteLength)
                    probeCrc32c = fetched.crc32c
                    probeDiscontinuity = fetched.discontinuity
                    fetched.bytes
                } else if (tcpActive) {
                    tcpSession!!.fetchSegment(manifest, firstSegment)
                } else if (fetchSegmentBytes == null) {
                    withContext(networkDispatcher) {
                        LiveDiagnostics.info(
                            "segment header probe dispatched seq=${firstSegment.sequence} " +
                                "thread=${Thread.currentThread().name}",
                        )
                        HttpsStreams.readLivePrefix(
                            firstSegment.url,
                            SEGMENT_HEADER_PROBE_BYTES,
                        )
                    }
                } else {
                    fetchSegmentBytes.invoke(firstSegment.url)
                }
                LiveDiagnostics.info(
                    "segment header probe complete seq=${firstSegment.sequence} bytes=${probeBytes.size} " +
                        "elapsedMs=${LiveDiagnostics.nowMs() - probeStarted}",
                )
                val derivedInit = deriveInitialization(firstSegment, probeBytes)
                validateSegmentTiming(manifest, derivedInit)
                streamInit = derivedInit
                pendingProbeUrl = firstSegment.url
                pendingProbeBytes = probeBytes
                pendingProbeCrc32c = probeCrc32c
                pendingProbeDiscontinuity = probeDiscontinuity
                retryCount = 0
                LiveDiagnostics.info(
                    "codec initialized from ecdc header segment=${firstSegment.sequence} " +
                        "model=${derivedInit.variant.wireName} codebooks=${derivedInit.codebooks} " +
                        "bandwidthKbps=${derivedInit.bandwidthKbps}",
                )
                return derivedInit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: LiveInitializationChangedException) {
                LiveDiagnostics.warn(
                    "codec initialization changed previous=${changed.previous} current=${changed.current}",
                )
                throw changed
            } catch (stale: ElTcpSegmentUnavailableException) {
                cacheTcpManifest(stale.freshManifest)
                tracker.skipUnavailable(stale.sequence)
                pendingProbeUrl = null
                pendingProbeBytes = null
                pendingProbeCrc32c = null
                pendingProbeDiscontinuity = null
                continue
            } catch (unsupported: ElTcpFetchUnsupportedException) {
                cachedManifest = null
                tracker.jumpToSafeLivePosition()
                LiveDiagnostics.info("tcp fetch fallback reconnecting=true mode=legacy_m_s")
                continue
            } catch (protocol: LiveProtocolException) {
                if (disableTcp(protocol)) {
                    cachedManifest = null
                    continue
                }
                LiveDiagnostics.warn("codec initialization protocol failure message=${protocol.message}")
                throw protocol
            } catch (error: IOException) {
                if (disableTcp(error)) {
                    cachedManifest = null
                    continue
                }
                if (tcpActive) tcpSession?.resetConnection()
                cachedManifest = null
                if (tcpActive) tracker.jumpToSafeLivePosition()
                retryCount++
                LiveDiagnostics.warn(
                    "codec initialization network failure retry=$retryCount " +
                        "error=${error.javaClass.simpleName} message=${error.message}",
                )
                onStatus("Network error: ${error.message ?: "retrying"}")
                val retryMs = recoveryRetryDelayMillis(retryCount)
                LiveDiagnostics.warn("source retry wait retry=$retryCount waitMs=$retryMs " +
                    "expectedSeq=${tracker.expectedSequence()} error=${error.javaClass.simpleName}")
                delay(retryMs)
            }
        }
        throw CancellationException("Live stream stopped")
    }

    suspend fun nextSegment(onStatus: (String) -> Unit): DownloadedLiveSegment {
        while (currentCoroutineContext().isActive) {
            try {
                var manifest = cachedManifest
                var selected = manifest?.let(tracker::select)
                val absoluteTcpFetch = tcpActive && tcpSession!!.supportsAbsoluteSequenceFetch()
                if (selected == null && absoluteTcpFetch && manifest != null) {
                    val expected = tracker.expectedSequence()
                    if (expected != null) selected = segmentForAbsoluteSequence(manifest, expected)
                }
                if (selected == null) {
                    LiveDiagnostics.info("manifest refresh reason=${if (manifest == null) "cache_empty" else "batch_exhausted"} " +
                        "expectedSeq=${tracker.expectedSequence()} cachedEdge=${manifest?.segments?.lastOrNull()?.sequence}")
                    onStatus(if (retryCount == 0) "Checking live edge…" else "Reconnecting…")
                    manifest = fetchAndCacheManifest()
                    selected = tracker.select(manifest)
                }
                val activeManifest = manifest
                    ?: throw LiveProtocolException("Live manifest was not available")
                if (selected == null) {
                    onStatus("Waiting for sequence ${tracker.expectedSequence() ?: activeManifest.mediaSequence}…")
                    val pollMs = pollDelayMillis(activeManifest)
                    LiveDiagnostics.info("manifest poll wait reason=sequence_not_published " +
                        "expectedSeq=${tracker.expectedSequence()} edge=${activeManifest.segments.lastOrNull()?.sequence} " +
                        "waitMs=$pollMs")
                    delay(pollMs)
                    cachedManifest = null
                    continue
                }
                onStatus("Buffering segment ${selected.sequence}…")
                val downloadStarted = LiveDiagnostics.nowMs()
                val manifestExpectedBytes = activeManifest
                    .segments.firstOrNull { it.sequence == selected.sequence }?.byteLength
                LiveDiagnostics.info(
                    "segment request seq=${selected.sequence} expectedBytes=${manifestExpectedBytes ?: "unknown"} " +
                        "durationMs=${(selected.duration * 1_000).toLong()} " +
                        "manifestEdge=${activeManifest.segments.lastOrNull()?.sequence} " +
                        "thread=${Thread.currentThread().name}",
                )
                val activeInit = streamInit
                    ?: throw LiveProtocolException("Codec has not been initialized from an ECDC segment")
                prefetchSmallSegments(activeManifest, selected)
                val isPendingProbe = pendingProbeUrl == selected.url
                val prefetched = prefetchedSegments.remove(selected.url)?.await()?.getOrThrow()
                    ?: if (isPendingProbe) pendingProbeBytes else null
                val probeCrc32c = if (isPendingProbe) pendingProbeCrc32c else null
                val probeDiscontinuity = if (isPendingProbe) pendingProbeDiscontinuity else null
                pendingProbeUrl = null
                pendingProbeBytes = null
                pendingProbeCrc32c = null
                pendingProbeDiscontinuity = null
                var serverDiscontinuity: Boolean? = null
                var tcpSegmentInfo: LiveSegmentInfo? = null
                val streamed = if (tcpActive && tcpSession!!.supportsAbsoluteSequenceFetch()) {
                    val fetched = if (isPendingProbe && prefetched != null && probeCrc32c != null) {
                        ElTcpFetchResult.Ready(prefetched, probeCrc32c, probeDiscontinuity == true)
                    } else {
                        fetchTcpSequence(selected.sequence, manifestExpectedBytes)
                    }
                    serverDiscontinuity = fetched.discontinuity
                    tcpSegmentInfo = selected.copy(
                        byteLength = fetched.bytes.size,
                        sha256 = null,
                        crc32c = fetched.crc32c,
                        discontinuity = selected.discontinuity || fetched.discontinuity,
                    )
                    verifySegment(fetched.bytes, tcpSegmentInfo!!, activeInit)
                    ByteArrayInputStream(fetched.bytes) to fetched.bytes.size
                } else if (tcpActive) {
                    val bytes = tcpSession!!.fetchSegment(activeManifest, selected)
                    verifySegment(bytes, selected, activeInit)
                    ByteArrayInputStream(bytes) to bytes.size
                } else if (fetchSegmentBytes == null) {
                    openStreamingSegment(selected, activeInit, prefetched)
                } else {
                    val bytes = prefetched ?: fetchSegmentBytes.invoke(selected.url)
                    verifySegment(bytes, selected, activeInit)
                    ByteArrayInputStream(bytes) to bytes.size
                }
                val downloadMs = LiveDiagnostics.nowMs() - downloadStarted
                val accepted = tracker.accept(tcpSegmentInfo ?: selected, serverDiscontinuity)
                LiveDiagnostics.info(
                    "segment stream opened seq=${selected.sequence} expectedBytes=${streamed.second} " +
                        "openMs=$downloadMs mode=${when {
                            tcpActive && tcpSession?.supportsAbsoluteSequenceFetch() == true -> "tcp_fetch"
                            tcpActive -> "tcp_legacy"
                            fetchSegmentBytes == null -> "range_stream"
                            else -> "buffered_test_source"
                        }} " +
                        "discontinuity=${accepted.discontinuity}",
                )
                retryCount = 0
                return DownloadedLiveSegment(
                    streamed.first, selected.sequence, accepted.discontinuity,
                    activeInit.codebooks, activeInit.bandwidthKbps,
                    selected.duration, downloadMs,
                    selected.sequence == activeManifest.segments.lastOrNull()?.sequence,
                    paceProducerAfterEnqueue = absoluteTcpFetch ||
                        selected.sequence == activeManifest.segments.lastOrNull()?.sequence,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: ElTcpSegmentUnavailableException) {
                cacheTcpManifest(stale.freshManifest)
                tracker.skipUnavailable(stale.sequence)
                clearPrefetch()
                LiveDiagnostics.warn(
                    "tcp sequence stale; refreshed manifest immediately expectedSeq=${tracker.expectedSequence()}",
                )
                continue
            } catch (unsupported: ElTcpFetchUnsupportedException) {
                tracker.jumpToSafeLivePosition()
                cachedManifest = null
                clearPrefetch()
                LiveDiagnostics.info("tcp fetch fallback reconnecting=true mode=legacy_m_s")
                continue
            } catch (protocol: LiveProtocolException) {
                if (disableTcp(protocol)) {
                    tracker.jumpToSafeLivePosition()
                    cachedManifest = null
                    clearPrefetch()
                    continue
                }
                LiveDiagnostics.warn(
                    "live segment protocol failure expectedSeq=${tracker.expectedSequence()} message=${protocol.message}",
                )
                throw protocol
            } catch (error: IOException) {
                disableTcp(error)
                if (tcpActive) tcpSession?.resetConnection()
                retryCount++
                // Do not retry a timed-out sequence after the stall. Fetch a
                // fresh manifest and restart at its safe live lookback point.
                val oldExpected = tracker.expectedSequence()
                tracker.jumpToSafeLivePosition()
                cachedManifest = null
                clearPrefetch()
                LiveDiagnostics.warn(
                    "live network recovery retry=$retryCount skippedExpectedSeq=$oldExpected " +
                        "selection=refreshed_manifest_safe_edge " +
                        "error=${error.javaClass.simpleName} message=${error.message}",
                )
                LiveDiagnostics.warn(
                    "network retry=$retryCount skippedExpectedSeq=$oldExpected " +
                        "error=${error.message}",
                )
                onStatus("Network error: ${error.message ?: "retrying"}")
                val retryMs = recoveryRetryDelayMillis(retryCount)
                LiveDiagnostics.warn("source retry wait retry=$retryCount waitMs=$retryMs " +
                    "error=${error.javaClass.simpleName}")
                delay(retryMs)
            }
        }
        throw CancellationException("Live stream stopped")
    }

    fun jumpToLive() {
        clearPrefetch()
        tracker.reset()
        cachedManifest = null
        streamInit = null
        pendingProbeUrl = null
        pendingProbeBytes = null
        streamTitle = null
    }

    fun close() {
        tcpActive = false
        tcpSession?.close()
        clearPrefetch()
    }

    private fun disableTcp(error: Throwable): Boolean {
        if (!tcpActive || tcpSession?.hasConnected() == true) return false
        tcpActive = false
        tcpSession?.close()
        LiveDiagnostics.warn(
            "tcp fallback transport=https reason=${error.javaClass.simpleName} message=${error.message}",
        )
        return true
    }

    private suspend fun fetchTcpSequence(sequence: Long, expectedByteLength: Int?): ElTcpFetchResult.Ready {
        val session = tcpSession ?: throw IOException("ELTCP session is unavailable")
        while (currentCoroutineContext().isActive) {
            when (val result = session.fetchSequence(sequence, expectedByteLength)) {
                is ElTcpFetchResult.Ready -> return result
                ElTcpFetchResult.NotPublished -> delay(1_000L)
            }
        }
        throw CancellationException("Live stream stopped while waiting for sequence $sequence")
    }

    private fun cacheTcpManifest(bytes: ByteArray): LiveManifest {
        val manifest = LiveManifestParser.parseProtobuf(bytes, manifestUrl)
        streamInit?.let { validateSegmentTiming(manifest, it) }
        manifestFormat = ManifestFormat.PROTOBUF
        streamTitle = manifest.title
        cachedManifest = manifest
        LiveDiagnostics.info(
            "manifest ready format=protobuf_tcp refresh=stale_sequence bytes=${bytes.size} " +
                "range=${manifest.segments.firstOrNull()?.sequence}..${manifest.segments.lastOrNull()?.sequence} " +
                "segments=${manifest.segments.size}",
        )
        return manifest
    }

    private fun segmentForAbsoluteSequence(manifest: LiveManifest, sequence: Long): LiveSegmentInfo? {
        if (sequence < 0) return null
        val template = manifest.segments.lastOrNull() ?: return null
        val filename = "segment-${sequence.toString().padStart(12, '0')}.ecdc"
        val url = runCatching { URI(template.url).resolve(filename).toString() }.getOrDefault(template.url)
        return template.copy(
            sequence = sequence,
            url = url,
            discontinuity = false,
            sha256 = null,
            crc32c = null,
        )
    }

    private fun clearPrefetch() {
        prefetchedSegments.values.forEach { it.cancel() }
        prefetchedSegments.clear()
    }

    /** Overlap request latency for tiny radio files while keeping playback ordered.
     * Larger files retain the progressive prefix/suffix streaming path. */
    private suspend fun prefetchSmallSegments(manifest: LiveManifest, selected: LiveSegmentInfo) {
        if (tcpActive || fetchSegmentBytes != null) return
        val scope = CoroutineScope(currentCoroutineContext())
        val candidates = manifest.segments.filter { it.sequence >= selected.sequence }
            .take(3)
        val wantedUrls = candidates.map { it.url }.toSet()
        prefetchedSegments.keys.filter { it !in wantedUrls }.forEach { url ->
            prefetchedSegments.remove(url)?.cancel()
        }
        for (segment in candidates) {
            if (segment.byteLength > SMALL_SEGMENT_PREFETCH_BYTES ||
                prefetchedSegments.containsKey(segment.url) ||
                (pendingProbeUrl == segment.url && pendingProbeBytes?.size == segment.byteLength)
            ) continue
            LiveDiagnostics.info("segment prefetch schedule seq=${segment.sequence} " +
                "bytes=${segment.byteLength} durationMs=${(segment.duration * 1_000).toLong()} concurrencyLimit=3")
            prefetchedSegments[segment.url] = scope.async(Dispatchers.IO) {
                try {
                    Result.success(HttpsStreams.readLivePrefix(
                        segment.url,
                        segment.byteLength,
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    // Report the failure when its sequence is consumed. A
                    // speculative request must not cancel the playback scope.
                    Result.failure(error)
                }
            }
        }
    }

    private fun pollDelayMillis(manifest: LiveManifest): Long =
        (manifest.targetDuration * 250).toLong().coerceIn(250L, 1_000L)

    internal fun recoveryRetryDelayMillis(attempt: Int): Long =
        (100L * attempt.coerceAtLeast(1)).coerceAtMost(500L)

    private suspend fun fetchAndCacheManifest(): LiveManifest {
        val fetchStarted = LiveDiagnostics.nowMs()
        LiveDiagnostics.info(
            "manifest request expectedSeq=${tracker.expectedSequence()} " +
                "thread=${Thread.currentThread().name}",
        )
        if (tcpActive) {
            try {
                val bytes = tcpSession!!.requestManifest()
                val manifest = LiveManifestParser.parseProtobuf(bytes, manifestUrl)
                val knownInit = streamInit
                if (knownInit != null) validateSegmentTiming(manifest, knownInit)
                streamTitle = manifest.title
                cachedManifest = manifest
                LiveDiagnostics.info(
                    "manifest ready format=protobuf_tcp bytes=${bytes.size} fetchMs=${LiveDiagnostics.nowMs() - fetchStarted} " +
                        "range=${manifest.segments.firstOrNull()?.sequence}..${manifest.segments.lastOrNull()?.sequence} " +
                        "mediaSeq=${manifest.mediaSequence} segments=${manifest.segments.size}",
                )
                return manifest
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (tcpSession?.hasConnected() == true) {
                    // Once a TCP socket has connected, keep this playback on
                    // TCP. The session drops broken sockets and reconnects
                    // with a fresh init/manifest on the next attempt.
                    tcpSession?.resetConnection()
                    LiveDiagnostics.warn(
                        "tcp reconnect scheduled after established-session failure " +
                            "reason=${error.javaClass.simpleName} message=${error.message}",
                    )
                    throw error
                }
                disableTcp(error)
            }
        }
        val selectedFormat = manifestFormat
        val (manifestBytes, manifest, actualFormat) = when {
            selectedFormat == ManifestFormat.PROTOBUF -> {
                val protobufUrl = protobufManifestUrl ?: manifestUrl
                val bytes = fetchManifest(protobufUrl)
                Triple(bytes, LiveManifestParser.parseProtobuf(bytes, protobufUrl), ManifestFormat.PROTOBUF)
            }
            selectedFormat == ManifestFormat.JSON -> {
                val bytes = fetchManifest(manifestUrl)
                Triple(bytes, LiveManifestParser.parse(bytes.toString(Charsets.UTF_8), manifestUrl), ManifestFormat.JSON)
            }
            protobufManifestUrl != null && !manifestUrl.substringBefore('?').substringBefore('#').endsWith(".pb") -> {
                try {
                    val bytes = fetchManifest(protobufManifestUrl)
                    Triple(bytes, LiveManifestParser.parseProtobuf(bytes, protobufManifestUrl), ManifestFormat.PROTOBUF)
                } catch (error: LiveProtocolException) {
                    LiveDiagnostics.warn("protobuf manifest unavailable or invalid; falling back to JSON")
                    val bytes = fetchManifest(manifestUrl)
                    Triple(bytes, LiveManifestParser.parse(bytes.toString(Charsets.UTF_8), manifestUrl), ManifestFormat.JSON)
                } catch (error: IOException) {
                    if (!error.message.orEmpty().contains(Regex("HTTP (404|410)"))) throw error
                    LiveDiagnostics.info("protobuf manifest not found; falling back to JSON")
                    val bytes = fetchManifest(manifestUrl)
                    Triple(bytes, LiveManifestParser.parse(bytes.toString(Charsets.UTF_8), manifestUrl), ManifestFormat.JSON)
                }
            }
            else -> {
                val bytes = fetchManifest(manifestUrl)
                Triple(bytes, LiveManifestParser.parse(bytes.toString(Charsets.UTF_8), manifestUrl), ManifestFormat.JSON)
            }
        }
        manifestFormat = actualFormat
        val knownInit = streamInit
        if (knownInit != null) validateSegmentTiming(manifest, knownInit)
        streamTitle = manifest.title
        cachedManifest = manifest
        val first = manifest.segments.firstOrNull()?.sequence
        val last = manifest.segments.lastOrNull()?.sequence
        LiveDiagnostics.info(
            "manifest ready format=${actualFormat.name.lowercase()} bytes=${manifestBytes.size} fetchMs=" +
                "${LiveDiagnostics.nowMs() - fetchStarted} range=$first..$last " +
                "mediaSeq=${manifest.mediaSequence} targetSec=${manifest.targetDuration} " +
                "segments=${manifest.segments.size} " +
                "codecInitialized=${streamInit != null}",
        )
        return manifest
    }

    private fun validateSegmentTiming(manifest: LiveManifest, init: LiveCodecInit) {
        manifest.segments.forEach { segment ->
            if (segment.sampleCount != null &&
                abs(segment.duration - segment.sampleCount.toDouble() / init.variant.sampleRate) > 0.02
            ) {
                throw LiveProtocolException(
                    "Segment ${segment.sequence} duration and ECDC sample count disagree",
                )
            }
        }
    }

    private fun deriveInitialization(segment: LiveSegmentInfo, prefix: ByteArray): LiveCodecInit {
        val header = inspectEcdcHeader(segment, prefix)
        if (header.usesLanguageModel) throw LiveProtocolException("LM-coded live streams are not supported")
        if (header.numCodebooks !in LiveManifestParser.supportedCodebooks(header.variant)) {
            throw LiveProtocolException("Unsupported live codebook count")
        }
        if (segment.sampleCount != null && header.audioLengthSamples != segment.sampleCount) {
            throw LiveProtocolException("Segment ${segment.sequence} sample count does not match its ECDC header")
        }
        if (abs(segment.duration - header.audioLengthSamples.toDouble() / header.variant.sampleRate) > 0.02) {
            throw LiveProtocolException("Segment ${segment.sequence} duration does not match its ECDC header")
        }
        return LiveCodecInit(
            variant = header.variant,
            bandwidthKbps = header.nominalBitrateBps / 1_000.0,
            codebooks = header.numCodebooks,
        )
    }

    private fun inspectEcdcHeader(segment: LiveSegmentInfo, prefix: ByteArray): com.henry.encodec.ecdc.EcdcHeader {
        if (prefix.size < 9 || !prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(69, 67, 68, 67))) {
            throw LiveProtocolException("Segment ${segment.sequence} has no ECDC header")
        }
        val metadataLength = java.nio.ByteBuffer.wrap(prefix, 5, 4).int
        val headerLength = 9L + metadataLength
        if (metadataLength !in 2..64 * 1024 || headerLength > segment.byteLength) {
            throw LiveProtocolException("Segment ${segment.sequence} has an invalid ECDC header length")
        }
        if (headerLength > prefix.size) {
            throw IOException("Segment ${segment.sequence} ECDC header is incomplete")
        }
        return try {
            EcdcReader.inspect(ByteArrayInputStream(prefix, 0, headerLength.toInt()))
        } catch (error: Exception) {
            throw LiveProtocolException("Segment ${segment.sequence} has an invalid ECDC header", error)
        }
    }

    private suspend fun fetchManifest(url: String): ByteArray {
        fetchManifestBytes?.let { return it() }
        val normalizedUrl = NetworkProtocolSettings.applyScheme(java.net.URL(url)).toExternalForm()
        val cachedBody = manifestBodies[normalizedUrl]
        val etag = manifestEtags[normalizedUrl]
        return HttpsStreams.readBytes(
            url,
            LiveManifestParser.MAX_MANIFEST_BYTES,
            noCache = true,
            dispatcher = networkDispatcher,
            accept = if (url.substringBefore('?').endsWith(".pb")) {
                "application/x-protobuf, application/octet-stream"
            } else "application/json, */*",
            ifNoneMatch = etag,
            // Keep one manifest fetch bounded even when Cronet's URLConnection
            // adapter stalls before headers. The source will discard the old
            // expectation and refetch the live window after this times out.
            wallClockTimeoutMs =
                (BuildConfig.LIVE_CONNECT_TIMEOUT_MS + BuildConfig.LIVE_READ_TIMEOUT_MS).toLong(),
            cachedResponse = cachedBody,
            onEtag = { responseEtag ->
                if (responseEtag.isNullOrBlank()) manifestEtags.remove(normalizedUrl)
                else manifestEtags[normalizedUrl] = responseEtag
            },
        ).also { bytes ->
            // 304 returns the cached bytes; replacing them is harmless and lets
            // the source recover cleanly if an intermediary changes validators.
            manifestBodies[normalizedUrl] = bytes
        }
    }

    /** Request an initial ECDC prefix, then stream the remaining bytes into a
     * bounded pipe while the decoder reads. */
    private suspend fun openStreamingSegment(
        segment: LiveSegmentInfo,
        init: LiveCodecInit,
        prefetchedBytes: ByteArray?,
    ): Pair<InputStream, Int> {
        // The transfer must live in the long-lived segment producer scope, not
        // in the short withContext block used for its blocking setup request.
        val transferScope = CoroutineScope(currentCoroutineContext())
        return withContext(networkDispatcher) {
        val prefix = prefetchedBytes ?: HttpsStreams.readLivePrefix(
            segment.url,
            SEGMENT_HEADER_PROBE_BYTES,
        )
        val header = inspectEcdcHeader(segment, prefix)
        verifyEcdcHeader(header, segment, init)
        val headerLength = 9 + java.nio.ByteBuffer.wrap(prefix, 5, 4).int
        LiveDiagnostics.info(
            "segment range probe seq=${segment.sequence} source=${if (prefetchedBytes != null) "prefetched" else "range_request"} " +
                "received=${prefix.size} headerBytes=$headerLength expectedTotal=${segment.byteLength}",
        )

        // For short low-bitrate segments the first range response can already
        // contain the complete object. Validate and use those bytes directly;
        // discarding the response tail would leave playback waiting for data
        // that the server has already sent.
        if (prefix.size == segment.byteLength) {
            verifySegment(prefix, segment, init)
            LiveDiagnostics.info(
                "segment range complete seq=${segment.sequence} bytes=${prefix.size} " +
                    "mode=complete_probe checksum=${checksumName(segment)}=valid",
            )
            return@withContext ByteArrayInputStream(prefix) to prefix.size
        }
        if (prefix.size > segment.byteLength) {
            throw LiveProtocolException("Segment ${segment.sequence} probe exceeds its declared byte length")
        }

        val pipeInput = PipedInputStream(64 * 1024)
        val pipeOutput = PipedOutputStream(pipeInput)
        val digest = segment.sha256?.let { MessageDigest.getInstance("SHA-256") }
        val crc32c = if (segment.crc32c != null) Crc32c() else null
        val received = java.util.concurrent.atomic.AtomicLong(0L)
        val transferError = java.util.concurrent.atomic.AtomicReference<IOException?>(null)
        // Reuse all bytes received by the probe. Request only the remaining
        // suffix so the second range does not redownload its payload prefix.
        digest?.update(prefix)
        crc32c?.update(prefix, 0, prefix.size)
        received.set(prefix.size.toLong())
        pipeOutput.write(prefix)
        val transfer = transferScope.launch(networkDispatcher) {
            val rangeStarted = LiveDiagnostics.nowMs()
            LiveDiagnostics.info("segment range start seq=${segment.sequence} requested=${prefix.size}-")
            try {
                HttpsStreams.openRange(
                    segment.url, prefix.size.toLong(),
                    connectTimeoutMs = BuildConfig.LIVE_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = BuildConfig.LIVE_READ_TIMEOUT_MS,
                    maxAttempts = 1,
                ).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (received.get() + read > segment.byteLength) throw IOException("Segment exceeded declared byte length")
                        digest?.update(buffer, 0, read)
                        crc32c?.update(buffer, 0, read)
                        received.addAndGet(read.toLong())
                        pipeOutput.write(buffer, 0, read)
                    }
                }
                if (received.get() != segment.byteLength.toLong()) {
                    throw IOException("Segment ended at ${received.get()} bytes; expected ${segment.byteLength}")
                }
                val checksumValid = when {
                    digest != null -> digest.digest().contentEquals(hexToBytes(segment.sha256!!))
                    crc32c != null -> crc32c.value == segment.crc32c
                    else -> false
                }
                if (!checksumValid) throw IOException("Segment ${segment.sequence} failed ${checksumName(segment)} validation")
                LiveDiagnostics.info(
                    "segment range complete seq=${segment.sequence} bytes=${received.get()} " +
                        "rangeMs=${LiveDiagnostics.nowMs() - rangeStarted} checksum=${checksumName(segment)}=valid",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                transferError.set(error)
                LiveDiagnostics.warn(
                    "segment range failed seq=${segment.sequence} bytes=${received.get()} " +
                        "rangeMs=${LiveDiagnostics.nowMs() - rangeStarted} error=${error.message}",
                )
            } finally {
                runCatching { pipeOutput.close() }
            }
        }
        val checked = object : FilterInputStream(pipeInput) {
            private var checkedEof = false
            override fun read(): Int = super.read().also { if (it < 0) validateEnd() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it < 0) validateEnd() }
            private fun validateEnd() {
                if (checkedEof) return
                checkedEof = true
                transferError.get()?.let { throw it }
            }
            override fun close() {
                if (transfer.isActive) transfer.cancel()
                super.close()
            }
        }
        checked to segment.byteLength
        }
    }

    private enum class ManifestFormat { JSON, PROTOBUF }

    private fun protobufSiblingUrl(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        if (!withoutQuery.endsWith(".json", ignoreCase = true)) return null
        return url.replace(Regex("(?i)\\.json(?=([?#]|$))"), ".pb")
    }

    companion object {
        private const val SEGMENT_HEADER_PROBE_BYTES = 1024
        private const val SMALL_SEGMENT_PREFETCH_BYTES = 16 * 1024

        fun verifySegment(bytes: ByteArray, segment: LiveSegmentInfo, init: LiveCodecInit) {
            if (bytes.size != segment.byteLength) {
                throw LiveProtocolException(
                    "Segment ${segment.sequence} has ${bytes.size} bytes, expected ${segment.byteLength}",
                )
            }
            val checksumValid = when {
                segment.sha256 != null -> MessageDigest.getInstance("SHA-256").digest(bytes)
                    .contentEquals(hexToBytes(segment.sha256))
                segment.crc32c != null -> Crc32c().apply { update(bytes) }.value == segment.crc32c
                else -> false
            }
            if (!checksumValid) throw LiveProtocolException(
                "Segment ${segment.sequence} failed ${checksumName(segment)} validation",
            )
            val header = try {
                ByteArrayInputStream(bytes).use(EcdcReader::inspect)
            } catch (error: Exception) {
                throw LiveProtocolException("Segment ${segment.sequence} is not valid ECDC v0", error)
            }
            verifyEcdcHeader(header, segment, init)
        }

        private fun checksumName(segment: LiveSegmentInfo): String =
            if (segment.crc32c != null) "crc32c" else "sha256"

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun verifyEcdcHeader(
        header: com.henry.encodec.ecdc.EcdcHeader,
        segment: LiveSegmentInfo,
        init: LiveCodecInit,
    ) {
        if (header.version != 0 || header.variant != init.variant || header.usesLanguageModel ||
            header.numCodebooks != init.codebooks ||
            header.numCodebooks !in LiveManifestParser.supportedCodebooks(init.variant)
        ) {
            val current = LiveCodecInit(
                header.variant,
                header.nominalBitrateBps / 1_000.0,
                header.numCodebooks,
            )
            throw LiveInitializationChangedException(init, current)
        }
        if (segment.sampleCount != null && header.audioLengthSamples != segment.sampleCount) {
            throw LiveProtocolException("Segment ${segment.sequence} sample count does not match its ECDC header")
        }
        if (abs(segment.duration - header.audioLengthSamples.toDouble() / header.variant.sampleRate) > 0.02) {
            throw LiveProtocolException("Segment ${segment.sequence} duration does not match its ECDC header")
        }
    }
    }
}
