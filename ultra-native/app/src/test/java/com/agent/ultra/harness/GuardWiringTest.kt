package com.agent.ultra.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring checks for the device-path guards. The Android classes can't run on the JVM, so these
 * pin (a) the pure behaviour the wiring relies on and (b) that the call sites still go through
 * the guards. Device behaviour stays SOURCE-FIXED BUT RUNTIME-UNPROVEN.
 */
class GuardWiringTest {
    private val base = "src/main/java/com/agent/ultra/"
    private fun src(rel: String) = File(base + rel).readText()

    @Test fun scrubIsIdempotentSoDoubleScrubbingOnLogAndReadIsSafe() {
        val once = OtpScrubber.scrub("Your verification code is 482913")
        assertEquals(once, OtpScrubber.scrub(once))
        assertFalse(once.contains("482913"))
    }

    @Test fun readTimeScrubCatchesKeywordInTitleAndCodeInText() {
        // notifications.log lines are "pkg | title | text"; the read handler scrubs the joined line.
        val line = "com.bank.app | Verification | 123456 is your login code"
        val out = OtpScrubber.scrub(line)
        assertFalse(out, out.contains("123456"))
    }

    @Test fun smsReadLineIsScrubbed() {
        val out = OtpScrubber.scrub("From +15551234567: G-558812 is your Google verification code")
        assertFalse(out.contains("558812"))
        assertTrue(out.contains(OtpScrubber.PLACEHOLDER))
    }

    @Test fun nonCodeNumbersSurviveInReadOutput() {
        val s = "From +15551234567: your order 77421 ships Tuesday"
        assertEquals(s, OtpScrubber.scrub(s))
    }

    @Test fun readHandlersAndLogWriterApplyTheScrubber() {
        val tools = src("agent/Tools.kt")
        assertTrue(tools.contains("OtpScrubber.scrub(controller.readNotifications("))
        assertTrue(tools.contains("OtpScrubber.scrub(controller.readSms("))
        val svc = src("UltraNotificationService.kt")
        assertTrue(svc.contains("OtpScrubber.scrub(title)"))
        assertTrue(svc.contains("OtpScrubber.scrub(text)"))
    }

    @Test fun coordinateTapGoesThroughTheScreenBoundGuard() {
        val a11y = src("AgentAccessibilityService.java")
        assertTrue(a11y.contains("screenGuard.requireScreenBound("))
        // Raw taps resolve the element under the point; the node-derived fallback passes its own bounds.
        assertTrue(a11y.contains("return performTapBound(x, y, nodeBoundsAt(x, y));"))
        assertTrue(a11y.contains("performTapBound(b.centerX(), b.centerY(), b)"))
        // Typing path keeps the password refusal.
        assertTrue(a11y.contains("screenGuard.requireNotPassword("))
    }
}
