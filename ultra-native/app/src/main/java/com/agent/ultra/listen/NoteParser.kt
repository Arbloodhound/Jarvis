package com.agent.ultra.listen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A note as the model wrote it, before its time words are resolved. [obs] is set (and only
 *  set) for [NoteKind.OBSERVATION]; its quotes are already verified against the transcript. */
data class NoteDraft(
    val kind: NoteKind, val text: String, val whenPhrase: String?, val obs: Observation? = null,
    /** Who said it / who it is about ("Me", "Sam", "Speaker 2"), from the model's "who". */
    val who: String? = null,
    /** Topic tags from the model's "tags" ([Note.cleanTags]). */
    val tags: List<String> = emptyList(),
)

/**
 * Tolerant parser for the librarian's "one JSON object per line" output. Pure, JVM-tested.
 *
 * Tolerates: a leftover reasoning trace (anything up to the last </think>, or an unterminated
 * <think> to the end), code fences, bullets/numbering before an object, a whole JSON array
 * instead of lines, "NONE", and prose lines (ignored). Rejects — counts, never stores — any
 * object that doesn't parse strictly, has an unknown kind, an empty or over-long text, or
 * wrong-typed fields, and a line cut off mid-object (the token limit). At most [MAX_NOTES]
 * notes per chunk.
 *
 * NOTE FORMAT (one JSON object per line; the contract for the zero-shot prompt and for any
 * fine-tuned librarian):
 *
 *   {"kind":"plan|todo|person|fact","text":"<one sentence>","when":"<time words as spoken, optional>"}
 *   {"kind":"observation","pattern":"<CuePattern id>","who":"<speaker label or name>",
 *    "quote":"<words copied from one transcript line>" | ["<quote>", ... up to 3],
 *    "confidence":"low|med|high","text":"<neutral one-sentence summary>",
 *    "reading":{"id":"<ReadingId id>","hedge":"may suggest|could be|might reflect",
 *               "confidence":"low|med|high","alt":"<one alternative explanation>"}}
 *   ("reading" is optional.)
 *
 * The zero-shot pipeline asks for "reading" in a second pass ([ReadingPrompt]) whose lines are
 * the reading object plus "obs":<1-based observation number>; [parseReadings] reads those.
 *
 * An observation is REJECTED when: the pattern isn't a [CuePattern] id; who/confidence is
 * missing (a missing "text" summary is filled in by [plainSummary]); any quote is not found verbatim (tags, case, punctuation and spacing aside) in one
 * source line ([CueText.quoteIn]); the quoted lines name speakers and none of them is its "who"
 * (untagged and [Unknown] lines name no one); or its text/who contains a verdict word
 * ([CueText.VERDICT_WORDS]: diagnoses, personality labels, honesty claims) or an
 * intent/mental-state claim ([CueText.INTENT_WORDS]). Only the verified quotes are exempt
 * (they are what was said). A note of any other kind with a verdict word is rejected too.
 * Timestamps come from the matched lines, never from the model; each quote is credited to its
 * own line's speaker when that line names one. A reading is dropped (the observation kept, the
 * drop counted as rejected) when its id is unknown, its hedge has no hedge word, confidence or
 * alt is missing, or it contains a verdict word.
 *
 * Two repairs for what a small model gets wrong (plan/todo/person/fact only; an observation only
 * ever gets [plainSummary] when its "text" is missing):
 * a fact worded as a duty ("need to", "must", "remind me to" …) becomes a todo ([normaliseKind]);
 * a plan or todo with no "when" takes the time words from its own text ([whenFromText]).
 */
object NoteParser {
    const val MAX_NOTES = 12
    const val MAX_TEXT = 300

    data class Result(val notes: List<NoteDraft>, val rejected: Int)

    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    const val MAX_QUOTES = 3
    const val MAX_QUOTE = 200
    const val MAX_WHO = 40
    const val MAX_ALT = 200

