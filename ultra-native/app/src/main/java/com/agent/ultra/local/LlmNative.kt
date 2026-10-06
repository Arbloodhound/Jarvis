package com.agent.ultra.local

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
                android.util.Log.i("UltraLlm", "cpu variant=$name")
                return true
            } catch (e: Throwable) {
                android.util.Log.w("UltraLlm", "$name not loadable: ${e.message}")
            }
        }
        android.util.Log.w("UltraLlm", "native model library not in this build")
        return false
    }

    fun interface TokenCallback {
        /** Return text to force-feed into the context without sampling (e.g. ThinkBudget's
         *  "</think>"), or null to continue normally. */
        fun onToken(piece: String): String?
    }

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
        callback: TokenCallback?,
    ): Int

    external fun nativeFree(handle: Long)

    /** The ctx [handle] was created with: the one asked of [nativeLoad], or 4096 if that failed. */
    external fun nativeContextSize(handle: Long): Int
}
