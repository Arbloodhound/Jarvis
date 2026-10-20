#!/usr/bin/env bash
# Host harness for jarvis_vlm.cpp. Usage: run_vlm.sh <path/to/jarvis_vlm.cpp> [extra java -D...]
# Builds the shim as a host JNI library against /tmp/llama.cpp/build-host-mtmd (llama.cpp
# 8345f33 with libmtmd, x86_64), then runs VlmHarness through the VisionNative mirror with the
# tiny random Qwen2.5-VL pair from make_vlm.py.
#   SAN=asan    build the shim with -fsanitize=address (llama/mtmd static libs uninstrumented)
#   VALGRIND=1  run the JVM under valgrind memcheck (slow)
set -euo pipefail
SRC="${1:?path to jarvis_vlm.cpp}"; shift || true
H=/tmp/jarvis-harness; V=$H/vlm; L=/tmp/llama.cpp; B=$L/build-host-mtmd
OUT="$(mktemp -d /tmp/jarvis-vlm-run.XXXX)"
JH="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
[ -f "$V/tiny-qwen25vl.gguf" ] || python3 "$V/make_vlm.py" "$V"
SANFLAGS=""; [ "${SAN:-}" = asan ] && SANFLAGS="-fsanitize=address -fno-omit-frame-pointer"
g++ -std=c++17 -O1 -g -fPIC -shared -w $SANFLAGS \
  -I"$H/stub" -I"$L/include" -I"$L/ggml/include" -I"$L/tools/mtmd" -I"$JH/include" -I"$JH/include/linux" \
  "$SRC" -o "$OUT/libjarvis_vlm.so" \
  -Wl,--start-group $(find "$B" -name 'libmtmd.a') $(find "$B" -name 'libllama.a') $(find "$B" -name 'libggml*.a') $(find "$B" -name "libvendor*.a") -Wl,--end-group \
  -lpthread -ldl -lm
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" $(find "$V/driver" -name '*.java')
PRE=()
if [ "${SAN:-}" = asan ]; then
  export LD_PRELOAD="$(g++ -print-file-name=libasan.so) $(g++ -print-file-name=libstdc++.so)"
  export ASAN_OPTIONS="detect_leaks=0:handle_segv=0:allow_user_segv_handler=1:detect_odr_violation=0"
fi
[ -n "${VALGRIND:-}" ] && PRE=(valgrind --error-exitcode=99 --leak-check=full --show-leak-kinds=definite --errors-for-leak-kinds=definite --gen-suppressions=no --log-file="$OUT/valgrind.log" --smc-check=all-non-file)
set +e
"${PRE[@]}" java -Xss4m -Djava.library.path="$OUT" "$@" -cp "$OUT/classes" com.agent.jarvis.local.VlmHarness 2>"$OUT/native.log"
rc=$?; set -e
echo "native log: $OUT/native.log"
grep -E "^\[JarvisVlm\]|image|encod|n_tokens|batch" "$OUT/native.log" | sort | uniq -c | sort -rn | head -30 || true
exit $rc
