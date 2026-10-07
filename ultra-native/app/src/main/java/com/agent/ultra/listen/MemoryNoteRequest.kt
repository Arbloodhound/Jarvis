package com.agent.ultra.listen

/**
 * Notes made later from what was already heard (owner, 2026-10-07: "make notes later on based on
 * previous memories it picked up from always listening"):
 *  - "make a note of what Sam said about the boiler" → search memory, write one note, save it;
 *  - "save that as a note" / "note that down" → the last answer becomes a note.
 * Pure, JVM-tested.
 */
object MemoryNoteRequest {
    /** How the owner's own words to Jarvis are kept in memory (Brain.recordTurn). */
    const val TO_JARVIS = "[Me] (to Jarvis)"

    sealed interface Kind {
        /** Write a note from memory about [query]. */
        data class FromMemory(val query: String) : Kind
        /** The previous answer, saved as a note. */
        object LastAnswer : Kind
    }

    private val LAST = Regex(
        "^(?:please )?(?:(?:save|add|put|keep|store|turn|make) (?:that|this|it|your (?:last )?answer)(?: as| into| in| to)? " +
            "(?:a )?(?:note|my notes)|note (?:that|this|it)(?: down)?|(?:jot|write) (?:that|this|it) down)$",
        RegexOption.IGNORE_CASE,
    )
    private val ASK = Regex(
        "^(?:please )?(?:(?:make|take|write|save|create|add) (?:a |me a )?note|note down|jot down|write down)" +
            "(?: of| about| on| from)?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    /** The body is about something heard, not a new fact to write down. */
    private val HEARD = Regex(
        "\\b(said|says|told|tells|mentioned|talked|talking|discussed|decided|agreed|promised|asked|" +
            "conversation|chat|call|meeting|heard|overheard|from (?:your |my |the )?memory|we (?:spoke|talked))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val LEAD = Regex("^(?:what|everything|all|the stuff|the things|whatever)\\s+", RegexOption.IGNORE_CASE)

    fun parse(request: String): Kind? {
        val t = request.trim().trimEnd('.', '!', '?')
        if (LAST.matches(t)) return Kind.LastAnswer
        val body = ASK.find(t)?.groupValues?.get(1)?.trim() ?: return null
        if (!HEARD.containsMatchIn(body)) return null
        return Kind.FromMemory(LEAD.replace(body, "").trim())
    }

    /** The note in the model's reply ("Note: …" line, else its first line), or null for none. Pure. */
    fun noteText(answer: String): String? {
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.firstOrNull { it.startsWith("note:", ignoreCase = true) }?.substringAfter(':')
            ?: lines.firstOrNull() ?: return null
        val t = line.trim().trim('"', '*', ' ')
        if (t.isEmpty() || t.startsWith("nothing found", ignoreCase = true) || t.startsWith("nothing ", ignoreCase = true)) return null
        return t.take(300)
    }

    /** An answer as a note: without the app's own tags ("(on-device)", "(from model knowledge, unverified)"). Pure. */
    fun fromAnswer(answer: String): String? =
        answer.replace(Regex("\\((?:on-device|from model knowledge, unverified)\\)"), " ")
            .replace(Regex("\\s+"), " ").trim().take(400).ifBlank { null }
}
