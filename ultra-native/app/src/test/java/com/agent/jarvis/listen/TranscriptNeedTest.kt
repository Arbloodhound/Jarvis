package com.agent.jarvis.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptNeedTest {
    @Test fun plainCommandsGoWithoutTheTranscript() {
        for (c in listOf("turn on the flashlight", "what's the weather tomorrow", "set an alarm for 7am",
                "open YouTube", "take a screenshot", "what time is it"))
            assertFalse(c, TranscriptNeed.needed(c))
    }

    @Test fun commandsAboutWhatWasSaidGetIt() {
        for (c in listOf("what did he just say", "summarise that conversation", "reply to her",
                "who was I talking to", "make a note of this"))
            assertTrue(c, TranscriptNeed.needed(c))
    }
}
