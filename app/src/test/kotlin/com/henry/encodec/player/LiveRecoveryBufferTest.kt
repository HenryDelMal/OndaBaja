package com.henry.encodec.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecoveryBufferTest {
    @Test
    fun networkOutageDoesNotConsumeWaitBudgetBeforeAudioIsReady() {
        val buffer = LiveRecoveryBuffer(4_960L)
        assertFalse(buffer.ready(0, 0L))
        assertFalse(buffer.ready(0, 20_000L))
        assertFalse(buffer.ready(1, 21_000L))
        assertFalse(buffer.ready(1, 21_999L))
        assertTrue(buffer.ready(1, 22_000L))
    }

    @Test
    fun secondSegmentResumesImmediatelyWithoutWaitingForLargeReserve() {
        val buffer = LiveRecoveryBuffer(4_960L)
        assertFalse(buffer.ready(1, 10_000L))
        assertTrue(buffer.ready(2, 10_001L))
    }

    @Test
    fun shorterSegmentsUseShorterRecoveryWait() {
        val buffer = LiveRecoveryBuffer(2_000L)
        assertFalse(buffer.ready(1, 0L))
        assertFalse(buffer.ready(1, 499L))
        assertTrue(buffer.ready(1, 500L))
    }
}
