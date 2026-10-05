#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "ggml-cpu.h"
#include "affinity_scope.h"

#define TAG "UltraLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t ctxSize = 0;
    // Pinned engines only (null/empty = ggml's per-decode threads, unpinned).
    ggml_threadpool *pool = nullptr;
    int poolThreads = 0;   // its worker count (ggml_threadpool_get_n_threads is declared, not defined)
    std::vector<int> cpus;
    // Exactly the tokens in the KV cache (sequence 0), in position order: token i
    // sits at position i. Prompt tokens, then every sampled token that was decoded,
    // then every injected token decoded. Empty = the memory is (to be) cleared.
    std::vector<llama_token> cached;
};

// Runs the pinned pool for one generation. The caller is ggml's worker 0, but ggml pins it
// only when it kicks off a PAUSED pool and then never unpins it, which would leave a shared
// Kotlin dispatcher thread stuck on the LLM cores. So the caller is pinned here, the pool is
// woken here, and on every exit the pool sleeps (no polling between replies) and the caller
// is unpinned.
struct PoolScope {
    ggml_threadpool *pool;
    AffinityScope pin;
    PoolScope(ggml_threadpool *p, const std::vector<int> &cpus)
            : pool(p), pin(p ? cpus : std::vector<int>()) {
        if (pool) ggml_threadpool_resume(pool);
    }
    ~PoolScope() { if (pool) ggml_threadpool_pause(pool); }   // then `pin` unpins the caller
    PoolScope(const PoolScope &) = delete;
    PoolScope &operator=(const PoolScope &) = delete;
};

// Drops the KV cache and its token record together, so the next call prefills
// from position 0. Used on every decode failure: after a failed decode the memory
// may hold a partial batch, and a record that disagrees with the memory would
// make the next call reuse KV it does not describe.
void resetCache(Engine *engine) {
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    engine->cached.clear();
}

bool backendReady = false;

// Global callback refs for the in-flight generation (single-flight by design).
JavaVM *gVm = nullptr;
jobject gCallback = nullptr;
jmethodID gOnToken = nullptr;

// Text crosses JNI as UTF-16, never through the *StringUTF* calls: those speak
// Modified UTF-8, which has no 4-byte sequences (emoji, rarer CJK) and rejects a
// character split across two token pieces. A byte-level BPE (Qwen) emits exactly
// those splits; CheckJNI aborts a debug build on them.
std::string gUtf8Pending;   // bytes of a character split across token pieces

void appendUtf16(std::u16string &out, uint32_t cp) {
    if (cp >= 0x10000) {
        cp -= 0x10000;
        out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
        out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
        out.push_back(static_cast<char16_t>(cp));
    }
}

// Complete characters of pending+piece as UTF-16; an incomplete tail stays pending.
// Invalid bytes become U+FFFD.
std::u16string takeCompleteUtf16(const std::string &piece) {
    gUtf8Pending += piece;
    const std::string &s = gUtf8Pending;
    const size_t n = s.size();
    std::u16string out;
    size_t i = 0;
    while (i < n) {
        const auto c = static_cast<unsigned char>(s[i]);
        const int len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0) { out.push_back(0xFFFD); i++; continue; }
        if (i + len > n) {
            bool cont = true;
            for (size_t k = i + 1; k < n; k++) if ((static_cast<unsigned char>(s[k]) & 0xC0) != 0x80) cont = false;
            if (cont) break;                  // wait for the rest of this character
            out.push_back(0xFFFD); i++; continue;
        }
        uint32_t cp = len == 1 ? c : len == 2 ? (c & 0x1F) : len == 3 ? (c & 0x0F) : (c & 0x07);
        bool bad = false;
        for (int k = 1; k < len; k++) {
            const auto cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) { bad = true; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (bad) { out.push_back(0xFFFD); i++; continue; }
        appendUtf16(out, cp);
        i += len;
    }
    gUtf8Pending.erase(0, i);
    return out;
}

