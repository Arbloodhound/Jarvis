#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "UltraLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t ctxSize = 0;
};

bool backendReady = false;

// Global callback refs for the in-flight generation (single-flight by design).
JavaVM *gVm = nullptr;
jobject gCallback = nullptr;
jmethodID gOnToken = nullptr;

// Delivers one piece to Kotlin. Kotlin may return text to force-feed into the
// context without sampling (ThinkBudget's "</think>" at the budget); empty = none.
std::string deliverToken(const std::string &piece) {
    std::string inject;
    if (!gVm || !gCallback || !gOnToken) return inject;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return inject;
        attached = true;
    }
    if (env) {
        jstring s = env->NewStringUTF(piece.c_str());
        auto r = static_cast<jstring>(env->CallObjectMethod(gCallback, gOnToken, s));
        env->DeleteLocalRef(s);
        if (env->ExceptionCheck()) env->ExceptionClear();
        else if (r) {
            const char *c = env->GetStringUTFChars(r, nullptr);
            if (c) { inject = c; env->ReleaseStringUTFChars(r, c); }
            env->DeleteLocalRef(r);
        }
    }
    if (attached) gVm->DetachCurrentThread();
    return inject;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_agent_ultra_local_LlmNative_nativeLoad(
        JNIEnv *env, jobject, jstring jpath, jint threads, jint ctxSize) {
    if (!backendReady) { llama_backend_init(); backendReady = true; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU path — measured winner on the A15 bench

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctxSize;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) { LOGE("ctx init failed"); llama_model_free(model); return 0; }

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    engine->ctxSize = ctxSize;
    LOGI("loaded: ctx=%d threads=%d", ctxSize, threads);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_agent_ultra_local_LlmNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint maxTokens,
        jfloat temperature, jint threads, jobject callback) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->ctx) return -1;

    // Every call is a fresh, self-contained prompt. Without this the KV cache kept
    // every earlier prompt + reply: positions grew call over call until decode
    // failed at ctxSize, and the model saw old turns as part of the new prompt.
    llama_memory_clear(llama_get_memory(engine->ctx), true);

    // ThermalGuard: fewer threads when hot. Applies to this and later calls.
    if (threads > 0) llama_set_n_threads(engine->ctx, threads, threads);

    const char *promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptC);
    env->ReleaseStringUTFChars(jprompt, promptC);

    // Tokenize
    std::vector<llama_token> tokens(prompt.size() + 32);
    int nTokens = llama_tokenize(engine->vocab, prompt.c_str(), prompt.size(),
                                 tokens.data(), tokens.size(), true, true);
    if (nTokens < 0) { LOGE("tokenize overflow"); return -2; }
    tokens.resize(nTokens);
    if ((int) tokens.size() >= engine->ctxSize - maxTokens - 8) {
        LOGE("prompt too long for ctx"); return -3;
    }

    // Register streaming callback
    if (callback) {
        env->GetJavaVM(&gVm);
        gCallback = env->NewGlobalRef(callback);
        gOnToken = env->GetMethodID(env->GetObjectClass(callback), "onToken", "(Ljava/lang/String;)Ljava/lang/String;");
    } else {
        gCallback = nullptr;
    }

    // Release the callback on EVERY exit from here down, not just the happy one.
    //
    // The prompt-decode failure below returns -4 between minting that global
    // reference and releasing it. That leaked the reference, and left gCallback
    // non-null and dangling for whatever called next — a stale pointer into a
    // Java object that may no longer exist. A guard makes the class of mistake
    // impossible rather than fixing the one path that happened to show it: any
    // return added later is covered without anyone having to remember.
    struct CallbackScope {
        JNIEnv *env;
        explicit CallbackScope(JNIEnv *e) : env(e) {}
        ~CallbackScope() {
            if (gCallback) { env->DeleteGlobalRef(gCallback); gCallback = nullptr; }
            gOnToken = nullptr;
        }
        CallbackScope(const CallbackScope &) = delete;
        CallbackScope &operator=(const CallbackScope &) = delete;
    } cbScope(env);

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(engine->ctx, batch) != 0) { LOGE("prompt decode failed"); return -4; }

    // Sampler: low-temp deterministic-ish for agent work
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));

    int generated = 0;
    int nPast = (int) tokens.size();
    for (; generated < maxTokens; generated++) {
        if (nPast >= engine->ctxSize - 1) { LOGE("ctx full at %d", nPast); break; }
        llama_token tok = llama_sampler_sample(chain, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, tok)) break;

        char buf[256];
        int len = llama_token_to_piece(engine->vocab, tok, buf, sizeof(buf), 0, true);
        std::string inject;
        if (len > 0) inject = deliverToken(std::string(buf, len));

        llama_sampler_accept(chain, tok);
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(engine->ctx, next) != 0) { LOGE("decode failed mid-gen"); break; }
        nPast++;

        if (!inject.empty()) {
            // Decode the forced text in one batch, no sampling; its last logits
            // seed the next sample. parse_special=true so "</think>" is one token.
            std::vector<llama_token> inj(inject.size() + 8);
            int n = llama_tokenize(engine->vocab, inject.c_str(), inject.size(),
                                   inj.data(), inj.size(), false, true);
            if (n > 0 && nPast + n < engine->ctxSize - 1) {
                inj.resize(n);
                llama_batch ib = llama_batch_get_one(inj.data(), n);
                if (llama_decode(engine->ctx, ib) != 0) { LOGE("inject decode failed"); break; }
                nPast += n;
            } else {
                LOGE("inject skipped (n=%d, nPast=%d)", n, nPast);
            }
        }
    }

    llama_sampler_free(chain);
    return generated;   // cbScope releases the callback
}

JNIEXPORT void JNICALL
Java_com_agent_ultra_local_LlmNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine) return;
    if (engine->ctx) llama_free(engine->ctx);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}

} // extern "C"
