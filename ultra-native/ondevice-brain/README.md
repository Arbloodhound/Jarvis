> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/jarvis-native/ondevice-brain/README.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# On-device brain — build bundle

The bundle for running Agent Ultra's brain on the phone itself: a large
on-device model wired into `local/` (on-device models via llama.cpp JNI,
with in-app downloads), plus the always-listening ambient layer in
`listen/` (ambient listening, speech, notes, the Librarian, and claim
promotion). Target device in the guides: Pixel 10 Pro on GrapheneOS.

> **Verification note.** The 2026-10-19 audits were static, grep-level
> reading of the code. They did **not** re-run anything on a device: model
> fit, memory headroom, token speeds, thermal behaviour, and wake-word
> behaviour below are carried over from the pre-consolidation bundle and
> were **not re-verified in the 2026-10-19 audits**. `local/` and `listen/`
> both carry the audit verdict "works with defects" (see the repo status in
> `../README.md`), and one audit finding bears directly on this bundle:
> GGUF downloads are verified by size only, with no integrity check before
> native load. CI has not yet compiled the merged tree.

## Files

- `Qwen3_30B_A3B_LoRA_Colab.ipynb` — LoRA-specialize the base MoE, merge,
  GGUF, quantize.
- `0001-ambient-listen.patch` — the always-listening layer (git patch).
- `build-whisper-android.sh` — build whisper.cpp arm64 libs the patch needs.
- `BUILD_GUIDE.md` — ordered build-and-deploy follow-along.
- `PIXEL_TEST_GUIDE.md` / `G15_TEST_GUIDE.md` — on-device test checklists.
- `RELEASE_SIGNING.md` — release key handling and the GitHub-built release.
- `CLAUDE_CODE_BRIEF.md` — point-in-time brief (historical record).
- `evidence/` — raw on-device evidence extracts (historical record).

## Model

Base discussed in the bundle: `Qwen/Qwen3-30B-A3B-Thinking-2507` (Qwen3 MoE,
30B total / 3B active), with an abliterated variant used for the
specialized build. The pre-consolidation bundle documented a 16 GB phone
budget of roughly 11 GB usable for weights after OS, KV cache, and the
listening stack, and wired four builds into `LocalModelEngine.kt` (default
stays a small safe model; the MoE is an opt-in pick), with context capped
at 4k:

| Build | Size (as documented) | Verdict (as documented) |
|---|---|---|
| IQ3 (any) | 11.8–12.9 GB | Over the 16 GB total — desktop only |
| Abliterated IQ2_M | 10.2 GB | Recommended on-device build |
| Abliterated IQ2_S | 9.3 GB | Roomier fallback |
| Stock IQ2_M | 10.8 GB | Knife-edge — comparison only |

These sizes and verdicts are the bundle's own pre-consolidation figures,
not re-verified in the 2026-10-19 audits.

Two paths:

1. **Test without training** — download the prebuilt IQ2_M GGUF, push it to
   the model dir (or let the in-app downloader fetch the wired URL), and
   pick it in Settings.
2. **Specialized build** — the notebook runs LoRA directly on the
   abliterated model, merges, builds an imatrix from formatted chats, and
   quantizes to IQ2_M (plus an IQ2_S fallback). Training-data guidance from
   the original bundle: keep `<think>` traces in most training answers and
   keep refusals out of the data. Then point the corresponding
   `ModelChoice` URL in `LocalModelEngine.kt` at the resulting repo, or
   sideload the GGUF with a `file://` URL. Raise `CTX_SIZE` only if RAM
   allows; leave `THREADS=4`.

## Native libs (both required for the app to build)

```bash
export ANDROID_NDK=~/Android/Sdk/ndk/<ver>
./build-llama-android.sh   # llama.cpp -> ~/llama-android/{lib,lib-dotprod,lib-i8mm,include}
./build-whisper-android.sh # whisper.cpp -> ~/whisper-android/{lib,lib-dotprod,lib-i8mm,include}
```

## Training (Colab, 80GB-class GPU)

The notebook states it needs ≥64 GB VRAM (the MoE trains in 16-bit; a 40 GB
A100 will OOM). Its preflight cell checks VRAM and disk before any
download; the data check and a measured ETA (after 13 steps) run before the
long part. Output: `qwen3moe-abliterated-iq2m.gguf` (~10.2 GB) on Drive,
with an optional IQ2_S fallback from the same files.

## Ambient listening — ASR models

Downloaded to the app's `filesDir` via the existing model-download flow (or
adb push):

- `ggml-base.en-q5_1.bin` (whisper)
- `ggml-silero-v5.1.2.bin` (VAD)

Verify current URLs in `listen/AmbientConfig.kt` before release. Then:
Settings → Ambient listening ON, and set a distinctive wake name.

The patch adds the `listen/` package — AmbientConfig, Segmenter (energy
gate), WhisperNative (+ `cpp/jarvis_whisper.cpp`), AmbientMemory (FTS4
store), WakeFilter, AmbientListenService (mic foreground service),
BootReceiver — plus the `ambient_recall` tool, ambient-context injection on
wake, a TTS echo guard, settings UI, manifest permissions/service/receiver,
and a CMake whisper target with isolated ggml symbols. (Component list as
documented pre-consolidation; the merged `listen/` package was audited as
"works with defects", and component-level presence was not re-verified in
the 2026-10-19 audits. One post-merge manifest gap is known in the harness
package — `SchedulerReceiver` and `SpotifyRedirectActivity` are not
registered in `AndroidManifest.xml` — but that is unrelated to this
patch's receiver.)

## Still missing (carried over, not re-verified)

1. openWakeWord acoustic gate (battery — whisper stays asleep until the
   name is spoken)
2. Web tools (search + fetch) in the agent
3. Thermal guard + battery-unrestricted setup
4. Even G2 connection
5. Prosody / social-cue branch (experimental — reports measured signal,
   not verdicts)

## Honest limits

- The MoE at IQ2_M on a 16 GB phone with an always-on mic service sits at
  the memory edge, per the original bundle; IQ2_S is the roomier fallback.
  Real RAM headroom has to be tested on the device — it was not re-verified
  in the 2026-10-19 audits.
- The mic indicator stays on whenever ambient listening runs. It cannot be
  hidden.
- Expect single-digit tok/s under thermal load (original bundle's figure;
  not re-verified).
- GGUF downloads are verified by size only — no integrity check before
  native load (2026-10-19 audit finding, still open).
