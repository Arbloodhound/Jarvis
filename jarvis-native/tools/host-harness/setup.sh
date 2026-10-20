#!/usr/bin/env bash
# One-time setup for the host harness: llama.cpp 8345f33 built for x86_64 (static, PIC) in
# $LLAMA_SRC/build-host, numpy, and the tiny random qwen3 model. No phone, no downloads
# beyond GitHub + PyPI (Hugging Face is not needed).
set -euo pipefail
L="${LLAMA_SRC:-/tmp/llama.cpp}"; SHA=8345f333951c661d166b00e6f9362e553768f292
if [ ! -d "$L/.git" ]; then git init -q "$L"; git -C "$L" remote add origin https://github.com/ggml-org/llama.cpp; fi
if [ "$(git -C "$L" rev-parse HEAD 2>/dev/null)" != "$SHA" ]; then
  git -C "$L" fetch -q --depth 1 origin "$SHA" && git -C "$L" checkout -q --force FETCH_HEAD; fi
cmake -S "$L" -B "$L/build-host" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF -DGGML_NATIVE=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF > /dev/null
cmake --build "$L/build-host" -j --target llama > /dev/null
python3 -c "import numpy" 2>/dev/null || pip install -q numpy
python3 "$(dirname "$0")/make_model.py"
