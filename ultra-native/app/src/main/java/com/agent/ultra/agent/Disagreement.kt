package com.agent.ultra.agent

/**
 * The moment the agent's idea of the job and the screen in front of it differ.
 *
 * Everything else in this codebase asks *may I*. This asks *is this right*, and
 * it is a different question. The gate already stops an irreversible action and
 * puts a card in front of the user; what it cannot do is tell them anything
 * useful, because it does not know what the job was. So the card says "about to
 * press Pay" whether the screen reads £240 or £2,400, and a person tapping
 * through a confirmation they have seen fifty times will approve both.
 *
 * ## Why only money, and only sometimes
 *
 * A guard that fires often gets switched off, and a guard that is off protects
 * nobody — the same reason [ScreenSecrets] refuses to treat every six-digit
 * number as a code. So this refuses to speak unless three things are true at
 * once: the user named an amount, the screen shows an amount, and **none** of
 * the amounts on screen match what they said.
 *
 * A screen showing a subtotal, tax and a total is the normal case, and one of
 * those matching is enough to stay quiet. Silence is the expected behaviour.
 * This exists for the case where nothing on the screen is the number the person
 * asked for, which is not a rounding difference — it is the wrong transaction.
 *
 * ## Comparison is arithmetic, never text
 *
 * `240` is a substring of `2400`. The house rule earned four times over in this
 * file's review — never let containment alone decide a match — is why both
 * sides are parsed to minor units and compared as integers. A near-miss here is
 * the exact failure the whole mechanism exists to catch.
 */
object Disagreement {

    /** An amount, normalised to minor units so 240 and 240.00 are one number. */
    data class Money(val minor: Long, val raw: String)

    /**
     * Currency markers.
     *
     * Deliberately short. Requiring a marker is what stops a house number, a
     * quantity or a year being read as a price, and an unmarked number is the
     * conservative thing to ignore: a missed comparison leaves the user exactly
     * where they were before this file existed, while a false one interrupts a
     * task they asked for.
     */
    private const val SYMBOLS = "£$€¥"
    private val CODES = setOf("GBP", "USD", "EUR", "JPY")

    /** Verbs after which a bare number in a *request* is money. */
    private val MONEY_VERBS = setOf("pay", "send", "transfer", "withdraw", "deposit", "refund")

    private val MARKED = Regex("""[$SYMBOLS]\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val CODED = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s?([A-Z]{3})""")
    private val BARE = Regex("""\b([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b""")

    /** Amounts visible on a screen. A currency marker is required. */
    fun onScreen(text: String): List<Money> {
        val out = mutableListOf<Money>()
        for (m in MARKED.findAll(text)) minor(m.groupValues[1])?.let { out += Money(it, m.value.trim()) }
        for (m in CODED.findAll(text)) {
            if (m.groupValues[2] in CODES) minor(m.groupValues[1])?.let { out += Money(it, m.value.trim()) }
        }
        return out.distinctBy { it.minor }
    }

    /**
     * Amounts the user actually named.
     *
     * Accepts a bare number straight after a payment verb — "pay 240 to the
     * gas people" is someone naming an amount, and refusing to understand it
     * would make this fire only for people who type currency symbols.
     */
    fun statedByUser(request: String): List<Money> {
        val out = onScreen(request).toMutableList()
        val words = request.split(Regex("\\s+"))
        for ((i, w) in words.withIndex()) {
            if (w.lowercase().trim(',', '.', ':') !in MONEY_VERBS) continue
            val next = words.getOrNull(i + 1) ?: continue
            if (next.any { it in SYMBOLS }) continue          // already counted
            BARE.find(next)?.let { m -> minor(m.groupValues[1])?.let { out += Money(it, next) } }
        }
        return out.distinctBy { it.minor }
    }

    /**
     * Why the agent should stop, or null to carry on.
     *
     * Names the largest amount on screen, because on a payment screen that is
     * usually the total and always the one worth arguing about.
     */
    fun contradiction(request: String, screenText: String): String? {
        val stated = statedByUser(request)
        if (stated.isEmpty()) return null
        val shown = onScreen(screenText)
        if (shown.isEmpty()) return null
        if (shown.any { s -> stated.any { it.minor == s.minor } }) return null
        val worst = shown.maxByOrNull { it.minor } ?: return null
        val said = stated.joinToString(" or ") { it.raw }
        return "this screen says ${worst.raw} and you said $said"
    }

    /** "2,400.50" → 240050. Null when it is not a number after all. */
    private fun minor(raw: String): Long? {
        val clean = raw.replace(",", "")
        val dot = clean.indexOf('.')
        return try {
            if (dot < 0) clean.toLong() * 100
            else {
                val whole = clean.substring(0, dot).toLong()
                val frac = clean.substring(dot + 1).padEnd(2, '0').take(2).toLong()
                whole * 100 + frac
            }
        } catch (_: Exception) { null }
    }
}
