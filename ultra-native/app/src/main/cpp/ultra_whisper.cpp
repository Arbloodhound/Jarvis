// whisper.cpp JNI bridge. Built as its own .so, SEPARATE from ultra_llm, with
// ggml symbols kept local (see CMakeLists) so llama's ggml and whisper's ggml
// never bind to each other at load time.
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include "whisper.h"
#include "affinity_scope.h"

struct Ctx {
    whisper_context* w = nullptr;
    std::string vadPath;   // empty = VAD off
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_agent_ultra_listen_WhisperNative_nativeInit(
        JNIEnv* env, jobject, jstring jModel, jstring jVad) {
    const char* model = env->GetStringUTFChars(jModel, nullptr);
    const char* vad   = env->GetStringUTFChars(jVad, nullptr);

    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;                       // PowerVR: CPU is the reliable path
    whisper_context* w = whisper_init_from_file_with_params(model, cp);

    auto* c = new Ctx();
    c->w = w;
    c->vadPath = vad ? vad : "";

    env->ReleaseStringUTFChars(jModel, model);
    env->ReleaseStringUTFChars(jVad, vad);
    if (!w) { delete c; return 0; }
    return reinterpret_cast<jlong>(c);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_agent_ultra_listen_WhisperNative_nativeTranscribe(
        JNIEnv* env, jobject, jlong handle, jfloatArray jpcm, jint threads, jstring jPrompt,
        jintArray jCpus) {
    auto* c = reinterpret_cast<Ctx*>(handle);
    auto empty = [&]{ return env->NewByteArray(0); };
    if (!c || !c->w) return empty();

    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_special    = false;
    p.single_segment   = false;
    p.no_timestamps    = true;
    p.suppress_blank   = true;
    p.temperature      = 0.0f;
    p.language         = "en";
    p.initial_prompt   = prompt;              // biases spelling of the wake name
    if (!c->vadPath.empty()) {
        p.vad            = true;
        p.vad_model_path = c->vadPath.c_str();
    }

    // CPU list (the cores the LLM doesn't use, from CpuTopology): pin this thread for the
    // transcription. whisper's ggml workers are created per graph and inherit this mask.
    // The caller is a shared Kotlin dispatcher thread, so the scope unpins it afterwards.
    // Empty list = unpinned, as before.
    std::vector<int> cpus;
    if (jCpus) {
        jsize nc = env->GetArrayLength(jCpus);
        std::vector<jint> raw(nc);
        env->GetIntArrayRegion(jCpus, 0, nc, raw.data());
        cpus.assign(raw.begin(), raw.end());
    }

    std::string text;
    double logprobSum = 0.0; int tok = 0;

    bool ok;
    {
        AffinityScope pin(cpus);
        ok = whisper_full(c->w, p, pcm.data(), (int)pcm.size()) == 0;
    }
    if (ok) {
        int segs = whisper_full_n_segments(c->w);
        for (int i = 0; i < segs; i++) {
            text += whisper_full_get_segment_text(c->w, i);
            int nt = whisper_full_n_tokens(c->w, i);
            for (int j = 0; j < nt; j++) {
                auto d = whisper_full_get_token_data(c->w, i, j);
                if (d.id >= whisper_token_eot(c->w)) continue;  // skip specials
                logprobSum += d.plog; tok++;
            }
        }
    }
    env->ReleaseStringUTFChars(jPrompt, prompt);

    float meanP = tok > 0 ? (float)std::exp(logprobSum / tok) : 0.0f;
    // trim leading spaces whisper prepends
    size_t s = text.find_first_not_of(" \t\n");
    if (s == std::string::npos) return empty();
    std::string out = std::to_string(meanP) + "\t" + text.substr(s);

    jbyteArray arr = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(arr, 0, (jsize)out.size(),
                            reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_agent_ultra_listen_WhisperNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* c = reinterpret_cast<Ctx*>(handle);
    if (c) { if (c->w) whisper_free(c->w); delete c; }
}
