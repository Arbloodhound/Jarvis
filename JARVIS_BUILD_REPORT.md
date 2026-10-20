> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/JARVIS_BUILD_REPORT.md`. CI has not yet compiled the merged tree.

## Status as of 2026-10-19

This report predates the 2026-10-19 consolidation. All work is now on `main` (tip c8043e1c); 51 of 58 branches were already contained, 2 were merged in the consolidation, and 4 remain unmerged by design; no branch was deleted.
The 2026-10-19 code audits covered two snapshots: pass 2 found 119 defects (1 critical, 16 high, 41 medium, 61 low) and the supplement found 87 findings (0 critical, 12 high, 31 medium, 44 low) — 206 findings in total, not a deduplicated count of the merged tree.
Post-merge there are 0 known criticals: the pass-2 critical (harness DB wiring) is resolved — JarvisDatabase is v9, with the harness entities, DAO accessors, and MIGRATION_7_8 + MIGRATION_8_9 registered.
CI has not yet compiled the merged tree, and audit verification was grep-level (definitions/call sites/imports), not compilation or device testing.
This report (2026-10-08, branch `claude/new-session-t3cy17`, v2.6.34-test) describes the Kotlin phone app, its models, phone measurements, hardware limits, and safety design.
The merged tree still centres on `jarvis-native/` (the shipping Kotlin app, with the harness package `com.agent.jarvis.harness` inside it), plus the `desktop/` TS app and the `src/` TS app.
Per-subsystem audit verdicts: `net/` Tor and `data/` JarvisDatabase work as designed (defects at the margins only for Tor); `agent/`, `gate/`, `listen/`, `local/`, `provider/`, `research/`, and `bench/`/tools work with defects.
Checked clean and consistent with the safety design described here: Tor fail-closed (three guards, no clearnet fallback), Gate deny-by-default, and purchases never grant-coverable at either layer.
Still open and relevant to the build described here: GGUF downloads verified by size only with no integrity check before native load; the cloud API key in plaintext SharedPreferences; and voiceprint speaker tags defaulting ON against their own doc comment.
The audits do not re-verify this report's phone measurements, model speeds, or hardware-acceleration claims: they were code-only and grep-level, with no device testing. Those numbers are not covered.

---

# Jarvis — what we built, how, and what the phone taught us

For: the model that designed the "MVP Personal Agent" (README_1 / README_2, the 12-layer system diagram, the
Personal Agent Blueprint). Purpose: so you can merge your design with what already exists and works within the
real limits of the owner's phone. Written 2026-10-08 from the project's DEVLOG, HANDOFF, ARCHITECTURE.md and source
(branch `claude/new-session-t3cy17`, release v2.6.34-test).

**How to read the evidence labels.** Every claim below carries one of:
- **MEASURED** — a number from the owner's real phone (results files / screenshots).
- **PROVEN (host/tests)** — shown by automated tests or by running the native code on a Linux host, not on the phone.
- **SOURCE ONLY** — the code does it; never confirmed running on the phone.
- **UNKNOWN** — nobody has measured it.

Please label your claims the same way when you answer. Only your READMEs reached us, not your code, so we
could not check any of your claims; we treated them as design intent.

---

## 1. The non-negotiable constraints (read these first)

| Constraint | Detail | Consequence |
|---|---|---|
| **Everything runs on one phone** | Pixel 10 Pro XL (Tensor G5), Android 17. No server, no PC: the owner uses a Chromebook and has no adb. | Your gateway, API server, Python core, APScheduler, Docker, Playwright and WebSocket bridge have nowhere to run. Anything you design must run inside one Android APK, or be optional. |
| **De-Googled intent** | Owner wants no Google services (assume GrapheneOS-like). | Android's built-in SpeechRecognizer is Google's on a Pixel and usually absent when de-Googled. Google TTS may be absent. Google's Tensor ML SDK needs Play delivery → unusable. ML Kit OCR works offline without Play services (PROVEN from bytecode) but sends usage telemetry to Firebase logging (known, not fixed). |
| **Memory** | 15,081 MB total RAM, but only **≈8.6 GB actually available** to the app (MEASURED). Phones have unified memory: no separate VRAM. | Model weights + KV cache + Android + other models must fit in ~8 GB. See §3. |
| **CPU only for LLMs** | CPU: 1×3.78 GHz + 5×3.05 GHz + 2×2.25 GHz, with i8mm / sve2 / bf16 (MEASURED). | All LLM, vision and speech inference runs on the CPU. The accelerators don't help (§4). |
| **Heat** | The phone throttles. At headroom ~0.8 the policy dropped to 2 threads and speed collapsed (MEASURED). | Every long-running job needs a thermal budget. Sustained speed is lower than burst speed. |
| **Owner is not a programmer** | Installs via Obtainium from GitHub Releases, tests by tapping one button (Jarvis Tests → ★ Real-use check) and sending a results file. | Nothing may need a terminal, a laptop, or configuration files. Every feature must be testable from that button. |
| **Owner priorities** | High reasoning quality over features; deploy rather than experiment; heavy emphasis on **searching** (web, products, forums, best price); background listening + notes + conversation insights; hands-free "Hey Jarvis"; driving apps on screen with a Stop button. | Features that cost RAM, latency or heat without improving those get rejected. |

---

## 2. How Jarvis is built

### Stack
- **App:** native Android, Kotlin + Jetpack Compose, package `com.agent.jarvis`. minSdk 26, targetSdk 35. The app code lives in `jarvis-native/` in the GitHub repo Arbloodhound/Jarvis, which is private.
- **Native, via C++ JNI:**
  - llama.cpp, pinned at commit 8345f33, built with libmtmd for vision.
  - whisper.cpp, pinned at 60c0be6.
  - sherpa-onnx 1.13.8 for text-to-speech; the static-link build is used so the APK carries only one ONNX Runtime.
  - ONNX Runtime 1.30 for the wake word, embeddings and voiceprints.
- **Three CPU builds of each native library:** armv8-a, dotprod and i8mm. The right one is chosen at runtime (`CpuVariant`), and a script checks that each build really contains those instructions. All native libraries are 16 KB page-aligned, which Android 15 and later require.
- **Storage:** Room/SQLite.
  - `jarvis.db`: chat history.
  - `ambient.db`: heard lines, FTS4 search, int8 vectors, tags and voices.
  - `memory_notes.db`: notes, with provenance back to the heard lines they came from.
  - JSONL audit log.
- **Cloud (optional):** any OpenAI-compatible endpoint with streaming (SSE). It is only used if the owner enters a key. By default there is no key, so everything is on-device.

### Models in use (all GGUF / ONNX, downloaded in-app, resumable, 4 parallel connections)

| Role | Model | Size | Notes |
|---|---|---|---|
| **Thinker** (main brain) | Qwen3-4B-Thinking-2507, abliterated, Q4_K_M | 2.50 GB | The owner chose this. The stock 4B Q4_0 and Qwen3.5-4B abliterated can also be selected. |
| **Librarian** (memory organiser) | Qwen3-1.7B Q4_0 | ~1 GB | 4096 context, 2 threads. It works while the phone charges or is idle, and yields to the thinker. |
| **Vision** | Qwen2.5-VL-3B-Instruct Q4_K_M + mmproj Q8_0 | 1.93 + 0.84 GB | Loaded only when needed. Screenshots are scaled to 896 px. Unloaded after 2 minutes idle. |
| **Speech to text** | whisper small.en q5_1 (base.en as a fallback) | 190 MB | Silero VAD. The app switches to base automatically if small can't keep up with real time. |
| **Wake word** | openWakeWord ("Hey Jarvis") | small | Three ONNX models plus a text check on the whisper transcript. |
| **Embeddings** | all-MiniLM-L6-v2, int8 ONNX, 384-d | small | The same model your README_2 names. Already in Jarvis. |
| **Voiceprints** | TitaNet | 40 MB | Speaker labels: [Me] / [name] / [Speaker N]. |
| **Text to speech** | Piper "Ryan" (high) by default; Kokoro (11 voices) and 10 Piper voices selectable | ~20–80 MB | Through sherpa-onnx. The phone's own voice is the fallback. |
| **OCR** | ML Kit text recognition 16.0.1, bundled, Latin script | in APK | Works offline. Sends telemetry (known issue). |

### Architecture (maps onto your 12 layers)
Request flow:
1. A request arrives from "Hey Jarvis", the assistant button, or typed chat.
2. **Brain.run** handles it in this order, stopping at the first that applies:
   - **Interrupt:** stop, add-on, or replace the current task.
   - **Deterministic shortcuts:** notes, time, recipes, memory focus.
   - **Scam check.**
   - **Route choice:** one of
     - the on-device tool call (`LocalToolCall`, a small catalog of device tools);
     - the on-device answer (`emitLocal`, with web research and the memory brief added);
     - on-device app driving (`OfflineNavigator`: perceive → think → act → verify);
     - the cloud tool loop (`runLoop`, at most 12 turns, 40 tools).
3. **Every action** passes the deterministic **Gate**: per-tool manifest of effects, contracts, risk score, a taint check for secrets and screen data, and a confirm card for commitments.
4. The answer is **streamed and spoken sentence by sentence.**

The full diagrams are in `ARCHITECTURE.md`: 8 Mermaid diagrams, plus the new **Verification tree**, which runs from every entry point down to every action and names the test or phone probe that covers each branch.

| Your layer | Jarvis today |
|---|---|
| 1 Client surfaces | Chat, voice screen, assistant button, "Hey Jarvis", notifications, takeover overlay. All in-app. |
| 2 Gateway / SSE | In-process. SSE is used only to the optional cloud model. There is no server, by design. |
| 3 Harness | Brain plus Interrupt (Stop / After / Replace / Run). Paused confirmations live in memory only, so a process kill loses the run. |
| 4 Model router | On-device by default, with cloud only if a key is set. **New:** an "Offline only" switch (default OFF) and an audit line for every routing decision. |
| 4b Pipeline hooks | Fixed stages, not configurable: wake gate → whisper → embedder → librarian → vision. Each extra model costs RAM and latency here. |
| 4c Plugin registry | Not built. A static manifest of 40 tools is checked against the code in both directions by a test (`ToolWiringTest`). Loading code at runtime is an attack surface on Android. |
| 5 Tools | 40 tools with effects declared (read / mutate / egress). **New:** typed arguments that fail closed. |
| 6 Sandbox | No code-execution tool at all. That is a smaller attack surface than a sandbox. |
| 7 Browser | Drives the owner's **real** installed browser (Tor Browser included) through the accessibility tree plus OCR, under a full-screen overlay with a Stop button. Playwright cannot run on a phone. |
| 8 Memory | Heard speech → ambient.db, with keyword search (FTS4) and int8 MiniLM vectors. The librarian writes dated notes with provenance. Recall combines exact vector search, keywords, a time window and recency. |
| 9 Scheduling | Librarian loop, event triggers, boot resume. No user-defined scheduled tasks (deferred). |
| 10 Approvals | Confirm card bound to the exact tool and parameters. The on-screen tap gate denies after a 120 s timeout. |
| 11 Security | Untrusted sources (screen, SMS, web, overheard speech) are labelled *below* the model, which forces a confirmation before anything leaves the phone. **New:** an `[UNTRUSTED DATA]` wrapper in the cloud prompt, and the audit log now rotates instead of being cut. |
| 12 Usage / budgets | Device budgets instead of billing: think tokens, wait time, heat, and a 12-turn cap. |

---

## 3. What we measured on the phone (the important numbers)

### LLM speed
| Measurement | Result | Status |
|---|---|---|
| 30B MoE IQ2_M (9.7 GB file) | **0.4–1.6 tok/s**, 25k–227k major page faults per answer, prefill of 197 tokens took 25–57 s | MEASURED. **Does not fit:** 9.7 GB of weights > 8.6 GB available. The kernel keeps re-reading pages. No setting can fix this. |
| Qwen3-4B Thinking, threads 4 / 5 / 6 | **8.96 / 7.23 / 4.79 tok/s** in one sweep | MEASURED. More threads is *slower*. 4 threads on the big cores is the default. |
| Qwen3-4B Thinking, steady in the brain test | **~6.0 tok/s**, 0 major faults, ~5.2 GB free | MEASURED (owner screenshot). Another quick run showed ~3.4 tok/s (cause not isolated; heat likely). |
| Cool phone, 6 threads | 10.2–11.6 tok/s | MEASURED once. In every same-run comparison 4 threads was ≥ 6. |
| Speculative decoding (Qwen3-0.6B draft) | **6.3 tok/s with the draft vs 9.3 without**, and one answer came out truncated | MEASURED. Slower, so it is OFF by default. |
| Cold first prompt read | ~25 s for a 745-token prompt on first use | MEASURED. Fixed by warming the system prompt once after load, so later questions reuse it (SOURCE ONLY). |
| Thinking-model overhead | "hi" spent ~160 tokens thinking | MEASURED. That is why every request type has a **think budget** and **wait budget** (spoken requests ≤ ~3–5 s of thinking). |
| A spoken answer end to end | 7.0 s, correct answer; "stop" released in 0.0 s | MEASURED (2.6.27). |

### Speech and voice
| Measurement | Result | Status |
|---|---|---|
| whisper base on the two little cores, 2 threads | 67–148 s per 28 s utterance (2.5–5× slower than speech); 129 utterances dropped | MEASURED. Fixed by placing whisper on idle big cores. |
| whisper on cores 2–7 | **0.4× real time** (keeps up) | MEASURED (2.6.28). |
| Kokoro TTS | **1.3–1.5× slower than real time**, even with the librarian yielding | MEASURED. That is why the default voice is Piper Ryan. |
| Wake word on Jarvis's own synthetic voice | Acoustic scores 0.26–0.97 across runs; whisper heard "Hey Joris" / "I hate Jarvis" | MEASURED. The fix is a text-side fuzzy name match accepted **only right after a wake word**. |
| Downloads, 4 connections vs 1 | **15.7 MB/s vs 7.3 MB/s** | MEASURED. |

### Not yet measured (UNKNOWN)
Not yet measured:
- Vision model load time, speed and memory together with the thinker.
- OCR quality.
- Whether DuckDuckGo blocks searches coming from the phone.
- Recall scan time at 40k vectors. The new probe measures this.
- Overlay settle time.
- The quality of the abliterated model's reasoning.

Nothing in releases 29–34 has been confirmed on the phone yet.

---

## 4. Hardware acceleration — what's real

| Path | Finding | Status |
|---|---|---|
| **Edge TPU (NNAPI "google-edgetpu")** | Present on Tensor G5. It **rejects** two of the three wake models ("cannot run using the current set of target devices"). It accepts the embedding model with identical output but gives no reliable speed-up (faster once, 4× slower another time). | MEASURED |
| **GPU (PowerVR, OpenCL 3.0 / Vulkan)** | The libraries load. whisper.cpp's Vulkan backend is fragile on PowerVR, and its OpenCL backend is Adreno-only. Nothing usable for the LLM today. | MEASURED (loads) / research |
| **Google Tensor ML SDK** | Needs Google Play delivery, so it is unusable on a de-Googled phone. | Research |
| **What actually helped** | i8mm CPU kernels with Q4_0 / Q4_K weights repacked for i8mm (IQ2 is never repacked); pinning threads to the big cores; 4 threads; q8_0 KV cache with flash attention; prompt-prefix KV reuse. | PROVEN on host; speed MEASURED |

The placement rule we use for small ONNX models: time each one on CPU, NNAPI and XNNPACK at startup. The accelerator is kept only if it is **≥20% faster AND its output matches the CPU's** (≤1e-3 relative difference). Otherwise the model stays on the CPU.

---

## 5. Optimisations that mattered (and why)

### Memory and loading
- **Fit the model to *available* RAM, not total RAM.** The default model is chosen from real free memory. Resident (non-mmap) loading is used only if available ≥ file size + 1 GB.
- **Page-cache warming.** With mmap, Android drops weight pages and then re-reads them one at a time (42,299 major faults were seen while 8.6 GB was free). Reading the whole file sequentially after load keeps the pages in cache.
- **Never load on the UI thread.** The first on-device answer used to load the model on the main thread, which caused two ANRs (MEASURED, stack traces). Loading is now a suspend function on the IO dispatcher.
- **Context size from RAM.** A per-model KV table and memory arithmetic decide the context length. n_ubatch is pinned at 512. If a larger context fails, it retries at 4096.

### Prompt reuse and history
- **Prefix KV reuse.** A stable system prompt and a stable history window mean each turn re-reads only the new tokens.
- **The history policy is also a quality fix.** A small model copied the previous answer from history into an unrelated reply (MEASURED). On-device answers now get history **only when the request refers back** ("that", "it", follow-ups).

### Generation budgets
- **Think budget + wait budget.** Silent thinking is capped per request type, using a measured decode speed for each model. Spoken requests get at most ~3–5 s of thinking.
- **Act first.** Device commands pick the tool with no hidden thinking (budget 0) and explain afterwards.

### Sharing the CPU between models
- **Two models, one CPU.** A shared native mutex is used. The librarian yields to the thinker, and a wake word, a command or Jarvis speaking cancels the librarian's run at the next token. Listening queues utterances (at most 8 / 2 min) while the brain is busy.
- **Whisper placement.** Whisper goes to idle big cores when the thinker is idle and the phone is cool; otherwise it uses the little cores.
- **Thermal policy.** Threads go 4 → 3 (WARM) → 2 (HOT), with headroom thresholds 0.75 / 0.87 / 0.95.

### Speech output and audio
- **Speak while generating.** The answer is cut into sentences as they are generated. The first piece must be ≥ 40 characters and later pieces ≥ 140, so speech doesn't come out as single words with pauses. Pieces go through a sequential speech queue.
- **Echo guard.** Every voice output mutes the listener, plus a 700 ms echo tail; the wake model keeps listening so the owner can still interrupt. Before this, Jarvis heard and answered itself (proven from source).
- **Audio routing.** Text-to-speech uses USAGE_MEDIA. With USAGE_ASSISTANT, the phone played the speech but the owner never heard it (log showed it playing; routing is the likely cause).

### Downloads and release
- **4-connection, ranged, resumable downloads** into a single .part file, with fsync'd progress. Before this, one Wi-Fi drop restarted a 10 GB download from zero.
- **Release pipeline for a non-programmer.** A GitHub Actions build signed with the release key publishes a normal release marked Latest (`v2.6.<run>-test`, the newest 5 kept), and Obtainium on the phone updates from it. Native libraries are cached, so a build takes 6–15 min. Installs go over the previous version, so the multi-GB models are kept.

---

## 6. Android platform gotchas we hit

- **Microphone after a restart.** Android 15+ refuses to start a microphone foreground service from BOOT_COMPLETED. Jarvis posts a "tap to resume listening" notice instead, and resumes when the app opens.
- **Accessibility service toggle.** It must be switched off and on once before screenshots work (`canTakeScreenshot`).
- **Takeover overlay.** A full-screen accessibility overlay swallows the owner's touches; Jarvis's own gestures pass through while the overlay briefly turns not-touchable. Two limits:
  - The 120 ms settle time is reasoned from AOSP, not measured.
  - The overlay covers the status bar.
- **Backups.** `allowBackup="false"` does **not** stop device-to-device transfer on Android 12+; that needs `data_extraction_rules`. **Fixed:** all app data is now excluded.
- **Logs.** Other apps can't read the app's log (logcat) since Android 4.1, but adb or a bug report can. Jarvis logs up to 2000 characters of tool results, which can include SMS text. **Open question for the owner.**
- **Doze cuts background network.** The download screen keeps the screen on and holds a partial wake lock.
- **Debug signing.** Each CI runner had a random debug key, so a new test build couldn't install over the last one. The fix is a fixed signing key, so an update keeps the 10 GB of models.
- **Running code.** No user namespaces for apps (SELinux `untrusted_app` + seccomp), so **bwrap, nsjail and Docker are impossible**. The closest Android equivalent would be an `isolatedProcess` service with a bundled interpreter.
- **Text crossing from C++ to Java.** JNI `NewStringUTF` expects Modified UTF-8. Both LLM shims let invalid UTF-8 through to Java (6 of 12 cases failed) — fixed and PROVEN on host. whisper's prompt path still has this issue for emoji.

---

## 7. What small on-device models get wrong (design around these)

These are behaviours we saw in Jarvis's own results files, not assumptions:
- **Copying prompt examples.** The 1.7B librarian saved the prompt's example notes as real notes. The fix drops any note ≥ 0.8 similar to an example.
- **Copying chat history.** The IQ2 model repeated the previous answer for a new question. The fix sends history only when the request refers back.
- **Incomplete JSON.** The 1.7B wrote an observation JSON with no `"text"` field. The fix derives a summary instead of rejecting the note.
- **Faking an action in text.** "Turn on the flashlight" got a text answer with no tool call. The fix is a dedicated on-device tool path that reads only the text after the thinking, offers only a small set of device tools, and falls back to a text answer.
- **Claiming it can't do things.** The model said "I can't browse / I have no memory / I can't see the screen" when the prompt didn't tell it what the app does. The prompt now states what is true (memory, notes, web search, screen access, the current date and time), and memory is put into the prompt for questions like "what do you remember?".
- **Thinking too long.** Thinking models spend many tokens on trivial input, so hard budgets are needed (§5).
- **Fail-open tool arguments.** A missing `"on"` used to turn wifi ON, and a missing volume set it to 50%. **Fixed:** arguments are typed and fail closed, and the bare-text parser also refuses to guess.

---

## 8. Safety design (what we'd keep from Jarvis)

- **Enforcement below the model.** Tool effects are declared in a manifest, and an undeclared tool is denied. Data from the screen, SMS, web or overheard speech is marked low-trust, which forces a confirmation before any egress. Secrets (codes, cards seen on screen) taint any argument that contains them. None of this depends on the model obeying a label.
- **Confirm card bound to the exact call.** It runs exactly the tool and parameters shown; a new request cancels a stale pause; on-screen taps on commitment buttons (send / pay / delete / agree) ask first, and a timeout denies.
- **"Don't ask when I asked" (the owner's choice, default on).** If the request already contains the commitment verb, the tap goes ahead. It **always** still asks for:
  - money, or any screen showing a price;
  - deletion, wipe, reset, uninstall or sign-out;
  - agree / accept;
  - unreadable labels;
  - messages, unless a contact named in the request is on screen;
  - anything that doesn't match the request.
- **Scam watch.** Follow-up actions after a scam pattern are blocked, and the scam is warned about out loud.
- **Protected apps.** Banking, mail and messaging apps are pre-ticked at first run. The "unrestricted access" switch defaults ON, by the owner's choice.
- **Audit log.** JSONL with no arguments and no user text. It rotates to a `.1` file instead of being cut. It records the route of every request and every on-screen tap decision.
- **`[UNTRUSTED DATA]` wrapper.** Used in the cloud loop, with the closing marker stripped from the data so it can't be spoofed. Kept out of the on-device prompt because the small models' token budget is tuned.

---

## 9. How we verify without an emulator (and how you should too)

There is no KVM in our build container, so no emulator. The owner has no adb, so there's no USB access to the phone. Verification therefore has four layers:

1. **JVM unit tests and Robolectric: 1338 tests, all passing, run in CI on every build.**
   - Pure logic lives in objects marked "Pure, JVM-tested".
   - `ScreensTest` renders the real Compose screens and taps them.
   - `BrainLoopTest` drives the **real Brain** against a scripted local SSE server. Covered: plain answer, tool call → result, confirm / cancel / resume, HTTP 500, re-ask after a truncated tool call, undeclared tool blocked, and offline-only sending zero requests.
   - `ToolWiringTest` checks all 40 tools against the manifest in both directions.
2. **Host harnesses** (`jarvis-native/tools/host-harness/`): the JNI C++ shims run on Linux against the same llama.cpp and whisper.cpp commits, with tiny random models.
   - The vision output is token-for-token identical to `llama-mtmd-cli`.
   - AddressSanitizer is clean, and there was no leak over 995 runs.
   - Prefix reuse equals a fresh engine.
3. **On-phone TestKit** (debug build, "Jarvis Tests" icon). The ★ Real-use check is one tap, takes about 5 minutes, and writes a results file. It covers:
   - real room listening;
   - voice synthesis speed;
   - "Hey Jarvis" played through the phone's own speaker and heard by the real listener;
   - a spoken request through the real brain;
   - stop latency;
   - download speed;
   - an "Offline only, for real" check.

   The Feature check runs about 20 more probes, including "Memory scan at 40k".
4. **Results files back from the owner** → fix only what they prove → one release per batch.

Every conclusion is labelled PROVEN / PARTIALLY PROVEN / SOURCE-FIXED BUT RUNTIME-UNPROVEN / CANNOT VERIFY.

---

## 10. What we took from your design, and what we didn't (with reasons)

**Built from your design (2026-10-08, v2.6.34):**
- Typed, fail-closed tool arguments.
- An offline-first master switch, plus an audit line for every routing decision.
- Append-only-style audit rotation.
- Private data excluded from backup and device transfer.
- The `[UNTRUSTED DATA]` labelling.
- A phone probe to settle the memory-index question.
- The Brain test harness and the verification tree.

**Already in Jarvis, and stronger than in the README:**
- Approval bound to the exact call.
- Injection defence enforced below the model.
- Real-browser control.
- Local MiniLM embeddings.

**Rejected, with evidence:**
- **RVQ 4×16 + IVF scout + exact sniper + MLP adapters (README_2).** RVQ 4×16 is 16 bits per vector, far coarser than our 388-byte int8 vectors. The "sniper" still needs the full vectors, so it saves no storage. IVF/ADC can only lose recall against our exact scan. The codebooks would need retraining as new speech arrives every day, and the MLP adapters need torch, which isn't on the phone. Your README's proof is a 20-document test. Our store is 10k–100k vectors (exact scan over the newest 40k). The probe will measure the actual scan time; if it's ≤ 1 s, no index is needed.
- **Tiered sandbox (bwrap → nsjail → docker → subprocess).** None of the four tiers works for an Android app (§6).
- **E2B and cloud sandboxes:** they need the cloud.
- **Gateway, API server, SSE gateway, WebSocket bridge to a Python core:** there is no PC or server to run them on.
- **Plugin registry:** loading code at runtime is an attack surface; a static manifest plus a two-way test is safer.
- **Configurable 10-stage pipeline with helper models:** every extra model costs RAM, latency and heat on this phone. Fixed stages are the right call.
- **Playwright:** it can't run on Android.
- **MCP:** it has no value without a server.

**Deferred:**
- **Keystore-wrapped API key:** a Keystore failure would silently lose the cloud brain.
- **Durable jobs table for scheduled agent tasks:** a new feature, and the owner wants to deploy now.

---

## 11. What would help most from you (for the merge)

1. **Send code, not just READMEs.** We could not verify any claim in your READMEs: the RVQ memory, the router and the sandbox were all described, not shown.
2. **Label every claim:** measured / tested / designed only. Say on what hardware.
3. **Design for the constraints in §1.** That means one APK, CPU-only, ~8 GB available memory, thermal throttling, no Google services, no server. Anything server-side should be clearly optional, a companion the owner may never run.
4. **Most useful from your side:**
   - A **policy matrix** as data: data classes × destinations → allow / deny / ask. Jarvis has the per-tool effects and contracts (`assets/jarvis.manifest.json`) but no written data-class policy.
   - A **replayable trace / eval set** for the brain: inputs and the expected tool calls.
   - **Durable checkpoints**, so a paused confirmation survives the app process being killed.
   - **Better search quality:** query planning, source cross-checking, product and seller trust scoring. This is the owner's top priority. Jarvis's current version: 2–4 queries with search operators, DuckDuckGo HTML, ranking by consensus + site trust, top 3 pages read, prices extracted, a cited brief.
   - **Ideas for higher reasoning quality from a ~4B model at 6–9 tok/s:** prompting, routing, verification. Bigger models don't fit (§3).
5. **Don't propose:** a bigger model than ~4–5 GB of weights, a GPU or NPU LLM path, speculative decoding (measured slower), or anything that needs Google Play services.

---

## Key files (if you get repo access)
- `ARCHITECTURE.md`: diagrams + verification tree.
- `DEVLOG.md`: every session, with its evidence.
- `HANDOFF.md`: the current state.
- `jarvis-native/app/src/main/java/com/agent/jarvis/`, by area:
  - **Brain and actions:** `agent/Brain.kt`, `agent/Tools.kt`.
  - **Gate:** `gate/Gate.kt`, `gate/Manifest.kt`, `assets/jarvis.manifest.json`.
  - **Models:** `local/LocalModelEngine.kt`.
  - **Memory:** `listen/MemoryRecall.kt`, `listen/AmbientMemory.kt`, `listen/Librarian.kt`.
  - **Search:** `agent/WebResearch.kt`.
- `jarvis-native/app/src/main/cpp/`: the JNI shims (`jarvis_llm.cpp`, `jarvis_vlm.cpp`, `jarvis_whisper.cpp`).
- `jarvis-native/tools/host-harness/`: the native tests that run on a Linux host.
