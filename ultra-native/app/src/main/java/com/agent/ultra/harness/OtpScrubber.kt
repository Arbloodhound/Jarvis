package com.agent.ultra.harness

/*
 * MIT License — Copyright (c) 2026 OpenClaw Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * OTP / 2FA code scrubber for the notification / SMS text path. One-time
 * verification codes must never reach the cloud model (standing rule: "SMS/email
 * codes and reset links never reach the cloud model"). Run [scrub] over any
 * notification/message text BEFORE it enters the agent's context; the code is
 * replaced with [PLACEHOLDER] so the agent can still reason about "a code
 * arrived" without seeing it.
 *
 * Scope: OTP/2FA codes only (contiguous 4–8 digits, 3+3 grouped codes, and the
 * Google-style `G-123456`). The LINK half of the standing rule — password-reset,
 * recovery and magic sign-in links — and the scrubbing of connector results are
 * handled by the already-wired
 * [com.agent.ultra.harness.connectors.SecretScrubber] (used by every connector
 * and by Brain's cloud scrubber); this primitive is the lighter text-path guard
 * that stamps the standing 10-minute placeholder, and does not duplicate link
 * handling.
 *
 * Conservative by design: it scrubs a digit run only when an OTP keyword is
 * nearby, and the `G-…` code always. A bare number with no code keyword (an
 * order number, a phone number, a quantity) is left alone. Pure; heavily
 * JVM-tested for both true positives and must-not-scrub negatives.
 */
object OtpScrubber {
    const val PLACEHOLDER = "{{OTP_CODE_PLACEHOLDER_10M}}"

    // Google-style verification code, e.g. "G-558812" — unambiguous, always scrubbed.
    private val GOOGLE = Regex("\\bG-\\d{4,8}\\b")

    // Keyword (strong OTP indicator) followed by a 4–8 digit code within a short,
    // digit-free gap. "code" on its own is handled separately and more tightly.
    private val KW_THEN_CODE = Regex(
        "(?i)\\b(?:otp|2fa|two[\\s-]?factor|one[\\s-]?time(?:\\s+(?:password|passcode|pin|code))?|" +
            "pass\\s?code|verification|verify|security\\s+code|authentication\\s+code|auth\\s+code|" +
            "login\\s+code|log[\\s-]?in\\s+code|sign[\\s-]?in\\s+code|confirmation\\s+code|" +
            "access\\s+code|activation\\s+code)\\b[^\\d\\n]{0,20}?(\\d{4,8}|\\d{3}[- ]\\d{3})\\b",
    )

    // Bare "code" giving a value: "code is 123456", "code: 482913", "code 90210".
    private val CODE_GIVES = Regex("(?i)\\bcode\\b[^\\d\\n]{0,10}?(\\d{4,8}|\\d{3}[- ]\\d{3})\\b")

    // Code before the keyword: "123456 is your verification code".
    private val CODE_BEFORE_KW = Regex(
        "(?i)\\b(\\d{4,8}|\\d{3}[- ]\\d{3})\\b\\s+is\\s+your\\b[^\\d\\n]{0,25}?\\b" +
            "(?:code|otp|passcode|pass\\s?code|password|pin|verification)\\b",
    )

    // Code used to act: "Enter 4821 to verify / confirm / log in / authenticate".
    private val CODE_TO_ACTION = Regex(
        "(?i)\\b(\\d{4,8}|\\d{3}[- ]\\d{3})\\b[^\\d\\n]{0,15}?\\bto\\s+(?:verify|confirm|log\\s*in|sign\\s*in|authenticate)\\b",
    )

    private val GROUP_PATTERNS = listOf(KW_THEN_CODE, CODE_GIVES, CODE_BEFORE_KW, CODE_TO_ACTION)

    /** Replace every detected OTP/2FA code with [PLACEHOLDER]. */
    fun scrub(input: String): String {
        if (input.isEmpty()) return input
        var s = GOOGLE.replace(input, PLACEHOLDER)
        for (re in GROUP_PATTERNS) {
            s = re.replace(s) { m -> replaceGroup1(m, PLACEHOLDER) }
        }
        return s
    }

    /** True if [scrub] would change the text (an OTP code is present). */
    fun containsOtp(input: String): Boolean = scrub(input) != input

    /** Rebuild a match with only its first capture group replaced by [rep]. */
    private fun replaceGroup1(m: MatchResult, rep: String): String {
        val g = m.groups[1] ?: return m.value
        val start = m.range.first
        val gs = g.range.first - start
        val ge = g.range.last - start + 1
        val v = m.value
        return v.substring(0, gs) + rep + v.substring(ge)
    }
}
