package com.agent.jarvis.agent

/**
 * Streaming cap on a reasoning model's <think> trace.
 *
 * This counts tokens inside the trace and, at the budget, tells the engine to force-feed a short
 * wrap-up plus "</think>" so the model moves on to the answer ("budget forcing").
 *
 * Engine contract (one call per sampled token):
 *   val step = budget.onToken(piece)
 *   if (step.visible.isNotEmpty()) emit(step.visible)          // UI / TTS text, trace removed
 *   step.inject?.let { decodeWithoutSampling(it) }              // feed into the KV, don't sample
 *   ... at end of generation: emit(budget.flush())
 *
 * Qwen3 *-Thinking-2507 templates open <think> in the prompt, so output often
 * contains only the closing tag: pass startsInThink = true for those models.
 */
class ThinkBudget(
    val budgetTokens: Int,
    startsInThink: Boolean,
    private val wrapUp: String = WRAP_UP,
) {
    data class Step(val visible: String, val inject: String? = null)

    var inThink = startsInThink; private set
    var thinkTokens = 0; private set
    var forced = false; private set

    /** Set from another thread (the chat's "Answer now" button); read on the generate thread. */
    @Volatile private var answerNowRequested = false

    /**
     * End the trace at the next thinking token, the same way the budget does (wrap-up +
     * close tag injected), so a long Deep trace can be cut short and still give an answer.
     * No effect once the trace is over. Thread-safe.
     */
    fun answerNow() { answerNowRequested = true }

    /** The hidden trace as it is written (the chat's live "thinking" preview); never the answer. */
    @Volatile var onThought: ((String) -> Unit)? = null

    private val carry = StringBuilder()   // holds text that might be the start of a tag
    private var skipNewlines = false      // drop the blank line(s) right after a trace ends

    fun onToken(piece: String): Step {
        carry.append(piece)
        val out = StringBuilder()
        var inject: String? = null
        var countedThisToken = false

        loop@ while (true) {
            if (inThink) {
                val close = carry.indexOf(CLOSE)
                if (close >= 0) {
                    carry.delete(0, close + CLOSE.length)
                    inThink = false
                    skipNewlines = true
                    continue@loop
                }
                if (!countedThisToken) { thinkTokens++; countedThisToken = true }
                // drop trace text (shown only to the preview), but keep a possible partial "</think>" at the tail
                onThought?.let { cb -> val n = carry.length - (CLOSE.length - 1); if (n > 0) cb(carry.substring(0, n)) }
                keepTail(CLOSE.length - 1)
                if (!forced && (thinkTokens >= budgetTokens || answerNowRequested)) {
                    forced = true
                    inject = (if (budgetTokens > 0) wrapUp else "") + CLOSE + "\n\n"
                    // the injected close tag ends the trace from the engine's view
                    carry.setLength(0)
                    inThink = false
                    skipNewlines = true
                }
                break@loop
            } else {
                if (skipNewlines) {
                    trimLeadingNewlines()
                    if (carry.isEmpty()) break@loop
                    skipNewlines = false
                }
                val open = carry.indexOf(OPEN)
                if (open >= 0) {
                    out.append(carry, 0, open)
                    carry.delete(0, open + OPEN.length)
                    inThink = true
                    continue@loop
                }
                // stray close tag outside a trace (template quirk): drop it
                val strayClose = carry.indexOf(CLOSE)
                if (strayClose >= 0) {
                    out.append(carry, 0, strayClose)
                    carry.delete(0, strayClose + CLOSE.length)
                    continue@loop
                }
                val hold = partialTagSuffix()
                out.append(carry, 0, carry.length - hold)
                carry.delete(0, carry.length - hold)
                break@loop
            }
        }
        return Step(out.toString(), inject)
    }

    /** End of generation: release any held text (never trace text). */
    fun flush(): String {
        val s = if (inThink) "" else carry.toString()
        carry.setLength(0)
        return s
    }

    private fun keepTail(n: Int) {
        if (carry.length > n) carry.delete(0, carry.length - n)
    }

    private fun trimLeadingNewlines() {
        while (carry.isNotEmpty() && (carry[0] == '\n' || carry[0] == '\r')) carry.deleteCharAt(0)
    }

    /** Length of the longest suffix of carry that is a prefix of a tag. */
    private fun partialTagSuffix(): Int {
        var best = 0
        for (tag in arrayOf(OPEN, CLOSE)) {
            for (len in minOf(tag.length - 1, carry.length) downTo 1) {
                if (carry.regionMatches(carry.length - len, tag, 0, len)) { best = maxOf(best, len); break }
            }
        }
        return best
    }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
        const val WRAP_UP = "\n\nTime is short, so I'll answer now with what I have.\n"

        /**
         * Budget that can never starve the answer: the tier's budget, clipped so
         * prompt + trace + wrap-up + answerReserve still fits in nCtx. Returns 0
         * (skip thinking entirely) when the prompt alone is already near the limit.
         */
        fun clipToContext(tierBudget: Int, nCtx: Int, promptTokens: Int, answerReserve: Int = 512): Int {
            val room = nCtx - promptTokens - answerReserve - WRAP_UP_TOKENS
            return tierBudget.coerceAtMost(room).coerceAtLeast(0)
        }
        private const val WRAP_UP_TOKENS = 24

        /** Remove reasoning from an assistant turn before it goes back into history (saves KV). */
        fun stripForHistory(text: String): String {
            var s = text.replace(Regex("(?s)<think>.*?</think>"), "")
            val c = s.indexOf(CLOSE)                 // template-opened trace: drop through the close
            if (c >= 0) s = s.substring(c + CLOSE.length)
            val o = s.indexOf(OPEN)                  // unterminated trace at the end
            if (o >= 0) s = s.substring(0, o)
            return s.trim()
        }
    }
}
