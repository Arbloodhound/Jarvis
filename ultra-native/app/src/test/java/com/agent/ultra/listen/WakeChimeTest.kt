package com.agent.ultra.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeChimeTest {
    @Test fun firstWakeChimes() = assertTrue(WakeChime.due(10_000L, 0L))

    /** The gate's fire and the text match of the same "Hey Jarvis" are one wake: one chime. */
    @Test fun noSecondChimeForTheSameWake() {
        assertFalse(WakeChime.due(10_000L + 1_500L, 10_000L))
        assertTrue(WakeChime.due(10_000L + WakeChime.MIN_GAP_MS, 10_000L))
    }
}
