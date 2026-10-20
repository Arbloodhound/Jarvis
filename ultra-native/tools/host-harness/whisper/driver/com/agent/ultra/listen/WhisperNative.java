package com.agent.ultra.listen;

/** Host mirror of WhisperNative.kt's externals: a Kotlin `object`, so instance methods on INSTANCE. */
public final class WhisperNative {
    public static final WhisperNative INSTANCE = new WhisperNative();
    private WhisperNative() { }
    public final native long nativeInit(String modelPath, String vadPath);
    public final native byte[] nativeTranscribe(long handle, float[] pcm, int threads, String prompt, int[] cpus, boolean timing);
    public final native void nativeFree(long handle);
}
