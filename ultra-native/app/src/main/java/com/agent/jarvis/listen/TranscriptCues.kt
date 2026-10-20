package com.agent.jarvis.listen

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** A word and when it was said, ms from the start of its utterance's audio. */
data class TimedWord(val text: String, val t0Ms: Long, val t1Ms: Long)

/** [startMs, endMs) on an utterance's clock (or wall clock, where said). */
data class Span(val startMs: Long, val endMs: Long) {
    val ms: Long get() = endMs - startMs
}

/**
 * Timing and level from an utterance's own audio. Pure, JVM-tested.
 *
 * "Speech" here is a frame the ambient energy gate would call loud ([gateDb], the same rule as
 * [Segmenter]), so pauses are measured with the threshold that cut the utterance in the first place.
 */
object CueAudio {
    /** dBFS per [frame]-sample frame (the last, shorter one included). */
    fun frameDb(pcm: ShortArray, frame: Int = AmbientConfig.FRAME): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = (pcm.size + frame - 1) / frame
        return FloatArray(n) { i ->
            Segmenter.dbfs(pcm.copyOfRange(i * frame, minOf(pcm.size, (i + 1) * frame)))
        }
    }

    /**
     * Median voice pitch (Hz) over the speech in [span] of [pcm] (16 kHz), or null when fewer than
     * [MIN_VOICED] frames are clearly voiced. Per 40 ms frame (20 ms hop): normalised autocorrelation
     * over 75-400 Hz lags; a frame counts only if its peak is at least [VOICED]. A measurement, not a
     * reading of mood: it is only ever compared with the same speaker's own usual pitch.
     */
    fun pitchHz(pcm: ShortArray, span: Span, rate: Int = AmbientConfig.SAMPLE_RATE): Float? {
        val win = rate / 25; val hop = rate / 50
        val minLag = rate / 400; val maxLag = rate / 75
        val from = (span.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
        val to = (span.endMs * rate / 1000).toInt().coerceIn(from, pcm.size)
        val found = ArrayList<Float>()
        var s = from
        while (s + win + maxLag <= to) {
            var energy = 0.0
            for (i in s until s + win) energy += pcm[i].toDouble() * pcm[i]
            if (energy / win > 1e4) {   // skip near-silent frames (RMS below ~100 of 32768)
                var bestLag = -1; var best = 0.0
                for (lag in minLag..maxLag) {
                    var num = 0.0; var e2 = 0.0
                    for (i in s until s + win) { num += pcm[i].toDouble() * pcm[i + lag]; e2 += pcm[i + lag].toDouble() * pcm[i + lag] }
                    val r = num / kotlin.math.sqrt(energy * e2 + 1e-9)
                    if (r > best) { best = r; bestLag = lag }
                }
                if (best >= VOICED && bestLag > 0) found += rate.toFloat() / bestLag
            }
            s += hop
        }
        if (found.size < MIN_VOICED) return null
        found.sort()
        return found[found.size / 2]
    }

    /** Autocorrelation peak a frame needs to count as voiced. */
    const val VOICED = 0.6
    /** Voiced frames needed before a pitch is reported (~0.2 s of voice). */
    const val MIN_VOICED = 10

    /** The loudness a frame must pass to count as speech, given the room's noise floor. */
    fun gateDb(floorDb: Float): Float =
        max(floorDb + AmbientConfig.GATE_ABOVE_FLOOR_DB, AmbientConfig.GATE_ABS_MIN_DB)

    /** First speech frame's start to last speech frame's end; null when there's none. */
    fun speechSpan(db: FloatArray, gate: Float, frameMs: Int = AmbientConfig.FRAME_MS): Span? {
        val first = db.indexOfFirst { it > gate }
        if (first < 0) return null
        val last = db.indexOfLast { it > gate }
        return Span(first.toLong() * frameMs, (last + 1).toLong() * frameMs)
    }

    /** Runs of non-speech frames of at least [minMs] between the first and last speech frame. */
    fun silences(db: FloatArray, gate: Float, minMs: Long, frameMs: Int = AmbientConfig.FRAME_MS): List<Span> {
        val out = ArrayList<Span>()
        val first = db.indexOfFirst { it > gate }
        val last = db.indexOfLast { it > gate }
        if (first < 0) return out
        var runStart = -1
        for (i in first..last) {
            val quiet = db[i] <= gate
            if (quiet && runStart < 0) runStart = i
            if (!quiet && runStart >= 0) {
                val s = Span(runStart.toLong() * frameMs, i.toLong() * frameMs)
                if (s.ms >= minMs) out += s
                runStart = -1
            }
        }
        return out
    }

    /** Mean power of the speech frames, in dB; null when there are none. */
    fun speechLevelDb(db: FloatArray, gate: Float): Float? {
        var sum = 0.0; var n = 0
        for (d in db) if (d > gate) { sum += Math.pow(10.0, d / 10.0); n++ }
        return if (n == 0) null else (10 * log10(sum / n)).toFloat()
    }

    /** Silences between whisper's VAD speech stretches of at least [minMs]. */
    fun vadGaps(vad: List<Span>, minMs: Long): List<Span> {
        val s = vad.sortedBy { it.startMs }
        return (1 until s.size).map { Span(s[it - 1].endMs, s[it].startMs) }.filter { it.ms >= minMs }
    }

    /** The pause before an utterance whose speech starts at [startAt], after the previous one's
     * speech ended at [prevEndAt] (wall clock ms; 0 = none yet): only between
     * [CueTags.MIN_PAUSE_MS] and [CueTags.MAX_TURN_GAP_MS], else null. */
    fun turnGap(prevEndAt: Long, startAt: Long): Long? {
        if (prevEndAt <= 0L) return null
        return (startAt - prevEndAt).takeIf { it in CueTags.MIN_PAUSE_MS..CueTags.MAX_TURN_GAP_MS }
    }

    /** Union of [a] and [b] (overlapping stretches joined), keeping those of at least [minMs]. */
    fun union(a: List<Span>, b: List<Span>, minMs: Long): List<Span> {
        val all = (a + b).filter { it.ms > 0 }.sortedBy { it.startMs }
        val out = ArrayList<Span>()
        for (s in all) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) out[out.size - 1] = Span(last.startMs, max(last.endMs, s.endMs))
            else out += s
        }
        return out.filter { it.ms >= minMs }
    }
}

