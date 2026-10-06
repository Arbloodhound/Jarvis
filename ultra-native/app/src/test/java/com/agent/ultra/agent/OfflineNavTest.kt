package com.agent.ultra.agent

import com.agent.ultra.agent.OfflineNav.Action
import com.agent.ultra.agent.OfflineNav.Parsed
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineNavTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name.json")!!.bufferedReader().readText()

    /** A node in the live flattenNode format (centres, ca, e). */
    private fun node(
        i: Int, p: Int, t: String = "", d: String = "", c: Boolean = false, ca: Boolean = false,
        e: Boolean = false, vid: String = "", x: Int = 540, y: Int = 500,
    ) = JSONObject().put("i", i).put("p", p).put("t", t).put("d", d).put("c", c).put("ca", ca)
        .put("e", e).put("s", false).put("vid", vid).put("x", x).put("y", y)

    private fun flat(vararg n: JSONObject) = JSONArray(n.toList()).toString()

    /** Settings › Network: rows are wordless clickable containers with a title and a summary below. */
    private val network = flat(
        node(0, -1, y = 1200),
        node(1, 0, d = "Navigate up", c = true, x = 60, y = 150),
        node(2, 0, t = "Network & internet", y = 150),
        node(3, 0, c = true, y = 400),
        node(4, 3, t = "Wi-Fi", ca = true, y = 380),
        node(5, 3, t = "Connected to Home-5G", ca = true, y = 430),
        node(6, 0, c = true, y = 600),
        node(7, 6, t = "Airplane mode", ca = true, y = 600),
        node(8, 0, c = true, y = 800),
        node(9, 8, t = "Hotspot & tethering", ca = true, y = 780),
        node(10, 8, t = "Off", ca = true, y = 830),
        node(11, 0, t = "Off-screen row", c = true, y = 2600),
    )

    /** Messages compose: a box, a wordless send button whose words are on a child, a row with Delete. */
    private val compose = flat(
        node(0, -1, y = 1200),
        node(1, 0, t = "", e = true, vid = "compose_message_text", x = 450, y = 2200),
        node(2, 0, c = true, vid = "send_message_button_container", x = 1000, y = 2200),
        node(3, 2, d = "Send SMS", ca = true, x = 1000, y = 2200),
        node(4, 0, c = true, y = 800),
        node(5, 4, t = "Alice", ca = true, y = 790),
        node(6, 4, t = "Delete", ca = true, x = 950, y = 800),
        node(7, 0, c = true, vid = "attach_button", x = 60, y = 2200),
    )

    // ── Perceive ────────────────────────────────────────────────────────────────────────────

    @Test fun rowIsOneElementByItsTitleWithItsSummary() {
        val s = OfflineNav.compact(network, 1080, 2400)
        assertEquals(
            "1 \"Navigate up\"\n2 \"Wi-Fi\" (Connected to Home-5G)\n3 \"Airplane mode\"\n4 \"Hotspot & tethering\" (Off)",
            s.text,
        )
        // The tap goes to the node that carries the words, never the wordless container.
        assertEquals(4, s.element(2)!!.node)
        assertEquals(7, s.element(3)!!.node)
        assertNull("off-screen rows are not listed", s.elements.firstOrNull { it.label == "Off-screen row" })
    }

    @Test fun commitmentWordsAreTheirOwnElementAndTapTheirOwnNode() {
        val s = OfflineNav.compact(compose, 1080, 2400)
        val send = s.elements.single { it.label == "Send SMS" }
        assertEquals("the gate must read \"Send SMS\" on the node it taps", 3, send.node)
        val delete = s.elements.single { it.label == "Delete" }
        assertEquals(6, delete.node)
        assertTrue("Alice is listed without Delete folded in", s.text.contains("\"Alice\"\n") || s.text.endsWith("\"Alice\""))
        assertFalse(s.text.contains("(Delete)"))
        val box = s.elements.single { it.typeable }
        assertEquals(1, box.node)
        assertTrue(s.text.contains("${box.n} [box] (empty)"))
        assertTrue("a wordless button is named by its id", s.text.contains("(icon: attach button)"))
    }

    @Test fun sameWordsAtTheSameSpotAreListedOnce() {
        val tabs = flat(
            node(0, -1, y = 1200),
            node(1, 0, d = "Timer", c = true, x = 800, y = 2250),
            node(2, 1, t = "Timer", ca = true, x = 800, y = 2290),
            node(3, 0, d = "Delete", c = true, x = 900, y = 400),
            node(4, 0, d = "Delete", c = true, x = 900, y = 900),
        )
        val s = OfflineNav.compact(tabs, 1080, 2400)
        assertEquals(1, s.elements.count { it.label == "Timer" })
        assertEquals("another row's own Delete is still offered", 2, s.elements.count { it.label == "Delete" })
    }

    @Test fun realSettingsCaptureIsSmallAndPointsAtClickableNodes() {
        val raw = fixture("native-settings")
        val s = OfflineNav.compact(raw, 1080, 2340)
        assertTrue(s.text, s.text.startsWith("1 \"Navigate up\"\n2 \"Search apps\"\n3 \"More options\"\n4 \"Calendar,\""))
        assertEquals(16, s.elements.size)
        assertEquals(0, s.hidden)
        val clickable = JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("c") }.map { it.getInt("i") }.toSet() }
        assertTrue(s.elements.all { it.node in clickable })
    }

    @Test fun realClockCaptureListsTheTabs() {
        val s = OfflineNav.compact(fixture("native-clock"), 1080, 2340)
        assertEquals(129, s.elements.single { it.label == "Timer" }.node)
        assertEquals(126, s.elements.single { it.label == "Stopwatch" }.node)
        assertTrue(s.text.length <= OfflineNav.MAX_SCREEN_CHARS)
    }

    @Test fun bigWebPageIsCappedAndSaysSo() {
        val s = OfflineNav.compact(fixture("amazon-search"), 1080, 2340)
        assertTrue(s.elements.size <= OfflineNav.MAX_ELEMENTS)
        assertTrue(s.hidden > 0)
        val listed = s.text.substringBeforeLast("\n(")
        assertTrue("screen block ${listed.length} chars", listed.length <= OfflineNav.MAX_SCREEN_CHARS)
        assertTrue(s.text.endsWith("more not shown — scroll down)"))
        assertTrue("labels are cut", s.text.lines().all { it.length <= 6 + OfflineNav.LABEL_CHARS + 2 + OfflineNav.EXTRA_CHARS + 3 + 8 })
        // Numbers are dense: n is the position in the list.
        s.elements.forEachIndexed { k, e -> assertEquals(k + 1, e.n) }
    }

    @Test fun emptyOrBrokenDumpSaysWhatToDo() {
        assertEquals(0, OfflineNav.compact("[]").elements.size)
        assertTrue(OfflineNav.compact("not json").text.contains("scroll down or back"))
    }

    // ── Think: parsing ──────────────────────────────────────────────────────────────────────

    private val screen = OfflineNav.compact(compose, 1080, 2400)
    private val boxN = screen.elements.single { it.typeable }.n
    private val aliceN = screen.elements.single { it.label == "Alice" }.n

    private fun ok(reply: String): Action = (OfflineNav.parse(reply, screen) as? Parsed.Ok)?.action
        ?: throw AssertionError("expected \"$reply\" to parse, got ${OfflineNav.parse(reply, screen)}")

    private fun bad(reply: String) =
        assertTrue("expected \"$reply\" to be refused", OfflineNav.parse(reply, screen) is Parsed.Bad)

    @Test fun everyActionForm() {
        assertEquals(Action.Tap(aliceN), ok("tap $aliceN"))
        assertEquals(Action.Type(boxN, "on my way"), ok("type $boxN \"on my way\""))
        assertEquals(Action.Scroll(true), ok("scroll down"))
        assertEquals(Action.Scroll(false), ok("scroll up"))
        assertEquals(Action.Back, ok("back"))
        assertEquals(Action.Home, ok("home"))
        assertEquals(Action.OpenApp("Clock"), ok("open_app Clock"))
        assertEquals(Action.OpenApp("Google Maps"), ok("open_app \"Google Maps\""))
        assertEquals(Action.Done("Wi-Fi is on"), ok("done \"Wi-Fi is on\""))
        assertEquals(Action.Done(""), ok("done"))
        assertEquals(Action.Fail("no such setting"), ok("fail \"no such setting\""))
    }

    @Test fun smallSlipsAroundTheLineAreForgiven() {
        assertEquals(Action.Tap(aliceN), ok("<think>\n\n</think>\n\ntap $aliceN"))
        assertEquals(Action.Tap(aliceN), ok("Action: tap $aliceN"))
        assertEquals(Action.Tap(aliceN), ok("`tap $aliceN`"))
        assertEquals(Action.Tap(aliceN), ok("TAP $aliceN\n"))
        assertEquals(Action.Tap(aliceN), ok("tap($aliceN)"))
        assertEquals(Action.Type(boxN, "hi"), ok("type $boxN “hi”"))
        // Only the first line counts.
        assertEquals(Action.Back, ok("back\nThen I will tap 2."))
    }

    @Test fun anythingElseIsRefused() {
        bad("")
        bad("I will tap the Wi-Fi row")
        bad("tap 99")
        bad("tap 0")
        bad("tap $aliceN and then 3")
        bad("type $aliceN \"hello\"")          // not a box
        bad("type $boxN \"\"")                 // nothing to type
        bad("type $boxN hello")                // unquoted
        bad("type $boxN \"" + "x".repeat(201) + "\"")
        bad("scroll left")
        bad("done Wi-Fi is on")                // unquoted answer
        bad("{\"tool\":\"tap\",\"n\":2}")
    }

    @Test fun reaskNoteSaysWhyAndShowsTheForm() {
        val why = (OfflineNav.parse("tap 99", screen) as Parsed.Bad).why
        assertEquals("99 is not on the list", why)
        assertTrue(OfflineNav.reaskNote(why).contains("tap 3"))
    }

    // ── Act: into the navigator's own syntax ────────────────────────────────────────────────

    @Test fun actionsBecomeTheNavigatorsActionsOnTheListedNodes() {
        val alice = screen.element(aliceN)!!.node
        assertEquals("tap($alice)", OfflineNav.toNavigator(Action.Tap(aliceN), screen))
        val typed = OfflineNav.toNavigator(Action.Type(boxN, "it's 5 o'clock"), screen)!!
        assertEquals(1 to "it's 5 o'clock", ReActNavigator.parseType(typed))
        assertEquals("scroll(down)", OfflineNav.toNavigator(Action.Scroll(true), screen))
        assertEquals("back()", OfflineNav.toNavigator(Action.Back, screen))
        assertEquals("home()", OfflineNav.toNavigator(Action.Home, screen))
        assertNull(OfflineNav.toNavigator(Action.OpenApp("Clock"), screen))
        assertNull(OfflineNav.toNavigator(Action.Done("x"), screen))
        assertNull("type never lands on a non-box", OfflineNav.toNavigator(Action.Type(aliceN, "x"), screen))
    }

    @Test fun historyLineNamesWhatWasTappedAndIsShort() {
        val line = OfflineNav.historyLine(3, Action.Tap(aliceN), screen, "screen changed")
        assertEquals("3. tap $aliceN (\"Alice\") -> screen changed", line)
        val long = OfflineNav.historyLine(4, Action.Type(boxN, "x".repeat(150)), screen, "NOT DONE: " + "y".repeat(80))
        assertTrue(long.length <= OfflineNav.HISTORY_LINE_CHARS)
    }

    // ── Verify: loops and change ────────────────────────────────────────────────────────────

    @Test fun sameActionOnTheSameScreenTwiceIsALoop() {
        val g = OfflineNav.LoopGuard()
        val sig = OfflineNav.signature(network, "com.android.settings")
        assertFalse(g.repeated(sig, Action.Tap(2)))
        assertFalse(g.repeated(sig, Action.Tap(3)))
        assertFalse(g.repeated(sig, Action.Back))
        assertTrue(g.repeated(sig, Action.Tap(2)))
    }

    @Test fun aKeypadWhoseDisplayChangesIsNotALoop() {
        fun keypad(display: String) = flat(
            node(0, -1, y = 1200),
            node(1, 0, t = display, y = 300),
            node(2, 0, t = "5", c = true, y = 1500),
            node(3, 0, t = "Start", c = true, y = 2100),
        )
        val g = OfflineNav.LoopGuard()
        val before = OfflineNav.signature(keypad("00h 00m 00s"), "clock")
        val after = OfflineNav.signature(keypad("00h 00m 05s"), "clock")
        assertTrue("the tappable list is the same, the screen is not", before != after)
        assertFalse(g.repeated(before, Action.Tap(1)))
        assertFalse(g.repeated(after, Action.Tap(1)))
        assertTrue(g.repeated(after, Action.Tap(1)))
    }

    @Test fun signatureIncludesTheApp() =
        assertTrue(OfflineNav.signature(network, "a") != OfflineNav.signature(network, "b"))

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────

    @Test fun budgets() {
        assertNull(OfflineNav.budgetStop(0, 0))
        assertNull(OfflineNav.budgetStop(11, OfflineNav.MAX_MILLIS - 1))
        assertEquals("ran out of steps (12)", OfflineNav.budgetStop(12, 0))
        assertEquals("ran out of time (6 min)", OfflineNav.budgetStop(3, OfflineNav.MAX_MILLIS))
    }

    @Test fun stepSecondsFromSpeeds() {
        // 500 new prompt tokens at 25 tok/s + 12 answer tokens at 3 tok/s = 20 + 4 s.
        assertEquals(24.0, OfflineNav.expectedStepSeconds(500, 12, 3.0, 25.0)!!, 1e-9)
        assertNull(OfflineNav.expectedStepSeconds(500, 12, null, 25.0))
        assertNull(OfflineNav.expectedStepSeconds(500, 12, 3.0, 0.0))
        assertNull(OfflineNav.expectedStepSeconds(500, 12, Double.NaN, 25.0))
    }

    // ── Prompt ──────────────────────────────────────────────────────────────────────────────

    @Test fun promptIsNoThinkAndKeepsItsPrefixAcrossSteps() {
        val s1 = OfflineNav.compact(network, 1080, 2400)
        val p1 = OfflineNav.prompt("turn on wifi", emptyList(), s1, "com.android.settings")
        assertTrue(p1.endsWith("Next action: /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(p1.indexOf("GOAL: turn on wifi") < p1.indexOf("SCREEN (com.android.settings):"))
        val h = listOf("1. tap 2 (\"Wi-Fi\") -> screen changed")
        val p2 = OfflineNav.prompt("turn on wifi", h, s1, "com.android.settings")
        val p3 = OfflineNav.prompt("turn on wifi", h + "2. tap 1 -> screen changed", s1, "com.android.settings")
        // Fixed rules + goal + history come first, so the next step re-reads only what is new.
        val shared = p2.substringBefore("SCREEN")
        assertTrue(p3.startsWith(shared))
        assertTrue(p1.startsWith(p1.substringBefore("GOAL:")) && p2.startsWith(p1.substringBefore("SCREEN")))
        val withNote = OfflineNav.prompt("turn on wifi", h, s1, "x", note = "Your last reply was not usable")
        assertTrue(withNote.indexOf("NOTE:") > withNote.indexOf("SCREEN"))
    }

    @Test fun promptStaysSmallOnABigPage() {
        val p = OfflineNav.prompt("search for headphones", List(12) { "x".repeat(OfflineNav.HISTORY_LINE_CHARS) },
            OfflineNav.compact(fixture("amazon-search"), 1080, 2340), "com.android.chrome")
        // ~3 chars a token: under ~1.3k tokens even with a full history.
        assertTrue("prompt ${p.length} chars", p.length < 4000)
    }

    // ── Which requests ──────────────────────────────────────────────────────────────────────

    @Test fun multiStepScreenJobsAreTaken() {
        assertEquals(OfflineNav.Task("open settings and turn on dark mode", "settings"),
            OfflineNav.task("open settings and turn on dark mode"))
        assertEquals("chrome", OfflineNav.task("Please open Chrome, then search for pizza near me")!!.appHint)
        assertEquals("chrome", OfflineNav.task("search for cats in chrome")!!.appHint)
        assertEquals("clock", OfflineNav.task("start the stopwatch in the clock app")!!.appHint)
        assertEquals("spotify", OfflineNav.task("play my liked songs on Spotify")!!.appHint)
        assertEquals("", OfflineNav.task("tap the Start button")!!.appHint)
        assertEquals("", OfflineNav.task("scroll down")!!.appHint)
        assertEquals("settings", OfflineNav.task("can you open the settings app and change the font size?")!!.appHint)
    }

    @Test fun commandsAndQuestionsAreNot() {
        for (r in listOf(
            "open chrome",
            "turn on wifi",
            "turn on do not disturb on my phone",
            "set an alarm for 7 on monday",
            "set an alarm for 7 in the morning",
            "what is the weather in paris",
            "how do I search in chrome",
            "is bluetooth on",
            "tell me a joke",
            "",
            "open settings and",
            "type hello in the search box",
        ))assertNull(r, OfflineNav.task(r))
    }
}
