package com.agent.ultra.local

/**
 * Which build of a native library this phone's CPU can run, best first: libultra_llm
 * (ondevice-brain/build-llama-android.sh) and libultra_whisper (build-whisper-android.sh) are
 * each built as the same three variants, named <base>, <base>_dotprod and <base>_i8mm. A newer
 * one on a CPU without its instructions dies with SIGILL, so a feature counts only if EVERY
 * core reports it (big.LITTLE threads move).
 * Pure parsing, JVM-tested; [read] is the only part that touches the device.
 */
object CpuVariant {
    const val I8MM = "ultra_llm_i8mm"        // armv8.2-a+dotprod+i8mm+fp16
    const val DOTPROD = "ultra_llm_dotprod"  // armv8.2-a+dotprod+fp16
    const val BASE = "ultra_llm"             // armv8-a, any arm64

    /** Features every core in /proc/cpuinfo reports; empty when none are listed. */
    fun commonFeatures(cpuinfo: String): Set<String> =
        cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim() == "Features" }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .reduceOrNull { a, b -> a intersect b }
            ?: emptySet()

    /** Variant suffixes to try in order, e.g. ["_i8mm", "_dotprod", ""]; "" (armv8-a) is always last. */
    fun suffixes(cpuinfo: String): List<String> {
        val f = commonFeatures(cpuinfo)
        val dotprod = "asimddp" in f && "asimdhp" in f && "fphp" in f
        return buildList {
            if (dotprod && "i8mm" in f) add("_i8mm")
            if (dotprod) add("_dotprod")
            add("")
        }
    }

    /** Library names to try in order for [base] (e.g. "ultra_whisper"); [base] is always last. */
    fun candidates(cpuinfo: String, base: String): List<String> = suffixes(cpuinfo).map { base + it }

    /** libultra_llm names to try in order; [BASE] is always last. */
    fun candidates(cpuinfo: String): List<String> = candidates(cpuinfo, BASE)

    fun read(base: String = BASE): List<String> = try {
        candidates(java.io.File("/proc/cpuinfo").readText(), base)
    } catch (_: Exception) { listOf(base) }
}
