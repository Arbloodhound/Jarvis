# Jarvis pod rig (`start.sh`)

One command that turns a rented GPU box (RunPod or Vast.ai) into a self-hosted Jarvis: a local brain and vision model on llama.cpp, the Jarvis desktop app, a virtual desktop it can see and drive, Tor, private web search, and a CPU forecasting server.

```bash
bash /workspace/start.sh          # RunPod: keep this file on the network volume
```

Status: **used on the owner's RunPod B300 pod on 2026-10-21** (brain and vision healthy, desktop, Tor and SearXNG up, Timestral answering). Other GPUs, Vast.ai and fresh-volume downloads were exercised with a stubbed harness, not on real hardware. See `HANDOFF_2026-10-21.md` at the repo root.

## What it starts

| Port | Service | Notes |
|---|---|---|
| 8080 | Brain: GLM-5.2-abliterated UD-Q2_K_MXFP4 (llama.cpp `llama-server`) | `NP_SLOTS` × `CTX_PER_STREAM` context, q8_0 KV cache, `--load-mode dio` |
| 8082 | Vision: Qwen3-VL-8B abliterated Q4_K_M + mmproj | `-c 8192`, one slot |
| 9050 | Tor SOCKS | Jarvis uses it in `auto` mode (only for .onion or when asked) |
| 8890 | SearXNG (loopback) | Chrome's default search on the virtual desktop |
| 6080 | noVNC → x11vnc → Xvfb `:1` (1280×800, fluxbox) | Apps menu: Jarvis app, Chrome, Tor Browser, Files, Editor, Terminal, Calculator |
| 9222 | Chrome debug port (loopback) | For Jarvis's `read_browser_page`; Chrome uses `--user-data-dir=/root/.config/jarvis-chrome` |
| 8095 | Timestral forecasting server (CPU) | Installed in the background; `tail /root/forecast.log` |

Ports 8080/8082 move to the next free port when something else holds them (e.g. a Vast portal).

## Knobs (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `NP_SLOTS` | 6 | Parallel conversations the brain serves (main chat + workers) |
| `CTX_PER_STREAM` | 65536 | Context per conversation (tokens). The script checks it fits in VRAM before loading and says the max if not |
| `WORKERS` | 4 | Jarvis subagents that may run at once |
| `LLAMA_REF` | b11785 | llama.cpp tag |
| `LOCAL_COPY` | off | `on` = also copy the model from the volume to local disk in parallel |

Restarting the brain reloads ~236 GB of weights (12–15 min at the measured 16–19 GB/min with `dio`), so change `NP_SLOTS` / `CTX_PER_STREAM` only when you mean to.

## What it keeps on the volume (`/workspace`)

`models/` (the GGUFs), `cache/` (llama.cpp build tarball per GPU arch + CUDA version, SearXNG settings, Chrome .deb, Hugging Face cache for Timestral), `patches/*.patch` (applied to every fresh Jarvis clone; already-applied ones are skipped), `jarvis-home/` (a backup of `~/.jarvis` every 5 minutes), `run/` (logs, pid files).

## Jarvis on the pod

- `jarvis chat` in a terminal, or the **Jarvis app** window on the virtual desktop.
- In chat: type while it works to interrupt; `/compress` shrinks a long chat; `/refresh` restarts Jarvis in place with the latest build and config and keeps the conversation; Ctrl+C stops a run or quits when idle.
- Features from the open PRs reach the pod as patches: put `compare/main...<branch>.diff` files in `/workspace/patches/`, rebuild (`cd /root/jarvis/desktop && npm run build`), then `/refresh`. The current set is in `HANDOFF_2026-10-21.md`.

## Hardware notes (measured on the B300, 2026-10-21)

- B300 is `sm_103`: the image's CUDA 12.8 can't build for it, so the script installs CUDA 12.9 when needed (driver must support ≥ 12.9).
- With 6 × 64K context, VRAM was 259 / 269 GiB used; 4 × 128K fits too (~4.5 GiB spare), 6 × 96K is tight.
- mmap loading stalled at the container's RAM limit; `--load-mode dio` loads at ~16–19 GB/min instead.
- The network volume can't `chmod`: builds happen under `/root`, and edits are made on `/root` and copied back.
