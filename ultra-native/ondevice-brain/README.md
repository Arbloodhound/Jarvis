# MINT-TRINITY — build bundle

Specialized Qwen3-30B-A3B MoE + always-listening ambient layer for Agent Ultra
on the Pixel 10 Pro (GrapheneOS). Everything here is the "testable today" set.

## Files
- `Qwen3_30B_A3B_LoRA_Colab.ipynb` — LoRA-specialize the abliterated MoE, merge, GGUF, IQ2_M quant.
- `0001-ambient-listen.patch` — the always-listening layer (git patch for the Agent Ultra fork).
- `build-whisper-android.sh` — build whisper.cpp arm64 libs the patch needs.

---

## Model — verified live on HF (Apache-2.0)
Base: `Qwen/Qwen3-30B-A3B-Thinking-2507` (qwen3_moe, 30B total / 3B active — best-reasoning MoE that fits a phone).
Abliterated (uncensored, research): `huihui-ai/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated` (same base, Apache-2.0).

### What fits 16GB (documented budget, assistant-only)
OS/GrapheneOS floor ~3.75GB + KV(4k) ~0.6 + whisper/wake/app ~0.75 ≈ **~5GB overhead → ~11GB usable for weights.**
| Build | Size | Verdict |
|---|---|---|
| IQ3 (any) | 11.8–12.9GB | **over 16GB total → NO** (desktop-only entry kept for reference) |
| **Abliterated IQ2_M** | **10.2GB** | **YES — ~1.6GB free. Recommended on-device.** |
| Abliterated IQ2_S | 9.3GB | roomier fallback |
| Stock IQ2_M | 10.8GB | ~0.1GB free, knife-edge — comparison only |

All four are wired into `LocalModelEngine.kt`. Default stays Gemma (safe); the MoE is an opt-in pick.
Cap context at 4k. Phone runs as an assistant-only environment (search + screen), nothing else resident.

### Path 1 — test today, no training
Download `mradermacher/Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated-i1-GGUF -> ...i1-IQ2_M.gguf` (10.2GB),
push to the model dir (or let the in-app downloader fetch the wired URL), pick it in Settings.

### Path 2b — YOUR specialized + abliterated build (the standing goal)
Notebook does: LoRA **directly on huihui's abliterated model** on your 6 domains → merge → imatrix on your
own formatted chats → quantize to IQ2_M (~10.2GB). `ablit + LoRA` equals the old transplant
(`your_merged + (huihui - base)`) algebraically, with one 61GB download instead of three and no tensor surgery.
Keep `<think>` traces in most training answers (the Thinking template turns trace-less answers into an empty
think block, which trains the model to stop reasoning) and keep refusals out of the data.
Then change the URL in the "ABLITERATED (IQ2_M)" ModelChoice to your HF repo.

Note: abliterated weights will answer in your pharmacology/chemistry/opsec domains without refusal. Your call as
operator/researcher; keep web-search grounding on so answers cite sources rather than recall.

## 1. Get the code into YOUR GitHub
```bash
# Fork dafarusd/Ultra-Agent on github.com first (keeps AGPL-3.0; publish source).
git clone https://github.com/<you>/Ultra-Agent.git
cd Ultra-Agent
git checkout native
git checkout -b ambient-listen
git am ../0001-ambient-listen.patch        # or: git apply ../0001-ambient-listen.patch
git push -u origin ambient-listen
```
I cannot push for you — no GitHub access from this chat. These 5 commands do it.

## 2. Native libs (both required for the app to build)
```bash
export ANDROID_NDK=~/Android/Sdk/ndk/<ver>
# llama.cpp (already required by the base repo) -> ~/llama-android/{lib,include}
# whisper.cpp (new, for ambient listening):
./build-whisper-android.sh
```

## 3. Train the model (Colab, 80GB-class GPU)
Needs **≥64GB VRAM**: current Unsloth trains this MoE in 16-bit (fused experts don't shrink in 4-bit), so a
40GB A100 will OOM. The preflight cell checks VRAM and disk before any download. Upload `train.jsonl`, run top to
bottom; the data check and a measured ETA (after 13 steps) run before the long part.
Out: `qwen3moe-abliterated-iq2m.gguf` (~10.2GB) on Drive; optional IQ2_S fallback from the same files.

## 4. Point the app at the model (already wired)
`ultra-native/app/src/main/java/com/agent/ultra/local/LocalModelEngine.kt`, in `PRESETS`,
add a `ModelChoice` (fields: label, url, fileName, approxMb, note). Host the GGUF on your
HF repo or sideload it to the model dir and use a file:// url:
```kotlin
ModelChoice(
    "Qwen3-30B-A3B Thinking ABLITERATED (IQ2_M) — on-device MoE",
    "https://huggingface.co/arbloodhound/<repo>/resolve/main/qwen3moe-abliterated-iq2m.gguf",
    "qwen3moe-abliterated-iq2m.gguf", 10240,
    "Your specialized + abliterated MoE. Keep the qwen3moe filename prefix (MoE fit math + ThinkBudget).",
),
```
Also raise `CTX_SIZE` only if RAM allows; leave `THREADS=4`.

## 5. Ambient listening — ship the ASR models
Download to the app's `filesDir` (reuse the existing model-download flow or adb push):
- `ggml-base.en-q5_1.bin`  (whisper)
- `ggml-silero-v5.1.2.bin` (VAD)
Verify current URLs in `listen/AmbientConfig.kt` before release.
Then: Settings → Ambient listening ON, set a distinctive wake name.

## What the patch adds
`listen/` — AmbientConfig, Segmenter (energy gate), WhisperNative (+cpp/ultra_whisper.cpp),
AmbientMemory (FTS4 store), WakeFilter, AmbientListenService (mic FGS), BootReceiver.
Plus: `ambient_recall` tool, ambient-context injection on wake, TTS echo guard, settings UI,
manifest perms/service/receiver, CMake whisper target with isolated ggml symbols.

## Still missing (next)
1. openWakeWord acoustic gate (battery — whisper stays asleep until the name is spoken)
2. Web tools (search + fetch) in the agent
3. Thermal guard + battery-unrestricted setup
4. Even G2 connection (last)
5. Prosody / social-cue branch (experimental, later — reports measured signal, not verdicts)

## Honest limits
- Qwen3-30B-A3B at IQ2_M (~1.6GB free) on a 16GB phone with an always-on mic service is at the memory edge; IQ2_S is the roomier fallback.
  Test real RAM headroom; keep OLMoE-1B-7B as the comfortable fallback.
- The mic indicator stays on whenever ambient listening runs. Cannot be hidden.
- Expect single-digit tok/s under thermal load.
