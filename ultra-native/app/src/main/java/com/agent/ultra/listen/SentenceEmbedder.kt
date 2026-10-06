package com.agent.ultra.listen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.io.File
import java.nio.LongBuffer

/**
 * On-device sentence embeddings: sentence-transformers/all-MiniLM-L6-v2 (Apache-2.0),
 * the Hub's own ONNX export quantised to int8 for arm64 (onnx/model_qint8_arm64.onnx,
 * 23,026,053 bytes) + its BERT uncased vocab.txt. 384-d, mean-pooled, L2-normalised —
 * the same recipe as the model's 1_Pooling + Normalize modules.
 *
 * Downloaded once by [fetch] (like the whisper / wake models). Placement is chosen like
 * [WakeGate]: CPU vs XNNPACK vs NNAPI (the Pixel's "google-edgetpu"), timed, the fastest
 * whose output matches the CPU's. Blocking: call from Dispatchers.IO / Default only.
 */
class SentenceEmbedder private constructor(model: File, vocab: File) {
    private val env = OrtEnvironment.getEnvironment()
    private val tokenizer = WordPiece(vocab.readLines(), MAX_LEN)
    private val modelFile = model
    @Volatile private var session: OrtSession
    var placement = ""; private set

    init {
        session = placed(model)
    }

