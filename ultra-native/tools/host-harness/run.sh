#!/usr/bin/env bash
# Host harness for jarvis_llm.cpp. Usage: run.sh <path/to/jarvis_llm.cpp> [driver dir]
# Builds the shim as a host JNI library against /tmp/llama.cpp/build-host (llama.cpp
# 8345f33, x86_64) and runs Harness.java with the tiny random model. A unit that changes
# a JNI signature edits driver/ here (or a copy passed as arg 2). First run setup.sh.
# HARNESS_CPUS="2,3" pins the engine to those CPUs; unset = unpinned. MODEL=<gguf> another model;
# DRAFT=<gguf> also tests the speed-boost draft (EXPECT_REFUSED=true: the model must refuse it).
set -euo pipefail
SRC="${1:?path to jarvis_llm.cpp}"; H="$(cd "$(dirname "$0")" && pwd)"; DRIVER="${2:-$H/driver}"
L="${LLAMA_SRC:-/tmp/llama.cpp}"
OUT="$(mktemp -d /tmp/jarvis-harness-run.XXXX)"
JH="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
LIBS=$(find "$L/build-host" -name 'libllama.a' -o -name 'libggml-cpu.a' -o -name 'libggml-base.a' -o -name 'libggml.a' | tr '\n' ' ')
# -fpermissive: the shim passes JNIEnv** to AttachCurrentThread, which Android's jni.h
# accepts and the JDK's (void**) does not. Nothing else differs.
g++ -std=c++17 -O1 -g -fPIC -shared -fpermissive -w \
  -I"$H/stub" -I"$L/include" -I"$L/ggml/include" -I"$JH/include" -I"$JH/include/linux" \
  "$SRC" -o "$OUT/libjarvis_llm.so" \
  -Wl,--start-group $(find "$L/build-host" -name 'libllama.a') $(find "$L/build-host" -name 'libggml*.a') -Wl,--end-group \
  -lpthread -ldl -lm
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" $(find "$DRIVER" -name '*.java')
set +e
java -Djava.library.path="$OUT" -Dmodel="${MODEL:-$H/tiny-qwen3.gguf}" -Ddraft="${DRAFT:-}" -DexpectRefused="${EXPECT_REFUSED:-false}" -cp "$OUT/classes" com.agent.jarvis.local.Harness 2>"$OUT/native.log"
rc=$?; set -e
echo "native log: $OUT/native.log ($(grep -c . "$OUT/native.log") lines)"; grep -E "^\[UltraLlm\]" "$OUT/native.log" | sort | uniq -c | sort -rn | head -15
exit $rc
