package com.agent.jarvis.listen

// VERIFIED against the real openWakeWord v0.5.1 release models and the upstream
// Python reference (openwakeword 0.6.0, utils.AudioFeatures._streaming_features):
//   melspectrogram.onnx   in  "input"   [batch, samples] float  (raw int16 VALUES, not /32768)
//                         out [time, 1, frames, 32]  -> then spec/10 + 2
//   embedding_model.onnx  in  "input_1" [N, 76, 32, 1]   out [N, 1, 1, 96]
//   hey_jarvis_v0.1.onnx  in  "x.1"     [1, 16, 96]      out [1, 1]
// Input names are read from each session at load, so a custom classifier with a
// different input name still works. A Python mirror of this exact algorithm scored
// 0.998 on a "hey jarvis" clip and 0.000 on a negative sentence, identical to upstream.

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.FloatBuffer

/**
 * Acoustic wake-word gate (openWakeWord), run on every mic frame so whisper can
 * stay asleep until the name is spoken.
 *
 *   16kHz PCM -> melspectrogram.onnx -> embedding_model.onnx -> <wakeword>.onnx -> score
 *
 * Streaming contract (matches upstream): audio is consumed in 80 ms chunks
 * (1280 samples). Each chunk runs mel over the last 1760 samples (chunk + 3 hops
 * of context) -> 8 new mel frames; one embedding from the last 76 mel frames;
 * the classifier scores the last 16 embeddings (~1.3 s of audio).
 *
 * Any frame size can be pushed; it is re-chunked internally. If a model file is
 * missing or fails to load, [ready] is false and the service uses text-match wake.
 */
