package com.agent.ultra.bench

import com.agent.ultra.agent.LocalPrompt
import com.agent.ultra.listen.LibrarianPrompt
import com.agent.ultra.listen.NoteDraft
import com.agent.ultra.listen.NoteKind
import com.agent.ultra.listen.NoteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's pure feature checks must pass on this code; a failure here is a real regression. */
class FeatureCasesTest {

    private fun assertAllOk(cases: List<FeatureCases.Case>) {
        val wrong = cases.filter { !it.ok }
        assertTrue(wrong.joinToString("\n") { it.line() }, wrong.isEmpty())
    }

    @Test fun historyOnlyQ10() = assertAllOk(FeatureCases.historyCases())
    @Test fun historyHasElevenQuestions() = assertEquals(11, FeatureCases.historyCases().size)

    @Test fun gateCasesPass() = assertAllOk(FeatureCases.gateCases())
    @Test fun gateCasesWithSettingOff() {
        val cases = FeatureCases.gateCases(skipWhenAsked = false)
        assertAllOk(cases)
        // With "Don't ask when I asked for it" off, the asked-for message asks too.
        assertEquals("ASK", cases.first { it.name == "message to a named contact" }.expected)
    }

    @Test fun navCasesPass() = assertAllOk(FeatureCases.navCases())
    @Test fun briefDecisionsPass() = assertAllOk(FeatureCases.briefDecisions())

    @Test fun mirroredPackingPromptHasNoEarlierAnswer() {
        val history = listOf(
            LocalPrompt.Turn(true, FeatureCases.REGRESSION_MATH),
            LocalPrompt.Turn(false, "17 × 23 = 391"),
        )
        val p = FeatureCases.mirroredPrompt(history, FeatureCases.REGRESSION_PACKING)
        assertFalse("391" in p)
        assertTrue(FeatureCases.REGRESSION_PACKING in p)
    }

    @Test fun mirroredSummaryPromptHasNoPackingList() {
        val history = listOf(
            LocalPrompt.Turn(true, FeatureCases.REGRESSION_PACKING),
            LocalPrompt.Turn(false, "Packing list: toothbrush, charger, socks"),
        )
        val p = FeatureCases.mirroredPrompt(history, FeatureCases.REGRESSION_SUMMARY)
        assertFalse("toothbrush" in p)
        assertTrue("beekeeping" in p)
    }

    @Test fun mirroredFollowUpKeepsHistory() {
        val history = listOf(LocalPrompt.Turn(true, "explain what a mortgage is"), LocalPrompt.Turn(false, "A mortgage is a loan."))
        assertTrue("A mortgage is a loan." in FeatureCases.mirroredPrompt(history, "summarize everything we talked about"))
    }

    @Test fun leakAndPasteGrading() {
        assertTrue(FeatureCases.packingLeak("Socks, charger, 17x23 (391)"))
        assertFalse(FeatureCases.packingLeak("Socks, charger, toothbrush"))
        assertTrue(FeatureCases.summaryReadPaste("The club inspected twelve hives and harvested honey to sell in June."))
        assertFalse(FeatureCases.summaryReadPaste("Packing list: toothbrush, charger."))
        assertFalse(FeatureCases.summaryReadPaste("Honey and a toothbrush"))
    }

    @Test fun thinkerQuestionsGradeTheirOwnAnswers() {
        val good = listOf("391", "250", "Ann", "Canberra", "banana", "4:30 pm")
        FeatureCases.THINKER_QUESTIONS.zip(good).forEach { (q, a) -> assertTrue(q.ask, FeatureGrade.passes(a, q.check)) }
        val bad = listOf("381", "25", "Sam", "Sydney", "Banana, sure!", "4:20")
        FeatureCases.THINKER_QUESTIONS.zip(bad).forEach { (q, a) -> assertFalse(q.ask, FeatureGrade.passes(a, q.check)) }
    }

