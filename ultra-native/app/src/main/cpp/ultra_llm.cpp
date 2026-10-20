#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
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

// Speculative decoding's draft model: a small model with the thinker's vocabulary
// (nativeAttachDraft checks it). Lives beside the thinker's context, shares its thread pool,
// and never runs at the same time as it.
struct Draft {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t ctxSize = 0;
    // Exactly the tokens in the draft's KV cache (sequence 0), in position order, like
    // Engine::cached. Synced to the thinker's committed tokens before every draft.
    std::vector<llama_token> cached;
};

void freeDraft(Draft *d) {
    if (!d) return;
    if (d->ctx) llama_free(d->ctx);
    if (d->model) llama_model_free(d->model);
    delete d;
}

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
    Draft *draft = nullptr;           // null = no speculative decoding
    // Recurrent or hybrid memory (Qwen3.5: Gated DeltaNet layers beside full attention). Such a
    // state holds only the latest position, so llama.cpp 8345f33 cannot drop a tail of it
    // (llama_memory_recurrent::seq_rm returns false for a partial range unless the context has
    // rollback snapshots, n_rs_seq > 0, which this shim does not request). Prefix reuse is then
    // limited to a pure extension (nothing to remove), and speculative decoding is refused, as
    // its verify step must remove rejected draft tokens.
    bool recurrent = false;
    // The last generate's speculation: tokens the draft proposed, and how many of them the
    // thinker accepted. specDrafted = -1: that generate ran without the draft.
    int specDrafted = -1;
    int specAccepted = 0;
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
        // Overlong forms, UTF-16 surrogates and values past U+10FFFF are not characters (and a
        // surrogate would reach NewString as invalid UTF-16).
        static const uint32_t kMin[5] = {0, 0, 0x80, 0x800, 0x10000};
        if (!bad && (cp < kMin[len] || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF)) bad = true;
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

// What Kotlin returns to stop generating (LlmNative.STOP, U+FFFF) in UTF-8.
const std::string kStop = "\xEF\xBF\xBF";

// Delivers one piece to Kotlin. Kotlin may return text to force-feed into the
// context without sampling (ThinkBudget's "</think>" at the budget); empty = none;
// kStop = stop here.
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

// ---------------------------------------------------------------------------------------------
// Speculative decoding. Ported from llama.cpp 8345f33 rather than linked: common/ (libcommon)
// is not part of this app's static llama build (build-llama-android.sh builds only libllama +
// ggml), and its speculative.cpp drags in common_sampler / common_batch / chat / json. What is
// ported is small and follows llama.cpp line for line:
//  - vocabCompatible  = common_speculative_are_compatible (common/speculative.cpp)
//  - draftTokens      = common_speculative_impl_draft_simple::draft (greedy argmax draft,
//                       stops below a confidence floor or at the length cap)
//  - the verify loop  = examples/speculative-simple + common_sampler_sample_and_accept_n
//                       (common/sampling.cpp): the thinker samples every position with its own
//                       sampler; a draft token is kept only when that sample equals it.
// ---------------------------------------------------------------------------------------------

// common/speculative.cpp SPEC_VOCAB_MAX_SIZE_DIFFERENCE / SPEC_VOCAB_CHECK_START_TOKEN_ID.
constexpr int kVocabMaxSizeDiff = 128;
constexpr int kVocabCheckStart = 5;
// Draft length cap the shim accepts (Kotlin passes the length it wants, at most this).
constexpr int kMaxDraft = 16;
// A draft stops at the first token the draft model is less sure of than this (its top
// softmax probability). 0.75 was llama.cpp's own draft p_min default before 8345f33 (it is
// 0 there now, with other implementations doing the filtering).
constexpr float kDraftPMin = 0.75f;
// A draft the thinker keeps rejecting costs time (its decodes plus a wider check) and saves
// none. Once kGiveUpAfter tokens were drafted in one generation and under a quarter were
// kept, the rest of that generation runs without drafting (the next one tries again). A
// guard, not a tuned figure: in a host check (tiny random models) an always-wrong draft
// made generation several times slower.
constexpr int kGiveUpAfter = 32;

