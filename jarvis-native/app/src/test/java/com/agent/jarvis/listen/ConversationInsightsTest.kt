package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ConversationInsightsTest {
    private val utc = ZoneId.of("UTC")
    private fun l(sec: Long, text: String) = ConversationInsights.Line(1_700_000_000_000L + sec * 1000, text)

    private val talk = listOf(
        l(0, "[Me] um so like I think we should go to the beach tomorrow, you know?"),
        l(3, "[Sam] [overlap] Yeah that sounds great, I love the beach"),
        l(6, "[Me] like um maybe we could leave early, like really early"),
        l(9, "[Sam] [overlap] Sure, thanks for planning it"),
        l(12, "[Me] um what time works for you?"),
        l(14, "[Sam] [overlap] [laughs] Nine is good"),
        l(16, "[Me] (to Jarvis) set an alarm for 8"),
        l(20, "[Me] I hate traffic though, it's so annoying and bad"),
    )

    @Test fun countsPerSpeaker() {
        val r = ConversationInsights.analyse(talk, utc)
        val me = r.speakers.first { it.name == "Me" }
        val sam = r.speakers.first { it.name == "Sam" }
        assertEquals(4, me.lines)
        assertEquals(3, sam.lines)
        assertEquals(3, sam.overlapped)
        assertEquals(1, sam.laughs)
        assertTrue(me.fillers >= 6)
        assertEquals(1, r.toJarvis)                  // the request to Jarvis isn't conversation
        assertTrue(me.share(r.words) > sam.share(r.words))
        assertTrue(me.questions >= 2)
    }

    @Test fun patternsAreSentences() {
        val p = ConversationInsights.analyse(talk, utc).patterns
        assertTrue(p.any { it.startsWith("You did most of the talking") })
        assertTrue(p.any { it.startsWith("Sam got talked over 3 times") })
        assertTrue(p.any { it.contains("answered") })
        assertTrue(p.any { it.contains("filler words") && it.contains("\"like\"") })
        assertTrue(p.any { it.startsWith("Busiest hour") })
        assertTrue(p.any { it == "You asked Jarvis 1 thing." })
    }

    @Test fun nothingHeard() {
        val r = ConversationInsights.analyse(emptyList(), utc)
        assertEquals(listOf("Nothing heard in this period yet."), r.patterns)
        assertEquals("I haven't heard any conversation today yet.", ConversationInsights.answer(r, ConversationInsights.Period.TODAY))
    }

    @Test fun speakerLabels() {
        assertEquals("Sam", ConversationInsights.speakerOf("[Sam] hello"))
        assertEquals("Speaker 2", ConversationInsights.speakerOf("[Speaker 2] [fast] hi"))
        assertNull(ConversationInsights.speakerOf("plain line"))
        assertNull(ConversationInsights.speakerOf("[pause 1.2s] hmm"))
    }

    @Test fun askedInWords() {
        assertEquals(ConversationInsights.Period.TODAY, ConversationInsights.asked("how did I talk today"))
        assertEquals(ConversationInsights.Period.WEEK, ConversationInsights.asked("who talked the most this week"))
        assertEquals(ConversationInsights.Period.MONTH, ConversationInsights.asked("show my conversation insights for the month"))
        assertNull(ConversationInsights.asked("turn on the flashlight"))
        assertNull(ConversationInsights.asked("what did Sam say"))
    }

    @Test fun noteTextHasThePeriodAndShares() {
        val t = ConversationInsights.noteText(ConversationInsights.analyse(talk, utc), "today")
        assertTrue(t.startsWith("Conversation insights (today):"))
        assertTrue(t.contains("Me ") && t.contains("Sam "))
    }

    @Test fun literatureFindingsNameTheirSource() {
        val lines = (0 until 14).flatMap { k -> listOf(
            l(k * 10L, "[Me] I always think I never get it right, I know I mess everything up because I am so tired, right?"),
            l(k * 10L + 4, "[Sam] [overlap] [fast] we should go, let's get our stuff, we can do it together"),
        ) }
        val p = ConversationInsights.analyse(lines, utc).patterns
        assertTrue(p.any { it.contains("Pennebaker") && it.startsWith("You used \"I/me/my\"") })
        assertTrue(p.any { it.contains("Al-Mosaiwi") })
        assertTrue(p.any { it.contains("Lakoff") })
        assertTrue(p.any { it.startsWith("Sam said \"we/us/our\"") })
        assertTrue(p.any { it.contains("high-involvement") && it.contains("Tannen") })
    }
}
