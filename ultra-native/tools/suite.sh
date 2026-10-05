#!/bin/bash
# Agent Ultra proof suite (M5). Sends each task into the app, waits for the
# brain's RUN COMPLETE marker, and captures logcat + screenshot evidence.
# Usage: suite.sh  — results in /tmp/opencode/ultra/suite/
set -uo pipefail
OUT=/tmp/opencode/ultra/suite
mkdir -p "$OUT"
APP=com.agent.ultra

send_task() {
  local name="$1"; shift
  local text="$1"; shift
  local adbtext="${text// /%s}"
  echo "=== $name: \"$text\""
  # Re-foreground the app — earlier tasks may have launched other apps
  adb shell am start -n $APP/.MainActivity > /dev/null 2>&1
  sleep 3
  adb logcat -c
  bash "$(dirname "$0")/uitap.sh" "Ask Agent Ultra…" || { echo "[$name] input field not found"; return 1; }
  sleep 1
  adb shell input text "$adbtext"
  sleep 1
  bash "$(dirname "$0")/uitap.sh" "Send" || { echo "[$name] send not found"; return 1; }
  # Wait for RUN COMPLETE (max 240s)
  for i in $(seq 1 60); do
    if adb logcat -d 2>/dev/null | grep -q "RUN COMPLETE"; then break; fi
    sleep 4
  done
  adb logcat -d > "$OUT/$name.logcat" 2>/dev/null
  adb shell screencap -p "/sdcard/_suite.png"
  adb pull "/sdcard/_suite.png" "$OUT/$name.png" > /dev/null 2>&1
  echo "[$name] evidence captured"
}

cd /home/dafarus/projects/Audit-Discuss-Build/ultra-native
bash "$(dirname "$0")/install.sh" app/build/outputs/apk/debug/app-debug.apk > /dev/null 2>&1
sleep 3

send_task "t01_battery"   "what is my battery level"
send_task "t02_open_chrome" "open chrome"
send_task "t03_open_url"  "open wikipedia.org"
send_task "t04_search"    "search the web for the capital of Japan"
send_task "t05_clipboard" "copy the words hello world to my clipboard then tell me what is on my clipboard"
send_task "t06_note"      "create a note saying buy milk tomorrow"
send_task "t07_flash"     "turn on the flashlight"
send_task "t08_nav"       "open chrome and go to google.com"

# Leave the device as we found it. t07 turns the torch on; a suite that walks
# away with the light burning drains the battery and heats the phone.
send_task "t09_flash_off" "turn off the flashlight"
echo "=== suite done ==="