// Same checks, same order, as common_speculative_are_compatible: vocab type, BOS/EOS
// handling, size within 128, and every token text from id 5 on identical.
bool vocabCompatible(const llama_vocab *tgt, const llama_vocab *dft) {
    if (llama_vocab_type(tgt) != llama_vocab_type(dft)) {
        LOGE("draft: vocab type %d != %d", (int) llama_vocab_type(dft), (int) llama_vocab_type(tgt));
        return false;
    }
    if (llama_vocab_get_add_bos(tgt) != llama_vocab_get_add_bos(dft) ||
        (llama_vocab_get_add_bos(tgt) && llama_vocab_bos(tgt) != llama_vocab_bos(dft))) {
        LOGE("draft: BOS handling differs");
        return false;
    }
    if (llama_vocab_get_add_eos(tgt) != llama_vocab_get_add_eos(dft) ||
        (llama_vocab_get_add_eos(tgt) && llama_vocab_eos(tgt) != llama_vocab_eos(dft))) {
        LOGE("draft: EOS handling differs");
        return false;
    }
    const int nT = llama_vocab_n_tokens(tgt), nD = llama_vocab_n_tokens(dft);
    if (std::abs(nT - nD) > kVocabMaxSizeDiff) {
        LOGE("draft: vocab size %d vs %d", nD, nT);
        return false;
    }
    for (int i = kVocabCheckStart; i < std::min(nT, nD); ++i) {
        if (std::strcmp(llama_vocab_get_text(tgt, i), llama_vocab_get_text(dft, i)) != 0) {
            LOGE("draft: token %d text differs", i);
            return false;
        }
    }
    return true;
}

void resetDraft(Draft *d) {
    llama_memory_clear(llama_get_memory(d->ctx), true);
    d->cached.clear();
}

// Makes the draft's KV hold exactly `committed` followed by `next`, with the logits of `next`
// last. Keeps the longest common prefix it already holds (as generate() does for the
// thinker); anything that doesn't check out is a full clear. False on a decode failure
// (the draft is then cleared and the caller decodes without it this step).
bool draftSync(Draft *d, const std::vector<llama_token> &committed, llama_token next) {
    llama_memory_t mem = llama_get_memory(d->ctx);
    size_t p = 0;
    if (llama_memory_seq_pos_max(mem, 0) + 1 == (llama_pos) d->cached.size() &&
        llama_memory_seq_pos_min(mem, 0) <= 0) {
        const size_t lim = std::min(d->cached.size(), committed.size());
        while (p < lim && d->cached[p] == committed[p]) p++;
    }
    if (p > 0 && p < d->cached.size() && !llama_memory_seq_rm(mem, 0, (llama_pos) p, -1)) p = 0;
    if (p == 0) resetDraft(d);
    else d->cached.resize(p);
    std::vector<llama_token> add(committed.begin() + (long) p, committed.end());
    add.push_back(next);
    if ((int) (d->cached.size() + add.size()) >= d->ctxSize) return false;
    if (llama_decode(d->ctx, llama_batch_get_one(add.data(), (int32_t) add.size())) != 0) {
        LOGE("draft: sync decode failed");
        resetDraft(d);
        return false;
    }
    d->cached.insert(d->cached.end(), add.begin(), add.end());
    return true;
}

// Greedy draft of up to nMax tokens continuing committed + next (draft-simple's loop).
// `out` may come back shorter, or empty.
void draftTokens(Draft *d, const llama_vocab *tgtVocab, const std::vector<llama_token> &committed,
                 llama_token next, int nMax, std::vector<llama_token> &out) {
    out.clear();
    if (nMax <= 0 || !draftSync(d, committed, next)) return;
    const int nVocab = std::min(llama_vocab_n_tokens(d->vocab), llama_vocab_n_tokens(tgtVocab));
    while ((int) out.size() < nMax) {
        const float *logits = llama_get_logits_ith(d->ctx, -1);
        if (!logits) break;
        int best = 0;
        for (int i = 1; i < nVocab; i++) if (logits[i] > logits[best]) best = i;
        // Softmax probability of the top token = 1 / sum(exp(l - lmax)); stop summing as soon as
        // the sum proves it is below the floor (the usual case for an unsure step).
        const float lmax = logits[best], limit = 1.0f / kDraftPMin;
        float sum = 0.0f;
        for (int i = 0; i < nVocab && sum <= limit; i++) sum += std::exp(logits[i] - lmax);
        if (sum > limit) break;
        out.push_back(best);
        if ((int) out.size() >= nMax || llama_vocab_is_eog(tgtVocab, best)) break;
        if ((int) d->cached.size() + 1 >= d->ctxSize) break;
        llama_token t = best;
        if (llama_decode(d->ctx, llama_batch_get_one(&t, 1)) != 0) {
            LOGE("draft: decode failed");
            resetDraft(d);
            break;                                       // keep what was drafted; the next sync rebuilds
        }
        d->cached.push_back(t);
    }
}

