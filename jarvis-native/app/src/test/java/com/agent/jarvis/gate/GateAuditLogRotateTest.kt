package com.agent.jarvis.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter

class GateAuditLogRotateTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun log(): File = File(tmp.root, "gate_audit.jsonl")

    @Test fun underTheLimitNothingMoves() {
        val f = log().apply { writeText("{\"a\":1}\n") }
        assertFalse(GateAuditLog.rotate(f, 1024))
        assertTrue(f.exists())
        assertFalse(GateAuditLog.rotatedOf(f).exists())
    }

    @Test fun rotationKeepsEveryLineAndCountSpansBothFiles() {
        val f = log()
        f.writeText((1..5).joinToString("") { "{\"n\":$it}\n" })
        assertTrue(GateAuditLog.rotate(f, 10))
        val r = GateAuditLog.rotatedOf(f)
        assertEquals("gate_audit.1.jsonl", r.name)
        assertFalse(f.exists())
        assertEquals((1..5).map { "{\"n\":$it}" }, r.readLines())   // nothing cut in half
        f.appendText("{\"n\":6}\n{\"n\":7}\n")
        assertEquals(7, GateAuditLog.countEntries(f))
        val out = StringWriter()
        GateAuditLog.writeEntries(f, out)   // export order: rotated first, then current
        assertEquals((1..7).map { "{\"n\":$it}" }, out.toString().trim().lines())
    }

    @Test fun secondRotationReplacesTheOlderGeneration() {
        val f = log()
        f.writeText("{\"gen\":1}\n{\"gen\":1}\n")
        assertTrue(GateAuditLog.rotate(f, 5))
        f.writeText("{\"gen\":2}\n{\"gen\":2}\n{\"gen\":2}\n")
        assertTrue(GateAuditLog.rotate(f, 5))
        val r = GateAuditLog.rotatedOf(f)
        assertEquals(List(3) { "{\"gen\":2}" }, r.readLines())
        assertEquals(3, GateAuditLog.countEntries(f))
    }

    @Test fun deleteRemovesBothFiles() {
        val f = log()
        f.writeText("{\"n\":1}\n")
        GateAuditLog.rotate(f, 1)
        f.writeText("{\"n\":2}\n")
        assertTrue(f.exists() && GateAuditLog.rotatedOf(f).exists())
        GateAuditLog.deleteBoth(f)
        assertFalse(f.exists())
        assertFalse(GateAuditLog.rotatedOf(f).exists())
        assertEquals(0, GateAuditLog.countEntries(f))
    }

    @Test fun routeEntryHasExactlyTheAllowedKeys() {
        val e = GateAuditLog.routeEntry(GateAuditLog.ROUTE_LOCAL_ANSWER, "no_client", 42L)
        assertEquals(setOf("ts", "kind", "route", "reason"), e.keySet())
        assertEquals(42L, e.getLong("ts"))
        assertEquals("route", e.getString("kind"))
        assertEquals("local_answer", e.getString("route"))
        assertEquals("no_client", e.getString("reason"))
    }

    @Test fun quietEntryHasExactlyTheAllowedKeys() {
        val e = GateAuditLog.quietEntry("tool:web_search", 20_499L, "cloud/request", 42L)
        assertEquals(setOf("ts", "kind", "stage", "elapsed_s", "route"), e.keySet())
        assertEquals(42L, e.getLong("ts"))
        assertEquals("quiet", e.getString("kind"))
        assertEquals("tool:web_search", e.getString("stage"))
        assertEquals(20L, e.getLong("elapsed_s"))
        assertEquals("cloud/request", e.getString("route"))
    }

    @Test fun routeConstantsAreTheFixedWords() {
        assertEquals(
            listOf("cloud", "local_answer", "local_tool", "offline_nav", "web", "cloud_fallback_local"),
            listOf(GateAuditLog.ROUTE_CLOUD, GateAuditLog.ROUTE_LOCAL_ANSWER, GateAuditLog.ROUTE_LOCAL_TOOL,
                GateAuditLog.ROUTE_OFFLINE_NAV, GateAuditLog.ROUTE_WEB, GateAuditLog.ROUTE_CLOUD_FALLBACK_LOCAL),
        )
    }

    @Test fun actionEntryFormat() {
        val yes = GateAuditLog.actionEntry("pay", true, 7L)
        assertEquals(setOf("ts", "kind", "outcome", "rule"), yes.keySet())
        assertEquals("action_gate", yes.getString("kind"))
        assertEquals("OVERRIDDEN", yes.getString("outcome"))
        assertEquals("commitment:pay", yes.getString("rule"))
        assertEquals("BLOCKED", GateAuditLog.actionEntry("pay", false, 7L).getString("outcome"))
    }

    @Test fun recordActionAndRouteAreNoOpsBeforeInit() {
        assertFalse(GateAuditLog.isInitialized())
        GateAuditLog.recordAction("pay", true)   // no Context: must return quietly, not throw
        GateAuditLog.recordRoute(GateAuditLog.ROUTE_CLOUD, "online")
        assertFalse(GateAuditLog.isInitialized())
    }
}
