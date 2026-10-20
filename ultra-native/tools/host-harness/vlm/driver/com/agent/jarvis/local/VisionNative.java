package com.agent.jarvis.local;

/**
 * Host mirror of VisionNative.kt. A Kotlin `object`'s `external fun` (no @JvmStatic) compiles to
 * a final INSTANCE method on a class with a static INSTANCE field, so the JNI functions receive
 * the INSTANCE as their jobject: mirrored exactly here.
 */
public final class VisionNative {
    public static final VisionNative INSTANCE = new VisionNative();
    private VisionNative() { }
    public final native long nativeVlmLoad(String modelPath, String mmprojPath, int threads, int nCtx, int[] cpus);
    public final native String nativeVlmDescribe(long handle, byte[] rgb, int width, int height, String prompt, int maxTokens, int threads);
    public final native void nativeVlmFree(long handle);
}
