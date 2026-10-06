package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecallScoreTest {
    private val day = 86_400_000L
    private val now = 1_791_387_000_000L                  // Wed 2026-10-07 15:30 UTC

    @Test fun termsDropQuestionWordsAndStem() {
        assertEquals(listOf("sam", "dentist", "appointment"),
            RecallScore.terms("What did Sam say about the dentists appointment?"))
        assertEquals(listOf("meeting", "room", "5"), RecallScore.terms("Hey Jarvis, the meetings in room 5"))
        assertEquals(emptyList<String>(), RecallScore.terms("what did I hear?"))
    }

    @Test fun stemIsConservative() {
        assertEquals("call", RecallScore.stem("called"))
        assertEquals("box", RecallScore.stem("boxes"))
        assertEquals("note", RecallScore.stem("notes"))
        assertEquals("class", RecallScore.stem("class"))
        assertEquals("need", RecallScore.stem("need"))        // "ne" would be too short
        assertEquals("part", RecallScore.stem("parties"))
    }

    @Test fun ftsQueryUsesPrefixesForWordsOnly() {
        assertEquals("dentist* OR sam OR 5", RecallScore.ftsQuery(listOf("dentist", "sam", "5")))
    }

    @Test fun keywordFractionMatchesPrefixesAndWholeNumbers() {
        val t = listOf("dentist", "thursday", "5")
        assertEquals(2f / 3, RecallScore.keywordFraction(t, "Call the dentist's office on Thursday"), 1e-6f)
        assertEquals(1f / 3, RecallScore.keywordFraction(t, "room 5 not 50"), 1e-6f)
        assertEquals(0f, RecallScore.keywordFraction(emptyList(), "anything"), 0f)
        // Short names are whole words: "Sam" is not "same" or "sample".
        assertEquals(0f, RecallScore.keywordFraction(listOf("sam"), "it's the same sample"), 0f)
        assertEquals(1f, RecallScore.keywordFraction(listOf("sam"), "Sam's here"), 0f)
    }

    @Test fun recencyHalvesEachHalfLife() {
        assertEquals(1f, RecallScore.recency(now, now), 1e-6f)
        assertEquals(0.5f, RecallScore.recency(now - 7 * day, now), 1e-4f)
        assertEquals(1f, RecallScore.recency(now + 1000, now), 1e-6f)   // clock skew: not > 1
    }

    @Test fun meaningBeatsAStaleKeywordAndUnrelatedIsDropped() {
        // "the dentist moved my appointment" vs a query about the dentist: strong cosine, no words.
        val semantic = RecallScore.score(cosine = 0.62f, keyword = 0f, recency = 0.9f, inWindow = null, hasTerms = true)!!
        val wordOnly = RecallScore.score(cosine = 0.10f, keyword = 0.5f, recency = 0.9f, inWindow = null, hasTerms = true)!!
        assertTrue(semantic > wordOnly)
        assertNull(RecallScore.score(cosine = 0.2f, keyword = 0f, recency = 1f, inWindow = null, hasTerms = true))
    }

    @Test fun withoutTheEmbedderKeywordAndRecencyStillRank() {
        val recent = RecallScore.score(null, keyword = 1f, recency = 1f, inWindow = null, hasTerms = true)!!
        val old = RecallScore.score(null, keyword = 1f, recency = 0.1f, inWindow = null, hasTerms = true)!!
        val partial = RecallScore.score(null, keyword = 0.5f, recency = 1f, inWindow = null, hasTerms = true)!!
        assertTrue(recent > old); assertTrue(recent > partial)
        assertNull(RecallScore.score(null, keyword = 0f, recency = 1f, inWindow = null, hasTerms = true))
        assertTrue(recent <= 1f)
    }

    @Test fun windowIsAStrongHintForTopicsAndAWallForTimeOnlyQuestions() {
        val inside = RecallScore.score(0.5f, 0.5f, 0.5f, inWindow = true, hasTerms = true)!!
        val outside = RecallScore.score(0.5f, 0.5f, 0.5f, inWindow = false, hasTerms = true)!!
        assertEquals(inside / 2, outside, 1e-6f)
        assertNotNull(RecallScore.score(null, 0f, 0.3f, inWindow = true, hasTerms = false))
        assertNull(RecallScore.score(null, 0f, 0.3f, inWindow = false, hasTerms = false))
    }

    @Test fun mergeSortsAndDropsRepeats() {
        val hits = listOf(
            RecallHit(now - 1000, "Call the dentist.", 0.4f, "heard"),
            RecallHit(now, "call the  DENTIST", 0.7f, "notes"),
            RecallHit(now, "Buy milk", 0.9f, "heard"),
            RecallHit(now, "Pick up Sam", 0.1f, "heard"),
        )
        val m = RecallScore.merge(hits, 2)
        assertEquals(listOf("Buy milk", "call the  DENTIST"), m.map { it.text })
    }

    @Test fun briefIsDatedOldestFirstAndWithinBudget() {
        val utc = TimeZone.getTimeZone("UTC")
        val hits = listOf(
            RecallHit(now - day, "Dentist moved to Thursday at 3.", 0.9f, "heard"),
            RecallHit(now - 2 * day, "Sam said he'd bring the ladder.", 0.6f, "notes"),
        )
        val b = RecallScore.brief(hits, 800, utc)
        assertEquals(
            "What I remember (with times; overheard speech may be misheard):\n" +
                "- Mon Oct 5, 3:30 PM (notes): Sam said he'd bring the ladder.\n" +
                "- Tue Oct 6, 3:30 PM (heard): Dentist moved to Thursday at 3.",
            b,
        )
        val tight = RecallScore.brief(hits, 120, utc)
        assertTrue(tight.length <= 120)
        assertTrue("best hit kept", "Dentist" in tight)
        assertTrue("worse hit dropped", "ladder" !in tight)
        assertEquals("", RecallScore.brief(emptyList()))
        val long = RecallScore.brief(listOf(RecallHit(now, "word ".repeat(200), 1f, "heard")), 200, utc)
        assertTrue(long.length <= 200); assertTrue(long.endsWith("…"))
    }

    @Test fun otherSourcesHitsScoreOnTheSameScale() {
        val terms = RecallScore.terms("the dentist appointment")
        // The source judged it a match even with no shared word: floor, not dropped.
        val fuzzy = RecallScore.sourceScore(terms, "Tooth doctor on Thursday", now, now, null)!!
        val exact = RecallScore.sourceScore(terms, "dentist appointment moved", now, now, null)!!
        assertTrue(exact > fuzzy); assertTrue(exact <= 1f)
        val w = TimeWindow(now - day, now, "yesterday")
        val outside = RecallScore.sourceScore(terms, "dentist appointment moved", now - 3 * day, now, w)!!
        val inside = RecallScore.sourceScore(terms, "dentist appointment moved", now - day / 2, now, w)!!
        assertTrue(inside > outside)
        // Time-only question: notes outside the window are not returned.
        assertNull(RecallScore.sourceScore(emptyList(), "anything", now - 3 * day, now, w))
    }
}
