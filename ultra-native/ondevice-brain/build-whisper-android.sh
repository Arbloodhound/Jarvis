#!/usr/bin/env bash
# Build whisper.cpp static libs for arm64 into ~/whisper-android/{lib,lib-dotprod,lib-i8mm,include},
# the path ultra-native/app/src/main/cpp/CMakeLists.txt reads for ultra_whisper,
# ultra_whisper_dotprod and ultra_whisper_i8mm. Mirrors build-llama-android.sh. Run on Linux/macOS with NDK.
# WHISPER_ANDROID_HOME overrides the output dir (CMakeLists.txt honours it too); TMPDIR moves the source.
#
# Three CPU variants, for the same reason as llama: a cross-compile turns GGML_NATIVE off and,
# with no GGML_CPU_ARM_ARCH, whisper's ggml CPU kernels are built for plain ARMv8.0 (no SDOT,
# no fp16 vector maths) even on phones that have them. WhisperNative loads the best variant the
# phone's /proc/cpuinfo supports (CpuVariant), so one APK still runs on every arm64 phone:
#   lib          armv8-a                        any arm64 (the old build, the fallback)
#   lib-dotprod  armv8.2-a+dotprod+fp16         Cortex-A76/A55 era and later
#   lib-i8mm     armv8.2-a+dotprod+i8mm+fp16    Armv8.6+/v9 cores, e.g. Tensor G5 (X4/A725/A520)
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to your r26+ NDK path}"
OUT="${WHISPER_ANDROID_HOME:-$HOME/whisper-android}"; SRC="${TMPDIR:-/tmp}/whisper.cpp"
# Pinned to the commit the app was built and tested against (DEVLOG 2026-10-05). Bump it on purpose.
WHISPER_SHA="${WHISPER_SHA:-60c0be6ac8fa71b1a2ae2dd938a31a34a508e774}"
rm -rf "$SRC" && git init -q "$SRC"
cd "$SRC"
git fetch -q --depth 1 https://github.com/ggml-org/whisper.cpp "$WHISPER_SHA" && git checkout -q FETCH_HEAD

build_variant() {  # <lib dir name> <GGML_CPU_ARM_ARCH, empty = toolchain default armv8-a>
  local name="$1" arch="$2" dir="build-android-$1"
  cmake -B "$dir" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF \
    -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF \
    -DGGML_CPU_ARM_ARCH="$arch"
  cmake --build "$dir" -j
  rm -rf "${OUT:?}/$name"; mkdir -p "$OUT/$name"
  find "$dir" -name 'libwhisper.a'   -exec cp {} "$OUT/$name/" \;
  find "$dir" -name 'libggml*.a'     -exec cp {} "$OUT/$name/" \;
  echo "whisper arm64 libs ($name, arch=${arch:-armv8-a}) -> $OUT/$name"; ls -la "$OUT/$name"
}

build_variant lib         ""
build_variant lib-dotprod "armv8.2-a+dotprod+fp16"
build_variant lib-i8mm    "armv8.2-a+dotprod+i8mm+fp16"

mkdir -p "$OUT/include"
cp include/whisper.h "$OUT/include/"
# whisper.h pulls in ggml-cpu.h -> ggml-backend.h -> ggml-alloc.h: ship all public ggml headers
cp ggml/include/*.h "$OUT/include/"