class WakeGate(
    context: Context,
    private val threshold: Float = 0.5f,
    private val refractoryMs: Long = 1500L,
) {
    private val env = OrtEnvironment.getEnvironment()
    private var mel: OrtSession? = null
    private var emb: OrtSession? = null
    private var cls: OrtSession? = null
    private var melIn = ""; private var embIn = ""; private var clsIn = ""

    private val pending = FloatArray(CHUNK); private var pendingN = 0
    private val raw = FloatArray(MEL_CTX)          // rolling last 1760 samples
    private var rawFilled = 0
    private val melFrames = ArrayDeque<FloatArray>()
    private val embFrames = ArrayDeque<FloatArray>()
    private var lastFire = 0L

    /** Where each model runs, e.g. "mel=nnapi emb=cpu cls=cpu" (see [placed]). Logged at load. */
    var placement = ""; private set

    /** Last classifier score, for a settings-screen meter / threshold tuning. */
    @Volatile var lastScore = 0f; private set

    val ready: Boolean

    init {
        val dir = context.filesDir
        val m = File(dir, MEL); val e = File(dir, EMB); val c = File(dir, cfgName(context))
        ready = if (m.exists() && e.exists() && c.exists()) {
            try {
                mel = placed("mel", m, longArrayOf(1, MEL_CTX.toLong())).also { melIn = it.inputNames.first() }
                emb = placed("emb", e, longArrayOf(1, MEL_WIN.toLong(), MEL_BINS.toLong(), 1)).also { embIn = it.inputNames.first() }
                cls = placed("cls", c, longArrayOf(1, EMB_WIN.toLong(), EMB_DIM.toLong())).also { clsIn = it.inputNames.first() }
                android.util.Log.i("UltraAmbient", "wake gate on: $placement")
                true
            } catch (_: Exception) { close(); false }
        } else false
    }

    /**
     * One model's session, on whichever of three places is measurably faster and gives the
     * same numbers: the CPU (ONNX Runtime's default kernels), the CPU through XNNPACK (ONNX
     * Runtime's optimised small-model CPU kernels, bundled in the library — any phone), or the
     * phone's own accelerator (GPU / NPU through Android's NNAPI — part of Android, no Google
     * services). A phone whose NNAPI has no accelerator driver, or whose driver can't run these
     * ops, just loses that candidate. These models run every 80 ms all day, so the choice is
     * worth a few milliseconds of timing at start-up.
     */
    private fun placed(tag: String, file: File, shape: LongArray): OrtSession {
        val cpu = env.createSession(file.absolutePath)
        val sessions = LinkedHashMap<String, OrtSession>()   // candidates that loaded
        candidate(file, "xnnpack") {
            // XNNPACK runs its own thread pool; ORT's intra-op pool stays at 1 so the two
            // don't fight, and nothing spins between the 80 ms bursts (ORT's XNNPACK guidance).
            setIntraOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addXnnpack(mapOf("intra_op_num_threads" to XNNPACK_THREADS.toString()))
        }?.let { sessions["xnnpack"] = it }
        candidate(file, "nnapi") {
            // CPU_DISABLED: never NNAPI's slow reference CPU path; ops the accelerator
            // can't take run on ONNX Runtime's own CPU kernels instead.
            addNnapi(java.util.EnumSet.of(NNAPIFlags.CPU_DISABLED))
        }?.let { sessions["nnapi"] = it }
        if (sessions.isEmpty()) { placement += "$tag=cpu "; return cpu }
        val choice = try {
            val n = shape.fold(1L) { a, b -> a * b }.toInt()
            val input = FloatArray(n) { ((it * 7919) % 2001 - 1000) / 10f }   // fixed, speech-scale values
            val cpuIn = cpu.inputNames.first()
            val cpuOut = run1(cpu, cpuIn, shape, input)
            val cpuMs0 = timeMs { run1(cpu, cpuIn, shape, input) }
            val measured = LinkedHashMap<String, Pair<Double, Float>>()   // name -> (ms, maxDiff)
            for ((name, s) in sessions) {
                try {
                    val sIn = s.inputNames.first()
                    val out = run1(s, sIn, shape, input)
                    measured[name] = timeMs { run1(s, sIn, shape, input) } to maxAbsDiff(cpuOut, out)
                } catch (e: Exception) {
                    android.util.Log.w("UltraAmbient", "wake model $tag: $name failed (${e.message})")
                }
            }
            // CPU timed again after the candidates, best of two: clocks ramping up during the
            // first timing must not make a later candidate look faster than it is.
            val cpuMs = minOf(cpuMs0, timeMs { run1(cpu, cpuIn, shape, input) })
            val scale = cpuOut.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val c = choosePlacement(cpuMs, measured, scale)
            fun ms(k: String) = measured[k]?.let { "%.2fms".format(it.first) } ?: "n/a"
            fun df(k: String) = measured[k]?.let { "%.1e".format(it.second) } ?: "n/a"
            // diff is xnnpack/nnapi, each vs the CPU output
            android.util.Log.i("UltraAmbient", "wake model $tag: cpu=%.2fms xnnpack=%s nnapi=%s diff=%s/%s -> %s"
                .format(cpuMs, ms("xnnpack"), ms("nnapi"), df("xnnpack"), df("nnapi"), c))
            c
        } catch (e: Exception) {
            android.util.Log.w("UltraAmbient", "wake model $tag: placement check failed (${e.message}); cpu")
            "cpu"
        }
        placement += "$tag=$choice "
        for ((name, s) in sessions) if (name != choice) runCatching { s.close() }
        return sessions[choice]?.also { runCatching { cpu.close() } } ?: cpu
    }

    /** A session with [setup] applied, or null when this phone / build can't create it. */
    private fun candidate(file: File, name: String, setup: OrtSession.SessionOptions.() -> Unit): OrtSession? =
        try {
            OrtSession.SessionOptions().use { o -> o.setup(); env.createSession(file.absolutePath, o) }
        } catch (e: Exception) {
            android.util.Log.i("UltraAmbient", "wake $name unavailable (${e.message})")
            null
        }

    private inline fun timeMs(run: () -> Unit): Double {
        repeat(WARMUP) { run() }
        val t0 = System.nanoTime()
        repeat(TIMED) { run() }
        return (System.nanoTime() - t0) / 1e6 / TIMED
    }

    /** Push PCM of any length. Returns true on the chunk where the wake word fires. */
    fun accept(frame: ShortArray): Boolean {
        if (!ready) return false
        var fired = false
        var i = 0
        while (i < frame.size) {
            val take = minOf(CHUNK - pendingN, frame.size - i)
            for (k in 0 until take) pending[pendingN + k] = frame[i + k].toFloat() // int16 range, unscaled
            pendingN += take; i += take
            if (pendingN == CHUNK) {
                pendingN = 0
                if (processChunk()) fired = true
            }
        }
        return fired
    }

    private fun processChunk(): Boolean {
        val m = mel ?: return false; val e = emb ?: return false; val c = cls ?: return false
        try {
            // slide raw window: drop oldest CHUNK samples, append new chunk
            System.arraycopy(raw, CHUNK, raw, 0, MEL_CTX - CHUNK)
            System.arraycopy(pending, 0, raw, MEL_CTX - CHUNK, CHUNK)
            rawFilled = minOf(MEL_CTX, rawFilled + CHUNK)
            if (rawFilled < MEL_CTX) return false

            // 1) mel over chunk + context -> 8 frames of 32 bins, transform x/10 + 2
            val melOut = run1(m, melIn, longArrayOf(1, MEL_CTX.toLong()), raw)
            val nf = melOut.size / MEL_BINS
            for (f in 0 until nf) {
                melFrames.addLast(FloatArray(MEL_BINS) { melOut[f * MEL_BINS + it] / 10f + 2f })
            }
            while (melFrames.size > MEL_WIN) melFrames.removeFirst()
            if (melFrames.size < MEL_WIN) return false

            // 2) one embedding per 80 ms chunk, over the last 76 mel frames
            val flatMel = FloatArray(MEL_WIN * MEL_BINS).also { out ->
                var o = 0; for (f in melFrames) { f.copyInto(out, o); o += MEL_BINS }
            }
            val embOut = run1(e, embIn, longArrayOf(1, MEL_WIN.toLong(), MEL_BINS.toLong(), 1), flatMel)
            embFrames.addLast(embOut)                         // 96-d
            while (embFrames.size > EMB_WIN) embFrames.removeFirst()
            if (embFrames.size < EMB_WIN) return false

            // 3) classifier over the last 16 embeddings
            val d = embOut.size
            val flatEmb = FloatArray(EMB_WIN * d).also { out ->
                var o = 0; for (f in embFrames) { f.copyInto(out, o); o += d }
            }
            val score = run1(c, clsIn, longArrayOf(1, EMB_WIN.toLong(), d.toLong()), flatEmb)
                .firstOrNull() ?: 0f
            lastScore = score
            val now = System.currentTimeMillis()
            if (score > threshold && now - lastFire > refractoryMs) { lastFire = now; return true }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    fun reset() {
        pendingN = 0; rawFilled = 0; raw.fill(0f)
        melFrames.clear(); embFrames.clear(); lastScore = 0f
    }

    private fun run1(s: OrtSession, name: String, shape: LongArray, data: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(name to t)).use { r -> return flatten(r[0].value) }
        }
    }

    private fun flatten(v: Any?): FloatArray {
        val acc = ArrayList<Float>()
        fun rec(o: Any?) {
            when (o) {
                is FloatArray -> o.forEach { acc.add(it) }
                is Array<*> -> o.forEach { rec(it) }
                is Float -> acc.add(o)
            }
        }
        rec(v); return acc.toFloatArray()
    }

    fun close() { mel?.close(); emb?.close(); cls?.close(); mel = null; emb = null; cls = null }

    companion object {
        const val CHUNK = 1280            // 80 ms @ 16 kHz
        const val MEL_CTX = CHUNK + 480   // + 3 x 160-sample hops of context
        const val MEL_BINS = 32
        const val MEL_WIN = 76
        const val EMB_WIN = 16
        const val EMB_DIM = 96              // embedding width (embedding_model.onnx output)
        private const val WARMUP = 3
        private const val TIMED = 10
        private const val XNNPACK_THREADS = 2   // always-on service: keep the thread count modest

        /**
         * Keep the accelerator only when it is clearly faster (at least 20%: timing noise and
         * the cost of waking it must not flip the choice) and its numbers match the CPU's
         * (an fp16-relaxed or buggy driver would move the wake score). Pure, JVM-tested.
         */
        fun useAccelerator(cpuMs: Double, accelMs: Double, maxDiff: Float, scale: Float): Boolean =
            accelMs > 0 && accelMs <= cpuMs * 0.8 && maxDiff <= 1e-3f * maxOf(1f, scale)

        /**
         * Where a wake model runs: among [candidates] (name -> (ms, maxDiff vs the CPU output))
         * that pass [useAccelerator], the fastest; otherwise "cpu". A candidate whose session
         * failed to load or run is simply absent. Pure, JVM-tested.
         */
        fun choosePlacement(cpuMs: Double, candidates: Map<String, Pair<Double, Float>>, scale: Float): String =
            candidates.filter { (_, r) -> useAccelerator(cpuMs, r.first, r.second, scale) }
                .minByOrNull { it.value.first }?.key ?: "cpu"

        fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return Float.MAX_VALUE
            var m = 0f
            for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]))
            return m
        }

        const val MEL = "melspectrogram.onnx"
        const val EMB = "embedding_model.onnx"
        const val DEFAULT_CLASSIFIER = "hey_jarvis.onnx"

        // Apache-2.0 release assets (save hey_jarvis_v0.1.onnx as DEFAULT_CLASSIFIER)
        const val REL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"
        const val MEL_URL = REL + "melspectrogram.onnx"
        const val EMB_URL = REL + "embedding_model.onnx"
        const val JARVIS_URL = REL + "hey_jarvis_v0.1.onnx"

        fun cfgName(context: Context): String =
            context.getSharedPreferences("ambient", Context.MODE_PRIVATE)
                .getString("wake_onnx", DEFAULT_CLASSIFIER) ?: DEFAULT_CLASSIFIER
    }
}
