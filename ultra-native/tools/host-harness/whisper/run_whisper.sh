#!/usr/bin/env bash
# Host harness for ultra_whisper.cpp. Usage: run_whisper.sh <path/to/ultra_whisper.cpp>
# Builds the shim as a host JNI .so against /tmp/whisper.cpp/build-host (whisper.cpp 60c0be6,
# static, PIC, no OpenMP, as build-whisper-android.sh), with whisper_exports.map and
# --exclude-libs,ALL as CMakeLists.txt links it, then runs WhisperHarness with whisper.cpp's
# own for-tests tiny.en model, its for-tests Silero VAD model and samples/jfk.wav.
# One-time: cmake -S /tmp/whisper.cpp -B /tmp/whisper.cpp/build-host -DCMAKE_BUILD_TYPE=Release \
#   -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
#   -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF && cmake --build ... -j --target whisper
set -euo pipefail
SRC="${1:?path to ultra_whisper.cpp}"; CPP="$(dirname "$(readlink -f "$SRC")")"
H=/tmp/jarvis-harness; WH=$H/whisper; W=/tmp/whisper.cpp; B=$W/build-host
OUT="$(mktemp -d /tmp/jarvis-whisper-run.XXXX)"
JH="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
SANFLAGS=""; [ "${SAN:-}" = asan ] && SANFLAGS="-fsanitize=address -fno-omit-frame-pointer"
g++ -std=c++17 -O1 -g -fPIC -shared -w $SANFLAGS \
  -I"$W/include" -I"$W/ggml/include" -I"$JH/include" -I"$JH/include/linux" \
  "$SRC" -o "$OUT/libultra_whisper.so" \
  -Wl,--start-group $(find "$B" -name 'libwhisper.a') $(find "$B" -name 'libggml*.a') -Wl,--end-group \
  -Wl,--exclude-libs,ALL -Wl,--version-script="$CPP/whisper_exports.map" -lpthread -ldl -lm
echo "exported dynamic symbols: $(nm -D --defined-only "$OUT/libultra_whisper.so" | awk '{print $3}' | tr '\n' ' ')"
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" $(find "$WH/driver" -name '*.java')
if [ "${SAN:-}" = asan ]; then
  export LD_PRELOAD="$(g++ -print-file-name=libasan.so) $(g++ -print-file-name=libstdc++.so)"
  export ASAN_OPTIONS="detect_leaks=0:handle_segv=0:allow_user_segv_handler=1:detect_odr_violation=0"
fi
set +e
java -Djava.library.path="$OUT" -Dmodel="${MODEL:-$W/models/for-tests-ggml-tiny.en.bin}" \
  -Dvad="${VAD:-$W/models/for-tests-silero-v6.2.0-ggml.bin}" -Dwav="$W/samples/jfk.wav" \
  -cp "$OUT/classes" com.agent.ultra.listen.WhisperHarness 2>"$OUT/native.log"
rc=$?; set -e
echo "native log: $OUT/native.log"
grep -E "error|ERROR|failed|vad|VAD|empty model" "$OUT/native.log" | sort | uniq -c | sort -rn | head -20 || true
exit $rc
