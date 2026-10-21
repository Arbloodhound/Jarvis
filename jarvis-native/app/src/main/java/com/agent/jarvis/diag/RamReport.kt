package com.agent.jarvis.diag

import android.content.Context
import java.io.File

/**
 * Where the phone's RAM is going, for the live diagnostic. Android lets an app see the whole phone's
 * totals (/proc/meminfo) and its own use (/proc/self/status, Debug.MemoryInfo), not other apps one by
 * one; "Android + other apps" is the rest, worked out from those.
 */
object RamReport {

    /** "MemTotal:  15443000 kB" lines -> name to MB. */
    fun parseKbLines(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
        val m = Regex("^(\\w+):\\s+(\\d+)\\s*kB").find(line.trim()) ?: return@mapNotNull null
        m.groupValues[1] to m.groupValues[2].toLong() / 1024
    }.toMap()

    /** The lines of the report from the phone's totals [mem], this app's [self] status and its parts. */
    fun lines(mem: Map<String, Long>, self: Map<String, Long>, appParts: List<Pair<String, Long>>, models: List<String>): List<String> {
        val total = mem["MemTotal"] ?: 0
        val avail = mem["MemAvailable"] ?: 0
        val jarvis = self["VmRSS"] ?: 0
        val out = mutableListOf<String>()
        out += "RAM phone: total $total MB · available $avail MB · in use ${total - avail} MB"
        out += "RAM phone detail: free ${mem["MemFree"] ?: 0} · cached files ${mem["Cached"] ?: 0} · " +
            "app memory (anon) ${mem["AnonPages"] ?: 0} · mapped files ${mem["Mapped"] ?: 0} · " +
            "swap used ${(mem["SwapTotal"] ?: 0) - (mem["SwapFree"] ?: 0)} of ${mem["SwapTotal"] ?: 0} MB"
        out += "RAM phone kernel: slab ${mem["Slab"] ?: 0} · kernel stacks ${mem["KernelStack"] ?: 0} · " +
            "page tables ${mem["PageTables"] ?: 0} · vmalloc ${mem["VmallocUsed"] ?: 0} · shared ${mem["Shmem"] ?: 0} · " +
            "locked ${mem["Mlocked"] ?: 0} MB"
        out += "RAM Jarvis: $jarvis MB resident (model files ${self["RssFile"] ?: 0} MB · " +
            "own memory ${self["RssAnon"] ?: 0} MB) · peak ${self["VmHWM"] ?: 0} MB"
        if (appParts.isNotEmpty()) out += "RAM Jarvis parts: " + appParts.joinToString(" · ") { "${it.first} ${it.second} MB" }
        out += "RAM Android + other apps (rest of in use): ${(total - avail - jarvis).coerceAtLeast(0)} MB"
        if (models.isNotEmpty()) out += "RAM models: " + models.joinToString(" · ")
        return out
    }

    /** The report now, read from this phone. */
    fun snapshot(ctx: Context): List<String> {
        val mem = read("/proc/meminfo")
        val self = read("/proc/self/status")
        val parts = try {
            val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            listOf("java-heap", "native-heap", "code", "graphics", "stack", "private-other", "system")
                .mapNotNull { k -> mi.getMemoryStat("summary.$k")?.toLongOrNull()?.let { k to it / 1024 } }
        } catch (_: Exception) { emptyList() }
        return lines(mem, self, parts, models(ctx))
    }

    private fun models(ctx: Context): List<String> = try {
        val thinker = com.agent.jarvis.local.LocalModelEngine.shared(ctx)
        val action = com.agent.jarvis.local.LocalModelEngine.librarian(ctx)
        fun mb(e: com.agent.jarvis.local.LocalModelEngine) = e.modelFileSizeBytes / 1_048_576
        listOf(
            "main brain ${if (thinker.loaded) "loaded" else "not loaded"} (file ${mb(thinker)} MB)",
            "speed boost ${if (thinker.draftAttached) "loaded" else "not loaded"}",
            "action model ${if (action.loaded) "loaded" else "not loaded"} (file ${mb(action)} MB)",
            "speech ${com.agent.jarvis.listen.AmbientListenService.asrModelName.ifBlank { "not listening" }}",
        )
    } catch (_: Exception) { emptyList() }

    private fun read(path: String): Map<String, Long> =
        try { parseKbLines(File(path).readText()) } catch (_: Exception) { emptyMap() }
}
