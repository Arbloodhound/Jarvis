package com.agent.jarvis.listen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VoiceMatchTest {
    /** A unit vector in the first two dimensions at [deg] degrees: cosine between two is cos(Δ). */
    private fun at(deg: Double, dim: Int = 4): FloatArray {
        val r = Math.toRadians(deg)
        return FloatArray(dim).also { it[0] = cos(r).toFloat(); it[1] = sin(r).toFloat() }
    }

    private fun voice(id: Long, deg: Double, owner: Boolean = false, name: String? = null, num: Int = id.toInt() + 1,
                      lastSeen: Long = id, n: Int = 5) =
        Voice(id, num, name, owner, at(deg), n, lastSeen)

    @Test fun cosineAndNormalize() {
        assertEquals(1f, VoiceMatch.cosine(at(0.0), at(0.0)), 1e-6f)
        assertEquals(0f, VoiceMatch.cosine(at(0.0), at(90.0)), 1e-6f)
        assertEquals(0.5f, VoiceMatch.cosine(at(0.0), at(60.0)), 1e-6f)
        assertEquals(0f, VoiceMatch.cosine(at(0.0, 4), at(0.0, 8)), 0f)     // another model's voiceprint
        assertEquals(0f, VoiceMatch.cosine(FloatArray(4), at(0.0)), 0f)      // zero vector
        val n = VoiceMatch.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), n, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), VoiceMatch.normalize(floatArrayOf(0f, 0f)), 0f)
    }

    @Test fun matchesTheMostSimilarVoiceAtOrAboveTheThreshold() {
        val voices = listOf(voice(1, 0.0), voice(2, 50.0))
        val d = VoiceMatch.decide(voices, at(40.0), speechMs = 3_000)
        assertTrue(d is VoiceMatch.Decision.Match)
        d as VoiceMatch.Decision.Match
        assertEquals(2L, d.voice.id)
        assertEquals(cos(Math.toRadians(10.0)).toFloat(), d.similarity, 1e-5f)
    }

    @Test fun thresholdIsInclusiveAndBelowItIsANewVoiceOnlyWithEnoughSpeech() {
        val voices = listOf(voice(1, 0.0))
        // cos 60° = 0.5 = MATCH exactly
        assertTrue(VoiceMatch.decide(voices, at(59.9), 3_000) is VoiceMatch.Decision.Match)
        assertSame(VoiceMatch.Decision.New, VoiceMatch.decide(voices, at(61.0), VoiceMatch.NEW_MIN_SPEECH_MS))
        assertSame(VoiceMatch.Decision.Unknown, VoiceMatch.decide(voices, at(61.0), VoiceMatch.NEW_MIN_SPEECH_MS - 1))
        assertSame(VoiceMatch.Decision.New, VoiceMatch.decide(emptyList(), at(0.0), 5_000))
    }

    @Test fun tooLittleSpeechOrNoEmbeddingIsUnknown() {
        val voices = listOf(voice(1, 0.0))
        assertSame(VoiceMatch.Decision.Unknown, VoiceMatch.decide(voices, null, 10_000))
        assertSame(VoiceMatch.Decision.Unknown, VoiceMatch.decide(voices, at(0.0), VoiceMatch.MIN_SPEECH_MS - 1))
    }

    @Test fun bestCanSkipTheLinesOwnVoice() {
        val voices = listOf(voice(1, 0.0), voice(2, 90.0))
        assertEquals(1L, VoiceMatch.best(voices, at(10.0))!!.first.id)
        assertEquals(2L, VoiceMatch.best(voices, at(10.0), except = 1L)!!.first.id)
        assertNull(VoiceMatch.best(emptyList(), at(0.0)))
    }

    @Test fun ownerMovesOnlyOnAConfidentMatch() {
        val owner = voice(1, 0.0, owner = true)
        val other = voice(2, 0.0)
        assertTrue(!VoiceMatch.shouldUpdate(owner, 0.55f))
        assertTrue(VoiceMatch.shouldUpdate(owner, VoiceMatch.OWNER_UPDATE))
        assertTrue(VoiceMatch.shouldUpdate(other, VoiceMatch.MATCH))
        assertTrue(!VoiceMatch.shouldUpdate(other, VoiceMatch.MATCH - 0.01f))
    }

    @Test fun centroidUpdateIsAWeightedUnitMeanCappedInMemory() {
        // a fresh voice (n = 0) becomes the embedding itself
        val (c0, n0) = VoiceMatch.update(at(0.0), 0, floatArrayOf(0f, 2f, 0f, 0f))
        assertArrayEquals(at(90.0), c0, 1e-6f)
        assertEquals(1, n0)
        // one line at 90° into a centroid of 1 line at 0°: halfway, 45°
        val (c1, n1) = VoiceMatch.update(at(0.0), 1, at(90.0))
        assertArrayEquals(at(45.0), c1, 1e-6f)
        assertEquals(2, n1)
        // at the cap, a new line weighs 1/CAP
        val (_, nCap) = VoiceMatch.update(at(0.0), VoiceMatch.CENTROID_CAP, at(90.0))
        assertEquals(VoiceMatch.CENTROID_CAP, nCap)
        // a different-size embedding (model changed) restarts the voice
        val (c2, n2) = VoiceMatch.update(at(0.0, 4), 30, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        assertEquals(8, c2.size); assertEquals(1, n2)
    }

    @Test fun mergeWeighsByLineCount() {
        val m = VoiceMatch.merge(at(0.0), 1, at(90.0), 1)
        assertArrayEquals(at(45.0), m, 1e-6f)
        val heavy = VoiceMatch.merge(at(0.0), 30, at(90.0), 1)
        assertTrue(VoiceMatch.cosine(heavy, at(0.0)) > 0.99f)
        assertArrayEquals(at(0.0, 4), VoiceMatch.merge(at(0.0, 4), 1, at(0.0, 8), 1), 0f)
    }

    @Test fun evictionForgetsTheLongestUnheardUnnamedVoiceOnlyWhenFull() {
        val voices = (1L..5L).map { voice(it, it * 10.0, lastSeen = 100 - it) } +
            voice(6, 0.0, owner = true, lastSeen = 0) + voice(7, 0.0, name = "Sam", lastSeen = 1)
        assertNull(VoiceMatch.evict(voices, max = 8))
        assertEquals(5L, VoiceMatch.evict(voices, max = 7)!!.id)   // lastSeen 95: oldest of the unnamed
        val keepers = listOf(voice(1, 0.0, owner = true), voice(2, 0.0, name = "Ann"))
        assertNull(VoiceMatch.evict(keepers, max = 2))
    }

    @Test fun overlapIsTheFirstLaterWindowThatIsClearlySomeoneElse() {
        val w = listOf(0.2f to 0.9f, 0.8f to 0.1f, 0.25f to 0.6f, 0.1f to 0.9f)
        assertEquals(2, VoiceMatch.overlapWindow(w))                         // window 0 never counts
        assertEquals(-1, VoiceMatch.overlapWindow(listOf(0.8f to 0.1f, 0.35f to 0.9f, 0.1f to 0.45f)))
        assertEquals(-1, VoiceMatch.overlapWindow(emptyList()))
    }

    @Test fun labelsFollowOwnerNameNumber() {
        assertEquals("Me", voice(1, 0.0, owner = true, name = "Dafarus").label)
        assertEquals("Sam", voice(2, 0.0, name = "Sam").label)
        assertEquals("Speaker 4", voice(3, 0.0, num = 4).label)
    }
}
