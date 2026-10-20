package com.agent.jarvis.agent

import com.agent.jarvis.agent.LocalPrompt.Turn
import com.agent.jarvis.bench.TestKitActivity
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

    /** The anchor stays put while turns are appended, then jumps a whole segment. */
    @Test fun anchorHoldsThenJumps() {
        val budget = 3000                       // ~9 turns of ~305 chars
        val starts = (2..40 step 2).map { n ->
            val w = HistoryPolicy.window(convo(n), budget)
            assertTrue(w.sumOf { it.text.length } <= budget)
            assertTrue(w.first().fromUser)
            w.first().text.substringBefore(' ').removePrefix("t").toInt()
        }
        // The anchor never moves backwards, and each move is more than one exchange.
        for ((a, b) in starts.zipWithNext()) {
            assertTrue("$a -> $b", b >= a)
            if (b != a) assertTrue("jump $a -> $b", b - a >= 4)
        }
        val held = starts.zipWithNext().count { (a, b) -> a == b }
        assertTrue("anchor held only $held times in ${starts.size}", held * 5 >= (starts.size - 1) * 3)
    }

    /** Segment starts depend only on earlier turns: appending never moves one. */
    @Test fun segmentStartsAreStableUnderAppend() {
        val h = convo(30, size = 137)
        val full = HistoryPolicy.segmentStarts(h.map { it.text.length }, h.map { it.fromUser }, 1500)
        for (n in 1..30) {
            val part = HistoryPolicy.segmentStarts(h.take(n).map { it.text.length }, h.take(n).map { it.fromUser }, 1500)
            assertEquals(full.filter { it < n }, part)
        }
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
        val w = HistoryPolicy.window(h, 1000)
        assertEquals(listOf(Turn(true, "q2"), Turn(false, "short answer")), w)
    }

    /** "tell me more" after a long answer: the answer must not be dropped, even when only it fits. */
    @Test fun neverDropsTheOnlyAnswerThatFits() {
        val h = listOf(Turn(true, "q".repeat(900)), Turn(false, "the answer"))
        assertEquals(listOf(Turn(false, "the answer")), HistoryPolicy.window(h, 500))
        val huge = listOf(Turn(true, "q"), Turn(false, "a".repeat(5000)))
        val w = HistoryPolicy.window(huge, 1000)
        assertEquals(1, w.size)
        assertFalse(w[0].fromUser)
        assertEquals(1000, w[0].text.length)
        // And LocalPrompt.build keeps it (same measure).
        assertTrue(LocalPrompt.build("S", w, "tell me more", 1000).contains("a".repeat(1000)))
    }

    @Test fun windowMeasuresStrippedText() {
        val h = listOf(Turn(true, "q"), Turn(false, "<think>" + "z".repeat(5000) + "</think>\n\nA"))
        assertEquals(listOf(Turn(true, "q"), Turn(false, "A")), HistoryPolicy.window(h, 100))
    }

    // ------------------------------------------------- review findings (d4029d5)

    @Test fun styleSampleAfterAReferenceGetsHistory() {
        assertTrue(include("rewrite that in this style: short sentences, plain words, no jargon at all please"))
        assertTrue(include("turn those into a poem like this: roses are red, violets are blue, and so on"))
        // The pronoun right before the colon is the paste itself.
        assertFalse(include("translate that: buenos días, ¿cómo estás hoy, amigo mío?"))
        assertFalse(include("translate it into French: good morning, how are you today my friend"))
        assertFalse(include("summarize this one: " + "word ".repeat(30)))
    }

    // ------------------------------------------------- review findings (this branch)

    @Test fun multiLineFollowUpStillRefersBack() {
        assertTrue(include("What did you mean by that?\nI don't understand the part about taxes"))
        assertEquals("summarize this", HistoryPolicy.instructionPart("summarize this\n" + "a pasted line of text. ".repeat(3)))
    }

    @Test fun possessiveFollowUpsGetHistory() {
        for (s in listOf("What's his best book?", "How old is its author?", "When did their band split?"))
            assertTrue(s, include(s))
    }

    @Test fun definitionsWithOrdinalWordsAreSelfContained() {
        for (s in listOf(
            "What's the farthest a probe has travelled so far?", "Who was the first one to climb Everest?",
            "What is element number 26?", "how do I continue a git rebase after fixing conflicts",
        )) assertFalse(s, include(s))
        for (s in listOf("tell me about number 2", "what have we covered so far", "continue the list"))
            assertTrue(s, include(s))
    }

    @Test fun dummyItIsNotAReference() {
        for (s in listOf("what time is it", "what day is it today", "is it going to rain", "is it raining",
            "it's cold, what should I wear", "how late is it in Tokyo")) assertFalse(s, include(s))
        for (s in listOf("why is it", "is it safe?", "how far is it?", "make it shorter", "what is it")) assertTrue(s, include(s))
    }
}
