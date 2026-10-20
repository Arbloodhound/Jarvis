package com.agent.ultra.bench

import java.util.Locale

/**
 * Scoring and report lines for the debug-only "Feature check" (src/debug/.../FeatureCheck.kt).
 * Lives in main only so the JVM tests can reach it, like [KitReport]; no release code path
 * references it, so R8 drops it. Pure, JVM-tested, no Android types.
 */
object FeatureGrade {

    enum class Verdict { PASS, WARN, FAIL, MISSING }

    /**
     * One feature's outcome. [numbers]: what was measured, short ("5/6 correct, 7.2 tok/s").
     * [meaning]: what that means in plain words. [detail]: the longer block for the results file.
     */
    data class Result(
        val feature: String,
        val verdict: Verdict,
        val numbers: String,
        val meaning: String,
        val detail: List<String> = emptyList(),
    )

    /** `PASS     Thinker model: 5/6 correct  — answers simple checkable questions right`. */
    fun line(r: Result): String =
        "${r.verdict.name.padEnd(7)} ${r.feature}: ${r.numbers.ifBlank { "-" }}  — ${r.meaning}"

    /** Counts line plus a fixed-width table, for the end of the results file. */
    fun summary(results: List<Result>): List<String> {
        val counts = Verdict.entries.joinToString(" · ") { v -> "${v.name} ${results.count { it.verdict == v }}" }
        val w = (results.maxOfOrNull { it.feature.length } ?: 7).coerceIn(7, 40)
        return buildList {
            add(counts)
            add("RESULT  " + "FEATURE".padEnd(w) + "  MEASURED")
            for (r in results) add(r.verdict.name.padEnd(8) + r.feature.take(w).padEnd(w) + "  " + r.numbers)
        }
    }

    /** PASS at or above [pass], WARN at or above [warn], else FAIL; no cases is FAIL. */
    fun byScore(correct: Int, total: Int, pass: Double = 0.8, warn: Double = 0.5): Verdict {
        if (total <= 0) return Verdict.FAIL
        val f = correct.toDouble() / total
        return when {
            f >= pass -> Verdict.PASS
            f >= warn -> Verdict.WARN
            else -> Verdict.FAIL
        }
    }

    /** Worst of several verdicts (FAIL > WARN > PASS); MISSING only when all are MISSING. */
    fun worst(vararg v: Verdict): Verdict {
        val present = v.filter { it != Verdict.MISSING }
        if (present.isEmpty()) return Verdict.MISSING
        return when {
            Verdict.FAIL in present -> Verdict.FAIL
            Verdict.WARN in present -> Verdict.WARN
            else -> Verdict.PASS
        }
    }

    fun pct(correct: Int, total: Int): String = if (total <= 0) "0%" else "${correct * 100 / total}%"

    fun f1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    fun f2(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    // ── Auto-graded answers ────────────────────────────────────────────────────────────────

    /** How an answer is checked. Everything is case-insensitive and ignores a think block. */
    sealed class Check {
        /** The number appears as a whole number ("391", "391.", "= 391"; not "3910"). */
        data class Number(val value: String) : Check()
        /** The answer, stripped of punctuation and spaces at the ends, is exactly [word]. */
        data class Exact(val word: String) : Check()
        /** [word] appears as a whole word and none of [not] do. */
        data class Word(val word: String, val not: List<String> = emptyList()) : Check()
        /** Any one of [checks] passes (e.g. "4:30" or "16:30"). */
        data class AnyOf(val checks: List<Check>) : Check()
    }

    /** The visible answer: reasoning is removed the way chat history removes it (a closed
     *  think block, everything up to a lone closing tag, and an unclosed think block to the end). */
    fun visible(answer: String): String = com.agent.ultra.agent.ThinkBudget.stripForHistory(answer)

    private fun hasWord(text: String, word: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun passes(answer: String, check: Check): Boolean {
        val a = visible(answer)
        return when (check) {
            is Check.Number -> Regex("(?<![\\d.,])" + Regex.escape(check.value) + "(?!\\d|[.,]\\d)").containsMatchIn(a)
            is Check.Exact -> a.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() } == check.word.lowercase(Locale.ROOT)
            is Check.Word -> hasWord(a, check.word) && check.not.none { hasWord(a, it) }
            is Check.AnyOf -> check.checks.any { passes(a, it) }
        }
    }

    // ── Word error rate ────────────────────────────────────────────────────────────────────

    /** Lowercase words; punctuation and hyphens split words, apostrophes are dropped ("it's" = "its"). */
    fun words(s: String): List<String> =
        s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace("'", "")
            .split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Word-level edit distance / reference length. 0 = perfect; can exceed 1. Empty reference: 0 or 1. */
    fun wordErrorRate(reference: String, hypothesis: String): Double {
        val r = words(reference)
        val h = words(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                val sub = prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[h.size].toDouble() / r.size
    }

    /** Linear-interpolation resample (the TTS voice is 22.05 kHz, whisper wants 16 kHz). */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0)
        if (fromRate == toRate || samples.isEmpty()) return samples.copyOf()
        val n = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val step = fromRate.toDouble() / toRate
        return FloatArray(n) { i ->
            val x = i * step
            val k = x.toInt().coerceAtMost(samples.size - 1)
            val k1 = (k + 1).coerceAtMost(samples.size - 1)
            val t = (x - k).toFloat()
            samples[k] * (1 - t) + samples[k1] * t
        }
    }

