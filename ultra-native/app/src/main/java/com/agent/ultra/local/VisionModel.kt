package com.agent.ultra.local

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen vision (owner, 2026-10-07: "a vision model … another two to four gigabytes is fine"):
 * Qwen2.5-VL 3B Instruct, Q4_K_M, with its Q8_0 image projector (ggml-org; 1,929,901,056 +
 * 844,757,728 bytes, checked on Hugging Face 2026-10-07). Used only when the screen can't be read
 * as text (accessibility tree and ML Kit text recognition both come up nearly empty: images, maps,
 * games, canvas). Loaded on demand, freed after [IDLE_MS]; screenshots are scaled down to
 * [MAX_SIDE] so encoding stays a few seconds on the phone CPU.
 */
object VisionModel {
    const val MODEL = "qwen2.5-vl-3b-q4km.gguf"
    const val MODEL_URL = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
    const val MODEL_BYTES = 1_929_901_056L
    const val MMPROJ = "qwen2.5-vl-3b-mmproj-q8.gguf"
    const val MMPROJ_URL = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"
    const val MMPROJ_BYTES = 844_757_728L
    const val TOTAL_BYTES = MODEL_BYTES + MMPROJ_BYTES
    /** Longest side of the picture the model sees: a phone screen's text stays legible, encoding stays short. */
    const val MAX_SIDE = 896
    private const val IDLE_MS = 120_000L
    private const val THREADS = 4

    private fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun modelFile(c: Context) = File(dir(c), MODEL)
    fun mmprojFile(c: Context) = File(dir(c), MMPROJ)

    /** Both files downloaded, at their exact sizes. */
    fun present(c: Context) = modelFile(c).length() == MODEL_BYTES && mmprojFile(c).length() == MMPROJ_BYTES

    /** Bytes downloaded so far across both files (for a progress line). */
    fun downloadedBytes(c: Context): Long = listOf(modelFile(c), mmprojFile(c)).sumOf { f ->
        if (f.exists()) f.length() else ParallelDownload.bytesSoFar(File(f.path + LocalModelEngine.PART_SUFFIX))
    }

    private val fetching = java.util.concurrent.atomic.AtomicBoolean(false)
    val downloading: Boolean get() = fetching.get()

    /** Download both files (4 connections each, resumable). Blocking work on IO; true when both are in. */
    suspend fun fetch(c: Context): Boolean = withContext(Dispatchers.IO) {
        if (present(c)) return@withContext true
        if (!fetching.compareAndSet(false, true)) return@withContext false
        try {
            val client = okhttp3.OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
            for ((file, url, size) in listOf(Triple(modelFile(c), MODEL_URL, MODEL_BYTES), Triple(mmprojFile(c), MMPROJ_URL, MMPROJ_BYTES))) {
                if (file.length() == size) continue
                val part = File(file.path + LocalModelEngine.PART_SUFFIX)
                var ok = false
                for (attempt in 1..5) {
                    try { ParallelDownload.run(client, url, part, size) {}; ok = true; break }
                    catch (e: java.io.IOException) {
                        android.util.Log.w("UltraVlm", "vision download ${file.name} attempt $attempt: ${e.message}")
                        delay(2_000L * attempt)
                    }
                }
                if (!ok || !part.renameTo(file)) return@withContext false
            }
            present(c)
        } finally { fetching.set(false) }
    }

    /** [fetch] in the background, so leaving Settings does not stop it. [done] gets the result. */
    fun startFetch(c: Context, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        scope.launch { done(runCatching { fetch(app) }.getOrDefault(false)) }
    }

    /** Both files and any unfinished download of them; nothing while a download runs. */
    fun delete(c: Context) {
        if (downloading) return
        unload()
        for (f in listOf(modelFile(c), mmprojFile(c))) {
            val part = File(f.path + LocalModelEngine.PART_SUFFIX)
            ParallelDownload.planFile(part).delete(); part.delete(); f.delete()
        }
    }

    private val lock = Mutex()
    private var handle = 0L
    private var idle: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ready(c: Context) = present(c) && VisionNative.available

    private fun unload() {
        scope.launch { lock.withLock { if (handle != 0L) { VisionNative.nativeVlmFree(handle); handle = 0L } } }
    }

    /**
     * The model's answer to [prompt] about [bitmap], or null when it isn't downloaded, can't load or
     * fails. The memory model gives way while this runs.
     */
    suspend fun describe(c: Context, bitmap: Bitmap, prompt: String, maxTokens: Int = 220): String? {
        if (!ready(c)) return null
        com.agent.ultra.listen.Librarian.yieldNow()
        return withContext(Dispatchers.IO) { lock.withLock {
            idle?.cancel()
            if (handle == 0L) {
                val t0 = System.currentTimeMillis()
                handle = VisionNative.nativeVlmLoad(modelFile(c).path, mmprojFile(c).path, THREADS, 4096,
                    CpuTopology.current.llmCpus.toIntArray())
                android.util.Log.i("UltraVlm", "load handle=${handle != 0L} in ${System.currentTimeMillis() - t0} ms")
                if (handle == 0L) return@withLock null
            }
            val scaled = scale(bitmap, MAX_SIDE)
            val rgb = rgbBytes(scaled)
            val w = scaled.width; val h = scaled.height
            if (scaled !== bitmap) scaled.recycle()
            val t1 = System.currentTimeMillis()
            val out = VisionNative.nativeVlmDescribe(handle, rgb, w, h, prompt, maxTokens, THREADS)
            android.util.Log.i("UltraVlm", "describe ${w}x$h in ${System.currentTimeMillis() - t1} ms: ${out?.take(80)}")
            idle = scope.launch { delay(IDLE_MS); lock.withLock { if (handle != 0L) { VisionNative.nativeVlmFree(handle); handle = 0L } } }
            out?.trim()?.takeIf { it.isNotEmpty() }
        } }
    }

    private fun scale(b: Bitmap, maxSide: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= maxSide) return b
        val f = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun rgbBytes(b: Bitmap): ByteArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = ByteArray(px.size * 3)
        var o = 0
        for (p in px) { out[o++] = (p shr 16).toByte(); out[o++] = (p shr 8).toByte(); out[o++] = p.toByte() }
        return out
    }
}