    /** [source] is the chunk the model read: observations are checked against it (none pass without). */
    fun parse(raw: String, source: List<HeardLine> = emptyList()): Result {
        val body = stripReasoning(raw)
        val notes = ArrayList<NoteDraft>()
        var rejected = 0
        val src = Source(source)

        fun take(e: JsonElement?) {
            val d = (e as? JsonObject)?.let { draft(it, src) { rejected++ } }
            if (d == null) rejected++ else if (notes.size < MAX_NOTES) notes += d
        }

        val whole = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (whole.startsWith("[")) {
            val arr = runCatching { json.parseToJsonElement(whole) }.getOrNull() as? JsonArray
            if (arr != null) { arr.forEach(::take); return Result(notes, rejected) }
        }
        for (line0 in body.lines()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            val open = line.indexOf('{')
            val close = line.lastIndexOf('}')
            if (open < 0) continue                               // prose / NONE: not a note
            if (close < open) { rejected++; continue }           // cut off mid-object
            take(runCatching { json.parseToJsonElement(line.substring(open, close + 1)) }.getOrNull())
        }
        return Result(notes, rejected)
    }

    /** Drop a reasoning trace: through the last </think>, and any unterminated <think> to the end. */
    fun stripReasoning(raw: String): String {
        var s = raw
        val close = s.lastIndexOf("</think>")
        if (close >= 0) s = s.substring(close + "</think>".length)
        val open = s.indexOf("<think>")
        if (open >= 0) s = s.substring(0, open)
        return s
    }

