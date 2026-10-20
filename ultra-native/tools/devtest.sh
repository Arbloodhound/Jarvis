#!/usr/bin/env bash
# One command to get the phone into a known state and run a task on it.
#
#   tools/devtest.sh "open example.com and tell me what it says"
#   tools/devtest.sh --allow com.android.chrome,com.sec.android.app.clockpackage "..."
#   tools/devtest.sh --no-build "..."
#
# Every hand-driven step this replaces was a step that got skipped, done in a
# different order, or done wrong late at night — and a test whose setup is
# unreliable produces results unreliable in the same way. Two whole runs were
# lost to an apostrophe in a task before this existed.
#
# NEVER uninstalls. Uninstalling wipes the allow-list, the provider, the screen
# memory and the accessibility binding, and re-earning those by hand is exactly
# the churn this removes. The signing key is stable now, so install -r keeps
# everything.
set -uo pipefail

ADB="${ANDROID_HOME:-$HOME/Android}/platform-tools/adb"
PKG=com.agent.ultra
SVC="$PKG/$PKG.AgentAccessibilityService"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$HERE")"
APK="$PROJ/app/build/outputs/apk/release/app-release.apk"

BUILD=1
ALLOW="com.android.chrome"
PERSONAL=""
TASK=""
TIMEOUT=200
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --allow) ALLOW="$2"; shift 2 ;;
    --personal-data) PERSONAL="$2"; shift 2 ;;   # on|off — messages, contacts, location
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) TASK="$1"; shift ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nSTOPPED: %s\n' "$*"; exit 1; }

$ADB get-state >/dev/null 2>&1 || die "no device. Plug the phone in and unlock it."

if [ "$BUILD" = 1 ]; then
  say "building"
  ( cd "$PROJ" && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
      ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}" ./gradlew :app:assembleRelease -q ) \
    || die "build failed"
fi

say "installing (never uninstalling — that is what wipes the setup)"
timeout 180 $ADB install -r "$APK" >/dev/null 2>&1 &
IPID=$!
sleep 8
# Play Protect asks on every new build; the owner's standing answer is Don't send.
if timeout 15 $ADB shell dumpsys window 2>/dev/null | grep -q PlayProtectDialogs; then
  timeout 15 $ADB shell input tap 540 1751 >/dev/null 2>&1
fi
wait $IPID

say "accessibility"
BOUND=$(timeout 30 $ADB shell dumpsys accessibility 2>/dev/null | grep -c 'Bound services:{Service')
if [ "$BOUND" = "0" ]; then
  LISTED=$(timeout 20 $ADB shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  case "$LISTED" in
    *"$PKG"*) die "Android LISTS the service but has not bound it. It cannot be fixed from here:
    Settings > Accessibility > Installed apps > Agent Ultra — turn it OFF, then ON." ;;
    *) die "the accessibility service is off:
    Settings > Accessibility > Installed apps > Agent Ultra — turn it ON." ;;
  esac
fi
echo "  bound"

say "provider"
DIR=/sdcard/Android/data/$PKG/files
if [ -n "${VENICE_API_KEY:-}" ]; then
  # Written by python straight from the environment: the key never appears in a
  # command line, a process list, or this script's output.
  TMP=$(mktemp); chmod 600 "$TMP"
  python3 -c "
import json,os,sys
json.dump({'baseUrl':'https://api.venice.ai/api/v1','apiKey':os.environ['VENICE_API_KEY'],
           'model':'llama-3.3-70b'}, open(sys.argv[1],'w'))" "$TMP"
  timeout 30 $ADB shell mkdir -p "$DIR" >/dev/null 2>&1
  timeout 30 $ADB push "$TMP" "$DIR/ultra_provider.json" >/dev/null 2>&1
  shred -u "$TMP" 2>/dev/null || rm -f "$TMP"
  echo "  seeded from VENICE_API_KEY"
else
  echo "  VENICE_API_KEY not set — using whatever the app already has"
fi

say "allowed apps: $ALLOW"
TMP=$(mktemp)
python3 -c "
import json,sys
cfg={'allowlistMode':True,'allowApps':sys.argv[1].split(',')}
if sys.argv[3]: cfg['personalData'] = (sys.argv[3] == 'on')
json.dump(cfg, open(sys.argv[2],'w'))" \
  "$ALLOW" "$TMP" "$PERSONAL"
[ -n "$PERSONAL" ] && echo "  personal data: $PERSONAL"
timeout 30 $ADB push "$TMP" "$DIR/ultra_setup.json" >/dev/null 2>&1
rm -f "$TMP"
# Launching the app applies and deletes it. Toggling the accessibility binding
# to force a service reconnect was tried first and is unreliable — Android
# frequently leaves the service listed but unbound, which is a worse state than
# the one being fixed.
timeout 30 $ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 4
if timeout 20 $ADB shell ls "$DIR/ultra_setup.json" >/dev/null 2>&1; then
  echo "  WARNING: setup file not consumed — is the app installed and launchable?"
else
  echo "  applied"
fi

[ -z "$TASK" ] && { say "ready. No task given."; exit 0; }

say "running: $TASK"
$ADB logcat -c
bash "$HERE/ask.sh" "$TASK" "$TIMEOUT" >/dev/null 2>&1

say "result"
timeout 30 $ADB logcat -d 2>/dev/null \
  | grep -E "UltraBrain: (TOOL CALL|TOOL RESULT|RUN COMPLETE)|UltraFlow|UltraNav: (DRIFT|step)|GATE: BLOCKED" \
  | sed 's/^.*: //' | tail -25

# Leave nothing sensitive behind.
timeout 20 $ADB shell rm -f "$DIR/ultra_provider.json" >/dev/null 2>&1
