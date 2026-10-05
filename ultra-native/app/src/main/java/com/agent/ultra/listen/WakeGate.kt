package com.agent.ultra.listen

// ⚠️ VERIFY BEFORE BUILD: the ONNX input names ("input", "input_1") and the
// window sizes (76 mel frames, 16 embeddings, 32 mel bins) below follow
// openWakeWord's documented pipeline, but must be checked against the ACTUAL
// model files you ship (Netron, or `onnxruntime` getInputInfo). If a name or
// shape differs, accept() throws, is caught, and returns false — the service
// then falls back to transcribe-then-text-match. Fail-safe, but fix the shapes
// to get the battery win. Reference runtime: rementia/openwakeword-android-kt.

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Acoustic wake-word gate (openWakeWord). Tiny ONNX pipeline that runs on EVERY
 * frame so whisper can stay asleep until the name is actually spoken.
 *
 * Three-stage openWakeWord pipeline (all Apache-2.0 ONNX from dscripka/openWakeWord):
 *   16kHz PCM → melspectrogram.onnx → embedding_model.onnx → <wakeword>.onnx → score
 *
 * Models live in filesDir (downloaded like the ASR models). If any is missing,
 * [ready] is false and the service falls back to transcribe-then-text-match.
 *
 * This is deliberately minimal: it keeps rolling mel frames and feeds the
 * embedding + classifier the way the openWakeWord runtime does. Thresholds and
 * buffer sizes mirror the upstream defaults (score > threshold = detection).
 */
class WakeGate(context: Context, private val threshold: Float = 0.5f) {

    private val env = OrtEnvironment.getEnvironment()
    private var mel: OrtSession? = null
    private var emb: OrtSession? = null
    private var cls: OrtSession? = null

    // rolling buffers (openWakeWord: 76 mel frames -> 1 embedding; 16 embeddings -> classifier)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embFrames = ArrayDeque<FloatArray>()

    val ready: Boolean

    init {
        val dir = context.filesDir
        val m = File(dir, MEL); val e = File(dir, EMB); val c = File(dir, cfgName(context))
        ready = if (m.exists() && e.exists() && c.exists()) {
            try {
                mel = env.createSession(m.absolutePath)
                emb = env.createSession(e.absolutePath)
                cls = env.createSession(c.absolutePath)
                true
            } catch (_: Exception) { false }
        } else false
    }

    /**
     * Push one 32ms frame (512 samples @16k). Returns true on the frame where
     * the wake word is detected. Cheap enough to call on every frame.
     */
    fun accept(frame: ShortArray): Boolean {
        val m = mel ?: return false
        val e = emb ?: return false
        val c = cls ?: return false
        try {
            // 1) mel
            val pcm = FloatArray(frame.size) { frame[it] / 32768f }
            val melOut = run1(m, "input", arrayOf(longArrayOf(1, pcm.size.toLong())), pcm)
            // melOut is [1, nFrames, 32]; push each 32-d mel frame
            val nf = melOut.size / 32
            for (i in 0 until nf) {
                melFrames.addLast(melOut.copyOfRange(i * 32, i * 32 + 32))
                if (melFrames.size > 76) melFrames.removeFirst()
            }
            if (melFrames.size < 76) return false
            // 2) embedding over the 76-frame window
            val flatMel = FloatArray(76 * 32).also { out ->
                var o = 0; for (f in melFrames) { f.copyInto(out, o); o += 32 }
            }
            val embOut = run1(e, "input_1", arrayOf(longArrayOf(1, 76, 32, 1)), flatMel)
            embFrames.addLast(embOut)
            if (embFrames.size > 16) embFrames.removeFirst()
            if (embFrames.size < 16) return false
            // 3) classifier over the 16-embedding window
            val flatEmb = FloatArray(16 * embOut.size).also { out ->
                var o = 0; for (f in embFrames) { f.copyInto(out, o); o += f.size }
            }
            val score = run1(c, "input", arrayOf(longArrayOf(1, 16, embOut.size.toLong())), flatEmb)
            return (score.firstOrNull() ?: 0f) > threshold
        } catch (_: Exception) {
            return false
        }
    }

    fun reset() { melFrames.clear(); embFrames.clear() }

    private fun run1(s: OrtSession, name: String, shape: Array<LongArray>, data: FloatArray): FloatArray {
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape[0])
        s.run(mapOf(name to t)).use { r ->
            val out = r[0].value
            return flatten(out)
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

    fun close() { mel?.close(); emb?.close(); cls?.close() }

    companion object {
        const val MEL = "melspectrogram.onnx"
        const val EMB = "embedding_model.onnx"
        // default pretrained classifier; a custom-trained one overrides via prefs
        const val DEFAULT_CLASSIFIER = "hey_jarvis.onnx"
        fun cfgName(context: Context): String =
            context.getSharedPreferences("ambient", Context.MODE_PRIVATE)
                .getString("wake_onnx", DEFAULT_CLASSIFIER) ?: DEFAULT_CLASSIFIER
    }
}
