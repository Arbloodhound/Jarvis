package com.agent.ultra.local

/** JNI declarations for the llama.cpp shim (libultra_llm.so). */
object LlmNative {

    /**
     * False when libultra_llm.so isn't in this build — the x86_64 emulator build (-Pbench)
     * has no llama.cpp. Every caller goes through [LocalModelEngine.ensureLoaded], which
     * checks this, so an agent without an on-device model degrades to the cloud brain
     * instead of throwing UnsatisfiedLinkError on first touch.
     */
    @JvmField val available: Boolean = try {
        System.loadLibrary("ultra_llm")
        true
    } catch (e: Throwable) {
        android.util.Log.w("UltraLlm", "native model library not in this build: ${e.message}")
        false
    }

    fun interface TokenCallback {
        /** Return text to force-feed into the context without sampling (e.g. ThinkBudget's
         *  "</think>"), or null to continue normally. */
        fun onToken(piece: String): String?
    }

    /** Returns a handle, or 0 on failure. */
    external fun nativeLoad(path: String, threads: Int, ctxSize: Int): Long

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
}