    /** One normalised 384-d vector per text. An accelerator session that fails at run time
     * (e.g. NNAPI refusing a new input shape) is replaced by a plain CPU session once. */
    fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val encoded = texts.map { tokenizer.encode(it) }
        val s = session
        return try {
            run(s, encoded)
        } catch (e: Exception) {
            if (placement == "cpu") throw e
            android.util.Log.w(TAG, "embedder $placement failed at run time (${e.message}); cpu from now on")
            val cpu = synchronized(this) {
                if (session === s) {
                    session = cpuSession(modelFile)
                    placement = "cpu"
                    runCatching { s.close() }
                }
                session
            }
            run(cpu, encoded)
        }
    }

    fun embed(text: String): FloatArray = embed(listOf(text)).first()

    private fun run(s: OrtSession, encoded: List<IntArray>): List<FloatArray> {
        val b = encoded.size
        val len = encoded.maxOf { it.size }
        val ids = LongArray(b * len) { tokenizer.pad.toLong() }
        val mask = LongArray(b * len)
        val maskRows = Array(b) { IntArray(len) }
        for ((i, e) in encoded.withIndex()) for ((t, id) in e.withIndex()) {
            ids[i * len + t] = id.toLong(); mask[i * len + t] = 1; maskRows[i][t] = 1
        }
        val shape = longArrayOf(b.toLong(), len.toLong())
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            for (name in s.inputNames) tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(
                when (name) {
                    "input_ids" -> ids
                    "attention_mask" -> mask
                    else -> LongArray(b * len)          // token_type_ids: all segment 0
                }), shape)
            s.run(tensors).use { r ->
                val out = (r.get("last_hidden_state").orElse(null) ?: r[0]) as OnnxTensor
                val fb = out.floatBuffer
                val flat = FloatArray(fb.remaining()).also { fb.get(it) }
                val dim = flat.size / (b * len)
                // An export that already pools gives [b, dim]: just normalise.
                if (out.info.shape.size == 2) {
                    val d2 = flat.size / b
                    return List(b) { MemoryVectors.normalize(flat.copyOfRange(it * d2, (it + 1) * d2)) }
                }
                return MemoryVectors.meanPoolNormalize(flat, maskRows, len, dim)
            }
        } finally { tensors.values.forEach { runCatching { it.close() } } }
    }

    private fun placed(file: File): OrtSession {
        val cpu = cpuSession(file)
        val sessions = LinkedHashMap<String, OrtSession>()
        candidate(file, "xnnpack") {
            setIntraOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
        }?.let { sessions["xnnpack"] = it }
        candidate(file, "nnapi") {
            setIntraOpNumThreads(THREADS)
            addNnapi(java.util.EnumSet.of(NNAPIFlags.CPU_DISABLED))
        }?.let { sessions["nnapi"] = it }
        val choice = if (sessions.isEmpty()) "cpu" else try {
            val sample = listOf(tokenizer.encode(SAMPLE))
            val cpuOut = run(cpu, sample).first()
            val cpuMs0 = timeMs { run(cpu, sample) }
            val measured = LinkedHashMap<String, Pair<Double, Float>>()
            for ((name, s) in sessions) try {
                val out = run(s, sample).first()
                measured[name] = timeMs { run(s, sample) } to WakeGate.maxAbsDiff(cpuOut, out)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "embedder: $name failed (${e.message})")
            }
            val cpuMs = minOf(cpuMs0, timeMs { run(cpu, sample) })
            // Outputs are unit vectors: scale 1, so a match means every component within 1e-3.
            val c = WakeGate.choosePlacement(cpuMs, measured, 1f)
            fun ms(k: String) = measured[k]?.let { "%.2fms".format(it.first) } ?: "n/a"
            fun df(k: String) = measured[k]?.let { "%.1e".format(it.second) } ?: "n/a"
            android.util.Log.i(TAG, "embedder placement: cpu=%.2fms xnnpack=%s nnapi=%s diff=%s/%s -> %s"
                .format(cpuMs, ms("xnnpack"), ms("nnapi"), df("xnnpack"), df("nnapi"), c))
            c
        } catch (e: Exception) {
            android.util.Log.w(TAG, "embedder placement check failed (${e.message}); cpu")
            "cpu"
        }
        placement = choice
        if (sessions.isEmpty()) android.util.Log.i(TAG, "embedder placement: cpu (no accelerator session)")
        for ((name, s) in sessions) if (name != choice) runCatching { s.close() }
        return sessions[choice]?.also { runCatching { cpu.close() } } ?: cpu
    }

    private fun cpuSession(file: File): OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(THREADS); env.createSession(file.absolutePath, o)
    }

    private fun candidate(file: File, name: String, setup: OrtSession.SessionOptions.() -> Unit): OrtSession? =
        try {
            OrtSession.SessionOptions().use { o -> o.setup(); env.createSession(file.absolutePath, o) }
        } catch (e: Exception) {
            android.util.Log.i(TAG, "embedder $name unavailable (${e.message})")
            null
        }

    private inline fun timeMs(block: () -> Unit): Double {
        repeat(2) { block() }
        val t0 = System.nanoTime()
        repeat(5) { block() }
        return (System.nanoTime() - t0) / 1e6 / 5
    }

    companion object {
        private const val TAG = "UltraMemory"
        const val MAX_LEN = 128
        const val DIM = 384
        private const val THREADS = 2       // leave the big cores to the LLM
        private const val SAMPLE = "Remind me to call the dentist about Thursday's appointment."

        const val MODEL = "minilm-l6-v2-qint8-arm64.onnx"
        const val VOCAB = "minilm-l6-v2-vocab.txt"
        private const val HUB = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/"
        const val MODEL_URL = HUB + "onnx/model_qint8_arm64.onnx"
        const val VOCAB_URL = HUB + "vocab.txt"
        // Sizes checked on the Hub (hf_fs stat, 2026-10-06).
        const val MODEL_BYTES = 23_026_053L
        const val VOCAB_BYTES = 231_508L

        /** Downloads the model + vocab once (blocking, ~23 MB). True when both are present. */
        fun fetch(ctx: Context): Boolean {
            val dir = ctx.applicationContext.filesDir
            return ModelFetcher.ensure(dir, VOCAB, VOCAB_URL, exactBytes = VOCAB_BYTES) &&
                ModelFetcher.ensure(dir, MODEL, MODEL_URL, exactBytes = MODEL_BYTES)
        }

        @Volatile private var shared: SentenceEmbedder? = null
        @Volatile private var failed = false

        /** The loaded embedder, or null when its files aren't downloaded yet or it can't load.
         * Never downloads (the indexer does that). The first call loads + times (~1–2 s). */
        fun shared(ctx: Context): SentenceEmbedder? {
            shared?.let { return it }
            if (failed) return null
            val dir = ctx.applicationContext.filesDir
            val m = File(dir, MODEL); val v = File(dir, VOCAB)
            if (m.length() != MODEL_BYTES || v.length() != VOCAB_BYTES) return null
            return synchronized(this) {
                shared ?: try {
                    SentenceEmbedder(m, v).also {
                        shared = it
                        android.util.Log.i(TAG, "embedder on: ${it.placement}")
                    }
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "embedder failed to load: ${e.message}")
                    failed = true
                    null
                }
            }
        }
    }
}
