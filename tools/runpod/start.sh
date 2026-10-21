#!/usr/bin/env bash
# =============================================================================
# start.sh — bring the whole Jarvis rig up on a rented GPU box, one command.
#   Works on RunPod (network volume at /workspace) and Vast.ai (persistent
#   container disk); Ubuntu 22.04 or 24.04 images; B300/B200/H200/H100.
#
#   bash /workspace/start.sh
#
# Everything on the container disk (/root) is wiped when the pod stops; only
# /workspace survives. This script redoes every step from the 2026-10-21
# bring-up, with the fixes found there baked in, ordered so the slow part
# (reading the 252 GB model off the network volume) starts as early as
# possible and everything else installs while it runs:
#
#   0. the model starts copying to local disk in parallel (if the container
#      disk is big enough) — the slow read overlaps everything below
#   1. CUDA + build tools only (the minimum the brain needs)
#   2. llama.cpp — restored from a cached build on the volume (seconds), or
#      built once (~10 min) and cached for every later session
#   3. brain (8080) and vision (8082) start loading in parallel
#   4. while they load: Node 22, Tor, SearXNG, Jarvis, the virtual desktop
#   5. wait for the brain with GB-read progress, smoke-probe, summary
#
# Fixes baked in:
#   * CUDA 12.9 (the image ships 12.8, which can't target the B300's sm_103);
#     the image's duplicate NVIDIA apt source is moved aside
#   * llama.cpp lives in /root: the network volume refuses chmod, so git
#     can't clone onto it. Pinned to b11785, the build that passed the gate.
#   * --load-mode dio: the pod's RAM limit (188 GB) is smaller than the model
#     (252 GB); the default mmap load thrashes forever once anything else
#     uses RAM. Direct reads stream to the GPU at a steady ~16 GB/min.
#   * vision on 8082 (8081 is the image's own nginx)
#
# Re-running is safe: finished steps are skipped and a running/loading brain
# is left alone (FORCE_RESTART=1 reloads it with the current settings).
#
# Tunables: NP_SLOTS (default 6 conversations = main chat + 4 workers + 1
# spare), CTX_PER_STREAM (default 65536), WORKERS (default 4 helper agents).
# The whole run is logged to /workspace/run/start.log.
# =============================================================================
set -uo pipefail
SECONDS=0

NP_SLOTS="${NP_SLOTS:-6}"
CTX_PER_STREAM="${CTX_PER_STREAM:-65536}"
WORKERS="${WORKERS:-4}"
CTX=$(( NP_SLOTS * CTX_PER_STREAM ))
LLAMA_REF="${LLAMA_REF:-b11785}"
WS=/workspace
RUN=$WS/run
CACHE=$WS/cache
V=$WS/models/qwen3-vl-8b
SRV=/root/llama.cpp/build/bin/llama-server
export DEBIAN_FRONTEND=noninteractive

mkdir -p "$RUN" "$CACHE"
# One copy of this script at a time: a second one (another window, a double
# paste) would race the first and restart a model it just started loading.
# (A pid file, not flock: the servers this script starts would inherit a
# lock descriptor and hold it long after the script ends.)
LOCK=/tmp/jarvis-start.pid
if [ -f $LOCK ] && kill -0 "$(cat $LOCK)" 2>/dev/null && grep -q start.sh /proc/"$(cat $LOCK)"/cmdline 2>/dev/null; then
  echo "start.sh is already running in another window — watch it there (or: tail -f $RUN/start.log)."; exit 1
fi
echo $$ > $LOCK

# Minimal images (common on Vast.ai) can lack these; everything below uses them.
if ! command -v curl >/dev/null || ! command -v wget >/dev/null || ! command -v ss >/dev/null || ! command -v pgrep >/dev/null; then
  apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl wget iproute2 procps ca-certificates >/dev/null 2>&1 \
    || { echo "could not install curl/wget/iproute2/procps"; exit 1; }
fi

# Ports: 8080 (brain) and 8082 (vision) unless something else already holds
# them — RunPod's image puts nginx on 8081, Vast.ai's instance portal and
# Jupyter sit on low 8xxx ports on some templates. A port held by one of our
# own llama-servers counts as free (it's ours to keep or restart).
port_taken() { ss -ltnp 2>/dev/null | grep ":$1 " | grep -qv llama-server; }
pick_port() { local p=$1; while port_taken "$p" || [ "$p" = "${2:-}" ]; do p=$(( p + 1 )); done; echo "$p"; }
BRAIN_PORT="${BRAIN_PORT:-$(pick_port 8080)}"
VISION_PORT="${VISION_PORT:-$(pick_port 8082 "$BRAIN_PORT")}"
python3 -m pip --version >/dev/null 2>&1 || apt-get install -y -qq python3-pip >/dev/null 2>&1
python3 -c "import ensurepip" >/dev/null 2>&1 || apt-get install -y -qq python3-venv >/dev/null 2>&1

# The Hugging Face CLI: pip refuses system-wide installs on some Ubuntu 24.04
# images (PEP 668), and where the "hf" script lands depends on the Python
# setup (system, conda, venv), so install defensively and look for it.
hf_install() {
  python3 -m pip install -q -U "huggingface_hub[hf_xet]" 2>/dev/null \
    || python3 -m pip install -q -U --break-system-packages "huggingface_hub[hf_xet]"
}
hf_cli() {
  local d
  command -v hf >/dev/null && { hf "$@"; return; }
  for d in "$(python3 -c 'import sysconfig; print(sysconfig.get_path("scripts"))')" /usr/local/bin "$HOME/.local/bin" \
           "$(dirname "$(python3 -c 'import sys; print(sys.executable)')")"; do
    [ -x "$d/hf" ] && { "$d/hf" "$@"; return; }
  done
  echo "hf CLI not found after installing huggingface_hub"; return 1
}

