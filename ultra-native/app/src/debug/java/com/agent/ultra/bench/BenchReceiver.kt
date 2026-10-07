package com.agent.ultra.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.ultra.local.CpuVariant
import com.agent.ultra.local.DraftModel
import com.agent.ultra.local.LlmNative
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.local.PageFaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device benchmark, DEBUG BUILDS ONLY (src/debug: release APKs do not contain it).
 * Driven by tools/bench.sh:
 *
 *   adb shell am broadcast -a com.agent.ultra.BENCH -n com.agent.ultra/.bench.BenchReceiver \
 *       [--ei threads N] [--ei tokens M]
 *
 * Logs (tag UltraBench) the CPU topology, then for each setting of [BenchReport.configs] (each
 * thread count without and with the speed boost's draft model, then other draft lengths) runs a
 * fixed prompt [BenchReport.RUNS] times through LocalModelEngine and reports the last run. Ends
 * with "done".
 *
 * Broadcast limit: onReceive has ~10 s (60 s for a background broadcast) before the system
 * calls it an ANR, and a sweep takes minutes. So goAsync() only covers the topology dump; the
 * PendingResult is finished before generation starts and the work carries on in this process.
 * The cost: once finished, nothing but a foreground activity keeps the process up — which is
 * why bench.sh starts MainActivity first. That also puts the app in the top-app cpuset; a
 * background app is held to the small cores, and the "allowed" line shows which one ran.
 */
class BenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "already running; ignored")
            return
        }
        val app = context.applicationContext
        val threads = BenchReport.threadCounts(intent.getIntExtra("threads", 0))
        val tokens = BenchReport.tokens(intent.getIntExtra("tokens", 0))
        val pending = goAsync()
        scope.launch {
            try {
                Log.i(TAG, "start threads=$threads tokens=$tokens runs=${BenchReport.RUNS}")
                logTopology()
                pending.finish()
                bench(app, threads, tokens)
            } catch (t: Throwable) {
                Log.e(TAG, "error: ${t.javaClass.simpleName}: ${t.message}", t)
                Log.i(TAG, "done status=error")
            } finally {
                // Second finish() throws; the first one ran unless logTopology threw.
                runCatching { pending.finish() }
                running.set(false)
            }
        }
    }

    internal fun logTopology() {
        val maxKHz = File("/sys/devices/system/cpu").listFiles().orEmpty()
            .mapNotNull { dir ->
                val id = BenchReport.cpuIdOf(dir.name) ?: return@mapNotNull null
                val khz = readOrNull(File(dir, "cpufreq/cpuinfo_max_freq"))?.trim()?.toLongOrNull()
                    ?: return@mapNotNull null
                id to khz
            }.toMap()
        maxKHz.toSortedMap().forEach { (id, khz) -> Log.i(TAG, "cpu=$id maxKHz=$khz") }
        BenchReport.clusters(maxKHz).forEach { (khz, cpus) ->
            Log.i(TAG, "cluster maxKHz=$khz count=${cpus.size} cpus=${cpus.joinToString(",")}")
        }
        val features = readOrNull(File("/proc/cpuinfo"))?.let { CpuVariant.commonFeatures(it) }
        Log.i(TAG, "features=${features?.sorted()?.joinToString(" ") ?: "unreadable"}")
        Log.i(TAG, "variant=${LlmNative.variant} available=${LlmNative.available}")
        val proc = readOrNull(File("/proc/self/status"))?.let { BenchReport.statusField(it, "Cpus_allowed_list") }
        val thread = readOrNull(File("/proc/thread-self/status"))?.let { BenchReport.statusField(it, "Cpus_allowed_list") }
        val cpuset = readOrNull(File("/proc/self/cpuset"))?.trim()
        Log.i(TAG, "allowed process=$proc thread=$thread cpuset=$cpuset")
    }

    internal suspend fun bench(context: Context, threads: List<Int>, tokens: Int) {
        val engine = LocalModelEngine.shared(context)
        // Load before timing anything: the first generate would otherwise count the load.
        if (!engine.ensureLoaded()) {
            Log.e(TAG, "error: no on-device model loaded or available (llama=${LlmNative.available} " +
                "present=${engine.modelPresent} file=${engine.modelFilePath}); pick and download one in Settings")
            Log.i(TAG, "done status=error")
            return
        }
        Log.i(TAG, "model=${File(engine.modelFilePath).name} sizeMB=${engine.modelFileSizeBytes / 1_048_576}")
        val draftFile = DraftModel.present(context) && DraftModel.eligible(File(engine.modelFilePath).name)
        Log.i(TAG, "draft model " + if (draftFile) "on the phone (${DraftModel.FILE})"
            else "not available (not downloaded, or this brain isn't Qwen3): draft runs skipped")
        val configs = BenchReport.configs(threads, draftFile, DraftModel.DRAFT_MAX)
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        var seq = 0
        val measured = mutableListOf<Pair<BenchReport.Config, Double>>()
        try {
            for ((n, draftLen) in configs) {
                for (run in 1..BenchReport.RUNS) {
                    seq++
                    var first = -1L
                    var last = -1L
                    var count = 0
                    val f0 = PageFaults.read()
                    val t0 = System.currentTimeMillis()
                    // A different first word each run: a KV cache that reuses a matching
                    // prefix must not skip the prompt the measurement is timing.
                    val result = engine.generate("Run $seq. $PROMPT", maxTokens = tokens, threads = n, exactThreads = true,
                        draft = draftLen > 0, draftLen = draftLen) {
                        val now = System.currentTimeMillis()
                        if (first < 0) first = now
                        last = now
                        count++
                    }
                    val f1 = PageFaults.read()
                    if (result.isFailure) {
                        Log.e(TAG, "error: generate failed at threads=$n draft=${BenchReport.draftLabel(draftLen)}: " +
                            "${result.exceptionOrNull()?.message}")
                        Log.i(TAG, "done status=error")
                        return
                    }
                    val line = BenchReport.resultLine(
                        threads = n,
                        promptMs = if (first >= 0) first - t0 else -1,
                        genTokens = count,
                        tokPerSec = BenchReport.decodeTokPerSec(count, first, last),
                        majfaults = if (f0 >= 0 && f1 >= 0) f1 - f0 else -1,
                        thermal = thermalStatus(power),
                        draftLen = draftLen,
                        drafted = engine.lastSpecStats[0],
                        accepted = engine.lastSpecStats[1],
                    )
                    if (draftLen > 0 && engine.lastSpecStats[0] < 0)
                        Log.w(TAG, "draft=$draftLen asked but not used (no room, or refused: see UltraLlm 'draft' lines)")
                    if (run < BenchReport.RUNS) Log.i(TAG, "warmup $line")
                    else {
                        Log.i(TAG, line)
                        measured += BenchReport.Config(n, draftLen) to BenchReport.decodeTokPerSec(count, first, last)
                    }
                }
            }
        } finally {
            // generate(threads = n) is sticky: it sets the context's count for every later call,
            // and the app's small-model paths pass 0 ("keep current"). Unloading means the next
            // real request reloads with the app's own thread policy instead of the last bench count.
            engine.unload()
        }
        Log.i(TAG, BenchReport.summary(measured))
        Log.i(TAG, "done status=ok")
    }

    /** PowerManager.THERMAL_STATUS_* (0 none .. 6 shutdown), or -1 below API 29. */
    private fun thermalStatus(power: android.os.PowerManager?): Int =
        if (power != null && android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1

    private fun readOrNull(f: File): String? = try { f.readText() } catch (_: Exception) { null }

    companion object {
        /** The default sweep run from inside the app (TestKitActivity), for phones with no adb.
         *  Suspends until done; logs exactly what the broadcast does. False when already running. */
        suspend fun runNow(context: Context): Boolean {
            if (!running.compareAndSet(false, true)) return false
            try {
                val threads = BenchReport.threadCounts(0)
                val tokens = BenchReport.tokens(0)
                Log.i(TAG, "start threads=$threads tokens=$tokens runs=${BenchReport.RUNS} (in-app)")
                val r = BenchReceiver()
                r.logTopology()
                r.bench(context.applicationContext, threads, tokens)
            } catch (t: Throwable) {
                Log.e(TAG, "error: ${t.javaClass.simpleName}: ${t.message}", t)
                Log.i(TAG, "done status=error")
            } finally {
                running.set(false)
            }
            return true
        }

        private const val TAG = "UltraBench"
        private val running = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Fixed prompt, roughly 200 tokens on the presets' tokenizers. Plain text, no chat
         *  template: the bench times the engine, not the answer. */
        private const val PROMPT =
            "The following is a long, careful explanation written for a curious reader. " +
            "A modern phone processor is not one engine but several. A single large core runs " +
            "at the highest clock and finishes short, serial work quickly. A group of mid-sized " +
            "cores trades a little peak speed for much better efficiency, and they carry most " +
            "sustained work. A pair of small cores handles background tasks while sipping power. " +
            "Running a language model on such a chip is mostly a question of memory bandwidth: " +
            "every generated word requires reading a large share of the model's weights, so " +
            "adding threads helps only until the memory bus is saturated, and a thread placed on " +
            "a small core can hold back all the others, because each step waits for the slowest " +
            "worker. Heat matters too, since a hot chip lowers its clocks. Explain, step by step " +
            "and in plain language, how you would choose the number of threads for this work, " +
            "what you would measure, and why. Begin with the first step:"
    }
}
