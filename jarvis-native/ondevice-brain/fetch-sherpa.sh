#!/usr/bin/env bash
# Fetch sherpa-onnx's prebuilt arm64 JNI library (Jarvis's own text-to-speech voice) into
# jarvis-native/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so (gitignored).
#
# The "static-link-onnxruntime" build: ONNX Runtime is linked inside libsherpa-onnx-jni.so with
# its symbols hidden, so the APK still ships exactly one libonnxruntime.so (the 1.30 one from the
# onnxruntime-android AAR the wake gate uses). The plain build would add a second, older one.
#
# Pinned by version and sha256; bump both on purpose. sherpa-onnx is Apache-2.0; the library
# contains eSpeak NG (GPL-3.0), compatible with this repo's AGPL-3.0.
# SHERPA_CACHE overrides where the archive is kept (default $TMPDIR or /tmp). ANDROID_NDK, if set,
# supplies llvm-readelf for the 16 KB check; otherwise readelf on PATH is used.
set -euo pipefail
SHERPA_VERSION="1.13.8"
SHERPA_SHA256="7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f"
NAME="sherpa-onnx-v$SHERPA_VERSION-android-static-link-onnxruntime.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/$NAME"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
CACHE="${SHERPA_CACHE:-${TMPDIR:-/tmp}}"
ARCHIVE="$CACHE/$NAME"

mkdir -p "$CACHE" "$OUT"
sha_ok() { echo "$SHERPA_SHA256  $ARCHIVE" | sha256sum -c --status; }
if [ ! -f "$ARCHIVE" ] || ! sha_ok; then
  curl -fL --retry 4 --retry-delay 2 -o "$ARCHIVE.part" "$URL"
  mv "$ARCHIVE.part" "$ARCHIVE"
fi
sha_ok || { echo "sha256 mismatch for $ARCHIVE (want $SHERPA_SHA256)"; rm -f "$ARCHIVE"; exit 1; }

# Only the arm64 library: the archive also carries other ABIs (and an x86 libonnxruntime.so).
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
tar -xjf "$ARCHIVE" -C "$TMP" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
SO="$TMP/jniLibs/arm64-v8a/libsherpa-onnx-jni.so"

# Android 15+ on 16 KB-page phones refuses libraries whose LOAD segments are 4 KB-aligned.
if [ -n "${ANDROID_NDK:-}" ]; then READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
else READELF="readelf"; fi
al=$("$READELF" -lW "$SO" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
[ "$al" = "0x4000 " ] || { echo "libsherpa-onnx-jni.so is not 16 KB-aligned (LOAD align: $al)"; exit 1; }

cp "$SO" "$OUT/libsherpa-onnx-jni.so"
echo "sherpa-onnx $SHERPA_VERSION arm64 (LOAD align $al) -> $OUT"; ls -la "$OUT/libsherpa-onnx-jni.so"
