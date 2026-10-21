package com.agent.jarvis.agent

/**
 * The moment the agent's idea of the job and the screen in front of it differ.
 *
 * Refuses to speak unless three things are true at once: the user named an amount, the screen shows
 * an amount, and **none** of the amounts on screen match what they said. One amount matching (a
 * subtotal, tax and a total screen) stays quiet.
 *
 * Comparison is arithmetic, never text: both sides are parsed to minor units and compared as
 * integers (`240` is a substring of `2400`).
 */
object Disagreement {

    /** An amount, normalised to minor units so 240 and 240.00 are one number. */
    data class Money(val minor: Long, val raw: String)

    /** Currency markers. */
    private const val SYMBOLS = "£$€¥"
    private val CODES = setOf("GBP", "USD", "EUR", "JPY")

    /** Verbs after which a bare number in a *request* is money. */
    private val MONEY_VERBS = setOf("pay", "send", "transfer", "withdraw", "deposit", "refund")

    /** Digits with any mix of "," and "." between them; [minor] decides what each one means. */
    private const val NUM = """[0-9](?:[0-9.,]*[0-9])?"""
    private val MARKED = Regex("""[$SYMBOLS]\s?($NUM)""")
    private val CODED = Regex("""($NUM)\s?([A-Z]{3})""")
    private val BARE = Regex("""\b($NUM)\b""")

    /** Amounts visible on a screen. A currency marker is required. */
    fun onScreen(text: String): List<Money> = scan(text).first

    /** The amounts, and whether any marked amount could not be read with certainty. */
    private fun scan(text: String): Pair<List<Money>, Boolean> {
        val out = mutableListOf<Money>()
        var unsure = false
        for (m in MARKED.findAll(text)) {
            val v = minor(m.groupValues[1])
            if (v == null) unsure = true else out += Money(v, m.value.trim())
        }
        for (m in CODED.findAll(text)) {
            if (m.groupValues[2] !in CODES) continue
            val v = minor(m.groupValues[1])
            if (v == null) unsure = true else out += Money(v, m.value.trim())
        }
        return out.distinctBy { it.minor } to unsure
    }

    /**
     * Amounts the user actually named. Accepts a bare number straight after a payment verb ("pay
     * 240 to the gas people").
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
     * Why the agent should stop, or null to carry on. Names the largest amount on screen.
     */
    fun contradiction(request: String, screenText: String): String? {
        val stated = statedByUser(request)
        if (stated.isEmpty()) return null
        val (shown, unsure) = scan(screenText)
        if (shown.isEmpty()) return null
        if (unsure) return null
        if (shown.any { s -> stated.any { it.minor == s.minor } }) return null
        val worst = shown.maxByOrNull { it.minor } ?: return null
        val said = stated.joinToString(" or ") { it.raw }
        return "this screen says ${worst.raw} and you said $said"
    }

    /**
     * "2,400.50" → 240050, "240,00" → 24000, "1.234,56" → 123456. Null when it is not a
     * number after all, or when the separators could mean two different amounts.
     *
     * The last separator is the decimal point when one or two digits follow it; every
     * other separator must then be the other kind, grouping exactly three digits. When
     * three digits follow the last separator it is a thousands separator ("2,400",
     * "1.234"), and all separators must agree.
     */
    private fun minor(raw: String): Long? {
        val seps = raw.withIndex().filter { it.value == ',' || it.value == '.' }
        if (seps.isEmpty()) return raw.toLongOrNull()?.times(100)
        val groups = raw.split(',', '.')
        if (groups.any { it.isEmpty() }) return null
        val last = seps.last()
        val tail = groups.last()
        val (wholeGroups, frac) = when (tail.length) {
            1, 2 -> {
                val others = seps.dropLast(1)
                if (others.any { it.value == last.value }) return null      // "1.234.56"
                groups.dropLast(1) to tail.padEnd(2, '0')
            }
            3 -> {
                if (seps.any { it.value != last.value }) return null         // "1,234.567"
                groups to "00"
            }
            else -> return null
        }
        // Thousands groups after the first are exactly three digits, and a grouped number does
        // not start with 0 ("0.125" is not 125).
        if (wholeGroups.size > 1 &&
            (wholeGroups.drop(1).any { it.length != 3 } || wholeGroups.first().length > 3 || wholeGroups.first().startsWith("0"))
        ) return null
        return try {
            wholeGroups.joinToString("").toLong() * 100 + frac.toLong()
        } catch (_: Exception) { null }
    }
}
