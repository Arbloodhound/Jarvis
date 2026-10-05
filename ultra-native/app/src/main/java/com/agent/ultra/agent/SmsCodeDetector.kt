package com.agent.ultra.agent

/**
 * Deterministic verification-code extractor.
 *
 * Looks for 4–8 digit codes in SMS text that sit near verification-related
 * keywords. No LLM involved — this is a regex with guard rails.
 *
 * Returns null when the text doesn't look like a verification message, or
 * the extracted code string when it does.
 */
object SmsCodeDetector {

    private val KEYWORDS = listOf(
        "verif", "code", "otp", "pin", "passcode", "one-time",
        "one time", "2fa", "mfa", "confirm", "authenticate",
        "security code", "login code", "sign-in", "signin",
        "access code", "authorization",
    )

    private val CODE_PATTERN = Regex("""\b(\d{4,8})\b""")

    // Codes that are obviously not verification codes
    private val FALSE_POSITIVES = setOf(
        "1234", "12345", "123456", "1234567", "12345678",
        "0000", "00000", "000000",
    )

    fun extract(smsBody: String): String? {
        if (smsBody.isBlank()) return null
        val lower = smsBody.lowercase()
        if (KEYWORDS.none { lower.contains(it) }) return null
        val candidates = CODE_PATTERN.findAll(smsBody)
            .map { it.groupValues[1] }
            .filter { it !in FALSE_POSITIVES }
            .toList()
        return candidates.firstOrNull()
    }
}
