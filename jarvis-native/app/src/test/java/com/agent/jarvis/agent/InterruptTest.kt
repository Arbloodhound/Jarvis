package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptTest {
    @Test fun stopWords() {
        for (t in listOf("stop", "Stop.", "Jarvis, stop!", "hey Jarvis stop it", "okay never mind", "Cancel that.",
                "shut up", "be quiet please", "stop stop stop", "Wait.", "forget it", "that's enough", "Stop now."))
            assertTrue(t, Interrupt.isStop(t))
    }

    @Test fun notStops() {
        for (t in listOf("stop the music", "stop watching me", "cancel my alarm", "don't forget the milk",
                "wait for the bus", "what is a stop codon", "never mind the weather, what time is it"))
            assertFalse(t, Interrupt.isStop(t))
    }

    @Test fun stopAlwaysWinsEvenWithNothingRunning() {
        assertEquals(Interrupt.Decision.Stop, Interrupt.decide("stop", null))
        assertEquals(Interrupt.Decision.Stop, Interrupt.decide("stop", "open YouTube"))
    }

    @Test fun nothingRunningIsAPlainRequest() {
        assertEquals(Interrupt.Decision.Run("open YouTube"), Interrupt.decide("open YouTube", null))
        assertEquals(Interrupt.Decision.Run("also open YouTube"), Interrupt.decide("also open YouTube", ""))
    }

    @Test fun addOnsRunAfter() {
        assertEquals(Interrupt.Decision.After("turn on the flashlight"), Interrupt.decide("also turn on the flashlight", "open maps"))
        assertEquals(Interrupt.Decision.After("text Sam I'm late"), Interrupt.decide("and then text Sam I'm late", "open maps"))
        assertEquals(Interrupt.Decision.After("set a timer for 5 minutes"), Interrupt.decide("set a timer for 5 minutes too", "open maps"))
        assertEquals(Interrupt.Decision.After("play some jazz"), Interrupt.decide("After that, play some jazz", "open maps"))
    }

    @Test fun aStandAloneRequestReplacesTheRun() {
        assertEquals(Interrupt.Decision.Replace("open YouTube"), Interrupt.decide("no, open YouTube instead", "open Spotify"))
        assertEquals(Interrupt.Decision.Replace("what's the weather tomorrow"), Interrupt.decide("actually what's the weather tomorrow", "tell me a story"))
    }

    @Test fun aChangeKeepsTheEarlierRequestInFront() {
        assertEquals(Interrupt.Decision.Replace("set an alarm for 6am — change: make it 7am"),
            Interrupt.decide("no, make it 7am", "set an alarm for 6am"))
        assertEquals(Interrupt.Decision.Replace("play music — change: louder"), Interrupt.decide("louder", "play music"))
        assertEquals(Interrupt.Decision.Replace("search the web for pizza — change: only places open now"),
            Interrupt.decide("only places open now", "search the web for pizza"))
    }

    @Test fun overheardContextIsKept() {
        val m = IntentRouter.REQUEST_MARKER
        val d = Interrupt.decide("[Ambient transcript]\nSam: hi\n\n${m}make it 7am", "set an alarm for 6am")
        assertEquals(Interrupt.Decision.Replace("[Ambient transcript]\nSam: hi\n\n${m}set an alarm for 6am — change: make it 7am"), d)
        // "stop" in the overheard part is not a stop: only the words to Jarvis count.
        assertEquals(Interrupt.Decision.Run("Sam: stop it\n${m}what time is it"), Interrupt.decide("Sam: stop it\n${m}what time is it", null))
    }
}
