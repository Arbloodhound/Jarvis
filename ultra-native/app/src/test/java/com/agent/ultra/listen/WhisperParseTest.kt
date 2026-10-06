package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native reply format written by cpp/ultra_whisper.cpp: "<p>\t<text>", NUL, timing lines. */
class WhisperParseTest {
    private fun bytesThroughUtf8(s: String) = String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

    @Test fun oldFormatWithoutTimingStillParses() {
        val r = WhisperNative.parse("0.812000\t hello there ")!!
        assertEquals("hello there", r.text)
        assertEquals(0.812f, r.confidence, 1e-6f)
        assertTrue(r.words.isEmpty())
        assertTrue(r.vad.isEmpty())
    }

    @Test fun wordsAndVadAfterTheNul() {
        val raw = "0.900000\tUm, okay so\u0000" +
            "W\t0\t320\tUm,\n" +
            "W\t1400\t1700\tokay\n" +
            "W\t1700\t1900\tso\n" +
            "V\t0\t400\n" +
            "V\t1350\t2000\n"
        val r = WhisperNative.parse(bytesThroughUtf8(raw))!!
        assertEquals("Um, okay so", r.text)
        assertEquals(listOf(TimedWord("Um,", 0, 320), TimedWord("okay", 1400, 1700), TimedWord("so", 1700, 1900)), r.words)
        assertEquals(listOf(Span(0, 400), Span(1350, 2000)), r.vad)
    }

    @Test fun nonAsciiWordsSurviveTheByteRoundTrip() {
        val raw = "0.7\tcafé naïve 東京\u0000W\t0\t100\tcafé\nW\t100\t200\tnaïve\nW\t200\t300\t東京\n"
        val r = WhisperNative.parse(bytesThroughUtf8(raw))!!
        assertEquals("café naïve 東京", r.text)
        assertEquals(listOf("café", "naïve", "東京"), r.words.map { it.text })
    }

    @Test fun malformedTimingLinesAreSkippedNotFatal() {
        val raw = "0.5\thi there\u0000" +
            "W\tx\t100\tbad\n" +          // non-numeric start
            "W\t100\t50\tbackwards\n" +   // ends before it starts
            "W\t-10\t50\tnegative\n" +    // token time not computed
            "W\t0\t100\t   \n" +          // blank word
            "W\t0\t100\n" +               // no word
            "V\t5\n" +                    // short line
            "Q\t0\t10\tunknown kind\n" +
            "\n\n" +
            "garbage\n" +
            "W\t100\t200\thi\n" +
            "V\t0\t300"                   // last line without its newline
        val r = WhisperNative.parse(raw)!!
        assertEquals("hi there", r.text)
        assertEquals(listOf(TimedWord("hi", 100, 200)), r.words)
        assertEquals(listOf(Span(0, 300)), r.vad)
    }

    @Test fun emptyOrHeadlessRepliesAreNothingSaid() {
        assertNull(WhisperNative.parse(""))
        assertNull(WhisperNative.parse("no tab here"))
        assertNull(WhisperNative.parse("0.9\t   "))
        assertNull(WhisperNative.parse("0.9\t \u0000W\t0\t1\tword\n"))
        // a tab only after the NUL is not a header
        assertNull(WhisperNative.parse("0.9\u0000W\t0\t1\tword\n"))
    }

    @Test fun badConfidenceIsZeroNotACrash() {
        assertEquals(0f, WhisperNative.parse("nan?\thello")!!.confidence, 0f)
    }

    @Test fun fillerOnlyLinesAreRecognised() {
        for (t in listOf("Um, uh.", "you know, I mean", "Hmm.", "So, like, um..."))
            assertTrue(t, WhisperNative.onlyPromptFillers(t))
        // the prompt echoed back whole, wake name excluded
        assertTrue(WhisperNative.onlyPromptFillers(WhisperNative.verbatimPrompt("Jarvis").substringBefore(" Jarvis")))
        for (t in listOf("um, okay", "Um, hey Jarvis", "I mean it", "", "...", "you know the drill"))
            assertTrue(t, !WhisperNative.onlyPromptFillers(t))
    }

    @Test fun verbatimPromptEndsWithTheWakeName() {
        val p = WhisperNative.verbatimPrompt("  Jarvis ")
        assertTrue(p.endsWith(" Jarvis."))
        assertTrue(p.startsWith("Um, uh"))
    }
}
