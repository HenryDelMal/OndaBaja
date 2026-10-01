package com.henry.encodec.decoder

import android.content.Context
import com.henry.encodec.ecdc.EcdcFrame
import com.henry.encodec.ecdc.EncodecVariant
import java.io.File

/** Experimental official Vocos EnCodec-24k model, one Eigen inference worker. */
class VocosDecoder(
    modelFile: File,
    context: Context,
    val rescale: Boolean = false,
    private val diagnosticsEnabled: () -> Boolean = { false },
) : EncodecDecoder {
    override val variant = EncodecVariant.MONO_24_KHZ
    // 27 convolution frames plus inverse-STFT overlap; use a small safety margin.
    override val rightContextTimeSteps = 32
    private val powerHint = DecoderPowerHint(context)
    private var handle = nativeCreate(modelFile.absolutePath)

    @Synchronized
    override fun decode(frame: EcdcFrame): DecodedPcm {
        check(handle != 0L) { "Vocos decoder is closed" }
        val target = (frame.outputLengthSamples.toLong() * 1_000_000_000L / 24000 * 7 / 10)
            .coerceIn(100_000_000L, 3_000_000_000L)
        val started = powerHint.beginWork(target)
        return try {
            DecodedPcm(
                nativeDecode(handle, frame.codes, frame.codebookCount, frame.timeSteps,
                    frame.trimLeadingSamples, frame.outputLengthSamples, rescale, diagnosticsEnabled()),
                24000, 1,
            )
        } catch (error: Exception) {
            throw DecoderUnavailableException("Experimental Vocos inference failed", error)
        } finally {
            powerHint.endWork(started)
        }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
        powerHint.close()
    }

    private external fun nativeCreate(path: String): Long
    private external fun nativeDecode(handle: Long, codes: IntArray, codebooks: Int,
        timeSteps: Int, trim: Int, length: Int, rescale: Boolean, diagnostics: Boolean): FloatArray
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("vocos_android") }
    }
}
