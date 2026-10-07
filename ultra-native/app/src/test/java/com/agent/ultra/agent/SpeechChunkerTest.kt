package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechChunkerTest {
    private fun feed(c: SpeechChunker, text: String, step: Int = 3): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) { out += c.push(text.substring(i, minOf(text.length, i + step))); i += step }
        return out
    }

    @Test fun firstSentenceGoesAtOnce() {
        val c = SpeechChunker()
        assertEquals(listOf("Sure."), c.push("Sure. "))
    }

    @Test fun laterSentencesWaitForLength() {
        val c = SpeechChunker(minChars = 40)
        val out = feed(c, "Sure. It is 3 pm. The meeting with Sam is at four, in the small room. Bring the notes. ")
        assertEquals(listOf("Sure.", "It is 3 pm. The meeting with Sam is at four, in the small room."), out)
        assertEquals("Bring the notes.", c.flush())
        assertNull(c.flush())
    }

    @Test fun decimalsAndUnfinishedEndsDontCut() {
        val c = SpeechChunker()
        assertEquals(emptyList<String>(), c.push("Version 3.5"))
        assertEquals("Version 3.5", c.flush())
    }

    @Test fun lineBreaksEndAPiece() {
        val c = SpeechChunker(minChars = 5)
        val out = feed(c, "Here you go\n- milk\n- eggs and bread\n")
        assertEquals(listOf("Here you go", "- milk", "- eggs and bread"), out)
    }

    @Test fun nothingLostOverAWholeAnswer() {
        val text = "Okay! I opened Maps. Then I searched for coffee near you: three places are open. The closest is 200 m away. Want directions?"
        val c = SpeechChunker()
        val parts = feed(c, text, step = 2) + listOfNotNull(c.flush())
        assertEquals(text.replace(Regex("\\s+"), " "), parts.joinToString(" "))
    }
}
