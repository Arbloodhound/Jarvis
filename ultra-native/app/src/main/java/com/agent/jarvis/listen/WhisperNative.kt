package com.agent.jarvis.listen

import com.agent.jarvis.local.CpuVariant

/**
 * whisper.cpp through JNI (libjarvis_whisper.so). Built separately from the
 * llama library with its ggml symbols hidden, so the two ggml copies never
 * collide at runtime. One context, one caller at a time.
 */
object WhisperNative {

    /** The build that loaded (see [CpuVariant]), or null; logged as "whisper cpu variant=".
     *  Declared before [available]: initialisers run in order, and load() sets it. */
    var variant: String? = null
        private set

    val available: Boolean = load()

    /** Best CPU variant first (jarvis_whisper_i8mm, _dotprod), falling back to armv8-a jarvis_whisper. */
    private fun load(): Boolean {
        for (name in CpuVariant.read("jarvis_whisper")) {
            try {
                System.loadLibrary(name)
                variant = name
                android.util.Log.i("JarvisAmbient", "whisper cpu variant=$name")
                return true
            } catch (e: Throwable) {
                android.util.Log.w("JarvisAmbient", "$name not loadable: ${e.message}")
            }
        }
        android.util.Log.w("JarvisAmbient", "whisper library not in this build")
        return false
    }

    /** Returns 0 on failure. vadPath may be empty to disable whisper's VAD. */
    external fun nativeInit(modelPath: String, vadPath: String): Long

    /** UTF-8 bytes of "<meanTokenP>\t<text>"; with [timing], then a NUL and timing lines (see
     *  [parse]); empty when nothing was said. [timing] false decodes exactly as before timing
     *  existed (no timestamps). [cpus] non-empty: the calling thread (and so whisper's workers) is
     *  pinned to them for the call. */
    external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, threads: Int, prompt: String, cpus: IntArray, timing: Boolean,
    ): ByteArray

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

    private val PROMPT_FILLERS = setOf("um", "uh", "so", "like", "you", "know", "i", "mean", "hmm")

    /** Only words from [verbatimPrompt]'s filler run ("Um, uh.", "you know, I mean"): no content,
     *  and what whisper produces when it echoes its prompt on noise. Pure, JVM-tested. */
    fun onlyPromptFillers(text: String): Boolean {
        val words = text.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in PROMPT_FILLERS }
    }

    /** [timing]: word times and VAD spans for the transcript cues (ambient only); off for
     *  interactive speech, which doesn't use them. */
    fun transcribe(
        handle: Long, pcm16: ShortArray, threads: Int, prompt: String, cpus: IntArray, timing: Boolean = false,
    ): Result? {
        val f = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        return parse(String(nativeTranscribe(handle, f, threads, prompt, cpus, timing), Charsets.UTF_8))
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
            if (t0 < 0 || t1 < t0) continue
            when (parts[0]) {
                "W" -> parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { words += TimedWord(it, t0, t1) }
                "V" -> vad += Span(t0, t1)
            }
        }
        return Result(text, head.substring(0, tab).toFloatOrNull() ?: 0f, words, vad)
    }
}
