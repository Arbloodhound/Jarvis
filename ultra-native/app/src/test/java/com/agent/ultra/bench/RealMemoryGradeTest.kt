package com.agent.ultra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RealMemoryGradeTest {
    @Test fun deniesMemory() {
        for (a in listOf("I don't have memory of past conversations.", "I'm unable to remember previous conversations",
                "As an AI, I have no memory of you.", "I can't store personal notes.", "I don’t have access to your past chats or personal notes"))
            assertTrue(a, FeatureGrade.deniesMemory(a))
        for (a in listOf("From what I heard this morning, Priya moved to Leeds.", "I have nothing on that in my memory.",
                "Your notes say: renew passport before Lisbon."))
            assertFalse(a, FeatureGrade.deniesMemory(a))
    }

    @Test fun mentionsIsWholeWordsAndIgnoresCase() =
        assertEquals(listOf("leeds"), FeatureGrade.mentions("Priya moved to LEEDS last month.", listOf("leeds", "lisbon")))

    /** 2026-10-07 14:05 UTC. */
    private val at = 1_791_381_900_000L
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun saysTheTime() {
        for (a in listOf("It's 2:05 pm.", "It is 14:07.", "The time is 2:00 PM", "about 2 pm", "2.05"))
            assertTrue(a, FeatureGrade.saysTime(a, at, utc))
        for (a in listOf("It's 9:30 am.", "I can't tell the time.", "It is 4:05 pm"))
            assertFalse(a, FeatureGrade.saysTime(a, at, utc))
    }

    @Test fun recallTargetFromARealLine() {
        val t = FeatureCases.recallTarget("[Sam] [pause 1.2s] The plumber is coming Thursday to replace the boiler valve")
        assertNotNull(t); t!!
        assertEquals("plumber", t.ask)                      // not "thursday": a day would be read as a time window
        assertTrue("thursday" in t.expect && "boiler" in t.expect)
        assertFalse(t.text.contains("["))
        assertNull(FeatureCases.recallTarget("yeah okay sure"))
    }
}
