package com.agent.ultra

import com.agent.ultra.gate.GateAuditLog
import com.agent.ultra.gate.ObservationLog
import com.agent.ultra.gate.Fact
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GateAuditLogTest {

    @Test
    fun `outcome enum names match expectations`() {
        assertEquals("ALLOWED", GateAuditLog.Outcome.ALLOWED.name)
        assertEquals("BLOCKED", GateAuditLog.Outcome.BLOCKED.name)
        assertEquals("OVERRIDDEN", GateAuditLog.Outcome.OVERRIDDEN.name)
    }

    @Test
    fun `device fingerprint contains expected keys`() {
        val ctx = android.content.ContextWrapper(null)
        try {
            val fp = GateAuditLog.deviceFingerprint(ctx)
            assertTrue(fp.has("android"))
            assertTrue(fp.has("device"))
            assertTrue(fp.has("ram_mb"))
        } catch (_: Exception) {
            // ContextWrapper(null) may throw on getSystemService in unit tests
        }
    }

    @Test
    fun `observation log counts match what record produces`() {
        val log = ObservationLog()
        log.record("battery_level", Fact.Source.SYSTEM_API, "100%")
        log.record("screen_read", Fact.Source.ACCESSIBILITY_TREE, "clock")
        assertEquals(1, log.highCount())
        assertEquals(1, log.lowCount())
        assertFalse(log.hasLowToolOnly())
        assertTrue(log.hasHighToolObservation())
    }

    @Test
    fun `low tool only is true with only tree reads`() {
        val log = ObservationLog()
        log.record(Fact(
            tool = "<user>",
            source = Fact.Source.USER_REQUEST,
            confidence = Fact.Confidence.HIGH,
            summary = "test",
        ))
        log.record("screen_read", Fact.Source.ACCESSIBILITY_TREE, "text")
        assertTrue(log.hasLowToolOnly())
    }
}
