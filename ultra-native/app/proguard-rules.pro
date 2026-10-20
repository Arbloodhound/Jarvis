# Release hardening. R8 is on: names are minified so the shipped APK does not
# hand a reader the design. Everything below is something that WOULD break if
# it were renamed, with the reason stated — nothing is kept "just in case".

# ── JNI: bound by symbol name, not by registration ───────────────────────
# ultra_llm.cpp exports Java_com_agent_jarvis_local_LlmNative_nativeLoad and
# friends. Rename the class or the methods and the lookup fails at runtime,
# the moment the on-device model is first used.
-keep class com.agent.jarvis.local.LlmNative { *; }
# ultra_vlm.cpp (same .so) exports Java_com_agent_jarvis_local_VisionNative_nativeVlmLoad,
# _nativeVlmDescribe and _nativeVlmFree: same by-name binding, same failure if renamed.
-keep class com.agent.jarvis.local.VisionNative { *; }

# The native side resolves this callback reflectively:
#   GetMethodID(GetObjectClass(callback), "onToken", "(Ljava/lang/String;)Ljava/lang/String;")
# (It returns text to inject since patch 0007; the old `void` rule matched nothing.)
-keep interface com.agent.jarvis.local.LlmNative$TokenCallback { *; }
-keepclassmembers class * implements com.agent.jarvis.local.LlmNative$TokenCallback {
    java.lang.String onToken(java.lang.String);
}
-keepclasseswithmembernames class * { native <methods>; }

# ONNX Runtime (the wake-word gate). Its JNI library looks up its own Java classes and
# constructors by name (ai/onnxruntime/TensorInfo, OrtException, OnnxTensor(JJLTensorInfo;)…);
# the AAR's consumer rules keep only its telemetry classes, so R8 renamed the rest and the
# first OrtSession.run in a release build failed. Debug builds aren't minified, so only
# release showed it.
-keep class ai.onnxruntime.** { *; }

# sherpa-onnx (Jarvis's own voice). libsherpa-onnx-jni.so binds OfflineTts's natives by name,
# reads every Offline*Config field by name (GetFieldID "model", "vits", "dataDir"…), builds
# GeneratedAudio through its ([FI)V constructor, and calls the progress lambda's invoke.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ── Components named as strings outside the code ─────────────────────────
# The accessibility service is written into Settings.Secure by its fully
# qualified name and matched by the system on every bind.
# Class NAMES only — their methods are called from Kotlin and can be renamed.
# Keeping the members too was leaving agentMayUse readable for no reason.
-keep,allowobfuscation,allowoptimization class com.agent.jarvis.AgentAccessibilityService
-keep,allowobfuscation,allowoptimization class com.agent.jarvis.AgentBackgroundService
-keep,allowobfuscation,allowoptimization class com.agent.jarvis.UltraNotificationService

# ── Room ─────────────────────────────────────────────────────────────────
# Generated code addresses entity fields directly, and the DAOs are resolved
# through the generated implementation.
-keep class com.agent.jarvis.data.** { *; }

# Quieter build: these are optional annotations R8 warns about but never needs.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
