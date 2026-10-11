package com.henry.encodec.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32C

class ElTcpStartupTest {
    @Test
    fun firstSegmentIsAvailableWhileNextFetchWaitsOnTheSameSocket(): Unit = runBlocking {
        val audio = ecdcSegment()
        val checksum = CRC32C().apply { update(audio) }.value
        val manifest = manifest(audio.size, checksum)
        val secondRequested = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverJob = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val output = DataOutputStream(socket.getOutputStream())
                    assertEquals('i'.code, input.read())
                    output.writeByte('m'.code)
                    output.write(varint(manifest.size.toLong()))
                    output.write(manifest)
                    output.flush()
                    for (sequence in 100L..101L) {
                        var command = input.read()
                        while (command == 'h'.code) {
                            output.writeByte('h'.code)
                            output.flush()
                            command = input.read()
                        }
                        assertEquals('f'.code, command)
                        assertEquals(sequence, readVarint(input))
                        if (sequence == 101L) {
                            secondRequested.countDown()
                            assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                        }
                        output.writeByte('S'.code)
                        output.write(varint(audio.size.toLong()))
                        output.writeInt(checksum.toInt())
                        output.writeByte(0)
                        output.write(audio)
                        output.flush()
                    }
                }
            }
            val source = LiveStreamSource(
                "https://example.com/radio/stream.pb",
                startupLookbackMs = 30_000,
                tcpEndpoint = ElTcpEndpoint("127.0.0.1", server.localPort),
            )
            try {
                withTimeout(5_000) {
                    source.initialize {}
                    val first = source.nextSegment {}
                    assertEquals(100L, first.sequence)
                    val background = async { source.nextSegment {} }
                    assertTrue(withContext(Dispatchers.IO) {
                        secondRequested.await(2, TimeUnit.SECONDS)
                    })
                    // Segment 101 is deliberately unavailable. Segment 100 is
                    // already readable by playback, despite the six-entry manifest.
                    assertFalse(background.isCompleted)
                    first.input.use { assertArrayEquals(audio, it.readBytes()) }
                    releaseSecond.countDown()
                    val second = background.await()
                    assertEquals(101L, second.sequence)
                    second.input.use { assertArrayEquals(audio, it.readBytes()) }
                }
                serverJob.get(5, TimeUnit.SECONDS)
            } finally {
                releaseSecond.countDown()
                source.close()
                executor.shutdownNow()
            }
        }
    }

    private fun ecdcSegment(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val header = "{\"m\":\"encodec_24khz\",\"al\":120000,\"nc\":2,\"lm\":false}".toByteArray()
            out.writeBytes("ECDC")
            out.writeByte(0)
            out.writeInt(header.size)
            out.write(header)
            out.write(ByteArray(938))
        }
    }.toByteArray()

    private fun manifest(byteLength: Int, checksum: Long): ByteArray {
        // Compact v2 protobuf: six five-second segments, starting at seq 100.
        val segment = byteArrayOf(8) + varint(byteLength.toLong()) + byteArrayOf(21) +
            ByteArray(4) { index -> (checksum ushr (8 * index)).toByte() }
        val group = byteArrayOf(8, 1) + (1..6).flatMap {
            (byteArrayOf(26) + varint(segment.size.toLong()) + segment).toList()
        }.toByteArray()
        return byteArrayOf(8, 2, 16, 100, 24) + varint(120_000) +
            byteArrayOf(42) + varint(group.size.toLong()) + group
    }

    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().also { output ->
        var rest = value
        while (rest >= 128) {
            output.write((rest.toInt() and 127) or 128)
            rest = rest ushr 7
        }
        output.write(rest.toInt())
    }.toByteArray()

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            val next = input.read()
            check(next >= 0)
            result = result or ((next and 127).toLong() shl shift)
            if (next and 128 == 0) return result
        }
        error("Invalid test request varint")
    }
}
