package com.henry.encodec.playback

import com.henry.encodec.decoder.DecodedPcm
import com.henry.encodec.decoder.EncodecDecoder
import com.henry.encodec.ecdc.EcdcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class EcdcPlaybackSession(
    private val decoder: EncodecDecoder,
    private val sharedSink: AudioTrackSink? = null,
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
        // Writes are short, non-blocking, and synchronized with abortQueued().
        // Drop decode-ahead immediately so a replacement session can start.
        currentSink?.abortQueued()
    }

    suspend fun play(
        input: InputStream,
        startSample: Long = 0,
        initialFrameIndex: Int = 0,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        EcdcReader(input, initialFrameIndex, decoder.rightContextTimeSteps).use { reader ->
            require(reader.header.variant == decoder.variant) {
                "File uses ${reader.header.variant.wireName}, decoder is ${decoder.variant.wireName}"
            }
            val ownsSink = sharedSink == null
            val sink = sharedSink ?: AudioTrackSink(
                reader.header.variant.sampleRate,
                reader.header.variant.channels,
            )
            try {
                currentSink = sink
                try {
                    sink.flushQueued()
                    sink.start()
                    if (paused) sink.pause()
                    val requestedStart = startSample.coerceIn(0, reader.header.audioLengthSamples - 1)
                    val stride = reader.header.variant.segmentStrideSamples
                        ?: EcdcReader.MONO_CHUNK_SAMPLES
                    val targetFrameIndex = (requestedStart / stride).toInt()
                    require(initialFrameIndex <= targetFrameIndex)
                    repeat(targetFrameIndex - initialFrameIndex) {
                        if (stopRequested) return@withContext
                        reader.readFrame() ?: return@withContext
                    }

                    // Poll the playback head on a wall-clock cadence so UI
                    // position does not inherit decoder-frame or write batching.
                    val progressIntervalSamples =
                        reader.header.variant.sampleRate.coerceAtLeast(1).toLong()
                    var lastReportedSample = requestedStart - progressIntervalSamples
                    fun reportPlayedPosition(force: Boolean = false) {
                        val playedSample = (requestedStart + sink.playedFrames())
                            .coerceAtMost(reader.header.audioLengthSamples)
                        if (force || playedSample - lastReportedSample >= progressIntervalSamples) {
                            lastReportedSample = playedSample
                            onProgress(
                                (playedSample.toDouble() / reader.header.audioLengthSamples)
                                    .toFloat().coerceIn(0f, 1f),
                            )
                        }
                    }
                    val progressJob = CoroutineScope(coroutineContext).launch {
                        while (isActive && !stopRequested) {
                            delay(1_000)
                            reportPlayedPosition()
                        }
                    }
                    fun writeToSink(pcm: DecodedPcm): Boolean = sink.write(
                        pcm = pcm,
                        shouldStop = { stopRequested },
                    )

                    var emittedSamples = requestedStart
                    // Retain only the short overlap tail. Keeping the complete
                    // frame here delayed startup until frame 2 was decoded.
                    var pendingTail: DecodedPcm? = null
                    var firstFrame = true
                    val overlapSamples = reader.header.variant.segmentSamples
                        ?.minus(reader.header.variant.segmentStrideSamples ?: 0)
                        ?: 0
                    try {
                        while (true) {
                            coroutineContext.ensureActive()
                            if (stopRequested) break
                            val frame = reader.readFrame() ?: break
                            var pcm = decoder.decode(frame)
                            if (firstFrame) {
                                val trim = (requestedStart - frame.outputOffsetSamples)
                                    .coerceIn(0, (pcm.samples.size / pcm.channels).toLong())
                                    .toInt()
                                if (trim > 0) {
                                    pcm = pcm.sliceFrames(trim, pcm.samples.size / pcm.channels)
                                }
                                firstFrame = false
                            }
                            if (overlapSamples == 0) {
                                val pcmFrames = pcm.samples.size / pcm.channels
                                if (!writeToSink(pcm)) break
                                emittedSamples += pcmFrames
                                continue
                            }
                            val pcmFrames = pcm.samples.size / pcm.channels
                            val previousTail = pendingTail
                            if (previousTail == null) {
                                val bodyEnd = (pcmFrames - overlapSamples).coerceAtLeast(0)
                                if (bodyEnd > 0) {
                                    if (!writeToSink(pcm.sliceFrames(0, bodyEnd))) break
                                    emittedSamples += bodyEnd
                                }
                                pendingTail = pcm.sliceFrames(bodyEnd, pcmFrames)
                            } else {
                                val overlap = minOf(
                                    overlapSamples,
                                    previousTail.samples.size / previousTail.channels,
                                    pcmFrames,
                                )
                                if (overlap > 0) {
                                    if (!writeToSink(crossfade(previousTail, pcm, overlap))) break
                                    emittedSamples += overlap
                                }
                                val bodyEnd = (pcmFrames - overlapSamples).coerceAtLeast(overlap)
                                if (bodyEnd > overlap) {
                                    if (!writeToSink(pcm.sliceFrames(overlap, bodyEnd))) break
                                    emittedSamples += bodyEnd - overlap
                                }
                                pendingTail = pcm.sliceFrames(bodyEnd, pcmFrames)
                            }
                        }
                        pendingTail?.let { last ->
                            val remaining = (reader.header.audioLengthSamples - emittedSamples)
                                .coerceAtMost(last.samples.size.toLong() / last.channels)
                                .toInt()
                            if (!stopRequested && remaining > 0) {
                                writeToSink(last.sliceFrames(0, remaining))
                            }
                        }
                        if (!stopRequested) {
                            sink.drain(shouldStop = { stopRequested })
                        }
                    } finally {
                        progressJob.cancelAndJoin()
                    }
                    if (!stopRequested) {
                        reportPlayedPosition(force = true)
                    }
                } finally {
                    currentSink = null
                }
            } finally {
                if (ownsSink) sink.close()
            }
        }
    }

    private fun DecodedPcm.sliceFrames(from: Int, until: Int): DecodedPcm =
        copy(samples = samples.copyOfRange(from * channels, until * channels))

    private fun crossfade(left: DecodedPcm, right: DecodedPcm, frames: Int): DecodedPcm {
        require(left.channels == right.channels)
        val channels = left.channels
        val leftStart = left.samples.size / channels - frames
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
}
