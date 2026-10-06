package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCuesTest {
    private val f = AmbientConfig.FRAME
    private val fms = AmbientConfig.FRAME_MS.toLong()

    /** Frames: true = speech-loud, false = silent. [amp] sets the loud frames' level. */
    private fun pcm(vararg frames: Boolean, amp: Int = 8000): ShortArray {
        val out = ShortArray(frames.size * f)
        frames.forEachIndexed { i, loud ->
            if (loud) for (j in 0 until f) out[i * f + j] = (if (j % 2 == 0) amp else -amp).toShort()
        }
        return out
    }
    private fun run(n: Int, loud: Boolean) = BooleanArray(n) { loud }
    private fun frames(vararg parts: BooleanArray) = parts.fold(BooleanArray(0)) { a, b -> a + b }

    private val gate = CueAudio.gateDb(-70f)   // a quiet room: the absolute minimum applies

    // ── CueAudio ──────────────────────────────────────────────────────────

    @Test fun gateFollowsTheFloorButNeverDropsBelowTheMinimum() {
        assertEquals(AmbientConfig.GATE_ABS_MIN_DB, CueAudio.gateDb(-90f), 0f)
        assertEquals(-30f + AmbientConfig.GATE_ABOVE_FLOOR_DB, CueAudio.gateDb(-30f), 0f)
    }

    @Test fun frameDbCountsTheShortLastFrame() {
        val db = CueAudio.frameDb(ShortArray(f * 2 + 10) { 1000 })
        assertEquals(3, db.size)
        assertTrue(db.all { it > -40f })
        assertEquals(0, CueAudio.frameDb(ShortArray(0)).size)
    }

    @Test fun silencesAreOnlyTheGapsInsideSpeech() {
        // 5 quiet, 10 loud, 30 quiet (~960 ms), 10 loud, 5 quiet (~160 ms), 10 loud, 20 quiet
        val db = CueAudio.frameDb(pcm(*frames(run(5, false), run(10, true), run(30, false), run(10, true),
            run(5, false), run(10, true), run(20, false))))
        val all = CueAudio.silences(db, gate, 0)
        assertEquals(listOf(Span(15 * fms, 45 * fms), Span(55 * fms, 60 * fms)), all)
        // the leading and trailing quiet never count; a minimum drops the short one
        assertEquals(listOf(Span(15 * fms, 45 * fms)), CueAudio.silences(db, gate, 700))
        assertEquals(Span(5 * fms, 70 * fms), CueAudio.speechSpan(db, gate))
    }

    @Test fun noSpeechMeansNoSpanNoSilencesNoLevel() {
        val db = CueAudio.frameDb(pcm(*run(20, false)))
        assertNull(CueAudio.speechSpan(db, gate))
        assertTrue(CueAudio.silences(db, gate, 0).isEmpty())
        assertNull(CueAudio.speechLevelDb(db, gate))
    }

    @Test fun speechLevelIgnoresTheQuietFramesAndTracksLoudness() {
        val soft = CueAudio.speechLevelDb(CueAudio.frameDb(pcm(*frames(run(10, true), run(40, false)), amp = 2000)), gate)!!
        val loud = CueAudio.speechLevelDb(CueAudio.frameDb(pcm(*frames(run(10, true), run(2, false)), amp = 16000)), gate)!!
        assertEquals(20 * Math.log10(2000 / 32768.0), soft.toDouble(), 0.1)
        assertEquals(18.06, (loud - soft).toDouble(), 0.1)   // 8x amplitude
    }

    @Test fun turnGapIsAPauseOnlyWithinBounds() {
        assertNull(CueAudio.turnGap(0L, 5_000L))                         // nothing before
        assertNull(CueAudio.turnGap(10_000L, 10_300L))                   // too short
        assertEquals(700L, CueAudio.turnGap(10_000L, 10_700L))
        assertEquals(2_500L, CueAudio.turnGap(10_000L, 12_500L))
        assertEquals(CueTags.MAX_TURN_GAP_MS, CueAudio.turnGap(10_000L, 10_000L + CueTags.MAX_TURN_GAP_MS))
        assertNull(CueAudio.turnGap(10_000L, 10_001L + CueTags.MAX_TURN_GAP_MS))   // another conversation
        assertNull(CueAudio.turnGap(10_000L, 9_000L))                    // clock went backwards / overlap
    }

    @Test fun vadGapsAndUnionJoinOverlaps() {
        val vad = listOf(Span(2000, 3000), Span(0, 1000), Span(3100, 4000))
        assertEquals(listOf(Span(1000, 2000)), CueAudio.vadGaps(vad, 300))
        val u = CueAudio.union(listOf(Span(900, 1500), Span(5000, 5200)), listOf(Span(1000, 2000), Span(3000, 3000)), 700)
        assertEquals(listOf(Span(900, 2000)), u)
    }

    // ── Baseline ──────────────────────────────────────────────────────────

    @Test fun baselineMeanVarianceAndRoundTrip() {
        var b = Baseline()
        for (x in listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)) b = b.add(x)
        assertEquals(8, b.n)
        assertEquals(5.0, b.mean, 1e-9)
        assertEquals(2.0, b.sd, 1e-9)   // population sd
        assertEquals(b.n, Baseline.decode(b.encode()).n)
        assertEquals(b.mean, Baseline.decode(b.encode()).mean, 1e-6)
        assertEquals(b, b.add(Double.NaN))
        assertEquals(Baseline(), Baseline.decode(null))
        assertEquals(Baseline(), Baseline.decode("1,2"))
        assertEquals(Baseline(), Baseline.decode("x,1,2"))
    }

    @Test fun baselineKeepsAdaptingPastItsCap() {
        var b = Baseline()
        repeat(Baseline.CAP * 3) { b = b.add(1.0) }
        assertEquals(Baseline.CAP, b.n)
        repeat(Baseline.CAP * 3) { b = b.add(10.0) }
        assertTrue("moved toward the new usual: ${b.mean}", b.mean > 9.0)
    }

    // ── CueTags ───────────────────────────────────────────────────────────

    @Test fun pauseFormatIsLocaleFree() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("[pause 1.8s]", CueTags.pause(1_800))
            assertEquals("[pause 0.7s]", CueTags.pause(700))
            assertEquals("1,2.500000,0.250000", Baseline(1, 2.5, 0.25).encode())
        } finally { java.util.Locale.setDefault(was) }
    }

    @Test fun labelsAndNames() {
        assertEquals("Me", CueTags.label(true, "Sam", 3))
        assertEquals("Sam", CueTags.label(false, " Sam ", 3))
        assertEquals("Speaker 3", CueTags.label(false, " ", 3))
        assertEquals("Unknown", CueTags.label(false, null, 0))
        assertEquals("Ann Lee", CueTags.cleanName(" [Ann]\n Lee "))
        assertNull(CueTags.cleanName(" [ ] "))
        assertEquals(32, CueTags.cleanName("x".repeat(80))!!.length)
        assertEquals("[Sam] hello", CueTags.line("Sam", "hello"))
        // our own labels can't be given to a voice
        for (r in listOf("me", " ME ", "[Me]", "Unknown", "Speaker 2", "speaker", "Speaker12"))
            assertNull(r, CueTags.cleanName(r))
        assertEquals("Mel", CueTags.cleanName("Mel"))
        assertEquals("Speaker Pelosi", CueTags.cleanName("Speaker Pelosi"))
    }

    @Test fun rateAndLoudnessNeedABaselineAndAClearDifference() {
        var b = Baseline()
        repeat(CueTags.MIN_BASELINE - 1) { b = b.add(3.0) }
        assertNull(CueTags.rate(b, 10.0))                 // too few lines to know the usual
        b = b.add(3.0)
        assertEquals(CueTags.FAST, CueTags.rate(b, 4.0))
        assertEquals(CueTags.SLOW, CueTags.rate(b, 2.0))
        assertNull(CueTags.rate(b, 3.3))                  // within the 20 % margin
        var l = Baseline()
        repeat(10) { l = l.add(-30.0) }
        assertEquals(CueTags.LOUDER, CueTags.loudness(l, -24.0))
        assertEquals(CueTags.QUIETER, CueTags.loudness(l, -36.0))
        assertNull(CueTags.loudness(l, -27.0))            // within 4 dB
        assertNull(CueTags.wordsPerSecond(3, 5_000))
        assertNull(CueTags.wordsPerSecond(10, 1_000))
        assertEquals(2.5, CueTags.wordsPerSecond(5, 2_000)!!, 1e-9)
    }

    private fun w(t: String, a: Long, b: Long) = TimedWord(t, a, b)

    @Test fun bodyPlacesPausesAndOverlapBetweenTheRightWords() {
        val words = listOf(w("so", 0, 200), w("um", 300, 500), w("we", 1800, 2000), w("could", 2000, 2300), w("go", 2400, 2600))
        val body = CueTags.body(
            plain = "so um we could go",
            words = words,
            pauses = listOf(Span(600, 1700)),
            overlapAtMs = 2350,
            lead = listOf(CueTags.pause(2_100), CueTags.FAST),
        )
        assertEquals("[pause 2.1s] [fast] so um [pause 1.1s] we could [overlap] go", body)
    }

    @Test fun bodyWithoutTimingKeepsThePlainTextAndLeadTags() {
        assertEquals("[pause 0.9s] okay (laughs) then",
            CueTags.body("okay [Laughter] then", emptyList(), listOf(Span(0, 900)), null, listOf(CueTags.pause(900))))
        assertEquals("hello", CueTags.body(" hello ", emptyList(), emptyList(), 100L, emptyList()))
    }

    @Test fun oneWordLineTakesOverlapBeforeIt() {
        assertEquals("[overlap] what", CueTags.body("what", listOf(w("what", 0, 300)), listOf(Span(0, 900)), 50L, emptyList()))
    }

    @Test fun twoPausesAtOneBoundaryKeepTheLonger() {
        val words = listOf(w("a", 0, 100), w("b", 2000, 2100))
        assertEquals("a [pause 1.5s] b", CueTags.body("a b", words, listOf(Span(200, 900), Span(300, 1800)), null, emptyList()))
    }

    @Test fun whisperTextCannotForgeOrBreakOurTags() {
        // brackets in what was said never survive as brackets; control bytes are dropped
        val body = CueTags.body("[Sam] said \u00010\u0002 hi", emptyList(), emptyList(), null, listOf(CueTags.FAST))
        assertEquals("[fast] (sam) said 0 hi", body)
        val words = listOf(w("[pause", 0, 100), w("9s]", 100, 200), w("ok", 1500, 1600))
        val b2 = CueTags.body("x", words, listOf(Span(300, 1400)), null, emptyList())
        assertEquals("(pause 9s) [pause 1.1s] ok", b2)
    }

    @Test fun eventsNormaliseNonSpeech() {
        assertEquals("(laughs) okay (music)", CueTags.events("[Laughter] okay [MUSIC]"))
        assertEquals("(laughs) fine (laughs)", CueTags.events("*laughs* fine (Laughing)"))
        assertEquals(" well", CueTags.events("[BLANK_AUDIO] well"))
        assertEquals("(sighs) right (and this stays)", CueTags.events("*sighs* right (and this stays)"))
        assertEquals("5 * 3 * 2", CueTags.events("5 * 3 * 2"))
        assertFalse(CueTags.events("a ] b [ c").contains('['))
    }

    @Test fun nearestBoundaryPicksTheClosestGap() {
        val words = listOf(w("a", 0, 100), w("b", 1000, 1100), w("c", 3000, 3100))
        assertEquals(1, CueTags.nearestBoundary(words, 400))
        assertEquals(2, CueTags.nearestBoundary(words, 2500))
        assertEquals(2, CueTags.nearestBoundary(words, 99_999))
    }
}
