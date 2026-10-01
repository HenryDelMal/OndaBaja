package com.henry.encodec.player

import cl.cuy.emergencyradio.BuildConfig
import android.util.Log
import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

internal object LiveDiagnostics {
    @Volatile var enabled: Boolean = false

    fun nowMs(): Long = System.nanoTime() / 1_000_000L
    fun info(message: String) {
        if (!enabled) return
        runCatching { Log.i("EnCodecLive", message) }
    }
    fun warn(message: String) {
        if (!enabled) return
        runCatching { Log.w("EnCodecLive", message) }
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
    val sampleCount: Long,
    val ptsSamples: Long,
    val programDateTime: Instant,
    val epoch: String,
    val discontinuity: Boolean,
    val byteLength: Int,
    val sha256: String,
)

data class LiveManifest(
    val mediaSequence: Long,
    val discontinuitySequence: Long,
    val targetDuration: Double,
    val init: LiveCodecInit,
    val segments: List<LiveSegmentInfo>,
    val title: String? = null,
)

data class LiveSelection(val segment: LiveSegmentInfo, val discontinuity: Boolean)

data class DownloadedLiveSegment(
    val bytes: ByteArray,
    val sequence: Long,
    val discontinuity: Boolean,
    val codebooks: Int,
    val bandwidthKbps: Double,
    val durationSeconds: Double,
    val downloadMillis: Long,
    val reachedManifestEdge: Boolean,
)

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
        Instant.parse(root.getString("updated_at"))
        val title = root.optString("title", "").trim().takeIf { it.isNotEmpty() }
        protocol(title == null || title.length <= MAX_TITLE_LENGTH) { "Invalid live stream title" }
        val mediaSequence = root.getLong("media_sequence")
        val discontinuitySequence = root.getLong("discontinuity_sequence")
        protocol(mediaSequence >= 0 && discontinuitySequence >= 0) { "Invalid manifest sequence" }
        val targetDuration = root.getDouble("target_duration")
        protocol(targetDuration.isFinite() && targetDuration in 0.25..60.0) { "Invalid target duration" }

        val initJson = root.getJSONObject("init")
        protocol(initJson.getString("container") == "ecdc") { "Live container is not ECDC" }
        protocol(initJson.getInt("container_version") == 0) { "Unsupported ECDC container version" }
        val model = initJson.getString("model")
        val variant = EncodecVariant.entries.firstOrNull { it.wireName == model }
            ?: throw LiveProtocolException("Unsupported EnCodec live model: $model")
        protocol(initJson.getInt("sample_rate") == variant.sampleRate) {
            "Live sample rate does not match $model"
        }
        protocol(initJson.getInt("channels") == variant.channels) {
            "Live channel count does not match $model"
        }
        protocol(initJson.getInt("bits_per_codebook") == 10) { "Unsupported code width" }
        protocol(!initJson.getBoolean("language_model")) { "LM-coded live streams are not supported" }
        protocol(initJson.getBoolean("self_initializing_segments")) { "Segments lack initialization" }
        val codebooks = initJson.getInt("codebooks")
        protocol(codebooks in supportedCodebooks(variant)) { "Unsupported live codebook count" }
        val bandwidth = initJson.getDouble("bandwidth_kbps")
        val codebookBitrateKbps = variant.frameRate * 10.0 / 1_000.0
        protocol(abs(bandwidth - codebooks * codebookBitrateKbps) < 0.001) {
            "Bandwidth and codebooks do not match"
        }
        val init = LiveCodecInit(variant, bandwidth, codebooks)

