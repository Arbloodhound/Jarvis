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

    /** UTF-8 bytes of "<meanTokenP>\t<text>", a NUL, then timing lines (see [parse]); empty when
     *  nothing was said. [cpus] non-empty: the calling thread (and so whisper's workers) is pinned
     *  to them for the call. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int, prompt: String, cpus: IntArray): ByteArray

    external fun nativeFree(handle: Long)

    /** [words]: when whisper says each word was spoken, ms from the start of the audio (rough:
     *  whisper's token timestamps). [vad]: the speech stretches whisper's VAD found, same clock. */
    data class Result(
        val text: String,
        val confidence: Float,
        val words: List<TimedWord> = emptyList(),
        val vad: List<Span> = emptyList(),
    )

    /**
     * Ambient prompt: a run of fillers, then the wake name (for its spelling). whisper tends to
     * leave "um" and "uh" out unless its context shows them, and the transcript cues want them
     * kept as said. It only biases the decoder: whisper has no verbatim switch. Pure, JVM-tested.
     */
    fun verbatimPrompt(wakeName: String): String = "Um, uh, so, like, you know, I mean, hmm. ${wakeName.trim()}."

    fun transcribe(handle: Long, pcm16: ShortArray, threads: Int, prompt: String, cpus: IntArray): Result? {
        val f = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        return parse(String(nativeTranscribe(handle, f, threads, prompt, cpus), Charsets.UTF_8))
    }

    /** The native reply as a [Result]; null when nothing was said. Malformed timing lines are
     *  skipped (the text still counts). Pure, JVM-tested. */
    fun parse(raw: String): Result? {
        val nul = raw.indexOf('\u0000')
        val head = if (nul < 0) raw else raw.substring(0, nul)
        val tab = head.indexOf('\t')
        if (tab < 0) return null
        val text = head.substring(tab + 1).trim()
        if (text.isEmpty()) return null
        val words = ArrayList<TimedWord>()
        val vad = ArrayList<Span>()
        if (nul >= 0) for (line in raw.substring(nul + 1).split('\n')) {
            val parts = line.split('\t', limit = 4)
            val t0 = parts.getOrNull(1)?.toLongOrNull() ?: continue
            val t1 = parts.getOrNull(2)?.toLongOrNull() ?: continue
            if (t1 < t0) continue
            when (parts[0]) {
                "W" -> parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { words += TimedWord(it, t0, t1) }
                "V" -> vad += Span(t0, t1)
            }
        }
        return Result(text, head.substring(0, tab).toFloatOrNull() ?: 0f, words, vad)
    }
}
