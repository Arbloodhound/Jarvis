package com.agent.ultra.bench

/**
 * Parsing and verdicts for the debug-only on-phone test kit (src/debug/.../TestKitActivity), the
 * no-laptop path for phones without adb. Lives in main only so the JVM tests can reach it; no
 * release code path references it, so R8 drops it. Pure, JVM-tested, no Android types.
 */
object KitReport {
    /** One LocalModelEngine "gen tokens=…" log line. -1 fields mean "not measured". */
    data class Gen(val tokens: Int, val ms: Long, val tokPerSec: Double, val majfaults: Long, val availMb: Long)

    // tokPerSec is formatted with the default locale, so "5,3" is possible.
    private val GEN = Regex("""gen tokens=(\d+) ms=(\d+) tokPerSec=([0-9]+[.,]?[0-9]*)""")
    private val MAJ = Regex("""majfaults=(-?\d+)""")
    private val AVAIL = Regex("""availMB=(-?\d+)""")

    fun parseGen(line: String): Gen? {
        val m = GEN.find(line) ?: return null
        return Gen(
            tokens = m.groupValues[1].toInt(),
            ms = m.groupValues[2].toLong(),
            tokPerSec = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null,
            majfaults = MAJ.find(line)?.groupValues?.get(1)?.toLong() ?: -1,
            availMb = AVAIL.find(line)?.groupValues?.get(1)?.toLong() ?: -1,
        )
    }

    /** The generation that answered a question: a question can run several (a tool call, then
     *  the answer); the longest one is the answer and the fairest speed sample. */
    fun mainGen(lines: List<String>): Gen? = lines.mapNotNull { parseGen(it) }.maxByOrNull { it.tokens }

    enum class Model { KEEP, TRY_SMALLER, NOT_ENOUGH_DATA }

    /** Speed below which reading answers as they appear gets uncomfortable (a guide, not a limit). */
    const val COMFORT_TOK_PER_SEC = 5.0

    /**
     * The keep / try-a-smaller-model rule from the Pixel guide (which model: [smallerModel]). [tps]: tokens/sec per question, in order
     * (null = that question produced no on-device answer). Keep when there was no crash, the
     * median is at least [COMFORT_TOK_PER_SEC], and the last question runs at no less than two
     * thirds of the second's speed (the first absorbs loading). Fewer than 3 measured
     * questions is not enough to judge.
     */
    fun modelVerdict(tps: List<Double?>, crashed: Boolean): Pair<Model, String> {
        if (crashed) return Model.TRY_SMALLER to "the app crashed during the test"
        val measured = tps.filterNotNull()
        if (measured.size < 3) return Model.NOT_ENOUGH_DATA to "only ${measured.size} answers were measured"
        val median = median(measured)!!
        if (median < COMFORT_TOK_PER_SEC) return Model.TRY_SMALLER to "median ${fmt(median)} tok/s is below $COMFORT_TOK_PER_SEC"
        val second = measured[1]
        val last = measured.last()
        if (last < second * 2 / 3) return Model.TRY_SMALLER to "slowed down from ${fmt(second)} to ${fmt(last)} tok/s"
        return Model.KEEP to "median ${fmt(median)} tok/s, steady (${fmt(second)} → ${fmt(last)})"
    }

