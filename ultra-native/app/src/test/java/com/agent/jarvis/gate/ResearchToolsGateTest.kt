package com.agent.jarvis.gate

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped manifest's wiring for the new research tools: a shopping search is an ordinary egress
 * search (allowed on a plain query), and checkout preparation stops at the confirm card when its
 * target was not named by the user.
 */
class ResearchToolsGateTest {

    private val manifest: Manifest =
        Manifest.fromJson(JSONObject(File("src/main/assets/ultra.manifest.json").readText()))

    private fun check(request: String, tool: String, args: String): Gate.Verdict =
        Gate(manifest).enforceCall(Gate.Episode(request), tool, JSONObject(args))

    @Test fun shopSearchAllowsAPlainQuery() {
        val v = check("find me the cheapest sony headphones", "shop_search",
            """{"query":"cheapest sony headphones"}""")
        assertTrue(v.allowed)
    }

    @Test fun webBrowseAllowsAPlainTask() {
        val v = check("browse for background on acme audio", "web_browse",
            """{"task":"background on acme audio"}""")
        assertTrue(v.allowed)
    }

    @Test fun checkoutPrepStopsAtConfirmCardWhenTargetUnnamed() {
        // Nothing in the request names this item: the gate blocks, confirmably (the confirm card).
        val v = check("what's the weather", "shop_checkout_prep",
            """{"title":"Sony WH-1000XM5","seller":"Acme Audio"}""")
        assertFalse(v.allowed)
        assertTrue(v.confirmable)
    }

    @Test fun checkoutPrepAllowedWhenTargetNamed() {
        val v = check("prepare an order for the sony headphones", "shop_checkout_prep",
            """{"title":"sony headphones","seller":"Acme Audio"}""")
        assertTrue(v.allowed)
    }
}
