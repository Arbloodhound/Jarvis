#!/bin/bash
# Tap a UI element by its exact text. Retries the dump until found.
# Usage: uitap.sh "Ask Agent Ultra…"
TEXT="$1"
for i in $(seq 1 10); do
  adb shell uiautomator dump /sdcard/_ui.xml > /dev/null 2>&1
  B=$(adb shell cat /sdcard/_ui.xml 2>/dev/null | grep -o "text=\"$TEXT\"[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | sed 's/[^0-9]/ /g' | head -1)
  if [ -n "$B" ]; then
    set -- $B
    adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
    exit 0
  fi
  sleep 1
done
echo "[uitap] not found: $TEXT" >&2
exit 1