    // ── No-diagnosis filter ────────────────────────────────────────────────────────────────

    /** What the pattern notes must never say (the owner's rule: observations, not diagnoses). */
    val BANNED = listOf("diagnos", "narcissist", "you are lying")

    /** The banned fragments found in [text] (case-insensitive substring), in [BANNED] order. */
    fun banned(text: String): List<String> {
        val t = text.lowercase(Locale.ROOT)
        return BANNED.filter { it in t }
    }

    // ── Transcript cues ────────────────────────────────────────────────────────────────────

    /** The seconds of every "[pause 1.6s]" tag in a tagged transcript line, in order. */
    fun pausesIn(line: String): List<Double> =
        Regex("\\[pause (\\d+(?:\\.\\d+)?)s]").findAll(line).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

    /** Word times usable for cues: each word ends no earlier than it starts, and no word starts
     *  before the previous one started. [times]: (start ms, end ms) per word. Empty: false. */
    fun timesIncrease(times: List<Pair<Long, Long>>): Boolean =
        times.isNotEmpty() && times.all { it.second >= it.first } &&
            times.zipWithNext().all { (a, b) -> b.first >= a.first }

        // ── Ranking ────────────────────────────────────────────────────────────────────────────

    /** 1-based rank of the first hit containing [expected] (case-insensitive), or null. */
    /** The brain says it has no memory, can't remember or can't keep notes: what it said before it
     *  was told about its memory (owner, 2026-10-07). */
    private val DENIES_MEMORY = Regex(
        "\\bi(?:'m| am)?\\s+(?:do not|don't|cannot|can't|not able to|unable to|have no way to)\\s+" +
            "(have|access|store|keep|retain|remember|recall|save|hold)\\b[^.]{0,40}\\b(memor|notes?|past conversations?|previous conversations?|personal)" +
            "|\\bi (have|'ve got) no (memory|memories|notes|access to (your|any) (past|previous|personal))" +
            "|\\b(no|without) (persistent |long-term )?memory\\b",
        RegexOption.IGNORE_CASE,
    )

    fun deniesMemory(answer: String): Boolean = DENIES_MEMORY.containsMatchIn(visible(answer).replace('’', '\''))

    /** Which of [words] the answer contains (whole words, case and punctuation aside). */
    fun mentions(answer: String, words: Collection<String>): List<String> {
        val have = words(visible(answer)).toSet()
        return words.filter { w -> words(w).firstOrNull()?.let { it in have } == true }
    }

    /**
     * Whether [answer] states the time [nowMs] (in [zone]) to within [slackMin] minutes: "2:05",
     * "14:05", "2 pm", "five past two" not attempted (numbers only). Pure.
     */
    fun saysTime(answer: String, nowMs: Long, zone: java.util.TimeZone, slackMin: Int = 10): Boolean {
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val t = visible(answer).lowercase()
        fun near(h24: Int, m: Int): Boolean {
            val d = kotlin.math.abs(h24 * 60 + m - nowMin)
            return minOf(d, 1440 - d) <= slackMin
        }
        for (mm in Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.?)?").findAll(t)) {
            val h = mm.groupValues[1].toInt(); val m = mm.groupValues[2].toInt()
            if (h > 23 || m > 59) continue
            val ap = mm.groupValues[3]
            val cands = when {
                ap.startsWith("a") -> listOf(h % 12)
                ap.startsWith("p") -> listOf(h % 12 + 12)
                h in 1..12 -> listOf(h % 12, h % 12 + 12)
                else -> listOf(h)
            }
            if (cands.any { near(it, m) }) return true
        }
        for (mm in Regex("\\b(\\d{1,2})\\s*(a\\.?m\\.?|p\\.?m\\.?)").findAll(t)) {
            val h = mm.groupValues[1].toInt(); if (h !in 1..12) continue
            val h24 = if (mm.groupValues[2].startsWith("a")) h % 12 else h % 12 + 12
            if (near(h24, 0) || kotlin.math.abs(h24 * 60 - nowMin) <= 60) return true
        }
        return false
    }

    fun rankOf(hits: List<String>, expected: String): Int? =
        hits.indexOfFirst { it.contains(expected, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
}