    /** Median of the measured speeds (nulls skipped), or null when none was measured. */
    fun median(tps: List<Double?>): Double? {
        val s = tps.filterNotNull().sorted()
        if (s.isEmpty()) return null
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** A model the Change-model list offers ([com.agent.ultra.local.ModelChoice], without Android). */
    data class Preset(val label: String, val fileName: String, val approxMb: Int, val desktopOnly: Boolean = false)

    /** A suggested model is at most this share of the current one's size. */
    const val MAX_SIZE_RATIO = 0.75

    /** The model to try and its expected speed (tok/s; null when nothing was measured). */
    data class Suggestion(val preset: Preset, val expectedTokPerSec: Double?)

    /**
     * The model to try when the current one ([currentFile], [currentMb] of weights) is too slow,
     * from the presets that exist ([presets], in the list's order: preferred first). Never the
     * desktop-only ones, nor the Qwen3-30B-A3B MoE or its fine-tunes ("moe" in the file name, as
     * LocalModelEngine tells them apart): on the
     * 16 GB Pixel only ~8.6 GB is free, the MoE's 9.3–10 GB pages from storage and measured
     * 0.4–1.6 tok/s, so it is never the cure for slowness (the old advice named its IQ2_S).
     * Nor anything in [exclude] (the librarian's own model).
     *
     * Candidates are at most [MAX_SIZE_RATIO] of the current model's size (a model a few percent
     * smaller would not fix the slowness). Decoding on the CPU is bound by memory
     * bandwidth, so speed scales roughly with 1 / weight size: each one's expected speed is
     * [medianTps] × currentMb / its size (a rough guide; the 8B→4B step on the Pixel went from
     * 2–4 to 8–11 tok/s, a little better than this predicts). The first candidate expected to
     * reach [COMFORT_TOK_PER_SEC] wins; with nothing measured, the first candidate in list order;
     * with none expected to reach it, the smallest (the fastest expected).
     * Null when no smaller model is listed.
     */
    fun smallerModel(
        currentFile: String,
        currentMb: Int,
        medianTps: Double?,
        presets: List<Preset>,
        exclude: Set<String> = emptySet(),
    ): Suggestion? {
        if (currentMb <= 0) return null
        val candidates = presets.filter {
            !it.desktopOnly && !it.fileName.contains("moe") && it.fileName != currentFile &&
                it.fileName !in exclude && it.approxMb in 1..(currentMb * MAX_SIZE_RATIO).toInt()
        }
        if (candidates.isEmpty()) return null
        fun expected(p: Preset) = medianTps?.let { it * currentMb / p.approxMb }
        val pick = if (medianTps == null) candidates.first()
            else candidates.firstOrNull { expected(it)!! >= COMFORT_TOK_PER_SEC } ?: candidates.minBy { it.approxMb }
        return Suggestion(pick, expected(pick))
    }

    /**
     * The brain test's one-line verdict for the model in use ([currentLabel]), naming a real
     * smaller preset ([smallerModel]) when it is too slow, except when the model in use is one of
     * [reasoningDefaults] (the default reasoning brain): every smaller preset reasons worse, and
     * trading reasoning for speed is not the advice. Then it says to keep it and how to make it
     * faster ([KEEP_FOR_REASONING]). Pixel 10 Pro XL: the 4B Thinking measured 3.4 tok/s on one
     * brain test and 6.0 on the next, and the 3.4 run was told to switch to the 1.7B.
     */
    fun advice(
        verdict: Model,
        currentLabel: String,
        currentFile: String,
        currentMb: Int,
        medianTps: Double?,
        presets: List<Preset>,
        exclude: Set<String> = emptySet(),
        reasoningDefaults: Set<String> = emptySet(),
    ): String = when (verdict) {
        Model.KEEP -> "KEEP this model ($currentLabel)."
        Model.NOT_ENOUGH_DATA -> "Not enough answers to judge — send me the results file."
        Model.TRY_SMALLER -> if (currentFile in reasoningDefaults) "KEEP this model ($currentLabel): $KEEP_FOR_REASONING"
        else when (val s = smallerModel(currentFile, currentMb, medianTps, presets, exclude)) {
            null -> "$currentLabel is already the smallest model listed; nothing smaller to try — send me the results file."
            else -> "TRY A SMALLER MODEL: Settings → ON-DEVICE MODEL → Change model → \"${s.preset.label}\" → Download, " +
                "then run test 2 again." + (s.expectedTokPerSec?.let {
                    " Expect roughly ${fmt(it)} tok/s (this model's ${fmt(medianTps!!)} scaled by size: ${currentMb} → ${s.preset.approxMb} MB)."
                } ?: "")
        }
    }

    /** What to do when the default reasoning brain is slow, instead of a weaker model. */
    const val KEEP_FOR_REASONING = "it reasons best of the models this phone runs, and every smaller one reasons worse. " +
        "To make it faster: close other heavy apps, let the phone cool down, set Settings → Thinking depth to Quick, " +
        "and turn on Settings → Speed boost (draft model) (its helper downloads under DOWNLOADS → Download all missing)."

    fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.1f", v)

    /** `logcat -T` start time for "everything since [epochMs]" (its seconds.millis form). */
    fun logcatSince(epochMs: Long): String = String.format(java.util.Locale.ROOT, "%d.%03d", epochMs / 1000, epochMs % 1000)

    /** True when a crash of this app shows in the given logcat lines. */
    fun hasCrash(lines: List<String>): Boolean = lines.any { "FATAL EXCEPTION" in it }
}
