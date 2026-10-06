#!/bin/bash
# On-device accelerator probe (DEBUG builds only: the receiver is in src/debug).
# Usage: accel-probe.sh [timeout-seconds]
# Answers "can this phone run Jarvis's audio models on its TPU / GPU?":
#   - what the shell sees: SoC props, NNAPI driver services (dumpsys / lshal)
#   - what the app sees: SoC, CPU topology, wake models timed on CPU / NNAPI / XNNPACK,
#     OpenCL / Vulkan presence and loadability, vendor NN driver file names, verdicts
#   - what the running wake gate chose (UltraAmbient lines, if it ran since boot)
# The NNAPI timing needs the wake models downloaded in the app (Settings); without
# them the app says "no model to test" and the rest still runs.
# Every section tolerates failure (non-root lshal and dumpsys often refuse) and says so.
set -uo pipefail
TIMEOUT="${1:-180}"
APP=com.agent.ultra
RECEIVER=$APP/.bench.AccelProbeReceiver

section() { echo; echo "=== $1"; }

section "SoC (getprop)"
for p in ro.soc.manufacturer ro.soc.model ro.hardware ro.board.platform ro.product.device; do
  v="$(adb shell getprop "$p" 2>/dev/null | tr -d '\r')" || v=""
  echo "$p=${v:-(empty or unreadable)}"
done

# NNAPI drivers register as services: AIDL ones (Android 12+) in `dumpsys -l`,
# HIDL ones in lshal. The names go to the app so its verdict can use them.
section "NNAPI services (dumpsys -l | grep -i neuralnetworks)"
AIDL_OK=0
HIDL_OK=0
if DUMPSYS="$(adb shell dumpsys -l 2>/dev/null | tr -d '\r')" && [ -n "$DUMPSYS" ]; then
  AIDL_OK=1
  AIDL="$(printf '%s\n' "$DUMPSYS" | grep -i neuralnetworks | sed -E 's/^[[:space:]]+//')"
  printf '%s\n' "${AIDL:-(none registered)}"
else
  AIDL=""
  echo "(dumpsys -l failed or printed nothing)"
fi

section "HIDL HALs (lshal | grep neuralnetworks|darwinn|edgetpu)"
if LSHAL="$(adb shell lshal 2>/dev/null | tr -d '\r')" && [ -n "$LSHAL" ]; then
  HIDL_OK=1
  HIDL="$(printf '%s\n' "$LSHAL" | grep -o -i -E "[A-Za-z0-9._@:/-]*(neuralnetworks|darwinn|edgetpu)[A-Za-z0-9._@:/-]*" | sort -u)"
  printf '%s\n' "${HIDL:-(none listed)}"
else
  HIDL=""
  echo "(lshal failed or printed nothing: often needs root; the app's TPU verdict then can't say "not available")"
fi

# What the app's own wake gate chose, from the log since boot. Read before the
# logcat -c below, which would erase it.
section "running wake gate (logcat UltraAmbient)"
AMBIENT="$(adb logcat -d -s UltraAmbient 2>/dev/null | tr -d '\r' | grep -E "wake model|wake gate on")"
printf '%s\n' "${AMBIENT:-(no wake gate lines in the current log: ambient listening off, models missing, or log rotated)}"

# Only names, comma-separated, characters that survive adb shell quoting.
# "complete" only when both lists were read: the Tensor G1-G3 TPU driver is
# HIDL, so a missing lshal list must keep the app from saying "not available".
HAL_EXTRA=()
if [ $AIDL_OK -eq 1 ] || [ $HIDL_OK -eq 1 ]; then
  NAMES="$(printf '%s\n%s\n' "$AIDL" "$HIDL" | grep -v "^$" | tr -cd 'A-Za-z0-9._@:/\n-' | paste -sd, -)"
  HAL_EXTRA=(--es hal "${NAMES:-none}")
  if [ $AIDL_OK -eq 1 ] && [ $HIDL_OK -eq 1 ]; then HAL_EXTRA+=(--ez complete true); fi
fi

# Foreground first, as bench.sh: the receiver finishes its broadcast before the
# work, so only a visible activity keeps the process alive.
adb shell am start -n $APP/.MainActivity > /dev/null 2>&1
sleep 3
adb logcat -c
adb shell am broadcast -a com.agent.ultra.ACCEL_PROBE -n $RECEIVER ${HAL_EXTRA[@]+"${HAL_EXTRA[@]}"} > /dev/null

logs() { adb logcat -d -v tag -s UltraAccel 2>/dev/null | tr -d '\r'; }

DONE=0
for _ in $(seq 1 $(( (TIMEOUT + 2) / 3 ))); do
  sleep 3
  # Not grep -q: it exits at the first match, adb gets SIGPIPE, and pipefail turns the hit into a miss.
  if logs | grep "UltraAccel: done status=" > /dev/null; then DONE=1; break; fi
done

LOG="$(logs)"
if [ $DONE -eq 0 ]; then
  if ! printf '%s\n' "$LOG" | grep "UltraAccel:" > /dev/null; then
    echo "[accel] no UltraAccel output at all. Is a DEBUG build installed? (release builds have no receiver)" >&2
  else
    echo "[accel] no 'done' line within ${TIMEOUT}s; partial output below" >&2
  fi
fi

REPORT="$(printf '%s\n' "$LOG" | grep "UltraAccel:" | sed -E 's/^[A-Z]\/UltraAccel *: //')"

section "app report (UltraAccel)"
printf '%s\n' "$REPORT" | grep -vE "^(verdict |done|$)"
section "verdicts"
printf '%s\n' "$REPORT" | grep "^verdict " || echo "(no verdict lines)"
printf '%s\n' "$REPORT" | grep "^done" || echo "(not done)"
printf '%s\n' "$REPORT" | grep "^done status=ok" > /dev/null
