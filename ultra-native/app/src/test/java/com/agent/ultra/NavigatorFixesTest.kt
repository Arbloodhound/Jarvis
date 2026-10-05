package com.agent.ultra

import com.agent.ultra.agent.ReActNavigator
import com.agent.ultra.agent.RouteWalker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pieces of the navigator fixed after review (2026-10-05). */
class NavigatorFixesTest {

    private fun node(
        i: Int, t: String = "", d: String = "", vid: String = "",
        c: Boolean = false, e: Boolean = false, x: Int = 500, y: Int = 500,
    ): JSONObject = JSONObject().put("i", i).put("t", t).put("d", d).put("vid", vid)
        .put("c", c).put("ca", false).put("e", e).put("s", false).put("x", x).put("y", y)

    private fun flat(vararg n: JSONObject) = JSONArray().apply { n.forEach { put(it) } }.toString()

    // ── goal satisfied only for navigation, only by a place ───────────

    @Test
    fun `only a goal that is nothing but going somewhere has a navigation target`() {
        assertEquals("google.com", ReActNavigator.navigationTarget("go to google.com in Chrome"))
        assertEquals("github.com", ReActNavigator.navigationTarget("Open https://www.github.com/"))
        assertEquals("wikipedia.org", ReActNavigator.navigationTarget("visit wikipedia.org"))
        assertNull(ReActNavigator.navigationTarget("email bob@gmail.com saying hi"))
        assertNull(ReActNavigator.navigationTarget("open gmail and send bob@gmail.com a note"))
        assertNull(ReActNavigator.navigationTarget("go to amazon.com and buy socks"))
    }

    @Test
    fun `a domain typed into a box or inside an email address is not the page`() {
        val typed = flat(node(0, t = "bob@gmail.com", e = true, vid = "to_field"))
        assertFalse(ReActNavigator.domainShown(typed, "gmail.com"))
        val inBox = flat(node(0, t = "google.com", e = true, vid = "message_body"))
        assertFalse(ReActNavigator.domainShown(inBox, "google.com"))
        val email = flat(node(0, t = "Contact bob@google.com"))
        assertFalse(ReActNavigator.domainShown(email, "google.com"))
    }

    @Test
    fun `a domain in the address bar or on the page is the page`() {
        assertTrue(ReActNavigator.domainShown(flat(node(0, t = "google.com", e = true, vid = "url_bar")), "google.com"))
        assertTrue(ReActNavigator.domainShown(flat(node(0, t = "https://www.google.com/")), "google.com"))
        assertFalse("a longer name is not this one", ReActNavigator.domainShown(flat(node(0, t = "notgoogle.com")), "google.com"))
    }

    // ── type() text may contain a bracket ─────────────────────────────

    @Test
    fun `type keeps a closing bracket inside the text`() {
        assertEquals(null to "call me :)", ReActNavigator.parseType("type(\"call me :)\")"))
        assertEquals(3 to "a (b) c", ReActNavigator.parseType("type(3, \"a (b) c\")"))
        assertEquals(null to "it's", ReActNavigator.parseType("type(\"it's\")"))
        assertNull(ReActNavigator.parseType("tap(\"Send\")"))
    }

    // ── Enter after typing ────────────────────────────────────────────

    @Test
    fun `enter beside a send button is a submit`() {
        val chat = flat(node(0, t = "hi bob", e = true, vid = "compose_text"), node(1, d = "Send", c = true))
        assertEquals("send", ReActNavigator.enterMaySubmit(chat, 0, null, "hi bob"))
        val idOnly = flat(node(0, e = true, vid = "compose"), node(1, vid = "btn_send", c = true))
        assertEquals("send", ReActNavigator.enterMaySubmit(idOnly, null, null, "hello there"))
    }

    @Test
    fun `enter in a search or address box, or on a web address, is not gated`() {
        val search = flat(node(0, t = "cats", e = true, vid = "search_src_text"), node(1, d = "Send feedback", c = true))
        assertNull(ReActNavigator.enterMaySubmit(search, 0, null, "cats"))
        val chat = flat(node(0, e = true, vid = "compose"), node(1, d = "Send", c = true))
        assertNull("a web address", ReActNavigator.enterMaySubmit(chat, 0, null, "google.com"))
        val form = flat(node(0, e = true, vid = "name"), node(1, t = "OK", c = true), node(2, t = "Cancel", c = true))
        assertNull("nothing on screen that Enter would commit", ReActNavigator.enterMaySubmit(form, 0, null, "my note"))
        val notes = flat(node(0, e = true, vid = "editor"), node(1, d = "Delete", c = true))
        assertNull("Enter does not delete", ReActNavigator.enterMaySubmit(notes, 0, null, "a line"))
    }

    // ── a wordless button is judged by its id ─────────────────────────

    @Test
    fun `a view id reads as words the gate understands`() {
        assertEquals("send button", ReActNavigator.vidAsWords("send_button"))
        assertEquals("send button", ReActNavigator.vidAsWords("sendButton"))
        assertNotNull(com.agent.ultra.agent.ActionGate.commitmentIn(ReActNavigator.vidAsWords("btn_send")))
        assertNotNull(com.agent.ultra.agent.ActionGate.commitmentIn(ReActNavigator.vidAsWords("send_button")))
    }

    // ── duplicate labels ──────────────────────────────────────────────

    @Test
    fun `same words close together are one control, far apart are two`() {
        assertTrue("tab icon and its text", ReActNavigator.sameSpot(200.0, 2200.0, 200.0, 2260.0))
        assertFalse("Delete on the next row", ReActNavigator.sameSpot(1000.0, 600.0, 1000.0, 760.0))
    }

    // ── remembered doors on busy screens ──────────────────────────────

    @Test
    fun `a remembered door is found even past the twelve best candidates`() {
        val nodes = (0 until 14).map { node(it, t = "item $it", vid = "nav_$it", c = true) } +
            node(14, vid = "menu_button", c = true)
        val f = flat(*nodes.toTypedArray())
        assertFalse("the ranked list is capped", RouteWalker.candidatesFromFlat(f).any { it.vid == "menu_button" })
        assertEquals(14, RouteWalker.rememberedChoice(f, "menu_button")?.index)
    }
}
