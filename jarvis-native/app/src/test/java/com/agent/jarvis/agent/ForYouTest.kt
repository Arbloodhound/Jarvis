package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForYouTest {
    @Test fun findsTheArrayInsideProseOrAFence() {
        assertEquals(2, ForYou.jsonArray("Here you go:\n```json\n[{\"title\":\"a\"},{\"title\":\"b\"}]\n```")!!.length())
        assertNull(ForYou.jsonArray("no ideas today"))
        assertNull(ForYou.jsonArray("[broken"))
    }
}
