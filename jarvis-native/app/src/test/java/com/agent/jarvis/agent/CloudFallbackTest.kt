package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFallbackTest {
    @Test fun aRejectedKeyIsNotCalledUnreachable() {
        // Phone, 2026-10-17: a 401 from api.anthropic.com showed as "cloud unreachable".
        assertTrue(Brain.cloudFailure(Exception("HTTP 401: {\"error\":{\"type\":\"authentication_error\"}}")).startsWith("key rejected"))
        assertEquals("rate-limited", Brain.cloudFailure(Exception("HTTP 429: slow down")))
        assertEquals("provider error", Brain.cloudFailure(Exception("HTTP 529: overloaded")))
        assertEquals("unreachable", Brain.cloudFailure(Exception("failed to connect to api.anthropic.com after 20000ms")))
        assertEquals("unreachable", Brain.cloudFailure(Exception()))
    }

    @Test fun theOnDeviceAnswerIsToldItFetchesNothing() {
        // Phone, 2026-10-17: offline, the local model wrote "I'll simulate loading it now" and an invented post.
        val r = LocalPrompt.OFFLINE_RULES
        assertTrue(r.contains("opens, loads, searches and reads nothing"))
        assertTrue(r.contains("no \"simulating\""))
        assertTrue("the Tor line no longer tells it to say a page is loading", !r.contains("say it is still loading"))
    }
}
