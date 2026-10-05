#!/usr/bin/env bash
# Builds llama.cpp static libs for arm64 -> ~/llama-android/{lib,include}
# (what app/src/main/cpp/CMakeLists.txt links as ultra_llm).
# Verified Oct 2026: NDK r27c, llama.cpp HEAD; the full APK links against this output.
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to your NDK path, e.g. ~/Android/Sdk/ndk/27.0.12077973}"
OUT="$HOME/llama-android"; SRC="${TMPDIR:-/tmp}/llama.cpp"
[ -d "$SRC" ] || git clone --depth 1 https://github.com/ggml-org/llama.cpp "$SRC"
cd "$SRC"
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build build-android -j --target llama      # takes several minutes
mkdir -p "$OUT/lib" "$OUT/include"
find build-android -name 'libllama.a' -o -name 'libggml*.a' | xargs -I{} cp {} "$OUT/lib/"
cp include/*.h ggml/include/*.h "$OUT/include/"
echo "llama arm64 libs -> $OUT/lib"; ls -la "$OUT/lib"
