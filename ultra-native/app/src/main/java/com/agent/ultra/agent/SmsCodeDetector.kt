package com.agent.ultra.agent

/**
 * Deterministic verification-code extractor.
 *
 * Looks for 4–8 digit codes in SMS text that sit near verification-related
 * keywords. No LLM involved — this is a regex with guard rails.
 *
 * Returns null when the text doesn't look like a verification message, or
 * the extracted code string when it does.
 *
 * Keywords are whole words ("pin" is not in "shipping", "confirm" is not
 * "confirmed"), and the code chosen is the candidate nearest a keyword, not the
 * first number in the text: "Sept 5 2026: your verification code is 482913"
 * is 482913, not 2026. Years, prices and pieces of phone numbers are never
 * candidates.
 */
object SmsCodeDetector {

    private val KEYWORD = Regex(
        """\b(verif\w*|codes?|otp|pin|passcode|one[- ]time|2fa|mfa|confirm|confirmation|authenticat\w*|""" +
            """sign[- ]?in|log[- ]?in|authori[sz]ation)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "zip code", "promo code" — the word is there, but not as a verification code. */
    private val NOT_A_CODE_PHRASE = Regex(
        """\b(zip|postal|post|postcode|area|promo|promotional|discount|coupon|voucher|referral|tracking|dress|qr|bar|source|country|sort)\s+codes?\b""",
        RegexOption.IGNORE_CASE,
    )

    private val CODE_PATTERN = Regex("""(?<![\d.,])\b(\d{4,8})\b(?![.,]\d)""")

    private val MONTH = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(st|nd|rd|th)?,?\s*$|\b\d{1,2}(st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?,?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // Codes that are obviously not verification codes
    private val FALSE_POSITIVES = setOf(
        "1234", "12345", "123456", "1234567", "12345678",
        "0000", "00000", "000000",
    )

    fun extract(smsBody: String): String? {
        if (smsBody.isBlank()) return null
        val keywordText = NOT_A_CODE_PHRASE.replace(smsBody) { " ".repeat(it.value.length) }
        val keywords = KEYWORD.findAll(keywordText).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        val candidates = CODE_PATTERN.findAll(smsBody)
            .filter { it.groupValues[1] !in FALSE_POSITIVES }
            .filter { !partOfSomethingElse(smsBody, it.range) }
            .toList()
        // Nearest keyword wins; a tie goes to the earlier candidate.
        return candidates.minByOrNull { c ->
            keywords.minOf { k ->
                when {
                    c.range.first > k.last -> c.range.first - k.last
                    k.first > c.range.last -> k.first - c.range.last
                    else -> 0
                }
            }
        }?.groupValues?.get(1)
    }

    /** A price, a year in a date, or one group of a phone number. */
    private fun partOfSomethingElse(s: String, r: IntRange): Boolean {
        val before = s.substring(maxOf(0, r.first - 12), r.first)
        val after = s.substring(r.last + 1, minOf(s.length, r.last + 13))
        // money: "$1500", "£ 1500", "1500 USD", "1500 dollars"
        if (Regex("""[$£€¥₹]\s?$""").containsMatchIn(before)) return true
        if (Regex("""^\s?(usd|eur|gbp|dollars?|euros?|pounds?|rs|inr)\b""", RegexOption.IGNORE_CASE).containsMatchIn(after)) return true
        // phone: "+44 0800 ...", "0800 123 4567", "(555) 0101", "555-0101"
        if (Regex("""(\+|\d[\s-]|\)\s?)$""").containsMatchIn(before)) return true
        if (Regex("""^[\s-]\d""").containsMatchIn(after)) return true
        // dates: "2026-09-19", "19/09/2026", "Sept 5 2026", "5 Sept, 2026"
        val digits = s.substring(r)
        if (digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20"))) {
            if (Regex("""\d[/.-]$""").containsMatchIn(before) || Regex("""^[/.-]\d""").containsMatchIn(after)) return true
            if (MONTH.containsMatchIn(s.substring(maxOf(0, r.first - 20), r.first))) return true
        }
        return false
    }
}
