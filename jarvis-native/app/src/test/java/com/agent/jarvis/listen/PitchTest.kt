package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchTest {
    private fun tone(hz: Double, ms: Int, amp: Double = 8000.0) =
        ShortArray(16 * ms) { i -> (amp * sin(2 * PI * hz * i / 16_000)).toInt().toShort() }

    @Test fun findsTheFundamentalOfAVoicedTone() {
        val hz = CueAudio.pitchHz(tone(200.0, 600), Span(0, 600))!!
        assertEquals(200f, hz, 4f)
        assertEquals(120f, CueAudio.pitchHz(tone(120.0, 600), Span(0, 600))!!, 3f)
    }

    @Test fun silenceAndShortBitsReportNothing() {
        assertNull(CueAudio.pitchHz(ShortArray(16 * 600), Span(0, 600)))
        assertNull(CueAudio.pitchHz(tone(200.0, 80), Span(0, 80)))
    }

    @Test fun tagsOnlyAgainstTheSpeakersOwnUsual() {
        var b = Baseline()
        repeat(10) { b = b.add(180.0) }
        assertEquals(CueTags.HIGHER, CueTags.pitch(b, 240.0))
        assertEquals(CueTags.LOWER, CueTags.pitch(b, 140.0))
        assertNull(CueTags.pitch(b, 190.0))
        assertNull("no usual yet", CueTags.pitch(Baseline().add(180.0), 300.0))
    }
}
