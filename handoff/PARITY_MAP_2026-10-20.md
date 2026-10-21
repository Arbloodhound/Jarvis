# PARITY MAP — phone features vs desktop client — 2026-10-20

Program contract for the desktop feature-parity build. Owner directive: the
desktop client must have ALL features the phone app has; include everything
now, test before running.

Sources (read directly, 2026-10-20):
- Phone reference (v5): ~/workspace/jarvis-rebuild/ARCHITECTURE.md,
  MERGE_PLAN.md, harness/*.py, features/*.py
- Phone live app (Kotlin): ~/workspace/jarvis-rebuild/build-src/main/
  ultra-native/app/src/main/java/com/agent/ultra/ (agent/, listen/, net/,
  research/, local/, harness/, gate/, provider/)
- Desktop (main @ fc22170776): ~/workspace/jarvis-rebuild/
  main-after-merge2/desktop (src/, test/ — 73 tests green)

Status key — phone: LIVE = implemented in the Kotlin app; REF = real
reference implementation in Python v5 (semantics fixed, device backing
swappable); DESIGNED = scoped/docs only, no implementation found.
Desktop: HAVE / PARTIAL / MISSING / IN-FLIGHT (another agent's branch).

| # | Feature | Phone | Desktop | Cluster / notes |
|---|---------|-------|---------|-----------------|
| 1 | Agent loop + tool registry + validate/retry | LIVE | HAVE | Desktop loop.ts + tools registry; grammar validation is model-side on phone, schema-side on desktop |
| 2 | Approvals / gate / confirm cards / audit | LIVE | HAVE | Desktop gate/ (gate, audit, scam, secrets) is at parity or better |
| 3 | Memory: claims, kind/salience, decay, provenance, supersede | LIVE | PARTIAL | Desktop memory.ts has remember/recall/forget + trash + embeddings, but not the full claim semantics (kind/salience/decay/supersede chains) of harness/memory.py → folded into Echolalia cluster's scope? No — claims upgrade tracked here; Echolalia cluster adds verify-before-store ON the memory path. Claims-semantics gap noted as residual in report if time-boxed. |
| 4 | Embeddings / semantic recall | LIVE | HAVE | Desktop embeddings.ts (OpenAI-compatible, MRL) ≈ phone SentenceEmbedder/MemoryVectors |
| 5 | Providers (local + cloud brains, switching) | LIVE | HAVE | Desktop providers/ claude + local |
| 6 | Vision (look at image/screen) | LIVE | HAVE | Desktop vision.ts + tools; runtime-proven 2026-10-20 |
| 7 | Screen control + screen-bound guard | LIVE | HAVE | Desktop control_screen + platform.ts (honest-reporting fixed) |
| 8 | Fast commands (no-model one-shots) | LIVE | HAVE | Desktop agent/fast.ts explicitly ports FastCommand.kt |
| 9 | Voice: STT (whisper-class) + TTS + finished-utterance rule | LIVE (WhisperNative.kt, LocalVoice/LocalSpeech, VoiceActivity; ambient in listen/) | MISSING | CLUSTER voice. Desktop equivalent: transcribe + speak tools, finished-utterance guard. Ambient always-on listening is phone-hardware-shaped → NOT ported; difference marked, not faked |
| 10 | Thinking tiers + dual-router (Claim 10 heuristic/literal matrix) | LIVE/REF (IntentRouter.kt; features/router.py tiers) | PARTIAL (fast commands only; config effort exists but no router) | CLUSTER router |
| 11 | NLI gate (contradiction check before claims reach user) | REF (features/nli_gate.py deterministic impl; on-device NLI head was the device backing) | MISSING | CLUSTER nli |
| 12 | Autonomous browsing (multi-step, full-page contract) | LIVE (research/AutonomousBrowse.kt; harness/browse.py contract) | PARTIAL (single-shot web_fetch/web_search only) | CLUSTER browse. Desktop contract adaptation: full HTML text + step-bound provenance + explicit failure; no renderer screenshot leg on CLI core — marked |
| 13 | Investigate module (case files, 4-leg orchestration, contacts incl. handles/PGP, draft-only outreach, per-recipient approval) | LIVE (research/Investigation.kt, Discovery.kt, Contacts.kt; features/investigate.py) | MISSING | CLUSTER investigate |
| 14 | Skills system (lazy loading, SKILL.md directory, allowlist) | REF (harness/skills.py full contract impl) | MISSING — correction (skills builder, grep-verified): NO skills code existed in the desktop tree at all (one README sentence); the zero-callers SkillStore is phone-side only. Desktop got a fresh faithful TS port | CLUSTER skills — DONE, see PARITY_SKILLS |
| 15 | Scheduler: cron + hooks, owner-tagged jobs, harness default jobs | LIVE (harness/Scheduler.kt + SchedulerRuntime.kt; harness/schedule.py) | MISSING | CLUSTER scheduler |
| 16 | Echolalia verify-before-store memory | DESIGNED (ARCHITECTURE §9: parked/scoped; grep finds no implementation in Python or Kotlin) | MISSING | CLUSTER echolalia — built fresh from the scoped semantics (patent Claim 31: verify-before-store) |
| 17 | Notification intelligence (inbound 3-gate filter: allowed apps, quiet hours, rate limit) | LIVE (UltraNotificationService.kt; features/notifications.py full impl) | MISSING (desktop has outbound notify tool only) | CLUSTER notifications |
| 18 | Tor transport (opt-in, no clearnet fallback for onion) | LIVE (net/EmbeddedTor.kt, TorProxy.kt) | IN-FLIGHT | Agent on claude/desktop-tor-2026-10-20 — NOT in this program's scope |
| 19 | Products / shopping / marketplace / reviews | LIVE (research/ProductCheck.kt, SellerReputation.kt, Shopping*.kt) | IN-FLIGHT | Agent on claude/desktop-shopping-2026-10-20 — NOT in this program's scope |
| 19a | Marketplace NAVIGATION (general, URL-agnostic navigate capability) | NOT A PHONE FEATURE — owner design change 2026-10-20: desktop ONLY, phone app explicitly not updated | IN-FLIGHT | DESKTOP-ONLY ADDITION, not a parity gap. Being built inside the shopping cluster (claude/desktop-shopping-2026-10-20) as a URL-agnostic navigate capability. Recorded here so the map stays truthful: the desktop is getting this EXTRA; the phone is not behind on it |
| 20 | On-device model serving / Unitary-IRVQ quantizer / TPU paths | LIVE (local/) | N/A | Model-serving layer, not a client feature: desktop talks to served endpoints (incl. the RunPod GLM rig). No desktop build; difference is architectural, marked here |
| 21 | Subagents / fan-out | LIVE | UNVERIFIED | Not audited this pass; not in the owner's cluster list; noted as residual |

Build clusters spawned from this map (each: own branch
claude/desktop-parity-<cluster>-2026-10-20 from main, tests + one real
functional check, push, no merge):
voice, router, nli, browse, investigate, skills, scheduler, echolalia,
notifications.

## SYNERGY PASS (owner requirement 2026-10-20 — features must compose, not coexist)

The vision model is the shared eyes. Graded per item in the final report as:
proven by test / wired-only / named follow-up.

| # | Synergy | Owner requirement | Status |
|---|---------|-------------------|--------|
| S1 | SEE→ACT→VERIFY loop | look_at_screen (configured vision provider) → control_screen action → look_at_screen again verifying the action landed. REAL end-to-end check on this VM (Xvfb) required: a UI-state change the VLM reads back | TEST IN FLIGHT (synergy agent, on main's tree — both features already exist on main; code changes only if the loop wiring is missing, branch claude/desktop-parity-synergy-2026-10-20) |
| S2 | VISION × SHOPPING | product_check gains vision-read evidence where listing facts live in images (price/photo-only details), LABELED vision-read, never merged silently with page-text facts | NAMED FOLLOW-UP unless the shopping branch's seams allow clean composition — shopping is another agent's in-flight branch (claude/desktop-shopping-2026-10-20); do not force it in mid-flight. Seam to be identified concretely in the synergy deliverable |
| S3 | TRANSPORT BLINDNESS | Content fetched through ANY path (direct, search chain, Tor when enabled) must reach vision identically — the fetch path is the single funnel; no feature may keep a private fetch that bypasses it | AUDIT IN FLIGHT (synergy agent: enumerate every content fetch in desktop src, prove the safeFetch funnel or name the exception) |
| S4 | GLM brain + VLM composition | The loop routes vision results back as text the brain reasons over; ONE vision endpoint serves both look_at_image and look_at_screen | TO BE STATED with config evidence (runtime.ts vision() + config) in the synergy deliverable and final report |

## ACCEPTANCE CRITERIA — screen-control / browsing clusters (owner requirements 2026-10-20)

These are pass/fail criteria for the screen + browse work, on top of the
cluster deliverables. Proof is marked per platform honestly: Linux is
provable on this VM; macOS/Windows are design-only until run there.

### A1. HUMAN-QUALITY INPUT (control_screen — platform layer)
The tool must operate like a person behind the machine, not a teleporter:
- (a) Cursor movement along smooth, EASED paths: interpolated motion steps
  with acceleration/deceleration and slight natural variance — never an
  instant jump.
- (b) Typing at a natural cadence with variable inter-key timing — never a
  whole string in one instant burst. Paste-style insertion only where the
  platform tool already does it deliberately AND the doc says so.
- (c) Natural scroll: incremental steps, not one jump.
Implementation: platform layer (src/tools/platform.ts). Linux/X11 via the
existing xdotool path today — motion sequenced as stepped moves; design the
seam (a shared motion/timing planner producing step sequences, per-platform
executors) so macOS/Windows equivalents slot in. Builder: dedicated agent,
branch claude/desktop-parity-humaninput-2026-10-20. Proof required: the step
plan is inspectable in tests (multiple eased motion steps, jittered timing,
scroll increments), plus one live Xvfb run showing intermediate cursor
positions during a move. mac/Win: design-only, marked as such.

### A2. PRECISION VISUAL GROUNDING + VERIFY (see→act→verify loop)
- The VLM grounds targets on the screenshot: WHAT + WHERE (named target,
  grounded location).
- The action executes at the grounded location.
- The post-action screenshot is re-read to VERIFY a NAMED EXPECTED PATTERN
  (button state, text, marker, dialog) actually appeared — the verification
  step compares against the named pattern and REPORTS the comparison.
- Verification failure = the tool reports failure honestly; it may
  re-ground + retry ONCE, then stop.
Owner: synergy agent's loop test (S1) extended to this shape.

### A3. VERIFICATION SCREENS — VISUAL UNDERSTANDING (corrected 2026-10-20)
CORRECTION (owner, 2026-10-20): the earlier park-and-report mandate is
STRUCK. The "I'll click all verification" rule binds MUSE (the assistant)
in its own browsing/actions — it was never a constraint on the Jarvis
product, and it is recorded here only to keep that boundary straight:
**the verification-handling constraint applies to the assistant's own
conduct, not the product.**
The product spec that stands: A1 (human-like input) and A2 (precision
grounding + verify) — and that precision EXTENDS to understanding visual
patterns and verification screens: the app should recognize a verification
screen, read its state, and read what is being asked, as part of the
visual-grounding capability. No park-and-report mandate, no challenge-
fixture parking test in the app requirements.
