package com.agent.jarvis.listen

import java.time.Instant
import java.time.ZoneId

/**
 * How people talk, read from what Jarvis heard (owner, 2026-10-07: "lean into the note-taking,
 * pattern-noticing, sociolinguistic side"). Counting, not a model: every number traces back to the
 * stored lines, and it runs instantly on a phone. Pure, JVM-tested.
 *
 * Per speaker (the transcript's labels, [Me] / a name / [Speaker 2]): share of the talking, words,
 * questions, filler words, hedges, politeness, positive vs negative words, swearing, how often they
 * were talked over ([overlap]), faster/slower and louder/quieter than their usual, laughter, and
 * their favourite words. Across everyone: who answers whom, the busiest hour, the topics. Then the
 * notable ones as plain sentences ([Report.patterns]).
 */
object ConversationInsights {

    data class Line(val ts: Long, val text: String)

    data class Speaker(
        val name: String,
        var lines: Int = 0,
        var words: Int = 0,
        var questions: Int = 0,
        var fillers: Int = 0,
        var hedges: Int = 0,
        var polite: Int = 0,
        var positive: Int = 0,
        var negative: Int = 0,
        var swears: Int = 0,
        var overlapped: Int = 0,
        var fast: Int = 0,
        var slow: Int = 0,
        var louder: Int = 0,
        var quieter: Int = 0,
        var laughs: Int = 0,
        // Literature-based counts (see [patterns] for the sources):
        var iWords: Int = 0,
        var weWords: Int = 0,
        var cognitive: Int = 0,
        var certainty: Int = 0,
        var absolutist: Int = 0,
        var tagQuestions: Int = 0,
        var intensifiers: Int = 0,
        var directives: Int = 0,
        val types: MutableSet<String> = HashSet(),
        val fillerWords: MutableMap<String, Int> = HashMap(),
        val topWords: MutableMap<String, Int> = HashMap(),
    ) {
        fun share(total: Int) = if (total == 0) 0 else Math.round(words * 100.0 / total).toInt()
        fun favourites(n: Int = 5) = topWords.entries.sortedByDescending { it.value }.take(n).map { it.key }
        fun topFiller() = fillerWords.maxByOrNull { it.value }
    }

    data class Report(
        val lines: Int,
        val words: Int,
        val speakers: List<Speaker>,
        /** Who spoke right after whom (within [CueTags.MAX_TURN_GAP_MS]): (from, to) → times. */
        val replies: Map<Pair<String, String>, Int>,
        val busiestHour: Int?,
        val topics: List<String>,
        val toJarvis: Int,
        val patterns: List<String>,
    )

    private val LABEL = Regex("^\\[([^\\]]+)]\\s*")
    private val TAG = Regex("\\[[^\\]]*]|\\((?:laughs?|laughter|sighs?|coughs?)\\)", RegexOption.IGNORE_CASE)
    private val WORD = Regex("[A-Za-z']+")
    private val FILLERS = listOf("um", "uh", "erm", "like", "you know", "i mean", "basically", "literally",
        "actually", "kind of", "sort of", "right")
    private val HEDGES = listOf("maybe", "i think", "i guess", "probably", "perhaps", "i suppose", "not sure", "kind of", "sort of")
    private val POLITE = listOf("please", "thanks", "thank you", "sorry", "excuse me", "appreciate")
    private val POSITIVE = setOf("good", "great", "love", "nice", "awesome", "happy", "glad", "amazing", "perfect",
        "cool", "excited", "fun", "beautiful", "best", "yes", "yay", "brilliant", "lovely", "fantastic")
    private val NEGATIVE = setOf("bad", "hate", "sad", "angry", "annoying", "tired", "worst", "awful", "terrible",
        "upset", "stressed", "worried", "sick", "pain", "hurt", "no", "never", "problem", "wrong", "sucks")
    private val SWEARS = setOf("fuck", "fucking", "fucked", "shit", "shitty", "damn", "bitch", "bastard", "crap", "ass", "asshole", "hell")
    private val I_WORDS = setOf("i", "me", "my", "mine", "myself", "i'm", "i've", "i'll", "i'd")
    private val WE_WORDS = setOf("we", "us", "our", "ours", "ourselves", "we're", "we've", "we'll", "let's")
    private val COGNITIVE = setOf("because", "think", "know", "realize", "realise", "understand", "reason", "cause",
        "means", "figure", "consider", "wonder", "therefore", "since")
    private val CERTAINTY = setOf("definitely", "certainly", "absolutely", "obviously", "clearly", "sure", "always", "never", "totally")
    private val ABSOLUTIST = setOf("always", "never", "nothing", "everything", "completely", "totally", "entirely",
        "constantly", "every", "all", "whole", "definitely", "absolutely")
    private val INTENSIFIERS = setOf("so", "really", "very", "super", "totally", "extremely", "insanely", "literally", "hella", "mad")
    private val TAG_Q = Regex(",\\s*(isn't it|aren't they|don't you|right|yeah|you know|isn't he|isn't she|wasn't it|didn't you|innit|no)\\?\\s*$", RegexOption.IGNORE_CASE)
    private val DIRECTIVE = Regex("^(can you|could you|would you|will you|please|let's|go|stop|give|send|tell|bring|take|don't)\\b", RegexOption.IGNORE_CASE)
    private val QUESTION_START = Regex("^(who|what|when|where|why|how|which|is|are|do|does|did|can|could|will|would|should|have|has)\\b", RegexOption.IGNORE_CASE)
    private val STOP = setOf("that", "this", "with", "have", "just", "like", "what", "they", "there", "then", "them", "their",
        "about", "would", "could", "should", "really", "yeah", "okay", "know", "think", "going", "gonna", "were", "been",
        "from", "your", "when", "will", "because", "want", "some", "dont", "it's", "that's", "i'm", "don't", "you're",
        "here", "well", "right", "said", "also", "only", "very", "much", "more", "into", "does", "didn't", "can't",
        "than", "where", "which", "while", "these", "those", "thing", "things", "something", "anything", "actually",
        "basically", "literally", "maybe", "probably", "jarvis", "gotta", "wanna", "kind", "sort", "mean", "it'll")

