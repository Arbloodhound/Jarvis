#!/usr/bin/env bash
# Builds llama.cpp static libs for arm64 -> ~/llama-android/{lib,lib-dotprod,lib-i8mm,include}
# (what app/src/main/cpp/CMakeLists.txt links as ultra_llm, ultra_llm_dotprod, ultra_llm_i8mm).
# Verified Oct 2026: NDK r27c, llama.cpp 8345f33; the full APK links against this output.
#
# Three CPU variants, because a cross-compile turns GGML_NATIVE off and, with no target set,
# ggml's CPU kernels are built for plain ARMv8.0: no SDOT (dotprod), no SMMLA (i8mm), no
# fp16 vector maths, even on phones that have them. LlmNative loads the best variant the
# phone's /proc/cpuinfo supports, so one APK still runs on every arm64 phone:
#   lib          armv8-a                        any arm64 (the old build, the fallback)
#   lib-dotprod  armv8.2-a+dotprod+fp16         Cortex-A76/A55 era and later
#   lib-i8mm     armv8.2-a+dotprod+i8mm+fp16    Armv8.6+/v9 cores, e.g. Tensor G5 (X4/A725/A520)
# Only libggml-cpu.a differs between them; the rest is copied per variant for a simple link.
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to your NDK path, e.g. ~/Android/Sdk/ndk/27.0.12077973}"
OUT="$HOME/llama-android"; SRC="${TMPDIR:-/tmp}/llama.cpp"
# Pinned to the commit the app was built and tested against (DEVLOG 2026-10-05). Bump it on purpose.
LLAMA_SHA="${LLAMA_SHA:-8345f333951c661d166b00e6f9362e553768f292}"
if [ ! -d "$SRC/.git" ]; then git init -q "$SRC"; git -C "$SRC" remote add origin https://github.com/ggml-org/llama.cpp; fi
cd "$SRC"
if [ "$(git rev-parse HEAD 2>/dev/null)" != "$LLAMA_SHA" ]; then
  git fetch -q --depth 1 origin "$LLAMA_SHA" && git checkout -q --force FETCH_HEAD
  rm -rf build-android build-android-*
fi

build_variant() {  # <lib dir name> <GGML_CPU_ARM_ARCH, empty = toolchain default armv8-a>
  local name="$1" arch="$2" dir="build-android-$1"
  cmake -B "$dir" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DGGML_NATIVE=OFF \
    -DGGML_CPU_ARM_ARCH="$arch" \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF
  cmake --build "$dir" -j --target llama      # takes several minutes per variant
  rm -rf "${OUT:?}/$name"; mkdir -p "$OUT/$name"
  find "$dir" -name 'libllama.a' -o -name 'libggml*.a' | xargs -I{} cp {} "$OUT/$name/"
  echo "llama arm64 libs ($name, arch=${arch:-armv8-a}) -> $OUT/$name"; ls -la "$OUT/$name"
}

build_variant lib         ""
build_variant lib-dotprod "armv8.2-a+dotprod+fp16"
build_variant lib-i8mm    "armv8.2-a+dotprod+i8mm+fp16"

mkdir -p "$OUT/include"
cp include/*.h ggml/include/*.h "$OUT/include/"
