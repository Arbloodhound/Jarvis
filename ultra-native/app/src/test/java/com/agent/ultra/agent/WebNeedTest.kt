package com.agent.ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebNeedTest {
    @Test fun askedInWords() {
        assertEquals("the best pizza in Leeds", WebNeed.query("search the web for the best pizza in Leeds"))
        assertEquals("who invented the zip", WebNeed.query("google who invented the zip"))
        assertEquals("Pixel 10 Pro reviews", WebNeed.query("look up Pixel 10 Pro reviews"))
    }

    @Test fun currentThings() {
        for (q in listOf("what's the weather tomorrow", "latest news about the election", "who won the match last night",
                "price of bitcoin", "is Tesco open now"))
            assertEquals(q, q, WebNeed.query(q))
    }

    @Test fun notForMemoryOrPlainKnowledge() {
        for (q in listOf("what did Sam say yesterday", "search my notes for Lisbon", "what is 17 times 23",
                "explain photosynthesis", "turn on the flashlight"))
            assertNull(q, WebNeed.query(q))
    }
}
