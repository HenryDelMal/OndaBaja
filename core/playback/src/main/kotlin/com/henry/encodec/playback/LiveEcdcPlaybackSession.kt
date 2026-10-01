package com.henry.encodec.playback

import android.util.Log
import com.henry.encodec.decoder.DecodedPcm
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

data class LiveEcdcSegment(
    val input: InputStream,
    val sequence: Long,
    val discontinuity: Boolean,
)

/**
 * Opens every live segment as an independent ECDC file while keeping one
 * decoder and one AudioTrack for the complete live session.
 */
class LiveEcdcPlaybackSession(
    private val decoder: EncodecDecoder,
    private val sharedSink: AudioTrackSink? = null,
    private val diagnosticsEnabled: () -> Boolean = { false },
) {
    @Volatile private var currentSink: AudioTrackSink? = null
    @Volatile private var paused = false
    @Volatile private var stopRequested = false

    fun pause() {
        paused = true
        currentSink?.pause()
    }

    fun resume() {
        paused = false
        currentSink?.resume()
    }

    fun stop() {
        stopRequested = true
        currentSink?.abortQueued()
    }

    suspend fun play(
        nextSegment: suspend () -> LiveEcdcSegment,
        onSegmentPlaying: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val ownsSink = sharedSink == null
        val sink = sharedSink ?: AudioTrackSink(
            decoder.variant.sampleRate,
            decoder.variant.channels,
        )
        try {
            currentSink = sink
            try {
                // Decode only the first EnCodec frame before starting the
                // device. This gives AudioTrack about one second of immediate
                // PCM without delaying startup for a complete live segment.
                val initialSegment = nextSegment()
                sink.flushQueued()
                var started = false
                val initialStats = initialSegment.input.use { input ->
                    decode(input) { pcm ->
                        if (stopRequested) return@decode false
                        if (!started) {
                            sink.start()
                            if (paused) sink.pause()
                            started = true
                        }
                        write(sink, pcm)
                    }
                }
                logSegment(initialSegment.sequence, initialStats, sink)
                if (!started || stopRequested) return@withContext
                if (!stopRequested) onSegmentPlaying(initialSegment.sequence)
                while (!stopRequested) {
                    coroutineContext.ensureActive()
                    val segment = nextSegment()
                    if (segment.discontinuity) {
                        sink.flushQueued()
                        if (!paused) sink.resume()
                    }
                    val stats = segment.input.use { decodeAndWrite(it, sink) }
                    logSegment(segment.sequence, stats, sink)
                    if (!stopRequested) onSegmentPlaying(segment.sequence)
                }
            } finally {
                currentSink = null
            }
        } finally {
            if (ownsSink) sink.close()
        }
    }

    private fun decodeAndWrite(input: InputStream, sink: AudioTrackSink): SegmentStats =
        decode(input) { write(sink, it) }

    private fun decode(input: InputStream, emit: (DecodedPcm) -> Boolean): SegmentStats {
        val wallStarted = System.nanoTime()
        var decodeNanos = 0L
        var writeNanos = 0L
        var decodedFrames = 0L
        var codecFrames = 0
        fun timedEmit(pcm: DecodedPcm): Boolean {
            val started = System.nanoTime()
            return emit(pcm).also { writeNanos += System.nanoTime() - started }
        }
        EcdcReader(input, rightContextTimeSteps = decoder.rightContextTimeSteps).use { reader ->
            require(reader.header.variant == decoder.variant) {
                "Live segment uses ${reader.header.variant.wireName}, decoder is ${decoder.variant.wireName}"
            }
            require(!reader.header.usesLanguageModel) { "LM-coded live segments are unsupported" }
            val overlapSamples = reader.header.variant.segmentSamples
                ?.minus(reader.header.variant.segmentStrideSamples ?: 0) ?: 0
            var emittedSamples = 0
            // Retain only the overlap tail so frame 1 is audible after one
            // decoder invocation instead of waiting for frame 2.
            var pendingTail: DecodedPcm? = null
            while (!stopRequested) {
                val frame = reader.readFrame() ?: break
                val decodeStarted = System.nanoTime()
                val pcm = decoder.decode(frame)
                decodeNanos += System.nanoTime() - decodeStarted
                decodedFrames += pcm.frameCount
                codecFrames++
                if (overlapSamples == 0) {
                    if (!timedEmit(pcm)) break
                    emittedSamples += pcm.frameCount
                    continue
                }
                val previousTail = pendingTail
                if (previousTail == null) {
                    val bodyEnd = (pcm.frameCount - overlapSamples).coerceAtLeast(0)
                    if (bodyEnd > 0 && !timedEmit(pcm.sliceFrames(0, bodyEnd))) break
                    emittedSamples += bodyEnd
                    pendingTail = pcm.sliceFrames(bodyEnd, pcm.frameCount)
                } else {
                    val overlap = minOf(overlapSamples, previousTail.frameCount, pcm.frameCount)
                    if (overlap > 0 && !timedEmit(crossfade(previousTail, pcm, overlap))) break
                    emittedSamples += overlap
                    val bodyEnd = (pcm.frameCount - overlapSamples).coerceAtLeast(overlap)
                    if (bodyEnd > overlap && !timedEmit(pcm.sliceFrames(overlap, bodyEnd))) break
                    emittedSamples += bodyEnd - overlap
                    pendingTail = pcm.sliceFrames(bodyEnd, pcm.frameCount)
                }
            }
            pendingTail?.let { last ->
                val remaining = (reader.header.audioLengthSamples - emittedSamples)
                    .coerceAtMost(last.frameCount.toLong()).toInt()
                if (!stopRequested && remaining > 0) timedEmit(last.sliceFrames(0, remaining))
            }
        }
        return SegmentStats(
            wallMs = (System.nanoTime() - wallStarted) / 1_000_000L,
            decodeMs = decodeNanos / 1_000_000,
            writeMs = writeNanos / 1_000_000,
            decodedFrames = decodedFrames,
            codecFrames = codecFrames,
        )
    }

    private fun logSegment(sequence: Long, stats: SegmentStats, sink: AudioTrackSink) {
        if (!diagnosticsEnabled()) return
        val audioMs = stats.decodedFrames * 1_000L / decoder.variant.sampleRate
        runCatching {
            Log.i(
                LIVE_LOG_TAG,
                "play segment seq=$sequence codecFrames=${stats.codecFrames} audioMs=$audioMs " +
                    "wallMs=${stats.wallMs} decodeMs=${stats.decodeMs} writeMs=${stats.writeMs} " +
                    "playedFrames=${sink.playedFrames()} thread=${Thread.currentThread().name}",
            )
        }
    }

    private fun write(sink: AudioTrackSink, pcm: DecodedPcm): Boolean =
        sink.write(pcm, shouldStop = { stopRequested })

    private val DecodedPcm.frameCount: Int get() = samples.size / channels

    private data class SegmentStats(
        val wallMs: Long,
        val decodeMs: Long,
        val writeMs: Long,
        val decodedFrames: Long,
        val codecFrames: Int,
    )

    private fun DecodedPcm.sliceFrames(from: Int, until: Int): DecodedPcm =
        copy(samples = samples.copyOfRange(from * channels, until * channels))

    private fun crossfade(left: DecodedPcm, right: DecodedPcm, frames: Int): DecodedPcm {
        require(left.channels == right.channels)
        val channels = left.channels
        val leftStart = left.frameCount - frames
        val mixed = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val rightWeight = (frame + 1f) / (frames + 1f)
            val leftWeight = 1f - rightWeight
            for (channel in 0 until channels) {
                mixed[frame * channels + channel] =
                    left.samples[(leftStart + frame) * channels + channel] * leftWeight +
                    right.samples[frame * channels + channel] * rightWeight
            }
        }
        return DecodedPcm(mixed, left.sampleRate, channels)
    }

    private companion object {
        const val LIVE_LOG_TAG = "EnCodecLive"
    }
}
