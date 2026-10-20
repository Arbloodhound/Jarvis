package com.agent.jarvis.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for deferred tool-namespace loading. */
class ToolNamespaceStoreTest {

    private val schemas = mapOf(
        "shopping" to "shopping:\n  shop_search {query, sort?}\n  shop_checkout_prep {...}",
        "vision" to "vision:\n  look_at_screen {question?}",
    )

    @Test fun deferredNamespaceShowsStubBeforeLoadAndFullAfter() {
        val store = ToolNamespaceStore(schemas)
        val before = store.promptBlock()
        assertTrue("stub before load",
            before.contains("shopping: [deferred: use load_tool_namespace(\"shopping\") to load full parameter schemas]"))
        assertFalse("no full schema before load", before.contains("shop_search {query, sort?}"))

        assertTrue(store.load("shopping"))
        val after = store.promptBlock()
        assertTrue("full schema after load", after.contains("shop_search {query, sort?}"))
        assertFalse("no stub after load",
            after.contains("shopping: [deferred"))
        // other namespaces stay deferred
        assertTrue(after.contains("vision: [deferred"))
    }

    @Test fun coreToolsAreAlwaysFullAndNeverDeferred() {
        val store = ToolNamespaceStore(schemas)
        val block = store.promptBlock()
        for (c in ToolNamespaceStore.CORE) {
            assertTrue(block.contains("$c: [core tool, always available]"))
        }
        // core cannot be "loaded" as a deferred namespace
        assertFalse(store.load("read"))
    }

    @Test fun loadingUnknownNamespaceIsRejected() {
        val store = ToolNamespaceStore(schemas)
        assertFalse(store.load("does_not_exist"))
        assertFalse(store.isActive("does_not_exist"))
    }

    @Test fun loadIsIdempotentAndTracksActive() {
        val store = ToolNamespaceStore(schemas)
        assertTrue(store.load("vision"))
        assertFalse("second load is a no-op", store.load("vision"))
        assertTrue(store.isActive("vision"))
        assertFalse(store.isActive("shopping"))
        assertTrue(store.activeNamespaces().contains("vision"))
    }
}
