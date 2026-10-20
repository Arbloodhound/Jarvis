# AndroidWorld: Ultra on a public benchmark

[AndroidWorld](https://github.com/google-research/android_world) (Google Research) runs 116
hand-written tasks across 20 real Android apps on an emulator and checks the device's own state
afterwards. It grades; nothing of ours does.

## Setup on this laptop (once)

```
~/Android/cmdline-tools/latest/bin/sdkmanager --install "emulator" \
  "system-images;android-33;google_apis;x86_64" "platforms;android-33"
~/Android/cmdline-tools/latest/bin/avdmanager create avd -n AndroidWorldAvd \
  -k "system-images;android-33;google_apis;x86_64" -d pixel_6
ln -sfn ~/Android ~/Android/Sdk          # run.py only looks in ~/Android/Sdk/platform-tools
git clone https://github.com/google-research/android_world ~/androidworld
cd ~/androidworld && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/pip install -e .
```

## Each session

```
~/Android/emulator/emulator -avd AndroidWorldAvd -no-snapshot -grpc 8554 -no-audio -gpu swiftshader_indirect &
cd ~/vault/runtime/bridge && ULTRA_BRAIN=venice:llama-3.3-70b python3 brain_proxy.py &   # the brain, laptop-side
adb -s emulator-5554 reverse tcp:8799 tcp:8799
cd ~/projects/Audit-Discuss-Build/ultra-native && ./gradlew assembleDebug -Pbench
adb -s emulator-5554 install -r -g app/build/outputs/apk/debug/app-debug.apk
cd ~/androidworld && ANDROID_SERIAL=emulator-5554 .venv/bin/python bench_ultra.py \
  --agent_name=ultra --tasks=ClockStopWatchRunning,SimpleSmsSend --n_task_combinations=1
```

`bench_ultra.py` is android_world's own `run.py` with our agent added; the agent itself is
`tools/androidworld_agent.py`.

## Things that cost hours, so they are written down

- **The emulator boots in 2023**, so every HTTPS call from an app fails with "Unacceptable
  certificate". The brain proxy on the laptop is how Ultra reaches a model at all; it also makes
  the brain a laptop-side setting (`ULTRA_BRAIN=venice:MODEL | local:qwen`) so one harness can
  score four brains.
- **`-Pbench`** builds x86_64 without llama.cpp (CMake skips non-arm64; `LlmNative.available` is
  false and the cloud brain is used). The phone's arm64 release path is untouched.
- **Two accessibility services do not share this emulator.** With Ultra's on, the harness logs
  "Could not get a11y tree, retrying"; with only the forwarder it reads first try. Turning Ultra's
  off between tasks was tried: every task then failed. Ultra's stays on, the harness retries, and
  the adapter drives Ultra over adb + uiautomator, which keeps working.
- **Ultra's app policy** defaults to allow-list with nothing allowed. In the emulator write
  `/data/data/com.agent.ultra/shared_prefs/ultra_protected_apps.xml` with `allowlist_mode=false`
  (adb root; a file pushed to /sdcard/Android/data is not readable by the app).
- **The benchmark reads the live screen**, so the adapter brings the task's app back to the front
  when Ultra's chat ended up there — and only then: reordering an app that is already in front
  sends it back to its default tab (the Clock reopened on Alarm and a passing task started failing).

- **The judge can be blind.** AndroidWorld's checks that read the screen (the Clock tasks) go
  through its accessibility forwarder, and on this emulator that reader returned 0 elements while
  the right app was in front — a timer showing `00h 16m 35s` scored 0. The adapter now records what
  the judge saw with every episode (`ultra_judge_saw`) and, when the forwarder gives nothing, has
  the judge read through AndroidWorld's own second method (`A11yMethod.UIAUTOMATOR`). Read
  `ultra_judge_saw` before believing a fail. Screen-reading scores from before 2026-09-20 03:10
  can't be trusted as fails.
- **The forwarder crashes on start now and then** (a null context in its own constructor thread).
  Android's "keeps stopping" dialog then covers Ultra's chat: a task is lost as "input not found",
  or "App info" gets tapped and the run sits on a Settings page for 600 s. The adapter sets
  `hide_error_dialogs` and taps "Close app" if it still sees one.
- **Don't `am force-stop` Ultra before a run** — its accessibility link dies and the next task does
  nothing for the full 600 s. Wipe learned state with `sqlite3` on the live app instead.
- **Run the harness with `python -u`.** Redirected to a file, its `print` lines sit in a buffer and
  a working run looks hung.
- **Your phone may be plugged in too.** Two devices means bare `adb` fails; everything here uses
  `-s emulator-5554` / `ANDROID_SERIAL=emulator-5554`.

## The verdict goes back to Ultra

After AndroidWorld checks the device, the adapter writes `verdict.json` into Ultra's files dir.
Ultra reads it at the start of its next request: a fail on a run it had counted as a success takes
back the stored "this is how" shortcut, moves the good mark on every served lesson to a bad one,
and leaves one lesson naming the approach that didn't do the job. `ULTRA_NO_VERDICT=1` switches it
off (the control arm). The score is computed before the verdict is sent; nothing here can change it.

**The action gate in a benchmark.** Ultra stops before pressing anything that commits ("Delete",
"Send") and asks its person, on an overlay drawn over the app. In the benchmark the person is the
task, so the adapter answers: "Do it" only when the button IS the verb the goal uses ("Delete" for
"delete the note" — not "Delete lines"), "Don't" otherwise. It presses the overlay's button at the
position Ultra logs (`OVERLAY shown: do_it=x,y`), and every answer is kept in the episode
(`ultra_gate_answers`). Ultra's gate is not changed by any of this.

Passing runs become routes on the laptop: `northstar bench routes [--push]`.

## Results

| When | Set | Score |
|---|---|---|
| 2026-09-19, first scored run | 12 tasks, 9 apps | **3/12 (25%)** |
| 2026-09-19, with Northstar lessons | 6 tasks it fails | 0/6 with, 0/6 without |

| 2026-09-20, routes played by the engine, seed 31 (P9) | the same 6 tasks, llama-3.3-70b | **1/6 without routes, 4/6 with** |
| 2026-09-20, replication on seed 33 (P10) | the same 6 tasks, llama-3.3-70b | **1/6 without routes, 4/6 with** |
| 2026-09-20, 33 tasks chosen by rule, seed 31 (P11) | complexity ≤ 1.2, not question-answering, no "…Verify" twins | **8/33 without routes, 13/33 with** (teacher: 13/33 and 14/33) |

Scores from 09-19 on the Clock tasks were judged by a blind judge (see above) and are not evidence.
The routes come from claude-sonnet-5 practising on seeds 30 and 32 through Ultra's hands
(3–4 of 6); the student never sees those seeds. Every round, with the prediction written before
it: `androidworld-results.md`.

For scale, the AndroidWorld paper's GPT-4 agent (M3A) reports ~30% on the full 116.

**Do lessons help?** Not on these tasks. The same mechanism that takes a model from 0/29 to 27/29
on knowledge questions (`northstar transfer`) moved nothing here: 0/6 both arms, with lessons
served on 2 of the 6 runs. These failures are skill — multi-step navigation inside an app — not
missing facts, and a sentence cannot supply skill. The open idea is route-shaped lessons: replay
the exact taps that finished a task as the navigator's plan.

What the benchmark found in Ultra on day one, all of it live on the phone too: an empty text box
was invisible; open menus read as empty; unlabelled buttons (every "+") were dropped; the navigator
drove Ultra's own chat when an app name didn't resolve; a plan that only opened a tab counted as
done; and a run that only launched an app was stored as the way to do the job.
