package com.agent.jarvis.agent

import com.agent.jarvis.agent.ThermalPolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    private fun lv(h: Float, s: Int = 0, p: Level = Level.NORMAL) = ThermalPolicy.levelFor(h, s, p)

    @Test fun headroomBands() {
        assertEquals(Level.NORMAL, lv(0.3f)); assertEquals(Level.WARM, lv(0.8f))
        assertEquals(Level.HOT, lv(0.9f)); assertEquals(Level.CRITICAL, lv(0.97f))
    }

    /** Pixel 10 Pro XL Feature check, 2026-10-06: a cool, idle phone read headroom 0.58, which
     *  the old WARM at 0.65 was only 0.07 above. Idle, and some warming above it, is NORMAL. */
    @Test fun coolIdlePixelIsNormal() {
        assertEquals(Level.NORMAL, lv(0.58f))
        assertEquals(Level.NORMAL, lv(0.65f))
        assertEquals(Level.NORMAL, lv(0.7f))
        assertEquals(Level.NORMAL, lv(0.58f, p = Level.WARM))   // back to idle after a warm spell
        assertEquals(ThermalPolicy.AmbientMode.NORMAL, lv(0.58f).ambient)
    }
    @Test fun thresholdsLeaveRoomForHysteresis() {
        assertTrue(ThermalPolicy.WARM_AT - ThermalPolicy.HYST > 0.58f + 0.1f)   // dropping back stays clear of idle
        assertTrue(ThermalPolicy.HOT_AT - ThermalPolicy.HYST > ThermalPolicy.WARM_AT)
        assertTrue(ThermalPolicy.CRIT_AT - ThermalPolicy.HYST > ThermalPolicy.HOT_AT)
        assertTrue(ThermalPolicy.CRIT_AT < 1.0f)                                // before the OS's SEVERE
    }
    @Test fun hysteresisPreventsFlapping() {
        assertEquals(Level.HOT, lv(0.85f, p = Level.HOT))       // stays HOT just under 0.87
        assertEquals(Level.WARM, lv(0.8f, p = Level.HOT))       // drops once clearly below
        assertEquals(Level.WARM, lv(0.85f, p = Level.NORMAL))   // rising from NORMAL to 0.85 is only WARM
        assertEquals(Level.WARM, lv(0.72f, p = Level.WARM))     // stays WARM just under 0.75
        assertEquals(Level.CRITICAL, lv(0.92f, p = Level.CRITICAL))
        assertEquals(Level.HOT, lv(0.92f, p = Level.HOT))
    }
    /** Whisper has its own cores: WARM leaves ambient alone, HOT slows it, only CRITICAL pauses it. */
    @Test fun ambientOnlyPausesAtCritical() {
        assertEquals(ThermalPolicy.AmbientMode.NORMAL, Level.WARM.ambient)
        assertEquals(ThermalPolicy.AmbientMode.SLOWED, Level.HOT.ambient)
        assertEquals(ThermalPolicy.AmbientMode.WAKE_ONLY, Level.CRITICAL.ambient)
    }
    @Test fun unsupportedFailsOpen() = assertEquals(Level.NORMAL, lv(Float.NaN))
    @Test fun osStatusIsAFloor() {
        assertEquals(Level.CRITICAL, lv(Float.NaN, ThermalPolicy.STATUS_SEVERE))
        assertEquals(Level.HOT, lv(0.2f, ThermalPolicy.STATUS_MODERATE))
        assertEquals(Level.CRITICAL, lv(0.97f, ThermalPolicy.STATUS_LIGHT))  // headroom can be worse than status
    }

    /** Pixel 10 Pro: status LIGHT on its own must not cut the LLM's threads. */
    @Test fun lightStatusAloneSetsNoFloor() {
        assertEquals(Level.NORMAL, lv(Float.NaN, ThermalPolicy.STATUS_LIGHT))
        assertEquals(Level.NORMAL, lv(0.3f, ThermalPolicy.STATUS_LIGHT))
        assertEquals(6, ThermalPolicy.resolveThreads(0, lv(0.3f, ThermalPolicy.STATUS_LIGHT), 6))
        // leaving a hot spell under LIGHT follows the headroom (and its hysteresis) alone
        assertEquals(Level.NORMAL, lv(0.68f, ThermalPolicy.STATUS_LIGHT, p = Level.WARM))
        assertEquals(Level.WARM, lv(0.72f, ThermalPolicy.STATUS_LIGHT, p = Level.WARM))
    }
    @Test fun headroomStillRaisesTheLevelUnderLight() {
        assertEquals(Level.WARM, lv(0.8f, ThermalPolicy.STATUS_LIGHT))
        assertEquals(Level.HOT, lv(0.9f, ThermalPolicy.STATUS_LIGHT))
        assertEquals(2, ThermalPolicy.resolveThreads(0, lv(0.9f, ThermalPolicy.STATUS_LIGHT), 6))
        assertEquals(Level.HOT, lv(0.85f, ThermalPolicy.STATUS_LIGHT, p = Level.HOT))   // hysteresis kept
    }
    @Test fun moderateAndAboveStillSetAFloor() {
        assertEquals(Level.HOT, lv(Float.NaN, ThermalPolicy.STATUS_MODERATE))
        assertEquals(Level.HOT, lv(0.0f, ThermalPolicy.STATUS_MODERATE, p = Level.NORMAL))
        assertEquals(Level.CRITICAL, lv(0.0f, ThermalPolicy.STATUS_SEVERE))
        assertEquals(Level.CRITICAL, lv(Float.NaN, 4))   // CRITICAL / EMERGENCY / SHUTDOWN
    }
    @Test fun budgets() {
        assertEquals(1200, ThermalPolicy.scaleBudget(1200, Level.NORMAL))
        assertEquals(720, ThermalPolicy.scaleBudget(1200, Level.WARM))
        assertEquals(360, ThermalPolicy.scaleBudget(1200, Level.HOT))
        assertEquals(64, ThermalPolicy.scaleBudget(1200, Level.CRITICAL))
        assertEquals(64, ThermalPolicy.scaleBudget(64, Level.CRITICAL))
    }
    private fun threads(base: Int) = Level.values().map { ThermalPolicy.threadsFor(it, base) }

    @Test fun threadsStepDown() =
        assertEquals(listOf(4, 3, 2, 2), threads(4))   // the old fixed table, unchanged at base 4
    @Test fun threadsScaleWithBase() {
        assertEquals(listOf(6, 4, 2, 2), threads(6))
        assertEquals(listOf(5, 3, 2, 2), threads(5))
        assertEquals(listOf(3, 2, 2, 2), threads(3))
        assertEquals(listOf(2, 2, 2, 2), threads(2))
        assertEquals(listOf(1, 1, 1, 1), threads(1))   // never above base
        assertEquals(listOf(1, 1, 1, 1), threads(0))   // never below 1
    }
    @Test fun threadsNeverRiseWithHeat() {
        for (base in 1..8) {
            val t = threads(base)
            assertTrue("base $base: $t", t.zipWithNext().all { (a, b) -> b <= a } && t.all { it in 1..base })
        }
    }
    @Test fun explicitThreadsAreCappedByHeat() {
        assertEquals(3, ThermalPolicy.resolveThreads(3, Level.NORMAL, 4))
        assertEquals(2, ThermalPolicy.resolveThreads(4, Level.HOT, 4))   // a caller can't outrun the policy
    }

    /** The sticky-thread bug: native keeps the last count when handed 0. */
    @Test fun defaultCallAfterHotGetsNormalCountBack() {
        var nativeThreads = 4                                      // set at load
        fun nativeGenerate(t: Int) { if (t > 0) nativeThreads = t } // jarvis_llm.cpp: if (threads > 0) llama_set_n_threads
        val base = 4
        // a reply while HOT
        nativeGenerate(ThermalPolicy.resolveThreads(0, Level.HOT, base))
        assertEquals(2, nativeThreads)
        // fast path (no explicit count) once the phone is back to NORMAL
        nativeGenerate(ThermalPolicy.resolveThreads(0, Level.NORMAL, base))
        assertEquals(4, nativeThreads)   // passing the raw 0 left this at 2
        // and a default call while WARM gets the WARM count, not "whatever was last"
        nativeGenerate(ThermalPolicy.resolveThreads(0, Level.WARM, base))
        assertEquals(3, nativeThreads)
    }

    @Test fun coolingNoteOnlyForNonDeviceAtCritical() {
        assertEquals(ThermalPolicy.COOLING_MESSAGE, ThermalPolicy.coolingNote(Level.CRITICAL, IntentRouter.Tier.NORMAL))
        assertEquals(null, ThermalPolicy.coolingNote(Level.CRITICAL, IntentRouter.Tier.DEVICE))
        assertEquals(null, ThermalPolicy.coolingNote(Level.HOT, IntentRouter.Tier.DEEP))
    }
}
