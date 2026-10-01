package com.henry.encodec.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DownloadAheadInputStream private constructor(
    private val input: PipedInputStream,
    private val output: PipedOutputStream,
    private val producer: Job,
    private val failure: AtomicReference<IOException?>,
    private val closed: AtomicBoolean,
    private val activeSource: AtomicReference<InputStream?>,
) : InputStream() {
    override fun read(): Int = checked { input.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checked { input.read(buffer, offset, length) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        producer.cancel()
        runCatching { activeSource.getAndSet(null)?.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }
    private inline fun checked(read: () -> Int): Int = try {
        read().also { if (it < 0) failure.get()?.let { error -> throw error } }
    } catch (error: IOException) {
        throw failure.get() ?: error
    }

    companion object {
        suspend fun open(
            scope: CoroutineScope,
            startupBytes: Int,
            sourceProvider: () -> InputStream,
        ): DownloadAheadInputStream {
            val input = PipedInputStream(CAPACITY_BYTES)
            val output = PipedOutputStream(input)
            val ready = CompletableDeferred<Unit>()
            val failure = AtomicReference<IOException?>()
            val closed = AtomicBoolean(false)
            val activeSource = AtomicReference<InputStream?>()
            val producer = scope.launch(Dispatchers.IO) {
                try {
                    sourceProvider().also(activeSource::set).use { source ->
                        output.use { sink ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                sink.write(buffer, 0, read)
                                total += read
                                if (total >= startupBytes) ready.complete(Unit)
                            }
                        }
                    }
                    ready.complete(Unit)
                } catch (error: Throwable) {
                    if (!closed.get()) {
                        val io = error as? IOException ?: IOException("Remote download failed", error)
                        failure.set(io)
                        ready.completeExceptionally(io)
                    }
                    runCatching { output.close() }
                } finally {
                    activeSource.set(null)
                }
            }
            val stream = DownloadAheadInputStream(
                input, output, producer, failure, closed, activeSource,
            )
            return try {
                ready.await()
                stream
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        }

        private const val CAPACITY_BYTES = 512 * 1024
    }
}
