package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutTest {
    private fun l(t: String, left: Int, top: Int, right: Int = left + 200, bottom: Int = top + 40) =
        OcrLayout.Line(t, left, top, right, bottom)

    @Test fun readingOrderWithSideBySideRowsJoined() {
        val lines = listOf(
            l("£249.99", 800, 1005),
            l("Sony WH-1000XM5", 100, 1000),
            l("Headphones", 100, 400),
            l("In stock", 100, 1100),
        )
        assertEquals("Headphones\nSony WH-1000XM5 · £249.99\nIn stock", OcrLayout.format(lines, 1080, 2400))
    }

    @Test fun dropsTheTakeoverOverlayAndTheStatusBar() {
        val lines = listOf(
            l("12:41", 40, 10, bottom = 60),          // status bar clock
            l("Jarvis is working", 600, 120),
            l("Stop", 900, 120),
            l("Results", 100, 300),
        )
        assertEquals("Results", OcrLayout.format(lines, 1080, 2400))
    }

    @Test fun capsTheLength() {
        val lines = (0 until 200).map { l("line number $it with some words", 10, 200 + it * 50) }
        val out = OcrLayout.format(lines, 1080, 20000, max = 300)
        assertTrue(out.length <= 302)
        assertTrue(out.endsWith("…"))
    }

    @Test fun sparseTreeText() {
        assertTrue(OcrLayout.sparse("Screen has no readable text"))
        assertTrue(OcrLayout.sparse("Current app: com.game\nPlay\nMenu"))
        assertFalse(OcrLayout.sparse("Sony WH-1000XM5 Wireless Noise Cancelling Headphones\n£249.99\nAdd to basket\nFree delivery tomorrow"))
    }

    @Test fun offlineNavAddsPictureTextBelowTheList() {
        val s = OfflineNav.Screen("1. \"Play\"", emptyList(), 0)
        assertEquals(s, OfflineNav.withPicture(s, "  "))
        val w = OfflineNav.withPicture(s, "Level 3\nScore 1200")
        assertTrue(w.text.startsWith("1. \"Play\"\nSeen in a picture of the screen"))
        assertTrue(w.text.endsWith("Level 3\nScore 1200"))
        val long = OfflineNav.withPicture(s, "x".repeat(5000))
        assertTrue(long.text.length < s.text.length + OfflineNav.PICTURE_CHARS + 100)
    }
}
