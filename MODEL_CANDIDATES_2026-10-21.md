# Model candidates and the prediction layer — notes, 2026-10-21

Status of everything here: **UNPROVEN — ideas from a model survey, nothing built or run.** Model facts come from each model's Hugging Face page as of 2026-10-21. The rig these notes assume is the self-hosted pod: GLM-5.2 brain (llama.cpp, 8080), Qwen3-VL-8B vision (8082), desktop Jarvis on top.

## 1. Timestral — a prediction layer fed by memory (top idea)

`mistralai/Timestral-209M`: a time-series forecaster (also 5M / 28M / 123M). Reads up to 32,768 past points, predicts up to 1,024 ahead, outputs a range (21 quantiles: "between X and Y", not one guess), handles several series at once plus covariates. PyTorch, runs on CPU, so it costs no GPU memory. License "other" — check before use.

**Why memory is the best source.** Jarvis's memory already holds timestamped facts: desktop memory facts, and on the phone the `listen/` notes, Librarian and MemoryClaims (claims carry provenance and time). Many of those facts are numbers that recur: hours slept, money spent, weight, how often a contact calls, how long a chore takes, a price seen, a bill amount. Pull those into series (one series per kind of fact), forecast them, and memory stops being only "what happened" and becomes "what's likely next".

| From memory | Forecast | What Jarvis does |
|---|---|---|
| Spending noted over weeks | Spend by month end, with a range | "At this rate you'll be ~$X over budget" |
| Bills / subscriptions noted | Next amount and date | Reminds before it lands; flags a bill far outside its range |
| Sleep, mood, exercise notes | Next days' trend | A gentle heads-up when a trend turns |
| Contacts' message / call rhythm | Expected next contact | "You usually hear from X by now" |
| How long tasks took before | ETA for the same task | Real time estimates in answers |

**Same layer, outside memory:** context-window fill rate (compress before the edge), download / model-load ETAs, VRAM / disk / credit burn on the pod ("credits run out ~21:30"), tool and site reliability, price history of watched items, and anomaly alerts (a value far outside its predicted range).

**Shape:** a small CPU service (`POST /forecast {series} → quantiles`), a recorder that turns numeric memory facts and runtime numbers into series, a `forecast` tool, and a few standing checks feeding the existing Proactivity module.

**Rules it must keep:** it informs, never acts — a forecast never buys, sends or deletes (purchases still need fresh approval). It extends patterns and can't explain causes; it needs history (hours to days) before it's useful; a forecast is shown as a range with its basis, never as fact.

## 2. Other candidates

| Model | What it is | Role for Jarvis | Notes |
|---|---|---|---|
| `nvidia/NVIDIA-Nemotron-3.5-Bolt-74B-A9B-24GB` | Agent-tuned VLM, 74B (9B active), native 2-bit, ~24 GB; text, image **and video** in; 256K context | **Scroll-and-read**: record the screen while scrolling, send the clip once (256 frames @ 2 fps). Also a budget brain. | vLLM only (not llama.cpp). Does not fit beside GLM on one B300. Not uncensored. WebArena-vision 50.9, BrowseComp 66.8 (model card). |
| `DavidAU/Qwen3.8-27B-…-Heretic-Uncensored-…-MTP-GGUF` | Uncensored Qwen3.8-27B with vision (`mmproj`) | **Vision swap** for 8082 | IQ4_XS ~15–17 GB + mmproj ~0.9 GB; fits only if the current vision model is removed (~18 GB free). |
| `nif0/Qwen3-VL-32B-Instruct-ultra-uncensored-heretic-H3-GGUF` | Uncensored Qwen3-VL-32B | Vision swap (alternative) | IQ4_XS ~13.4 GB, Q4_K_M ~14.9 GB + mmproj ~0.8 GB. |
| `vllm-sr/d3` | 27B multimodal *decision* model: choice / yes-no / score with probabilities, no text generation; ~54 ms text, ~290 ms with an image | Fast first check: routing, scam screening, "did the screen change?" | transformers only. Not a chat model. |
| `tencent/Hy-Memory-30B-A3B` | Memory-writer model (3B active) deciding what to keep and how to update a memory bank | Long-term memory write path | Beats much larger models on the authors' memory benchmarks (model card). |
| `facebook/sam3` | Image / video segmentation, 0.86B (gated) | Precise click targets for screen control | Add-on. |
| `MiniMaxAI/MiniMax-M3` | MiniMax's agent / vision-language model | Possible brain | Not to be confused with **MiniMax-H3**, which only generates video+audio and can't answer or act. |
| `prism-ml/Ternary-Bonsai-2-9B-gguf` | Qwen3.5-9B at 2 bits, with vision | On-device / phone app | Not Gemma. |

Not pursued: `MiniMaxAI/MiniMax-H3` (video generation only).

## 3. Scroll-and-read without a new model

Before Nemotron: (1) read Chrome pages' full text directly over its debug connection — no scrolling; (2) a `scroll_read` tool that scrolls and screenshots to the end, de-duplicates the overlap and sends the frames to the current vision model in one batch. Either turns a look→scroll→look loop (one brain turn per screen) into one step.

## Side note

The pod is not using Gemma: desktop memory's `embeddings` setting (default model name `embeddinggemma-2`) is off by default and `start.sh` never enables it; recall is word-overlap.
