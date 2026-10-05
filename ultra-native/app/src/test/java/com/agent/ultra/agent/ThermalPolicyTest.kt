package com.agent.ultra.agent

import com.agent.ultra.agent.ThermalPolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    private fun lv(h: Float, s: Int = 0, p: Level = Level.NORMAL) = ThermalPolicy.levelFor(h, s, p)

    @Test fun headroomBands() {
        assertEquals(Level.NORMAL, lv(0.3f)); assertEquals(Level.WARM, lv(0.7f))
        assertEquals(Level.HOT, lv(0.85f)); assertEquals(Level.CRITICAL, lv(0.97f))
    }
    @Test fun hysteresisPreventsFlapping() {
        assertEquals(Level.HOT, lv(0.78f, p = Level.HOT))       // stays HOT just under 0.80
        assertEquals(Level.WARM, lv(0.74f, p = Level.HOT))      // drops once clearly below
        assertEquals(Level.WARM, lv(0.78f, p = Level.NORMAL))   // rising from NORMAL to 0.78 is only WARM
    }
    @Test fun unsupportedFailsOpen() = assertEquals(Level.NORMAL, lv(Float.NaN))
    @Test fun osStatusIsAFloor() {
        assertEquals(Level.CRITICAL, lv(Float.NaN, ThermalPolicy.STATUS_SEVERE))
        assertEquals(Level.HOT, lv(0.2f, ThermalPolicy.STATUS_MODERATE))
        assertEquals(Level.CRITICAL, lv(0.97f, ThermalPolicy.STATUS_LIGHT))  // headroom can be worse than status
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
        fun nativeGenerate(t: Int) { if (t > 0) nativeThreads = t } // ultra_llm.cpp: if (threads > 0) llama_set_n_threads
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
