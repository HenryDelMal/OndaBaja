package com.henry.encodec.ecdc

enum class EncodecVariant(
    val wireName: String,
    val sampleRate: Int,
    val channels: Int,
    val frameRate: Int,
    val segmentSamples: Int?,
    val segmentStrideSamples: Int?,
    val normalized: Boolean,
) {
    MONO_24_KHZ(
        wireName = "encodec_24khz",
        sampleRate = 24_000,
        channels = 1,
        frameRate = 75,
        segmentSamples = null,
        segmentStrideSamples = null,
        normalized = false,
    ),
    STEREO_48_KHZ(
        wireName = "encodec_48khz",
        sampleRate = 48_000,
        channels = 2,
        frameRate = 150,
        segmentSamples = 48_000,
        segmentStrideSamples = 47_520,
        normalized = true,
    );

    companion object {
        fun fromWireName(value: String): EncodecVariant =
            entries.firstOrNull { it.wireName == value }
                ?: throw EcdcFormatException("Unsupported EnCodec model: $value")
    }
}

data class EcdcHeader(
    val version: Int,
    val variant: EncodecVariant,
    val audioLengthSamples: Long,
    val numCodebooks: Int,
    val usesLanguageModel: Boolean,
) {
    /** Nominal raw-code bitrate; ECDC stores 10 bits per codebook per frame. */
    val nominalBitrateBps: Int
        get() = numCodebooks * variant.frameRate * 10
}

/** Codebook indices are time-major: codes[time * numCodebooks + codebook]. */
data class EcdcFrame(
    val codebookCount: Int,
    val timeSteps: Int,
    val codes: IntArray,
    val scale: Float?,
    val outputOffsetSamples: Long,
    val outputLengthSamples: Int,
    /** Decoder warm-up audio to discard before exposing this frame as PCM. */
    val trimLeadingSamples: Int = 0,
)

class EcdcFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
