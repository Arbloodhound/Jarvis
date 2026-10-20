package com.agent.ultra

import com.agent.ultra.agent.ScreenSignature
import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising a screen, and reusing what was learned about it.
 *
 * The point is not speed. It is that the same screen should read the same way
 * every time. Before this, one Amazon page came back as 130 records, then 126,
 * then 123 across a single scrolling read, because each pass re-derived the
 * winner from whatever happened to be on screen.
 */
class ScreenMemoryTest {

    private fun tree(name: String) = ScreenStructure.parse(
        javaClass.getResourceAsStream("/$name.json")!!.bufferedReader().readText()
    )

    // ── Fingerprints ────────────────────────────────────────────────

    @Test
    fun `a native screen is recognised by the app's own view ids`() {
        val fp = ScreenSignature.of("com.sec.android.app.clockpackage", tree("native-clock"))
        assertEquals(ScreenSignature.Confidence.IDS, fp.confidence)
        assertTrue(fp.known)
    }

    @Test
    fun `the same screen fingerprints the same twice`() {
        val a = ScreenSignature.of("pkg", tree("native-clock"))
        val b = ScreenSignature.of("pkg", tree("native-clock"))
        assertEquals(a.key, b.key)
    }

    @Test
    fun `two different screens do not collide`() {
        val clock = ScreenSignature.of("pkg", tree("native-clock"))
        val settings = ScreenSignature.of("pkg", tree("native-settings"))
        assertNotEquals(clock.key, settings.key)
    }

    @Test
    fun `the same screen in two apps is not the same screen`() {
        val a = ScreenSignature.of("com.one", tree("native-clock"))
        val b = ScreenSignature.of("com.two", tree("native-clock"))
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `two different websites in the same browser do not collide`() {
        // Both pages carry Chrome's own toolbar ids — location_bar,
        // menu_button and 20 others that are identical on every page in the
        // browser. What separates them is the page's own ids: Amazon exposes
        // 142, Hacker News 24. This is the case that would silently recall the
        // wrong template for the wrong site.
        val amazon = ScreenSignature.of("com.android.chrome", tree("amazon-search"))
        val hn = ScreenSignature.of("com.android.chrome", tree("hn-front"))
        assertNotEquals(amazon.key, hn.key)
        assertTrue(amazon.known && hn.known)
    }

    @Test
    fun `content dressed up as a view id is not treated as one`() {
        // Read off live pages: Amazon exposes product codes and UUIDs as ids,
        // Hacker News exposes story numbers. They change on every visit, so a
        // fingerprint built on them would never match the same page twice, and
        // as a field name one would reach the model as "49417298: 130 points".
        for (content in listOf(
            "49417298", "1248879011", "0d6f55ac-a8a3-46bb-a6a5-f7e069fa3ff1",
            "0e5bd316", "12345", "a1b2c3",
        )) {
            assertFalse("$content is content, not a name",
                ScreenStructure.looksLikeAName(content))
            assertNull("$content must not become a field name",
                ScreenStructure.fieldNameFor(content))
        }
        // Real ids still pass.
        for (name in listOf("alarm_item_time", "nav-main", "price", "location_bar")) {
            assertTrue("$name is a real id", ScreenStructure.looksLikeAName(name))
        }
    }

    @Test
    fun `an empty screen has no fingerprint`() {
        val fp = ScreenSignature.of("pkg", emptyList())
        assertFalse(fp.known)
        assertEquals(ScreenSignature.Confidence.NONE, fp.confidence)
    }

    // ── Reusing the remembered template ─────────────────────────────

    @Test
    fun `recall reproduces the answer on a page whose rows are split in two`() {
        // The case that caught this: on a link aggregator the remembered shape
        // is the headline block, and recalling it without the same widening
        // returned thirty headlines with every score stripped off — a worse
        // answer than not remembering at all.
        val nodes = tree("hn-front")
        val fresh = ScreenStructure.items(nodes)
        val learned = ScreenStructure.lastTemplate
        val recalled = ScreenStructure.items(nodes, knownTemplate = learned)
        assertEquals(fresh.size, recalled.size)
        assertEquals(fresh.map { it.signature }, recalled.map { it.signature })

        val withScore = recalled.count { r ->
            ScreenStructure.fields(r).any { it.name == "points" }
        }
        assertTrue("recalled rows must still carry their score, got $withScore",
            withScore >= recalled.size - 3)
    }

    @Test
    fun `a remembered template gives the same records as working it out`() {
        val nodes = tree("native-clock")
        val fresh = ScreenStructure.items(nodes)
        val learned = ScreenStructure.lastTemplate
        assertTrue("a template must have been learned", learned.isNotBlank())

        val recalled = ScreenStructure.items(nodes, knownTemplate = learned)
        assertEquals(fresh.size, recalled.size)
        assertEquals(fresh.map { it.signature }, recalled.map { it.signature })
    }

    @Test
    fun `the same page read twice gives an identical answer`() {
        val nodes = tree("amazon-search")
        val first = ScreenStructure.items(nodes)
        val template = ScreenStructure.lastTemplate
        val second = ScreenStructure.items(nodes, knownTemplate = template)
        assertEquals(first.map { it.signature }, second.map { it.signature })
    }

    @Test
    fun `a template that no longer fits is ignored, not forced`() {
        // A remembered answer is a shortcut, never an override of what is
        // actually on screen. Handing the clock a template learned from a
        // different screen must not produce clock-shaped nonsense.
        ScreenStructure.items(tree("native-settings"))
        val settingsTemplate = ScreenStructure.lastTemplate

        val clock = tree("native-clock")
        val expected = ScreenStructure.items(clock)
        val withStale = ScreenStructure.items(clock, knownTemplate = settingsTemplate)
        assertEquals(expected.map { it.signature }, withStale.map { it.signature })
    }

    @Test
    fun `a nonsense template falls through to reading the screen`() {
        val nodes = tree("native-clock")
        val expected = ScreenStructure.items(nodes)
        val got = ScreenStructure.items(nodes, knownTemplate = "NoSuchShape{Nope}")
        assertEquals(expected.size, got.size)
    }
}
