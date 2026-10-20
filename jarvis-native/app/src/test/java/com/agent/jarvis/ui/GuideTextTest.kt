package com.agent.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTextTest {
    private val md = """
        # Title
        Intro line one
        continues here.

        ## Voice
        - Pick a **voice**.
        > Tip: try Kokoro.

        ### Speed
        Use the slider.

        ## Memory
        Jarvis keeps what it hears.
    """.trimIndent()

    @Test fun parsesEveryBlockKind() {
        val b = GuideText.parse(md)
        assertEquals(GuideText.Block.Heading(1, "Title"), b[0])
        assertEquals(GuideText.Block.Para("Intro line one continues here."), b[1])
        assertEquals(GuideText.Block.Heading(2, "Voice"), b[2])
        assertEquals(GuideText.Block.Bullet("Pick a **voice**."), b[3])
        assertEquals(GuideText.Block.Tip("Tip: try Kokoro."), b[4])
        assertEquals(GuideText.Block.Heading(3, "Speed"), b[5])
    }

    @Test fun boldRuns() {
        assertEquals(listOf("Pick a " to false, "voice" to true, "." to false), GuideText.boldRuns("Pick a **voice**."))
        assertEquals(listOf("an ** odd one" to false), GuideText.boldRuns("an ** odd one"))
    }

    @Test fun contentsListsTheMainSections() {
        val b = GuideText.parse(md)
        assertEquals(listOf("Voice", "Memory"), GuideText.sections(b).map { it.second })
    }

    @Test fun searchKeepsWholeMatchingSections() {
        val b = GuideText.parse(md)
        val f = GuideText.filter(b, "slider")
        assertEquals(GuideText.Block.Heading(3, "Speed"), f.first())
        assertTrue(f.none { it is GuideText.Block.Heading && it.text == "Memory" })
        assertEquals(b, GuideText.filter(b, "  "))
        assertTrue(GuideText.filter(b, "nothing-like-this").isEmpty())
    }
}
