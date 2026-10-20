package com.agent.ultra.agent

import com.agent.ultra.harness.PromptFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fragment order, equivalence with the old prompts, and stub -> full after a namespace load. */
class LocalEnvelopeTest {

    private val order = listOf(
        PromptFragment.CORE_PERSONA, PromptFragment.USER_PROFILE,
        PromptFragment.LOADED_TOOL_SCHEMAS, PromptFragment.COVERED_HISTORY,
    )
    private val turns = listOf(LocalPrompt.Turn(true, "hello"), LocalPrompt.Turn(false, "hi there"))

    @Test fun fragmentsAreInStableOrder() {
        assertEquals(order, LocalEnvelope.answer("Sam", "be brief", turns, 3000).fragments.map { it.id })
        assertEquals(order, LocalToolCall.envelope(LocalToolCall.newNamespaceStore()).fragments.map { it.id })
    }

    @Test fun answerSystemEqualsOfflineSystem() {
        for ((name, ins) in listOf("" to "", "Sam" to "", "" to "be brief", "Sam" to "be brief")) {
            val env = LocalEnvelope.answer(name, ins, turns, 3000)
            assertEquals(LocalPrompt.offlineSystem(name, ins), LocalEnvelope.systemText(env.fragments, "\n"))
        }
    }

    @Test fun historyRenderingMatchesBuild() {
        val kept = LocalPrompt.keptHistory(turns, 3000)
        val built = LocalPrompt.build("S", turns, "now?", 3000)
        assertTrue(built.startsWith("<|im_start|>system\nS<|im_end|>\n" + LocalPrompt.renderHistory(kept)))
    }

    @Test fun onlyHistoryChangeKeepsThreeOfFour() {
        val a = LocalEnvelope.answer("Sam", "x", turns, 3000)
        val b = LocalEnvelope.answer("Sam", "x", turns + LocalPrompt.Turn(true, "more"), 3000)
        assertEquals(3, LocalEnvelope.reusableFragments(a, b))
        assertEquals(4, LocalEnvelope.reusableFragments(a, a))
        assertEquals(0, LocalEnvelope.reusableFragments(null, a))
    }

    @Test fun profileChangeKeepsOnlyPersona() {
        val a = LocalEnvelope.answer("Sam", "x", turns, 3000)
        val b = LocalEnvelope.answer("Sam", "y", turns, 3000)
        assertEquals(1, LocalEnvelope.reusableFragments(a, b))
    }

    @Test fun toolPromptWithoutDeferredIsTheOldFixedCatalog() {
        val store = LocalToolCall.newNamespaceStore()
        assertEquals(LocalToolCall.SYSTEM, LocalToolCall.systemFor(store))
        for (t in LocalToolCall.TOOLS) assertTrue("$t listed", LocalToolCall.SYSTEM.contains("\n$t {"))
        assertFalse("nothing to load, so not advertised", LocalToolCall.SYSTEM.contains(LocalToolCall.LOAD_NAMESPACE))
    }

    @Test fun deferredNamespaceIsStubThenFullAfterLoad() {
        val full = "shopping:\n  shop_search {query}"
        val store = LocalToolCall.newNamespaceStore(mapOf("shopping" to full))
        val before = LocalToolCall.systemFor(store)
        assertTrue(before.contains("shopping: [deferred: use load_tool_namespace(\"shopping\")"))
        assertFalse(before.contains("shop_search {query}"))
        assertTrue("core tools stay full", before.contains("flashlight_toggle {\"on\":true|false}"))
        assertTrue(before.contains(LocalToolCall.LOAD_NAMESPACE))
        val envBefore = LocalToolCall.envelope(store)

        assertTrue(store.load("shopping"))
        val after = LocalToolCall.systemFor(store)
        assertTrue(after.contains("shop_search {query}"))
        assertFalse(after.contains("shopping: [deferred"))
        // only the tool fragment changed: persona + profile still reusable
        val envAfter = LocalToolCall.envelope(store)
        assertEquals(2, LocalEnvelope.reusableFragments(envBefore, envAfter))
        assertEquals(1, envAfter.toolFingerprints.size)
        assertTrue(envBefore.toolFingerprints.isEmpty())
    }

    @Test fun coreToolsCannotBeLoadedOrDeferred() {
        val store = LocalToolCall.newNamespaceStore(mapOf("shopping" to "x"))
        assertFalse(store.load("flashlight_toggle"))
        assertFalse(store.load("nope"))
        assertTrue(store.load("shopping"))
        assertFalse("second load is a no-op", store.load("shopping"))
    }

    @Test fun promptCarriesLoadedNamespaceAndRecalled() {
        val store = LocalToolCall.newNamespaceStore(mapOf("shopping" to "shopping:\n  shop_search {query}"))
        store.load("shopping")
        val p = LocalToolCall.prompt("find a mug", "shop_search {}", store)
        assertTrue(p.contains("shop_search {query}"))
        assertTrue(p.contains("A request like this previously worked with: shop_search {}"))
        assertEquals(LocalToolCall.prompt("hi", null), LocalToolCall.prompt("hi", null, LocalToolCall.newNamespaceStore()))
    }

    @Test fun extractRecognisesLoadNamespace() {
        val a = LocalToolCall.extract("""{"tool":"load_tool_namespace","params":{"namespace":"shopping"}}""")
        assertEquals(LocalToolCall.Parsed.LoadNamespace("shopping"), a)
        val b = LocalToolCall.extract("""x</think>{"tool":"load_tool_namespace","params":{"name":" vision "}}""")
        assertEquals(LocalToolCall.Parsed.LoadNamespace("vision"), b)
        // not a device tool: ToolWiringTest's catalog must not contain it
        assertFalse(LocalToolCall.LOAD_NAMESPACE in LocalToolCall.TOOLS)
    }
}
