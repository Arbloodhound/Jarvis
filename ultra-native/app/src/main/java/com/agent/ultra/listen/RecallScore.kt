package com.agent.ultra.listen

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One remembered thing. [score] is 0..1 (1 = as relevant as it gets); [source] e.g. "heard", "notes". */
data class Hit(val text: String, val ts: Long, val score: Float, val source: String)

/**
 * A second place to remember from (e.g. the librarian's notes), plugged into
 * [MemoryRecall.extraSources]. [query] arrives with its time phrase already removed and
 * parsed into [window] (null = no time named). Return at most [limit] hits, scores on the
 * same 0..1 scale as [RecallScore.score], so they merge fairly with heard lines.
 */
interface RecallSource {
    suspend fun search(query: String, window: TimeWindow?, limit: Int): List<Hit>
}

/**
 * Pure ranking for [MemoryRecall] (JVM-tested): query terms, the FTS query built from them,
 * keyword overlap, recency, the combined score, the merge, and the prompt [brief].
 *
 * score = 0.8 × relevance + 0.2 × recency, where relevance = 0.65 × semantic + 0.35 × keyword
 * when the line has a vector, else keyword alone (the embedder-unavailable path). A line
 * outside the asked-for time window keeps half its score (the window is a strong hint, not a
 * wall: "the meeting on Tuesday" may have been mentioned on Monday). With no content words
 * ("what did I hear yesterday?") lines rank by recency inside the window.
 */
object RecallScore {
    /** Cosine below this is "unrelated" for MiniLM-style sentence vectors; [SEM_FULL] is a strong match. */
    const val SEM_FLOOR = 0.15f
    const val SEM_FULL = 0.65f
    /** A line with no keyword overlap needs at least this cosine to be returned at all. */
    const val SEM_ALONE = 0.30f
    const val OUT_OF_WINDOW = 0.5f
    const val HALF_LIFE_MS = 7 * 86_400_000L
    private const val MAX_TERMS = 8

    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "if", "so", "then", "than", "as", "of", "to", "in", "on", "at",
        "for", "with", "about", "from", "by", "into", "up", "out", "over", "off", "is", "are", "was", "were",
        "be", "been", "being", "am", "do", "does", "did", "done", "doing", "have", "has", "had", "i", "me", "my",
        "mine", "you", "your", "yours", "we", "us", "our", "they", "them", "their", "he", "him", "his", "she",
        "her", "it", "its", "this", "that", "these", "those", "there", "here", "what", "whats", "when", "where",
        "who", "whom", "which", "why", "how", "any", "anything", "something", "someone", "somebody", "anyone",
        "anybody", "everything", "say", "said", "says", "saying", "hear", "heard", "hearing", "talk", "talked",
        "talking", "mention", "mentioned", "remember", "recall", "tell", "told", "know", "please", "can",
        "could", "would", "should", "will", "shall", "may", "might", "must", "hey", "jarvis", "ok", "okay",
        "just", "again", "ago", "earlier", "today", "yesterday", "tonight", "now", "not", "no", "yes", "all",
        "some", "get", "got", "let", "lets", "also", "very", "really", "too", "like", "thing", "things",
    )

    /** Content words of [query], lightly stemmed, deduped, at most 8. Digits are kept as-is. */
    fun terms(query: String): List<String> =
        words(query).filter { it !in STOP && (it.length >= 2 || it[0].isDigit()) }
            .map { if (it[0].isDigit()) it else stem(it) }
            .distinct().take(MAX_TERMS)

    /** Suffix stripping just strong enough for a prefix match ("meetings" → "meet"). */
    fun stem(w: String): String {
        fun cut(suffix: String, n: Int = suffix.length) =
            if (w.endsWith(suffix) && w.length - n >= 3) w.dropLast(n) else null
        return cut("ing") ?: cut("ies") ?: cut("sses", 2) ?: cut("xes", 2) ?: cut("ches", 2) ?: cut("shes", 2)
            ?: cut("ed") ?: (if (w.endsWith("ss")) null else cut("s")) ?: w
    }

    /** FTS4 MATCH text: any term, words as prefixes ("dentist* OR 5"). [terms] holds letters and
     * digits only (see [words]), so nothing in it is FTS syntax. */
    fun ftsQuery(terms: List<String>): String =
        terms.joinToString(" OR ") { if (it[0].isDigit()) it else "$it*" }

    /** Share of [terms] found in [text] (word prefix for words, whole word for numbers). */
    fun keywordFraction(terms: List<String>, text: String): Float {
        if (terms.isEmpty()) return 0f
        val ws = words(text)
        val n = terms.count { t -> ws.any { w -> if (t[0].isDigit()) w == t else w.startsWith(t) } }
        return n.toFloat() / terms.size
    }

    fun recency(ts: Long, now: Long, halfLifeMs: Long = HALF_LIFE_MS): Float {
        val age = (now - ts).coerceAtLeast(0L).toDouble()
        return Math.pow(0.5, age / halfLifeMs.coerceAtLeast(1L)).toFloat()
    }

    fun semantic(cosine: Float): Float = ((cosine - SEM_FLOOR) / (SEM_FULL - SEM_FLOOR)).coerceIn(0f, 1f)

    /**
     * The combined score, or null when the line should not be returned. [cosine] is null when
     * the line has no vector (or the embedder is unavailable); [inWindow] is null when no window
     * was asked for; [hasTerms] = the query had content words.
     */
    fun score(cosine: Float?, keyword: Float, recency: Float, inWindow: Boolean?, hasTerms: Boolean): Float? {
        val base = if (!hasTerms) {
            if (inWindow == false) return null          // time-only question: the window is the question
            recency
        } else {
            if (keyword <= 0f && (cosine == null || cosine < SEM_ALONE)) return null
            val rel = if (cosine != null) 0.65f * semantic(cosine) + 0.35f * keyword else keyword
            0.8f * rel + 0.2f * recency
        }
        return if (inWindow == false) base * OUT_OF_WINDOW else base
    }

    /** Best first, the same words said twice (or found by two sources) kept once. */
    fun merge(hits: List<Hit>, limit: Int): List<Hit> {
        val seen = HashSet<String>()
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.ts })
            .filter { seen.add(words(it.text).joinToString(" ")) }
            .take(limit)
    }

    /**
     * A compact dated block for a prompt, oldest first, at most [maxChars] including the header.
     * Lower-scored hits are the ones left out when it doesn't all fit. Empty when nothing fits.
     */
    fun brief(hits: List<Hit>, maxChars: Int = 800, zone: TimeZone = TimeZone.getDefault()): String {
        val header = "What I remember (overheard speech may be misheard):"
        val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).apply { timeZone = zone }
        var used = header.length
        val kept = ArrayList<Pair<Hit, String>>()
        for (h in hits.sortedByDescending { it.score }) {
            val text = h.text.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val prefix = "\n- ${fmt.format(Date(h.ts))} (${h.source}): "
            val room = maxChars - used - prefix.length
            if (room < 24) continue
            val body = if (text.length <= minOf(room, MAX_LINE)) text
                else text.take(minOf(room, MAX_LINE) - 1).trimEnd() + "…"
            val line = prefix + body
            kept += h to line
            used += line.length
        }
        if (kept.isEmpty()) return ""
        return header + kept.sortedBy { it.first.ts }.joinToString("") { it.second }
    }

    private const val MAX_LINE = 240

    internal fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
