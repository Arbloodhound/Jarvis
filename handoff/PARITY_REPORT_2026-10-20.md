# PARITY REPORT — desktop feature parity with the phone app — 2026-10-20

Owner directive: the desktop client must have ALL features the phone app
has — include everything now, test before running. Contract:
[PARITY_MAP_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_MAP_2026-10-20.md).
Base for every branch: main @ fc22170776e7b50ab4002b1568ed14bc93c5d0ec
(baseline: typecheck clean, 73/73 tests). **Nothing is merged** — merging
is the owner's call, as with every prior round. Draft PRs were attempted
on every branch and uniformly returned HTTP 403 (token cannot create
PRs); branches are tree-verified against their remotes and are the
deliverables, ready to PR from the web UI.

Test-count note: each branch's total is its own suite against the shared
73 baseline — parallel branches, **not additive**.

## Per-feature results

| # | Feature | Phone | Desktop before | Desktop after this program | Branch · tests · live check |
|---|---------|-------|----------------|----------------------------|------------------------------|
| 1 | Agent loop + registry | LIVE | HAVE | HAVE | — |
| 2 | Gate / approvals / audit | LIVE | HAVE | HAVE | — |
| 3 | Memory claims semantics (kind/salience/decay/supersede) | LIVE | PARTIAL | **PARTIAL — residual** (see below) | Echolalia added verify-before-store on the memory path; the full claims-semantics upgrade was not a spawned cluster |
| 4 | Embeddings / semantic recall | LIVE | HAVE | HAVE | — |
| 5 | Providers (local + cloud) | LIVE | HAVE | HAVE | — |
| 6 | Vision | LIVE | HAVE | HAVE (+ synergy composition, S4) | — |
| 7 | Screen control + guard | LIVE | HAVE | HAVE **+ human-quality input (A1)** | humaninput · 93/93 · live PASS |
| 9 | Voice (STT/TTS) | LIVE | MISSING | **BUILT** | voice · 90/90 · live PASS (real whisper) |
| 10 | Router (tiers + Claim 10 matrix) | LIVE/REF | PARTIAL | **BUILT** | router · 87/87 · trace PASS |
| 11 | NLI gate | REF | MISSING | **BUILT** | nli · 94/94 · 9/9 vs Python original |
| 12 | Autonomous browsing | LIVE | PARTIAL | **BUILT** | browse · 83/83 · live PASS |
| 13 | Investigate | LIVE | MISSING | **BUILT** | investigate · 87/87 · live PASS (Shopify) |
| 14 | Skills | REF | MISSING (no code at all — corrected) | **BUILT** | skills · 91/91 · live PASS (real model) |
| 15 | Scheduler (cron + hooks, owners, defaults) | LIVE | MISSING | **BUILT** | scheduler · 19 new tests · live PASS |
| 16 | Echolalia verify-before-store | DESIGNED only | MISSING | **BUILT** (fresh, desktop-first) | echolalia · 85/85 · live PASS 3/3 |
| 17 | Notification intelligence (inbound) | LIVE | MISSING | **BUILT** | notifications · 89/89 · live PASS |
| 18 | Tor transport | LIVE | IN-FLIGHT (other agent) | IN-FLIGHT — untouched by this program | claude/desktop-tor-2026-10-20 |
| 19 | Shopping / marketplace / reviews | LIVE | IN-FLIGHT (other agent) | IN-FLIGHT — untouched | claude/desktop-shopping-2026-10-20 |
| 19a | Marketplace navigation (DESKTOP-ONLY addition) | not a phone feature | — | IN-FLIGHT inside the shopping branch | (owner design change; not a parity gap) |
| 20 | On-device serving / quantizer / TPU | LIVE | N/A | N/A — architectural, desktop uses served endpoints | — |
| 21 | Subagents / fan-out | LIVE | UNVERIFIED | UNVERIFIED — residual, not audited this pass | — |

## Cluster detail

- **Router** (`claude/desktop-parity-router-2026-10-20`, tip a3fbc77e) —
  `routeRequest()` ported faithfully from features/router.py (tiers) +
  Claim 10 heuristic/literal mode; tier→effort wired to both providers;
  route emitted in run events, transcript, and Runtime state; fast
  commands still short-circuit (zero provider calls, labelled fast).
  Limits stated by the builder: cloud effort proven to the provider
  boundary only (no key spent); mode is a recorded label, not a provider
  knob (none exists). [PARITY_ROUTER_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_ROUTER_2026-10-20.md)
- **NLI gate** (`claude/desktop-parity-nli-2026-10-20`, tip ba431f8a) —
  deterministic checker 1:1 from features/nli_gate.py; **cross-checked
  against the Python original on 9 pairs: 9/9 verdicts and reason strings
  identical**. Loop wiring gates numeric claims against the run's tool
  results; one re-check turn; persistent failure is delivered with a
  visible disagreement block — never silently passed or dropped. Optional
  model layer is one-directional (can never override a deterministic
  FAIL). [PARITY_NLI_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_NLI_2026-10-20.md)
