package com.agent.ultra.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTP/2FA scrubbing: codes must never reach the cloud model. Heavy coverage of
 * true positives, plus must-not-scrub negatives (order numbers, phone numbers,
 * quantities with no OTP keyword).
 */
class OtpScrubberTest {
    private val P = OtpScrubber.PLACEHOLDER

    private fun scrubbed(s: String) = OtpScrubber.scrub(s)
    private fun assertScrubsDigits(s: String, vararg codes: String) {
        val out = scrubbed(s)
        assertTrue("expected a placeholder in: $out", out.contains(P))
        for (c in codes) assertFalse("code $c should be gone from: $out", out.contains(c))
    }

    // ── true positives ──
    @Test fun verificationCodeIs() = assertScrubsDigits("Your verification code is 123456", "123456")
    @Test fun codeColon() = assertScrubsDigits("code: 482913", "482913")
    @Test fun otpKeyword() = assertScrubsDigits("Your OTP is 90210", "90210")
    @Test fun oneTimePasscode() = assertScrubsDigits("Your one-time passcode is 5567", "5567")
    @Test fun googleStyle() = assertScrubsDigits("G-558812 is your Google code", "558812")
    @Test fun codeBeforeKeyword() = assertScrubsDigits("123456 is your verification code", "123456")
    @Test fun eightDigit2fa() = assertScrubsDigits("2FA code 11223344", "11223344")
    @Test fun enterToVerify() = assertScrubsDigits("Enter 4821 to verify your account", "4821")
    @Test fun securityCode() = assertScrubsDigits("Security code 778899 expires soon", "778899")
    @Test fun groupedCodeSpace() = assertScrubsDigits("Your code is 481 920", "481 920")
    @Test fun groupedCodeDash() = assertScrubsDigits("verification code: 481-920", "481-920")

    @Test fun scrubsMultipleCodesInOneText() {
        val out = scrubbed("Your code is 123456. If that fails, use backup code 654321.")
        assertFalse(out.contains("123456"))
        assertFalse(out.contains("654321"))
        assertEquals(2, Regex(Regex.escape(P)).findAll(out).count())
    }

    @Test fun containsOtpReflectsScrub() {
        assertTrue(OtpScrubber.containsOtp("verification code 445566"))
        assertFalse(OtpScrubber.containsOtp("the meeting is in room 4455"))
    }

    // ── must NOT scrub (no OTP keyword near the number) ──
    @Test fun orderNumberUntouched() {
        val s = "Your order #12345678 has shipped and will arrive Tuesday"
        assertEquals(s, scrubbed(s))
    }

    @Test fun phoneNumberUntouched() {
        val s = "Call me back at 5551234 when you can"
        assertEquals(s, scrubbed(s))
    }

    @Test fun quantityUntouched() {
        val s = "The invoice total was 4500 for 12 items"
        assertEquals(s, scrubbed(s))
    }

    @Test fun linesOfCodeNotAnOtp() {
        // "code" keyword present but no code value given after it.
        val s = "I wrote 1500 lines of code today"
        assertEquals(s, scrubbed(s))
    }

    @Test fun yearAndAddressUntouched() {
        val s = "Built in 1987, the house at 4210 Oak Street is lovely"
        assertEquals(s, scrubbed(s))
    }

    @Test fun shortAndLongDigitRunsIgnoredWithoutKeyword() {
        assertEquals("pin 12", scrubbed("pin 12")) // too short
        assertEquals("ref 1234567890", scrubbed("ref 1234567890")) // 10 digits, no keyword
    }

    @Test fun emptyStringSafe() = assertEquals("", scrubbed(""))
}
