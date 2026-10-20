# Agent Ultra

An Android agent that drives real apps on the phone. A small language model runs
on the device itself, reads the screen through the accessibility service, and
taps. When a step is beyond it, it escalates to a cloud model. A policy gate on
the device can refuse the model before anything destructive happens.

The signed APK is a free download: **[Ultra-Agent-Release](https://github.com/dafarusd/Ultra-Agent-Release)**.

## What's in here

- `ultra-native/` — the current app. Native Kotlin, Jetpack Compose, minSdk 26,
  arm64. This is what ships.
- `ultra-native/app/src/main/cpp/` — llama.cpp through a JNI bridge, so the
  on-device model runs without a server.
- `DEVLOG.md` — what changed, why, and the bugs that mattered. Append-only.
- `CLAUDE.md`, `SKILLS_REFERENCE.md` — how this repo is worked on.
- Everything else at the root, including `app/`, `src/`, `server/` and the
  `*_PROOF.md` files, is the earlier Expo/React Native version. It is history,
  not the product.

## How it works

- **On-device brain.** Gemma 3 1B, loaded through llama.cpp. It plans the next
  action from what's on screen.
- **Cloud escalation.** When the 1B can't do the step, the request goes to a
  cloud model on your own API key. No key ships in this repo.
- **The gate.** Tool calls pass a policy gate that runs on the phone. It refuses
  destructive calls rather than asking the model to behave. It exists because of
  an incident during development: the brain sent messages nobody asked for. When a
  tap would commit something — delete, send, pay — it stops and asks, on top of
  the app you're in.
- **Steps it was shown.** A job a stronger model finished once, and a check
  outside the app confirmed, is kept as a short list of steps. The next request
  of the same kind plays those steps with your own details. Every played step
  still goes through the gate.

## Build it

The native library needs llama.cpp built for arm64 first. It isn't vendored
here. Clone llama.cpp, build it with the Android NDK's CMake toolchain for
`arm64-v8a` with `-DGGML_OPENMP=OFF`, then copy `libllama.a` and the
`libggml*.a` libraries into `~/llama-android/lib` and the headers into
`~/llama-android/include` — that's the path `ultra-native/app/src/main/cpp/CMakeLists.txt`
reads. The llama.cpp commit behind the published APKs isn't pinned yet.

```
cd ultra-native
./gradlew assembleDebug
```

A fresh clone signs debug builds with the Android SDK's own debug key. Release
signing needs a `keystore.properties` that is not in this repo, and a release
build **fails** without it rather than falling back to a debug key — 2.3.0 was
published debug-signed because the old fallback did exactly that.

Current version: **2.4.0** (versionCode 15). 447 JVM unit tests under
`ultra-native/app/src/test`.

## Measured

AndroidWorld, Google's public phone benchmark. It sets the task and checks the
phone afterwards; nothing here grades. 33 simpler tasks picked by rule, details
it had never seen: **8 done on its own, 13 with steps from one checked run** by
a stronger model, which itself scores 13 and 14. On six tasks that all failed
the day before: 1 of 6 without, 4 of 6 with — twice, on different details. No
model was retrained. Every round, with the guess written down before it:
[`ultra-native/tools/androidworld-results.md`](ultra-native/tools/androidworld-results.md).

## Honest limits

- The benchmark runs are on an emulator with a cloud model, and the memory that
  keeps the steps runs on a laptop beside it. The phone can't learn them by
  itself yet. 20 of the 33 tasks still fail. Camera tasks and calendar grids are
  two of them; picking one file out of look-alikes is another.
- The 1B echoes its few-shot examples before answering. The parser handles it;
  the logs are ugly.
- Screen reading depends on what each app exposes to the accessibility service.
  Apps that draw their own widgets are harder.
- Cloud escalation sends screen context to whichever provider you configure.
  That is your decision to make per task, and the gate is what stands between a
  cloud model and a destructive action.

## Credit

Some of the design came from people who pushed back on the first release:

- **u/clearingai** pointed out that a person approving every gate decision
  decays the way manual review queues always have — careful in month one,
  tapping on reflex by month six. Risk scoring and risk-based auto-approve
  (2.3.0) came out of that.
- **u/donk8r** argued, over several rounds, that confidence belongs on what the
  agent observes rather than on each call. That became confidence-scored
  observations and the `low_confidence_egress` gate rule.
- **u/arthaudm** asked how the operator tap holds up as the tool list grows.
  Auto-approving low-risk decisions is part of the answer.

The reasoning, and what's still unsolved, is in `DEVLOG.md`.

## License

AGPL-3.0-only. See `LICENSE` and `NOTICE`. A commercial license is available
for proprietary or closed-source use that the AGPL's copyleft doesn't permit —
see `NOTICE`. llama.cpp is MIT; the Gemma weights are downloaded at runtime under
Google's own terms.

---

Built by Dafarus — local-first software and hardware you own.

Follow the work on X: [@Dafarusd](https://x.com/Dafarusd)

My company:
- Keephaven — [keephaven.co](https://keephaven.co) · [source](https://github.com/dafarusd/keephaven) · [X](https://x.com/Keephaven) · [Facebook](https://www.facebook.com/profile.php?id=61592155452190)

More work: [gate](https://github.com/dafarusd/gate) · [Sentinel](https://github.com/dafarusd/sentinel-public) · [Agent Ultra](https://github.com/dafarusd/Ultra-Agent-Release) · [EveryVoice](https://github.com/dafarusd/everyvoice) · [Mind Meld](https://github.com/dafarusd/mindmeld) · [monero-swap](https://github.com/dafarusd/monero-swap)

Donations help keep it going:
- BTC `bc1qpyeupsjkrny259upq9jrg7d22h32ncrlknj3vw`
- ETH `0x8ec99D65C23D39772Cc2425cfd1F7a3872af8636`
- XMR `428vC4FYUs7Dm2aAAN2i2zY39z4sM5RDYBAPdyCxCS4ZUJ36KENQaP5AdjYpytvtkXZ15sB8ooAGGR1GehJjo5GUSPnHVAV`