    @Test fun notesFoundFromParsedOutput() {
        val chunk = FeatureCases.notesTranscript(1_700_000_000_000)
        val raw = """
            {"kind":"person","text":"Priya is the speaker's sister-in-law and moved to Leeds"}
            {"kind":"plan","text":"Dentist appointment with Dr Lee","when":"tomorrow at 3pm"}
            {"kind":"todo","text":"Renew the passport before the Lisbon trip"}
        """.trimIndent()
        val drafts = NoteParser.parse(raw, chunk).notes
        assertAllOk(FeatureCases.notesFound(drafts))
    }

    @Test fun planWithoutTimeDoesNotCount() {
        val found = FeatureCases.notesFound(listOf(NoteDraft(NoteKind.PLAN, "Dentist appointment", null)))
        assertEquals(listOf(false, false, false), found.map { it.ok })
    }

    @Test fun recallExpectationsAreUniqueInTheSandbox() {
        // Each expected fragment must pick out exactly one synthetic line, or top-1 means nothing.
        for (r in FeatureCases.RECALL) {
            assertEquals(r.expected, 1, FeatureCases.HEARD.count { it.text.contains(r.expected, ignoreCase = true) })
        }
    }

    @Test fun heardTsUsesDayAndTime() {
        val zone = java.time.ZoneId.of("Europe/London")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val yday8pm = java.time.ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(yday8pm, FeatureCases.heardTs(FeatureCases.Heard("x", 1, 20), now, zone))
    }

    @Test fun heardTsNeverInTheFuture() {
        val zone = java.time.ZoneId.of("Europe/London")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(now - 600_000, FeatureCases.heardTs(FeatureCases.Heard("x", 0, 8), now, zone))
    }

    @Test fun heardTsTodayStaysTodayJustAfterMidnight() {
        val zone = java.time.ZoneId.of("Europe/London")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 0, 5, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = java.time.ZonedDateTime.of(2026, 10, 6, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(midnight, FeatureCases.heardTs(FeatureCases.Heard("x", 0, 8), now, zone))
    }

    @Test fun trainTimeAcceptsTwentyFourHour() {
        val q = FeatureCases.THINKER_QUESTIONS.last()
        assertTrue(FeatureGrade.passes("16:30", q.check))
        assertTrue(FeatureGrade.passes("04:30 pm", q.check))
        assertFalse(FeatureGrade.passes("14:30", q.check))
    }

    @Test fun transcriptsAreTimeOrdered() {
        val t = FeatureCases.patternDialogue(0)
        assertEquals(t.sortedBy { it.ts }, t)
        assertEquals(6, t.size)
    }

    /** The Pixel's 1/3 (2026-10-06): the time only in the text, the to-do filed as a fact. The
     *  parser's repairs make the same output count, as it is then stored with its time and kind. */
    @Test fun notesFoundWhenTheModelSlipsAsOnThePixel() {
        val chunk = FeatureCases.notesTranscript(1_700_000_000_000)
        val raw = """
            {"kind":"person","text":"Priya is the speaker's sister-in-law and moved to Leeds"}
            {"kind":"plan","text":"Dentist appointment with Dr Lee tomorrow at 3pm"}
            {"kind":"fact","text":"You need to renew your passport before the Lisbon trip"}
        """.trimIndent()
        val drafts = NoteParser.parse(raw, chunk).notes
        assertAllOk(FeatureCases.notesFound(drafts))
        assertEquals("tomorrow at 3pm", drafts[1].whenPhrase)
    }

    /** The librarian prompt's examples must not be the probe's own test case, or the probe grades
     *  a copied example: no name, place or key word of the test conversations in the prompt. */
    @Test fun librarianPromptDoesNotContainTheProbeCases() {
        for (prompt in listOf(LibrarianPrompt.SYSTEM, LibrarianPrompt.SYSTEM_PLAIN)) {
            for (w in listOf("Priya", "Leeds", "Dentist", "Dr Lee", "passport", "Lisbon", "tomorrow at 3pm",
                    "Sam", "maybe Friday", "dinner", "book the table")) {
                assertFalse(w, prompt.contains(w, ignoreCase = true))
            }
        }
    }
}
