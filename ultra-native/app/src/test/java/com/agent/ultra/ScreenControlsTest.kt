package com.agent.ultra

import com.agent.ultra.agent.ScreenControls
import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remembering where a screen's controls are.
 *
 * The privacy line is the important part and is tested first: a view id is a
 * constant from a layout file, a label is user content. "Send to Mum" and
 * "Pay £240.00" must never reach the database.
 */
class ScreenControlsTest {

    private fun node(
        i: Int, vid: String = "", cls: String = "Button",
        editable: Boolean = false, clickable: Boolean = true,
        enabled: Boolean = true, text: String = "",
    ) = ScreenStructure.Node(
        index = i, parent = -1, depth = 1, text = text, desc = "",
        clickable = clickable, top = 0, bottom = 40, cls = cls, vid = vid,
        editable = editable, enabled = enabled,
    )

    @Test
    fun `no label or text is ever stored`() {
        val controls = ScreenControls.of(listOf(
            node(1, vid = "send_button", text = "Send to Mum"),
            node(2, vid = "amount_field", editable = true, text = "240.00"),
        ))
        val json = ScreenControls.toJson(controls)
        assertFalse("a label must never be written", json.contains("Mum"))
        assertFalse("a value must never be written", json.contains("240"))
        assertTrue("the app's own id is what is kept", json.contains("send_button"))
    }

    @Test
    fun `a control with no view id is not remembered`() {
        // It is still usable in the moment through the indexed list. This is
        // about what survives to the next visit, and a label is not safe to
        // keep.
        val controls = ScreenControls.of(listOf(node(1, vid = "", text = "Pay now")))
        assertTrue(controls.isEmpty())
    }

    @Test
    fun `content dressed up as an id is not remembered either`() {
        val controls = ScreenControls.of(listOf(
            node(1, vid = "49417298"),
            node(2, vid = "0d6f55ac-a8a3-46bb-a6a5-f7e069fa3ff1"),
            node(3, vid = "search_box", editable = true),
        ))
        assertEquals(listOf("search_box"), controls.map { it.vid })
    }

    @Test
    fun `an input is told apart from a button`() {
        val controls = ScreenControls.of(listOf(
            node(1, vid = "url_bar", editable = true),
            node(2, vid = "go_button"),
        ))
        assertEquals(ScreenControls.Role.INPUT, controls.first { it.vid == "url_bar" }.role)
        assertEquals(ScreenControls.Role.BUTTON, controls.first { it.vid == "go_button" }.role)
    }

    @Test
    fun `a disabled control is not offered`() {
        val controls = ScreenControls.of(listOf(node(1, vid = "submit_button", enabled = false)))
        assertTrue(controls.isEmpty())
    }

    // ── Finding the right one ───────────────────────────────────────

    private val chromeish = ScreenControls.of(listOf(
        node(1, vid = "url_bar", editable = true),
        node(2, vid = "search_src_text", editable = true),
        node(3, vid = "address_bar_container"),
        node(4, vid = "menu_button"),
        node(5, vid = "add_bookmark"),
    ))

    @Test
    fun `an intent finds the developer's own word for it`() {
        assertEquals("search_src_text", ScreenControls.find(chromeish, "search")?.vid)
        assertEquals("menu_button", ScreenControls.find(chromeish, "menu")?.vid)
    }

    @Test
    fun `a short intent does not match the middle of another word`() {
        // "add" must not select address_bar_container.
        assertEquals("add_bookmark", ScreenControls.find(chromeish, "add")?.vid)
    }

    @Test
    fun `an intent with no match returns nothing rather than a guess`() {
        assertNull(ScreenControls.find(chromeish, "checkout"))
        assertNull(ScreenControls.find(emptyList(), "search"))
    }

    @Test
    fun `asking for an input never returns a button`() {
        val found = ScreenControls.find(chromeish, "menu", ScreenControls.Role.INPUT)
        assertNull("menu_button is a button, not an input", found)
    }

    // ── Round trip ──────────────────────────────────────────────────

    @Test
    fun `controls survive being stored and read back`() {
        val json = ScreenControls.toJson(chromeish)
        val back = ScreenControls.fromJson(json)
        assertEquals(chromeish.map { it.vid }, back.map { it.vid })
        assertEquals(chromeish.map { it.role }, back.map { it.role })
    }

    @Test
    fun `a corrupt stored value reads back as nothing, not a crash`() {
        assertTrue(ScreenControls.fromJson("not json").isEmpty())
        assertTrue(ScreenControls.fromJson("").isEmpty())
        assertTrue(ScreenControls.fromJson("""[{"v":"x","r":"NOPE"}]""").isEmpty())
    }

    @Test
    fun `a real native screen yields its controls`() {
        val nodes = ScreenStructure.parse(
            javaClass.getResourceAsStream("/native-clock.json")!!.bufferedReader().readText()
        )
        val controls = ScreenControls.of(nodes)
        assertTrue("the clock screen has controls, got ${controls.size}", controls.isNotEmpty())
        assertNotNull("adding an alarm is one of them",
            ScreenControls.find(controls, "alarm"))
        val json = ScreenControls.toJson(controls)
        assertFalse("no alarm name may be stored", json.contains("Morning"))
        assertFalse("no time may be stored", json.contains("8:15"))
    }
}
