package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** "Hey" as whisper heard it on the phone (Feature check, 2026-10-06), and "hi". */
    @Test fun heyAsWhisperMishearsItStillWakes() {
        assertEquals("What time is it?", cmd("I hate Jarvis. What time is it?"))
        assertEquals("what's the time", cmd("Hi Jarvis, what's the time"))
        assertEquals("", cmd("Hay, Jarvis."))
        assertEquals("", strip("I hate Jarvis."))
        // only right before the name: hating something else is conversation
        assertEquals(null, cmd("I hate Mondays, Jarvis"))
        assertEquals(null, cmd("I really hate Jarvis"))
    }

    /** "Hey, Joris, what time is it?" — the Pixel's decode of "Hey Jarvis" (2026-10-06, four times). */
    @Test fun aMisspeltNameAfterHeyStillWakes() {
        assertEquals("what time is it?", cmd("Hey, Joris, what time is it?"))
        assertEquals("", cmd("Hey Jervis."))
        assertEquals("what time is it?", strip("Hey, Joris, what time is it?"))
        // without a wake word before it, a near-name is not a wake
        assertEquals(null, cmd("Joris, what time is it?"))
        // other names stay conversation
        for (s in listOf("Hey James, what time is it?", "Hey Travis, come here", "Hey Doris!", "Hey Jack, look"))
            assertEquals(s, null, cmd(s))
    }

    @Test fun soundsLikeName() {
        for (w in listOf("Joris", "Jervis", "Javis", "Jarvus", "jarviss")) assertTrue(w, WakeFilter.soundsLikeName(w, "Jarvis"))
        for (w in listOf("James", "Jason", "Travis", "Doris", "Jo", "Jack")) assertFalse(w, WakeFilter.soundsLikeName(w, "Jarvis"))
    }
}
