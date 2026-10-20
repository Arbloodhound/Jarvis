package com.agent.ultra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStandInTest {
    @Test fun perTurnBudgetMirrorsTheBrainsLoop() {
        // Must match Brain.runLoop (Brain.kt:1261): wide first turn, wide last two, tighter middle.
        assertEquals(2000, CloudStandIn.perTurnBudget(0))
        assertEquals(1500, CloudStandIn.perTurnBudget(5))
        assertEquals(2500, CloudStandIn.perTurnBudget(10)) // maxTurns-2
        assertEquals(2500, CloudStandIn.perTurnBudget(11))
    }

    @Test fun muteBoundaryFiresOnlyPastTheBudget() {
        assertNull("a short reply is not muted", CloudStandIn.muteBoundary(replyChars = 100, budgetTokens = 1500))
        // 1500 tokens ≈ 6000 chars; a 7000-char reply is cut at 6000.
        assertEquals(6000, CloudStandIn.muteBoundary(replyChars = 7000, budgetTokens = 1500))
    }

    @Test fun applyMuteDropsOnlyTheTail() {
        assertEquals("abc", CloudStandIn.applyMute("abcdef", 3))
        assertEquals("abcdef", CloudStandIn.applyMute("abcdef", null))
        assertEquals("abcdef", CloudStandIn.applyMute("abcdef", 99))
    }

    @Test fun classifyUsesAccessAndTheManifest() {
        val manifest = setOf("web_search", "react_navigate")
        val dead = setOf("react_navigate") // accessibility off
        assertEquals(CloudStandIn.ToolOutcome.SUCCESS, CloudStandIn.classify("web_search", manifest, dead))
        assertEquals(CloudStandIn.ToolOutcome.LIMITED, CloudStandIn.classify("react_navigate", manifest, dead))
        assertEquals(CloudStandIn.ToolOutcome.FAILED, CloudStandIn.classify("made_up_tool", manifest, dead))
    }

    @Test fun muteNoticeNamesTheReasonAndStaysOnTopic() {
        val n = CloudStandIn.muteNotice(CloudStandIn.MuteEvent(3, CloudStandIn.Reason.BUDGET, keptTokens = 1500, mutedTokens = 250))
        assertTrue(n, n.contains("turn 3"))
        assertTrue(n, n.contains("budget"))
        assertTrue(n, n.contains("muted, not cut"))
    }

    @Test fun theInjectedTextCarriesNoneOfTheBlockedCluster() {
        // The diagnostic was paused twice when it was drafted bundled with onion/Tor/abliterated
        // content; this guards that the clean version's injected text never drifts back into it.
        val blocked = Regex("tor|onion|abliterat|dark ?web", RegexOption.IGNORE_CASE)
        assertTrue("PROMPT stays on token-cutoff + tool-call vigilance", !blocked.containsMatchIn(CloudStandIn.PROMPT))
        for (r in CloudStandIn.Reason.entries) {
            val n = CloudStandIn.muteNotice(CloudStandIn.MuteEvent(1, r, 10, 10))
            assertTrue("muteNotice($r) stays on topic", !blocked.containsMatchIn(n))
        }
    }
}
