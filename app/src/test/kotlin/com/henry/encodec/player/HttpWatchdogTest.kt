package com.henry.encodec.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class HttpWatchdogTest {
    @Test(timeout = 5000)
    fun stuckNetworkWorkerCannotBlockRecoveryAfterDeadline() = runBlocking {
        val release = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        val job = launch {
            try {
                HttpsStreams.boundedRequest(150L) {
                    // Simulate a network call that ignores interruption.
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    byteArrayOf()
                }
            } catch (_: SocketTimeoutException) {
                timedOut.set(true)
            }
        }
        try {
            withTimeout(1500L) { job.join() }
            assertTrue(timedOut.get())
            // The next request can run while the abandoned worker is stuck.
            assertEquals(123, HttpsStreams.boundedRequest(500L) { 123 })
        } finally {
            release.countDown()
        }
    }
}