    /** The speaker label of a stored line, or null when it was stored without one. */
    fun speakerOf(text: String): String? = LABEL.find(text)?.groupValues?.get(1)?.trim()?.takeIf {
        !it.startsWith("pause", true) && it.lowercase() !in setOf("overlap", "fast", "slow", "louder", "quieter")
    }

    private fun count(lower: String, phrase: String): Int =
        Regex("\\b${Regex.escape(phrase)}\\b").findAll(lower).count()

    fun analyse(lines: List<Line>, zone: ZoneId = ZoneId.systemDefault()): Report {
        val bySpeaker = LinkedHashMap<String, Speaker>()
        val replies = HashMap<Pair<String, String>, Int>()
        val hours = IntArray(24)
        val allWords = HashMap<String, Int>()
        var toJarvis = 0
        var prev: Pair<String, Long>? = null
        for (l in lines.sortedBy { it.ts }) {
            val label = speakerOf(l.text) ?: CueTags.UNKNOWN
            var body = l.text.replaceFirst(LABEL, "")
            if (body.contains(MemoryNoteRequest.TO_JARVIS.removePrefix("[Me] ")) ) { toJarvis++; continue }
            val tags = TAG.findAll(body).map { it.value.lowercase() }.toList()
            body = body.replace(TAG, " ").replace(Regex("\\s+"), " ").trim()
            val words = WORD.findAll(body).map { it.value.lowercase() }.toList()
            if (words.isEmpty()) continue
            val s = bySpeaker.getOrPut(label) { Speaker(label) }
            val lower = " " + words.joinToString(" ") + " "
            s.lines++; s.words += words.size
            if (body.trimEnd().endsWith("?") || QUESTION_START.containsMatchIn(body)) s.questions++
            for (f in FILLERS) { val n = count(lower, f); if (n > 0) { s.fillers += n; s.fillerWords.merge(f, n, Int::plus) } }
            s.hedges += HEDGES.sumOf { count(lower, it) }
            s.polite += POLITE.sumOf { count(lower, it) }
            s.positive += words.count { it in POSITIVE }
            s.negative += words.count { it in NEGATIVE }
            s.swears += words.count { it in SWEARS }
            if (tags.any { it == CueTags.OVERLAP }) s.overlapped++
            if (tags.any { it == CueTags.FAST }) s.fast++
            if (tags.any { it == CueTags.SLOW }) s.slow++
            if (tags.any { it == CueTags.LOUDER }) s.louder++
            if (tags.any { it == CueTags.QUIETER }) s.quieter++
            if (tags.any { "laugh" in it }) s.laughs++
            s.iWords += words.count { it in I_WORDS }
            s.weWords += words.count { it in WE_WORDS }
            s.cognitive += words.count { it in COGNITIVE }
            s.certainty += words.count { it in CERTAINTY }
            s.absolutist += words.count { it in ABSOLUTIST }
            s.intensifiers += words.count { it in INTENSIFIERS }
            if (TAG_Q.containsMatchIn(body)) s.tagQuestions++
            if (DIRECTIVE.containsMatchIn(body)) s.directives++
            s.types += words
            for (w in words) if (w.length >= 4 && w !in STOP && w !in SWEARS) {
                s.topWords.merge(w, 1, Int::plus); allWords.merge(w, 1, Int::plus)
            }
            hours[Instant.ofEpochMilli(l.ts).atZone(zone).hour]++
            prev?.let { (who, ts) ->
                if (who != label && l.ts - ts <= CueTags.MAX_TURN_GAP_MS) replies.merge(who to label, 1, Int::plus)
            }
            prev = label to l.ts
        }
        val speakers = bySpeaker.values.sortedByDescending { it.words }
        val total = speakers.sumOf { it.words }
        val busiest = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 }
        val topics = allWords.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(8).map { it.key }
        val report = Report(speakers.sumOf { it.lines }, total, speakers, replies, busiest, topics, toJarvis, emptyList())
        return report.copy(patterns = patterns(report))
    }

    /** The notable things, most telling first, as plain sentences. */
    fun patterns(r: Report): List<String> {
        val out = ArrayList<String>()
        val labelled = r.speakers.filter { it.name != CueTags.UNKNOWN }
        if (r.words == 0) return listOf("Nothing heard in this period yet.")
        if (labelled.size >= 2) {
            val top = labelled.first()
            out += "${who(top.name)} did most of the talking: ${top.share(r.words)}% of the words."
        }
        r.speakers.filter { it.overlapped >= 2 }.maxByOrNull { it.overlapped }?.let {
            out += "${who(it.name)} got talked over ${it.overlapped} times."
        }
        r.replies.entries.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let { (pair, n) ->
            out += "${who(pair.second)} answered ${whom(pair.first)} most often ($n times)."
        }
        for (s in r.speakers.filter { it.words >= 30 }) {
            val per100 = s.fillers * 100.0 / s.words
            s.topFiller()?.takeIf { per100 >= 3 }?.let { (f, n) ->
                out += "${who(s.name)} used filler words a lot (${"%.0f".format(per100)} per 100 words), \"$f\" most ($n times)."
            }
            if (s.lines >= 5 && s.questions * 3 >= s.lines) out += "${who(s.name)} asked a lot of questions (${s.questions} of ${s.lines} lines)."
            if (s.hedges >= 3) out += "${who(s.name)} hedged often (\"maybe\", \"I think\": ${s.hedges} times)."
            if (s.positive + s.negative >= 5) {
                val mood = when {
                    s.positive >= s.negative * 2 -> "mostly upbeat"
                    s.negative >= s.positive * 2 -> "mostly down or frustrated"
                    else -> "mixed"
                }
                out += "${who(s.name)} sounded $mood (${s.positive} positive words, ${s.negative} negative)."
            }
            if (s.swears >= 3) out += "${who(s.name)} swore ${s.swears} times."
            if (s.polite >= 3) out += "${who(s.name)} was polite: ${s.polite} pleases, thanks or sorries."
            if (s.laughs >= 2) out += "${who(s.name)} laughed ${s.laughs} times."
            if (s.fast >= 3) out += "${who(s.name)} talked faster than usual ${s.fast} times."
            if (s.louder >= 3) out += "${who(s.name)} got louder than usual ${s.louder} times."
        }
        out += literature(r)
        r.busiestHour?.let { out += "Busiest hour: ${hourText(it)}." }
        if (r.topics.isNotEmpty()) out += "Talked about most: ${r.topics.take(5).joinToString(", ")}."
        if (r.toJarvis > 0) out += "You asked Jarvis ${r.toJarvis} thing${if (r.toJarvis == 1) "" else "s"}."
        return out
    }

    /**
     * Findings named after the research they come from. Rates are per 100 words; thresholds are
     * rough everyday-conversation levels, so these are tendencies, never diagnoses.
     */
    fun literature(r: Report): List<String> {
        val out = ArrayList<String>()
        fun rate(n: Int, s: Speaker) = n * 100.0 / s.words
        for (s in r.speakers.filter { it.words >= 60 && it.name != CueTags.UNKNOWN }) {
            val w = who(s.name)
            val i = rate(s.iWords, s); val we = rate(s.weWords, s)
            if (i >= 7 && i > we * 4) out += "$w used \"I/me/my\" a lot (${f1(i)} per 100 words): in Pennebaker's research heavy self-focus goes with stress or a lower-power position in the talk."
            if (we >= 2.5) out += "$w said \"we/us/our\" often (${f1(we)} per 100 words), which Pennebaker links to shared identity and closeness."
            if (rate(s.absolutist, s) >= 2.5) out += "$w used absolute words (always, never, nothing…) ${s.absolutist} times; Al-Mosaiwi & Johnstone (2018) found these more common when people feel anxious or low."
            if (rate(s.cognitive, s) >= 3) out += "$w used a lot of reasoning words (because, think, know): Pennebaker reads that as working something through."
            if (s.tagQuestions >= 2) out += "$w used tag questions (\"…, right?\") ${s.tagQuestions} times: Lakoff (1975) counts them as seeking agreement or softening a claim."
            if (rate(s.intensifiers, s) >= 4) out += "$w leaned on intensifiers (so, really, super…) ${s.intensifiers} times, an emphasis style Lakoff and Tagliamonte describe."
            if (rate(s.certainty, s) >= 2 && s.hedges <= s.certainty / 3) out += "$w spoke with high certainty and few hedges: assertive in Holmes' terms."
            if (s.directives >= 4) out += "$w gave ${s.directives} requests or directives (speech acts in Searle's sense): steering what happens."
            val ttr = s.types.size.toDouble() / s.words
            if (s.words >= 150) out += "$w's vocabulary: ${s.types.size} different words in ${s.words} (" +
                (if (ttr >= 0.5) "varied — Bernstein's 'elaborated' end" else if (ttr < 0.3) "repetitive — Bernstein's 'restricted', in-group end" else "average variety") + ")."
        }
        // Tannen (1984, 2005): high-involvement (overlap, fast, latching) vs high-considerateness (pauses, no overlap).
        if (r.lines >= 12) {
            val overlaps = r.speakers.sumOf { it.overlapped }
            val fast = r.speakers.sumOf { it.fast }
            val per = (overlaps + fast) * 100.0 / r.lines
            out += when {
                per >= 25 -> "Conversation style: high-involvement (lots of overlap and pace) — Tannen's term; overlap here is usually enthusiasm, not rudeness."
                per <= 5 -> "Conversation style: high-considerateness (turns wait their place) — Tannen's term."
                else -> "Conversation style: in between Tannen's high-involvement and high-considerateness."
            }
        }
        // Giles' Communication Accommodation Theory: do the two main speakers take up each other's words?
        val named = r.speakers.filter { it.name != CueTags.UNKNOWN && it.words >= 60 }
        if (named.size >= 2) {
            val a = named[0].topWords.keys; val b = named[1].topWords.keys
            val shared = a.intersect(b).size.toDouble() / (minOf(a.size, b.size).coerceAtLeast(1))
            if (shared >= 0.35) out += "${who(named[0].name)} and ${whom(named[1].name)} shared a lot of vocabulary (${(shared * 100).toInt()}% overlap): convergence in Giles' accommodation theory, usually a sign of rapport."
            else if (shared < 0.1) out += "${who(named[0].name)} and ${whom(named[1].name)} barely shared vocabulary: divergence in Giles' terms (different topics, or keeping distance)."
        }
        return out
    }

    private fun f1(d: Double) = "%.1f".format(d)

    /** A note's text: the period, who talked, and the patterns. */
    fun noteText(r: Report, period: String): String = buildString {
        append("Conversation insights ($period): ${r.lines} lines, ${r.words} words")
        if (r.speakers.isNotEmpty()) append(", ").append(r.speakers.take(4).joinToString(", ") { "${it.name} ${it.share(r.words)}%" })
        append(". ")
        append(r.patterns.joinToString(" "))
    }.trim()

    private fun who(name: String) = if (name == CueTags.ME) "You" else name
    private fun whom(name: String) = if (name == CueTags.ME) "you" else name
    private fun hourText(h: Int): String {
        fun f(x: Int) = when { x == 0 -> "12 am"; x < 12 -> "$x am"; x == 12 -> "12 pm"; else -> "${x - 12} pm" }
        return "${f(h)}–${f((h + 1) % 24)}"
    }

    // ── Asked in words ───────────────────────────────────────────────────────────────────────

    private val ASKED = Regex(
        "\\b(conversation insights|insights|how (did|do) (i|we) (talk|sound)|who (talks|talked|spoke|speaks) (the )?most|" +
            "my (talking|speech|speaking) (patterns|habits)|patterns in (my|our) (talk|conversations?|speech)|" +
            "(how many|which) filler words|how much did i (talk|swear))\\b",
        RegexOption.IGNORE_CASE,
    )

    enum class Period(val label: String, val days: Int) { TODAY("today", 1), WEEK("this week", 7), MONTH("this month", 30) }

    /** The period asked about when [request] asks for conversation insights, else null. */
    fun asked(request: String): Period? {
        if (!ASKED.containsMatchIn(request)) return null
        val t = request.lowercase()
        return when {
            "month" in t -> Period.MONTH
            "week" in t -> Period.WEEK
            else -> Period.TODAY
        }
    }

    /** Spoken/typed answer for [r] over [p]. */
    fun answer(r: Report, p: Period): String =
        if (r.words == 0) "I haven't heard any conversation ${p.label} yet."
        else "Here's how the talking went ${p.label}: " + r.patterns.take(6).joinToString(" ")
}
