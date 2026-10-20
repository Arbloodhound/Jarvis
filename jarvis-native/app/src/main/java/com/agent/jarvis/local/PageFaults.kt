package com.agent.jarvis.local

/**
 * Major page faults of this process: each one is a page the kernel had to read back
 * from storage. While a model answers, a steady stream of them means its weights no
 * longer fit in RAM and are being re-read every token: thrashing. Pure parsing,
 * JVM-tested; [read] is the only part that touches the device.
 */
object PageFaults {
    /** majflt from a /proc/<pid>/stat line (field 12), or -1. The comm field may hold spaces. */
    fun majorFrom(statLine: String): Long {
        val rest = statLine.substringAfterLast(')', "").trim()
        if (rest.isEmpty()) return -1
        // rest starts at field 3 (state), so field 12 is index 9
        return rest.split(' ').getOrNull(9)?.toLongOrNull() ?: -1
    }

    fun read(): Long = try {
        majorFrom(java.io.File("/proc/self/stat").readText())
    } catch (_: Exception) { -1 }
}