std::string jstringToUtf8(JNIEnv *env, jstring js) {
    std::string out;
    if (!js) return out;
    const jsize n = env->GetStringLength(js);
    const jchar *u = env->GetStringChars(js, nullptr);
    if (!u) return out;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;                      // lone surrogate
        }
        if (cp < 0x80) out += static_cast<char>(cp);
        else if (cp < 0x800) { out += static_cast<char>(0xC0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18)); out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(js, u);
    return out;
}

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
        // One callback per token even when the piece is only part of a character
        // (then ""): ThinkBudget counts tokens by callbacks.
        const std::u16string u = takeCompleteUtf16(piece);
        jstring s = env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
        auto r = static_cast<jstring>(env->CallObjectMethod(gCallback, gOnToken, s));
        env->DeleteLocalRef(s);
        if (env->ExceptionCheck()) env->ExceptionClear();
        else if (r) {
            inject = jstringToUtf8(env, r);
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
        JNIEnv *env, jobject, jstring jpath, jint threads, jint ctxSize, jintArray jcpus) {
    if (!backendReady) { llama_backend_init(); backendReady = true; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU path — measured winner on the A15 bench

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctxSize;
    // KV cache at q8_0 instead of the f16 default: about half the bytes (Qwen3-30B-A3B
    // at 4096 ctx: ~384 MB f16 -> ~204 MB q8_0), and on a 16 GB phone holding ~10 GB
    // of weights it is memory, not compute, that decides whether the model stays
    // resident. A quantized V cache needs flash attention; llama.cpp would promote
    // AUTO to ENABLED for it anyway, so ENABLED here only states the requirement.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    // The prompt goes to llama_decode as ONE batch, and llama.cpp aborts the
    // process (GGML_ASSERT) when a batch exceeds n_batch (default 2048). The
    // length guard in generate() allows up to ~ctxSize, so n_batch must match.
    cparams.n_batch = ctxSize;
    // Full-size cache for sliding-window layers (Gemma): a window-sized cache drops early
    // positions, and then generate() can never reuse the shared prompt prefix (it falls
    // back to a full prefill). No effect on models without SWA (the Qwen3 MoE).
    cparams.swa_full = true;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        // llama.cpp refuses q8_0 K/V when a head dim is not a multiple of the
        // 32-wide q8_0 block, or when flash attention cannot be enabled. Fall back
        // to the old behaviour (f16 K/V, flash attention AUTO) rather than fail.
        // A null context can also mean an allocation failure; the llama.cpp log
        // line just above this one says which.
        LOGE("ctx init failed with q8_0 KV + flash attention (cause in llama.cpp log); retrying with f16 KV");
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, cparams);
    }
    if (!ctx) { LOGE("ctx init failed"); llama_model_free(model); return 0; }

    // CPU list (the fastest cores, from CpuTopology): ONE persistent pool of `threads`
    // workers on those cores. Without it ggml creates and joins `threads` new workers on
    // every llama_decode, each inheriting whatever cores the calling dispatcher thread may
    // use, little ones included. Empty list = exactly that old behaviour.
    std::vector<int> cpus;
    if (jcpus) {
        const jsize n = env->GetArrayLength(jcpus);
        std::vector<jint> raw(n);
        env->GetIntArrayRegion(jcpus, 0, n, raw.data());
        for (jint c : raw) if (c >= 0 && c < GGML_MAX_N_THREADS && c < CPU_SETSIZE) cpus.push_back(c);
    }
    ggml_threadpool *pool = nullptr;
    if (!cpus.empty() && threads > 0) {
        ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
        for (int c : cpus) tpp.cpumask[c] = true;
        tpp.strict_cpu = false;   // each worker may run on any listed core; the kernel balances
        tpp.paused = true;        // asleep until nativeGenerate; workers pin themselves on start
        tpp.poll = 10;            // brief spin between tokens, then sleep (default 50 spins longer)
        pool = ggml_threadpool_new(&tpp);
        // Larger n_threads (llama_set_n_threads) is clamped by ggml; smaller leaves workers idle.
        llama_attach_threadpool(ctx, pool, nullptr);
    }

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    engine->ctxSize = ctxSize;
    engine->pool = pool;
    engine->poolThreads = pool ? threads : 0;
    engine->cpus = cpus;
    LOGI("loaded: ctx=%d threads=%d pinnedCpus=%zu kv=%s", ctxSize, threads, cpus.size(),
         ggml_type_name(cparams.type_k));
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_agent_ultra_local_LlmNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint maxTokens,
        jfloat temperature, jint threads, jobject callback) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->ctx) return -1;

    // ThermalGuard: fewer threads when hot. Applies to this and later calls. A pinned pool
    // has exactly its load-time workers; ggml would clamp a larger count with a warning on
    // every decode, so clamp here once.
    if (threads > 0) {
        if (engine->pool) threads = std::min<int>(threads, engine->poolThreads);
        llama_set_n_threads(engine->ctx, threads, threads);
    }

    const std::string prompt = jstringToUtf8(env, jprompt);
    gUtf8Pending.clear();

    // Tokenize
    std::vector<llama_token> tokens(prompt.size() + 32);
    int nTokens = llama_tokenize(engine->vocab, prompt.c_str(), prompt.size(),
                                 tokens.data(), tokens.size(), true, true);
    // -2 and -3 leave the KV memory and its record untouched (both still agree), so
    // a retry with a trimmed prompt can still reuse the shared prefix.
    if (nTokens < 0) { LOGE("tokenize overflow"); return -2; }
    tokens.resize(nTokens);
    if ((int) tokens.size() >= engine->ctxSize - maxTokens - 8) {
        LOGE("prompt too long for ctx"); return -3;
    }

    // Every call is still a fresh, self-contained prompt; only the KV of its
    // longest common token prefix with the previous call is kept. Clearing the
    // whole cache each call (the fix for positions growing call over call until
    // decode failed at ctxSize, and old turns leaking into the new prompt) threw
    // away a system prompt + history that consecutive turns share, and re-prefilled
    // it every turn.
    //
    // Neither old failure can come back: everything from position p on is removed
    // before decoding, and positions 0..p-1 hold the KV of tokens[0..p-1] — the
    // same tokens at the same positions this prompt has, and a token's KV depends
    // only on the tokens before it. So the memory ends up holding this prompt and
    // nothing else, and positions top out at n_prompt + generated as before. The
    // only difference from a fresh prefill is float summation order: the prefix was
    // computed in a different batch split, and the CPU flash-attention kernel is not
    // batch-invariant (host test at 8345f33: last-token logits differ by ~1e-3,
    // bitwise equal with flash attention off; the removed tail leaves no trace —
    // prefix + junk + seq_rm + suffix is bitwise equal to prefix + suffix). Near a
    // tie that can pick a different token than a cold run of the same prompt.
    //
    // At least one prompt token is always decoded: sampling needs the logits of
    // the last prompt token. Any state that does not check out — the record and the
    // memory disagree, an SWA cache already dropped early positions, seq_rm refuses
    // a partial removal, positions would not continue at p — falls back to a full
    // clear, i.e. exactly the old behaviour.
    llama_memory_t mem = llama_get_memory(engine->ctx);
    const int nPrompt = (int) tokens.size();
    int reuse = 0;
    if (llama_memory_seq_pos_max(mem, 0) + 1 == (llama_pos) engine->cached.size() &&
        llama_memory_seq_pos_min(mem, 0) <= 0) {
        const int lim = std::min((int) engine->cached.size(), nPrompt - 1);
        while (reuse < lim && engine->cached[reuse] == tokens[reuse]) reuse++;
    }
    if (reuse > 0 && !(llama_memory_seq_rm(mem, 0, reuse, -1) &&
                       llama_memory_seq_pos_min(mem, 0) == 0 &&
                       llama_memory_seq_pos_max(mem, 0) == reuse - 1)) {
        reuse = 0;
    }
    if (reuse == 0) resetCache(engine);
    else engine->cached.resize(reuse);
    LOGI("reused=%d of %d prompt tokens", reuse, nPrompt);

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

    // From here on, every exit puts the pool to sleep and unpins the caller.
    PoolScope poolScope(engine->pool, engine->cpus);

    // Evaluate the prompt past the reused prefix. llama_batch_get_one carries no
    // positions; llama.cpp continues from the memory's last position (reuse - 1).
    llama_batch batch = llama_batch_get_one(tokens.data() + reuse, nPrompt - reuse);
    if (llama_decode(engine->ctx, batch) != 0) { LOGE("prompt decode failed"); resetCache(engine); return -4; }
    engine->cached = tokens;

    // Sampler: low-temp deterministic-ish for agent work. top_k(40) first: on its
    // own, top_p softmaxes the whole vocabulary (~151k for Qwen) every token and
    // falls back to a full sort whenever its top 256 hold < 90% of the (untempered)
    // mass. Host bench on a 151,936-entry vocab: ~2-10 ms/token -> ~0.2-0.3 ms.
    // top_k and top_p see untempered logits, so the candidate set can shrink when the
    // top 40 hold < 90%; the draw itself is at temperature 0.2, which raises
    // probability ratios to the 5th power, so tokens past rank 40 had next to no
    // chance of being drawn anyway.
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));

    int generated = 0;
    int nPast = nPrompt;
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
        if (llama_decode(engine->ctx, next) != 0) { LOGE("decode failed mid-gen"); resetCache(engine); break; }
        engine->cached.push_back(tok);
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
                if (llama_decode(engine->ctx, ib) != 0) { LOGE("inject decode failed"); resetCache(engine); break; }
                engine->cached.insert(engine->cached.end(), inj.begin(), inj.end());
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
    // After the context (the pool was only attached to it; llama does not own or free it).
    if (engine->pool) ggml_threadpool_free(engine->pool);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}

} // extern "C"