// Decodes `inject` (Kotlin's forced text) into the thinker, as the plain loop does.
// False on a decode failure (the cache was reset).
bool decodeInject(Engine *engine, const std::string &inject, int &nPast) {
    std::vector<llama_token> inj(inject.size() + 8);
    int n = llama_tokenize(engine->vocab, inject.c_str(), inject.size(), inj.data(), inj.size(), false, true);
    if (n > 0 && nPast + n < engine->ctxSize - 1) {
        inj.resize(n);
        if (llama_decode(engine->ctx, llama_batch_get_one(inj.data(), n)) != 0) {
            LOGE("inject decode failed"); resetCache(engine); return false;
        }
        engine->cached.insert(engine->cached.end(), inj.begin(), inj.end());
        nPast += n;
    } else {
        LOGE("inject skipped (n=%d, nPast=%d)", n, nPast);
    }
    return true;
}

/*
 * The decode loop with a draft model. Emits exactly what the plain loop emits:
 *  - Every emitted token is the thinker's own sample: `chain` applied to the thinker's logits
 *    for exactly the committed prefix, one draw from the same seeded RNG per emitted token, in
 *    the same order (llama_sampler_sample accepts the token itself; the plain loop's extra
 *    llama_sampler_accept is a no-op for this chain at 8345f33: top_k / top_p / temp have no
 *    accept, dist's only acts for backend sampling). A draft token only decides which positions get their logits from one
 *    batched decode instead of one decode each; when the thinker's sample differs, that
 *    sample is used and the rest of the draft is dropped. So the output distribution is the
 *    thinker's, unchanged, at any temperature (no rejection sampling, no draft probabilities
 *    involved). Token for token it equals a run without the draft except where the batched
 *    logits differ in the last float bits from one-token decodes (the CPU kernels are not
 *    batch-invariant; see the prefix-reuse note in nativeGenerate), which can flip a near-tie.
 *  - Every token gets its callback before the next is sampled, so streaming, ThinkBudget's
 *    count and inject, and STOP behave as in the plain loop: a stopped token is removed from
 *    the KV cache, and engine->cached always describes the cache.
 */