/**
 * The pause before each utterance, from one utterance to the next as ambient listening cuts
 * them: the silence between the previous utterance's last speech frame and this one's first
 * ([CueAudio.turnGap]). Every utterance moves the clock on, stored or not. One caller at a time
 * (the whisper worker; the debug feature check uses its own). Pure, JVM-tested.
 */
class TurnGaps {
    private var lastSpeechEndAt = 0L

    /** [db]: the utterance's frame dB; [floorDb]: the noise floor it was cut against; [endedAt]:
     *  when its audio ended (ms, any clock that only goes forward); [audioMs]: its length. */
    fun next(db: FloatArray, floorDb: Float, endedAt: Long, audioMs: Long): Long? {
        val span = CueAudio.speechSpan(db, CueAudio.gateDb(floorDb)) ?: return null
        val audioStart = endedAt - audioMs
        val gap = CueAudio.turnGap(lastSpeechEndAt, audioStart + span.startMs)
        lastSpeechEndAt = audioStart + span.endMs
        return gap
    }
}

/**
 * A running mean and variance that keeps adapting: after [CAP] samples each new one weighs 1/CAP,
 * so a voice that changes over weeks (a cold, a new phone position) moves its baseline with it.
 * Pure, JVM-tested.
 */
data class Baseline(val n: Int = 0, val mean: Double = 0.0, val variance: Double = 0.0) {
    val sd: Double get() = sqrt(max(0.0, variance))

    fun add(x: Double): Baseline {
        if (x.isNaN() || x.isInfinite()) return this
        val n2 = minOf(n + 1, CAP)
        val w = 1.0 / n2
        val d = x - mean
        return Baseline(n2, mean + w * d, (1 - w) * (variance + w * d * d))
    }

    fun encode(): String = String.format(Locale.ROOT, "%d,%.6f,%.6f", n, mean, variance)

    companion object {
        const val CAP = 100
        fun decode(s: String?): Baseline {
            val p = s?.split(',') ?: return Baseline()
            if (p.size != 3) return Baseline()
            return Baseline(p[0].toIntOrNull() ?: return Baseline(), p[1].toDoubleOrNull() ?: return Baseline(),
                p[2].toDoubleOrNull() ?: return Baseline())
        }
    }
}

/**
 * The transcript's cue tags and the tagged line. Pure, JVM-tested.
 *
 * A stored tagged line is "[label] " + body, e.g.
 * `[Sam] [pause 2.1s] [fast] so um [pause 0.9s] we could [overlap] go tomorrow (laughs)`.
 * The label is added when the line is read ([label]: a rename applies to old lines too); the body
 * is stored. A line stored with speaker tags off has no label at all ([UNLABELLED], [read]).
 * The plain text (what search and the sentence index see) never carries any of this.
 */
