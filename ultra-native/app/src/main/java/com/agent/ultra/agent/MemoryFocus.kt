package com.agent.ultra.agent

/**
 * The memory tool and the notes tool: a question answered by reasoning over the owner's memory
 * (what Jarvis heard) and/or notes (what the memory model and the owner wrote down). Chosen from
 * the chat's Focus menu, or by the words of a request, typed or spoken ("search my notes for
 * Lisbon", "what's in my memory about Sam", "read my to-dos"). Pure parts JVM-tested; the search
 * itself is [provider], set by the app (UltraApplication), like [MemoryBrief.provider].
 */
object MemoryFocus {

    enum class Focus(val label: String) {
        NORMAL("Normal"), MEMORY("Memory"), NOTES("Notes"), BOTH("Memory + notes");
    }

    /** One focused question: where to look, what to look for ("" = the newest), to-dos only. */
    data class Ask(val focus: Focus, val query: String, val todosOnly: Boolean = false)

    /** Found text for [Ask] (lines with times), or null/blank for nothing. */
    @Volatile var provider: (suspend (ask: Ask, nowMs: Long) -> String?)? = null

    /** A focused brief may be longer than [MemoryBrief.MAX_CHARS]: reasoning over the notes is the job. */
    const val MAX_CHARS = 1800

    private val NOTES_WORDS = "notes?|note book|notebook"
    private val MEMORY_WORDS = "memory|memories"
    private val LOOK = "in|from|search|searching|check|checking|look (?:in|through|at)|go through|read(?: me)?|" +
        "according to|based on|using|with|what(?:'s| is| are) (?:in|on)|anything (?:in|on)"

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private val BOTH_RX = rx("\\b(?:my|your) (?:$MEMORY_WORDS) and (?:my |your )?(?:$NOTES_WORDS)\\b|" +
        "\\b(?:my|your) (?:$NOTES_WORDS) and (?:my |your )?(?:$MEMORY_WORDS)\\b")
    private val NOTES_RX = rx("\\b(?:$LOOK) (?:my|the|your) (?:$NOTES_WORDS)\\b|\\b(?:my|your) (?:$NOTES_WORDS) (?:say|said|about|on|for)\\b")
    private val MEMORY_RX = rx("\\b(?:$LOOK) (?:my|your) (?:$MEMORY_WORDS)\\b|\\b(?:my|your) (?:$MEMORY_WORDS) (?:say|of|about|on|for)\\b")
    private val TODOS_RX = rx("\\b(?:my|the) (?:to-?dos?|to do list|todo list|to-do list|tasks)\\b")

    /**
     * The focused question in [request], or null for an ordinary one. Its query is the request
     * without the "search my notes for" part, so the search looks for "Lisbon", not "notes".
     * Pure.
     */
    fun detect(request: String): Ask? {
        val t = IntentRouter.requestPart(request).trim()
        val focus = when {
            BOTH_RX.containsMatchIn(t) -> Focus.BOTH
            TODOS_RX.containsMatchIn(t) -> Focus.NOTES
            NOTES_RX.containsMatchIn(t) -> Focus.NOTES
            MEMORY_RX.containsMatchIn(t) -> Focus.MEMORY
            else -> return null
        }
        return Ask(focus, query(t), todosOnly = TODOS_RX.containsMatchIn(t) && focus == Focus.NOTES)
    }

    private val STRIP = rx("\\b(?:$LOOK|search|find|tell me|show me|what does|what do|what did|is there|are there|anything|" +
        "my|your|the|for|about|on|say|said|says|$NOTES_WORDS|$MEMORY_WORDS|and|to-?dos?|to do list|todo list|tasks|list|please|jarvis)\\b")

    /** The words left to search for once the focus words are gone ("" = just show the newest). Pure. */
    fun query(request: String): String =
        STRIP.replace(request, " ").replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** "open my notes", "show me my memory": a screen to open, or null. Pure. */
    fun screen(request: String): String? {
        val t = IntentRouter.requestPart(request).trim().lowercase().trimEnd('.', '!', '?')
        val m = Regex("^(?:please )?(?:open|show(?: me)?|go to|bring up|pull up|display)(?: up)? (?:my |the |your )?" +
            "(notes?|note book|notebook|memory|memories|to-?do list|to do list|to-?dos?)(?: screen| page| section)?(?: please)?$").find(t) ?: return null
        return if (m.groupValues[1].startsWith("memor")) "memory" else "notes"
    }

    /** What the model is told above the found text. */
    fun instruction(ask: Ask): String {
        val where = when (ask.focus) {
            Focus.MEMORY -> "what you heard (your memory)"
            Focus.NOTES -> if (ask.todosOnly) "the owner's to-do notes" else "the owner's notes"
            Focus.BOTH, Focus.NORMAL -> "your memory and the owner's notes"
        }
        return "The owner asked you to answer from $where, given below with times. Reason across them " +
            "(dates, people, what is still to do) and answer from them; say which note or moment you mean. " +
            "If they don't hold the answer, say so plainly."
    }

    /** The block the brain gets: [instruction] then what was found (or that nothing was). */
    fun brief(ask: Ask, found: String?): String {
        val body = found?.takeIf { it.isNotBlank() } ?: "(nothing found${if (ask.query.isBlank()) "" else " for \"${ask.query}\""})"
        return instruction(ask) + "\n" + body
    }
}
