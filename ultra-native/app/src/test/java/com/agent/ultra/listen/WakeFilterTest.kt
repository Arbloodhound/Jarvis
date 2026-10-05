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
}
