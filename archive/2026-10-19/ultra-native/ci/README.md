# ci/test-debug.keystore

Signs the DEBUG APKs GitHub builds (`.github/workflows/debug-apk.yml` points the
`JARVIS_TEST_KEYSTORE` variable at it; app/build.gradle.kts then signs debug builds with it). Without it every run got a fresh random debug key, so a new test
build couldn't install over the previous one, and uninstalling wiped the downloaded 10 GB model.

It is a test-only key with the standard debug passwords (`android`). It deliberately lives in
the repo: anyone who can read the repo can sign an APK that updates a DEBUG install, nothing
else. It is NOT the release key: release APKs are signed with the secret key from
RELEASE_SIGNING.md and can't be updated with this one.
