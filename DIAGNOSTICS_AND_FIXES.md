# Diagnostics and bug fixes — 2026-10-19

## Done in this batch
| # | What | Status |
|---|---|---|
| 1 | **Setup checklist** at the top of the chat: every permission/model still missing, one button each. Hides when all are done. | Built, tests pass. Not yet tried on the phone. |
| 2 | **Speed boost can load now.** It needed 3 GB free beyond the model, which your phone never had (log: 3659 MB free). It now needs 2 GB. Speed boost is still **off** by default, as before. | Built, tests pass. Not yet tried on the phone. |
| 3 | **Chains keep going after Confirm.** "Call mom and then text Sam" used to stop at the call's Confirm card. Now tapping OK makes the call and then goes on to the text. | Built, tests pass. Not yet tried on the phone. |
| 4 | **Thinking stays with the answer.** It used to vanish when the answer came. Now "Thought ▸" sits above each answer; tap it to read all of it. It lasts until the app closes (it isn't saved with the chat). | Built, tests pass. Not yet tried on the phone. |
| 5 | **20 real tasks.** The app sends all 20 down the right path (20/20, `RealTasksTest`). The phone checklist is `ultra-native/tools/REAL_TASKS.md`. | Routing is proven by tests. Phone pass rate is waiting on your run. |

## Bugs the 20-task check found (fixed)
- **"Make a note: buy milk" went to chat** instead of saving a note. Only the words "note this" were recognised. Fixed.
- **"Open settings and turn on dark mode" opened Settings and stopped.** The tool step ran first and has no dark-mode tool, so the screen driver never got the job. Requests that start by opening an app ("open X and …", "go on Google and search …") now go straight to the screen driver. Fixed.

## Known, not fixed yet
- **Speed (1–3 words a second).** The cause is not proven yet. I need a capture from the newest build (steps below).
- "In YouTube, search for cat videos" (app name first, no "open") isn't recognised as a screen job. The tool step handles it instead, which may open a web search rather than the app. Saying "open YouTube and search…" works.
- A Confirm-card chain only continues when the action model is the thinking model, which is the default setup.

## What I need from your phone for the speed fix
1. Install the new build.
2. Close other apps. Make sure the phone isn't hot or charging.
3. Tap **DIAG**. It records for 5 minutes.
4. In the chat, type these one at a time, waiting for each answer:
   - `turn on the flashlight` (should be instant, with no model run)
   - `what's the capital of France`
   - `write me a short poem about the sea`
5. When DIAG finishes, send me the file.
6. Optional but useful: Settings → turn **Speed boost** on, then repeat steps 3–5. Send both files so I can compare.

From those two files I can see load time, time to the first word, words per second, which cores ran it, and whether the speed boost loaded. Then I can fix the actual cause.

## What the v3.0.73 logs showed (2026-10-19 captures)
| Measured | With background listening running (16:24) | With it quiet (00:56) |
|---|---|---|
| Brain speed | 1.5–1.8 words/sec, 3 cores (phone WARM) | 5.6–6.0 words/sec, 4 cores |
| Speed boost | loaded, 70–78% of guesses kept | loaded, 61–88% kept |
| Background transcriber | 10–32 s to transcribe 1–4 s of speech; 14 clips dropped | not running |

- **Likely main cause of slow answers: background listening running at the same time.** Its transcriber (small.en, on the 2 small cores) runs almost nonstop and competes for memory bandwidth and heat. This is likely, not proven: the phone was also WARM in the slow capture.
- Speed boost now loads (the 2 GB change worked) and helps: up to 88% of its guesses were kept.
- The first answer after a while took 93 s with nothing written. The model had been pushed out of memory and was read back from storage (600,911 page reads).
- The action model's first tool call took 61 s: a cold load plus reading a 1,350-word prompt on 2 cores. Its second call took 11.5 s.
- Fixed: "go on google for me and look up the time" did a web search and then a pointless second web lookup. A chain now stops instead of looking the same thing up twice.

**Waiting on you:** (1) background listening while answering: pause it, use a smaller transcriber, both, or leave it; (2) keep the action model loaded and give it 4 cores (about 1 GB of RAM).