        val array = root.getJSONArray("segments")
        val segments = buildList {
            var previous = Long.MIN_VALUE
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sequence = item.getLong("sequence")
                protocol(sequence >= 0 && sequence > previous) { "Live segment sequences are not strictly increasing" }
                previous = sequence
                val duration = item.getDouble("duration")
                val sampleCount = item.getLong("sample_count")
                protocol(duration.isFinite() && duration > 0.0 && sampleCount > 0) { "Invalid live segment duration" }
                protocol(abs(duration - sampleCount.toDouble() / variant.sampleRate) <= 0.02) {
                    "Segment duration and sample count disagree"
                }
                val ptsSamples = item.getLong("pts_samples")
                protocol(ptsSamples >= 0) { "Invalid segment PTS" }
                val epoch = item.getString("epoch")
                UUID.fromString(epoch)
                val programDateTime = Instant.parse(item.getString("program_date_time"))
                val byteLength = item.getInt("byte_length")
                protocol(byteLength in 1..MAX_SEGMENT_BYTES) { "Invalid live segment byte length" }
                val sha256 = item.getString("sha256").lowercase()
                protocol(SHA256_REGEX.matches(sha256)) { "Invalid live segment SHA-256" }
                val resolved = base.resolve(item.getString("uri"))
                protocol(resolved.isAbsolute && resolved.scheme.isHttp()) { "Segment URI must use HTTP or HTTPS" }
                add(
                    LiveSegmentInfo(
                        sequence, resolved.toString(), duration, sampleCount, ptsSamples,
                        programDateTime, epoch, item.getBoolean("discontinuity"),
                        byteLength, sha256,
                    ),
                )
            }
        }
        protocol(segments.firstOrNull()?.sequence?.let { it == mediaSequence } ?: true) {
            "media_sequence does not match the first segment"
        }
        LiveManifest(mediaSequence, discontinuitySequence, targetDuration, init, segments, title)
    } catch (error: LiveProtocolException) {
        throw error
    } catch (error: Exception) {
        throw LiveProtocolException("Malformed live manifest: ${error.message ?: "invalid JSON"}", error)
    }

    private fun String?.isHttp(): Boolean =
        equals("http", ignoreCase = true) || equals("https", ignoreCase = true)

    private fun protocol(condition: Boolean, message: () -> String) {
        if (!condition) throw LiveProtocolException(message())
    }

    internal const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    internal const val MAX_MANIFEST_BYTES = 1024 * 1024
    internal const val MAX_TITLE_LENGTH = 200
    internal fun supportedCodebooks(variant: EncodecVariant): Set<Int> = when (variant) {
        EncodecVariant.MONO_24_KHZ -> setOf(2, 4, 8, 16, 32)
        EncodecVariant.STEREO_48_KHZ -> setOf(2, 4, 8, 16)
    }
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}

class LiveSequenceTracker {
    private var nextSequence: Long? = null
    private var previousEpoch: String? = null

    fun select(manifest: LiveManifest): LiveSegmentInfo? {
        if (manifest.segments.isEmpty()) return null
        val safeEdgeIndex = manifest.segments.lastIndex
        val safeEdge = manifest.segments[safeEdgeIndex]
        val expected = nextSequence
        if (expected == null || expected < manifest.segments.first().sequence) {
            // Begin far enough behind the published edge to download the
            // complete startup cushion from this one manifest response.
            val startupIndex = (safeEdgeIndex - (STARTUP_BUFFER_SEGMENTS - 1))
                .coerceAtLeast(0)
            return manifest.segments[startupIndex]
        }
        return manifest.segments
            .take(safeEdgeIndex + 1)
            .firstOrNull { it.sequence >= expected }
    }

    fun accept(segment: LiveSegmentInfo): LiveSelection {
        val expected = nextSequence
        val discontinuity = segment.discontinuity ||
            (expected != null && segment.sequence != expected) ||
            (previousEpoch != null && previousEpoch != segment.epoch)
        nextSequence = segment.sequence + 1
        previousEpoch = segment.epoch
        return LiveSelection(segment, discontinuity)
    }

    fun reset() {
        nextSequence = null
        previousEpoch = null
    }

    internal fun expectedSequence(): Long? = nextSequence

    private companion object {
        val STARTUP_BUFFER_SEGMENTS = BuildConfig.LIVE_STARTUP_BUFFER_SEGMENTS
    }
}

