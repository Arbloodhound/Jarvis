# DEVLOG

Append-only session log: what was done, why, what's still open, what to pick up next. Newest at the bottom. (Created 2026-10-21: `CLAUDE.md` refers to this file but it was not on `main`; earlier history is in the `HANDOFF*.md`, `MERGE_LOG_2026-10-19.md` and `archive/` docs.)

## 2026-10-20 / 2026-10-21 — self-hosted pod rig and desktop features

**Scope:** `desktop/` (subsystem G) and the pod kit (`tools/runpod/`, build/release I). No Kotlin, harness or `src/` changes.

**Goal:** a working self-hosted Jarvis on a rented GPU (RunPod B300): GLM-5.2 brain + Qwen3-VL vision on llama.cpp, Jarvis desktop with a virtual desktop it can drive, Tor, private search; then the features the owner asked for while using it.

**Done**
- `tools/runpod/start.sh`: one-command rig (preflight VRAM fit, CUDA 12.9 for `sm_103`, llama.cpp build cache, `--load-mode dio`, port auto-pick, RunPod/Vast detection, SearXNG, Tor, noVNC desktop with Chrome/Tor Browser/Jarvis app, Chrome debug port, Timestral server, `~/.jarvis` backup to the volume). MEASURED working on the B300.
- PRs (all draft, desktop only): #25 always-allow, #26 Tor auto mode, #27 interrupt, #28 context compression, #30 read whole Chrome pages + scroll_read, #31 Timestral prediction layer + `/refresh`, #29 model-candidates notes. Local suite: 433/433 with the pod's patch set on `main`.
- Timestral server MEASURED on the pod (real model, CPU).

**Why these, in order:** confirm cards blocked unattended use (#25); Tor everywhere was slow and unwanted (#26); runs couldn't be redirected (#27); a long chat hit the brain's 64K window ("66,508 tokens exceeds 65,536") and the owner would not reload the model to raise it (#28); page reading took one model turn per screen (#30); forecasting from tracked numbers / memory, and restarting chat without losing it (#31).

**Still open**
- macOS/Windows `npm test` red in CI on every PR (re-runs spent; logs not downloadable from the session; pre-existing on other branches). Need the failing test names.
- Not yet seen live: compression on a real long chat, `read_browser_page` / `scroll_read` on real pages, Jarvis calling `forecast`.
- Desktop docs still say "Tor is not part of desktop" though `main` has an opt-in Tor transport.

**Next:** see `HANDOFF_2026-10-21.md` §6.

**Status:** desktop features SOURCE-FIXED and unit-PROVEN locally, RUNTIME-UNPROVEN on the pod except the pieces marked MEASURED; CI has not passed on macOS/Windows.
