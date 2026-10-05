package com.agent.ultra

import com.agent.ultra.gate.Fact
import com.agent.ultra.gate.Fact.Confidence.HIGH
import com.agent.ultra.gate.Fact.Confidence.LOW
import com.agent.ultra.gate.Fact.Source
import org.junit.Assert.*
import org.junit.Test

class FactTest {

    @Test
    fun `system API observations are HIGH confidence`() {
        assertEquals(HIGH, Fact.confidenceOf(Source.SYSTEM_API))
    }

    @Test
    fun `tree observations are LOW confidence`() {
        assertEquals(LOW, Fact.confidenceOf(Source.ACCESSIBILITY_TREE))
    }

    @Test
    fun `operator confirmation is HIGH confidence`() {
        assertEquals(HIGH, Fact.confidenceOf(Source.OPERATOR_CONFIRMATION))
    }

    @Test
    fun `user request is HIGH confidence`() {
        assertEquals(HIGH, Fact.confidenceOf(Source.USER_REQUEST))
    }

    @Test
    fun `recent fact keeps its confidence`() {
        val f = Fact("battery_status", Source.SYSTEM_API, HIGH)
        assertEquals(HIGH, f.effectiveConfidence())
        assertTrue(f.isRecent())
    }

    @Test
    fun `stale fact decays to LOW`() {
        val old = System.currentTimeMillis() - 200_000
        val f = Fact("battery_status", Source.SYSTEM_API, HIGH, timestamp = old)
        assertEquals(LOW, f.effectiveConfidence())
        assertFalse(f.isRecent())
    }

    @Test
    fun `stale LOW stays LOW`() {
        val old = System.currentTimeMillis() - 200_000
        val f = Fact("read_text_on_screen", Source.ACCESSIBILITY_TREE, LOW, timestamp = old)
        assertEquals(LOW, f.effectiveConfidence())
    }

    @Test
    fun `custom max age is respected`() {
        val tenSecsAgo = System.currentTimeMillis() - 10_000
        val f = Fact("battery_status", Source.SYSTEM_API, HIGH, timestamp = tenSecsAgo)
        assertTrue(f.isRecent(20_000))
        assertFalse(f.isRecent(5_000))
        assertEquals(HIGH, f.effectiveConfidence(20_000))
        assertEquals(LOW, f.effectiveConfidence(5_000))
    }

    @Test
    fun `sourceOf maps battery to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("battery_status"))
    }

    @Test
    fun `sourceOf maps device_info to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("device_info"))
    }

    @Test
    fun `sourceOf maps clipboard to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("clipboard_read"))
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("clipboard_write"))
    }

    @Test
    fun `sourceOf maps screen reads to TREE`() {
        assertEquals(Source.ACCESSIBILITY_TREE, Fact.sourceOf("read_text_on_screen"))
        assertEquals(Source.ACCESSIBILITY_TREE, Fact.sourceOf("read_screen_deep"))
        assertEquals(Source.ACCESSIBILITY_TREE, Fact.sourceOf("describe_screen"))
    }

    @Test
    fun `sourceOf maps toggles to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("flashlight_toggle"))
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("wifi_toggle"))
    }

    @Test
    fun `sourceOf returns null for actions`() {
        assertNull(Fact.sourceOf("react_navigate"))
        assertNull(Fact.sourceOf("app_launch"))
        assertNull(Fact.sourceOf("open_url"))
        assertNull(Fact.sourceOf("sms_send"))
    }

    @Test
    fun `sourceOf returns null for recipes`() {
        assertNull(Fact.sourceOf("recipe_run"))
        assertNull(Fact.sourceOf("recipe_save"))
    }

    @Test
    fun `sourceOf maps location to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("device_location"))
    }

    @Test
    fun `sourceOf maps sms_read to SYSTEM_API`() {
        assertEquals(Source.SYSTEM_API, Fact.sourceOf("sms_read"))
    }

    @Test
    fun `sourceOf maps notification_read to TREE`() {
        assertEquals(Source.ACCESSIBILITY_TREE, Fact.sourceOf("notification_read"))
    }
}
