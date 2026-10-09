package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuickReplyTest {
    // Wednesday 7 October 2026, 14:05.
    private val now = ZonedDateTime.of(2026, 10, 7, 14, 5, 0, 0, ZoneId.of("Europe/London"))
    private fun r(s: String) = QuickReply.reply(s, now)

    @Test fun greetings() {
        for (s in listOf("hi", "hey", "hello", "hiya", "Hi!", "HELLO.", "hi jarvis", "Hey Jarvis, hello", "jarvis hi"))
            assertEquals(s, "Hi. What can I do for you?", r(s))
        assertEquals("Good morning. What can I do for you?", r("good morning"))
        assertEquals("Good afternoon. What can I do for you?", r("Good afternoon, Jarvis."))
        assertEquals("Good evening. What can I do for you?", r("good evening"))
    }

    @Test fun thanks() {
        for (s in listOf("thanks", "thank you", "Thank you very much!", "thanks a lot", "thx", "cheers", "thanks jarvis"))
            assertEquals(s, "You're welcome.", r(s))
    }

    @Test fun goodbyes() {
        assertEquals("Bye.", r("bye"))
        assertEquals("Bye.", r("Goodbye!"))
        assertEquals("Good night.", r("good night"))
    }

    @Test fun timeIsTwelveHourLikeNowLine() {
        for (s in listOf("what time is it", "What's the time?", "what is the time", "what’s the time", "jarvis what time is it"))
            assertEquals(s, "It's 2:05 pm.", r(s))
        assertEquals("It's 9:30 am.", QuickReply.reply("what time is it", now.withHour(9).withMinute(30)))
        assertEquals("It's 12:00 am.", QuickReply.reply("what time is it", now.withHour(0).withMinute(0)))
    }

    @Test fun date() {
        for (s in listOf("what's the date", "what is the date", "what day is it", "what's today's date", "What's today’s date?"))
            assertEquals(s, "It's Wednesday 7 October 2026.", r(s))
    }

    @Test fun anAmbientRequestIsMatchedOnItsRequestPart() {
        assertEquals("You're welcome.", r("we were talking about dinner\n${IntentRouter.REQUEST_MARKER}thanks"))
        assertNull(r("thanks for dinner\n${IntentRouter.REQUEST_MARKER}what's the weather"))
    }

    @Test fun confirmationWordsAreNotAnswered() {
        for (s in listOf("ok", "okay", "yes", "no", "sure", "cool", "nice", "OK.", "Yes!"))
            assertNull(s, r(s))
    }

    @Test fun anythingWithMoreContentIsNotAnswered() {
        for (s in listOf("hi, what's the weather", "thanks, now turn on wifi", "what time is it in Tokyo",
            "hello there how are you", "good morning, set an alarm for 7", "what's the date of easter",
            "say thanks to Sam", "", "   ", "jarvis", "hey jarvis", "?!"))
            assertNull(s, r(s))
    }
}