class LiveStreamSource(
    private val manifestUrl: String,
    private val networkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fetchManifestBytes: suspend () -> ByteArray = {
        HttpsStreams.readBytes(
            manifestUrl,
            LiveManifestParser.MAX_MANIFEST_BYTES,
            noCache = true,
            dispatcher = networkDispatcher,
            acceptGzip = true,
        )
    },
    private val fetchSegmentBytes: suspend (String) -> ByteArray = { url ->
        HttpsStreams.readBytes(
            url,
            LiveManifestParser.MAX_SEGMENT_BYTES,
            dispatcher = networkDispatcher,
        )
    },
) {
    private val tracker = LiveSequenceTracker()
    private var retryCount = 0
    private var cachedManifest: LiveManifest? = null
    private var streamInit: LiveCodecInit? = null
    var streamTitle: String? = null
        private set

    suspend fun initialize(onStatus: (String) -> Unit): LiveCodecInit {
        streamInit?.let { return it }
        while (currentCoroutineContext().isActive) {
            try {
                onStatus(if (retryCount == 0) "Checking stream format…" else "Reconnecting…")
                val manifest = fetchAndCacheManifest()
                retryCount = 0
                return manifest.init
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: LiveInitializationChangedException) {
                throw changed
            } catch (protocol: LiveProtocolException) {
                throw protocol
            } catch (error: IOException) {
                retryCount++
                onStatus("Network error: ${error.message ?: "retrying"}")
                delay(min(5_000L, 500L * retryCount))
            }
        }
        throw CancellationException("Live stream stopped")
    }

    suspend fun nextSegment(onStatus: (String) -> Unit): DownloadedLiveSegment {
        while (currentCoroutineContext().isActive) {
            try {
                var manifest = cachedManifest
                var selected = manifest?.let(tracker::select)
                if (selected == null) {
                    onStatus(if (retryCount == 0) "Checking live edge…" else "Reconnecting…")
                    manifest = fetchAndCacheManifest()
                    selected = tracker.select(manifest)
                }
                val activeManifest = manifest
                    ?: throw LiveProtocolException("Live manifest was not available")
                if (selected == null) {
                    onStatus("Waiting for sequence ${tracker.expectedSequence() ?: activeManifest.mediaSequence}…")
                    delay(pollDelayMillis(activeManifest))
                    cachedManifest = null
                    continue
                }
                onStatus("Buffering segment ${selected.sequence}…")
                val downloadStarted = LiveDiagnostics.nowMs()
                LiveDiagnostics.info(
                    "segment request seq=${selected.sequence} expectedBytes=${selected.byteLength} " +
                        "thread=${Thread.currentThread().name}",
                )
                val bytes = fetchSegmentBytes(selected.url)
                val downloadMs = LiveDiagnostics.nowMs() - downloadStarted
                verifySegment(bytes, selected, activeManifest.init)
                val accepted = tracker.accept(selected)
                LiveDiagnostics.info(
                    "segment ready seq=${selected.sequence} bytes=${bytes.size} downloadMs=$downloadMs " +
                        "discontinuity=${accepted.discontinuity}",
                )
                retryCount = 0
                return DownloadedLiveSegment(
                    bytes, selected.sequence, accepted.discontinuity,
                    activeManifest.init.codebooks, activeManifest.init.bandwidthKbps,
                    selected.duration, downloadMs,
                    selected.sequence == activeManifest.segments.lastOrNull()?.sequence,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (protocol: LiveProtocolException) {
                throw protocol
            } catch (error: IOException) {
                retryCount++
                LiveDiagnostics.warn(
                    "network retry=$retryCount expectedSeq=${tracker.expectedSequence()} " +
                        "error=${error.message}",
                )
                onStatus("Network error: ${error.message ?: "retrying"}")
                delay(min(5_000L, 500L * retryCount))
            }
        }
        throw CancellationException("Live stream stopped")
    }

    fun jumpToLive() {
        tracker.reset()
        cachedManifest = null
        streamInit = null
        streamTitle = null
    }

    private fun pollDelayMillis(manifest: LiveManifest): Long =
        (manifest.targetDuration * 750).toLong().coerceIn(1_000L, 4_000L)

    private suspend fun fetchAndCacheManifest(): LiveManifest {
        val fetchStarted = LiveDiagnostics.nowMs()
        LiveDiagnostics.info(
            "manifest request expectedSeq=${tracker.expectedSequence()} " +
                "thread=${Thread.currentThread().name}",
        )
        val manifestBytes = fetchManifestBytes()
        val manifest = LiveManifestParser.parse(
            manifestBytes.toString(Charsets.UTF_8),
            manifestUrl,
        )
        val expectedInit = streamInit
        if (expectedInit != null && manifest.init != expectedInit) {
            throw LiveInitializationChangedException(expectedInit, manifest.init)
        }
        streamInit = manifest.init
        streamTitle = manifest.title
        cachedManifest = manifest
        val first = manifest.segments.firstOrNull()?.sequence
        val last = manifest.segments.lastOrNull()?.sequence
        LiveDiagnostics.info(
            "manifest ready bytes=${manifestBytes.size} fetchMs=" +
                "${LiveDiagnostics.nowMs() - fetchStarted} range=$first..$last " +
                "mediaSeq=${manifest.mediaSequence} targetSec=${manifest.targetDuration}",
        )
        return manifest
    }

    companion object {
        fun verifySegment(bytes: ByteArray, segment: LiveSegmentInfo, init: LiveCodecInit) {
            if (bytes.size != segment.byteLength) {
                throw LiveProtocolException(
                    "Segment ${segment.sequence} has ${bytes.size} bytes, expected ${segment.byteLength}",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (digest != segment.sha256) {
                throw LiveProtocolException("Segment ${segment.sequence} failed SHA-256 validation")
            }
            val header = try {
                ByteArrayInputStream(bytes).use(EcdcReader::inspect)
            } catch (error: Exception) {
                throw LiveProtocolException("Segment ${segment.sequence} is not valid ECDC v0", error)
            }
            if (header.version != 0 || header.variant != init.variant ||
                header.usesLanguageModel || header.numCodebooks != init.codebooks ||
                header.numCodebooks !in LiveManifestParser.supportedCodebooks(init.variant) ||
                header.audioLengthSamples != segment.sampleCount
            ) {
                throw LiveProtocolException("Segment ${segment.sequence} codec initialization changed")
            }
        }
    }
}
