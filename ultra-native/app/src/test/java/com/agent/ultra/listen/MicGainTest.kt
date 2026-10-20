package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MicGainTest {
    private fun tone(amp: Double, n: Int = 16_000) = ShortArray(n) { (amp * 32767 * sin(2 * PI * 220 * it / 16_000.0)).toInt().toShort() }

    @Test fun farSpeechIsBroughtUp() {
        val quiet = tone(0.01)                     // ~ -43 dBFS RMS: speech from across a room
        assertEquals(MicGain.TARGET_DB, MicGain.rmsDb(MicGain.normalize(quiet)), 0.3f)       // up to ordinary speech
        val faint = tone(0.002)                    // ~ -57 dBFS: needs more than the cap
        val out = MicGain.normalize(faint)
        assertEquals(MicGain.MAX_GAIN_DB, MicGain.rmsDb(out) - MicGain.rmsDb(faint), 0.3f)   // capped at +24 dB
    }

    @Test fun loudSpeechIsLeftAlone() {
        val loud = tone(0.3)                       // ~ -13 dBFS
        assertSame(loud, MicGain.normalize(loud))
    }

    @Test fun neverClips() {
        val spiky = tone(0.02).also { it[100] = 30_000 }
        val out = MicGain.normalize(spiky)
        assertTrue(out.all { it in -32768..32767 })
        assertTrue(out[100] <= 32767)
        assertTrue(MicGain.rmsDb(out) - MicGain.rmsDb(spiky) < 1f)   // the peak leaves almost no headroom
    }

    @Test fun agcFollowsSpeechNotSilence() {
        val agc = MicGain.Agc()
        repeat(30) { agc.process(tone(0.01, 320), gate = -58f) }
        val speechGain = agc.gainDb
        assertTrue(speechGain > 10f)
        repeat(30) { agc.process(ShortArray(320), gate = -58f) }   // silence: the gain holds
        assertEquals(speechGain, agc.gainDb, 0.001f)
    }
}
