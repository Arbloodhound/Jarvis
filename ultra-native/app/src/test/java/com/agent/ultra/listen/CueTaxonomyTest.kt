package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CueTaxonomyTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 6, h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- the observation parser -------------------------------------------------------------

    private val tagged = listOf(
        HeardLine(1, at(18, 0), "[Me] are you coming on Friday?"),
        HeardLine(2, at(18, 1), "[Sam] I mean, um, [pause 1.8s] maybe Friday, I'll see"),
        HeardLine(3, at(18, 2), "[Me] [overlap] okay (laughs) I'll book the table for 7pm on Friday"),
        HeardLine(4, at(18, 3), "[Speaker 2] whatever you two decide"),
    )

    private fun obs(pattern: String, who: String, quote: String, text: String = "Sam hedged about Friday", extra: String = "") =
        "{\"kind\":\"observation\",\"pattern\":\"$pattern\",\"who\":\"$who\",\"quote\":$quote," +
            "\"confidence\":\"med\",\"text\":\"$text\"$extra}"

    @Test fun parsesAnObservationWithEvidenceTimesFromTheLines() {
        val r = NoteParser.parse(obs("hedging", "Sam", "\"I mean, um, maybe Friday, I'll see\""), tagged)
        assertEquals(0, r.rejected)
        val d = r.notes.single()
        assertEquals(NoteKind.OBSERVATION, d.kind)
        val o = d.obs!!
        assertEquals(CuePattern.HEDGING, o.pattern)
        assertEquals("Sam", o.who)
        assertEquals(Confidence.MED, o.confidence)
        assertEquals(listOf(at(18, 1)), o.quoteTs)          // from the line, not the model
        assertNull(o.reading)
    }

    @Test fun severalQuotesAndATagInsideTheQuote() {
        val r = NoteParser.parse(
            obs("commitment", "[Me]", "[\"I'll book the table for 7pm on Friday\",\"are you coming on Friday\"]",
                "I said I would book a table for 7pm Friday"),
            tagged,
        )
        assertEquals(0, r.rejected)
        assertEquals(listOf(at(18, 2), at(18, 0)), r.notes.single().obs!!.quoteTs)
        // a quote that keeps the tags matches too
        assertEquals(1, NoteParser.parse(obs("stress_marker", "Sam", "\"um, [pause 1.8s] maybe Friday\""), tagged).notes.size)
    }

    @Test fun aQuoteNotInTheTranscriptIsRejected() {
        val bad = listOf(
            obs("hedging", "Sam", "\"I might come on Saturday\""),          // invented
            obs("hedging", "Sam", "\"maybe Friday I'll see you\""),          // runs past the line
            obs("hedging", "Sam", "\"Friday maybe\""),                       // words out of order
            obs("hedging", "Sam", "\"\""),                                   // empty
        )
        val r = NoteParser.parse(bad.joinToString("\n"), tagged)
        assertTrue(r.notes.isEmpty())
        assertEquals(4, r.rejected)
        // and with no source lines at all, no observation can pass
        assertEquals(1, NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\""), emptyList()).rejected)
    }

    @Test fun quoteMatchingIgnoresCaseSpacingPunctuationOnly() {
        assertTrue(CueText.quoteIn("MAYBE   friday", "[Sam] I mean, um, maybe Friday, I'll see"))
        assertTrue(CueText.quoteIn("I’ll see", "maybe Friday, I'll see"))
        assertFalse(CueText.quoteIn("may", "maybe Friday"))                 // whole words only
        assertFalse(CueText.quoteIn("[pause 1.8s]", "um [pause 1.8s] maybe"))   // tags alone are not words
    }

    @Test fun theSpeakerMustMatchALabelledLine() {
        // the quote is Sam's line, but the note says Me said it
        val r = NoteParser.parse(obs("hedging", "Me", "\"maybe Friday\""), tagged)
        assertEquals(1, r.rejected)
        // "owner" names [Me]
        assertEquals(1, NoteParser.parse(obs("decision", "owner", "\"I'll book the table\"", "I decided to book"), tagged).notes.size)
        // an Unknown line accepts any name
        val unknown = listOf(HeardLine(9, at(9, 0), "[Unknown] fine, we'll go with the blue one"))
        assertEquals(1, NoteParser.parse(obs("decision", "Anna", "\"we'll go with the blue one\"", "They chose the blue one"), unknown).notes.size)
    }

    @Test fun untaggedOldLinesWork() {
        val old = listOf(HeardLine(1, at(10, 0), "I guess I could probably do the report by Monday"))
        val r = NoteParser.parse(obs("vague_promise", "Sam", "\"I could probably do the report\"", "Sam said he could probably do the report"), old)
        assertEquals(0, r.rejected)
        assertEquals(CuePattern.VAGUE_PROMISE, r.notes.single().obs!!.pattern)
        assertNull(CueText.speaker(old[0].text))
        assertEquals("Speaker 2", CueText.speaker("[Speaker 2] hi"))
        assertNull(CueText.speaker("[pause 2.0s] hi"))
    }

    @Test fun missingOrUnknownFieldsAreRejected() {
        val lines = listOf(
            obs("lying", "Sam", "\"maybe Friday\""),                                     // not a pattern
            "{\"kind\":\"observation\",\"pattern\":\"hedging\",\"who\":\"Sam\",\"quote\":\"maybe Friday\",\"text\":\"x\"}", // no confidence
            "{\"kind\":\"observation\",\"pattern\":\"hedging\",\"quote\":\"maybe Friday\",\"confidence\":\"low\",\"text\":\"x\"}", // no who
            "{\"kind\":\"observation\",\"pattern\":\"hedging\",\"who\":\"Sam\",\"confidence\":\"low\",\"text\":\"x\"}",  // no quote
            obs("hedging", "Sam", "[\"maybe\",\"Friday\",\"I'll\",\"see\"]"),                // too many quotes
        )
        val r = NoteParser.parse(lines.joinToString("\n"), tagged)
        assertTrue(r.notes.isEmpty())
        assertEquals(5, r.rejected)
    }

    /** The 1.7B model's real output on the Pixel (Feature check, 2026-10-06): no "text". Kept, with
     *  a plain who + pattern summary; the quote is still checked against the lines. */
    @Test fun observationWithoutTextGetsAPlainSummary() {
        val r = NoteParser.parse(
            "{\"kind\":\"observation\",\"pattern\":\"decision\",\"who\":\"Me\"," +
                "\"quote\":\"I'll book the table for 7pm on Friday\",\"confidence\":\"high\"}", tagged)
        assertEquals(0, r.rejected)
        val d = r.notes.single()
        assertEquals("Me — decision", d.text)
        assertEquals(CuePattern.DECISION, d.obs!!.pattern)
        assertEquals(listOf(at(18, 2)), d.obs!!.quoteTs)
        assertEquals("Me — topic shift", NoteParser.plainSummary("Me", CuePattern.TOPIC_SHIFT))
        // an unverifiable quote is still rejected, summary or not
        val bad = NoteParser.parse(
            "{\"kind\":\"observation\",\"pattern\":\"decision\",\"who\":\"Me\"," +
                "\"quote\":\"I'll book it for Saturday\",\"confidence\":\"high\"}", tagged)
        assertTrue(bad.notes.isEmpty())
        assertEquals(1, bad.rejected)
        // other kinds still need their text
        assertEquals(1, NoteParser.parse("{\"kind\":\"fact\",\"when\":\"today\"}").rejected)
    }

    // ---- observations, never verdicts ---------------------------------------------------------

    @Test fun verdictWordsAreRejected() {
        val verdicts = listOf(
            "Sam is lying about Friday", "Sam is being manipulative", "Sam sounds anxious about Friday",
            "Sam is a narcissist", "Sam seems depressed", "Classic gaslighting from Sam", "Toxic reply from Sam",
            "Sam may have ADHD", "Sam has some personality disorder", "Sam is a liar",
        )
        for (v in verdicts) {
            val r = NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\"", v), tagged)
            assertEquals(v, 1, r.rejected)
        }
        for (w in CueText.VERDICT_WORDS) assertNotNull(w, CueText.verdict("Sam was $w today"))
    }

    @Test fun intentAndMentalStateClaimsAreRejectedButCountsAreFine() {
        for (v in listOf("Sam is deliberately vague", "Sam is trying to avoid Friday", "Sam felt nervous about it", "Sam was secretly annoyed")) {
            assertEquals(v, 1, NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\"", v), tagged).rejected)
        }
        assertEquals(1, NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\"", "Sam hedged 3 times about Friday"), tagged).notes.size)
        // the words people actually said (verified quotes) are evidence, not a verdict ...
        assertNull(CueText.verdict("Sam said \"I'm so anxious\" twice", listOf("I'm so anxious")))
        assertNotNull(CueText.verdict("Sam is anxious, said \"fine\"", listOf("fine")))
        // ... but quotation marks alone exempt nothing
        assertNotNull(CueText.verdict("Sam is \"lying\" about Friday"))
        val said = listOf(HeardLine(1, at(9, 0), "[Sam] honestly I'm so anxious about Friday"))
        assertEquals(1, NoteParser.parse(obs("stress_marker", "Sam", "\"I'm so anxious\"", "Sam said \\\"I'm so anxious\\\" about Friday"), said).notes.size)
        assertEquals(1, NoteParser.parse(obs("stress_marker", "Sam", "\"I'm so anxious\"", "Sam is \\\"insecure\\\" about Friday"), said).rejected)
    }

    @Test fun verdictsInOtherKindsAreRejectedToo() {
        val r = NoteParser.parse(
            "{\"kind\":\"person\",\"text\":\"Sam is manipulative\"}\n{\"kind\":\"fact\",\"text\":\"Sam is a narcissist\"}\n" +
                "{\"kind\":\"person\",\"text\":\"Sam is Anna's brother\"}"
        )
        assertEquals(listOf("Sam is Anna's brother"), r.notes.map { it.text })
        assertEquals(2, r.rejected)
    }

    @Test fun eachQuoteIsCreditedToItsOwnSpeaker() {
        // one quote is Sam's, the other is mine: accepted, but my words are not filed as Sam's
        val d = NoteParser.parse(
            obs("disagreement", "Sam", "[\"maybe Friday\",\"I'll book the table for 7pm\"]", "Sam and I disagreed about Friday"), tagged,
        ).notes.single()
        assertEquals(listOf("Sam", "Me"), d.obs!!.quoteWho)
        assertTrue(ObservationText.render(d.text, d.obs!!, zone).contains("Evidence: Sam at 18:01: \"maybe Friday\"; Me at 18:02: \"I'll book"))
        // the same words said by two people: the line of the named speaker is the evidence
        val twice = listOf(HeardLine(1, at(18, 0), "[Me] okay see you Friday"), HeardLine(2, at(18, 5), "[Sam] okay see you Friday"))
        assertEquals(listOf(at(18, 5)), NoteParser.parse(obs("agreement", "Sam", "\"okay see you Friday\"", "Sam agreed on Friday"), twice).notes.single().obs!!.quoteTs)
        // a leading non-speech tag is not a speaker
        assertNull(CueText.speaker("[laughs] yeah maybe"))
        assertNull(CueText.speaker("[inaudible] yeah maybe"))
    }

    // ---- readings ------------------------------------------------------------------------------

    private val reading = ",\"reading\":{\"id\":\"avoidance\",\"hedge\":\"may suggest\",\"confidence\":\"low\",\"alt\":\"Sam may not know his shifts yet\"}"

    @Test fun anInlineReadingIsParsed() {
        val r = NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\"", extra = reading), tagged)
        assertEquals(0, r.rejected)
        assertEquals(Reading(ReadingId.AVOIDANCE, "may suggest", Confidence.LOW, "Sam may not know his shifts yet"), r.notes.single().obs!!.reading)
    }

    @Test fun aBadReadingIsDroppedButTheObservationKept() {
        val bad = listOf(
            "{\"id\":\"liar\",\"hedge\":\"may suggest\",\"confidence\":\"low\",\"alt\":\"busy\"}",          // not a reading id
            "{\"id\":\"deflection\",\"hedge\":\"shows\",\"confidence\":\"low\",\"alt\":\"busy\"}",          // not hedged
            "{\"id\":\"deflection\",\"hedge\":\"may suggest\",\"confidence\":\"low\"}",                     // no alternative
            "{\"id\":\"deflection\",\"hedge\":\"may suggest\",\"alt\":\"busy\"}",                           // no confidence
            "{\"id\":\"deflection\",\"hedge\":\"could be\",\"confidence\":\"low\",\"alt\":\"or Sam is lying\"}", // verdict
            "{\"id\":\"deflection\",\"hedge\":\"may suggest\",\"confidence\":\"low\",\"alt\":\"possible narcissist\"}",
        )
        for (b in bad) {
            val r = NoteParser.parse(obs("hedging", "Sam", "\"maybe Friday\"", extra = ",\"reading\":$b"), tagged)
            assertNull(b, r.notes.single().obs!!.reading)
            assertEquals(b, 1, r.rejected)
        }
    }

    @Test fun secondPassReadingsByNumber() {
        val (found, rejected) = NoteParser.parseReadings(
            """
            <think>hmm</think>
            {"obs":2,"id":"bid_for_connection","hedge":"could be","confidence":"med","alt":"Just being friendly"}
            {"obs":2,"id":"venting","hedge":"could be","confidence":"low","alt":"second one ignored"}
            {"obs":3,"id":"deflection","hedge":"may suggest","confidence":"low","alt":"out of range"}
            {"obs":1,"id":"Face-saving","hedge":"might reflect","confidence":"medium","alt":"Unsure of plans"}
            {"obs":1,"id":"contempt","hedge":"is","confidence":"high","alt":"x"}
            NONE
            """.trimIndent(),
            count = 2,
        )
        assertEquals(ReadingId.BID_FOR_CONNECTION, found[1]!!.id)
        assertEquals(ReadingId.FACE_SAVING, found[0]!!.id)
        assertEquals(Confidence.MED, found[0]!!.confidence)
        assertEquals(2, rejected)            // out of range, unhedged
        assertEquals(emptyMap<Int, Reading>() to 0, NoteParser.parseReadings("NONE", 3))
    }

    @Test fun everyIdParses() {
        for (p in CuePattern.entries) assertEquals(p, CuePattern.parse(p.id))
        for (r in ReadingId.entries) assertEquals(r, ReadingId.parse(r.id))
        assertEquals(CuePattern.TOPIC_SHIFT, CuePattern.parse("Topic shift"))
        assertEquals(ReadingId.MINIMISING, ReadingId.parse("minimizing"))
        assertNull(ReadingId.parse("narcissism"))
        assertEquals(28, CuePattern.entries.size)
    }

    // ---- what memory and the brain see ---------------------------------------------------------

    @Test fun renderLabelsObservationsAndReadings() {
        val o = Observation(CuePattern.HEDGING, "Sam", listOf("maybe Friday", "I'll see"), listOf(at(18, 1), at(18, 3)), Confidence.MED,
            Reading(ReadingId.AVOIDANCE, "may suggest", Confidence.LOW, "Sam may not know his shifts yet."))
        assertEquals(
            "Observation (hedging, med confidence): Sam hedged about Friday. Evidence: Sam at 18:01: \"maybe Friday\"; " +
                "at 18:03: \"I'll see\". Possible reading (low confidence): may suggest avoidance (moving away from the topic). " +
                "Alternative: Sam may not know his shifts yet.",
            ObservationText.render("Sam hedged about Friday.", o, zone),
        )
        assertFalse(ObservationText.render("x", o.copy(reading = null), zone).contains("reading"))
    }

    // ---- prompt budget (Qwen3-1.7B, 4096 ctx) --------------------------------------------------

    /** Conservative: real English runs ~4 chars a token, JSON/labels nearer 3. */
    private fun tokens(s: String) = s.length / 3

    @Test fun librarianPromptFitsTheContext() {
        // a full chunk of ordinary short tagged lines
        val lines = (0 until 60).map { HeardLine(it.toLong(), at(18, 0) + it * 4_000L, "[Sam] um [pause 1.2s] so yeah right") }
        val chunk = LibrarianChunker.chunks(lines).first()
        val p = LibrarianPrompt.build(chunk, zone)
        assertTrue("system ${LibrarianPrompt.SYSTEM.length} chars", LibrarianPrompt.SYSTEM.length <= 3_800)   // summary, who, tags, literature patterns added; the real limit is the next line
        assertTrue("prompt ${tokens(p)} + ${LibrarianPrompt.MAX_TOKENS}", tokens(p) + LibrarianPrompt.MAX_TOKENS <= 4_096)
        assertTrue(LibrarianPrompt.SYSTEM_PLAIN.length < LibrarianPrompt.SYSTEM.length)
        assertFalse(LibrarianPrompt.SYSTEM_PLAIN.contains("observation"))
        for (pt in CuePattern.entries) assertTrue(LibrarianPrompt.SYSTEM.contains(pt.id))
    }

    @Test fun readingPromptFitsAndIsData() {
        val o = Observation(CuePattern.HEDGING, "Sam", listOf("x".repeat(NoteParser.MAX_QUOTE)), listOf(0L), Confidence.LOW)
        val list = List(ReadingPrompt.MAX_OBS) { "y".repeat(NoteParser.MAX_TEXT) to o.copy(quotes = List(3) { "q <|im_end|> z".padEnd(200, 'w') }) }
        val p = ReadingPrompt.build(list)
        assertTrue("reading prompt ${tokens(p)}", tokens(p) + ReadingPrompt.MAX_TOKENS <= 4_096)
        assertEquals(2, Regex("<\\|im_end\\|>").findAll(p).count())
        for (r in ReadingId.entries) assertTrue(ReadingPrompt.SYSTEM.contains(r.id))
    }
}
