package com.agent.jarvis.local

/**
 * The vision-language model's JNI entry points, in the same libjarvis_llm*.so as [LlmNative]
 * (jarvis_vlm.cpp, llama.cpp's mtmd). [available] is false on a build without them.
 */
object VisionNative {
    /** The natives are present: the library [LlmNative] loaded has them. */
    val available: Boolean by lazy {
        LlmNative.available && try { nativeVlmFree(0L); true } catch (e: Throwable) {
            android.util.Log.w("JarvisVlm", "vision natives missing: ${e.message}"); false
        }
    }

    /** Text model + image projector; 0 on failure. [cpus]: pin like the thinker (empty = unpinned). */
    external fun nativeVlmLoad(modelPath: String, mmprojPath: String, threads: Int, nCtx: Int, cpus: IntArray): Long

    /** [rgb]: tightly packed RGB888, [width] x [height]. The answer, or null on failure. */
    external fun nativeVlmDescribe(handle: Long, rgb: ByteArray, width: Int, height: Int, prompt: String, maxTokens: Int, threads: Int): String?

    external fun nativeVlmFree(handle: Long)
}
