package com.henry.encodec.ecdc

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.min

class EcdcReader(
    input: InputStream,
    initialFrameIndex: Int = 0,
    private val rightContextTimeSteps: Int = 0,
    private val monoChunkSamples: Int = MONO_CHUNK_SAMPLES,
) : AutoCloseable {
    init { require(rightContextTimeSteps in 0..75) }
    init { require(monoChunkSamples in 320..MONO_CHUNK_SAMPLES && monoChunkSamples % 320 == 0) }
    private val source = DataInputStream(BufferedInputStream(input))
    val header: EcdcHeader = readHeader(source)
    private val frameCount = frameCount(header, monoChunkSamples)
    private var framesRead = initialFrameIndex.also {
        require(it in 0..frameCount) { "Invalid initial ECDC frame index: $it" }
    }
    private val monoBits = if (header.variant == EncodecVariant.MONO_24_KHZ) {
        BitUnpacker(10, source)
    } else {
        null
    }
    private var monoContextCodes = IntArray(0)
    private var monoPendingCodes = IntArray(0)

    fun readFrame(): EcdcFrame? {
        if (framesRead >= frameCount) return null
        if (header.usesLanguageModel) {
            throw EcdcFormatException(
                "This file uses EnCodec language-model entropy coding; " +
                    "the milestone-1 decoder accepts files created with --no-lm only.",
            )
        }

        if (header.variant == EncodecVariant.MONO_24_KHZ) {
            return readMonoChunk()
        }

        val variant = header.variant
        val stride = variant.segmentStrideSamples ?: header.audioLengthSamples.toInt()
        val segment = variant.segmentSamples ?: header.audioLengthSamples.toInt()
        val outputOffset = framesRead.toLong() * stride
        val remaining = header.audioLengthSamples - outputOffset
        val outputLength = min(remaining, segment.toLong()).toInt()
        val timeSteps = ceil(outputLength.toDouble() * variant.frameRate / variant.sampleRate).toInt()

        val scale = if (variant.normalized) source.readFloat() else null
        // The reference writer flushes and recreates its packer for every frame,
        // so any unused bits in the final byte must not leak into the next frame.
        val bits = BitUnpacker(10, source)
        val codes = IntArray(timeSteps * header.numCodebooks)
        for (time in 0 until timeSteps) {
            for (codebook in 0 until header.numCodebooks) {
                codes[time * header.numCodebooks + codebook] = bits.pull()
                    ?: throw EcdcFormatException("Code stream ended in frame $framesRead")
            }
        }

        return EcdcFrame(
            codebookCount = header.numCodebooks,
            timeSteps = timeSteps,
            codes = codes,
            scale = scale,
            outputOffsetSamples = outputOffset,
            outputLengthSamples = outputLength,
        ).also { framesRead++ }
    }

    /**
     * The official 24 kHz container has one unbounded causal code stream. Split
     * it into four-second output chunks and prepend a short latent history. The
     * larger useful window avoids repeatedly decoding one second of history for
     * every two seconds of audio, which was too expensive on phones.
     */
    private fun readMonoChunk(): EcdcFrame {
        val outputOffset = framesRead.toLong() * monoChunkSamples
        val outputLength = min(
            header.audioLengthSamples - outputOffset,
            monoChunkSamples.toLong(),
        ).toInt()
        val newTimeSteps = ceil(
            outputLength.toDouble() * header.variant.frameRate / header.variant.sampleRate,
        ).toInt()
        val newCodes = IntArray(newTimeSteps * header.numCodebooks)
        val bits = requireNotNull(monoBits)
        val pendingSteps = min(monoPendingCodes.size / header.numCodebooks, newTimeSteps)
        monoPendingCodes.copyInto(newCodes, endIndex = pendingSteps * header.numCodebooks)
        for (time in pendingSteps until newTimeSteps) {
            for (codebook in 0 until header.numCodebooks) {
                newCodes[time * header.numCodebooks + codebook] = bits.pull()
                    ?: throw EcdcFormatException("Code stream ended in mono chunk $framesRead")
            }
        }

        val contextTimeSteps = monoContextCodes.size / header.numCodebooks
        val remainingSamples = header.audioLengthSamples - outputOffset - outputLength
        val aheadSteps = min(rightContextTimeSteps, ceil(remainingSamples / 320.0).toInt())
        val ahead = IntArray(aheadSteps * header.numCodebooks)
        for (index in ahead.indices) {
            ahead[index] = bits.pull() ?: throw EcdcFormatException("Code stream ended in mono look-ahead")
        }
        monoPendingCodes = ahead
        val combined = monoContextCodes + newCodes + ahead
        val retainedTimeSteps = min(MONO_CONTEXT_TIME_STEPS, newTimeSteps)
        monoContextCodes = newCodes.copyOfRange(
            (newTimeSteps - retainedTimeSteps) * header.numCodebooks,
            newCodes.size,
        )

        return EcdcFrame(
            codebookCount = header.numCodebooks,
            timeSteps = contextTimeSteps + newTimeSteps + aheadSteps,
            codes = combined,
            scale = null,
            outputOffsetSamples = outputOffset,
            outputLengthSamples = outputLength,
            trimLeadingSamples = contextTimeSteps * header.variant.sampleRate /
                header.variant.frameRate,
        ).also { framesRead++ }
    }

    override fun close() = source.close()

    companion object {
        private val magic = byteArrayOf('E'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte())
        private const val MAX_METADATA_BYTES = 64 * 1024

        fun inspect(input: InputStream): EcdcHeader =
            DataInputStream(BufferedInputStream(input)).use(::readHeader)

        /** Reads exactly the version-0 header without consuming any code bytes. */
        fun readHeaderBytes(input: InputStream): ByteArray {
            val fixed = ByteArray(9)
            DataInputStream(input).readFully(fixed)
            if (!fixed.copyOfRange(0, 4).contentEquals(magic)) {
                throw EcdcFormatException("File is not in ECDC format")
            }
            val metadataSize = ((fixed[5].toInt() and 0xff) shl 24) or
                ((fixed[6].toInt() and 0xff) shl 16) or
                ((fixed[7].toInt() and 0xff) shl 8) or
                (fixed[8].toInt() and 0xff)
            if (metadataSize !in 2..MAX_METADATA_BYTES) {
                throw EcdcFormatException("Invalid ECDC metadata size: $metadataSize")
            }
            val metadata = ByteArray(metadataSize)
            DataInputStream(input).readFully(metadata)
            return fixed + metadata
        }

        /**
         * Byte position of a 48 kHz HQ frame. Every complete HQ frame has a
         * fixed-size scale value and a separately padded 10-bit code payload.
         */
        fun frameByteOffset(header: EcdcHeader, headerBytes: Int, frameIndex: Int): Long {
            require(header.variant == EncodecVariant.STEREO_48_KHZ)
            require(frameIndex >= 0)
            val codeBitsPerFrame = header.numCodebooks.toLong() *
                header.variant.frameRate * 10L
            val scaleBytes = if (header.variant.normalized) 4L else 0L
            val frameBytes = scaleBytes + (codeBitsPerFrame + 7L) / 8L
            return headerBytes.toLong() + frameIndex.toLong() * frameBytes
        }

        /**
         * Byte position of a four-second 24 kHz reader chunk. The official
         * payload is one continuous 10-bit stream, but a 300-step chunk is
         * always byte-aligned for every supported codebook count.
         */
        fun monoChunkByteOffset(header: EcdcHeader, headerBytes: Int, chunkIndex: Int): Long {
            require(header.variant == EncodecVariant.MONO_24_KHZ)
            require(chunkIndex >= 0)
            val timeStepsPerChunk = MONO_CHUNK_SAMPLES.toLong() *
                header.variant.frameRate / header.variant.sampleRate
            val bitsPerChunk = timeStepsPerChunk * header.numCodebooks * 10L
            require(bitsPerChunk % 8L == 0L)
            return headerBytes.toLong() + chunkIndex.toLong() * (bitsPerChunk / 8L)
        }

        private fun readHeader(source: DataInputStream): EcdcHeader {
            val actualMagic = ByteArray(4).also(source::readFully)
            if (!actualMagic.contentEquals(magic)) throw EcdcFormatException("File is not in ECDC format")

            val version = source.readUnsignedByte()
            if (version != 0) throw EcdcFormatException("Unsupported ECDC version: $version")

            val metadataSize = source.readInt()
            if (metadataSize !in 2..MAX_METADATA_BYTES) {
                throw EcdcFormatException("Invalid ECDC metadata size: $metadataSize")
            }
            val json = ByteArray(metadataSize).also(source::readFully)
                .toString(StandardCharsets.UTF_8)

            return try {
                val model = json.stringField("m")
                val audioLength = json.longField("al")
                val codebooks = json.intField("nc")
                val lm = json.booleanField("lm")
                if (audioLength <= 0) throw EcdcFormatException("Audio length must be positive")
                if (codebooks !in 1..32) throw EcdcFormatException("Invalid codebook count: $codebooks")
                EcdcHeader(version, EncodecVariant.fromWireName(model), audioLength, codebooks, lm)
            } catch (error: EcdcFormatException) {
                throw error
            } catch (error: Exception) {
                throw EcdcFormatException("Invalid ECDC metadata", error)
            }
        }

        private fun frameCount(header: EcdcHeader, monoChunkSamples: Int): Int {
            if (header.variant == EncodecVariant.MONO_24_KHZ) {
                return ceil(header.audioLengthSamples.toDouble() / monoChunkSamples).toInt()
            }
            val stride = header.variant.segmentStrideSamples ?: return 1
            return ceil(header.audioLengthSamples.toDouble() / stride).toInt()
        }

        const val MONO_CHUNK_SAMPLES = 96_000
        /** One causal second, matching the bounded C++ command-line decoder. */
        const val MONO_CONTEXT_TIME_STEPS = 75

        // The version-0 header has four fixed primitive fields. Keeping this parser small
        // prevents a JSON library from becoming part of the decoder's hot-path artifact.
        private fun String.stringField(name: String): String =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(this)?.groupValues?.get(1)
                ?: throw EcdcFormatException("Missing metadata field: $name")

        private fun String.longField(name: String): Long =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)")
                .find(this)?.groupValues?.get(1)?.toLong()
                ?: throw EcdcFormatException("Missing metadata field: $name")

        private fun String.intField(name: String): Int = longField(name).toInt()

        private fun String.booleanField(name: String): Boolean =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)")
                .find(this)?.groupValues?.get(1)?.toBooleanStrict()
                ?: throw EcdcFormatException("Missing metadata field: $name")
    }
}

internal class BitUnpacker(
    private val bits: Int,
    private val source: InputStream,
) {
    private val mask = (1 shl bits) - 1
    private var currentValue = 0L
    private var currentBits = 0

    fun pull(): Int? {
        while (currentBits < bits) {
            val byte = source.read()
            if (byte == -1) return null
            currentValue += byte.toLong() shl currentBits
            currentBits += 8
        }
        val result = (currentValue and mask.toLong()).toInt()
        currentValue = currentValue shr bits
        currentBits -= bits
        return result
    }
}
