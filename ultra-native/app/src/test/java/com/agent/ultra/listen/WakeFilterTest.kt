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
}