if [ -n "${VAST_CONTAINERLABEL:-}" ]; then PLATFORM=vast
elif [ -n "${RUNPOD_POD_ID:-}" ]; then PLATFORM=runpod
else PLATFORM=other; fi
exec > >(tee -a "$RUN/start.log") 2>&1

log()  { printf '\n[%s +%dm] == %s ==\n' "$(date +%H:%M:%S)" $(( SECONDS / 60 )) "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '\n[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*"; exit 1; }
healthy() { curl -s -m 5 "localhost:$1/health" | grep -q '"ok"'; }
listening() { ss -ltn | grep -q ":$1 "; }
# Stop the llama-server on a port and wait until it has really let go —
# a new one started while the old still holds the port dies on bind.
stop_port() {
  local pat="llama-server.*--port $1"
  pkill -f "$pat" || return 0
  for _ in $(seq 1 20); do pgrep -f "$pat" >/dev/null || return 0; sleep 1; done
  pkill -9 -f "$pat"; sleep 2
}

# -----------------------------------------------------------------------------
# 0. Pre-flight: will this many conversations × this much context fit?
#    Measured on 2026-10-21: the brain at 1 × 32K used 237,332 MiB of GPU
#    (235,928 MiB without its KV cache); vision ~8,000 MiB. KV costs
#    44,928 B/token (q8_0). Failing here takes a second; failing after a
#    16-minute load wastes the session.
# -----------------------------------------------------------------------------
TOTAL_MIB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits | awk '{s+=$1} END{print s}')
GPUS=$(nvidia-smi -L | wc -l)
KV_MIB=$(( CTX * 44928 / 1048576 ))
# Each extra GPU (e.g. 2× or 3× H200) carries its own CUDA context and
# compute buffers: ~1.5 GB apiece on top of the single-card measurement.
BUDGET_MIB=$(( TOTAL_MIB - 235928 - (GPUS - 1) * 1536 - 8000 - 3000 ))
if [ "$KV_MIB" -gt "$BUDGET_MIB" ]; then
  MAX_PER=$(( BUDGET_MIB * 1048576 / 44928 / NP_SLOTS / 4096 * 4096 ))
  die "$NP_SLOTS × $CTX_PER_STREAM needs ~$KV_MIB MiB of context memory but only ~$BUDGET_MIB MiB is free on this GPU. Use at most CTX_PER_STREAM=$MAX_PER with NP_SLOTS=$NP_SLOTS (or fewer slots)."
fi
if [ "$WORKERS" -ge "$NP_SLOTS" ]; then
  warn "WORKERS=$WORKERS but only $NP_SLOTS slots: the main chat plus $WORKERS workers need $(( WORKERS + 1 )) — some will queue. Use NP_SLOTS=$(( WORKERS + 1 )) or more."
fi

# Which CUDA this GPU needs, and whether the host's driver can run it —
# decided before any download starts, so a wrong host fails in a second.
GPU_CAP=$(nvidia-smi --query-gpu=compute_cap --format=csv,noheader | head -1 | tr -d .)
DRIVER_CUDA=$(nvidia-smi | grep -o "CUDA Version: [0-9.]*" | awk '{print $3}')
# The image's own toolkit is used when it can compile for this GPU (H100 =
# sm_90 and B200 = sm_100 work with 12.8). The B300 (sm_103) needs 12.9.
SYS_NVCC="$(command -v nvcc || ls /usr/local/cuda/bin/nvcc 2>/dev/null || true)"
if [ -n "$SYS_NVCC" ] && "$SYS_NVCC" --list-gpu-arch 2>/dev/null | grep -q "compute_$GPU_CAP\b"; then
  CUDA_HOME="$(dirname "$(dirname "$(readlink -f "$SYS_NVCC")")")"
  CUDA_TAG="$("$SYS_NVCC" --version | grep -o 'release [0-9.]*' | awk '{print $2}')"
  CUDA_PKG=""
else
  CUDA_HOME=/usr/local/cuda-12.9; CUDA_TAG=12.9
  awk -v d="${DRIVER_CUDA:-0}" 'BEGIN{exit !(d+0 >= 12.9)}' \
    || die "this GPU (sm_$GPU_CAP) needs CUDA 12.9, but this host's driver only supports CUDA ${DRIVER_CUDA:-?}. Pick a host with a newer driver (575+)."
  if [ -x "$SRV" ] || ls $CACHE/llama-$LLAMA_REF-sm$GPU_CAP-cuda12.9.tar >/dev/null 2>&1; then CUDA_PKG=cuda-libraries-12-9
  else CUDA_PKG=cuda-toolkit-12-9; fi
fi
export PATH=$CUDA_HOME/bin:$PATH CUDACXX=$CUDA_HOME/bin/nvcc
export LD_LIBRARY_PATH=$CUDA_HOME/lib64:$CUDA_HOME/targets/x86_64-linux/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
BUILD_TAR=$CACHE/llama-$LLAMA_REF-sm$GPU_CAP-cuda$CUDA_TAG.tar

# A download onto the volume that finished in an earlier session: give it its
# final name now (nothing has it open yet).
if [ -f $WS/models/glm52-staging/.complete ] && [ ! -e $WS/models/glm52 ]; then
  mv $WS/models/glm52-staging $WS/models/glm52 && rm -f $WS/models/glm52/.complete
  if [ -d $WS/models/qwen3-vl-8b-staging ] && [ ! -e $WS/models/qwen3-vl-8b ]; then
    mv $WS/models/qwen3-vl-8b-staging $WS/models/qwen3-vl-8b
  fi
