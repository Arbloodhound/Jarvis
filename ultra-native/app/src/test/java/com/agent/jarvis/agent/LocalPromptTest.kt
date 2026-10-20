package com.agent.jarvis.agent

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

    /** Date/time and memory share one late system block; the system + history prefix is unchanged. */
    @Test fun nowLineGoesBeforeTheUserTurn() {
        val plain = LocalPrompt.build("s", emptyList(), "u")
        val p = LocalPrompt.build("s", emptyList(), "u", now = "Tuesday 7 October 2026, 2:05 pm", memory = "- note: x")
        assertTrue(p.contains("<|im_start|>system\nNow: Tuesday 7 October 2026, 2:05 pm\n${MemoryBrief.HEADER}\n- note: x<|im_end|>\n<|im_start|>user\nu"))
        assertTrue(p.startsWith(plain.substringBefore("<|im_start|>user")))
        val onlyNow = LocalPrompt.build("s", emptyList(), "u", now = "Monday")
        assertTrue(onlyNow.contains("<|im_start|>system\nNow: Monday<|im_end|>\n<|im_start|>user"))
    }

    /** The offline brain is told what it can do, so it stops saying it has no memory or tools. */
    @Test fun offlineRulesStateItsMemoryAndActions() {
        val r = LocalPrompt.OFFLINE_RULES
        assertTrue(r.contains("What I remember"))
        assertTrue(r.contains("Never say you have no memory"))
        assertTrue(r.contains("flashlight"))
        assertFalse(r.contains("cannot browse or use tools"))
        assertEquals("wednesday 7 october 2026, 5:05 am",
            LocalPrompt.nowLine(1_791_349_500_000L, java.util.TimeZone.getTimeZone("UTC")).lowercase())
    }

    /** Settings → PEOPLE / SYSTEM PROMPT: the owner's name and instructions end the system block. */
    @Test fun ownerSection() {
        assertEquals(LocalPrompt.OFFLINE_RULES, LocalPrompt.offlineSystem("", "  "))
        val s = LocalPrompt.offlineSystem("Dafarus", "Keep answers short.")
        assertTrue(s.startsWith(LocalPrompt.OFFLINE_RULES))
        assertTrue(s.contains("is Dafarus. In transcripts and notes \"Me\" is Dafarus."))
        assertTrue(s.endsWith("The owner's own instructions (follow them, unless they ask for something unsafe):\nKeep answers short."))
        assertEquals("The owner's own instructions (follow them, unless they ask for something unsafe):\nBe brief.",
            LocalPrompt.ownerSection("", "Be brief."))
    }
}
