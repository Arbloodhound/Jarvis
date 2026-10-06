package com.agent.ultra.agent

import com.agent.ultra.agent.IntentRouter.Tier
import com.agent.ultra.agent.ThermalPolicy.Level
import com.agent.ultra.agent.WaitBudget.Depth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, JVM-tested: Settings → Thinking depth mapped onto [WaitBudget] and the heat cut. */
class ThinkingDepthTest {

    @Test fun quickIsTodaysCaps() {
        for (t in Tier.values()) {
            assertEquals(WaitBudget.maxSilentSeconds(t), WaitBudget.maxSilentSeconds(t, Depth.QUICK), 0.0)
            assertEquals(t.thinkBudget, WaitBudget.tokenCeiling(t, Depth.QUICK))
            for (s in listOf(null, 1.6, 5.0, 10.0, 1000.0)) {
                assertEquals("$t $s", WaitBudget.budget(t, s), WaitBudget.budget(t, s, Depth.QUICK))
            }
        }
        assertEquals(20.0, WaitBudget.maxSilentSeconds(Tier.NORMAL, Depth.QUICK), 0.0)
        assertEquals(60.0, WaitBudget.maxSilentSeconds(Tier.DEEP, Depth.QUICK), 0.0)
    }

    @Test fun deepLiftsTheWaitToMinutes() {
        assertTrue(WaitBudget.maxSilentSeconds(Tier.NORMAL, Depth.DEEP) >= 180.0)
        assertTrue(WaitBudget.maxSilentSeconds(Tier.DEEP, Depth.DEEP) >= 300.0)
    }

    @Test fun commandsAndGreetingsStayQuickAtEveryDepth() {
        for (d in Depth.values()) for (t in listOf(Tier.DEVICE, Tier.LIGHT)) {
            assertEquals(4.0, WaitBudget.maxSilentSeconds(t, d), 0.0)
            assertEquals(t.thinkBudget, WaitBudget.tokenCeiling(t, d))
            assertEquals(WaitBudget.budget(t, 10.0), WaitBudget.budget(t, 10.0, d))
        }
    }

    /** Qwen3-4B Thinking 2507 on the Pixel: ~10 tok/s. */
    @Test fun fourBAtPixelSpeed() {
        assertEquals(200, WaitBudget.budget(Tier.NORMAL, 10.0, Depth.QUICK))     // 20 s
        assertEquals(450, WaitBudget.budget(Tier.NORMAL, 10.0, Depth.BALANCED))  // 45 s
        assertEquals(1800, WaitBudget.budget(Tier.NORMAL, 10.0, Depth.DEEP))     // 3 min
        assertEquals(600, WaitBudget.budget(Tier.DEEP, 10.0, Depth.QUICK))       // 60 s
        assertEquals(1200, WaitBudget.budget(Tier.DEEP, 10.0, Depth.BALANCED))   // 2 min
        assertEquals(3000, WaitBudget.budget(Tier.DEEP, 10.0, Depth.DEEP))       // 5 min
    }

    @Test fun ceilingsHoldOnAFastPhone() {
        for (d in Depth.values()) for (t in Tier.values()) {
            assertEquals("$t $d", WaitBudget.tokenCeiling(t, d), WaitBudget.budget(t, 1000.0, d))
        }
    }

    @Test fun unknownSpeedKeepsTheTierBudgetAtEveryDepth() {
        for (d in Depth.values()) for (t in Tier.values()) {
            assertEquals(t.thinkBudget, WaitBudget.budget(t, null, d))
            assertEquals(t.thinkBudget, WaitBudget.budget(t, Double.NaN, d))
        }
    }

    @Test fun deeperNeverThinksLess() {
        for (t in Tier.values()) for (s in listOf(0.5, 1.6, 3.0, 8.0, 11.0, 40.0)) {
            val q = WaitBudget.budget(t, s, Depth.QUICK)
            val b = WaitBudget.budget(t, s, Depth.BALANCED)
            val dd = WaitBudget.budget(t, s, Depth.DEEP)
            assertTrue("$t $s", q <= b && b <= dd)
        }
    }

    @Test fun floorsStayAtEveryDepth() {
        for (d in Depth.values()) for (t in Tier.values()) {
            assertTrue(WaitBudget.budget(t, 0.1, d) >= minOf(WaitBudget.floorTokens(t), t.thinkBudget))
        }
    }

    @Test fun deepIsNotCutOnAWarmPhone() {
        assertEquals(1800, WaitBudget.scaleForHeat(1800, Level.WARM, Depth.DEEP))
        assertEquals(1080, WaitBudget.scaleForHeat(1800, Level.WARM, Depth.BALANCED))   // x0.6 as before
        assertEquals(1080, WaitBudget.scaleForHeat(1800, Level.WARM, Depth.QUICK))
    }

    @Test fun hotAndCriticalCutAtEveryDepth() {
        for (d in Depth.values()) {
            assertEquals(ThermalPolicy.scaleBudget(1800, Level.HOT), WaitBudget.scaleForHeat(1800, Level.HOT, d))
            assertEquals(ThermalPolicy.scaleBudget(1800, Level.CRITICAL), WaitBudget.scaleForHeat(1800, Level.CRITICAL, d))
            assertEquals(1800, WaitBudget.scaleForHeat(1800, Level.NORMAL, d))
        }
    }

    @Test fun settingValueParsing() {
        assertEquals(Depth.BALANCED, WaitBudget.depthOf(null))
        assertEquals(Depth.BALANCED, WaitBudget.depthOf("nonsense"))
        for (d in Depth.values()) assertEquals(d, WaitBudget.depthOf(d.name))
    }

    @Test fun answerNowEndsTheTraceWithAnAnswer() {
        val b = ThinkBudget(3000, startsInThink = true)
        assertEquals(null, b.onToken("Let me think").inject)
        b.answerNow()
        val step = b.onToken(" more")
        assertEquals(ThinkBudget.WRAP_UP + ThinkBudget.CLOSE + "\n\n", step.inject)
        assertTrue(b.forced)
        assertEquals(2, b.thinkTokens)
        assertEquals("The answer.", b.onToken("The answer.").visible)
    }

    @Test fun answerNowAfterTheTraceDoesNothing() {
        val b = ThinkBudget(3000, startsInThink = true)
        b.onToken("x</think>")
        b.answerNow()
        val step = b.onToken("Hello")
        assertEquals(null, step.inject)
        assertEquals("Hello", step.visible + b.flush())
    }
}
