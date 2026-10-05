#!/usr/bin/env bash
# Build whisper.cpp static libs for arm64 into ~/whisper-android/{lib,include},
# the path ultra-native/app/src/main/cpp/CMakeLists.txt reads for ultra_whisper.
# Mirrors how the repo already builds llama.cpp. Run on Linux/macOS with NDK.
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to your r26+ NDK path}"
OUT="$HOME/whisper-android"; mkdir -p "$OUT/lib" "$OUT/include"
# Pinned to the commit the app was built and tested against (DEVLOG 2026-10-05). Bump it on purpose.
WHISPER_SHA="${WHISPER_SHA:-60c0be6ac8fa71b1a2ae2dd938a31a34a508e774}"
rm -rf /tmp/whisper.cpp && git init -q /tmp/whisper.cpp
cd /tmp/whisper.cpp
git fetch -q --depth 1 https://github.com/ggml-org/whisper.cpp "$WHISPER_SHA" && git checkout -q FETCH_HEAD
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF \
  -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build build-android -j
find build-android -name 'libwhisper.a'   -exec cp {} "$OUT/lib/" \;
find build-android -name 'libggml*.a'     -exec cp {} "$OUT/lib/" \;
cp include/whisper.h "$OUT/include/"
# whisper.h pulls in ggml-cpu.h -> ggml-backend.h -> ggml-alloc.h: ship all public ggml headers
cp ggml/include/*.h "$OUT/include/"
echo "whisper arm64 libs -> $OUT/lib"; ls -la "$OUT/lib"