fi
GLM="$(find $WS/models/glm52 -name '*00001-of-*.gguf' 2>/dev/null | sort | head -1)"
# Vast.ai keeps the container disk across stop/start, so a model downloaded
# to it in an earlier run is still there: use it rather than downloading.
if [ -z "$GLM" ] && [ -f /root/models/glm52/.complete ] && [ -f /root/models/qwen3-vl-8b/Qwen3-VL-8B-Instruct-abliterated.Q4_K_M.gguf ]; then
  GLM="$(find /root/models/glm52 -name '*00001-of-*.gguf' | sort | head -1)"
  V=/root/models/qwen3-vl-8b
fi
LOCAL_DIR=/root/models/glm52
COPY_PID=""
# A copy/download already running from an earlier run of this script (e.g.
# re-run after fixing a setting): follow it instead of starting a second
# one writing the same files.
if [ -f $RUN/model-copy.pid ] && kill -0 "$(cat $RUN/model-copy.pid)" 2>/dev/null; then
  COPY_PID=$(cat $RUN/model-copy.pid)
fi
FREE_BYTES=$(df --output=avail -B1 /root | tail -1)

if [ -z "$GLM" ]; then
  GLM_BYTES=252600000000
  GLM_GB=$(( GLM_BYTES / 1000000000 ))
  if [ "$FREE_BYTES" -gt $(( GLM_BYTES + 35000000000 )) ]; then
  # ---------------------------------------------------------------------------
  # No model yet, big container disk: download both models straight to the
  # pod's local disk, starting NOW so the download overlaps CUDA + the build,
  # then (if a volume is mounted) save them to it for next time.
  # ---------------------------------------------------------------------------
  V=/root/models/qwen3-vl-8b
  if [ -z "$COPY_PID" ] && [ ! -f $LOCAL_DIR/.complete ] && ! pgrep -f "llama-server.*--port $BRAIN_PORT" >/dev/null; then
    mkdir -p $LOCAL_DIR $V
    (
      export HF_XET_HIGH_PERFORMANCE=1
      hf_install \
        && hf_cli download huihui-ai/Huihui-GLM-5.2-abliterated-GGUF --include "*UD-Q2_K_MXFP4*.gguf" --local-dir $LOCAL_DIR --max-workers 16 \
        && hf_cli download mradermacher/Qwen3-VL-8B-Instruct-abliterated-GGUF --include "*Q4_K_M.gguf" --include "*mmproj-Q8_0.gguf" --local-dir $V \
        && touch $LOCAL_DIR/.complete \
        && echo "download complete" || exit 1
      # A fresh network volume on /workspace: save both models to it in the
      # background (parallel, into a temp folder renamed when done, so a
      # half-finished copy is never mistaken for a model). Next session the
      # volume path is used and nothing is downloaded again.
      if mountpoint -q $WS && [ "$(df --output=avail -B1 $WS | tail -1)" -gt 265000000000 ]; then
        echo "saving the models to the volume…"
        rm -rf $WS/models/.partial-glm52 $WS/models/.partial-vl
        mkdir -p $WS/models/.partial-glm52 $WS/models/.partial-vl
        for f in $(find $LOCAL_DIR -name '*.gguf'); do cp --no-preserve=all "$f" $WS/models/.partial-glm52/ & done
        cp --no-preserve=all $V/*.gguf $WS/models/.partial-vl/ &
        wait
        got=$(find $WS/models/.partial-glm52 -name '*.gguf' -printf '%s\n' | awk '{s+=$1} END{printf "%d", s}')
        want=$(find $LOCAL_DIR -name '*.gguf' -printf '%s\n' | awk '{s+=$1} END{printf "%d", s}')
        if [ "$got" = "$want" ]; then
          mv $WS/models/.partial-glm52 $WS/models/glm52 && mv $WS/models/.partial-vl $WS/models/qwen3-vl-8b \
            && echo "SAVED TO VOLUME — next session loads from it, no download"
        else
          echo "volume copy incomplete ($got of $want bytes) — next session will download again"
        fi
      else
        echo "no network volume with 265 GB free on $WS — models not saved (next session downloads again)"
      fi
    ) > /root/model-copy.log 2>&1 &
    COPY_PID=$!; echo $COPY_PID > $RUN/model-copy.pid
    printf '[%s] No model on /workspace: downloading it to local disk in the background (log: /root/model-copy.log)\n' "$(date +%H:%M:%S)"
  fi
  elif mountpoint -q $WS; then
    # -------------------------------------------------------------------------
    # No model yet, small container disk, volume mounted (e.g. a global volume
    # that grows with use): download straight onto the volume, into a staging
    # folder that only gets its final name once complete — so an interrupted
    # download is never mistaken for a model. The brain loads from the volume
    # (direct reads, ~16 min) and next session finds the model in place.
    # -------------------------------------------------------------------------
    LOCAL_DIR=$WS/models/glm52-staging
    V=$WS/models/qwen3-vl-8b-staging
    if [ -z "$COPY_PID" ] && [ ! -f $LOCAL_DIR/.complete ] && ! pgrep -f "llama-server.*--port $BRAIN_PORT" >/dev/null; then
      mkdir -p $LOCAL_DIR $V
      (
        export HF_XET_HIGH_PERFORMANCE=1
        hf_install \
          && hf_cli download huihui-ai/Huihui-GLM-5.2-abliterated-GGUF --include "*UD-Q2_K_MXFP4*.gguf" --local-dir $LOCAL_DIR --max-workers 16 \
          && hf_cli download mradermacher/Qwen3-VL-8B-Instruct-abliterated-GGUF --include "*Q4_K_M.gguf" --include "*mmproj-Q8_0.gguf" --local-dir $V \
          && touch $LOCAL_DIR/.complete \
          && echo "download complete — SAVED TO VOLUME (next session needs no download)"
      ) > /root/model-copy.log 2>&1 &
      COPY_PID=$!; echo $COPY_PID > $RUN/model-copy.pid
      printf '[%s] No model yet and only %d GB of container disk: downloading straight to the volume in the background (log: /root/model-copy.log)\n' \
        "$(date +%H:%M:%S)" $(( FREE_BYTES / 1000000000 ))
    fi
  else
    die "no model on /workspace, no volume mounted there, and only $(( FREE_BYTES / 1000000000 )) GB of container disk. Attach a volume at /workspace, or give the pod a 300 GB container disk."
  fi
else
  [ -f $V/Qwen3-VL-8B-Instruct-abliterated.Q4_K_M.gguf ] || die "vision model not found in $V — run setup.sh once to download it."
  SRC_DIR="$(dirname "$GLM")"
  GLM_BYTES=$(find "$SRC_DIR" -name '*.gguf' -printf '%s\n' | awk '{s+=$1} END{printf "%d", s}')
  GLM_GB=$(( GLM_BYTES / 1000000000 ))
  # ---------------------------------------------------------------------------
  # Fast path: copy the model to the pod's local disk in parallel (one stream
  # per shard), starting NOW — before CUDA installs or llama.cpp builds — so
  # the slow network-volume read overlaps the setup instead of following it.
  # The brain then loads from local NVMe in a minute or two. Needs a container
  # disk of ~270 GB+ (set when creating the pod); with a smaller disk this is
  # skipped and the brain loads straight from the volume (~16 min).
  # OFF by default (LOCAL_COPY=on enables it): measured on RunPod 2026-10-21,
  # the parallel copy read the volume slower than llama.cpp's direct load
  # (~2 vs ~19 GB/min) and failed with "Cannot allocate memory" reads.
  # ---------------------------------------------------------------------------
  if [ -z "$COPY_PID" ] && [ "${LOCAL_COPY:-off}" = "on" ] && [ ! -f $LOCAL_DIR/.complete ] && ! pgrep -f "llama-server.*--port $BRAIN_PORT" >/dev/null; then
    if [ "$FREE_BYTES" -gt $(( GLM_BYTES + 25000000000 )) ]; then
      mkdir -p $LOCAL_DIR
      (
        for f in "$SRC_DIR"/*.gguf; do cp "$f" $LOCAL_DIR/ & done; wait
        got=$(find $LOCAL_DIR -name '*.gguf' -printf '%s\n' | awk '{s+=$1} END{printf "%d", s}')
        if [ "$got" = "$GLM_BYTES" ]; then touch $LOCAL_DIR/.complete; else echo "size mismatch: $got vs $GLM_BYTES"; fi
      ) > /root/model-copy.log 2>&1 &
      COPY_PID=$!; echo $COPY_PID > $RUN/model-copy.pid
      printf '[%s] Copying the model to local disk in parallel, in the background (log: /root/model-copy.log)\n' "$(date +%H:%M:%S)"
    else
      printf '[%s] Local disk has %d GB free — too small to hold the %d GB model, so it loads straight from the volume (~16 min). A 300 GB container disk enables the fast path.\n' \
        "$(date +%H:%M:%S)" $(( FREE_BYTES / 1000000000 )) "$GLM_GB"
    fi
  fi
fi

log "Starting the rig: $NP_SLOTS conversations × $CTX_PER_STREAM context, $WORKERS workers (fits: ~$(( (BUDGET_MIB - KV_MIB) / 1024 )) GB spare)"

# SSH keys survive pod stops via a copy on the volume.
mkdir -p ~/.ssh
[ -f $WS/authorized_keys ] && cat $WS/authorized_keys >> ~/.ssh/authorized_keys
if [ -f ~/.ssh/authorized_keys ]; then
  sort -u -o ~/.ssh/authorized_keys ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
  cp ~/.ssh/authorized_keys $WS/authorized_keys 2>/dev/null
fi

# -----------------------------------------------------------------------------
# 1. CUDA + build tools — only what the brain needs, so it starts loading
#    as early as possible. With a cached build only the runtime libraries
#    are needed (much smaller than the full toolkit).
# -----------------------------------------------------------------------------

log "CUDA $CUDA_TAG (${CUDA_PKG:-from the image}) + build tools"
if [ -n "$CUDA_PKG" ]; then
  . /etc/os-release
  DISTRO="ubuntu${VERSION_ID//./}"   # ubuntu2204 or ubuntu2404
  if [ ! -f /etc/apt/sources.list.d/cuda-$DISTRO-x86_64.list ]; then
    wget -q -O /root/cuda-keyring.deb https://developer.download.nvidia.com/compute/cuda/repos/$DISTRO/x86_64/cuda-keyring_1.1-1_all.deb \
      && dpkg -i /root/cuda-keyring.deb >/dev/null || die "could not add NVIDIA's package source for $DISTRO"
  fi
  # Some images (RunPod's PyTorch one) already list the same source without
  # a key; apt refuses the duplicate.
  [ -f /etc/apt/sources.list.d/cuda.list ] && mv /etc/apt/sources.list.d/cuda.list /root/cuda.list.bak
fi
apt-get update -qq >/root/apt.log 2>&1
apt-get install -y -qq $CUDA_PKG git cmake build-essential >>/root/apt.log 2>&1 \
  || die "CUDA/build tools install failed — see /root/apt.log"

# -----------------------------------------------------------------------------
# 2. llama.cpp — cached build on the volume, else build once and cache it.
#    A tar on the volume sidesteps the volume's no-chmod rule; unpacked into
#    /root it keeps its executable bits and its RUNPATH (build/bin).
# -----------------------------------------------------------------------------
if [ ! -x "$SRV" ] && [ -f "$BUILD_TAR" ]; then
  log "Restoring the cached llama.cpp build"
  mkdir -p /root/llama.cpp && tar -C /root/llama.cpp -xf "$BUILD_TAR" || die "could not unpack $BUILD_TAR"
fi
if [ ! -x "$SRV" ]; then
  log "Building llama.cpp $LLAMA_REF (~10 min, first session only — cached afterwards)"
  [ -d /root/llama.cpp/.git ] || git clone -q --depth 1 --branch "$LLAMA_REF" https://github.com/ggml-org/llama.cpp /root/llama.cpp \
    || die "could not clone llama.cpp $LLAMA_REF"
  { cmake -S /root/llama.cpp -B /root/llama.cpp/build -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=native -DCMAKE_BUILD_TYPE=Release \
      && cmake --build /root/llama.cpp/build -j"$(nproc)" --target llama-server; } >/root/llama-build.log 2>&1 \
    || die "llama.cpp build failed — see /root/llama-build.log"
  tar -C /root/llama.cpp -cf "$BUILD_TAR.tmp" build/bin && mv "$BUILD_TAR.tmp" "$BUILD_TAR" \
    || warn "could not cache the build on the volume (next session will rebuild)"
fi
# Every library the server links must resolve (CUDA 12.9 runtime included).
if ldd "$SRV" /root/llama.cpp/build/bin/*.so 2>/dev/null | grep -q "not found"; then
  ldd "$SRV" /root/llama.cpp/build/bin/*.so | grep "not found" | sort -u
  die "llama-server is missing libraries (above). Delete $BUILD_TAR and re-run to rebuild."
fi

# -----------------------------------------------------------------------------
# 3. Brain (8080) and vision (8082). From the volume they start loading now,
#    in parallel; while a local copy/download is still running they start
#    the moment it finishes (step 5), and everything else installs meanwhile.
# -----------------------------------------------------------------------------
launch_vision() {
  pgrep -f "llama-server.*--port $VISION_PORT" >/dev/null && return 0
  log "Vision loading on $VISION_PORT"
  nohup "$SRV" -m $V/Qwen3-VL-8B-Instruct-abliterated.Q4_K_M.gguf --mmproj $V/Qwen3-VL-8B-Instruct-abliterated.mmproj-Q8_0.gguf \
    --alias jarvis-vision --host 0.0.0.0 --port $VISION_PORT -c 8192 -np 1 -ngl 999 --jinja > $RUN/vision-server.log 2>&1 &
  echo $! > $RUN/vision-server.pid
}
launch_brain() {
  if pgrep -f "llama-server.*--port $BRAIN_PORT" >/dev/null && [ "${FORCE_RESTART:-0}" != "1" ]; then
    pgrep -f "llama-server.*--port $BRAIN_PORT" | head -1 > $RUN/llama-server.pid; return 0
  fi
  if [ -f $LOCAL_DIR/.complete ]; then
    GLM_LOAD=$(find $LOCAL_DIR -name '*00001-of-*.gguf' | sort | head -1)
    case "$LOCAL_DIR" in $WS/*) WHERE="network volume, ~16 min";; *) WHERE="local disk, ~2 min";; esac
  else
    GLM_LOAD=$GLM; WHERE="network volume, ~16 min"
  fi
  # Direct reads (dio) proved out on the network volume; whether the local
  # disk's filesystem takes O_DIRECT varies, so test it on the real file and
  # fall back to plain reads ("none" — still no mmap, so no RAM thrash).
  if dd if="$GLM_LOAD" of=/dev/null bs=4M count=1 iflag=direct status=none 2>/dev/null; then MODE=dio; else MODE=none; fi
  log "Brain loading on $BRAIN_PORT (-c $CTX -np $NP_SLOTS on $GPUS GPU(s), --load-mode $MODE from the $WHERE)"
  stop_port $BRAIN_PORT
  nohup "$SRV" -m "$GLM_LOAD" --alias jarvis-glm52 --host 0.0.0.0 --port $BRAIN_PORT \
    -c "$CTX" -np "$NP_SLOTS" -ctk q8_0 -ctv q8_0 -ngl 999 --jinja --load-mode $MODE > $RUN/llama-server.log 2>&1 &
  echo $! > $RUN/llama-server.pid
}
copy_running() { [ -n "$COPY_PID" ] && kill -0 "$COPY_PID" 2>/dev/null && [ ! -f $LOCAL_DIR/.complete ]; }

BRAIN_DEFERRED=0
if pgrep -f "llama-server.*--port $BRAIN_PORT" >/dev/null && [ "${FORCE_RESTART:-0}" != "1" ]; then
  # Running or still loading: a reload would throw away minutes of loading.
  log "Brain already running/loading on $BRAIN_PORT — left alone (FORCE_RESTART=1 to reload)"
  pgrep -f "llama-server.*--port $BRAIN_PORT" | head -1 > $RUN/llama-server.pid
elif copy_running; then
  BRAIN_DEFERRED=1
  log "The brain starts as soon as the local copy finishes — installing everything else meanwhile"
else
  launch_brain
fi
# Vision's files are on the volume already, or arrive with the download.
if [ -f $V/Qwen3-VL-8B-Instruct-abliterated.Q4_K_M.gguf ]; then launch_vision; fi

# -----------------------------------------------------------------------------
# 4. Everything else, while the models load. Node and Tor are required;
#    the desktop apps are best-effort (a missing one never stops the rig).
# -----------------------------------------------------------------------------
log "Node 22, Tor and the desktop packages (while the models load)"
if ! command -v node >/dev/null || [ "$(node -v | tr -d v | cut -d. -f1)" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >>/root/apt.log 2>&1 || die "could not add the Node 22 package source"
fi
[ -s $CACHE/chrome.deb ] || wget -q -O $CACHE/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
apt-get install -y -qq nodejs tor >>/root/apt.log 2>&1 || die "Node/Tor install failed — see /root/apt.log"
apt-get install -y -qq xvfb fluxbox x11vnc novnc websockify xdotool scrot xterm pcmanfm mousepad galculator \
  x11-xserver-utils torbrowser-launcher python3-venv python3-dev libxslt1-dev zlib1g-dev libffi-dev libssl-dev \
  $CACHE/chrome.deb >>/root/apt.log 2>&1 || warn "some desktop packages didn't install — see /root/apt.log"
# Electron's runtime libraries (names differ between Ubuntu 24.04 and 22.04).
apt-get install -y -qq libnss3 libgbm1 libxkbcommon0 libgtk-3-0t64 libasound2t64 libatk-bridge2.0-0t64 >>/root/apt.log 2>&1 \
  || apt-get install -y -qq libnss3 libgbm1 libxkbcommon0 libgtk-3-0 libasound2 libatk-bridge2.0-0 >>/root/apt.log 2>&1 \
  || warn "Electron's libraries didn't install — the Jarvis app may not open"

pgrep -x tor >/dev/null || tor --RunAsDaemon 1 >/dev/null 2>&1 || warn "Tor didn't start"

# SearXNG on 127.0.0.1:8890 — many engines at once, safe search off. The
# install (venv included) is cached on the volume like the llama.cpp build;
# its settings live there too, so the secret key stays the same.
if ! listening 8890; then
  log "SearXNG on 8890"
  if [ ! -x /root/searxng/venv/bin/python ] && [ -f $CACHE/searxng.tar ]; then
    tar -C /root -xf $CACHE/searxng.tar || warn "could not unpack the cached SearXNG"
  fi
  if [ ! -x /root/searxng/venv/bin/python ]; then
    { [ -d /root/searxng/.git ] || git clone -q --depth 1 https://github.com/searxng/searxng.git /root/searxng; } \
      && ( cd /root/searxng && python3 -m venv venv && . venv/bin/activate \
        && pip install -q -U pip setuptools wheel pyyaml && pip install -q -r requirements.txt && pip install -q --no-build-isolation -e . ) \
        >/root/searxng-install.log 2>&1 \
      && { tar -C /root -cf $CACHE/searxng.tar.tmp searxng && mv $CACHE/searxng.tar.tmp $CACHE/searxng.tar; } \
      || warn "SearXNG install failed — see /root/searxng-install.log"
  fi
  if [ -x /root/searxng/venv/bin/python ]; then
    # Edited on the container disk: sed -i can't work on the volume (it
    # needs chmod), and a cached copy from that failure keeps SearXNG's
    # default key and port, so only a settings file that has both changes
    # is trusted.
    if ! grep -q "port: 8890" $CACHE/searxng-settings.yml 2>/dev/null || grep -q ultrasecretkey $CACHE/searxng-settings.yml; then
      cp /root/searxng/searx/settings.yml /root/searxng-settings.yml
      sed -i "s/ultrasecretkey/$(openssl rand -hex 32)/; s/^  port: .*/  port: 8890/; s/^  bind_address: .*/  bind_address: \"127.0.0.1\"/; s/^  safe_search: .*/  safe_search: 0/" /root/searxng-settings.yml
      sed -i 's/^    - html$/    - html\n    - json/' /root/searxng-settings.yml
      cat /root/searxng-settings.yml > $CACHE/searxng-settings.yml
    else
      cp $CACHE/searxng-settings.yml /root/searxng-settings.yml
    fi
    ( cd /root/searxng && SEARXNG_SETTINGS_PATH=/root/searxng-settings.yml nohup venv/bin/python -m searx.webapp > /root/searxng.log 2>&1 & )
  fi
fi

# Jarvis — fresh clone each session (latest code), memory restored from and
# backed up to the volume every 5 minutes.
log "Jarvis"
[ -d /root/jarvis/.git ] || git clone -q --depth 1 https://github.com/Arbloodhound/jarvis.git /root/jarvis || die "could not clone Jarvis"
# Your own changes to Jarvis, kept on the volume as patches (e.g.
# jarvis-always-allow.patch), are applied to every fresh clone.
for pch in $WS/patches/*.patch; do
  [ -f "$pch" ] || continue
  if (cd /root/jarvis && git apply --reverse --check "$pch" 2>/dev/null); then continue; fi   # already applied
  if (cd /root/jarvis && git apply "$pch"); then
    echo "applied patch $(basename "$pch")"; rm -f /root/jarvis/desktop/dist/cli.js   # rebuild with it
  else
    warn "patch $(basename "$pch") didn't apply (Jarvis changed since it was made) — skipped"
  fi
done
if [ ! -f /root/jarvis/desktop/dist/cli.js ]; then
  ( cd /root/jarvis/desktop && ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install --silent --no-fund --no-audit && npm run build --silent ) \
    >/root/jarvis-build.log 2>&1 || die "Jarvis build failed — see /root/jarvis-build.log"
fi
# The native Jarvis app (Electron: the chat page in its own window, with
# the settings toggles) for the virtual desktop. Best-effort: chat works
# without it. Its runtime libraries come with the desktop packages above.
if [ ! -x /root/jarvis/desktop/node_modules/electron/dist/electron ]; then
  ( cd /root/jarvis/desktop && node node_modules/electron/install.js ) >>/root/jarvis-build.log 2>&1 \
    || warn "the Jarvis app (Electron) didn't download — 'jarvis chat' still works; see /root/jarvis-build.log"
fi
if [ ! -d ~/.jarvis ] && [ -d $WS/jarvis-home ]; then cp -r $WS/jarvis-home ~/.jarvis; fi
mkdir -p ~/.jarvis
if [ ! -f ~/.jarvis/config.json ]; then
  cat > ~/.jarvis/config.json <<EOF
{
  "brain": "local",
  "local":  { "baseUrl": "http://127.0.0.1:$BRAIN_PORT/v1", "model": "jarvis-glm52" },
  "vision": { "enabled": true, "baseUrl": "http://127.0.0.1:$VISION_PORT/v1", "model": "jarvis-vision" },
  "screenControl": { "enabled": true },
  "subagents": { "maxConcurrent": $WORKERS },
  "tor":    { "enabled": false, "socksPort": 9050 }
}
EOF
fi
# A restored config keeps its other settings but follows this run's WORKERS
# and ports.
sed -i -E "s#(\"local\": *\{ *\"baseUrl\": *\"http://127.0.0.1:)[0-9]+#\1$BRAIN_PORT#; s#(\"vision\": *\{[^}]*\"baseUrl\": *\"http://127.0.0.1:)[0-9]+#\1$VISION_PORT#" ~/.jarvis/config.json
sed -i "s/\"maxConcurrent\": *[0-9]*/\"maxConcurrent\": $WORKERS/" ~/.jarvis/config.json
pgrep -f "jarvis-home-backup" >/dev/null || nohup bash -c 'exec -a jarvis-home-backup bash -c "while sleep 300; do mkdir -p /workspace/jarvis-home && cp -r \$HOME/.jarvis/. /workspace/jarvis-home/ 2>/dev/null; done"' >/dev/null 2>&1 &
cat > /usr/local/bin/jarvis <<'EOF'
#!/bin/sh
cd /root/jarvis/desktop && DISPLAY=:1 exec node dist/cli.js "$@"
EOF
chmod +x /usr/local/bin/jarvis

# The prediction layer: a Timestral forecasting server on the CPU (no GPU
# memory). Best-effort and in the background — the rig never waits for it;
# Jarvis's forecast tool says so until it answers. Weights cache on the volume.
FORECAST_PORT=8095
if ! curl -sf 127.0.0.1:$FORECAST_PORT/health >/dev/null 2>&1; then
  nohup bash -c "
    [ -x /root/forecast-venv/bin/python ] && /root/forecast-venv/bin/python -c 'import timestral' 2>/dev/null || {
      python3 -m venv /root/forecast-venv &&
      /root/forecast-venv/bin/pip install -q torch --index-url https://download.pytorch.org/whl/cpu &&
      /root/forecast-venv/bin/pip install -q 'git+https://github.com/mistralai/timestral.git'
    } && HF_HOME=$WS/cache/hf exec /root/forecast-venv/bin/python /root/jarvis/desktop/forecast/server.py --port $FORECAST_PORT
  " > /root/forecast.log 2>&1 &
fi
node -e 'const f=process.env.HOME+"/.jarvis/config.json",fs=require("fs");const c=JSON.parse(fs.readFileSync(f));c.forecast={...(c.forecast||{}),baseUrl:"http://127.0.0.1:'$FORECAST_PORT'"};fs.writeFileSync(f,JSON.stringify(c,null,2))' \
  || warn "couldn't set forecast.baseUrl in ~/.jarvis/config.json"

# Virtual desktop on :1, viewable at 127.0.0.1:6080 through the SSH tunnel.
log "Virtual desktop"
export DISPLAY=:1
if command -v Xvfb >/dev/null; then
  pgrep Xvfb >/dev/null || { Xvfb :1 -screen 0 1280x800x24 >/dev/null 2>&1 & sleep 2; }
  pgrep fluxbox >/dev/null || (fluxbox >/dev/null 2>&1 &)
  pgrep x11vnc >/dev/null || (x11vnc -display :1 -forever -shared -nopw -localhost -rfbport 5900 >/dev/null 2>&1 &)
  pgrep -f websockify >/dev/null || (websockify --web /usr/share/novnc 127.0.0.1:6080 localhost:5900 >/dev/null 2>&1 &)
  # Tor Browser refuses to run as root, so it runs as the "desk" user.
  id desk >/dev/null 2>&1 || useradd -m -s /bin/bash desk
  xhost +SI:localuser:desk >/dev/null 2>&1
  mkdir -p ~/.fluxbox && cat > ~/.fluxbox/menu <<'EOF'
[begin] (Apps)
  [exec] (Jarvis app) {sh -c "cd /root/jarvis/desktop && ./node_modules/.bin/electron . --no-sandbox"}
  [exec] (Chrome - SearXNG) {google-chrome --no-sandbox --no-first-run --remote-debugging-port=9222 --user-data-dir=/root/.config/jarvis-chrome}
  [exec] (Tor Browser) {su - desk -c "DISPLAY=:1 torbrowser-launcher"}
  [exec] (Files) {pcmanfm}
  [exec] (Text Editor) {mousepad}
  [exec] (Calculator) {galculator}
  [exec] (Terminal) {xterm}
  [separator]
  [restart] (Restart desktop)
[end]
EOF
  fluxbox-remote "Reconfigure" >/dev/null 2>&1
  mkdir -p /etc/opt/chrome/policies/managed && cat > /etc/opt/chrome/policies/managed/search.json <<'EOF'
{
  "DefaultSearchProviderEnabled": true,
  "DefaultSearchProviderName": "SearXNG",
  "DefaultSearchProviderKeyword": "sx",
  "DefaultSearchProviderSearchURL": "http://127.0.0.1:8890/search?q={searchTerms}",
  "HomepageLocation": "http://127.0.0.1:8890",
  "RestoreOnStartupURLs": ["http://127.0.0.1:8890"],
  "RestoreOnStartup": 4
}
EOF
  command -v google-chrome >/dev/null && { pgrep -f "chrome.*no-first-run" >/dev/null || (google-chrome --no-sandbox --no-first-run --remote-debugging-port=9222 --user-data-dir=/root/.config/jarvis-chrome >/dev/null 2>&1 &); }
  # Open the Jarvis app on the desktop (Electron refuses to run as root
  # without --no-sandbox; the container is the sandbox here).
  if [ -x /root/jarvis/desktop/node_modules/.bin/electron ] && ! pgrep -f "electron . --no-sandbox" >/dev/null; then
    ( cd /root/jarvis/desktop && nohup ./node_modules/.bin/electron . --no-sandbox > /root/jarvis-app.log 2>&1 & )
  fi
else
  warn "no virtual desktop (Xvfb didn't install) — Jarvis still works, just without a screen"
fi

# -----------------------------------------------------------------------------
# 5. Wait for the brain — GB read so far, every 30 s, with an ETA.
# -----------------------------------------------------------------------------
if [ "$BRAIN_DEFERRED" = 1 ]; then
  log "Waiting for the local copy of the model to finish"
  while copy_running; do
    have=$(( $(du -sb $LOCAL_DIR 2>/dev/null | cut -f1) / 1000000000 ))
    printf '[%s +%dm]  local copy: %d of %d GB\n' "$(date +%H:%M:%S)" $(( SECONDS / 60 )) "$have" "$GLM_GB"
    sleep 30
  done
  if [ ! -f $LOCAL_DIR/.complete ]; then
    [ -n "$GLM" ] || die "model download failed — see /root/model-copy.log"
    warn "local copy didn't complete ($(tail -2 /root/model-copy.log)) — loading from the volume instead"
  fi
  launch_brain
fi
launch_vision

log "Waiting for the brain"
P="$(cat $RUN/llama-server.pid 2>/dev/null)"
deadline=$(( SECONDS + 2700 ))
prev=0
until healthy $BRAIN_PORT; do
  if [ -z "$P" ] || ! kill -0 "$P" 2>/dev/null; then
    tail -20 $RUN/llama-server.log
    die "the brain stopped while loading — log tail above (full log: $RUN/llama-server.log)."
  fi
  [ "$SECONDS" -lt "$deadline" ] || die "the brain isn't ready after 45 min — see $RUN/llama-server.log"
  read_gb=$(( $(awk '/^rchar/{print $2}' /proc/$P/io 2>/dev/null || echo 0) / 1000000000 ))
  rate=$(( read_gb - prev )); prev=$read_gb
  eta=$([ "$rate" -gt 0 ] && echo "~$(( (GLM_GB - read_gb) / rate / 2 + 1 )) min left" || echo "")
  printf '[%s +%dm]  brain: read %d of %d GB  %s\n' "$(date +%H:%M:%S)" $(( SECONDS / 60 )) "$read_gb" "$GLM_GB" "$eta"
  sleep 30
done
for _ in $(seq 1 24); do healthy $VISION_PORT && break; sleep 5; done

# A real request, not just /health: proves the brain answers.
PROBE=$(curl -s -m 180 -o /dev/null -w '%{http_code}' localhost:$BRAIN_PORT/v1/chat/completions \
  -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"Say OK"}],"max_tokens":16}')

