package com.henry.encodec.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSelectionTest {
    @Test
    fun selectingAnotherLibraryTrackReplacesTheCurrentTrack() {
        val result = replaceCurrentQueueItem(listOf("first"), 0, "second")

        assertEquals(listOf("second"), result.items)
        assertEquals(0, result.currentIndex)
        assertEquals("first", result.replacedItem)
    }

    @Test
    fun replacementPreservesTracksExplicitlyQueuedAfterCurrent() {
        val result = replaceCurrentQueueItem(listOf("past", "current", "queued"), 1, "selected")

        assertEquals(listOf("past", "selected", "queued"), result.items)
        assertEquals(1, result.currentIndex)
        assertEquals("current", result.replacedItem)
    }

    @Test
    fun firstSelectionCreatesTheInitialQueueEntry() {
        val result = replaceCurrentQueueItem(emptyList<String>(), -1, "first")

        assertEquals(listOf("first"), result.items)
        assertEquals(0, result.currentIndex)
        assertNull(result.replacedItem)
    }
}
