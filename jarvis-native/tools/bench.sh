#!/bin/bash
# On-device LLM benchmark (DEBUG builds only: the receiver is in src/debug).
# Usage: bench.sh [threads] [tokens] [timeout-seconds]
#   threads  one count to run; 0 or omitted sweeps 4, 5, 6
#   tokens   tokens to generate per run (default 64)
# Each count runs without and (when downloaded) with the speed boost's draft model,
# then draft lengths 4 and 8 at the top count; each setting runs twice and the
# second is reported (the first absorbs warm-up). A "summary" line names the best.
# Prints the CPU topology the app sees, then a table. Needs a model already
# downloaded in the app (Settings); otherwise the app logs why and stops.
set -uo pipefail
THREADS="${1:-0}"
TOKENS="${2:-0}"
TIMEOUT="${3:-1800}"
APP=com.agent.jarvis
RECEIVER=$APP/.bench.BenchReceiver

# Foreground first. The receiver finishes its broadcast before generating, so
# only a visible activity keeps the process alive — and a background app is
# held to the small cores, which would make every number below meaningless.
adb shell am start -n $APP/.MainActivity > /dev/null 2>&1
sleep 3
adb logcat -c

EXTRAS=()
[ "$THREADS" -gt 0 ] 2>/dev/null && EXTRAS+=(--ei threads "$THREADS")
[ "$TOKENS" -gt 0 ] 2>/dev/null && EXTRAS+=(--ei tokens "$TOKENS")
adb shell am broadcast -a com.agent.jarvis.BENCH -n $RECEIVER ${EXTRAS[@]+"${EXTRAS[@]}"} > /dev/null

# -v tag: "I/JarvisBench: message", nothing else to strip.
logs() { adb logcat -d -v tag -s JarvisBench JarvisLlm 2>/dev/null | tr -d '\r'; }

DONE=0
for i in $(seq 1 $(( (TIMEOUT + 2) / 3 ))); do
  sleep 3
  # Not grep -q: it exits at the first match, adb gets SIGPIPE, and pipefail turns the hit into a miss.
  if logs | grep "JarvisBench: done" > /dev/null; then DONE=1; break; fi
done

LOG="$(logs)"
if [ $DONE -eq 0 ]; then
  if ! printf '%s\n' "$LOG" | grep "JarvisBench:" > /dev/null; then
    echo "[bench] no JarvisBench output at all. Is a DEBUG build installed? (release builds have no receiver)" >&2
  else
    echo "[bench] no 'done' line within ${TIMEOUT}s; partial output below" >&2
  fi
fi

BENCH="$(printf '%s\n' "$LOG" | grep "JarvisBench:" | sed -E 's/^[A-Z]\/JarvisBench *: //')"

echo "=== topology and status"
printf '%s\n' "$BENCH" | grep -vE "^(threads=|warmup |summary |done|$)"
echo "=== engine (JarvisLlm)"
printf '%s\n' "$LOG" | grep "JarvisLlm:" | sed -E 's/^[A-Z]\/JarvisLlm *: //'
echo "=== result (second run of each threads/draft setting; draft=off is plain decoding)"
printf '%s\n' "$BENCH" | grep "^threads=" | awk '
  BEGIN { printf "%-8s | %-6s | %-8s | %-9s | %-9s | %-9s | %-9s | %s\n", "threads", "draft", "tok/s", "accept", "promptMs", "majfaults", "genTokens", "thermal" }
  {
    split("", v)
    for (i = 1; i <= NF; i++) { split($i, kv, "="); v[kv[1]] = kv[2] }
    printf "%-8s | %-6s | %-8s | %-9s | %-9s | %-9s | %-9s | %s\n", v["threads"], v["draft"], v["tokPerSec"], v["accept"], v["promptMs"], v["majfaults"], v["genTokens"], v["thermal"]
  }'
printf '%s\n' "$BENCH" | grep "^summary "
printf '%s\n' "$BENCH" | grep "^done" || echo "(not done)"
printf '%s\n' "$BENCH" | grep "^done status=ok" > /dev/null
