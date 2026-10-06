package com.agent.ultra.agent

import com.agent.ultra.agent.LocalPrompt.Turn
import com.agent.ultra.bench.TestKitActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    private fun include(s: String) = HistoryPolicy.decide(s).include

    /** The Pixel brain test: only Q10 refers back. Q8 (packing list) and Q11 (long paste) were the leaks. */
    @Test fun jarvisTestsOnlySummaryOfConversationGetsHistory() {
        val qs = TestKitActivity.QUESTIONS
        assertEquals(11, qs.size)
        val withHistory = qs.indices.filter { include(qs[it]) }.map { it + 1 }
        assertEquals(listOf(10), withHistory)
        assertTrue(qs[10].length > HistoryPolicy.LONG_PASTE_CHARS)
    }

    @Test fun referringBackGetsHistory() {
        for (s in listOf(
            "summarize everything we talked about", "summarize our conversation", "what did I ask",
            "what did I ask you before", "continue", "go on", "tell me more", "why?", "really?",
            "and the other one?", "the second one", "tell me more about the second one", "make it shorter",
            "translate that into French", "what do you mean by that?", "is that true?", "why is that",
            "what about pasta?", "you said 391, are you sure", "say that again", "which one is cheaper",
            "explain those", "can you list them",
        )) assertTrue(s, include(s))
    }

    @Test fun selfContainedGetsNone() {
        for (s in listOf(
            "what's the capital of Peru", "why is the sky blue", "where is the eiffel tower",
            "how do I bake sourdough bread", "write a haiku about rain", "what's the first step to learn guitar",
            "translate this: buenos días, ¿cómo estás hoy, amigo mío?", "summarize this: " + "word ".repeat(30),
            "turn on the flashlight", "set an alarm for 7",
        )) assertFalse(s, include(s))
    }

    /** A long paste is self-contained even when its text says "as we discussed". */
    @Test fun longPasteNeverGetsHistory() {
        val paste = "summarize this: " + "As we discussed earlier, the second one is better. ".repeat(30)
        assertFalse(include(paste))
        assertEquals("long paste: self-contained", HistoryPolicy.decide(paste).reason)
    }

    /** Words inside pasted material never count as references back. */
    @Test fun instructionPartDropsThePaste() {
        assertEquals("summarize this", HistoryPolicy.instructionPart("summarize this: tell me more about that one, the rest of it"))
        assertEquals("translate this: hola", HistoryPolicy.instructionPart("translate this: hola"))
        assertFalse(include("summarize this: tell me more about that one, continue the rest of it"))
    }

    /** An ambient command is judged on the request, not on the overheard transcript. */
    @Test fun ambientWrapperIgnored() {
        val wrapped = "[Ambient transcript]\nand what about that, tell me more, we talked about it\n\n" +
            "${IntentRouter.REQUEST_MARKER}what's the capital of Peru"
        assertFalse(include(wrapped))
    }

    // ---------------------------------------------------------------- window

    private fun convo(n: Int, size: Int = 300) = (1..n).map { Turn(it % 2 == 1, "t$it " + "x".repeat(size)) }

    @Test fun windowKeepsEverythingThatFits() {
        val h = convo(6)
        assertEquals(h, HistoryPolicy.window(h, 3000))
        assertTrue(HistoryPolicy.window(emptyList(), 3000).isEmpty())
    }

    /** The anchor stays put while turns are appended, then jumps a whole block. */
    @Test fun anchorMovesInBlocks() {
        val budget = 3000                       // ~9 turns of ~305 chars
        val starts = (2..30 step 2).map { n ->
            val w = HistoryPolicy.window(convo(n), budget)
            assertTrue(w.sumOf { it.text.length } <= budget)
            assertTrue(w.first().fromUser)
            w.first().text.substringBefore(' ')
        }
        // Never one turn at a time: every anchor is a block start (t1, t5, t9, ...).
        for (s in starts) assertEquals(s, 0, (s.removePrefix("t").toInt() - 1) % HistoryPolicy.BLOCK_TURNS)
        // And each anchor holds across several consecutive turns.
        val runs = starts.zipWithNext().count { (a, b) -> a == b }
        assertTrue("anchor held only $runs times in ${starts.size}", runs >= starts.size / 2)
    }

    /** The reason for the window: consecutive prompts share everything up to the previous request. */
    @Test fun consecutivePromptsShareLongPrefix() {
        var sameAnchor = 0; var total = 0
        for (n in 4..28 step 2) {
            val h = convo(n)
            val p1 = LocalPrompt.build("SYS", HistoryPolicy.window(h, 3000), "what did I ask", 3000)
            val h2 = h + Turn(true, "what did I ask") + Turn(false, "<think>t</think>\n\nYou asked about x.")
            val p2 = LocalPrompt.build("SYS", HistoryPolicy.window(h2, 3000), "and the other one?", 3000)
            val common = p1.commonPrefixWith(p2).length
            if (HistoryPolicy.window(h, 3000).first() == HistoryPolicy.window(h2, 3000).first()) {
                // Same anchor: the old prompt, minus only its opened assistant turn, is a prefix.
                assertEquals(p1.removeSuffix("<think>\n").length, common)
                sameAnchor++
            }
            total++
        }
        assertTrue("anchor held $sameAnchor of $total", sameAnchor * 2 >= total)
    }

    /** The old window slid by one turn each time: the shared prefix was the system prompt only. */
    @Test fun slidingWindowWouldHaveLostThePrefix() {
        val h = convo(20)
        val h2 = h + Turn(true, "q") + Turn(false, "a".repeat(300))
        val p1 = LocalPrompt.build("SYS", h, "q", 3000)
        val p2 = LocalPrompt.build("SYS", h2, "next", 3000)
        assertTrue(p1.commonPrefixWith(p2).length < 50)
        val s1 = LocalPrompt.build("SYS", HistoryPolicy.window(h, 3000), "q", 3000)
        val s2 = LocalPrompt.build("SYS", HistoryPolicy.window(h2, 3000), "next", 3000)
        assertTrue(s1.commonPrefixWith(s2).length > 50)
    }

    @Test fun oversizedTurnFallsBackToNewestThatFit() {
        val h = listOf(Turn(true, "q1"), Turn(false, "y".repeat(5000)), Turn(true, "q2"), Turn(false, "short answer"))
        val w = HistoryPolicy.window(h, 1000, block = 4)
        assertEquals(listOf(Turn(true, "q2"), Turn(false, "short answer")), w)
    }

    @Test fun windowMeasuresStrippedText() {
        val h = listOf(Turn(true, "q"), Turn(false, "<think>" + "z".repeat(5000) + "</think>\n\nA"))
        assertEquals(2, HistoryPolicy.window(h, 100).size)
    }
}
