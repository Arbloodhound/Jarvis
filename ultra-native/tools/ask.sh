#!/bin/bash
# Send one task into Agent Ultra and wait for the brain's RUN COMPLETE marker.
# Usage: ask.sh "what is my battery level" [timeout-seconds]
# Prints the brain's logcat trace for the run.
set -uo pipefail
TEXT="${1:?usage: ask.sh \"task\" [timeout]}"
TIMEOUT="${2:-180}"
APP=com.agent.ultra
DIR="$(dirname "$0")"

adb shell am start -n $APP/.MainActivity > /dev/null 2>&1
sleep 3
adb logcat -c
bash "$DIR/uitap.sh" "Ask Agent Ultra…" || { echo "[ask] input field not found" >&2; exit 1; }
sleep 1
# Quote-safe. An apostrophe in the task used to reach `adb shell input text`
# unescaped, which failed with "no closing quote" and produced no log at all —
# two runs were lost before anyone noticed the task had never been sent.
ESCAPED=$(printf '%s' "$TEXT" | sed "s/'/'\\\\''/g")
adb shell "input text '${ESCAPED// /%s}'"
sleep 1
bash "$DIR/uitap.sh" "Send" || { echo "[ask] send button not found" >&2; exit 1; }

for i in $(seq 1 $((TIMEOUT / 3))); do
  sleep 3
  if adb logcat -d 2>/dev/null | grep -q "UltraBrain.*RUN COMPLETE"; then break; fi
done
# Strip ONLY logcat's own prefix. The old `s/^.*: //` was greedy and cut to the
# LAST colon on the line, so every message containing one came back truncated:
# a recipe listing printed as blank lines, and two rounds of debugging went
# looking for a store that was never actually empty.
adb logcat -d 2>/dev/null | grep -E "UltraBrain|UltraGate|UltraNav|UltraWalk|UltraLearn|OpenAiClient" \
  | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] [A-Za-z_]+: //'
