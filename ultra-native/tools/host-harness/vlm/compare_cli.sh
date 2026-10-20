#!/usr/bin/env bash
# Token-for-token check of ultra_vlm.cpp against llama.cpp's own llama-mtmd-cli (8345f33) on the
# tiny Qwen2.5-VL pair: same image, same chat-templated prompt (the marker is put in the prompt
# so both build "<|im_start|>user\n<__media__>Describe this screen.<|im_end|>\n<|im_start|>assistant\n"),
# same sampler chain (top_k 40 -> top_p 0.9 -> temp 0.2 -> dist seed 42; the cli's other samplers
# at their no-op values). Identical output = same image tokens, same n_past/M-RoPE positions for
# the generated tokens, same KV handling. Usage: compare_cli.sh <path/to/ultra_vlm.cpp> [max]
set -euo pipefail
SRC="${1:?path to ultra_vlm.cpp}"; MAX="${2:-32}"
H=/tmp/jarvis-harness; V=$H/vlm; L=/tmp/llama.cpp; B=$L/build-host-mtmd
OUT="$(mktemp -d /tmp/jarvis-vlm-cmp.XXXX)"
JH="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
[ -f "$V/img300x600.png" ] || python3 "$V/make_png.py" "$V/img300x600.png"
g++ -std=c++17 -O1 -g -fPIC -shared -w \
  -I"$H/stub" -I"$L/include" -I"$L/ggml/include" -I"$L/tools/mtmd" -I"$JH/include" -I"$JH/include/linux" \
  "$SRC" -o "$OUT/libultra_vlm.so" \
  -Wl,--start-group $(find "$B" -name 'libmtmd.a') $(find "$B" -name 'libllama.a') $(find "$B" -name 'libggml*.a') $(find "$B" -name "libvendor*.a") -Wl,--end-group \
  -lpthread -ldl -lm
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" $(find "$V/driver" -name '*.java')
PROMPT="<__media__>Describe this screen."
"$B/bin/llama-mtmd-cli" -m "$V/tiny-qwen25vl.gguf" --mmproj "$V/tiny-qwen25vl-mmproj.gguf" --image "$V/img300x600.png" \
  -p "$PROMPT" -n "$MAX" -t 4 -c 4096 -b 512 -ub 512 --image-max-tokens 512 --no-warmup \
  --temp 0.2 --top-k 40 --top-p 0.9 --min-p 0 --seed 42 --repeat-penalty 1.0 \
  > "$OUT/cli.out" 2> "$OUT/cli.err"
java -Djava.library.path="$OUT" -Dmodel="$V/tiny-qwen25vl.gguf" -Dmmproj="$V/tiny-qwen25vl-mmproj.gguf" \
  -Dprompt="$PROMPT" -Dmax="$MAX" -Dout="$OUT/shim.out" -cp "$OUT/classes" com.agent.jarvis.local.VlmOnce 2> "$OUT/shim.err" | grep -v JAVA_TOOL || true
grep -E "sampler chain|sampler seed|image_tokens->n|decoding image|n_tokens_batch" "$OUT/cli.err" || true
python3 "$V/compare_cli.py" "$OUT/cli.out" "$OUT/shim.out"
