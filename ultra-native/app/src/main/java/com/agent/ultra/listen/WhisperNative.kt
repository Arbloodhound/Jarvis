package com.agent.ultra.listen

import com.agent.ultra.local.CpuVariant

/**
 * whisper.cpp through JNI (libultra_whisper.so). Built separately from the
 * llama library with its ggml symbols hidden, so the two ggml copies never
 * collide at runtime. One context, one caller at a time.
 */
object WhisperNative {

    /** The build that loaded (see [CpuVariant]), or null; logged as "whisper cpu variant=".
     *  Declared before [available]: initialisers run in order, and load() sets it. */
    var variant: String? = null
        private set

    val available: Boolean = load()

    /** Best CPU variant first (ultra_whisper_i8mm, _dotprod), falling back to armv8-a ultra_whisper. */
    private fun load(): Boolean {
        for (name in CpuVariant.read("ultra_whisper")) {
            try {
                System.loadLibrary(name)
                variant = name
                android.util.Log.i("UltraAmbient", "whisper cpu variant=$name")
                return true
            } catch (e: Throwable) {
                android.util.Log.w("UltraAmbient", "$name not loadable: ${e.message}")
            }
        }
        android.util.Log.w("UltraAmbient", "whisper library not in this build")
        return false
    }

    /** Returns 0 on failure. vadPath may be empty to disable whisper's VAD. */
    external fun nativeInit(modelPath: String, vadPath: String): Long

    /** UTF-8 bytes of "<meanTokenP>\t<text>"; empty when nothing was said. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int, prompt: String): ByteArray

    external fun nativeFree(handle: Long)

    data class Result(val text: String, val confidence: Float)

    fun transcribe(handle: Long, pcm16: ShortArray, threads: Int, prompt: String): Result? {
        val f = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        val raw = String(nativeTranscribe(handle, f, threads, prompt), Charsets.UTF_8)
        val tab = raw.indexOf('\t')
        if (tab < 0) return null
        val text = raw.substring(tab + 1).trim()
        if (text.isEmpty()) return null
        return Result(text, raw.substring(0, tab).toFloatOrNull() ?: 0f)
    }
}
