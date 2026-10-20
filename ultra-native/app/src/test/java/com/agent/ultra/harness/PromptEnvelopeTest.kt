package com.agent.ultra.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure tests for prompt-fragment hashing and the reusable KV prefix. */
class PromptEnvelopeTest {

    private fun frag(id: String, text: String) = PromptFragment(id, text)

    @Test fun sha256IsStableAndContentAddressed() {
        assertEquals(frag("a", "hello").sha256, PromptFragment.sha256Hex("hello"))
        assertEquals(
            // known SHA-256 of "abc"
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PromptFragment.sha256Hex("abc"),
        )
        assertNotEquals(frag("a", "hello").sha256, frag("a", "hello!").sha256)
    }

    @Test fun reusablePrefixCountsLeadingIdenticalFragments() {
        val old = listOf(
            frag(PromptFragment.CORE_PERSONA, "persona"),
            frag(PromptFragment.USER_PROFILE, "profile v1"),
            frag(PromptFragment.LOADED_TOOL_SCHEMAS, "tools A"),
            frag(PromptFragment.COVERED_HISTORY, "turn 1"),
        )
        val samePrefix = listOf(
            frag(PromptFragment.CORE_PERSONA, "persona"),
            frag(PromptFragment.USER_PROFILE, "profile v1"),
            frag(PromptFragment.LOADED_TOOL_SCHEMAS, "tools B"), // diverges here
            frag(PromptFragment.COVERED_HISTORY, "turn 2"),
        )
        assertEquals(2, reusablePrefixLen(old, samePrefix))
    }

    @Test fun identicalListsReuseEverything() {
        val l = listOf(frag("a", "x"), frag("b", "y"))
        assertEquals(2, reusablePrefixLen(l, l))
    }

    @Test fun firstFragmentChangeReusesNothing() {
        val old = listOf(frag("a", "x"), frag("b", "y"))
        val new = listOf(frag("a", "x!"), frag("b", "y"))
        assertEquals(0, reusablePrefixLen(old, new))
    }

    @Test fun shorterListBoundsThePrefix() {
        val old = listOf(frag("a", "x"), frag("b", "y"), frag("c", "z"))
        val new = listOf(frag("a", "x"))
        assertEquals(1, reusablePrefixLen(old, new))
    }

    @Test fun envelopeCarriesFragmentsAndToolFingerprints() {
        val e = SessionEnvelope(listOf(frag("a", "x")), listOf("web_search@1"))
        assertEquals(1, e.fragments.size)
        assertEquals(listOf("web_search@1"), e.toolFingerprints)
    }
}
