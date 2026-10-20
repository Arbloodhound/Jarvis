#!/usr/bin/env bash
# Image token count llama.cpp's own Qwen2.5-VL preprocessing (mtmd dyn_size, patch 14, merge 2)
# gives at image_max_tokens 512 for a few screen sizes. Usage: image_tokens.sh [WxH ...]
set -uo pipefail
V=/tmp/jarvis-harness/vlm; B=/tmp/llama.cpp/build-host-mtmd
SIZES=("$@"); [ ${#SIZES[@]} -eq 0 ] && SIZES=(300x600 403x896 896x403 1080x2400 560x896)
for d in "${SIZES[@]}"; do
  w="${d%x*}"; h="${d#*x}"
  python3 "$V/make_png.py" "$V/img_$d.png" "$w" "$h"
  info=$("$B/bin/llama-mtmd-cli" -m "$V/tiny-qwen25vl.gguf" --mmproj "$V/tiny-qwen25vl-mmproj.gguf" \
    --image "$V/img_$d.png" -p "x" -n 1 -t 4 --image-max-tokens 512 -lv 5 2>&1 >/dev/null \
    | grep -E "image_tokens->n|copying image|total [0-9]+ tokens" | sed 's/^[0-9.]* D //' | tr '\n' ' ')
  echo "$d: $info"
done
