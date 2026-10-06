package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptTest {
    @Test fun matchesQwenGenerationPrompt() {
        val p = LocalPrompt.build("SYS", emptyList(), "hi")
        assertEquals("<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think>\n", p)
    }
    @Test fun historyIsStrippedOfTraces() {
        val p = LocalPrompt.build("S", listOf(LocalPrompt.Turn(true, "q1"),
            LocalPrompt.Turn(false, "<think>secret</think>\n\nA1")), "q2")
        assertTrue(p.contains("<|im_start|>assistant\nA1<|im_end|>")); assertFalse(p.contains("secret"))
    }
    @Test fun oldestHistoryDroppedFirst() {
        val h = (1..10).map { LocalPrompt.Turn(it % 2 == 1, "m$it" + "x".repeat(500)) }
        val p = LocalPrompt.build("S", h, "now", historyChars = 1200)
        assertTrue(p.contains("m10")); assertFalse(p.contains("m1x"))
    }
    @Test fun offlineRulesNameNoTools() {
        for (t in listOf("web_search", "app_launch", "system_info")) assertFalse(LocalPrompt.OFFLINE_RULES.contains(t))
    }
    @Test fun systemPromptFitsWithBudget() {
        val p = LocalPrompt.build(LocalPrompt.OFFLINE_RULES, emptyList(), "explain how MoE routing works")
        val b = ThinkBudget.clipToContext(IntentRouter.Tier.DEEP.thinkBudget, 4096, LocalPrompt.estimateTokens(p))
        assertEquals(1200, b)   // full DEEP budget survives with an empty history
    }

    @Test fun noThinkMatchesQwenTemplateWithThinkingOff() {
        val p = LocalPrompt.buildNoThink(" SYS ", " find my notes about the boat \n")
        assertEquals("<|im_start|>system\nSYS<|im_end|>\n" +
            "<|im_start|>user\nfind my notes about the boat /no_think<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n", p)
    }
    @Test fun noThinkClosesTheTraceItOpens() {
        val p = LocalPrompt.buildNoThink("S", "q")
        assertTrue(p.endsWith("<think>\n\n</think>\n\n"))
        assertEquals(1, Regex("<think>").findAll(p).count())
        assertEquals(1, Regex("</think>").findAll(p).count())
        assertEquals(1, Regex(LocalPrompt.NO_THINK).findAll(p).count())
    }

    /** After a cloud failure the current question is followed by a note; it must still appear once. */
    @Test fun currentTurnRemovedEvenWhenNotLast() {
        val h = listOf(
            LocalPrompt.Turn(true, "earlier question"), LocalPrompt.Turn(false, "earlier answer"),
            LocalPrompt.Turn(true, "what is a MoE"), LocalPrompt.Turn(false, "(cloud unreachable — answering on-device)"),
        )
        val out = LocalPrompt.withoutCurrentTurn(h, "what is a MoE")
        assertEquals(3, out.size)
        assertEquals(0, out.count { it.fromUser && it.text == "what is a MoE" })
        assertEquals(h, LocalPrompt.withoutCurrentTurn(h, "something new"))
    }
}
