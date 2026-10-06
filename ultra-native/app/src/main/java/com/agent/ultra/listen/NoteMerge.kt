package com.agent.ultra.listen

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a memory note is about. Stored as [key] (lower case) in MemoryNotes. */
enum class NoteKind {
    PERSON, PLAN, TODO, FACT;

    val key: String get() = name.lowercase(Locale.ROOT)

    /** Kinds whose meaning hangs on a time: two of them only merge when their times agree. */
    val timed: Boolean get() = this == PLAN || this == TODO

    companion object {
        private val SYNONYMS = mapOf(
            "task" to TODO, "to-do" to TODO, "to_do" to TODO, "reminder" to TODO,
            "event" to PLAN, "appointment" to PLAN, "meeting" to PLAN,
            "preference" to FACT, "place" to FACT, "people" to PERSON,
        )

        /** Case-insensitive; a few synonyms a small model tends to write; null otherwise. */
        fun parse(s: String?): NoteKind? {
            val k = s?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.key == k } ?: SYNONYMS[k]
        }
    }
}

/**
 * One organised memory note. Times are epoch ms. [whenTs] is WHEN the note is about (null = no
 * time, e.g. a fact); [whenEnd] is the end of that span when only a day or part of a day was said
 * (null = a clock time was said). [heardTs] is when it was overheard; [sourceIds] are the
 * `heard` rows it came from (rowids in ambient.db).
 */
data class Note(
    val id: Long = 0,
    val kind: NoteKind,
    val text: String,
    val whenTs: Long?,
    val whenEnd: Long?,
    val heardTs: Long,
    val sourceIds: List<Long>,
    val createdTs: Long,
    val updatedTs: Long,
) {
    /** "Tue 7 Oct 15:00", "Tue 7 Oct" (a whole day or part of one), or null with no time. */
    fun whenText(zone: ZoneId): String? {
        val start = whenTs ?: return null
        val fmt = if (whenEnd == null) AT else DAY
        return fmt.format(Instant.ofEpochMilli(start).atZone(zone))
    }

    /** One line for the big model: "[plan, Tue 7 Oct 15:00] Dentist with Dr Lee". */
    fun describe(zone: ZoneId): String {
        val time = whenText(zone)?.let { ", $it" } ?: ""
        return "[${kind.key}$time] $text"
    }

    private companion object {
        val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    }
}

/**
 * The librarian's dedupe/merge rule. Pure, JVM-tested.
 *
 * Duplicate = same kind, similar wording, and (for plans/to-dos) times that agree:
 *  - wording: content words (stop words dropped) with Jaccard ≥ [JACCARD], or, when the
 *    shorter note has ≥ 3 content words, ≥ [CONTAINMENT] of them inside the other note;
 *  - times: both timed → start times within [TIME_SLACK_MS]; one or both untimed → agree.
 * Merge keeps the more detailed (longer) text, the new time when the new note has one, the
 * union of source rows (newest [MAX_SOURCES]), the earliest heard time, the original created time.
 */
object NoteMerge {
    const val JACCARD = 0.6
    const val CONTAINMENT = 0.85
    const val TIME_SLACK_MS = 2 * 3_600_000L
    const val MAX_SOURCES = 64

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
        val sa = a.whenTs ?: return true
        val sb = b.whenTs ?: return true
        return kotlin.math.abs(sa - sb) <= TIME_SLACK_MS
    }

    fun isDuplicate(a: Note, b: Note): Boolean =
        a.kind == b.kind && timesCompatible(a, b) && similar(a.text, b.text)

    /** The most similar duplicate of [fresh] among [candidates], or null. */
    fun findDuplicate(fresh: Note, candidates: List<Note>): Note? =
        candidates.filter { it.id != fresh.id && isDuplicate(fresh, it) }
            .maxByOrNull { similarity(fresh.text, it.text) }

    /** [old] absorbs [fresh]; keeps old's id and created time. */
    fun merge(old: Note, fresh: Note, now: Long): Note {
        val text = if (fresh.text.length >= old.text.length) fresh.text else old.text
        val (start, end) = if (fresh.whenTs != null) fresh.whenTs to fresh.whenEnd
            else old.whenTs to old.whenEnd
        return old.copy(
            text = text,
            whenTs = start,
            whenEnd = end,
            heardTs = minOf(old.heardTs, fresh.heardTs),
            sourceIds = (old.sourceIds + fresh.sourceIds).distinct().sorted().takeLast(MAX_SOURCES),
            updatedTs = now,
        )
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

    /**
     * Orders FTS matches for a search: most query words matched (prefix match, like the FTS
     * query) first, then the newest. Pure, JVM-tested. An OR query alone would rank a note that
     * shares one common word with the question as high as one that shares all of them.
     */
    fun rank(query: String, notes: List<Note>, limit: Int): List<Note> {
        val q = tokens(query).filter { it.length >= 2 }
        if (q.isEmpty()) return notes.sortedByDescending { it.updatedTs }.take(limit)
        fun score(n: Note): Int {
            val t = tokens(n.text)
            return q.count { w -> t.any { it.startsWith(w) } }
        }
        return notes.map { it to score(it) }
            .sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.updatedTs })
            .take(limit)
            .map { it.first }
    }
}
