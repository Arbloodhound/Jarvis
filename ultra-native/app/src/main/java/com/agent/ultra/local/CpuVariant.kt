package com.agent.ultra.local

/**
 * Which build of libultra_llm this phone's CPU can run, best first. The variants come from
 * ondevice-brain/build-llama-android.sh; a newer one on a CPU without its instructions dies
 * with SIGILL, so a feature counts only if EVERY core reports it (big.LITTLE threads move).
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

    /** Library names to try in order; [BASE] is always last. */
    fun candidates(cpuinfo: String): List<String> {
        val f = commonFeatures(cpuinfo)
        val dotprod = "asimddp" in f && "asimdhp" in f && "fphp" in f
        return buildList {
            if (dotprod && "i8mm" in f) add(I8MM)
            if (dotprod) add(DOTPROD)
            add(BASE)
        }
    }

    fun read(): List<String> = try {
        candidates(java.io.File("/proc/cpuinfo").readText())
    } catch (_: Exception) { listOf(BASE) }
}
