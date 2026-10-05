package com.agent.ultra.listen

/**
 * whisper.cpp through JNI (libultra_whisper.so). Built separately from the
 * llama library with its ggml symbols hidden, so the two ggml copies never
 * collide at runtime. One context, one caller at a time.
 */
object WhisperNative {
    val available: Boolean = try {
        System.loadLibrary("ultra_whisper"); true
    } catch (_: UnsatisfiedLinkError) { false }

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
