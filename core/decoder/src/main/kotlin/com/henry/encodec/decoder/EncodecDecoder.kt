package com.henry.encodec.decoder

import com.henry.encodec.ecdc.EcdcFrame
import com.henry.encodec.ecdc.EncodecVariant

data class DecodedPcm(
    /** Channel-interleaved, normalized float PCM. */
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
)

interface EncodecDecoder : AutoCloseable {
    val variant: EncodecVariant
    val rightContextTimeSteps: Int get() = 0
    fun decode(frame: EcdcFrame): DecodedPcm
}

class DecoderUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
