package com.agent.jarvis.local

/** JNI declarations for the llama.cpp shim (libultra_llm.so). */
object LlmNative {

    /** The build that loaded (see [CpuVariant]), or null. Logged so speed reports say which.
     *  Declared before [available]: initialisers run in order, and load() sets it. */
    var variant: String? = null
        private set

    /**
     * False when libultra_llm.so isn't in this build — the x86_64 emulator build (-Pbench)
     * has no llama.cpp. Every caller goes through [LocalModelEngine.ensureLoaded], which
     * checks this, so an agent without an on-device model degrades to the cloud brain
     * instead of throwing UnsatisfiedLinkError on first touch.
     */
    @JvmField val available: Boolean = load()

    private fun load(): Boolean {
        for (name in CpuVariant.read()) {
            try {
                System.loadLibrary(name)
                variant = name
                android.util.Log.i("JarvisLlm", "cpu variant=$name")
                return true
            } catch (e: Throwable) {
                android.util.Log.w("JarvisLlm", "$name not loadable: ${e.message}")
            }
        }
        android.util.Log.w("JarvisLlm", "native model library not in this build")
        return false
    }

    fun interface TokenCallback {
        /** Return text to force-feed into the context without sampling (e.g. ThinkBudget's
         *  "</think>"), null to continue normally, or [STOP] to end the generation here (the
         *  token just delivered is not decoded; the KV cache stays consistent). */
        fun onToken(piece: String): String?
    }

    /** Returned by a [TokenCallback] to stop generating (U+FFFF, a noncharacter no model text
     *  is injected as; ultra_llm.cpp kStop matches its UTF-8). */
    const val STOP = "\uFFFF"

    /**
     * Returns a handle, or 0 on failure. [cpus] non-empty: one persistent pool of [threads]
     * workers pinned to those cores (see [CpuTopology]), and the generating thread is pinned
     * to them for the duration of each [nativeGenerate]; empty = unpinned, workers per decode.
     */
    external fun nativeLoad(path: String, threads: Int, ctxSize: Int, cpus: IntArray, resident: Boolean): Long

    /**
     * Runs the prompt synchronously on the calling thread, streaming tokens
     * to [callback]. Returns the number of generated tokens, or a negative
     * error code.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        threads: Int,          // <= 0 keeps the current count
        draftMax: Int,         // > 0 with a draft attached: speculative decoding, drafts up to this long
        callback: TokenCallback?,
    ): Int

    /**
     * Loads the speculative-decoding draft model ([DraftModel]) beside [handle] with a [ctxSize]
     * context. Returns that ctx (> 0), 0 when the file didn't load, -1 when its vocabulary
     * isn't the loaded model's (llama.cpp's own compatibility check), -2 when its context
     * couldn't be created, -3 when the loaded model (or the draft) has recurrent/hybrid memory
     * (Qwen3.5), whose rejected draft tokens can't be rolled back. On failure nothing stays
     * loaded and generation runs without it.
     */
    external fun nativeAttachDraft(handle: Long, path: String, ctxSize: Int): Int

    /** Frees the draft model (no-op without one); generation continues without it. */
    external fun nativeDetachDraft(handle: Long)

    /** The last [nativeGenerate]'s speculation: [drafted, accepted]; drafted = -1 = no draft used. */
    external fun nativeSpecStats(handle: Long): IntArray

    external fun nativeFree(handle: Long)

    /** The ctx [handle] was created with: the one asked of [nativeLoad], or 4096 if that failed. */
    external fun nativeContextSize(handle: Long): Int
}
