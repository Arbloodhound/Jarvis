package com.agent.jarvis.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * What the app and the phone are doing at one moment, for the live diagnostic (owner, 2026-10-20:
 * "a trace of everything going on in the app, and everything going on in Android that it can").
 * Each poll writes: which of Jarvis's threads used the CPU since the last poll (whisper, the LLM
 * workers, the UI…), every core's clock, thermal state and headroom, battery, screen, network, and
 * the app's own state (brain running, models loaded and busy, listening). Android hides other
 * apps' processes from an app, so the phone side is what the system reports to everyone.
 * Parsing is pure and JVM-tested.
 */
object SystemTrace {

    /** One thread's name and CPU time (user + system, clock ticks). */
    data class Ticks(val name: String, val ticks: Long)

    /** "/proc/self/task/<tid>/stat" -> name and utime+stime. The name may hold spaces and ')'. */
    fun parseStat(line: String): Ticks? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val rest = line.substring(close + 2).split(' ')
        // After ")": state(0) ppid(1) … utime is field 14 of the whole line, rest index 11; stime 12.
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return Ticks(line.substring(open + 1, close), utime + stime)
    }

    /**
     * The threads that used the CPU between [prev] and [now] (tid -> ticks), busiest first, grouped
     * by name ("ggml" workers add up), as "name 1.2s". [hz] is clock ticks per second.
     */
    fun busiest(prev: Map<Int, Ticks>, now: Map<Int, Ticks>, top: Int = 8, hz: Int = 100): List<String> =
        now.mapNotNull { (tid, t) -> (t.ticks - (prev[tid]?.ticks ?: t.ticks)).takeIf { it > 0 }?.let { t.name to it } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.take(top)
            .map { "${it.key} ${"%.1f".format(it.value.toDouble() / hz)}s" }

    fun readThreads(): Map<Int, Ticks> = try {
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { d ->
            val tid = d.name.toIntOrNull() ?: return@mapNotNull null
            try { parseStat(File(d, "stat").readText())?.let { tid to it } } catch (_: Exception) { null }
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    /** Each core's current clock in MHz ("-" where the system hides it). */
    fun cpuClocks(): String = (0 until Runtime.getRuntime().availableProcessors()).joinToString(" ") { c ->
        val khz = try { File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toLong() } catch (_: Exception) { -1L }
        "cpu$c=" + if (khz > 0) "${khz / 1000}" else "-"
    }

    /** The lines for this poll; returns the thread table for the next one. */
    fun snapshot(ctx: Context, prevThreads: Map<Int, Ticks>): Pair<List<String>, Map<Int, Ticks>> {
        val threads = readThreads()
        val out = mutableListOf<String>()
        if (prevThreads.isNotEmpty()) out += "CPU threads: " + busiest(prevThreads, threads).joinToString(" · ").ifBlank { "idle" }
        out += "CPU clocks MHz: ${cpuClocks()} · threads ${threads.size}"
        out += phone(ctx)
        out += app(ctx)
        return out to threads
    }

    private fun phone(ctx: Context): String = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus.toString() else "-"
        val headroom = if (Build.VERSION.SDK_INT >= 30) "%.2f".format(pm.getThermalHeadroom(10)) else "-"
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        } + if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == false) " (not validated: login page?)" else ""
        "PHONE thermal=$thermal headroom=$headroom (1.0 = throttling) · battery $level% ${temp}C" +
            "${if (plugged) " charging" else ""} · screen ${if (pm.isInteractive) "on" else "off"}" +
            " · power saver ${if (pm.isPowerSaveMode) "ON" else "off"} · network $net"
    } catch (e: Exception) { "PHONE (unreadable: ${e.message})" }

    private fun app(ctx: Context): String = try {
        val thinker = com.agent.jarvis.local.LocalModelEngine.shared(ctx)
        val action = com.agent.jarvis.local.LocalModelEngine.librarian(ctx)
        fun st(e: com.agent.jarvis.local.LocalModelEngine) =
            if (!e.loaded) "off" else if (e.busy.value) "busy" else "idle"
        "APP brain ${if (com.agent.jarvis.agent.Brain.anyRunning) "running" else "idle"} · main model ${st(thinker)}" +
            " · speed boost ${if (thinker.draftAttached) "on" else "off"} · action model ${st(action)}" +
            " · thermal level ${com.agent.jarvis.agent.ThermalGuard.level}" +
            " · listening ${com.agent.jarvis.listen.AmbientListenService.asrModelName.ifBlank { "off" }}" +
            " · speaking ${if (com.agent.jarvis.ui.SpeechQueue.anyBusy) "yes" else "no"}"
    } catch (e: Exception) { "APP (unreadable: ${e.message})" }
}
