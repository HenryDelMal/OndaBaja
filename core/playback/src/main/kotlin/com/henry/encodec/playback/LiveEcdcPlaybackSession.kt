package com.henry.encodec.playback

import android.util.Log
import com.henry.encodec.decoder.DecodedPcm
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.FilterInputStream
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
    private val diagnosticReporter: ((String) -> Unit)? = null,
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
                var initialSegment = nextSegment()
                sink.flushQueued()
                var started = false
                var initialStats: SegmentStats? = null
                while (!stopRequested && initialStats == null) {
                    report("audio initial segment seq=${initialSegment.sequence} ${sink.diagnosticState()}")
                    try {
                        initialStats = initialSegment.input.use { input ->
                            decode(input) { pcm ->
                                if (stopRequested) return@decode false
                                if (!started) {
                                    sink.start()
                                    if (paused) sink.pause()
                                    started = true
                                    report("audio start seq=${initialSegment.sequence} ${sink.diagnosticState()}")
                                }
                                write(sink, pcm)
                            }
                        }
                    } catch (error: java.io.IOException) {
                        report("audio segment interrupted seq=${initialSegment.sequence} " +
                            "reason=${error.javaClass.simpleName} message=${error.message}")
                        sink.flushQueued()
                        // flushQueued pauses AudioTrack. Restart the first-frame
                        // path after an interrupted initial segment.
                        started = false
                        if (!stopRequested) initialSegment = nextSegment()
                    }
                }
                if (!started || stopRequested || initialStats == null) return@withContext
                logSegment(initialSegment.sequence, initialStats, sink)
                if (!stopRequested) onSegmentPlaying(initialSegment.sequence)
                while (!stopRequested) {
                    coroutineContext.ensureActive()
                    report("audio request next ${sink.diagnosticState()}")
                    val segment = nextSegment()
                    report("audio segment begin seq=${segment.sequence} " +
                        "discontinuity=${segment.discontinuity} ${sink.diagnosticState()}")
                    if (segment.discontinuity) {
                        // These are independently decoded ECDC files. A sequence
                        // jump does not invalidate audio already queued for output.
                        report("audio discontinuity seq=${segment.sequence} policy=preserve_queued_pcm " +
                            sink.diagnosticState())
                        if (!paused) sink.resume()
                    }
                    try {
                        val stats = segment.input.use { decodeAndWrite(it, sink) }
                        logSegment(segment.sequence, stats, sink)
                        if (!stopRequested) onSegmentPlaying(segment.sequence)
                    } catch (error: java.io.IOException) {
                        report("audio segment interrupted seq=${segment.sequence} " +
                            "reason=${error.javaClass.simpleName} message=${error.message}")
                        sink.flushQueued()
                        if (!paused && !stopRequested) sink.resume()
                        report("audio output recovered seq=${segment.sequence} ${sink.diagnosticState()}")
                    }
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
        // EcdcReader buffers ahead and closes its input when the last codec
        // frame has been decoded. For ranged live downloads, drain on close so
        // the transfer can finish and its length/hash validation can complete.
        val drainOnClose = object : FilterInputStream(input) {
            override fun close() {
                if (stopRequested) {
                    super.close()
                } else {
                    val buffer = ByteArray(8 * 1024)
                    while (read(buffer) >= 0) Unit
                    super.close()
                }
            }
        }
        EcdcReader(
            drainOnClose,
            rightContextTimeSteps = decoder.rightContextTimeSteps,
            // One-second causal chunks let the 24 kHz stream begin decoding
            // before a longer segment finishes downloading. Static-file
            // playback keeps EcdcReader's larger four-second default.
            monoChunkSamples = if (decoder.variant == EncodecVariant.MONO_24_KHZ) LIVE_MONO_CHUNK_SAMPLES
                else EcdcReader.MONO_CHUNK_SAMPLES,
        ).use { reader ->
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
        report(
            "play segment seq=$sequence codecFrames=${stats.codecFrames} audioMs=$audioMs " +
                "wallMs=${stats.wallMs} decodeMs=${stats.decodeMs} writeMs=${stats.writeMs} " +
                "${sink.diagnosticState()} thread=${Thread.currentThread().name}",
        )
    }

    private fun report(message: String) {
        if (!diagnosticsEnabled()) return
        runCatching {
            diagnosticReporter?.invoke(message) ?: Log.i(LIVE_LOG_TAG, message)
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
        const val LIVE_MONO_CHUNK_SAMPLES = 24_000
    }
}
