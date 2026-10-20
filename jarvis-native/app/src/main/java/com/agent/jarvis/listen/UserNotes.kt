package com.agent.jarvis.listen

import java.time.ZoneId

/**
 * Notes the owner makes themself ("note: buy milk", "remember that the gate code is 4512"), kept
 * in the same store as the librarian's ([MemoryNotes]) so there is one notes list to browse,
 * search and ask about. They used to go to text files under files/notes that nothing ever read.
 * Pure parts JVM-tested.
 */
object UserNotes {

    private val COMMAND = Regex(
        "^(?:please\\s+)?(?:" +
            "(?:take|make|add|save|write|create)\\s+(?:a\\s+)?note(?:\\s+that)?[:,]?" +
            "|note(?:\\s+this)?(?:\\s+down)?[:,]?" +
            "|(?:jot|write)\\s+(?:this\\s+)?down[:,]?" +
            "|remember\\s+that" +
            ")\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The note's text when [request] tells Jarvis to note something down, else null. "Remember
     * that …" only as an instruction (no question mark, at least 3 words after it): "remember
     * that time we went to Rome?" is a question about memory. Pure.
     */
    fun command(request: String): String? {
        val t = request.trim().trimEnd('.', '!')
        val m = COMMAND.find(t) ?: return null
        val body = m.groupValues[1].trim()
        if (body.isEmpty() || t.endsWith("?")) return null
        if (t.lowercase().startsWith("remember") && body.split(Regex("\\s+")).size < 3) return null
        return body.replaceFirstChar { it.uppercase() }
    }

    /**
     * The note for [text], said at [now]: a to-do when worded as one ("buy milk", "need to …"
     * → see [NoteParser.normaliseKind]), a plan when it names a time, else a fact. The time words
     * are resolved against [now] like the librarian's. Pure.
     */
    fun build(text: String, now: Long, zone: ZoneId): Note {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val phrase = TimeResolver.phraseIn(clean)
        var kind = NoteParser.normaliseKind(NoteKind.FACT, clean)
        if (kind == NoteKind.FACT && (phrase != null || TODO_START.containsMatchIn(clean)))
            kind = if (TODO_START.containsMatchIn(clean)) NoteKind.TODO else NoteKind.PLAN
        val span = if (kind.timed) TimeResolver.resolve(phrase, now, zone) else null
        return Note(
            kind = kind, text = clean, whenTs = span?.start, whenEnd = span?.end,
            heardTs = now, sourceIds = emptyList(), createdTs = now, updatedTs = now,
        )
    }

    /** "Buy milk", "call the dentist", "pick up …": a bare verb at the start is something to do. */
    private val TODO_START = Regex(
        "^(?:buy|get|call|ring|text|email|book|pay|pick up|collect|send|renew|fix|clean|cancel|order|" +
            "check|finish|bring|return|post|ask|tell|remind)\\b",
        RegexOption.IGNORE_CASE,
    )
}
