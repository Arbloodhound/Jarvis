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
     * The keep / try-IQ2_S rule from the Pixel guide. [tps]: tokens/sec per question, in order
     * (null = that question produced no on-device answer). Keep when there was no crash, the
     * median is at least [COMFORT_TOK_PER_SEC], and the last question runs at no less than two
     * thirds of the second's speed (the first absorbs loading). Fewer than 3 measured
     * questions is not enough to judge.
     */
    fun modelVerdict(tps: List<Double?>, crashed: Boolean): Pair<Model, String> {
        if (crashed) return Model.TRY_SMALLER to "the app crashed during the test"
        val measured = tps.filterNotNull()
        if (measured.size < 3) return Model.NOT_ENOUGH_DATA to "only ${measured.size} answers were measured"
        val median = measured.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        if (median < COMFORT_TOK_PER_SEC) return Model.TRY_SMALLER to "median ${fmt(median)} tok/s is below $COMFORT_TOK_PER_SEC"
        val second = measured[1]
        val last = measured.last()
        if (last < second * 2 / 3) return Model.TRY_SMALLER to "slowed down from ${fmt(second)} to ${fmt(last)} tok/s"
        return Model.KEEP to "median ${fmt(median)} tok/s, steady (${fmt(second)} → ${fmt(last)})"
    }

    fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.1f", v)

    /** `logcat -T` start time for "everything since [epochMs]" (its seconds.millis form). */
    fun logcatSince(epochMs: Long): String = String.format(java.util.Locale.ROOT, "%d.%03d", epochMs / 1000, epochMs % 1000)

    /** True when a crash of this app shows in the given logcat lines. */
    fun hasCrash(lines: List<String>): Boolean = lines.any { "FATAL EXCEPTION" in it }
}
