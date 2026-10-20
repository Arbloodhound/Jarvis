package com.agent.jarvis.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    @Test fun shortReplyGetsMargin() = assertEquals(2_000L + 1_000L, EchoGuard.muteMsFor("x".repeat(12)))
    @Test fun scalesWithLength() = assertTrue(EchoGuard.muteMsFor("x".repeat(600)) > 50_000L)
    @Test fun neverForever() = assertEquals(120_000L, EchoGuard.muteMsFor("x".repeat(100_000)))
}
