package com.henry.encodec.ecdc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcdcReaderTest {
    @Test
    fun `reads official version zero header`() {
        val bytes = file("encodec_24khz", 24_000, 2, false, intArrayOf(0, 1, 1023, 42))
        val header = EcdcReader.inspect(ByteArrayInputStream(bytes))

        assertEquals(EncodecVariant.MONO_24_KHZ, header.variant)
        assertEquals(24_000, header.audioLengthSamples)
        assertEquals(2, header.numCodebooks)
        assertEquals(1_500, header.nominalBitrateBps)
    }

    @Test
    fun `unpacks little endian 10 bit codes in time major order`() {
        val expected = IntArray(150) { (it * 17) % 1024 }
        val bytes = file("encodec_24khz", 24_000, 2, false, expected)

        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            val frame = requireNotNull(reader.readFrame())
            assertContentEquals(expected, frame.codes)
            assertEquals(75, frame.timeSteps)
            assertEquals(null, reader.readFrame())
        }
    }

    @Test
    fun `rejects lm stream before treating arithmetic codes as raw bits`() {
        val bytes = file("encodec_24khz", 24_000, 2, true, intArrayOf())
        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            assertFailsWith<EcdcFormatException> { reader.readFrame() }
        }
    }

    @Test
    fun `chunks long 24 kHz streams with decoder context`() {
        val codebooks = 3
        val expected = IntArray(375 * codebooks) { (it * 29) % 1024 }
        val bytes = file("encodec_24khz", 120_000, codebooks, false, expected)

        EcdcReader(ByteArrayInputStream(bytes)).use { reader ->
            val first = requireNotNull(reader.readFrame())
            assertEquals(300, first.timeSteps)
            assertEquals(96_000, first.outputLengthSamples)
            assertEquals(0, first.trimLeadingSamples)
            assertContentEquals(expected.copyOfRange(0, 300 * codebooks), first.codes)

            val second = requireNotNull(reader.readFrame())
            assertEquals(150, second.timeSteps)
            assertEquals(96_000, second.outputOffsetSamples)
            assertEquals(24_000, second.outputLengthSamples)
            assertEquals(24_000, second.trimLeadingSamples)
            assertContentEquals(expected.copyOfRange(225 * codebooks, expected.size), second.codes)
            assertEquals(null, reader.readFrame())
        }
    }

    @Test
    fun `preserves exact header and calculates direct HQ frame offsets`() {
        val bytes = file("encodec_48khz", 480_000, 4, false, intArrayOf())
        val headerBytes = EcdcReader.readHeaderBytes(ByteArrayInputStream(bytes))
        val header = EcdcReader.inspect(ByteArrayInputStream(headerBytes))
        val bytesPerFrame = 4L + (4L * 150L * 10L + 7L) / 8L

        assertContentEquals(bytes.copyOf(headerBytes.size), headerBytes)
        assertEquals(
            headerBytes.size.toLong() + 7L * bytesPerFrame,
            EcdcReader.frameByteOffset(header, headerBytes.size, 7),
        )
    }

    @Test
    fun `calculates byte aligned mono chunk offsets`() {
        val codebooks = 2
        val codes = IntArray(3_000 * codebooks) { (it * 31) % 1024 }
        val bytes = file("encodec_24khz", 960_000, codebooks, false, codes)
        val headerBytes = EcdcReader.readHeaderBytes(ByteArrayInputStream(bytes))
        val header = EcdcReader.inspect(ByteArrayInputStream(headerBytes))
        val chunkIndex = 6
        val bytesPerChunk = 300L * codebooks * 10L / 8L
        val offset = EcdcReader.monoChunkByteOffset(header, headerBytes.size, chunkIndex)

        assertEquals(
            headerBytes.size.toLong() + chunkIndex * bytesPerChunk,
            offset,
        )
        val rangedFile = headerBytes + bytes.copyOfRange(offset.toInt(), bytes.size)
        EcdcReader(ByteArrayInputStream(rangedFile), initialFrameIndex = chunkIndex).use { reader ->
            val frame = requireNotNull(reader.readFrame())
            assertEquals(chunkIndex.toLong() * EcdcReader.MONO_CHUNK_SAMPLES, frame.outputOffsetSamples)
            assertContentEquals(
                codes.copyOfRange(
                    chunkIndex * 300 * codebooks,
                    (chunkIndex + 1) * 300 * codebooks,
                ),
                frame.codes,
            )
        }
    }

    @Test
    fun `vocos lookahead preserves tokens and output timeline including short final chunk`() {
        for (length in listOf(120_000, 192_013)) {
            val books = 4
            val codes = IntArray(((length + 319) / 320) * books) { (it * 31) % 1024 }
            val bytes = file("encodec_24khz", length, books, false, codes)
            EcdcReader(ByteArrayInputStream(bytes), rightContextTimeSteps = 32).use { reader ->
                var offset = 0
                while (offset < length) {
                    val frame = requireNotNull(reader.readFrame())
                    val history = if (offset == 0) 0 else 75
                    val samples = minOf(96_000, length - offset)
                    val outputSteps = (samples + 319) / 320
                    val ahead = minOf(32, (length - offset - samples + 319) / 320)
                    val firstStep = offset / 320 - history
                    assertEquals(offset.toLong(), frame.outputOffsetSamples)
                    assertEquals(samples, frame.outputLengthSamples)
                    assertEquals(history * 320, frame.trimLeadingSamples)
                    assertEquals(history + outputSteps + ahead, frame.timeSteps)
                    assertContentEquals(codes.copyOfRange(firstStep * books,
                        (firstStep + frame.timeSteps) * books), frame.codes)
                    offset += samples
                }
                assertEquals(null, reader.readFrame())
            }
        }
    }

    @Test
    fun `vocos lookahead works after direct mono seek`() {
        val books = 2
        val codes = IntArray(900 * books) { it % 1024 }
        val bytes = file("encodec_24khz", 288_000, books, false, codes)
        val headerBytes = EcdcReader.readHeaderBytes(ByteArrayInputStream(bytes))
        val header = EcdcReader.inspect(ByteArrayInputStream(headerBytes))
        val byteOffset = EcdcReader.monoChunkByteOffset(header, headerBytes.size, 1)
        val ranged = headerBytes + bytes.copyOfRange(byteOffset.toInt(), bytes.size)
        EcdcReader(ByteArrayInputStream(ranged), 1, 32).use { reader ->
            val frame = requireNotNull(reader.readFrame())
            assertEquals(96_000L, frame.outputOffsetSamples)
            assertEquals(0, frame.trimLeadingSamples)
            assertContentEquals(codes.copyOfRange(300 * books, 632 * books), frame.codes)
        }
    }

    private fun file(model: String, length: Int, codebooks: Int, lm: Boolean, codes: IntArray): ByteArray {
        val metadata = "{\"m\":\"$model\",\"al\":$length,\"nc\":$codebooks,\"lm\":$lm}"
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("ECDC")
                out.writeByte(0)
                out.writeInt(metadata.toByteArray().size)
                out.writeBytes(metadata)
                pack10(codes).forEach { out.writeByte(it.toInt()) }
            }
        }.toByteArray()
    }

    private fun pack10(values: IntArray): ByteArray {
        val result = ByteArrayOutputStream()
        var current = 0L
        var bits = 0
        values.forEach { value ->
            current += value.toLong() shl bits
            bits += 10
            while (bits >= 8) {
                result.write((current and 0xff).toInt())
                current = current shr 8
                bits -= 8
            }
        }
        if (bits > 0) result.write(current.toInt())
        return result.toByteArray()
    }
}
