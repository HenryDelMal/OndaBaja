package com.henry.encodec.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.henry.encodec.decoder.DecodedPcm
import kotlin.math.max
import kotlin.math.roundToInt

class AudioTrackSink(
    val sampleRate: Int,
    val channels: Int,
    private val diagnosticsEnabled: () -> Boolean = { false },
) : AutoCloseable {
    private var framesWritten = 0L
    private val channelMask = if (channels == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }
    private val createdTrack = createCompatibleTrack()
    private val track = createdTrack.track
    private val encoding = createdTrack.encoding

    init {
        val bufferFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.bufferSizeInFrames
        } else {
            -1
        }
        if (diagnosticsEnabled()) {
            Log.i(
                "EnCodecLive",
                "AudioTrack created sampleRate=$sampleRate channels=$channels encoding=$encoding " +
                    "bufferFrames=$bufferFrames sessionId=${track.audioSessionId}",
            )
        }
    }

    @Synchronized
    fun start() = track.play()

    @Synchronized
    fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    @Synchronized
    fun resume() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    /** Drop queued PCM and reset playback timing at an explicit discontinuity. */
    @Synchronized
    fun flushQueued() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        track.flush()
        framesWritten = 0L
    }

    /** Immediately discard decode-ahead PCM when replacing this session. */
    @Synchronized
    fun abortQueued() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        track.flush()
        framesWritten = 0L
    }

    /**
     * Uses short blocking writes while playing so AudioTrack sleeps in the
     * kernel instead of polling a full buffer every 5 ms. Each write is bounded
     * to about 20 ms of audio, preserving responsive pause and stop behavior.
     * Paused tracks retain non-blocking writes because their buffers cannot
     * drain until playback resumes. The kernel write must not hold this
     * object's monitor: pause, stop and seek use it to interrupt playback from
     * the main thread.
     */
    fun write(
        pcm: DecodedPcm,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit = {},
    ): Boolean = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        writeFloat(pcm.samples, shouldStop, onPlaybackAdvanced)
    } else {
        writePcm16(pcm.samples, shouldStop, onPlaybackAdvanced)
    }

    private fun writeFloat(
        samples: FloatArray,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit,
    ): Boolean {
        var offset = 0
        while (offset < samples.size && !shouldStop()) {
            val requested = minOf(samples.size - offset, writeChunkSamples)
            val written = track.write(
                samples,
                offset,
                requested,
                writeMode(),
            )
            check(written >= 0) { "AudioTrack float write failed: $written" }
            onPlaybackAdvanced(playedFrames())
            if (written == 0) {
                Thread.sleep(AUDIO_RETRY_DELAY_MS)
                continue
            }
            offset += written
            framesWritten += written / channels
        }
        return offset == samples.size
    }

    private fun writePcm16(
        samples: FloatArray,
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit,
    ): Boolean {
        val converted = ShortArray(samples.size) { index ->
            val sample = samples[index].coerceIn(-1f, 1f)
            val scale = if (sample < 0f) 32_768f else 32_767f
            (sample * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        var offset = 0
        while (offset < converted.size && !shouldStop()) {
            val requested = minOf(converted.size - offset, writeChunkSamples)
            val written = track.write(
                converted,
                offset,
                requested,
                writeMode(),
            )
            check(written >= 0) { "AudioTrack 16-bit write failed: $written" }
            onPlaybackAdvanced(playedFrames())
            if (written == 0) {
                Thread.sleep(AUDIO_RETRY_DELAY_MS)
                continue
            }
            offset += written
            framesWritten += written / channels
        }
        return offset == converted.size
    }

    /** Wait for queued PCM to reach the speaker before closing or changing tracks. */
    fun drain(
        shouldStop: () -> Boolean,
        onPlaybackAdvanced: (Long) -> Unit = {},
    ) {
        while (
            !shouldStop() &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            playedFrames() < framesWritten
        ) {
            onPlaybackAdvanced(playedFrames())
            Thread.sleep(10)
        }
        onPlaybackAdvanced(playedFrames())
    }

    /** Number of PCM frames that have actually reached the playback device. */
    fun playedFrames(): Long = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    private val writeChunkSamples: Int
        get() = (sampleRate / AUDIO_WRITE_CHUNKS_PER_SECOND).coerceAtLeast(1) * channels

    private fun writeMode(): Int =
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            AudioTrack.WRITE_BLOCKING
        } else {
            AudioTrack.WRITE_NON_BLOCKING
        }

    @Synchronized
    override fun close() {
        // A streaming AudioTrack can contain several seconds of decode-ahead
        // PCM. Discard it before stop/release so seeking never waits for stale
        // audio to drain.
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        track.flush()
        runCatching { track.stop() }
        track.release()
    }

    private fun createCompatibleTrack(): CreatedTrack {
        val errors = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            try {
                return CreatedTrack(buildLegacyPcm16Track(), AudioFormat.ENCODING_PCM_16BIT)
            } catch (error: RuntimeException) {
                errors += "legacy-pcm16/minimum: " +
                    (error.message ?: error::class.java.simpleName)
            }
        }
        val attempts = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // Old AudioFlinger implementations can remain resource-starved after
            // a rejected multi-megabyte allocation, so start with safe buffers.
            listOf(
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, EMULATOR_BUFFER_SECONDS),
            )
        } else {
            listOf(
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, DECODE_AHEAD_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_FLOAT, 0),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, EMULATOR_BUFFER_SECONDS),
                TrackAttempt(AudioFormat.ENCODING_PCM_16BIT, 0),
            )
        }.distinct()
        attempts.forEach { attempt ->
            try {
                return CreatedTrack(buildTrack(attempt), attempt.encoding)
            } catch (error: RuntimeException) {
                errors += "${encodingName(attempt.encoding)}/${bufferName(attempt)}: " +
                    (error.message ?: error::class.java.simpleName)
            }
        }
        throw IllegalStateException(
            "Cannot create AudioTrack for $sampleRate Hz, $channels channels. " +
                errors.joinToString("; "),
        )
    }

    @Suppress("DEPRECATION")
    private fun buildLegacyPcm16Track(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) {
            "unsupported minimum buffer ($minBuffer)"
        }
        val candidate = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
            AudioTrack.MODE_STREAM,
        )
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            error("legacy AudioTrack was not initialized")
        }
        return candidate
    }

    private fun buildTrack(attempt: TrackAttempt): AudioTrack {
        val bytesPerSample = if (attempt.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            Float.SIZE_BYTES
        } else {
            Short.SIZE_BYTES
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            attempt.encoding,
        )
        require(minBuffer > 0) {
            "unsupported minimum buffer ($minBuffer)"
        }
        val requestedBuffer = if (attempt.bufferSeconds > 0) {
            sampleRate * channels * bytesPerSample * attempt.bufferSeconds
        } else {
            minBuffer
        }
        val candidate = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(attempt.encoding)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, requestedBuffer))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            error("AudioTrack was not initialized")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Streaming tracks default to a start threshold near their full
            // capacity. This track is intentionally large for decode-ahead,
            // but playback should begin after the platform's minimum buffer.
            val bytesPerFrame = channels * bytesPerSample
            val minimumFrames = (minBuffer / bytesPerFrame).coerceAtLeast(1)
            candidate.setStartThresholdInFrames(minimumFrames)
        }
        return candidate
    }

    private fun encodingName(value: Int): String =
        if (value == AudioFormat.ENCODING_PCM_FLOAT) "float" else "pcm16"

    private fun bufferName(attempt: TrackAttempt): String =
        if (attempt.bufferSeconds == 0) "minimum" else "${attempt.bufferSeconds}s"

    private data class TrackAttempt(
        val encoding: Int,
        val bufferSeconds: Int,
    )

    private data class CreatedTrack(
        val track: AudioTrack,
        val encoding: Int,
    )

    private companion object {
        const val DECODE_AHEAD_SECONDS = 6
        const val EMULATOR_BUFFER_SECONDS = 2
        const val AUDIO_WRITE_CHUNKS_PER_SECOND = 50
        const val AUDIO_RETRY_DELAY_MS = 20L
    }
}
