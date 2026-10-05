package com.agent.ultra.local

/**
 * Which cores the LLM and whisper run on. Phones mix core types (Tensor G5: one X4, five
 * A725, two A520), and the CPU numbering differs per SoC, so it is read from each core's
 * cpuinfo_max_freq: the LLM gets the fastest cores, whisper gets the rest. Pure parsing,
 * JVM-tested; [read] is the only part that touches the device.
 */
object CpuTopology {
    /** Threads, and no pinning, when the frequencies can't be read (today's behaviour). */
    const val FALLBACK_THREADS = 4
    const val MAX_LLM_THREADS = 6

    data class Topology(
        val fastestFirst: List<Int>,   // CPU ids, fastest first (ties: lower id first)
        val big: List<Int>,            // every CPU above the slowest cluster's frequency
        val llmThreads: Int,           // big-core count, at least 4 (or every CPU), at most 6
        val llmCpus: List<Int>,        // empty = don't pin
        val whisperCpus: List<Int>,    // empty = don't pin
    )

    val FALLBACK = Topology(emptyList(), emptyList(), FALLBACK_THREADS, emptyList(), emptyList())

    /** [maxFreqKhz]: CPU id -> cpuinfo_max_freq. Empty or non-positive values => [FALLBACK]. */
    fun fromMaxFreqs(maxFreqKhz: Map<Int, Long>): Topology {
        if (maxFreqKhz.isEmpty() || maxFreqKhz.any { it.key < 0 || it.value <= 0 }) return FALLBACK
        val ordered = maxFreqKhz.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .map { it.key }
        val slowest = maxFreqKhz.values.min()
        // One cluster (every core alike): there is nothing slower to avoid, so all are "big".
        val big = ordered.filter { maxFreqKhz.getValue(it) > slowest }.ifEmpty { ordered }
        // Never fewer than the 4 threads measured on the A15 5G (2 big + 6 little cores): with
        // only 2 big cores, the LLM takes the next-fastest cores too rather than lose half its threads.
        val floor = minOf(FALLBACK_THREADS, ordered.size)
        val threads = big.size.coerceIn(floor, MAX_LLM_THREADS)
        val llm = ordered.take(threads)
        val whisper = ordered.filter { it !in llm }
            .ifEmpty { ordered.filter { maxFreqKhz.getValue(it) == slowest } }
        return Topology(ordered, big, threads, llm.sorted(), whisper.sorted())
    }

    /** CPU ids from /sys/devices/system/cpu entry names ("cpu0", "cpu7"; not "cpufreq"). */
    fun cpuIds(names: List<String>): List<Int> =
        names.mapNotNull { n -> if (n.startsWith("cpu")) n.substring(3).toIntOrNull() else null }
            .filter { it >= 0 }
            .sorted()

    /** Reads each CPU's max frequency. A CPU whose file can't be read (e.g. offline) is left
     *  out of both lists rather than costing every other core its placement; none => [FALLBACK]. */
    fun read(): Topology = try {
        val root = java.io.File("/sys/devices/system/cpu")
        val ids = cpuIds(root.list()?.toList() ?: emptyList())
        val freqs = ids.mapNotNull { id ->
            try {
                id to java.io.File(root, "cpu$id/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            } catch (_: Exception) { null }
        }.toMap()
        fromMaxFreqs(freqs)
    } catch (_: Exception) { FALLBACK }

    /** Read once per process: the cores don't change, and both engines share the answer. */
    val current: Topology by lazy { read() }
}
