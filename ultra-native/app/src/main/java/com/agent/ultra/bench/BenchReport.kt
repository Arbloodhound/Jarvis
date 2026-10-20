package com.agent.ultra.bench

/**
 * Parsing and formatting for the debug-only on-device benchmark (src/debug/.../BenchReceiver).
 * Lives in main only so the JVM tests can reach it; nothing in a release code path references
 * it, so R8 drops it from the release APK. Pure, no Android types; tools/bench.sh parses the
 * lines [resultLine] writes, so change both together.
 */
object BenchReport {
    /** Thread counts swept when the broadcast names none: around the Tensor G5's 1+5 big cores. */
    val DEFAULT_THREADS = listOf(4, 5, 6)
    const val DEFAULT_TOKENS = 64
    /** Runs per thread count; only the last is reported (the first absorbs warm-up). */
    const val RUNS = 2

    fun threadCounts(requested: Int): List<Int> = if (requested > 0) listOf(requested) else DEFAULT_THREADS

    fun tokens(requested: Int): Int = if (requested > 0) requested else DEFAULT_TOKENS

    /** One measured setting: [threads], and the draft length ([draftLen] 0 = plain decoding). */
    data class Config(val threads: Int, val draftLen: Int)

    /** Extra draft lengths tried at the largest thread count, around the default. */
    val EXTRA_DRAFT_LENS = listOf(4, 8)

    /**
     * The sweep: each of [threads] without the draft and, when [draftAvailable], with it at
     * [defaultDraftLen]; then, at the largest thread count, the [EXTRA_DRAFT_LENS] other than the
     * default. So one run answers both "best thread count" and "real speed-up of the draft".
     */
    fun configs(threads: List<Int>, draftAvailable: Boolean, defaultDraftLen: Int): List<Config> {
        val out = mutableListOf<Config>()
        for (n in threads) {
            out += Config(n, 0)
            if (draftAvailable) out += Config(n, defaultDraftLen)
        }
        val top = threads.maxOrNull()
        if (draftAvailable && top != null)
            for (len in EXTRA_DRAFT_LENS) if (len != defaultDraftLen) out += Config(top, len)
        return out
    }

    /**
     * The sweep's answer in one line: the fastest thread count without the draft, the fastest
     * setting with it, and the speed-up between them. [results]: each setting's reported tok/s
     * (settings with 0 tok/s, i.e. unmeasured, are ignored). Starts with "summary" and carries
     * threads=, so the in-app speed test shows it.
     */
    fun summary(results: List<Pair<Config, Double>>): String {
        val ok = results.filter { it.second > 0 }
        val plain = ok.filter { it.first.draftLen == 0 }.maxByOrNull { it.second }
        val draft = ok.filter { it.first.draftLen > 0 }.maxByOrNull { it.second }
        fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)
        val p = plain?.let { "best without draft: threads=${it.first.threads} ${f(it.second)} tok/s" } ?: "no run without draft"
        val d = draft?.let { "best with draft: threads=${it.first.threads} draft=${it.first.draftLen} ${f(it.second)} tok/s" +
            (plain?.let { pl -> " (x${f(it.second / pl.second)})" } ?: "") } ?: "no run with the draft"
        return "summary $p; $d"
    }

    /** "off", or the draft length. */
    fun draftLabel(draftLen: Int): String = if (draftLen > 0) draftLen.toString() else "off"

    /** Share of drafted tokens the model kept, as "62%"; "-" without a draft (drafted < 0) or
     *  when nothing was drafted. */
    fun acceptLabel(drafted: Int, accepted: Int): String =
        if (drafted <= 0) "-" else "${accepted * 100 / drafted}%"

    /** "cpu7" -> 7; anything else under /sys/devices/system/cpu ("cpufreq", "cpuidle") -> null. */
    fun cpuIdOf(dirName: String): Int? =
        Regex("cpu(\\d+)").matchEntire(dirName)?.groupValues?.get(1)?.toIntOrNull()

    /** The value of one "Key:\tvalue" line of /proc/<pid>/status, e.g. Cpus_allowed_list. */
    fun statusField(status: String, key: String): String? =
        status.lineSequence()
            .firstOrNull { it.substringBefore(':').trim() == key }
            ?.substringAfter(':')?.trim()

    /** Cores grouped by max frequency, fastest first: (maxKHz, cpu ids ascending). */
    fun clusters(maxKHz: Map<Int, Long>): List<Pair<Long, List<Int>>> =
        maxKHz.entries.groupBy({ it.value }, { it.key })
            .map { (khz, cpus) -> khz to cpus.sorted() }
            .sortedByDescending { it.first }

    /**
     * Decode speed from the token callback times. The native loop calls back after each
     * sample and before that token's decode, so the first callback lands when the prompt
     * is done and the n-1 gaps between first and last callback are one decode each.
     * A token whose piece is empty gets no callback, so then this reads slightly low; the
     * engine's own UltraLlm "gen tokens=" line has the native count to compare.
     */
    fun decodeTokPerSec(tokens: Int, firstTokenAtMs: Long, lastTokenAtMs: Long): Double {
        val span = lastTokenAtMs - firstTokenAtMs
        return if (tokens < 2 || span <= 0) 0.0 else (tokens - 1) * 1000.0 / span
    }

    /** One reported measurement. tools/bench.sh reads these key=value pairs. */
    fun resultLine(
        threads: Int, promptMs: Long, genTokens: Int, tokPerSec: Double, majfaults: Long, thermal: Int,
        draftLen: Int = 0, drafted: Int = -1, accepted: Int = 0,
    ): String = "threads=$threads promptMs=$promptMs genTokens=$genTokens " +
        "tokPerSec=${"%.2f".format(java.util.Locale.ROOT, tokPerSec)} majfaults=$majfaults thermal=$thermal " +
        "draft=${draftLabel(draftLen)} accept=${acceptLabel(drafted, accepted)}"
}