object CueTags {
    const val MIN_PAUSE_MS = 700L
    /** A gap between two utterances longer than this is not a pause in a conversation. */
    const val MAX_TURN_GAP_MS = 10_000L
    const val ME = "Me"
    const val UNKNOWN = "Unknown"
    const val OVERLAP = "[overlap]"
    const val FAST = "[fast]"
    const val SLOW = "[slow]"
    const val LOUDER = "[louder]"
    const val QUIETER = "[quieter]"
    const val HIGHER = "[higher]"
    const val LOWER = "[lower]"

    /** A baseline needs this many lines before a line is called fast/slow/louder/quieter. */
    const val MIN_BASELINE = 8
    /** Rate: at least this many words over this much speech before it is measured at all. */
    const val RATE_MIN_WORDS = 4
    const val RATE_MIN_SPEECH_MS = 1_500L

    fun pause(ms: Long): String = String.format(Locale.ROOT, "[pause %.1fs]", ms / 1000.0)

    /** "Me" for the owner, the name the owner gave, else "Speaker N"; "Unknown" with nothing. */
    fun label(owner: Boolean, name: String?, num: Int?): String = when {
        owner -> ME
        !name.isNullOrBlank() -> name.trim()
        num != null && num > 0 -> "Speaker $num"
        else -> UNKNOWN
    }

