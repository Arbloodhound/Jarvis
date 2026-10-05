package com.agent.ultra.local;

/** Host mirror of LlmNative.kt. If your unit changes a JNI signature, change it here too. */
public final class LlmNative {
    public interface TokenCallback { String onToken(String piece); }
    public static native long nativeLoad(String path, int threads, int ctxSize, int[] cpus);
    public static native int nativeGenerate(long handle, String prompt, int maxTokens,
                                            float temperature, int threads, TokenCallback cb);
    public static native void nativeFree(long handle);
}
