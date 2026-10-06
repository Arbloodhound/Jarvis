package com.agent.ultra.listen

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a memory note is about. Stored as [key] (lower case) in MemoryNotes. */
enum class NoteKind {
    EVENT, TODO, FACT, PERSON, PLACE, PREFERENCE;

    val key: String get() = name.lowercase(Locale.ROOT)

    /** Kinds whose meaning hangs on a time: two of them only merge when their times agree. */
    val timed: Boolean get() = this == EVENT || this == TODO

    companion object {
        private val SYNONYMS = mapOf(
            "task" to TODO, "to-do" to TODO, "to_do" to TODO, "reminder" to TODO,
            "plan" to EVENT, "appointment" to EVENT, "meeting" to EVENT,
            "pref" to PREFERENCE, "like" to PREFERENCE, "location" to PLACE,
        )

        /** Case-insensitive; a few synonyms a small model tends to write; null otherwise. */
        fun parse(s: String?): NoteKind? {
            val k = s?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.key == k } ?: SYNONYMS[k]
        }
    }
}

/**
 * One organised memory note. Times are epoch ms. [aboutStart]/[aboutEnd] say WHEN the note is
 * about (null = no time, e.g. a fact); [sourceFrom]/[sourceTo] say when it was overheard.
 */
data class Note(
    val id: Long = 0,
    val createdTs: Long,
    val aboutStart: Long?,
    val aboutEnd: Long?,
    val kind: NoteKind,
    val text: String,
    val people: List<String>,
    val sourceFrom: Long,
    val sourceTo: Long,
    val updatedTs: Long,
) {
    /** One line for a brief: "[event Tue 7 Oct 15:00] Dentist with Dr Lee (Dr Lee)". */
    fun describe(zone: ZoneId): String {
        val time = aboutStart?.let { " " + WHEN.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
        val who = if (people.isEmpty()) "" else " (" + people.joinToString(", ") + ")"
        return "[${kind.key}$time] $text$who"
    }

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    }
}

/**
 * The librarian's dedupe/merge rule. Pure, JVM-tested.
 *
 * Duplicate = same kind, similar wording, and (for events/to-dos) times that agree:
 *  - wording: content words (stop words dropped) with Jaccard ≥ [JACCARD], or, when the
 *    shorter note has ≥ 3 content words, ≥ [CONTAINMENT] of them inside the other note;
 *  - times: both timed → start times within [TIME_SLACK_MS]; one or both untimed → agree.
 * Merge keeps the more detailed (longer) text, the newer time when the new note has one, the
 * union of people, the widest source span, the original created time.
 */
object NoteMerge {
    const val JACCARD = 0.6
    const val CONTAINMENT = 0.85
    const val TIME_SLACK_MS = 2 * 3_600_000L

    private val STOP = setOf(
        "a", "an", "the", "to", "at", "on", "in", "of", "and", "or", "is", "are", "was", "be", "will",
        "for", "with", "my", "our", "i", "we", "he", "she", "they", "it", "that", "this", "has", "have",
        "had", "his", "her", "their", "about", "from", "by", "as", "there", "should", "needs", "need",
    )

    fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in STOP }
            .toSet()

    /** 0..1 wording similarity; also the tie-break between several candidate duplicates. */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        val inter = ta.count { it in tb }.toDouble()
        val jaccard = inter / (ta.size + tb.size - inter)
        val small = minOf(ta.size, tb.size)
        val contain = if (small >= 3) inter / small else 0.0
        return if (contain >= CONTAINMENT) maxOf(jaccard, contain) else jaccard
    }

    fun similar(a: String, b: String): Boolean = similarity(a, b) >= JACCARD

    fun timesCompatible(a: Note, b: Note): Boolean {
        if (!a.kind.timed) return true
        val sa = a.aboutStart ?: return true
        val sb = b.aboutStart ?: return true
        return kotlin.math.abs(sa - sb) <= TIME_SLACK_MS
    }

    fun isDuplicate(a: Note, b: Note): Boolean =
        a.kind == b.kind && timesCompatible(a, b) && similar(a.text, b.text)

    /** The most similar duplicate of [fresh] among [candidates], or null. */
    fun findDuplicate(fresh: Note, candidates: List<Note>): Note? =
        candidates.filter { isDuplicate(fresh, it) }.maxByOrNull { similarity(fresh.text, it.text) }

    /** [old] absorbs [fresh]; keeps old's id and created time. */
    fun merge(old: Note, fresh: Note, now: Long): Note {
        val text = if (fresh.text.length >= old.text.length) fresh.text else old.text
        val (start, end) = if (fresh.aboutStart != null) fresh.aboutStart to fresh.aboutEnd
            else old.aboutStart to old.aboutEnd
        return old.copy(
            aboutStart = start,
            aboutEnd = end,
            text = text,
            people = unionPeople(old.people, fresh.people),
            sourceFrom = minOf(old.sourceFrom, fresh.sourceFrom),
            sourceTo = maxOf(old.sourceTo, fresh.sourceTo),
            updatedTs = now,
        )
    }

    /** Order-preserving, case-insensitive union. */
    fun unionPeople(a: List<String>, b: List<String>): List<String> {
        val seen = HashSet<String>()
        return (a + b).map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
    }

    /**
     * A safe FTS4 MATCH string for free text: words only (letters/digits), OR-ed, each a prefix
     * match, at most 8. Null when nothing searchable is left (the caller lists by time instead).
     * Never passes user punctuation through, so a stray quote or "-" can't raise a syntax error.
     */
    fun ftsQuery(text: String): String? {
        val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            // lower case: FTS operators are only recognised in upper case (OR, NOT, NEAR)
            .filter { it.length >= 2 && it !in STOP }
            .distinct()
            .take(8)
        return if (words.isEmpty()) null else words.joinToString(" OR ") { "$it*" }
    }
}
