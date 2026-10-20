package com.agent.jarvis.agent

import com.agent.jarvis.agent.IntentRouter.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {
    private fun tier(s: String) = IntentRouter.route(s).tier

    @Test fun deviceCommands() {
        for (s in listOf("turn on the flashlight", "set an alarm for 7", "open spotify", "set a timer for 10 minutes",
                         "volume up", "toggle bluetooth", "is bluetooth on?", "what's the battery at"))
            assertEquals(s, Tier.DEVICE, tier(s))
    }
    @Test fun compoundDeviceTaskGetsMoreRoom() = assertEquals(Tier.NORMAL, tier("open signal and send mom I'm running late"))
    @Test fun social() {
        for (s in listOf("hey", "thanks!", "good morning", "ok cool")) assertEquals(s, Tier.LIGHT, tier(s))
    }
    @Test fun recall() = assertEquals(Tier.LIGHT, tier("what did she say about the lease earlier today"))
    @Test fun reasoning() {
        for (s in listOf("why does a MoE only activate 3B parameters", "explain how the wake gate saves battery",
                         "compare IQ2_M and IQ2_S for my phone"))
            assertEquals(s, Tier.DEEP, tier(s))
    }
    @Test fun riskDomainAlwaysDeep() {
        assertEquals(Tier.DEEP, tier("sertraline and tramadol interaction"))
        assertEquals(Tier.DEEP, tier("quick: max dose of ibuprofen"))   // brevity never cuts verification here
    }
    @Test fun overrides() {
        assertEquals(Tier.DEEP, tier("think hard: should I refinance"))
        assertEquals(Tier.LIGHT, tier("quickly, what's a good name for a cat"))
    }
    @Test fun defaultIsNormal() = assertEquals(Tier.NORMAL, tier("what's a good dinner with chickpeas and spinach"))

    /** An ambient command carries overheard transcript; only the request decides the tier. */
    @Test fun ambientCommandRoutesOnRequestOnly() {
        val transcript = "they said the reaction was bad and the dose was 50 mg ".repeat(10)
        val wrapped = "[Ambient transcript, last 15 min — context only, may be noisy]\n$transcript\n\n" +
            "${IntentRouter.REQUEST_MARKER}turn on the flashlight"
        assertEquals(Tier.DEVICE, tier(wrapped))
        assertEquals("turn on the flashlight", IntentRouter.requestPart(wrapped))
        assertEquals("plain text", IntentRouter.requestPart("plain text"))
    }

    /** A question that merely mentions a device word is not a phone command. */
    @Test fun deviceWordInARealQuestionIsNotADeviceCommand() {
        assertEquals(Tier.DEEP, tier("What is the time complexity of quicksort and why?"))
        assertEquals(Tier.DEEP, tier("Is it true bluetooth causes cancer, and why?"))
        for (s in listOf("what is the time complexity of quicksort", "is it true bluetooth causes cancer"))
            assertTrue(s, tier(s) != Tier.DEVICE)
        for (s in listOf("what's the time", "is wifi connected", "what's the battery level", "how much storage left"))
            assertEquals(s, Tier.DEVICE, tier(s))
    }
}
