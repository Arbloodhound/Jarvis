#!/usr/bin/env bash
# Runs utf8_test.cpp against the UTF-8 -> UTF-16 helpers of ultra_vlm.cpp and ultra_llm.cpp.
# Usage: run_utf8.sh <cpp dir (ultra-native/app/src/main/cpp)>
set -uo pipefail
CPP="${1:?cpp dir}"; H=/tmp/jarvis-harness; V=$H/vlm; L=/tmp/llama.cpp; B=$L/build-host-mtmd
OUT="$(mktemp -d /tmp/jarvis-utf8.XXXX)"
JH="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
LIBS="-Wl,--start-group $(find "$B" -name 'libmtmd.a') $(find "$B" -name 'libllama.a') $(find "$B" -name 'libggml*.a') $(find "$B" -name 'libvendor*.a') -Wl,--end-group -lpthread -ldl -lm"
rc=0
for shim in VLM LLM; do
  g++ -std=c++17 -O0 -g -w -fpermissive -DSHIM_$shim -I"$CPP" -I"$H/stub" -I"$L/include" -I"$L/ggml/include" -I"$L/tools/mtmd" \
    -I"$JH/include" -I"$JH/include/linux" "$V/utf8_test.cpp" -o "$OUT/utf8_$shim" $LIBS || exit 2
  echo "== ultra_${shim,,}.cpp"; "$OUT/utf8_$shim" || rc=1
done
exit $rc
