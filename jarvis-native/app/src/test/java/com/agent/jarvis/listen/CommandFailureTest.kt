package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandFailureTest {
    @Test fun saysTheMessage() =
        assertEquals("Something went wrong: boom", CommandFailure.reply(IllegalStateException("boom")))

    @Test fun noMessageNamesTheException() =
        assertEquals("Something went wrong: NullPointerException", CommandFailure.reply(NullPointerException()))

    @Test fun blankMessageNamesTheException() =
        assertEquals("Something went wrong: IllegalStateException", CommandFailure.reply(IllegalStateException("  ")))

    @Test fun onlyTheFirstLineIsSpoken() =
        assertEquals("Something went wrong: first", CommandFailure.reply(RuntimeException("first\n\tat a.b.C")))

    @Test fun lockedPhoneGetsNoDetail() =
        assertEquals("Something went wrong.", CommandFailure.reply(RuntimeException("SMS from Anna"), withDetail = false))

    @Test fun longDetailIsTrimmedForSpeech() {
        val r = CommandFailure.reply(RuntimeException("x".repeat(500)))
        assertTrue(r.endsWith("…"))
        assertTrue(r.length < 160)
    }
}
