package com.agent.ultra

import com.agent.ultra.agent.RouteWalker
import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an agent may touch while guessing its way through an app.
 *
 * Replaying a recorded route means searching, and searching means tapping
 * things to see what they do — on someone's real phone, in an app they allowed
 * it into. "Delete account" and "Confirm payment" are controls like any other
 * and a search does not know the difference. These tests are that difference.
 */
class RouteWalkerTest {

    private fun node(
        i: Int, label: String = "", vid: String = "",
        clickable: Boolean = true, enabled: Boolean = true,
    ) = ScreenStructure.Node(
        index = i, parent = -1, depth = 1, text = label, desc = "",
        clickable = clickable, top = i * 10, bottom = i * 10 + 8,
        cls = "Button", vid = vid, editable = false, enabled = enabled,
    )

    // ── What must never be guessed at ───────────────────────────────

    @Test
    fun `anything that commits is never tapped on spec`() {
        for (label in listOf(
            "Pay", "Pay now", "Buy it now", "Place order", "Checkout",
            "Send", "Transfer", "Confirm", "Confirm and pay", "Submit",
            "Delete", "Delete account", "Remove card", "Sign out",
            "Book", "Reserve", "Accept", "Agree", "Install", "Call",
            "Share", "Post", "Withdraw",
        )) {
            assertTrue("'$label' must not be guessed at", RouteWalker.tooRiskyToGuess(label))
        }
    }

    @Test
    fun `ordinary navigation is fine to try`() {
        for (label in listOf(
            "Settings", "History", "Bookmarks", "More", "Menu", "Back",
            "Payment history", "Account settings", "Notifications", "Search",
        )) {
            assertFalse("'$label' is navigation", RouteWalker.tooRiskyToGuess(label))
        }
    }

    @Test
    fun `a risky word inside another word does not trip it`() {
        // "payment history" is a page; "pay" is a button. The difference is the
        // whole word, which is the same rule four other bugs today needed.
        assertFalse(RouteWalker.tooRiskyToGuess("Payment history"))
        assertFalse(RouteWalker.tooRiskyToGuess("Shareholder report"))
        assertFalse(RouteWalker.tooRiskyToGuess("Bookmarks"))
        assertTrue(RouteWalker.tooRiskyToGuess("Book"))
    }

    @Test
    fun `an unlabelled control is allowed, because it commits to nothing visible`() {
        assertFalse(RouteWalker.tooRiskyToGuess(""))
    }

    // ── What gets tried, and in what order ──────────────────────────

    @Test
    fun `dangerous controls are absent, not merely last`() {
        val c = RouteWalker.candidates(listOf(
            node(1, "Settings", "settings_button"),
            node(2, "Pay now", "pay_button"),
            node(3, "History", "history_button"),
        ))
        assertEquals(listOf("settings_button", "history_button"), c.map { it.vid })
    }

    @Test
    fun `a control the app named is tried before an anonymous one`() {
        val c = RouteWalker.candidates(listOf(
            node(1, "", ""),
            node(2, "Menu", "menu_button"),
        ))
        assertEquals("menu_button", c.first().vid)
    }

    @Test
    fun `things that cannot be tapped are not candidates`() {
        val c = RouteWalker.candidates(listOf(
            node(1, "Label only", clickable = false),
            node(2, "Greyed out", "disabled_btn", enabled = false),
            node(3, "Menu", "menu_button"),
        ))
        assertEquals(1, c.size)
        assertEquals("menu_button", c[0].vid)
    }

    @Test
    fun `something already tried is not tried again`() {
        val nodes = listOf(node(1, "Menu", "menu_button"), node(2, "History", "history_button"))
        val first = RouteWalker.candidates(nodes)
        val tried = setOf(RouteWalker.keyOf(first.first()))
        val second = RouteWalker.candidates(nodes, tried)
        assertFalse(second.any { RouteWalker.keyOf(it) in tried })
    }

    @Test
    fun `a crowded screen does not become an endless search`() {
        val many = (1..80).map { node(it, "Item $it", "item_$it") }
        assertTrue(RouteWalker.candidates(many).size <= 12)
    }

    @Test
    fun `content-shaped ids are treated as anonymous`() {
        // Product codes and record numbers change every visit, so remembering
        // one as "the control I tried" is remembering nothing.
        val c = RouteWalker.candidates(listOf(node(1, "Thing", "1248879011")))
        assertEquals("", c.first().vid)
        assertEquals("@1", RouteWalker.keyOf(c.first()))
    }

    /**
     * An icon button with no text is still a button, and the app's own id is
     * the only name it has. Chrome's menu button carries a description; a
     * "delete" icon in a file manager often carries nothing at all, and a
     * search that read only labels would tap it.
     */
    @Test
    fun `a risky view id is refused even with no label`() {
        val flat = """[
          {"i":0,"c":true,"t":"","d":"","vid":"delete_button"},
          {"i":1,"c":true,"t":"","d":"","vid":"history_button"}
        ]"""
        val names = RouteWalker.candidatesFromFlat(flat).map { it.vid }
        assertFalse("delete_button" in names)
        assertTrue("history_button" in names)
    }

    /** Underscores separate words, so a two-word phrase is caught in an id. */
    @Test
    fun `a two word risk phrase is caught inside a view id`() {
        val flat = """[{"i":0,"c":true,"t":"","d":"","vid":"place_order_cta"}]"""
        assertTrue(RouteWalker.candidatesFromFlat(flat).isEmpty())
    }

    /**
     * "New tab" is safe to press and still belongs last: there is no history
     * behind a blank page, so back cannot return the search to where it was.
     */
    @Test
    fun `a control that opens a new context is tried last, not refused`() {
        val flat = """[
          {"i":0,"c":true,"t":"","d":"New tab","vid":"optional_toolbar_button"},
          {"i":1,"c":true,"t":"","d":"Customize and control","vid":"menu_button"}
        ]"""
        val order = RouteWalker.candidatesFromFlat(flat).map { it.vid }
        assertEquals(listOf("menu_button", "optional_toolbar_button"), order)
    }

    /**
     * The routine's name is the only statement of intent in a replay. A menu
     * of icon buttons and named rows should offer the one the user named.
     */
    @Test
    fun `a control echoing the routine name is tried first`() {
        val flat = """[
          {"i":0,"c":true,"t":"","d":"Forward","vid":"button_one"},
          {"i":1,"c":true,"t":"","d":"Bookmark","vid":"button_two"},
          {"i":2,"c":true,"t":"","d":"","vid":"open_history_menu_id"},
          {"i":3,"c":true,"t":"","d":"","vid":"downloads_menu_id"}
        ]"""
        val first = RouteWalker.candidatesFromFlat(flat, emptySet(), "chrome history").first()
        assertEquals("open_history_menu_id", first.vid)
    }

    /** With nothing in common, the order is exactly what it was before. */
    @Test
    fun `an unrelated routine name changes nothing`() {
        val flat = """[
          {"i":0,"c":true,"t":"","d":"Forward","vid":"button_one"},
          {"i":1,"c":true,"t":"","d":"","vid":"downloads_menu_id"}
        ]"""
        val withGoal = RouteWalker.candidatesFromFlat(flat, emptySet(), "pay the gas bill").map { it.vid }
        val without = RouteWalker.candidatesFromFlat(flat).map { it.vid }
        assertEquals(without, withGoal)
    }
}
