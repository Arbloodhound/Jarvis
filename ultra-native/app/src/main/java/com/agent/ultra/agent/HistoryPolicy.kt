package com.agent.ultra.agent

/**
 * Which earlier chat turns an offline answer gets. Pure Kotlin, JVM-tested.
 *
 * Measured on the Pixel (DEVLOG 2026-10-06): with the last ~3000 chars of unrelated Q&A in
 * every offline prompt, the small-quant model copied from it — "summarize this: <paste>"
 * repeated the previous summary, a packing list picked up "17x23 (391)". So history is
 * opt-in: only a request that refers back to the conversation gets it. A self-contained
 * request (a fresh topic, a long paste) gets none.
 *
 * When history IS included, [window] keeps it stable between turns so the engine's KV
 * prefix reuse covers it: old turns are dropped in whole blocks from a fixed anchor, never
 * one at a time from the front.
 */
object HistoryPolicy {

    data class Decision(val include: Boolean, val reason: String)

    /** User text longer than this is treated as a self-contained paste. */
    const val LONG_PASTE_CHARS = 800

    /** Turns per block: the anchor only ever sits on a multiple of this. */
    const val BLOCK_TURNS = 4

    private fun rx(vararg words: String) =
        Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    // The conversation itself: "summarize everything we talked about", "what did I ask".
    private val conversationRef = rx(
        "we (talked|discussed|spoke|chatted|covered|went over|were (talking|discussing))",
        "(our|this) (conversation|chat|discussion|thread)",
        "you (just )?(said|mentioned|told me|wrote|suggested|recommended|listed|gave|meant)",
        "(what|when) (did )?i (say|said|ask|asked|tell you|told you)", "i (just )?(told you|asked you)",
        "what was my (last )?question", "i (said|mentioned|wrote|asked) (earlier|above|before)",
        "(your|my|the) (last|previous|earlier|first) (answer|reply|message|question|response|point)",
        "so far", "(from|in) (your|the) (list|answer|reply)",
    )
    // Continuations: "continue", "tell me more", "say that again".
    private val continuation = rx(
        "continue", "tell me more", "more (detail|details|on that|about (it|that|those))",
        "elaborate", "expand on", "(say|do|try|explain|tell me|read)( (it|that|this))? again", "(the )?rest of (it|that|the list)",
    )
    // Pointing at an item of an earlier answer: "the second one", "number 2", "option 3".
    private val ordinal = rx(
        "the (first|second|third|fourth|fifth|last|other|next|previous|same) (one|ones|option|idea|item|suggestion|recipe|dish)",
        "(that|this|which) one", "(number|option|item) ?#?\\d+",
    )
    // A command acting on something already on screen: "translate that", "make it shorter".
    private val pronounObject = Regex(
        "\\b(make|translate|rewrite|reword|shorten|summari[sz]e|explain|repeat|redo|fix|change|simplify|expand|" +
            "clarify|check|improve|continue|finish|list|compare|convert|turn)\\s+(it|that|those|these|them|this)\\b(?!\\s*:)",
        RegexOption.IGNORE_CASE,
    )
    // A referring pronoun ending the request: "what do you mean by that?", "why is that".
    private val pronounAtEnd = Regex("\\b(that|those|these|them|it)\\s*[?.!]*\\s*$", RegexOption.IGNORE_CASE)
    private val referringPronoun = rx("that", "those", "these", "them", "it", "he", "she", "they", "him", "her")
    // Short follow-ups: "and the other one?", "what about pasta?"; bare "why?", "really?".
    private val followUpStart = Regex(
        "^(and|but|so|also|then|what about|how about|what else)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val bareFollowUp = Regex(
        "^(why( not)?|how( come| so)?|really|seriously|are you sure|is that (true|right|correct)|source|example|" +
            "go on|keep going|carry on|more|next)\\W*$",
        RegexOption.IGNORE_CASE,
    )
    private const val SHORT_WORDS = 6

    /**
     * The instruction a request gives, without pasted material: "summarize this: <text>"
     * scans only "summarize this". Words inside a paste never count as references back.
     */
    fun instructionPart(request: String): String {
        val t = IntentRouter.requestPart(request).trim()
        val cut = Regex(":\\s|\\n").find(t) ?: return t
        val rest = t.substring(cut.range.last + 1).trim()
        return if (rest.length > 20) t.substring(0, cut.range.first).trim() else t
    }

    fun decide(request: String): Decision {
        val full = IntentRouter.requestPart(request).trim()
        if (full.length > LONG_PASTE_CHARS) return Decision(false, "long paste: self-contained")
        val t = instructionPart(request)
        if (t.isEmpty()) return Decision(false, "empty")
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        // "summarize this: <text>" — the pronoun points at the paste, not back.
        val pasted = t.length < full.length
        return when {
            conversationRef.containsMatchIn(t) -> Decision(true, "refers to the conversation")
            pasted -> Decision(false, "paste: self-contained")
            continuation.containsMatchIn(t) -> Decision(true, "continuation")
            ordinal.containsMatchIn(t) -> Decision(true, "points at an earlier item")
            pronounObject.containsMatchIn(t) -> Decision(true, "acts on something earlier")
            pronounAtEnd.containsMatchIn(t) && words <= 10 -> Decision(true, "ends on a reference")
            words <= SHORT_WORDS && referringPronoun.containsMatchIn(t) -> Decision(true, "short, with a reference")
            bareFollowUp.matches(t) -> Decision(true, "bare follow-up")
            words <= SHORT_WORDS && followUpStart.containsMatchIn(t) -> Decision(true, "short follow-up")
            else -> Decision(false, "self-contained")
        }
    }

    /** Text a history turn contributes to the prompt — the same measure [LocalPrompt.build] uses. */
    fun textOf(t: LocalPrompt.Turn): String = if (t.fromUser) t.text.trim() else ThinkBudget.stripForHistory(t.text)

    /**
     * The turns to include, stable across consecutive turns. [history] is the whole
     * conversation in order, without the current request; indices must not shift between
     * turns (turns are only appended), which ChatStore's list gives.
     *
     * The window starts at the earliest multiple of [block] whose turns fit [budgetChars].
     * As turns are appended that anchor stays put, so the prompt grows only at its end and
     * the previous prompt is a prefix of the next up to the last request. When the anchor
     * moves it jumps a whole block. A leading assistant turn is dropped so the window opens
     * on the user. If even the last block does not fit, the newest turns that fit are kept.
     */
    fun window(history: List<LocalPrompt.Turn>, budgetChars: Int, block: Int = BLOCK_TURNS): List<LocalPrompt.Turn> {
        if (history.isEmpty() || budgetChars <= 0) return emptyList()
        val len = history.map { textOf(it).length }
        val suffix = IntArray(history.size + 1)
        for (i in history.indices.reversed()) suffix[i] = suffix[i + 1] + len[i]
        val step = block.coerceAtLeast(1)
        var start = -1
        var s = 0
        while (s < history.size) {
            if (suffix[s] <= budgetChars) { start = s; break }
            s += step
        }
        if (start < 0) {
            // Over budget even from the last anchor: newest-first, as before.
            start = history.size
            while (start > 0 && suffix[start - 1] <= budgetChars) start--
        }
        while (start < history.size && (!history[start].fromUser || len[start] == 0)) start++
        return history.subList(start, history.size).filter { textOf(it).isNotEmpty() }
    }
}