    /** A name as the owner typed it, made safe for a `[label]`: no brackets or line breaks, at
     *  most 32 characters; null when nothing is left, or when it is one of our own labels ("Me"
     *  is the owner to the librarian; "Speaker 2" / "Unknown" would pass one voice off as another). */
    fun cleanName(raw: String): String? =
        raw.replace(Regex("[\\[\\]\\r\\n\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(32).trim()
            .takeIf { it.isNotEmpty() && !RESERVED.matches(it) }

    private val RESERVED = Regex("(?i)me|unknown|speaker\\s*\\d*")

    fun line(label: String, body: String): String = "[$label] $body".trim()

    /** heard_tag.voice of a line stored with speaker tags off: no speaker label when read (0 is
     *  "no voiceprint matched", read as [UNKNOWN]; above 0 a voice). */
    const val UNLABELLED = -1L

    /** The tagged line for a stored [body] and its [voiceId]: the body alone when it was stored
     *  with speaker tags off, else [line] with [label] (the voice's label now; null = no voice,
     *  or one since forgotten: [UNKNOWN]). */
    fun read(body: String, voiceId: Long, label: String?): String =
        if (voiceId == UNLABELLED) body.trim() else line(label ?: UNKNOWN, body)

    /** "[fast]" / "[slow]" for [wordsPerSec] against the speaker's [b], or null. */
    fun rate(b: Baseline, wordsPerSec: Double): String? {
        if (b.n < MIN_BASELINE || b.mean <= 0) return null
        val margin = max(1.5 * b.sd, 0.2 * b.mean)
        return when {
            wordsPerSec > b.mean + margin -> FAST
            wordsPerSec < b.mean - margin -> SLOW
            else -> null
        }
    }

    /** "[louder]" / "[quieter]" for [levelDb] against the speaker's [b] (dB), or null. */
    fun loudness(b: Baseline, levelDb: Double): String? {
        if (b.n < MIN_BASELINE) return null
        val margin = max(1.5 * b.sd, 4.0)
        return when {
            levelDb > b.mean + margin -> LOUDER
            levelDb < b.mean - margin -> QUIETER
            else -> null
        }
    }

    /** "[higher]" / "[lower]" for [hz] against the speaker's own usual pitch [b] (Hz), or null. */
    fun pitch(b: Baseline, hz: Double): String? {
        if (b.n < MIN_BASELINE || b.mean <= 0) return null
        val margin = max(1.5 * b.sd, 0.12 * b.mean)   // ~2 semitones at least
        return when {
            hz > b.mean + margin -> HIGHER
            hz < b.mean - margin -> LOWER
            else -> null
        }
    }

    /** Words per second of speech: [words] over [speechMs]; null when too little to judge. */
    fun wordsPerSecond(words: Int, speechMs: Long): Double? =
        if (words < RATE_MIN_WORDS || speechMs < RATE_MIN_SPEECH_MS) null else words * 1000.0 / speechMs

    /**
     * The stored body of a tagged line.
     *
     * [lead]: tags before the words, in order (a pause before the line, then [fast] etc.).
     * [pauses] and [overlapAtMs] are placed at the word boundary nearest to them (whisper's word
     * times are rough; the durations come from the audio). Without [words] (no timing from
     * whisper) the body is [plain] with only the lead tags. Non-speech events whisper wrote are
     * normalised ([events]); fillers are kept as whisper wrote them.
     */
    fun body(
        plain: String,
        words: List<TimedWord>,
        pauses: List<Span>,
        overlapAtMs: Long?,
        lead: List<String>,
    ): String {
        // Our tags travel through [events] as placeholders, so its bracket rewriting can't touch them.
        val tags = ArrayList<String>()
        fun mark(tag: String): String = "\u0001${tags.size}\u0002".also { tags += tag }
        val sb = StringBuilder()
        for (t in lead) sb.append(mark(t)).append(' ')
        if (words.isEmpty()) {
            sb.append(plain.replace(CTRL, "").trim())
        } else {
            // inserts[k] = tags before words[k]
            val inserts = HashMap<Int, MutableList<String>>()
            val pauseAt = HashMap<Int, Long>()
            if (words.size >= 2) for (p in pauses) {
                val k = nearestBoundary(words, (p.startMs + p.endMs) / 2)
                pauseAt[k] = max(pauseAt[k] ?: 0L, p.ms)
            }
            for ((k, ms) in pauseAt) inserts.getOrPut(k) { ArrayList() } += pause(ms)
            if (overlapAtMs != null) {
                val k = if (words.size >= 2) nearestBoundary(words, overlapAtMs) else 0
                inserts.getOrPut(k) { ArrayList() } += OVERLAP
            }
            words.forEachIndexed { i, w ->
                inserts[i]?.forEach { sb.append(mark(it)).append(' ') }
                sb.append(w.text.replace(CTRL, "")).append(' ')
            }
        }
        // (whisper text had \u0001/\u0002 removed above, so it cannot fake a placeholder)
        val text = events(sb.toString())
        val restored = Regex("\u0001(\\d+)\u0002").replace(text) { tags.getOrNull(it.groupValues[1].toInt()) ?: "" }
        return restored.replace(Regex("\\s+"), " ").trim()
    }

    private val CTRL = Regex("[\u0001\u0002]")

    /** Index k (1..n-1) of the boundary before words[k] whose time is nearest [ms]. */
    internal fun nearestBoundary(words: List<TimedWord>, ms: Long): Int {
        var best = 1; var bestD = Long.MAX_VALUE
        for (k in 1 until words.size) {
            val b = (words[k - 1].t1Ms + words[k].t0Ms) / 2
            val d = abs(b - ms)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    private val LAUGH = Regex("^(laugh|laughs|laughing|laughter|chuckle|chuckles|chuckling|giggle|giggles|giggling)$")
    private val DROP = Regex("^(blank_audio|blank audio|silence|no speech|inaudible_audio)$")

    /**
     * Non-speech events as whisper writes them ("[Laughter]", "(laughing)", "*laughs*",
     * "[MUSIC]") become lower-case "(...)", laughter as "(laughs)"; "[BLANK_AUDIO]"-style
     * markers are dropped. Square brackets never survive, so they can only mean our own tags.
     */
    fun events(s: String): String {
        fun norm(inner: String): String {
            val e = inner.trim().lowercase(Locale.ROOT)
            return when {
                LAUGH.matches(e) -> "(laughs)"
                DROP.matches(e) -> ""
                else -> "($e)"
            }
        }
        var out = Regex("\\[\\s*([^\\[\\]\\u0001\\u0002]{1,40}?)\\s*]").replace(s) { norm(it.groupValues[1]) }
        // letters only between the stars, so "5 * 3 * 2" stays arithmetic
        out = Regex("\\*\\s*(\\p{L}[\\p{L} '_-]{0,39}?)\\s*\\*").replace(out) { norm(it.groupValues[1]) }
        out = Regex("\\(\\s*([^()\\u0001\\u0002]{1,40}?)\\s*\\)").replace(out) {
            val e = it.groupValues[1].trim().lowercase(Locale.ROOT)
            if (LAUGH.matches(e)) "(laughs)" else if (DROP.matches(e)) "" else it.value
        }
        return out.replace("[", "(").replace("]", ")")
    }
}
