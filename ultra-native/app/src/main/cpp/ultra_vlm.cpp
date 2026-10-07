// On-device vision: a vision-language model (text GGUF + its mmproj GGUF) through llama.cpp's
// libmtmd. Compiled into the same libultra_llm*.so as ultra_llm.cpp, so both share the one
// llama/ggml; Kotlin binds it as com.agent.ultra.local.VisionNative (an `object`, hence the
// jobject `thiz` in every signature). One describe at a time per handle: Kotlin serialises.
//
// Each describe is a fresh, self-contained prompt: the KV memory is cleared, the image and the
// chat-templated prompt are evaluated from position 0, and the answer is sampled. No prefix
// reuse (an image's KV depends on the image, and screens change between calls).
#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "affinity_scope.h"

#define TAG "UltraVlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Image tokens are capped so a phone's encode (and the prefill after it) stays bounded: a
// dynamic-resolution model (Qwen-VL family) would otherwise turn a 1080x2400 screenshot into
// thousands of tokens. Only applies to models with dynamic resolution (mtmd.h); fixed-size
// encoders (Gemma 3: 256) are unaffected. Also keeps one image within one 512-token ubatch,
// which a non-causal image (Gemma 3) needs (mtmd-helper.cpp scope_non_causal).
constexpr int kImageMaxTokens = 512;
constexpr int kBatch = 512;

struct VlmEngine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *mctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int32_t ctxSize = 0;
    // Pinned engines only (empty = unpinned). The describe call's thread is pinned for its
    // duration; ggml's per-decode workers (no persistent pool here) and the clip encoder's
    // workers are created by that thread and inherit its affinity.
    std::vector<int> cpus;
};

