package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrSpeedTest {
    @Test fun picksSmallOnlyWhenWantedReadyAndFastEnough() {
        assertEquals(AmbientConfig.ASR_SMALL_MODEL, AsrSpeed.pick(accurate = true, smallReady = true, smallTooSlow = false))
        assertEquals(AmbientConfig.ASR_MODEL, AsrSpeed.pick(accurate = true, smallReady = false, smallTooSlow = false))
        assertEquals(AmbientConfig.ASR_MODEL, AsrSpeed.pick(accurate = true, smallReady = true, smallTooSlow = true))
        assertEquals(AmbientConfig.ASR_MODEL, AsrSpeed.pick(accurate = false, smallReady = true, smallTooSlow = false))
    }

    @Test fun needsAFullWindowBeforeJudging() {
        val s = AsrSpeed(window = 4)
        repeat(3) { s.record(1_000, 3_000) }
        assertFalse(s.tooSlow())
        s.record(1_000, 3_000)
        assertTrue(s.tooSlow())
    }

    @Test fun mostlyFasterThanSpeechIsFine() {
        val s = AsrSpeed(window = 5)
        listOf(400L, 500L, 2_500L, 600L, 3_000L).forEach { s.record(1_000, it) }   // two slow outliers
        assertFalse(s.tooSlow())
    }

    @Test fun onlyRecentUtterancesCount() {
        val s = AsrSpeed(window = 3)
        repeat(3) { s.record(1_000, 2_000) }
        assertTrue(s.tooSlow())
        repeat(3) { s.record(1_000, 500) }
        assertFalse(s.tooSlow())
    }
}