- **Notifications** (`claude/desktop-parity-notifications-2026-10-20`,
  tip e5f047e0) — 3-gate filter 1:1 (own app always excluded; quiet-hour
  wrap-around exact; 5/min fixed window); drops audited (deliberate
  deviation from phone's silent drop, per brief). Both intakes through
  one choke point: authenticated POST /api/notifications (no token →
  401) and the inbox file drop. Live: all five gate scenarios correct
  against a real server. Limit: pending queue is in-memory (drops are
  durable; undelivered passes don't survive restart). [PARITY_NOTIFICATIONS_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_NOTIFICATIONS_2026-10-20.md)
- **Browse** (`claude/desktop-parity-browse-2026-10-20`, tip 389274f5) —
  `browse_task` with the full-page contract: fetched full text only,
  snippets never quoted as content, FAILED pages explicit, URL + SHA-256
  provenance per page, stale-page flagging on hash change. Live PASS:
  real multi-step run (search → nodejs.org), 8 steps, 8 pages, 0 failed;
  failure paths (fetch error, 404, SSRF refusal, onion refusal with zero
  fetcher calls) unit-proven. [PARITY_BROWSE_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_BROWSE_2026-10-20.md)
- **Echolalia** (`claude/desktop-parity-echolalia-2026-10-20`, tip
  de051c85) — pending store + deterministic source check + echo/owner
  promotion; quarantine is owner-resurrectable only, with the override
  recorded. Behind `memory.verifyBeforeStore` (default off). Live PASS
  3/3 through the shipped runtime (promote-on-echo, quarantine-on-
  contradiction, stays-pending without echo). [PARITY_ECHOLALIA_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_ECHOLALIA_2026-10-20.md)
- **Investigate** (`claude/desktop-parity-investigate-2026-10-20`, tip
  59fe5456) — case files, graded evidence (source URL mandatory),
  provenance-bound contacts, structurally send-proof drafts
  (`sent: false` literal). **All four legs auto-gather**; live testing
  caught and fixed a dedupe bug starving legs 2–4 (regression-tested).
  Live PASS: Shopify case — 24 evidence, 26 contacts, draft verified
  unapproved + unsent, 311-line report. The phone's Tor leg was not
  ported (desktop Tor is its own cluster). [PARITY_INVESTIGATE_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_INVESTIGATE_2026-10-20.md)
- **Voice** (`claude/desktop-parity-voice-2026-10-20`, tip 324e2c9e) —
  `transcribe_audio` (OpenAI-compatible STT client; no fake transcriber)
  + `speak` enforcing the finished-utterance rule before any backend
  call; OS TTS with honest failure. Live PASS with **real** faster-
  whisper: known sentence transcribed **verbatim on all three runs**
  (the thin local HTTP shim is a labelled stand-in *server*; the engine
  is the real model). **Ambient always-on listening NOT ported** —
  phone-hardware feature, marked, not faked. mac/Win TTS runtime-
  unproven. [PARITY_VOICE_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_VOICE_2026-10-20.md)
- **Scheduler** (`claude/desktop-parity-scheduler-2026-10-20`, tree-
  verified, 49 files) — owner-required jobs (ownerless rejected at API
  and loader), in-repo 5-field cron matcher + interval shorthand,
  once/cron/hook kinds (**real fs.watch hook firing** in tests), missed-
  job grace (fire-once inside grace, skip+log beyond), gate fully active
  (confirmation-needed firings are declined-and-logged, matching the
  app's piped-stdin rule). **Default harness jobs ARE seeded**
  (6 jobs, idempotent, no resurrection) — the phone's never-seeded bug
  not repeated. Live check: harness printed **LIVE CHECK PASS**.
  19 new tests in test/scheduler.test.ts. (Builder was interrupted once
  mid-run and resumed; its deliverable was read directly for this
  report.) [PARITY_SCHEDULER_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_SCHEDULER_2026-10-20.md)
- **Skills** (`claude/desktop-parity-skills-2026-10-20`, tip 7f0160c3) —
  fresh faithful TS port of harness/skills.py (the desktop tree had NO
  skills code — premise corrected in the map). Live PASS through the
  real app path with a real model (qwen3:1.7b): the model found the
  skill in the metadata directory, called `skill_read`, and followed a
  procedure whose marker existed **only in the body** — proof the lazy
  load, not the prompt, carried it. [PARITY_SKILLS_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_SKILLS_2026-10-20.md)
- **Human input** (`claude/desktop-parity-humaninput-2026-10-20`, tip
  a24ef02a) — see Acceptance A1. [PARITY_HUMANINPUT_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_HUMANINPUT_2026-10-20.md)
- **Synergy** (`claude/desktop-parity-synergy-2026-10-20`, tip
  830c8920, 83/83) — see Synergy section. [PARITY_SYNERGY_2026-10-20.md](sandbox://workspace/jarvis-rebuild/PARITY_SYNERGY_2026-10-20.md)

## Synergy (graded honestly)

- **S1 + A2 SEE→ACT→VERIFY — PROVEN BY TEST.** Main had only separate
  primitives, so the synergy branch built `see_act_verify` (ground named
  target → act at grounded point → verify vs named expected pattern →
  comparison printed → retry-once). Live on Xvfb through the real tools
  with qwen3-vl:2b: read #1 confirmed the marker absent; the tool's own
  verify printed "Expected: ZEBRAQUANTUM7398 | Verify read: …MARKER:
  ZEBRAQUANTUM7398… YES | Match: YES — VERIFIED on attempt 1"; an
  independent second read confirmed it. Retry/failure/not-grounded
  paths unit-proven (not-grounded ⇒ zero actions).
- **S1b verification-screen understanding (the A3 replacement) — PROVEN
  BY TEST.** Against a locally-made "Verify you are human" fixture, the
  grounding read correctly recognized the challenge, its state
  (unchecked), and what it asks — with rough coordinates (a 2B-VLM
  quality limit, recorded).
- **S2 VISION × SHOPPING — FOLLOW-UP, seam named.** Assembly point:
  `parseListingPage` (shopping branch `shopping.ts:400`) and its
  `claims[]`; `runProductCheck` already receives `ToolContext.vision`,
  so `ctx.vision.describe()` is reachable with no new plumbing. Two real
  gaps: listing image URLs aren't extracted yet (use JSON-LD `image` /
  og:image via the existing `metaContent`), and the funnel's body is a
  utf8 string — vision needs a bytes sibling of `safeFetch` reusing
  `getOnce`/`publicOnlyLookup`. Labeling design: new claim kind + a
  "Vision-read (from the listing's images — NOT page text)" section,
  mirroring the table's existing page-vs-memory segregation. Shopping
  tree untouched, per instructions.
- **S3 TRANSPORT BLINDNESS — PROVEN (audit).** In main, web_fetch/
  web_search are the only content fetches, both via safeFetch; the only
  other outbound calls are the four model-endpoint clients, and
  vision.ts never fetches content URLs (callers supply bytes). **No
  private content fetches in main.** Tor tree extends the same funnel
  (fail-closed); shopping tree rides safeFetch everywhere. **Merge
  caveat:** at merge time, hand the shopping cores `ctx.webFetch ??
  safeFetch` or they'll stay on direct fetch while the web tools use
  Tor. Awareness item: `open_url` hands URLs to the owner's browser,
  outside the transport story by design.
- **S4 GLM + VLM composition — WIRED-ONLY.** One `Runtime.vision()`
  serves both look tools (defaults: local qwen3-vl @ 127.0.0.1:11434;
  cloud fallback claude-opus-5-5, which also makes both tools
  alwaysConfirm); vision results return to the loop as plain text — the
  brain never sees image bytes. Exercised live in S1; the GLM itself
  was not run (pod parked by the owner) → wired-only, honestly.
- **Environment findings (for the pod kit):** this Ollama build drops
  OpenAI-compat generations at ~5m01s (HTTP 500) and thinking VLMs burn
  token caps on hidden reasoning (measured 700 tokens / 79 s / zero
  visible text) — token-cap VLM reads on the pod; the synergy branch
  caps at 1900/1400 for this reason. The VM also needed `scrot`
  installed for the platform.ts screenshot fallback chain.

## Acceptance criteria

- **A1 human-quality input — PROVEN on Linux, design-only mac/Win.**
  Seeded planner (eased paths, jittered cadence, incremental scroll,
  duration caps) + stepped xdotool executors; control_screen gained
  move + scroll. Live: 19 intermediate cursor positions mid-move,
  slow-fast-slow; typing whole; scroll in successive frames. mac/Win
  typing replays plans (unproven); mac/Win move/scroll throw honest
  "not wired up" errors.
- **A2 precision grounding + verify — PROVEN** (see S1).
- **A3 — STRUCK by owner correction, recorded.** The park-and-report
  mandate was removed from all requirements; both affected builders
  verified nothing of it was ever implemented (browse) or remained at
  design stage only (synergy). The map records the boundary: the
  "I'll click all verification" rule binds the assistant's own conduct,
  not the product. S1b (understanding verification screens) stands in
  its place and is proven.

## Residuals & merge notes

1. **Memory claims semantics (#3) still PARTIAL** — kind/salience/
   decaying confidence/supersede chains from harness/memory.py were
   not ported as a standalone cluster; desktop memory + Echolalia's
   verify-before-store cover storage and trust, not the full claims
   model. Named here rather than silently closed.
2. **Subagents (#21) UNVERIFIED** — desktop fan-out was not audited
   this pass.
3. **Merge order/conflicts:** branches are parallel from fc22170776
   and several touch the same files (loop.ts: router, nli, echolalia,
   skills, scheduler; config.ts and tools/index.ts: most clusters;
   platform.ts: voice, humaninput; runtime.ts: several). Expect real
   merge work per branch, in the owner's merge order. The synergy
   branch also noted remote main has since moved past fc22170776.
4. Every branch attempted a draft PR and hit the token 403 — PRs must
   be opened from the web UI, or merged via the API pattern used in
   prior rounds, on the owner's word.
