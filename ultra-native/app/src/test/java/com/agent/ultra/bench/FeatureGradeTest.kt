package com.agent.ultra.bench

import com.agent.ultra.bench.FeatureGrade.Check
import com.agent.ultra.bench.FeatureGrade.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGradeTest {

    // ── report lines ──

    @Test fun lineFormat() = assertEquals(
        "PASS    Thinker quality: 5/6 correct  — answers simple checkable questions right",
        FeatureGrade.line(FeatureGrade.Result("Thinker quality", Verdict.PASS, "5/6 correct", "answers simple checkable questions right")),
    )

    @Test fun lineWithNoNumbers() = assertEquals(
        "MISSING Transcript cues: -  — not merged yet",
        FeatureGrade.line(FeatureGrade.Result("Transcript cues", Verdict.MISSING, "", "not merged yet")),
    )

    @Test fun summaryCountsAndRows() {
        val s = FeatureGrade.summary(listOf(
            FeatureGrade.Result("A", Verdict.PASS, "1/1", ""),
            FeatureGrade.Result("Longer name", Verdict.FAIL, "0/2", ""),
            FeatureGrade.Result("C", Verdict.MISSING, "", ""),
        ))
        assertEquals("PASS 1 · WARN 0 · FAIL 1 · MISSING 1", s[0])
        assertEquals("RESULT  FEATURE      MEASURED", s[1])
        assertEquals("PASS    A            1/1", s[2])
        assertEquals("FAIL    Longer name  0/2", s[3])
        assertEquals(5, s.size)
    }

    @Test fun summaryOfNothing() = assertEquals("PASS 0 · WARN 0 · FAIL 0 · MISSING 0", FeatureGrade.summary(emptyList())[0])

    @Test fun byScoreThresholds() {
        assertEquals(Verdict.PASS, FeatureGrade.byScore(5, 6))
        assertEquals(Verdict.WARN, FeatureGrade.byScore(3, 6))
        assertEquals(Verdict.FAIL, FeatureGrade.byScore(2, 6))
        assertEquals(Verdict.FAIL, FeatureGrade.byScore(0, 0))
    }

    @Test fun worstVerdict() {
        assertEquals(Verdict.FAIL, FeatureGrade.worst(Verdict.PASS, Verdict.FAIL, Verdict.WARN))
        assertEquals(Verdict.WARN, FeatureGrade.worst(Verdict.PASS, Verdict.MISSING, Verdict.WARN))
        assertEquals(Verdict.PASS, FeatureGrade.worst(Verdict.PASS, Verdict.MISSING))
        assertEquals(Verdict.MISSING, FeatureGrade.worst(Verdict.MISSING, Verdict.MISSING))
    }

    // ── graders ──

    @Test fun numberWholeOnly() {
        assertTrue(FeatureGrade.passes("391", Check.Number("391")))
        assertTrue(FeatureGrade.passes("17 × 23 = 391.", Check.Number("391")))
        assertFalse(FeatureGrade.passes("3910", Check.Number("391")))
        assertFalse(FeatureGrade.passes("1391", Check.Number("391")))
        assertFalse(FeatureGrade.passes("391.5", Check.Number("391")))
        assertTrue(FeatureGrade.passes("It arrives at 4:30 pm.", Check.Number("4:30")))
        assertFalse(FeatureGrade.passes("14:30", Check.Number("4:30")))
    }

    @Test fun numberIgnoresThinkBlock() =
        assertFalse(FeatureGrade.passes("<think>maybe 391</think>\nI am not sure", Check.Number("391")))

    @Test fun unclosedThinkBlockIsNotTheAnswer() =
        assertFalse(FeatureGrade.passes("<think>17x23 is 391, let me double check", Check.Number("391")))

    @Test fun exactWord() {
        assertTrue(FeatureGrade.passes("banana", Check.Exact("banana")))
        assertTrue(FeatureGrade.passes("  Banana.\n", Check.Exact("banana")))
        assertFalse(FeatureGrade.passes("Banana!  Here you go", Check.Exact("banana")))
        assertFalse(FeatureGrade.passes("bananas", Check.Exact("banana")))
    }

    @Test fun wordWithForbidden() {
        val c = Check.Word("Ann", not = listOf("Tom", "Sam"))
        assertTrue(FeatureGrade.passes("Ann is the shortest.", c))
        assertFalse(FeatureGrade.passes("Ann, then Sam", c))
        assertFalse(FeatureGrade.passes("Anne", c))
        assertTrue(FeatureGrade.passes("canberra", Check.Word("Canberra")))
    }

    // ── WER ──

    @Test fun werPerfect() = assertEquals(0.0, FeatureGrade.wordErrorRate("Hello, world.", "hello world"), 1e-9)
    @Test fun werOneSubstitution() = assertEquals(0.25, FeatureGrade.wordErrorRate("buy milk and bread", "buy milk and red"), 1e-9)
    @Test fun werDeletionAndInsertion() {
        assertEquals(0.5, FeatureGrade.wordErrorRate("buy fresh milk now", "buy milk"), 1e-9)
        assertEquals(0.5, FeatureGrade.wordErrorRate("buy milk", "buy the milk"), 1e-9)
    }
    @Test fun werEmpty() {
        assertEquals(1.0, FeatureGrade.wordErrorRate("buy milk", ""), 1e-9)
        assertEquals(0.0, FeatureGrade.wordErrorRate("", ""), 1e-9)
    }
    @Test fun wordsSplitHyphensAndDropApostrophes() =
        assertEquals(listOf("its", "a", "well", "known", "fact"), FeatureGrade.words("It's a well-known fact!"))

    // ── resample ──

    @Test fun resampleLength() = assertEquals(16_000, FeatureGrade.resample(FloatArray(22_050), 22_050, 16_000).size)
    @Test fun resampleKeepsLevel() {
        val out = FeatureGrade.resample(FloatArray(2205) { 0.5f }, 22_050, 16_000)
        assertTrue(out.all { kotlin.math.abs(it - 0.5f) < 1e-6 })
    }
    @Test fun resampleInterpolates() {
        val out = FeatureGrade.resample(floatArrayOf(0f, 1f, 2f, 3f), 4, 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 1e-6f); assertEquals(2f, out[1], 1e-6f)
    }
    @Test fun resampleSameRateCopies() = assertEquals(3, FeatureGrade.resample(floatArrayOf(1f, 2f, 3f), 16_000, 16_000).size)

    // ── banned words, ranking ──

    @Test fun bannedFound() = assertEquals(listOf("diagnos", "narcissist"),
        FeatureGrade.banned("This could be a Diagnosis of a narcissistic trait"))
    @Test fun bannedYouAreLying() = assertEquals(listOf("you are lying"), FeatureGrade.banned("He said: You are lying!"))
    @Test fun bannedNone() = assertTrue(FeatureGrade.banned("Sam hedged about Friday; may suggest uncertainty").isEmpty())

    @Test fun pauseTagsParsed() {
        assertEquals(listOf(1.6, 0.8), FeatureGrade.pausesIn("[Me] so [pause 1.6s] maybe Friday [pause 0.8s] okay"))
        assertTrue(FeatureGrade.pausesIn("no tags [fast] here").isEmpty())
    }

    @Test fun wordTimesIncrease() {
        assertTrue(FeatureGrade.timesIncrease(listOf(0L to 200L, 200L to 450L, 900L to 1200L)))
        assertFalse(FeatureGrade.timesIncrease(listOf(0L to 200L, 100L to 50L)))
        assertFalse(FeatureGrade.timesIncrease(listOf(500L to 600L, 100L to 200L)))
        assertFalse(FeatureGrade.timesIncrease(emptyList()))
    }

    @Test fun rank() {
        val hits = listOf("weather rainy", "The Honda will be fixed", "honda again")
        assertEquals(2, FeatureGrade.rankOf(hits, "honda"))
        assertNull(FeatureGrade.rankOf(hits, "dentist"))
    }
}
