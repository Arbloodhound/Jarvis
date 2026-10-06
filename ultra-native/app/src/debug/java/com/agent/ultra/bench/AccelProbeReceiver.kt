package com.agent.ultra.bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agent.ultra.listen.WakeGate
import com.agent.ultra.local.CpuTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device accelerator probe, DEBUG BUILDS ONLY (src/debug: release APKs do not contain it).
 * Answers "can this phone run Jarvis's audio models on its TPU / GPU?" from real evidence.
 * Driven by tools/accel-probe.sh:
 *
 *   adb shell am broadcast -a com.agent.ultra.ACCEL_PROBE -n com.agent.ultra/.bench.AccelProbeReceiver \
 *       [--es hal "name1,name2" | --es hal none] [--ez complete true]
 *
 * (hal: NNAPI service names the script found; complete: both dumpsys and lshal were read.)
 * Logs (tag UltraAccel): SoC, CPU topology, ONNX Runtime providers, then for each openWakeWord
 * model already in filesDir (the same files WakeGate loads) a CPU / NNAPI(CPU_DISABLED) / XNNPACK
 * timing, vendor NN-driver file names, the NNAPI / TPU verdicts (AccelReport), then OpenCL /
 * Vulkan presence and loadability. Ends with "done status=ok|error". Never touches WakeGate's
 * own sessions: it opens its own and closes them.
 *
 * Library loading is last on purpose: System.load runs the vendor library's native init in this
 * process, and a native crash there can't be caught. Each load is logged before it is tried, so
 * a crash shows which library did it, and the TPU verdict is already in the log.
 *
 * Same broadcast handling as BenchReceiver: goAsync() is finished right after the first line,
 * the work carries on in this process, and the script brings MainActivity to the front first so
 * the process stays up and runs in the top-app cpuset.
 */
class AccelProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "already running; ignored")
            return
        }
        val app = context.applicationContext
        val hal = AccelReport.parseHalExtra(intent.getStringExtra("hal"))
        val complete = intent.getBooleanExtra("complete", false)
        val pending = goAsync()
        scope.launch {
            try {
                Log.i(TAG, "start hal=${hal?.joinToString(",")?.ifEmpty { "none" } ?: "not-given"} complete=$complete")
                pending.finish()
                probe(app, hal, complete)
                Log.i(TAG, "done status=ok")
            } catch (t: Throwable) {
                Log.e(TAG, "error: ${AccelReport.shortError(t)}", t)
                Log.i(TAG, "done status=error")
            } finally {
                runCatching { pending.finish() }
                running.set(false)
            }
        }
    }

    private fun probe(context: Context, hal: List<String>?, halComplete: Boolean) {
        // --- SoC and CPU
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "n/a (API ${Build.VERSION.SDK_INT})"
        Log.i(TAG, "soc=$soc hardware=${Build.HARDWARE} board=${Build.BOARD} device=${Build.DEVICE} api=${Build.VERSION.SDK_INT}")
        val topo = CpuTopology.current
        Log.i(TAG, "cpu fastestFirst=${topo.fastestFirst} big=${topo.big} llmThreads=${topo.llmThreads} " +
            "llmCpus=${topo.llmCpus} whisperCpus=${topo.whisperCpus}")

        // --- ONNX Runtime: which providers this build carries, then each wake model
        val providers = try { OrtEnvironment.getAvailableProviders().joinToString(",") } catch (t: Throwable) { "error ${AccelReport.shortError(t)}" }
        Log.i(TAG, "ort providers=$providers")
        val results = probeModels(context)

        // --- NN driver names the app can see without root
        val hintDirs = listOf("/vendor/lib64", "/vendor/lib64/hw", "/vendor/etc/vintf/manifest", "/vendor/etc/init")
        val listed = hintDirs.map { it to listNames(it) { n -> AccelReport.isNnDriverName(n) } }
        listed.forEach { (dir, names) -> Log.i(TAG, "driver files $dir=${names?.joinToString(",")?.ifEmpty { "none" } ?: "unlistable"}") }
        val fileHints = listed.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.flatten()

        // --- NNAPI / TPU verdicts
        val best = AccelReport.best(results)
        Log.i(TAG, "verdict NNAPI: $best")
        Log.i(TAG, AccelReport.tpuVerdict(hal, hal != null && halComplete, fileHints, best).line())

        // --- GPU libraries (last: see the class comment)
        Log.i(TAG, "vulkan drivers=${listNames("/vendor/lib64/hw") { it.startsWith("vulkan.") } ?: "unlistable"}")
        val publicLibs = safe { File("/vendor/etc/public.libraries.txt").readText() }?.let { AccelReport.publicLibraries(it) }
        Log.i(TAG, "vendor public libraries=${publicLibs?.joinToString(",")?.ifEmpty { "none" } ?: "unreadable"}")
        // By name first, through the app's linker namespace (what a GPU backend would actually
        // do). After a load by path the linker would resolve the name to the loaded copy.
        var clLoaded = false
        for (name in listOf("OpenCL", "vulkan")) {
            Log.i(TAG, "loading lib$name.so by name")
            val load = try { System.loadLibrary(name); "ok" } catch (t: Throwable) { AccelReport.shortError(t) }
            if (name == "OpenCL" && load == "ok") clLoaded = true
            Log.i(TAG, "lib loadLibrary($name) load=$load")
        }
        var clExists = false
        for (dir in LIB_DIRS) {
            for (name in listOf("libOpenCL.so", "libvulkan.so")) {
                val f = File(dir, name)
                val exists = safe { f.exists() } ?: false
                if (exists) Log.i(TAG, "loading ${f.absolutePath}")
                val load = if (!exists) "-" else try { System.load(f.absolutePath); "ok" } catch (t: Throwable) { AccelReport.shortError(t) }
                if (name == "libOpenCL.so") { clExists = clExists || exists; clLoaded = clLoaded || load == "ok" }
                Log.i(TAG, AccelReport.libLine(f.absolutePath, exists, load))
            }
        }
        Log.i(TAG, "verdict ${AccelReport.openClVerdict(clExists, clLoaded)}")
    }

    /** One AccelReport.Nnapi per wake model found; "no model to test" when none are downloaded. */
    private fun probeModels(context: Context): List<AccelReport.Nnapi> {
        val dir = context.filesDir
        val cls = safe { WakeGate.cfgName(context) } ?: WakeGate.DEFAULT_CLASSIFIER
        val models = listOf(
            Triple("mel", File(dir, WakeGate.MEL), longArrayOf(1, WakeGate.MEL_CTX.toLong())),
            Triple("emb", File(dir, WakeGate.EMB), longArrayOf(1, WakeGate.MEL_WIN.toLong(), WakeGate.MEL_BINS.toLong(), 1)),
            Triple("cls", File(dir, cls), longArrayOf(1, WakeGate.EMB_WIN.toLong(), WakeGate.EMB_DIM.toLong())),
        ).filter { it.second.exists() }
        if (models.isEmpty()) {
            Log.i(TAG, "model none: no model to test (the wake models are not downloaded; download them in Settings first)")
            return emptyList()
        }
        val env = OrtEnvironment.getEnvironment()
        return models.map { (tag, file, shape) ->
            val n = shape.fold(1L) { a, b -> a * b }.toInt()
            val input = FloatArray(n) { ((it * 7919) % 2001 - 1000) / 10f }   // same fixed input as WakeGate
            val cpu = timed(env, tag, "cpu", file, shape, input) { OrtSession.SessionOptions() }
            val nnapi = timed(env, tag, "nnapi", file, shape, input) {
                OrtSession.SessionOptions().apply { addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED)) }
            }
            val xnn = timed(env, tag, "xnnpack", file, shape, input) {
                OrtSession.SessionOptions().apply { addXnnpack(emptyMap()) }
            }
            val diff = if (cpu != null && nnapi != null) WakeGate.maxAbsDiff(cpu.second, nnapi.second) else null
            val scale = cpu?.second?.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            Log.i(TAG, AccelReport.modelLine(tag, cpu?.first, nnapi?.first, xnn?.first, diff))
            AccelReport.nnapiVerdict(true, cpu?.first, nnapi?.first, diff ?: Float.MAX_VALUE, scale)
        }
    }

    /** (ms per run, output) for one provider, or null after logging why it failed. */
    private fun timed(
        env: OrtEnvironment, tag: String, provider: String, file: File, shape: LongArray, input: FloatArray,
        options: () -> OrtSession.SessionOptions,
    ): Pair<Double, FloatArray>? {
        val t0 = System.nanoTime()
        val session = try {
            options().use { env.createSession(file.absolutePath, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "model $tag $provider: session failed: ${AccelReport.shortError(t)}")
            return null
        }
        val createMs = (System.nanoTime() - t0) / 1e6
        return session.use { s ->
            try {
                val name = s.inputNames.first()
                val out = run1(env, s, name, shape, input)
                repeat(WARMUP) { run1(env, s, name, shape, input) }
                val t1 = System.nanoTime()
                repeat(TIMED) { run1(env, s, name, shape, input) }
                val ms = (System.nanoTime() - t1) / 1e6 / TIMED
                Log.i(TAG, "model $tag $provider: created in ${AccelReport.ms(createMs)}ms")
                ms to out
            } catch (t: Throwable) {
                Log.w(TAG, "model $tag $provider: run failed: ${AccelReport.shortError(t)}")
                null
            }
        }
    }

    private fun run1(env: OrtEnvironment, s: OrtSession, name: String, shape: LongArray, data: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(name to t)).use { r ->
                val buf = (r[0] as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

    /** File names in [dir] matching [keep]; null when the directory can't be listed (SELinux). */
    private fun listNames(dir: String, keep: (String) -> Boolean): List<String>? =
        safe { File(dir).list() }?.filter(keep)?.sorted()

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    companion object {
        private const val TAG = "UltraAccel"
        private const val WARMUP = 3
        private const val TIMED = 10
        private val LIB_DIRS = listOf("/vendor/lib64", "/system/lib64", "/system/vendor/lib64")
        private val running = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
