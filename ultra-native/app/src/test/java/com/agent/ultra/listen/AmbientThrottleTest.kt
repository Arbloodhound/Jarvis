package com.agent.ultra.listen

import com.agent.ultra.agent.ThermalPolicy.AmbientMode.NORMAL
import com.agent.ultra.agent.ThermalPolicy.AmbientMode.SLOWED
import com.agent.ultra.agent.ThermalPolicy.AmbientMode.WAKE_ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientThrottleTest {
    private fun d(m: com.agent.ultra.agent.ThermalPolicy.AmbientMode, win: Boolean = false, gate: Boolean = true) =
        AmbientThrottle.decide(m, win, gate, 2)

    @Test fun normalUnchanged() = assertEquals(AmbientThrottle.Decision(true, 2), d(NORMAL))

    /** HOT: whisper is on its own cores, so overheard speech is still transcribed (one thread),
     *  every utterance, however close together (it used to keep one per 10 s). */
    @Test fun slowedStillTranscribesEverything() = assertEquals(AmbientThrottle.Decision(true, 1), d(SLOWED))
    @Test fun onlyCriticalPausesMemory() = assertFalse(d(WAKE_ONLY).transcribe)
    @Test fun commandWindowAlwaysHeard() {
        assertTrue(d(SLOWED, win = true).transcribe); assertTrue(d(WAKE_ONLY, win = true).transcribe)
    }
    @Test fun noGateNeverGoesDeaf() {
        assertTrue(d(SLOWED, gate = false).transcribe); assertTrue(d(WAKE_ONLY, gate = false).transcribe)
    }
    @Test fun neverMoreThreadsThanBase() = assertEquals(1, AmbientThrottle.decide(SLOWED, false, true, 1).threads)

    private val llm = intArrayOf(2, 3, 4, 5, 6, 7)
    private val little = intArrayOf(0, 1)
    private fun place(busy: Boolean, cool: Boolean, l: IntArray = llm) =
        AmbientThrottle.placement(d(NORMAL), busy, cool, l, little).let { it.first to it.second.toList() }

    /** Brain idle and phone cool: the big cores are free, whisper takes 4 of them. */
    @Test fun idleAndCoolUsesTheBigCores() = assertEquals(4 to llm.toList(), place(busy = false, cool = true))

    @Test fun busyOrWarmStaysOnItsOwnCores() {
        assertEquals(2 to little.toList(), place(busy = true, cool = true))
        assertEquals(2 to little.toList(), place(busy = false, cool = false))
        assertEquals(2 to little.toList(), place(busy = false, cool = true, l = IntArray(0)))   // cores unknown
    }
}
