package com.agent.ultra.agent

import com.agent.ultra.agent.IntentRouter.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, JVM-tested: [WaitBudget] bounds the hidden think budget by wait time. */
class WaitBudgetTest {

    @Test fun unknownSpeedKeepsTierBudget() {
        for (t in Tier.values()) {
            assertEquals(t.thinkBudget, WaitBudget.budget(t, null))
            assertEquals(t.thinkBudget, WaitBudget.budget(t, 0.0))
            assertEquals(t.thinkBudget, WaitBudget.budget(t, -3.0))
            assertEquals(t.thinkBudget, WaitBudget.budget(t, Double.NaN))
            assertEquals(t.thinkBudget, WaitBudget.budget(t, Double.POSITIVE_INFINITY))
        }
    }

    @Test fun pixelEvidenceHiGetsTheFloor() {
        // 1.6 tok/s × 4 s = 6 tokens → floor 16 (~10 s), not ~160 (~100 s)
        assertEquals(16, WaitBudget.budget(Tier.LIGHT, 1.6))
        assertEquals(16, WaitBudget.budget(Tier.DEVICE, 1.6))
    }

    @Test fun slowPhoneNormalAndDeep() {
        assertEquals(64, WaitBudget.budget(Tier.NORMAL, 1.6))   // 32 → floor 64
        assertEquals(128, WaitBudget.budget(Tier.DEEP, 1.6))    // 96 → floor 128
        assertEquals(100, WaitBudget.budget(Tier.NORMAL, 5.0))  // 5 × 20
        assertEquals(300, WaitBudget.budget(Tier.DEEP, 5.0))    // 5 × 60
    }

    @Test fun timeCapFloorsFractionalTokens() {
        assertEquals(41, WaitBudget.budget(Tier.LIGHT, 10.4))   // 41.6 → 41
    }

    @Test fun fastPhoneHitsTierBudget() {
        for (t in Tier.values()) assertEquals(t.thinkBudget, WaitBudget.budget(t, 1000.0))
    }

    @Test fun neverAboveTierBudgetNeverBelowFloor() {
        for (t in Tier.values()) for (s in listOf(0.1, 0.5, 1.0, 3.3, 8.0, 25.0, 80.0)) {
            val b = WaitBudget.budget(t, s)
            assertTrue("$t $s", b <= t.thinkBudget)
            assertTrue("$t $s", b >= minOf(WaitBudget.floorTokens(t), t.thinkBudget))
        }
    }

    @Test fun monotonicInSpeed() {
        for (t in Tier.values()) {
            var prev = 0
            for (i in 1..400) {
                val b = WaitBudget.budget(t, i / 10.0)
                assertTrue("$t ${i / 10.0}", b >= prev); prev = b
            }
        }
    }

    @Test fun lightBudgetLowered() = assertTrue(Tier.LIGHT.thinkBudget < 160)

    @Test fun spokenRequestsThinkOnlySeconds() {
        // 9 tok/s: 5 s = 45 tokens, under every tier's budget.
        assertEquals(45, WaitBudget.spoken(512, 9.0))
        assertEquals(30, WaitBudget.spoken(30, 9.0))                    // never above the budget
        assertEquals(16, WaitBudget.spoken(512, 1.0))                   // the DEVICE floor on a slow phone
        assertEquals(WaitBudget.SPOKEN_UNKNOWN_SPEED_TOKENS, WaitBudget.spoken(512, null))
        assertEquals(WaitBudget.SPOKEN_UNKNOWN_SPEED_TOKENS, WaitBudget.spoken(512, Double.NaN))
    }
}
