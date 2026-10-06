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
        jintArray jCpus, jboolean jTiming) {
    const bool timing_on = jTiming == JNI_TRUE;
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
    // Timing (ambient only): timestamps on, per token, so the transcript's pause/overlap cues
    // can be placed between words by when whisper says each word was spoken (mapped back to
    // the original audio when VAD trimmed silence). Timestamp decoding may segment the text
    // differently and shift token probabilities; unmeasured. Off = exactly the old decode.
    p.no_timestamps    = !timing_on;
    p.token_timestamps = timing_on;
    p.suppress_blank   = true;
    p.temperature      = 0.0f;
    p.language         = "en";
    p.initial_prompt   = prompt;              // wake-name spelling (ambient: + fillers, see WhisperNative)
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
    std::string timing;   // "W\t<t0 ms>\t<t1 ms>\t<word>\n" per word, "V\t<t0 ms>\t<t1 ms>\n" per VAD segment
    double logprobSum = 0.0; int tok = 0;

    bool ok;
    {
        AffinityScope pin(cpus);
        ok = whisper_full(c->w, p, pcm.data(), (int)pcm.size()) == 0;
    }
    if (ok) {
        int segs = whisper_full_n_segments(c->w);
        // Words = text tokens joined until the next one that starts with a space. Bytes are
        // joined before anything leaves, so a character split across tokens stays whole.
        std::string word; int64_t w0 = 0, w1 = 0;
        auto flush = [&]{
            size_t a = word.find_first_not_of(' ');
            if (a != std::string::npos) {
                std::string clean = word.substr(a);
                for (auto& ch : clean) if (ch == '\t' || ch == '\n' || ch == '\0') ch = ' ';
                timing += "W\t" + std::to_string(w0 * 10) + "\t" + std::to_string(w1 * 10) + "\t" + clean + "\n";
            }
            word.clear();
        };
        for (int i = 0; i < segs; i++) {
            text += whisper_full_get_segment_text(c->w, i);
            int nt = whisper_full_n_tokens(c->w, i);
            for (int j = 0; j < nt; j++) {
                auto d = whisper_full_get_token_data(c->w, i, j);
                if (d.id >= whisper_token_eot(c->w)) continue;  // skip specials
                logprobSum += d.plog; tok++;
                if (!timing_on) continue;
                const char* piece = whisper_full_get_token_text(c->w, i, j);
                if (!piece || !*piece) continue;
                int64_t t0 = whisper_full_get_token_t0(c->w, i, j);   // centiseconds, original audio
                int64_t t1 = whisper_full_get_token_t1(c->w, i, j);
                if (piece[0] == ' ' || word.empty()) { if (!word.empty()) flush(); w0 = t0; }
                word += piece; w1 = t1;
            }
        }
        if (!word.empty()) flush();
        int nv = timing_on ? whisper_full_n_vad_segments(c->w) : 0;
        for (int i = 0; i < nv; i++) {
            timing += "V\t" + std::to_string(whisper_full_get_vad_segment_t0(c->w, i) * 10) + "\t" +
                      std::to_string(whisper_full_get_vad_segment_t1(c->w, i) * 10) + "\n";
        }
    }
    env->ReleaseStringUTFChars(jPrompt, prompt);

    float meanP = tok > 0 ? (float)std::exp(logprobSum / tok) : 0.0f;
    // trim leading spaces whisper prepends
    size_t s = text.find_first_not_of(" \t\n");
    if (s == std::string::npos) return empty();
    std::string out = std::to_string(meanP) + "\t" + text.substr(s);
    // A NUL, then the timing lines (WhisperNative.parse splits on it). No timing: the old format.
    if (timing_on) {
        out.push_back('\0');
        out += timing;
    }

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