int generateSpeculative(Engine *engine, llama_sampler *chain, int maxTokens, int draftMax, int nPrompt) {
    Draft *d = engine->draft;
    llama_memory_t mem = llama_get_memory(engine->ctx);
    llama_batch batch = llama_batch_init(kMaxDraft + 1, 0, 1);
    std::vector<llama_token> draft;
    int generated = 0, nPast = nPrompt, drafted = 0, accepted = 0, rounds = 0;
    bool haveNext = false;
    llama_token next = 0;
    // Batch index of the logits the next sample reads: -1 = the last decode's last output.
    int sampleIdx = -1;
    bool drafting = true;
    // The draft reads the prompt now, with the thinker's prefill, rather than after the first
    // token: its time then counts as waiting for the first token, not as decoding (tok/s and
    // DecodeSpeed time from the first token). A prefix it already holds is reused.
    if (nPrompt >= 2) {
        const std::vector<llama_token> head(engine->cached.begin(), engine->cached.end() - 1);
        draftSync(d, head, engine->cached.back());
    }
    const int64_t t0 = ggml_time_us();

    while (generated < maxTokens) {
        if (nPast >= engine->ctxSize - 1) { LOGE("ctx full at %d", nPast); break; }
        const llama_token tok = haveNext ? next : llama_sampler_sample(chain, engine->ctx, sampleIdx);
        haveNext = false;
        sampleIdx = -1;
        if (llama_vocab_is_eog(engine->vocab, tok)) break;

        char buf[256];
        int len = llama_token_to_piece(engine->vocab, tok, buf, sizeof(buf), 0, true);
        const std::string inject = deliverToken(len > 0 ? std::string(buf, len) : std::string());
        if (inject == kStop) break;

        // Draft room: positions up to ctxSize - 2 (as the plain loop), the output budget, and
        // the draft's own context. No drafting before forced text.
        int k = std::min({draftMax, maxTokens - generated - 1, engine->ctxSize - 2 - nPast,
                          d->ctxSize - 2 - nPast});
        draft.clear();
        if (drafting && inject.empty() && k > 0) draftTokens(d, engine->vocab, engine->cached, tok, k, draft);
        k = (int) draft.size();

        batch.n_tokens = k + 1;
        for (int i = 0; i <= k; i++) {
            batch.token[i] = i == 0 ? tok : draft[i - 1];
            batch.pos[i] = nPast + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 1;
        }
        if (llama_decode(engine->ctx, batch) != 0) { LOGE("decode failed mid-gen"); resetCache(engine); break; }
        engine->cached.push_back(tok);
        nPast++;
        generated++;

        if (!inject.empty()) {
            if (!decodeInject(engine, inject, nPast)) break;
            continue;                                   // next sample: logits of the forced text
        }
        if (k == 0) continue;                           // next sample: logits of tok (the only output)

        rounds++;
        drafted += k;
        bool stop = false, failed = false;
        // Logits at batch index i predict the token after tok, draft[0..i-1].
        for (int i = 0; i <= k; i++) {
            const llama_token t = llama_sampler_sample(chain, engine->ctx, i);
            if (i < k && t == draft[i] && generated < maxTokens && !llama_vocab_is_eog(engine->vocab, t)) {
                len = llama_token_to_piece(engine->vocab, t, buf, sizeof(buf), 0, true);
                const std::string inj = deliverToken(len > 0 ? std::string(buf, len) : std::string());
                if (inj == kStop) { stop = true; break; }   // not committed: removed below
                engine->cached.push_back(t);                 // already in the KV at nPast
                nPast++;
                generated++;
                accepted++;
                if (!inj.empty()) {
                    // Drop the unverified rest first, so the forced text follows t directly.
                    if (!llama_memory_seq_rm(mem, 0, (llama_pos) engine->cached.size(), -1)) {
                        resetCache(engine); failed = true; break;
                    }
                    const int before = nPast;
                    if (!decodeInject(engine, inj, nPast)) failed = true;
                    // Skipped for lack of room: the next sample reads t's own logits, which
                    // sit at batch index i + 1 (t is draft[i]), as the plain loop would.
                    else if (nPast == before) sampleIdx = i + 1;
                    break;
                }
                continue;
            }
            // The thinker's own token at this position: the next one to emit (an EOG ends the
            // generation there, exactly as in the plain loop).
            next = t;
            haveNext = true;
            break;
        }
        if (failed) break;
        if (drafted >= kGiveUpAfter && accepted * 4 < drafted) {
            drafting = false;
            LOGI("spec: %d of %d drafted tokens kept; drafting off for the rest of this answer", accepted, drafted);
        }
        // Remove the drafted tokens the thinker did not keep. Their KV sits past every
        // committed position; removing a tail leaves no trace (see nativeGenerate).
        if (!engine->cached.empty() &&
            !llama_memory_seq_rm(mem, 0, (llama_pos) engine->cached.size(), -1)) {
            LOGE("seq_rm failed after verify");
            resetCache(engine);
            break;
        }
        if (stop) break;
    }

    llama_batch_free(batch);
    const double secs = (double) (ggml_time_us() - t0) / 1e6;
    engine->specDrafted = drafted;
    engine->specAccepted = accepted;
    LOGI("spec: generated=%d drafted=%d accepted=%d (%.0f%%) rounds=%d draftMax=%d decode %.1f tok/s",
         generated, drafted, accepted, drafted > 0 ? 100.0 * accepted / drafted : 0.0, rounds, draftMax,
         secs > 0 ? generated / secs : 0.0);
    return generated;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_agent_ultra_local_LlmNative_nativeLoad(
        JNIEnv *env, jobject, jstring jpath, jint threads, jint ctxSize, jintArray jcpus, jboolean resident) {
    if (!backendReady) { llama_backend_init(); backendReady = true; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU path — measured winner on the A15 bench
    // resident: read the weights into this process's own memory instead of mapping the file.
    // A mapped file's pages can be dropped by the kernel at any time and are then read back one
    // fault at a time mid-generation (Pixel 10 Pro, 2026-10-07: 42,299 major faults, 1.6 tok/s,
    // 8.6 GB free). Owned memory can't be silently dropped. Android apps can't mlock 10 GB.
    // (llama.cpp 8345f33 replaced the use_mmap bool with load_mode; lazy reads need a mapping.)
    if (resident) {
        mparams.load_mode = LLAMA_LOAD_MODE_NONE;
        mparams.lazy_mode = LLAMA_LAZY_MODE_OFF;
    }
    LOGI("load resident=%d load_mode=%d", (int) resident, (int) mparams.load_mode);

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctxSize;
    // KV cache at q8_0 instead of the f16 default: about half the bytes (Qwen3-30B-A3B
    // at 4096 ctx: ~384 MB f16 -> ~204 MB q8_0; ctx comes from contextFor), and on a 16 GB phone holding ~10 GB
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
    // Physical batch: llama_decode splits the prompt into ubatches of this size, and the
    // compute buffer is reserved for min(n_ctx, n_ubatch) tokens (llama-context.cpp sched_reserve
    // at 8345f33), so with n_ubatch pinned a bigger ctx (LocalModelEngine.contextFor: up to
    // 16384) grows it only by the f16 KQ mask (n_ctx x n_ubatch x 2 bytes), not by n_batch.
    // 512 is llama.cpp's own default; set here so the memory arithmetic can't drift with it.
    cparams.n_ubatch = 512;
    // Full-size cache for sliding-window layers (Gemma): a window-sized cache drops early
    // positions, and then generate() can never reuse the shared prompt prefix (it falls
    // back to a full prefill). No effect on models without SWA (the Qwen3 MoE).
    cparams.swa_full = true;
    // Speculative decoding verifies up to kMaxDraft + 1 tokens of one sequence in one batch,
    // each with its logits. The per-sequence cap (default 1 at 8345f33) is only enforced for
    // backend samplers, which this shim doesn't use; set so the requirement is stated.
    cparams.n_outputs_max_per_seq = kMaxDraft + 1;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    // A context above the old 4096 (LocalModelEngine.CTX_SIZE) that can't be set up -- most
    // likely its larger KV allocation -- is retried at 4096 with the same q8_0 KV before the
    // f16 fallback below, which would otherwise double the KV at the larger size. The weights
    // stay loaded. The ctx actually in use is reported by nativeContextSize.
    constexpr int kBaseCtx = 4096;
    if (!ctx && ctxSize > kBaseCtx) {
        LOGE("ctx init failed at ctx=%d (cause in llama.cpp log); retrying at %d", ctxSize, kBaseCtx);
        ctxSize = kBaseCtx;
        cparams.n_ctx = ctxSize;
        cparams.n_batch = ctxSize;
        ctx = llama_init_from_model(model, cparams);
    }
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
    engine->recurrent = llama_model_is_recurrent(model) || llama_model_is_hybrid(model);
    LOGI("loaded: ctx=%d threads=%d pinnedCpus=%zu kv=%s recurrent=%d", ctxSize, threads, cpus.size(),
         ggml_type_name(cparams.type_k), (int) engine->recurrent);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_agent_ultra_local_LlmNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint maxTokens,
        jfloat temperature, jint threads, jint draftMax, jobject callback) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->ctx) return -1;
    engine->specDrafted = -1;
    engine->specAccepted = 0;

    // ThermalGuard: fewer threads when hot. Applies to this and later calls. A pinned pool
    // has exactly its load-time workers; ggml would clamp a larger count with a warning on
    // every decode, so clamp here once.
    if (threads > 0) {
        if (engine->pool) threads = std::min<int>(threads, engine->poolThreads);
        llama_set_n_threads(engine->ctx, threads, threads);
        if (engine->draft) llama_set_n_threads(engine->draft->ctx, threads, threads);
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
    if (engine->recurrent) {
        // Recurrent / hybrid: the state after the last cached token is all there is (its
        // seq_pos_min is that last position, not 0). Reuse only when this prompt starts with
        // everything cached, so nothing has to be removed; any other overlap re-reads the whole
        // prompt from a cleared state. Never decode onto a state that holds tokens this prompt
        // does not have.
        const int n = (int) engine->cached.size();
        if (n > 0 && n <= nPrompt - 1 && llama_memory_seq_pos_max(mem, 0) + 1 == (llama_pos) n &&
            std::equal(engine->cached.begin(), engine->cached.end(), tokens.begin())) {
            reuse = n;
        }
    } else {
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

    // With a draft model attached and asked for: the speculative loop (same output, see
    // generateSpeculative). Otherwise the plain loop below, unchanged.
    if (engine->draft && draftMax > 0 && !engine->recurrent) {
        const int n = generateSpeculative(engine, chain, maxTokens, std::min<int>(draftMax, kMaxDraft), nPrompt);
        llama_sampler_free(chain);
        return n;
    }

    int generated = 0;
    int nPast = nPrompt;
    for (; generated < maxTokens; generated++) {
        if (nPast >= engine->ctxSize - 1) { LOGE("ctx full at %d", nPast); break; }
        llama_token tok = llama_sampler_sample(chain, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, tok)) break;

        char buf[256];
        int len = llama_token_to_piece(engine->vocab, tok, buf, sizeof(buf), 0, true);
        std::string inject;
        // Every token gets its callback, even one with an empty piece: a stop request (and
        // ThinkBudget's count) must not miss it.
        inject = deliverToken(len > 0 ? std::string(buf, len) : std::string());
        // Before decoding tok: engine->cached still matches the KV cache exactly.
        if (inject == kStop) break;

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

// The ctx the loaded context was created with (nativeLoad may have fallen back to 4096).
JNIEXPORT jint JNICALL
Java_com_agent_ultra_local_LlmNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    return engine ? engine->ctxSize : 0;
}

// Loads the speculative-decoding draft model beside a loaded engine. Returns the draft's ctx
// (> 0) on success; 0 = the model didn't load, -1 = its vocabulary isn't the engine's
// (llama.cpp's own check, vocabCompatible), -2 = its context couldn't be created, -3 = the
// engine (or the draft) has recurrent/hybrid memory, whose rejected draft tokens can't be
// removed (Engine::recurrent). On any failure nothing stays loaded and generate runs as before.
// Replaces a draft already attached.
JNIEXPORT jint JNICALL
Java_com_agent_ultra_local_LlmNative_nativeAttachDraft(JNIEnv *env, jobject, jlong handle, jstring jpath, jint ctxSize) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->ctx || ctxSize <= 0) return 0;
    freeDraft(engine->draft);
    engine->draft = nullptr;
    if (engine->recurrent) {
        LOGE("draft: refused, the model has recurrent/hybrid memory (no partial rollback)");
        return -3;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    // Always the app's own memory: a few hundred MB, read once; a mapped draft could be paged
    // out between tokens like the thinker's mapped weights were.
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    mparams.lazy_mode = LLAMA_LAZY_MODE_OFF;
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("draft: model load failed"); return 0; }
    if (!vocabCompatible(engine->vocab, llama_model_get_vocab(model))) {
        LOGE("draft: vocabulary differs from the thinker's; speculative decoding off");
        llama_model_free(model);
        return -1;
    }
    // draftSync trims the draft's own cache with partial seq_rm; a recurrent draft would only
    // ever fall back to full re-reads. Refused rather than run slowly.
    if (llama_model_is_recurrent(model) || llama_model_is_hybrid(model)) {
        LOGE("draft: refused, the draft model has recurrent/hybrid memory");
        llama_model_free(model);
        return -3;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctxSize;
    cparams.n_batch = ctxSize;        // the draft prefills the whole prompt in one llama_decode
    cparams.n_ubatch = 512;
    // The draft only ever reads its last token's logits: reserving one output row instead of
    // n_ubatch keeps its compute buffer ~300 MB smaller (512 x 151,936 x 4 B for Qwen).
    cparams.n_outputs_max = 1;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    cparams.n_threads = engine->poolThreads > 0 ? engine->poolThreads : cparams.n_threads;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("draft: ctx init failed at ctx=%d (cause in llama.cpp log)", ctxSize);
        llama_model_free(model);
        return -2;
    }
    // Same pinned workers as the thinker: the two never decode at the same time.
    if (engine->pool) llama_attach_threadpool(ctx, engine->pool, nullptr);

    auto *d = new Draft();
    d->model = model;
    d->ctx = ctx;
    d->vocab = llama_model_get_vocab(model);
    d->ctxSize = ctxSize;
    engine->draft = d;
    LOGI("draft attached: ctx=%d vocab=%d", ctxSize, llama_vocab_n_tokens(d->vocab));
    return ctxSize;
}

JNIEXPORT void JNICALL
Java_com_agent_ultra_local_LlmNative_nativeDetachDraft(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine) return;
    freeDraft(engine->draft);
    engine->draft = nullptr;
}

// The last generate's speculation: {drafted, accepted}; drafted = -1 when it ran without one.
JNIEXPORT jintArray JNICALL
Java_com_agent_ultra_local_LlmNative_nativeSpecStats(JNIEnv *env, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    jint v[2] = {engine ? engine->specDrafted : -1, engine ? engine->specAccepted : 0};
    jintArray out = env->NewIntArray(2);
    if (out) env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT void JNICALL
Java_com_agent_ultra_local_LlmNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine) return;
    // Before the pool: the draft's context uses it too.
    freeDraft(engine->draft);
    engine->draft = nullptr;
    if (engine->ctx) llama_free(engine->ctx);
    // After the context (the pool was only attached to it; llama does not own or free it).
    if (engine->pool) ggml_threadpool_free(engine->pool);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}

} // extern "C"