    /** Readings from the second pass, keyed by 0-based observation index (first valid one wins),
     *  for [count] observations; plus how many lines were rejected. */
    fun parseReadings(raw: String, count: Int): Pair<Map<Int, Reading>, Int> {
        val out = HashMap<Int, Reading>()
        var rejected = 0
        for (line0 in stripReasoning(raw).lines()) {
            val line = line0.trim()
            val open = line.indexOf('{')
            val close = line.lastIndexOf('}')
            if (open < 0) continue
            if (close < open) { rejected++; continue }
            val o = runCatching { json.parseToJsonElement(line.substring(open, close + 1)) }.getOrNull() as? JsonObject
            val n = (o?.get("obs") as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
            val r = o?.let(::reading)
            if (o == null || n == null || n !in 1..count || r == null) { rejected++; continue }
            out.putIfAbsent(n - 1, r)
        }
        return out to rejected
    }

    /** A reading object ({"id" or "reading", "hedge", "confidence", "alt"}), or null if it breaks a rule. */
    fun reading(o: JsonObject): Reading? {
        val id = ReadingId.parse(string(o["id"]) ?: string(o["reading"])) ?: return null
        val hedge = string(o["hedge"])?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.length <= 40 } ?: return null
        if (!CueText.isHedged(hedge)) return null
        val conf = Confidence.parse(string(o["confidence"])) ?: return null
        val alt = string(o["alt"] ?: o["alternative"])?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (alt.isEmpty() || alt.length > MAX_ALT) return null
        if (CueText.verdict(hedge) != null || CueText.verdict(alt) != null) return null
        // The tip is optional: a bad one is dropped, the reading kept.
        val tip = string(o["tip"])?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_ALT && CueText.verdict(it) == null }
        return Reading(id, hedge, conf, alt, tip)
    }

    private fun draft(o: JsonObject, source: Source, droppedReading: () -> Unit): NoteDraft? {
        val kind = NoteKind.parse(string(o["kind"]) ?: return null) ?: return null
        val given = string(o["text"])?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null && given.length > MAX_TEXT) return null
        if (given != null && LibrarianPrompt.copiesExample(given)) return null   // the prompt's example, not something heard
        if (kind == NoteKind.OBSERVATION) {
            val (summary, obs) = observation(o, given, source, droppedReading) ?: return null
            return NoteDraft(kind, summary, null, obs)
        }
        val text = given ?: return null
        // No diagnosis, personality label or honesty verdict in any note ("Sam is a liar" as a person note).
        if (CueText.verdict(text) != null) return null
        val whenEl = o["when"] ?: o["time"]
        val whenPhrase = when (whenEl) {
            null, JsonNull -> null
            else -> string(whenEl)   // a number or object: keep the note, just without a time
        }?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
        val k = normaliseKind(kind, text)
        val tags = when (val t = o["tags"] ?: o["tag"]) {
            is JsonArray -> t.mapNotNull { string(it) }
            null, JsonNull -> emptyList()
            else -> string(t)?.split(',', ';', '#').orEmpty()
        }
        return NoteDraft(k, text, whenPhrase ?: whenFromText(k, text), who = Note.cleanWho(string(o["who"])), tags = Note.cleanTags(tags))
    }

    /** Something someone has to do, said as such ("need to", "have to", "must", "remind me to",
     *  "don't forget to" …). "must be" / "must have been" are guesses, not duties. */
    private val TODO_WORDS = Regex(
        "\\b(?:needs? to|ha(?:ve|s) to(?! have been\\b)|had better|must(?! (?:be|have been)\\b)|" +
            "remind (?:me|him|her|us|them|you) to|(?:don'?t|do not) forget to|remember to|gotta)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A negation just before the duty words ("doesn't need to", "no longer has to", "never
     *  have to"): then it is not a duty. */
    private val NEGATED = Regex(
        "\\b(?:not|no|never|no longer|don'?t|doesn'?t|didn'?t|won'?t|wouldn'?t|shouldn'?t|isn'?t|aren'?t)\\s+(?:\\w+\\s+)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A small model often files a to-do as a fact (Pixel Feature check: "You need to renew your
     * passport" came back as fact). A fact worded as something someone has to do becomes a
     * [NoteKind.TODO]; every other kind is kept as the model chose it.
     */
    fun normaliseKind(kind: NoteKind, text: String): NoteKind =
        if (kind == NoteKind.FACT && TODO_WORDS.findAll(text).any { !NEGATED.containsMatchIn(text.substring(0, it.range.first)) })
            NoteKind.TODO else kind

    /**
     * The `when` of a plan or to-do whose model left it out but wrote the time into the text
     * ("Dentist with Dr Lee tomorrow at 3pm", no "when"): the time words found there
     * ([TimeResolver.phraseIn]), or null. Other kinds never get a time from their text: a fact
     * like "lives at 12 Baker Street" is not about a time.
     */
    fun whenFromText(kind: NoteKind, text: String): String? =
        if (kind.timed) TimeResolver.phraseIn(text) else null

    /** The observation and its summary: the model's "text", or when it left that out, [plainSummary]. */
    private fun observation(o: JsonObject, given: String?, source: Source, droppedReading: () -> Unit): Pair<String, Observation>? {
        val pattern = CuePattern.parse(string(o["pattern"])) ?: return null
        val who = string(o["who"])?.replace(Regex("\\s+"), " ")?.trim()?.removePrefix("[")?.removeSuffix("]")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_WHO } ?: return null
        val text = given ?: plainSummary(who, pattern)
        val conf = Confidence.parse(string(o["confidence"])) ?: return null
        val quotes = when (val q = o["quote"] ?: o["quotes"]) {
            is JsonArray -> q.map { string(it) ?: return null }
            else -> listOf(string(q) ?: return null)
        }.map { it.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”').trim() }
        if (quotes.isEmpty() || quotes.size > MAX_QUOTES || quotes.any { it.isEmpty() || it.length > MAX_QUOTE }) return null
        // Each quote's line: the first containing it whose speaker is [who] (or unnamed), else the first.
        val lines = quotes.map { q ->
            val qw = CueText.words(q)
            val hits = source.indices.filter { CueText.quoteInWords(qw, source.words[it]) }
            if (hits.isEmpty()) return null
            source.lines[hits.firstOrNull { i -> source.speakers[i].let { it == null || CueText.sameSpeaker(who, it) } } ?: hits.first()]
        }
        val named = lines.map { CueText.namedSpeaker(it.text) }
        if (named.any { it != null } && named.none { it != null && CueText.sameSpeaker(who, it) }) return null
        for (s in listOf(text, who)) if (CueText.verdict(s, quotes) != null || CueText.intentClaim(s, quotes) != null) return null
        val reading = (o["reading"] as? JsonObject)?.let { r -> reading(r).also { if (it == null) droppedReading() } }
        return text to Observation(pattern, who, quotes, lines.map { it.ts }, conf, reading, quoteWho = named)
    }

    /**
     * An observation's summary when the model wrote none: who, and the pattern's name ("Me —
     * decision"). The 1.7B model does leave it out (Pixel Feature check, 2026-10-06: pattern,
     * who, quote and confidence, no "text"), and the observation was then thrown away whole.
     * Nothing is inferred: the verified quote stays the evidence, and the label is the
     * [CuePattern] id the model chose.
     */
    fun plainSummary(who: String, pattern: CuePattern): String = "$who — ${pattern.id.replace('_', ' ')}"

    /** The chunk the model read, with each line's words and named speaker worked out once. */
    private class Source(val lines: List<HeardLine>) {
        val words: List<String> by lazy { lines.map { CueText.words(it.text) } }
        val speakers: List<String?> by lazy { lines.map { CueText.namedSpeaker(it.text) } }
        val indices get() = lines.indices
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
