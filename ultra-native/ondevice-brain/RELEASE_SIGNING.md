> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/jarvis-native/ondevice-brain/RELEASE_SIGNING.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# Release signing — the cheap way (GitHub builds it for you)

You don't need a release build to TEST. The debug APK installs on any phone. A release build is
for the copy you hand over, and for updates later.

> **Status as of 2026-10-19:** this procedure is preserved from before the full-branch consolidation (all work now on `main`). It was not re-verified in the 2026-10-19 audits — the Status section at the end still governs: the workflow had not run on GitHub yet, and CI has not yet compiled the merged tree, so the first GitHub release run on `main` remains the proof.

## What the key is, and the one rule

A release APK is signed with your private key (a `.jks` file + passwords). Android only installs an
update if it's signed with the SAME key as the copy already on the phone.

- **Lose the key: you can never update that install again.** The person has to uninstall, which
  deletes the app's data. Back up the `.jks` file AND its passwords in two places (a password
  manager plus a USB stick, for example).
- **Never commit it to git.** `keystore.properties` and `*.jks` are already gitignored.
- **Debug and release don't mix.** A phone that has the debug APK has to uninstall it before the
  release APK will install (the keys differ).
- **Already have the old key?** If you hold the key that signed Ultra 2.4.0 (`0d04510d…ca241f`),
  use it so old installs can update. If you don't, make a new one; phones with the old install
  then uninstall once.

## 1. Make the key (once, about 2 minutes, any computer)

You need Java's `keytool`. It comes with any JDK: install "Temurin 17" from adoptium.net if you
don't have one.

```
keytool -genkeypair -v -keystore jarvis-release.jks -alias jarvis -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and a name. The name can be anything. Write down the password; the alias is
`jarvis`. Back up `jarvis-release.jks` now.

## 2. Turn the key into text for GitHub

- Mac / Linux: `base64 -i jarvis-release.jks | tr -d '\n' > key.txt`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis-release.jks")) | Set-Content key.txt`

## 3. Add 4 secrets to the GitHub repo (once)

GitHub → the Jarvis repo → Settings → Secrets and variables → Actions → **New repository secret**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | everything in `key.txt` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `jarvis` |
| `KEY_PASSWORD` | the key password (the same one, unless you set two) |

Then delete `key.txt`. GitHub stores the secrets encrypted, and the build never prints them.

## 4. Build it

- After PR #1 is merged: GitHub → **Actions** → **Release APK** → **Run workflow**.
- Before that: push a version tag on the branch, e.g. `git tag v2.5.0 && git push origin v2.5.0`
  (or ask Claude Code to do it).

The run builds the native libraries, runs the unit tests, builds and signs the APK, and refuses to
finish if the signature or 16 KB alignment is wrong. It takes roughly half an hour (estimate; not
yet timed on GitHub).

## 5. Download

Open the finished run → **Artifacts** → `app-release` (a zip) → unzip → `app-release.apk`.

## Cost

Public repo: free. Private repo: GitHub's free plan includes 2,000 build minutes a month, so a
handful of releases costs nothing.

## Status

The workflow file is written and its YAML parses. Every step was run by hand on Linux with a
throwaway key: the release build, R8 keeping every JNI name, the signature and the 16 KB
alignment. It has NOT run on GitHub yet; the first run is the proof.

*2026-10-19 note: still not re-verified. The 2026-10-19 audits were static reading of the merged tree and CI has not compiled it; nothing above about the workflow's behaviour on GitHub changes until a release run completes on `main`.*