# -----------------------------------------------------------------------------
# 6. Summary
# -----------------------------------------------------------------------------
status() { if eval "$2"; then echo "  ✓ $1"; else echo "  ✗ $1"; fi; }
log "Rig is up in $(( SECONDS / 60 )) min"
status "brain     :$BRAIN_PORT  ($NP_SLOTS × $CTX_PER_STREAM ctx, answers requests)" "healthy $BRAIN_PORT && [ '$PROBE' = 200 ]"
status "vision    :$VISION_PORT" "healthy $VISION_PORT"
status "tor       :9050" "listening 9050"
status "searxng   :8890" "listening 8890"
status "desktop   :6080 (noVNC)" "listening 6080"
status "forecast  :$FORECAST_PORT (Timestral, CPU — may still be installing: tail /root/forecast.log)" "curl -sf 127.0.0.1:$FORECAST_PORT/health >/dev/null"
echo "  GPU memory: $(nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader)"
if [ -z "$GLM" ]; then
  echo "  Model download: $(tail -1 /root/model-copy.log)"
  grep -q "SAVED TO VOLUME" /root/model-copy.log 2>/dev/null \
    || echo "  → Don't stop the pod until /root/model-copy.log says SAVED TO VOLUME (check: tail -1 /root/model-copy.log)"
fi
cat <<EOF

 From the Chromebook — SSH details: $( [ "$PLATFORM" = vast ] && echo "Vast console → your instance → Connect (the 'ssh -p PORT root@IP' line)" || echo "RunPod → your pod → Connect → 'SSH over exposed TCP'" ):
   ssh -L $BRAIN_PORT:127.0.0.1:$BRAIN_PORT -L $VISION_PORT:127.0.0.1:$VISION_PORT -L 6080:127.0.0.1:6080 -i ~/.ssh/id_ed25519 -p <PORT> root@<IP>
 Screen viewer: http://127.0.0.1:6080/vnc.html  → Connect

 Talk to Jarvis (on the pod):  jarvis chat
 Tor on/off for Jarvis:        edit "tor" → "enabled" in ~/.jarvis/config.json

 Stop the pod in RunPod when you're done (~\$9/hr running; volume only when stopped).
EOF
