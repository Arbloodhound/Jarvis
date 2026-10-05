package com.agent.ultra

import com.agent.ultra.gate.Fact
import com.agent.ultra.gate.Fact.Confidence.HIGH
import com.agent.ultra.gate.Fact.Confidence.LOW
import com.agent.ultra.gate.Fact.Source
import com.agent.ultra.gate.ObservationLog
import org.junit.Assert.*
import org.junit.Test

class ObservationLogTest {

    @Test
    fun `empty log has no observations`() {
        val log = ObservationLog()
        assertEquals(0, log.size)
        assertFalse(log.hasHighConfidenceRecent())
        assertFalse(log.hasLowConfidenceOnly())
        assertEquals("no observations", log.summary())
    }

    @Test
    fun `records system API as HIGH`() {
        val log = ObservationLog()
        log.record("battery_status", Source.SYSTEM_API, "Battery: 80%")
        assertEquals(1, log.size)
        assertTrue(log.hasHighConfidenceRecent())
        assertFalse(log.hasLowConfidenceOnly())
        assertEquals(1, log.highCount())
        assertEquals(0, log.lowCount())
    }

    @Test
    fun `records tree as LOW`() {
        val log = ObservationLog()
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE, "some text")
        assertEquals(1, log.size)
        assertFalse(log.hasHighConfidenceRecent())
        assertTrue(log.hasLowConfidenceOnly())
        assertEquals(0, log.highCount())
        assertEquals(1, log.lowCount())
    }

    @Test
    fun `mixed observations show both`() {
        val log = ObservationLog()
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        log.record("battery_status", Source.SYSTEM_API)
        log.record("describe_screen", Source.ACCESSIBILITY_TREE)
        assertEquals(3, log.size)
        assertTrue(log.hasHighConfidenceRecent())
        assertFalse(log.hasLowConfidenceOnly())
        assertEquals(1, log.highCount())
        assertEquals(2, log.lowCount())
    }

    @Test
    fun `operator confirmation is HIGH`() {
        val log = ObservationLog()
        log.record(Fact(
            tool = "sms_send",
            source = Source.OPERATOR_CONFIRMATION,
            confidence = HIGH,
            summary = "operator confirmed sms_send",
        ))
        assertTrue(log.hasHighConfidenceRecent())
        assertEquals(1, log.highCount())
    }

    @Test
    fun `user request is HIGH`() {
        val log = ObservationLog()
        log.record(Fact(
            tool = "<user>",
            source = Source.USER_REQUEST,
            confidence = HIGH,
            summary = "what is my battery level",
        ))
        assertTrue(log.hasHighConfidenceRecent())
    }

    @Test
    fun `stale HIGH decays to LOW`() {
        val log = ObservationLog()
        val old = System.currentTimeMillis() - 200_000
        log.record(Fact("battery_status", Source.SYSTEM_API, HIGH, timestamp = old))
        assertFalse(log.hasHighConfidenceRecent())
        assertTrue(log.hasLowConfidenceOnly())
        assertEquals(0, log.highCount())
        assertEquals(1, log.lowCount())
    }

    @Test
    fun `all() returns all facts`() {
        val log = ObservationLog()
        log.record("battery_status", Source.SYSTEM_API)
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        val all = log.all()
        assertEquals(2, all.size)
        assertEquals("battery_status", all[0].tool)
        assertEquals("read_text_on_screen", all[1].tool)
    }

    @Test
    fun `summary describes counts`() {
        val log = ObservationLog()
        log.record("battery_status", Source.SYSTEM_API)
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        log.record("describe_screen", Source.ACCESSIBILITY_TREE)
        assertEquals("3 observations (1 HIGH, 2 LOW)", log.summary())
    }

    @Test
    fun `summary with all HIGH`() {
        val log = ObservationLog()
        log.record("battery_status", Source.SYSTEM_API)
        log.record("clipboard_read", Source.SYSTEM_API)
        assertEquals("2 observations (2 HIGH, 0 LOW)", log.summary())
    }

    @Test
    fun `record via Fact object`() {
        val log = ObservationLog()
        val f = Fact("battery_status", Source.SYSTEM_API, HIGH, summary = "Battery: 42%")
        log.record(f)
        assertEquals(1, log.size)
        assertEquals("Battery: 42%", log.all()[0].summary)
    }

    @Test
    fun `summary truncated to 120 chars`() {
        val log = ObservationLog()
        val long = "x".repeat(200)
        log.record("read_screen_deep", Source.ACCESSIBILITY_TREE, long)
        assertEquals(120, log.all()[0].summary.length)
    }

    // ── Tool-only observation queries (gate rules use these) ───────

    @Test
    fun `toolObservations excludes user request`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH, summary = "do something"))
        log.record("battery_status", Source.SYSTEM_API)
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        assertEquals(2, log.toolObservations().size)
        assertTrue(log.toolObservations().none { it.source == Source.USER_REQUEST })
    }

    @Test
    fun `hasHighToolObservation true with system API`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        log.record("battery_status", Source.SYSTEM_API)
        assertTrue(log.hasHighToolObservation())
    }

    @Test
    fun `hasHighToolObservation false with only tree`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        assertFalse(log.hasHighToolObservation())
    }

    @Test
    fun `hasHighToolObservation false with no tool observations`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        assertFalse(log.hasHighToolObservation())
    }

    @Test
    fun `hasLowToolOnly true with only tree reads`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        log.record("describe_screen", Source.ACCESSIBILITY_TREE)
        assertTrue(log.hasLowToolOnly())
    }

    @Test
    fun `hasLowToolOnly false with no tool observations`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        assertFalse(log.hasLowToolOnly())
    }

    @Test
    fun `hasLowToolOnly false when system API present`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        log.record("battery_status", Source.SYSTEM_API)
        assertFalse(log.hasLowToolOnly())
    }

    @Test
    fun `hasLowToolOnly false when operator confirmed`() {
        val log = ObservationLog()
        log.record(Fact("<user>", Source.USER_REQUEST, HIGH))
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        log.record(Fact("sms_send", Source.OPERATOR_CONFIRMATION, HIGH))
        assertFalse(log.hasLowToolOnly())
    }

    @Test
    fun `stale system API decays so hasLowToolOnly becomes true`() {
        val log = ObservationLog()
        val old = System.currentTimeMillis() - 200_000
        log.record(Fact("battery_status", Source.SYSTEM_API, HIGH, timestamp = old))
        log.record("read_text_on_screen", Source.ACCESSIBILITY_TREE)
        assertTrue(log.hasLowToolOnly())
        assertFalse(log.hasHighToolObservation())
    }
}
