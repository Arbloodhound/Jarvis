package com.agent.ultra.agent

import com.agent.ultra.bench.TestKitActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBriefTest {
    @After fun reset() { MemoryBrief.provider = null }

    private fun wants(s: String) = MemoryBrief.wants(s).search

    @Test fun memoryQuestionsSearch() {
        for (s in listOf(
            "what did Sarah say about the lease", "what did she say", "who said we should leave at 6",
            "when did Tom call", "did he mention the price", "remind me what the plumber said", "remind me",
            "do you remember the wifi password", "what was that about", "what did I hear this morning",
            "what happened yesterday", "the meeting last week", "what's Dana's number", "notes from the call with Priya",
            "what was the name of the restaurant they mentioned earlier", "summarize everything we talked about",
        )) assertTrue(s, wants(s))
    }

    @Test fun freshQuestionsDoNot() {
        for (s in listOf(
            "what's the capital of Peru", "who was Napoleon in two sentences", "Paris travel tips",
            "what do you think about electric cars", "remind me to buy milk at 5", "write a poem about the sea",
            "summarize this: " + "the council met yesterday and Tom said the bus is late. ".repeat(20),
        )) assertFalse(s, wants(s))
    }

    /** The Pixel brain test: only Q10 (it refers back to the conversation) searches memory. */
    @Test fun jarvisTestsOnlyQ10Searches() {
        val qs = TestKitActivity.QUESTIONS
        assertEquals(listOf(10), qs.indices.filter { wants(qs[it]) }.map { it + 1 })
    }

    @Test fun capCutsAtABoundary() {
        val long = "word ".repeat(400)
        val c = MemoryBrief.cap(long)
        assertTrue(c.length <= MemoryBrief.MAX_CHARS)
        assertTrue(c.endsWith("word…"))
        assertEquals("short", MemoryBrief.cap("  short \n"))
    }

    @Test fun recallUsesProvider() = runBlocking {
        assertNull(MemoryBrief.recall("anything"))                  // no provider: no brief
        var asked: String? = null
        var at = 0L
        MemoryBrief.provider = { q, now -> asked = q; at = now; "09:12 Sarah: the lease ends in March" }
        assertEquals("09:12 Sarah: the lease ends in March", MemoryBrief.recall("what did Sarah say", nowMs = 42L))
        assertEquals("what did Sarah say", asked)
        assertEquals(42L, at)
        MemoryBrief.provider = { _, _ -> "   " }
        assertNull(MemoryBrief.recall("q"))                         // blank: no brief
        MemoryBrief.provider = { _, _ -> "x".repeat(5000) }
        assertEquals(MemoryBrief.MAX_CHARS, MemoryBrief.recall("q")!!.length)
    }

    @Test fun recallSurvivesFailureAndSlowness() = runBlocking {
        MemoryBrief.provider = { _, _ -> error("index missing") }
        assertNull(MemoryBrief.recall("q"))
        MemoryBrief.provider = { _, _ -> delay(10_000); "late" }
        assertNull(MemoryBrief.recall("q", timeoutMs = 50))
        MemoryBrief.provider = { _, _ -> kotlinx.coroutines.withTimeout(10) { delay(1000); "x" } }
        assertNull(MemoryBrief.recall("q"))                         // the provider's own timeout
    }

    /** The brief lands after the history and before the user turn, labelled, and only when non-blank. */
    @Test fun promptPlacement() {
        val h = listOf(LocalPrompt.Turn(true, "q1"), LocalPrompt.Turn(false, "a1"))
        val p = LocalPrompt.build("SYS", h, "what did Sarah say", memory = "09:12 Sarah: the lease ends in March")
        val brief = "<|im_start|>system\n${MemoryBrief.HEADER}\n09:12 Sarah: the lease ends in March<|im_end|>\n"
        assertTrue(p.contains("<|im_start|>assistant\na1<|im_end|>\n$brief<|im_start|>user\nwhat did Sarah say<|im_end|>"))
        assertEquals(LocalPrompt.build("SYS", h, "x"), LocalPrompt.build("SYS", h, "x", memory = "  "))
        // The system + history prefix is the same with or without a brief.
        val without = LocalPrompt.build("SYS", h, "what did Sarah say")
        assertTrue(p.startsWith(without.substringBefore("<|im_start|>user\nwhat did Sarah say")))
        assertTrue(LocalPrompt.build("S", emptyList(), "u", memory = "m".repeat(5000)).length < 1000)
    }

    // ------------------------------------------------- review findings (d4029d5)

    @Test fun generalQuestionsWithNamesOrRecallWordsDoNot() {
        for (s in listOf(
            "what is Newton's second law", "explain Ohm's law", "what is a product recall",
            "is the earlier train faster", "tell me more", "why is that", "make it shorter",
        )) assertFalse(s, wants(s))
        for (s in listOf("do you recall the address", "what's Dana's number", "what did we decide earlier",
            "the meeting with Priya", "what did he say last time")) assertTrue(s, wants(s))
        assertTrue(MemoryBrief.needsMemory("what did Sarah say"))
        assertFalse(MemoryBrief.needsMemory("what's the capital of Peru"))
    }

    /** A pronoun-only request borrows content from the previous user turn. */
    @Test fun queryHasContent() {
        assertEquals("what did he say about that (about: the lease renewal with Tom)",
            MemoryBrief.query("what did he say about that", "the lease renewal with Tom"))
        assertEquals("what did Sarah say about the lease", MemoryBrief.query("what did Sarah say about the lease", "hi"))
        assertEquals("what did he say", MemoryBrief.query("what did he say", null))
    }
}
