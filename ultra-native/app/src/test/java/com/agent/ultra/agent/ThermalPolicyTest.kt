package com.agent.ultra.agent

import com.agent.ultra.agent.ThermalPolicy.Level
import org.junit.Assert.assertEquals
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
    @Test fun threadsStepDown() =
        assertEquals(listOf(4, 3, 2, 2), Level.values().map { it.threads })

    @Test fun coolingNoteOnlyForNonDeviceAtCritical() {
        assertEquals(ThermalPolicy.COOLING_MESSAGE, ThermalPolicy.coolingNote(Level.CRITICAL, IntentRouter.Tier.NORMAL))
        assertEquals(null, ThermalPolicy.coolingNote(Level.CRITICAL, IntentRouter.Tier.DEVICE))
        assertEquals(null, ThermalPolicy.coolingNote(Level.HOT, IntentRouter.Tier.DEEP))
    }
}
