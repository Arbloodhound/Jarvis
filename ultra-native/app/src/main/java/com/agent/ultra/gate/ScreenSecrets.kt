package com.agent.ultra.gate

/**
 * The secrets that actually appear on a phone screen.
 *
 * The ported detector looks for what leaks out of a developer's machine — API
 * keys, forty-character hashes, `password: hunter2`. None of that is what is at
 * risk here. What appears on a phone is a six-digit code from a bank, a card
 * number, a one-time password in a notification. **A 4–8 digit code was never
 * "secret-shaped" to the old detector**, while the notification logger three
 * files away was redacting anything over twenty characters. Two subsystems, two
 * different beliefs about what a secret is, and the one guarding the exits held
 * the weaker belief.
 *
 * ## Why context, and not just shape
 *
 * A bare six-digit number is a postcode, a house number, a year, a quantity, a
 * price without its decimal point. Treating every one as a secret would block
 * ordinary typing constantly, and a guard that fires on everything gets turned
 * off — which is worse than not having it.
 *
 * So a short number counts only when the screen says what it is: *your code
 * is*, *verification*, *one-time*, *OTP*, *PIN*. That is not clever, and it will
 * miss a bank that words it unusually. It is deliberately the conservative
 * direction: a missed secret leaves things exactly as they were before this
 * file existed, while a false positive breaks a task the user asked for.
 *
 * Card numbers are the exception — they are checked by arithmetic rather than
 * by wording, because a sixteen-digit string passing Luhn is not a coincidence.
 */
object ScreenSecrets {

    /** A value seen on screen, and where it was seen. */
    data class Seen(val value: String, val pkg: String, val why: String)

    /**
     * Words that turn a nearby number into a code.
     *
     * Kept close to the number: "your verification code is 481920" qualifies,
     * a page that happens to mention security somewhere and shows a house
     * number elsewhere does not.
     */
    private val CODE_CONTEXT = Regex(
        """(?i)\b(one[- ]?time|verification|verify|security|auth(?:entication)?|access|confirm(?:ation)?|login|sign[- ]?in|otp|pin|passcode|2fa|mfa)\b""",
    )

    private val SHORT_CODE = Regex("""\b\d{4,8}\b""")
    private val LONG_DIGITS = Regex("""\b(?:\d[ -]?){13,19}\b""")

    /** How far either side of a number the wording still counts. */
    private const val CONTEXT_WINDOW = 40

    /**
     * Secrets visible in this text, with the app they were seen in.
     *
     * The package travels with the value because that is the whole point: the
     * rule worth enforcing is not "this is a secret" but "this came from your
     * bank and is being typed into a chat app".
     */
    fun find(text: String, pkg: String): List<Seen> {
        val out = mutableListOf<Seen>()

        for (m in LONG_DIGITS.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && passesLuhn(digits)) {
                out += Seen(m.value.trim(), pkg, "a card number")
            }
        }

        for (m in SHORT_CODE.findAll(text)) {
            val from = (m.range.first - CONTEXT_WINDOW).coerceAtLeast(0)
            val to = (m.range.last + CONTEXT_WINDOW).coerceAtMost(text.length)
            if (CODE_CONTEXT.containsMatchIn(text.substring(from, to))) {
                out += Seen(m.value, pkg, "a one-time code")
            }
        }

        // Everything the ported detector already knew about, now carrying a
        // source rather than being a bare string in a list.
        for (s in findSecrets(text)) out += Seen(s, pkg, "a key or password")

        return out.distinctBy { it.value }
    }

    /**
     * The check that a card number is a card number.
     *
     * Arithmetic, not wording, which is why this one needs no context: a
     * sixteen-digit run that satisfies Luhn is not an order reference.
     */
    internal fun passesLuhn(digits: String): Boolean {
        if (digits.length < 13) return false
        var sum = 0
        var alternate = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /**
     * May this text be typed into this app?
     *
     * Returns the reason to refuse, or null to allow.
     *
     * The rule is deliberately narrow: a secret may go back into the app it
     * came from. Reading a code from a banking app and typing it into that same
     * banking app is the normal, expected thing, and blocking it would make the
     * agent useless for the one task people most want. Carrying it somewhere
     * else is the leak, and that is all this refuses.
     */
    fun refuseTyping(text: String, intoPkg: String, seen: List<Seen>): String? {
        if (text.isBlank()) return null
        for (s in seen) {
            if (s.pkg == intoPkg) continue          // home again is not a leak
            if (!text.contains(s.value)) continue
            return "that text contains ${s.why} read from ${s.pkg}, and this is $intoPkg. " +
                "A code from one app does not get typed into another."
        }
        return null
    }
}
