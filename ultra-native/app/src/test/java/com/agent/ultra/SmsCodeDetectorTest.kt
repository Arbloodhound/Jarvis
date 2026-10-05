package com.agent.ultra

import com.agent.ultra.agent.SmsCodeDetector
import org.junit.Assert.*
import org.junit.Test

class SmsCodeDetectorTest {

    @Test
    fun `extracts 6-digit code with keyword`() {
        assertEquals("847293", SmsCodeDetector.extract("Your verification code is 847293"))
    }

    @Test
    fun `extracts 4-digit code`() {
        assertEquals("9182", SmsCodeDetector.extract("Your OTP is 9182. Do not share."))
    }

    @Test
    fun `extracts 8-digit code`() {
        assertEquals("91827364", SmsCodeDetector.extract("Your one-time passcode: 91827364"))
    }

    @Test
    fun `returns null for plain text`() {
        assertNull(SmsCodeDetector.extract("Hey, want to grab lunch?"))
    }

    @Test
    fun `returns null for empty text`() {
        assertNull(SmsCodeDetector.extract(""))
    }

    @Test
    fun `returns null for blank text`() {
        assertNull(SmsCodeDetector.extract("   "))
    }

    @Test
    fun `keyword match is case insensitive`() {
        assertEquals("482910", SmsCodeDetector.extract("YOUR VERIFICATION CODE IS 482910"))
    }

    @Test
    fun `rejects sequential false positives`() {
        assertNull(SmsCodeDetector.extract("Your code is 123456"))
    }

    @Test
    fun `rejects all zeros`() {
        assertNull(SmsCodeDetector.extract("Your code is 000000"))
    }

    @Test
    fun `extracts code near sign-in keyword`() {
        assertEquals("739201", SmsCodeDetector.extract("739201 is your sign-in code for App"))
    }

    @Test
    fun `extracts code with 2fa keyword`() {
        assertEquals("8472", SmsCodeDetector.extract("Your 2FA code: 8472"))
    }

    @Test
    fun `no match without keyword even with digits`() {
        assertNull(SmsCodeDetector.extract("Your order 847293 has shipped"))
    }

    @Test
    fun `picks first valid code when multiple present`() {
        assertEquals("482910", SmsCodeDetector.extract("Code: 482910. Expires in 300 seconds."))
    }

    @Test
    fun `handles code at start of message`() {
        assertEquals("739201", SmsCodeDetector.extract("739201 is your verification code"))
    }

    @Test
    fun `handles code at end of message`() {
        assertEquals("847293", SmsCodeDetector.extract("Your security code is 847293"))
    }

    @Test
    fun `handles confirm keyword`() {
        assertEquals("5823", SmsCodeDetector.extract("To confirm your account enter 5823"))
    }

    @Test
    fun `handles access code keyword`() {
        assertEquals("901234", SmsCodeDetector.extract("Your access code is 901234"))
    }

    @Test
    fun `3 digits too short`() {
        assertNull(SmsCodeDetector.extract("Your code is 123"))
    }

    @Test
    fun `9 digits too long`() {
        assertNull(SmsCodeDetector.extract("Your code is 123456789"))
    }
}
