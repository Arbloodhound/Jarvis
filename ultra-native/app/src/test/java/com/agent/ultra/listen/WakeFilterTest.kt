package com.agent.ultra.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeFilterTest {
    private val names = listOf("Ultra", "jarvis")
    private fun strip(s: String) = WakeFilter.stripWakePhrase(s, names)

    @Test fun wakePhraseAloneLeavesNothing() {
        for (s in listOf("Hey Jarvis.", "hey jarvis", "Jarvis!", "Ultra.", "Hey, Jervis.", "  Hey Jarvis?  "))
            assertEquals(s, "", strip(s))
    }
    @Test fun commandAfterWakePhraseIsKept() {
        assertEquals("what's the weather", strip("Hey Jarvis, what's the weather"))
        assertEquals("turn on the flashlight.", strip("Ultra: turn on the flashlight."))
    }
    @Test fun nameMustBeAWholeWord() = assertEquals("Ultraviolet light is harmful", strip("Ultraviolet light is harmful"))
    @Test fun noLeadingWakePhraseLeavesTextAlone() =
        assertEquals("set a timer for ten minutes", strip("set a timer for ten minutes"))

    private fun cmd(s: String) = WakeFilter.addressedCommand(s, "Jarvis")

    @Test fun wakeOnlyAtTheStart() {
        assertEquals("what's the weather", cmd("Hey Jarvis, what's the weather"))
        assertEquals("set a timer for ten minutes", cmd("OK Jarvis set a timer for ten minutes"))
        assertEquals("turn on the flashlight", cmd("Jarvis, turn on the flashlight"))
        assertEquals("", cmd("Hey Jarvis."))           // bare: the next utterance is the command
    }
    @Test fun nameInsideASentenceIsJustConversation() {
        for (s in listOf("I tried Jarvis yesterday and it broke", "so then hey Jarvis did nothing",
                         "Jarvisville is a town", "my friend Jarvis is coming over"))
            assertEquals(s, null, cmd(s))
    }
    /** whisper now keeps fillers (WhisperNative.verbatimPrompt): they must not hide the wake phrase. */
    @Test fun leadingFillersStillWake() {
        assertEquals("what's the weather", cmd("Um, hey Jarvis, what's the weather"))
        assertEquals("what's the time", cmd("Uh... um Jarvis what's the time"))
        assertEquals("", strip("Hmm, hey Jarvis."))
        // a filler is not any word: "so" before the name is still conversation
        assertEquals(null, cmd("so Jarvis did nothing"))
        assertEquals("umbrella Jarvis", strip("umbrella Jarvis"))
    }

    /** "Hey Jarvis, um." then a pause: a bare wake, so the next utterance is the command. */
    @Test fun trailingFillersAreNotACommand() {
        assertEquals("", cmd("Hey Jarvis, um."))
        assertEquals("", strip("Jarvis, uh, um..."))
        assertEquals("set a timer", cmd("Jarvis, er, set a timer"))
        assertEquals("umbrellas are on sale", cmd("Jarvis, umbrellas are on sale"))
        assertEquals("ermine is a stoat", strip("Hey Jarvis ermine is a stoat"))
    }
}
