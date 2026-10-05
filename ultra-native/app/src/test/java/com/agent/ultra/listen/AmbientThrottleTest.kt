package com.agent.ultra.listen

import com.agent.ultra.agent.ThermalPolicy.AmbientMode.NORMAL
import com.agent.ultra.agent.ThermalPolicy.AmbientMode.SLOWED
import com.agent.ultra.agent.ThermalPolicy.AmbientMode.WAKE_ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientThrottleTest {
    private fun d(m: com.agent.ultra.agent.ThermalPolicy.AmbientMode, win: Boolean = false, gate: Boolean = true,
                  now: Long = 100_000L, last: Long = 95_000L) = AmbientThrottle.decide(m, win, gate, now, last, 2)

    @Test fun normalUnchanged() = assertEquals(AmbientThrottle.Decision(true, 2), d(NORMAL))
    @Test fun slowedThrottlesMemory() {
        assertFalse(d(SLOWED).transcribe)                         // 5 s since last memory capture
        assertTrue(d(SLOWED, last = 80_000L).transcribe)          // 20 s since
        assertEquals(1, d(SLOWED).threads)
    }
    @Test fun wakeOnlyDropsMemory() = assertFalse(d(WAKE_ONLY, last = 0L).transcribe)
    @Test fun commandWindowAlwaysHeard() {
        assertTrue(d(SLOWED, win = true).transcribe); assertTrue(d(WAKE_ONLY, win = true).transcribe)
    }
    @Test fun noGateNeverGoesDeaf() {
        assertTrue(d(SLOWED, gate = false).transcribe); assertTrue(d(WAKE_ONLY, gate = false).transcribe)
    }
}
