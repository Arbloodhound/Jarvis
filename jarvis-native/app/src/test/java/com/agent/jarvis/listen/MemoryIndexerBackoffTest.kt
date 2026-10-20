package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryIndexerBackoffTest {
    private val h = 3_600_000L

    @Test fun backoffDoublesFromThreeHoursAndCapsAtAWeek() {
        assertEquals(0L, MemoryIndexer.fetchBackoffMs(0))
        assertEquals(3 * h, MemoryIndexer.fetchBackoffMs(1))
        assertEquals(6 * h, MemoryIndexer.fetchBackoffMs(2))
        assertEquals(12 * h, MemoryIndexer.fetchBackoffMs(3))
        assertEquals(7 * 24 * h, MemoryIndexer.fetchBackoffMs(7))
        assertEquals(7 * 24 * h, MemoryIndexer.fetchBackoffMs(1_000))
    }
}
