package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkBudgetTest {

    /** Feed pieces; returns (visible text, injected strings). Stops sampling after an inject, like the engine. */
    private fun run(b: ThinkBudget, pieces: List<String>): Pair<String, List<String>> {
        val vis = StringBuilder(); val inj = mutableListOf<String>()
        for (p in pieces) { val s = b.onToken(p); vis.append(s.visible); s.inject?.let { inj += it } }
        vis.append(b.flush())
        return vis.toString() to inj
    }

    @Test fun traceIsHiddenAndAnswerPassesThrough() {
        val (v, inj) = run(ThinkBudget(100, startsInThink = false),
            listOf("<th", "ink>", "let me ", "think", "</th", "ink>\n\n", "The answer", " is 4."))
        assertEquals("The answer is 4.", v); assertTrue(inj.isEmpty())
    }

    @Test fun templateOpenedTraceOnlyHasCloseTag() {
        val (v, _) = run(ThinkBudget(100, startsInThink = true), listOf("hmm ", "ok", "</think>", "\nHi!"))
        assertEquals("Hi!", v)
    }

    @Test fun budgetForcesCloseExactlyOnce() {
        val b = ThinkBudget(3, startsInThink = true)
        val steps = listOf("a", "b", "c", "d").map { b.onToken(it) }
        assertNull(steps[0].inject); assertNull(steps[1].inject)
        assertNotNull(steps[2].inject); assertTrue(steps[2].inject!!.endsWith("</think>\n\n"))
        assertNull(steps[3].inject)                       // never twice
        assertTrue(b.forced); assertFalse(b.inThink)
        assertEquals("d", steps[3].visible)              // answer after the forced close is visible
    }

    @Test fun zeroBudgetSkipsThinkingWithNoWrapUp() {
        val s = ThinkBudget(0, startsInThink = true).onToken("x")
        assertEquals("</think>\n\n", s.inject)
    }

    @Test fun partialTagIsNotLeakedToVisibleText() {
        val b = ThinkBudget(100, startsInThink = false)
        assertEquals("Hello ", b.onToken("Hello <").visible)
        assertEquals("", b.onToken("thi").visible)
        assertEquals("", b.onToken("nk>secret</think>").visible)
        assertEquals("ok", b.onToken("ok").visible)
    }

    @Test fun lessThanSignStillReachesUser() {
        val (v, _) = run(ThinkBudget(10, startsInThink = false), listOf("3 <", " 5 is true"))
        assertEquals("3 < 5 is true", v)
    }

    @Test fun clipNeverStarvesAnswer() {
        assertEquals(512, ThinkBudget.clipToContext(512, 4096, 1500))
        assertEquals(4096 - 3400 - 512 - 24, ThinkBudget.clipToContext(1200, 4096, 3400))
        assertEquals(0, ThinkBudget.clipToContext(1200, 4096, 3900))
    }

    @Test fun historyStripping() {
        assertEquals("Answer.", ThinkBudget.stripForHistory("<think>long</think>\n\nAnswer."))
        assertEquals("Answer.", ThinkBudget.stripForHistory("template trace</think>Answer."))
        assertEquals("Answer.", ThinkBudget.stripForHistory("Answer.<think>cut off"))
    }

    @Test fun thePreviewSeesTheTraceButNeverTheAnswer() {
        val seen = StringBuilder()
        val t = ThinkBudget(100, startsInThink = true).also { it.onThought = { p -> seen.append(p) } }
        val out = StringBuilder()
        for (p in listOf("Let me ", "check the ", "time.</th", "ink>\n\nIt is ", "3 pm.")) out.append(t.onToken(p).visible)
        out.append(t.flush())
        assert(seen.toString().startsWith("Let me check the")) { seen.toString() }
        assert(!seen.contains("3 pm")) { seen.toString() }
        org.junit.Assert.assertEquals("It is 3 pm.", out.toString())
    }
}
