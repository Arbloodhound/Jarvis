package com.agent.jarvis.harness.connectors

/**
 * Standing rule (§6): SMS/email codes and reset links never reach the cloud model. Every connector
 * result passes through [scrub] before it is returned: one-time codes named as codes, password-reset
 * / account-recovery links and magic sign-in links are replaced with an opaque placeholder
 * (`[credential:<id>]`), the same shape Muse's Gmail connector uses. The placeholder can't be turned
 * back into the code by the agent; the original stays only in [held] (in memory, this process) for
 * the user's own screen. Pure, JVM-tested.
 */
class SecretScrubber {

    private val held = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    data class Result(val text: String, val removed: Int)

    fun scrub(text: String, nowMs: Long = System.currentTimeMillis()): Result {
        var n = 0
        fun hold(v: String): String {
            n++
            val id = java.util.UUID.randomUUID().toString().take(8)
            held[id] = v to nowMs
            return "[credential:$id]"
        }
        var out = LINK.replace(text) { m -> if (isSensitiveLink(m.value)) hold(m.value) else m.value }
        out = CODE_NEAR_WORD.replace(out) { m -> m.value.replace(m.groupValues[2], hold(m.groupValues[2])) }
        out = WORD_AFTER_CODE.replace(out) { m -> m.value.replace(m.groupValues[1], hold(m.groupValues[1])) }
        // Held values expire after ten minutes, like Muse's authd references.
        held.entries.removeIf { nowMs - it.value.second > HOLD_MS }
        return Result(out, n)
    }

    /** For the user's own screen only; never passed to a model. Null once expired. */
    internal fun reveal(id: String, nowMs: Long = System.currentTimeMillis()): String? =
        held[id]?.takeIf { nowMs - it.second <= HOLD_MS }?.first

    companion object {
        const val HOLD_MS = 10 * 60_000L

        private val LINK = Regex("https?://[^\\s\"'<>)]+", RegexOption.IGNORE_CASE)

        private val SENSITIVE_PATH = Regex(
            "(reset|recover|recovery|forgot|password|passwd|magic|signin|sign-in|sign_in|login|log-in|verify|verification|" +
                "confirm|activate|one-?time|otp|auth|token|code)", RegexOption.IGNORE_CASE)

        /** A link that resets, recovers or signs in (and carries a token-ish query or path). */
        fun isSensitiveLink(url: String): Boolean {
            val pathAndQuery = url.substringAfter("://").substringAfter('/', "")
            if (!SENSITIVE_PATH.containsMatchIn(pathAndQuery)) return false
            // Needs something that looks like a token: a long opaque segment or a token-ish parameter.
            // Opaque = 16+ characters mixing letters with digits, or upper with lower case
            // ("AbCdEf…", "x9Fk2…") — not a lower-case slug like "how-to-reset-your-router".
            return Regex("[A-Za-z0-9_\\-]{16,}").findAll(pathAndQuery).any { m ->
                val v = m.value
                (v.any(Char::isDigit) && v.any(Char::isLetter)) || (v.any(Char::isUpperCase) && v.any(Char::isLowerCase))
            } ||
                Regex("[?&](token|code|key|t|k|oobcode|ticket|nonce|otp|sig)=", RegexOption.IGNORE_CASE).containsMatchIn(pathAndQuery)
        }

        private const val CODE_WORDS = "(?:verification|security|confirmation|login|sign[- ]?in|one[- ]?time|access|auth(?:entication)?|2fa|otp|passcode|pin)"

        /** "your verification code is 481920", "code: 4819-20", "OTP 481920". */
        private val CODE_NEAR_WORD = Regex(
            "(?i)\\b($CODE_WORDS\\s*(?:code|pin|number)?|code)\\b[^\\d\\n]{0,24}?\\b(\\d{4,8}|\\d{3}[- ]\\d{3})\\b")

        /** "481920 is your code". */
        private val WORD_AFTER_CODE = Regex(
            "(?i)\\b(\\d{4,8})\\b\\s+is\\s+your\\s+(?:$CODE_WORDS\\s+)?(?:code|pin|passcode)\\b")
    }
}