void freeEngine(VlmEngine *e) {
    if (!e) return;
    if (e->mctx) mtmd_free(e->mctx);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

bool backendReady = false;

// mtmd/clip log to stderr by default, which Android drops. Warnings and errors (why an mmproj
// was refused, an encode failure) go to logcat; the per-load INFO dump does not.
void mtmdLog(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "mtmd: %s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "mtmd: %s", text);
}

// Text crosses JNI as UTF-16, never through the *StringUTF* calls (Modified UTF-8 has no
// 4-byte sequences); same conversion as ultra_llm.cpp, which keeps its helpers file-local.
void appendUtf16(std::u16string &out, uint32_t cp) {
    if (cp >= 0x10000) {
        cp -= 0x10000;
        out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
        out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
        out.push_back(static_cast<char16_t>(cp));
    }
}

// The whole answer as UTF-16. Invalid bytes become U+FFFD; a character cut off at the end
// (maxTokens reached mid-character: a byte-level BPE splits characters across tokens) is
// dropped, as ultra_llm.cpp leaves such a tail pending and never delivers it.
std::u16string utf8ToUtf16(const std::string &s) {
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
            if (cont) break;                  // incomplete final character: dropped
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

// The user turn through the model's own chat template (llama.cpp's built-in list; a model with
// no template gets chatml, llama.cpp's default), ending with the assistant's turn opened. A
// template llama.cpp does not know: the bare prompt, logged (the answer may then be poorer).
std::string applyTemplate(const llama_model *model, const std::string &prompt) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message msg = {"user", prompt.c_str()};
    std::vector<char> buf(prompt.size() * 2 + 512);
    int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        LOGW("chat template not supported by llama_chat_apply_template; using the bare prompt");
        return prompt;
    }
    return std::string(buf.data(), n);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_agent_ultra_local_VisionNative_nativeVlmLoad(
        JNIEnv *env, jobject, jstring jmodel, jstring jmmproj, jint threads, jint nCtx, jintArray jcpus) {
    if (!backendReady) { llama_backend_init(); backendReady = true; }
    mtmd_helper_log_set(mtmdLog, nullptr);
    if (nCtx <= 0) { LOGE("bad nCtx=%d", nCtx); return 0; }

    const std::string modelPath = jstringToUtf8(env, jmodel);
    const std::string mmprojPath = jstringToUtf8(env, jmmproj);
    if (modelPath.empty() || mmprojPath.empty()) { LOGE("missing model or mmproj path"); return 0; }

    std::vector<int> cpus;
    if (jcpus) {
        const jsize n = env->GetArrayLength(jcpus);
        std::vector<jint> raw(n);
        env->GetIntArrayRegion(jcpus, 0, n, raw.data());
        for (jint c : raw) if (c >= 0 && c < CPU_SETSIZE) cpus.push_back(c);
    }
    // Loading (and mtmd's init) spawns no long-lived threads; pinned only so the load's own
    // work lands on the same cores as the describes.
    AffinityScope pin(cpus);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                       // CPU only
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_batch = kBatch;                       // mtmd_helper_eval_chunks splits to this
    cparams.n_ubatch = kBatch;
    // f16 KV, flash attention AUTO: llama.cpp's own default, the path mtmd-cli runs (a
    // non-causal image pass, Gemma 3, included). A small VLM's f16 KV is small at phone ctx.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.n_threads = threads > 0 ? threads : cparams.n_threads;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) { LOGE("ctx init failed at ctx=%d (cause in llama.cpp log)", nCtx); llama_model_free(model); return 0; }

    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = false;
    mp.n_threads = cparams.n_threads;
    mp.print_timings = false;
    mp.warmup = false;
    mp.image_max_tokens = kImageMaxTokens;
    mtmd_context *mctx = mtmd_init_from_file(mmprojPath.c_str(), model, mp);
    if (!mctx) {
        LOGE("mmproj load failed (wrong file, or not the projector for this model)");
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }
    if (!mtmd_support_vision(mctx)) {
        LOGE("mmproj has no vision encoder");
        mtmd_free(mctx);
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    auto *e = new VlmEngine();
    e->model = model;
    e->ctx = ctx;
    e->mctx = mctx;
    e->vocab = llama_model_get_vocab(model);
    e->ctxSize = nCtx;
    e->cpus = cpus;
    LOGI("loaded: ctx=%d threads=%d pinnedCpus=%zu mrope=%d", nCtx, cparams.n_threads, cpus.size(),
         (int) mtmd_decode_use_mrope(mctx));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL
Java_com_agent_ultra_local_VisionNative_nativeVlmDescribe(
        JNIEnv *env, jobject, jlong handle, jbyteArray jrgb, jint width, jint height,
        jstring jprompt, jint maxTokens, jint threads) {
    auto *e = reinterpret_cast<VlmEngine *>(handle);
    if (!e || !e->ctx || !e->mctx || !jrgb || width <= 0 || height <= 0 || maxTokens <= 0) return nullptr;
    const int64_t need = (int64_t) width * height * 3;
    if (env->GetArrayLength(jrgb) < need) {
        LOGE("rgb is %d bytes, %dx%d RGB888 needs %lld", (int) env->GetArrayLength(jrgb), width, height,
             (long long) need);
        return nullptr;
    }

    AffinityScope pin(e->cpus);
    if (threads > 0) llama_set_n_threads(e->ctx, threads, threads);

    // The bitmap copies the pixels (mtmd_bitmap's constructor memcpy's them), so the critical
    // section holds no JNI calls and ends at once.
    auto *px = static_cast<unsigned char *>(env->GetPrimitiveArrayCritical(jrgb, nullptr));
    if (!px) { LOGE("rgb pixels unavailable"); return nullptr; }
    mtmd_bitmap *bmp = mtmd_bitmap_init((uint32_t) width, (uint32_t) height, px);
    env->ReleasePrimitiveArrayCritical(jrgb, px, JNI_ABORT);
    if (!bmp) { LOGE("bitmap init failed"); return nullptr; }

    std::string prompt = jstringToUtf8(env, jprompt);
    const char *marker = mtmd_default_marker();
    if (prompt.find(marker) == std::string::npos) prompt = std::string(marker) + "\n" + prompt;
    const std::string formatted = applyTemplate(e->model, prompt);

    mtmd_input_text text;
    text.text = formatted.c_str();
    text.text_len = formatted.size();
    text.add_special = true;                        // fresh sequence from position 0: BOS if the model wants one
    text.parse_special = true;                      // the template's control tokens
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[] = {bmp};
    const int32_t tr = mtmd_tokenize(e->mctx, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(bmp);
    if (tr != 0) {
        LOGE("mtmd_tokenize failed: %d (1 = marker count != 1 image, 2 = image preprocessing)", tr);
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }

    // KV cells used = tokens (with M-RoPE, positions advance slower than tokens).
    const int nPromptTokens = (int) mtmd_helper_get_n_tokens(chunks);
    if (nPromptTokens >= e->ctxSize - 1) {
        LOGE("prompt + image is %d tokens, ctx %d", nPromptTokens, e->ctxSize);
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(e->ctx), true);
    const int64_t t0 = ggml_time_us();
    llama_pos nPast = 0;
    const int32_t er = mtmd_helper_eval_chunks(e->mctx, e->ctx, chunks, 0, 0, kBatch, true, &nPast);
    mtmd_input_chunks_free(chunks);
    if (er != 0) {
        LOGE("eval (image encode + prefill) failed: %d", er);
        llama_memory_clear(llama_get_memory(e->ctx), true);
        return nullptr;
    }
    const int64_t t1 = ggml_time_us();

    // Same sampler as ultra_llm.cpp's generate (top_k first, then top_p, temperature, draw).
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));

    // Explicit positions, as mtmd-cli does: after an M-RoPE image nPast is the image's position
    // count, not its token count, so llama_batch_get_one's implicit positions would not do.
    llama_batch batch = llama_batch_init(1, 0, 1);
    std::string out;
    std::vector<char> buf(256);
    int generated = 0, nKv = nPromptTokens;
    bool failed = false;
    while (generated < maxTokens) {
        const llama_token tok = llama_sampler_sample(chain, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        int len = llama_token_to_piece(e->vocab, tok, buf.data(), (int32_t) buf.size(), 0, true);
        if (len < 0) {
            buf.resize(-len);
            len = llama_token_to_piece(e->vocab, tok, buf.data(), (int32_t) buf.size(), 0, true);
        }
        if (len > 0) out.append(buf.data(), len);
        generated++;
        if (generated >= maxTokens) break;          // the last token needs no decode
        if (nKv + 1 >= e->ctxSize) { LOGE("ctx full at %d", nKv); break; }
        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = nPast;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(e->ctx, batch) != 0) { LOGE("decode failed mid-gen"); failed = true; break; }
        nPast++;
        nKv++;
    }
    llama_batch_free(batch);
    llama_sampler_free(chain);
    // Nothing is reused across describes; free the KV now rather than holding the image.
    llama_memory_clear(llama_get_memory(e->ctx), true);
    if (failed) return nullptr;

    const double prefill = (double) (t1 - t0) / 1e6, decode = (double) (ggml_time_us() - t1) / 1e6;
    LOGI("describe: %dx%d prompt=%d tokens, encode+prefill %.2f s, %d tokens in %.2f s (%.1f tok/s)",
         width, height, nPromptTokens, prefill, generated, decode, decode > 0 ? generated / decode : 0.0);

    const std::u16string u = utf8ToUtf16(out);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
}

JNIEXPORT void JNICALL
Java_com_agent_ultra_local_VisionNative_nativeVlmFree(JNIEnv *, jobject, jlong handle) {
    freeEngine(reinterpret_cast<VlmEngine *>(handle));
}

} // extern "C"
