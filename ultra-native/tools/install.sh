#!/bin/bash
# Agent Ultra native — one-command device install.
# Handles: Play Protect scan dialog, Samsung app-compatibility dialog,
# a11y re-enable, app launch, and verifies the install actually landed.
# Usage: install.sh [path-to-apk]   (default: the debug APK)
set -uo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || { echo "[install] APK not found: $APK"; exit 1; }
PKG=com.agent.ultra
SVC=$PKG/com.agent.ultra.AgentAccessibilityService

BEFORE=$(adb shell dumpsys package $PKG 2>/dev/null | grep -o 'lastUpdateTime=.*' | head -1)

# Buttons that dismiss the dialogs this device throws during a sideload.
# Play Protect scan prompt, Samsung "App compatibility", and the generic
# "blocked by Play Protect" sheet all use different labels — match them all.
DISMISS_TEXTS=(
  "Don't send"
  "Don’t send"          # curly apostrophe — Samsung uses this one
  "Don't Show Again"
  "Don’t Show Again"
  "Install anyway"
  "OK"
  "Got it"
  "Close"
)

dismiss_dialogs() {
  # Poll for the whole install window; dialogs appear with variable delay
  # and sometimes more than one appears in sequence.
  local deadline=$(( SECONDS + 90 ))
  local hits=0
  while [ $SECONDS -lt $deadline ]; do
    adb shell uiautomator dump /sdcard/_pp.xml > /dev/null 2>&1 || { sleep 2; continue; }
    local xml
    xml=$(adb shell cat /sdcard/_pp.xml 2>/dev/null)
    [ -z "$xml" ] && { sleep 2; continue; }
    local tapped=0
    for t in "${DISMISS_TEXTS[@]}"; do
      local b
      b=$(printf '%s' "$xml" \
        | grep -o "text=\"$t\"[^>]*bounds=\"[^\"]*\"" \
        | grep -o 'bounds="[^"]*"' | sed 's/[^0-9]/ /g' | head -1)
      if [ -n "$b" ]; then
        set -- $b
        adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) > /dev/null 2>&1
        echo "[install] dismissed dialog button: $t"
        hits=$((hits+1)); tapped=1
        sleep 1
        break
      fi
    done
    # Install finished and no dialog on screen -> stop early.
    if [ $tapped -eq 0 ] && ! kill -0 $INSTALL_PID 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  [ $hits -eq 0 ] && echo "[install] no dialogs seen"
  return 0
}

adb install -r -i com.android.vending "$APK" &
INSTALL_PID=$!
dismiss_dialogs
wait $INSTALL_PID
INSTALL_RC=$?

# Append, never replace. This setting is a colon-separated list and the phone
# may already be running someone else's accessibility service — overwriting it
# silently switches off a tool the owner relies on.
CUR=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
case ":$CUR:" in
  *":$SVC:"*) echo "[install] a11y already listed" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
    adb shell settings put secure enabled_accessibility_services "$NEW"
    echo "[install] a11y appended (was: ${CUR:-none})"
    ;;
esac
adb shell settings put secure accessibility_enabled 1
adb shell am start -n $PKG/.MainActivity > /dev/null 2>&1
sleep 3

AFTER=$(adb shell dumpsys package $PKG 2>/dev/null | grep -o 'lastUpdateTime=.*' | head -1)
echo "[install] adb install rc=$INSTALL_RC"
adb shell dumpsys package $PKG 2>/dev/null | grep versionName | head -1
if [ "$BEFORE" = "$AFTER" ]; then
  echo "[install] WARNING: lastUpdateTime unchanged ($AFTER) — the APK did NOT land."
  echo "[install] Do not trust a test run against this install."
  exit 2
fi
echo "[install] $AFTER"
if adb shell dumpsys accessibility 2>/dev/null | grep -q "AgentAccessibilityService, isDefault"; then
  echo "[install] a11y bound"
else
  echo "[install] WARNING: accessibility service is NOT bound"
fi
