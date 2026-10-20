package com.agent.jarvis.agent

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
 * prefix reuse covers it: the window is append-only from a fixed anchor and, when full,
 * jumps forward a whole segment — never one turn at a time from the front.
 */
object HistoryPolicy {

    data class Decision(val include: Boolean, val reason: String)

    /** User text longer than this is treated as a self-contained paste. */
    const val LONG_PASTE_CHARS = 800

    private fun rx(vararg words: String) =
        Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    // The conversation itself: "summarize everything we talked about", "what did I ask".
    private val conversationRef = rx(
        "we (talked|discussed|spoke|chatted|covered|went over|were (talking|discussing))",
        "(our|this) (conversation|chat|discussion|thread)",
        "you (just )?(said|mentioned|told me|wrote|suggested|recommended|listed|gave|meant|mean)",
        "(what|when) (did )?i (say|said|ask|asked|tell you|told you)", "i (just )?(told you|asked you)",
        "what was my (last )?question", "i (said|mentioned|wrote|asked) (earlier|above|before)",
        "(your|my|the) (last|previous|earlier|first) (answer|reply|message|question|response|point)",
        "(said|discussed|covered|talked about|told me|asked|got|have) so far", "(from|in) (your|the) (list|answer|reply)",
    )
    // Continuations: "continue", "tell me more", "say that again".
    private val continuation = rx(
        "^continue", "continue (that|it|this|the (list|story|answer|text))", "tell me more", "more (detail|details|on that|about (it|that|those))",
        "elaborate", "expand on", "(say|do|try|explain|tell me|read)( (it|that|this))? again", "(the )?rest of (it|that|the list)",
    )
    // Pointing at an item of an earlier answer: "the second one", "number 2", "option 3".
    // Not a definition: "the first one to climb Everest", "element number 26".
    private val ordinal = rx(
        "the (first|second|third|fourth|fifth|last|other|next|previous|same) (one|ones|option|idea|item|suggestion|recipe|dish)" +
            "(?!\\s+(to|who|that|which|of|in|on|at|from|by|ever)\\b)",
        "(that|this|which) one(?!\\s+(to|who|that|of)\\b)",
        "(^|(the|about|on|for|with|pick|take|try|choose|use|go with|explain|expand)\\s+)(number|option|item) ?#?\\d+",
    )
    // A command acting on something already on screen: "translate that", "make it shorter".
    private val pronounObject = Regex(
        "\\b(make|translate|rewrite|reword|shorten|summari[sz]e|explain|repeat|redo|fix|change|simplify|expand|" +
            "clarify|check|improve|continue|finish|list|compare|convert|turn)\\s+(it|that|those|these|them|this)\\b(?!\\s*:)",
        RegexOption.IGNORE_CASE,
    )
    // A referring pronoun ending the request: "what do you mean by that?", "why is that".
    private val pronounAtEnd = Regex("\\b(that|those|these|them|it)\\s*[?.!]*\\s*$", RegexOption.IGNORE_CASE)
    private val referringPronoun = rx(
        "that", "those", "these", "them", "it", "its", "he", "she", "they", "him", "her", "his", "their", "theirs",
    )
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
        val head = t.substring(0, cut.range.first).trim()
        val rest = t.substring(cut.range.last + 1).trim()
        // A line break after a question ("what did you mean by that?\nthe part about taxes") is
        // still the request; only a long block after it counts as pasted material.
        val isPaste = if (cut.value.startsWith(":")) rest.length > 20
            else rest.length > 200 || (rest.length > 20 && !head.endsWith("?"))
        return if (isPaste) head else t
    }

    // "it" that points at nothing: "what time is it", "is it going to rain", "it's cold".
    // "how far is it?" / "is it safe?" stay references: after an answer they point back.
    private val dummyIt = Regex(
        "\\b(what (time|day|date|year|month)|how (late|early))\\b[^?.!]*?\\b(is|was) it\\b|" +
            "\\b(is|was|will) it (be )?(going to )?(rain|raining|snow|snowing|sunny|cloudy|cold|hot|warm|windy|late|early|" +
            "true that)\\b|" +
            "\\bit('s| is| was) (raining|snowing|sunny|cloudy|cold|hot|warm|windy|late|early)\\b",
        RegexOption.IGNORE_CASE,
    )
    // In "rewrite that in this style: <sample>" the "that" points back; in "translate that: hola"
    // (pronoun right before the colon) and "translate it into French: hola" it is the paste.
    private val backPronounBeforePaste = Regex(
        "\\b(make|translate|rewrite|reword|shorten|summari[sz]e|explain|repeat|redo|fix|change|simplify|expand|" +
            "clarify|check|improve|continue|finish|list|compare|convert|turn)\\s+(that|those)\\s+\\S",
        RegexOption.IGNORE_CASE,
    )

    fun decide(request: String): Decision {
        val full = IntentRouter.requestPart(request).trim()
        if (full.length > LONG_PASTE_CHARS) return Decision(false, "long paste: self-contained")
        val t = instructionPart(request)
        if (t.isEmpty()) return Decision(false, "empty")
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        // "summarize this: <text>" — the pronoun points at the paste, not back.
        val pasted = t.length < full.length
        // Pronoun rules never see an "it" that refers to nothing.
        val p = dummyIt.replace(t, " ").trim()
        return when {
            conversationRef.containsMatchIn(t) -> Decision(true, "refers to the conversation")
            pasted && (ordinal.findAll(t).any { !it.value.startsWith("this", ignoreCase = true) } ||
                backPronounBeforePaste.containsMatchIn(t)) ->
                Decision(true, "paste with a reference back")
            pasted -> Decision(false, "paste: self-contained")
            continuation.containsMatchIn(t) -> Decision(true, "continuation")
            ordinal.containsMatchIn(t) -> Decision(true, "points at an earlier item")
            pronounObject.containsMatchIn(p) -> Decision(true, "acts on something earlier")
            pronounAtEnd.containsMatchIn(p) && words <= 10 -> Decision(true, "ends on a reference")
            words <= SHORT_WORDS && referringPronoun.containsMatchIn(p) -> Decision(true, "short, with a reference")
            bareFollowUp.matches(t) -> Decision(true, "bare follow-up")
            words <= SHORT_WORDS && followUpStart.containsMatchIn(t) -> Decision(true, "short follow-up")
            else -> Decision(false, "self-contained")
        }
    }

    /** Text a history turn contributes to the prompt — the same measure [LocalPrompt.build] uses. */
    fun textOf(t: LocalPrompt.Turn): String = if (t.fromUser) t.text.trim() else ThinkBudget.stripForHistory(t.text)

    /**
     * Segment starts for [history]: greedy from the front, a new segment opens at a user turn
     * once the current one holds [segmentChars]. Each start depends only on the turns before
     * it, so appending turns never moves an existing start.
     */
    fun segmentStarts(len: List<Int>, fromUser: List<Boolean>, segmentChars: Int): List<Int> {
        val starts = mutableListOf(0)
        var acc = 0
        for (i in len.indices) {
            if (i > starts.last() && fromUser[i] && acc + len[i] > segmentChars) { starts += i; acc = 0 }
            acc += len[i]
        }
        return starts
    }

    /**
     * The turns to include, stable across consecutive turns. [history] is the whole
     * conversation in order, without the current request; turns are only appended, which
     * ChatStore's list gives.
     *
     * The conversation is cut from the front into segments of about 2/3 of [budgetChars]
     * ([segmentStarts]). The window opens at the start of the previous segment when that fits,
     * else at the start of the current one. So the window only grows at its end (the previous
     * prompt is a prefix of the next up to the last request) and its start jumps forward by a
     * whole segment about once per segment's worth of text: append-only, then reset. After a
     * jump it still holds the current segment (at least the last exchange, ~1/3 of the budget).
     * If even the current segment does not fit (one huge turn), the newest turns that fit are
     * kept; when not even the last turn fits, its first [budgetChars] are kept, so a request that
     * refers back always sees the answer it refers to.
     */
    fun window(history: List<LocalPrompt.Turn>, budgetChars: Int): List<LocalPrompt.Turn> {
        if (budgetChars <= 0) return emptyList()
        val turns = history
            .map { LocalPrompt.Turn(it.fromUser, textOf(it)) }
            .filter { it.text.isNotEmpty() }
        if (turns.isEmpty()) return emptyList()
        val len = turns.map { it.text.length }
        val suffix = IntArray(turns.size + 1)
        for (i in turns.indices.reversed()) suffix[i] = suffix[i + 1] + len[i]
        val starts = segmentStarts(len, turns.map { it.fromUser }, budgetChars * 2 / 3)
        val start = starts.takeLast(2).firstOrNull { suffix[it] <= budgetChars } ?: run {
            var s = turns.size
            while (s > 0 && suffix[s - 1] <= budgetChars) s--
            s
        }
        if (start < turns.size) return turns.subList(start, turns.size)
        val last = turns.last()
        return listOf(LocalPrompt.Turn(last.fromUser, last.text.take(budgetChars).trimEnd()))
    }
}
