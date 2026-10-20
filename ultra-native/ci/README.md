> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/jarvis-native/ci/README.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# ci/test-debug.keystore

Signs the DEBUG APKs GitHub builds (`.github/workflows/debug-apk.yml` points the
`JARVIS_TEST_KEYSTORE` variable at it; `app/build.gradle.kts` then signs debug
builds with it). Without it every run got a fresh random debug key, so a new test
build couldn't install over the previous one, and uninstalling wiped the
downloaded model files along with the app data.

It is a test-only key with the standard debug passwords (`android`). It
deliberately lives in the repo: anyone who can read the repo can sign an APK
that updates a DEBUG install, nothing else. It is NOT the release key: release
APKs are signed with the secret key described in
`../ondevice-brain/RELEASE_SIGNING.md` and can't be updated with this one.

## Status as of 2026-10-19

This keystore and its role in the debug workflow were not separately
re-verified in the 2026-10-19 code audits (static, grep-level reading of the
merged tree); the description above is carried over from the previous version
of this file. CI has not yet compiled the merged tree, so the first green
debug build on `main` is what confirms the workflow end to end.
