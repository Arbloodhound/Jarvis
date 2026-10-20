package com.agent.jarvis.listen

import com.agent.jarvis.listen.NameInference.Line
import com.agent.jarvis.listen.NameInference.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameInferenceTest {
    private fun me(ts: Long, t: String) = Line(1, owner = true, named = false, ts = ts, text = t)
    private fun v(id: Long, ts: Long, t: String, named: Boolean = false) = Line(id, owner = false, named = named, ts = ts, text = t)

    @Test fun selfIntroductionNamesAVoice() =
        assertEquals(mapOf(2L to "Sam"), NameInference.infer(listOf(v(2, 0, "Hi, I'm Sam from next door.")), emptySet(), ""))

    @Test fun beingAddressedAndAnsweringAddsUp() {
        val lines = listOf(
            me(0, "Thanks, Priya."), v(3, 3_000, "No problem."),
            me(60_000, "Priya, are you coming Friday?"), v(3, 64_000, "Maybe."),
            me(120_000, "Hey Priya, look at this"), v(3, 122_000, "Oh nice."),
        )
        assertEquals(mapOf(3L to "Priya"), NameInference.infer(lines, emptySet(), ""))
        // twice is not enough yet
        assertEquals(emptyMap<Long, String>(), NameInference.infer(lines.take(4), emptySet(), ""))
    }

    @Test fun neverOverridesOrDuplicates() {
        val intro = v(2, 0, "I'm Sam.")
        assertTrue(NameInference.infer(listOf(intro.copy(named = true)), emptySet(), "").isEmpty())     // named already
        assertTrue(NameInference.infer(listOf(intro), setOf("Sam"), "").isEmpty())                       // another voice is Sam
        assertTrue(NameInference.infer(listOf(intro), emptySet(), "sam").isEmpty())                      // that's the owner
        assertTrue(NameInference.infer(listOf(v(2, 0, "I'm Sorry, I'm Fine. This is Monday.")), emptySet(), "").isEmpty())
        // a reply too late is not an answer
        assertTrue(NameInference.infer(listOf(me(0, "Thanks, Tom."), v(4, 60_000, "Sure.")) , emptySet(), "").isEmpty())
    }

    @Test fun whatTheOwnerTellsIt() {
        assertEquals(Who.SpeakerNum(2) to "Sam", NameInference.told("Speaker 2 is Sam"))
        assertEquals(Who.LastOther to "Anna", NameInference.told("the person I was just talking to is anna"))
        assertEquals(Who.LastOther to "Mum", NameInference.told("that voice was Mum."))
        assertEquals(Who.LastOther to "Tom Hardy", NameInference.told("who I was talking to is called Tom Hardy"))
        assertNull(NameInference.told("speaker 2 is loud"))   // describes, doesn't name
        assertNull(NameInference.told("speaker 2 is my brother"))
    }
}
