#!/usr/bin/env bash
# Run the on-device reliability tests (quality/reliability) and print a summary.
#
#   quality/scripts/run-reliability.sh
#
# Needs one connected, unlocked phone with the app set up as a user runs it:
# accessibility service on, overlay allowed. ADB defaults to `adb`; on WSL with a
# Windows-side device set ADB=adb.exe. Installs the release build signed with the
# local release key (see local.properties), so the app's data is kept.
#
# The tests briefly disturb the phone — crash the app, revoke its overlay and
# accessibility permissions, switch Wi-Fi and mobile data off, toggle dark mode
# and rotation — and restore each in `finally`. This script records the original
# settings first and restores them again on exit, even if a test run dies.
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.octos.aichat
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/reliability-results
mkdir -p "$OUT"

apk_path() { if [[ $ADB == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi; }
install() { "$ADB" install -r -t "$(apk_path "$1")" | tail -1; }
get() { "$ADB" shell "$@" | tr -d '\r'; }

# Snapshot what the tests touch, so a crashed run can't leave the phone changed.
A11Y=$(get settings get secure enabled_accessibility_services)
WIFI=$(get settings get global wifi_on)
DATA=$(get settings get global mobile_data)
NIGHT=$(get cmd uimode night | sed 's/.*: *//')
AUTOROT=$(get settings get system accelerometer_rotation)
ROT=$(get settings get system user_rotation)

restore() {
    "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
    if [[ -n $A11Y && $A11Y != null ]]; then
        "$ADB" shell settings put secure enabled_accessibility_services "$A11Y" || true
        "$ADB" shell settings put secure accessibility_enabled 1 || true
    fi
    [[ $WIFI != 0 ]] && "$ADB" shell svc wifi enable || true
    [[ $DATA == 1 ]] && "$ADB" shell svc data enable || true
    "$ADB" shell cmd uimode night "${NIGHT:-auto}" >/dev/null || true
    "$ADB" shell settings put system user_rotation "${ROT:-0}" || true
    "$ADB" shell settings put system accelerometer_rotation "${AUTOROT:-1}" || true
    "$ADB" uninstall "$PKG.reliability" >/dev/null 2>&1 || true
}
trap restore EXIT

cd "$ROOT"
echo "== reliability (release build)"
./gradlew -q :app:assembleRelease :quality:reliability:assembleRelease
install app/build/outputs/apk/release/app-release.apk
install quality/reliability/build/outputs/apk/release/reliability-release.apk
# An update install stops the bubble service; opening the app starts it again.
"$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am instrument -w -r "$PKG.reliability/androidx.test.runner.AndroidJUnitRunner" \
    > "$OUT/reliability.txt" 2>&1 || true

python3 - "$OUT/reliability.txt" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read().replace("\r", "")
# Per-test outcome from the status blocks: test name, then the status code.
for m in re.finditer(r"INSTRUMENTATION_STATUS: test=(\w+)\n(?:INSTRUMENTATION_STATUS: .*\n)*?INSTRUMENTATION_STATUS_CODE: (-?\d+)", text):
    code = int(m.group(2))
    if code == 1:
        continue
    print({0: "  pass", -2: "  FAIL", -1: "  ERROR", -3: "  skip", -4: "  skip"}.get(code, f"  ({code})"), m.group(1))
for line in re.findall(r"INSTRUMENTATION_STATUS: reliability=(.*)", text):
    print("    ·", line)
for msg in re.findall(r"INSTRUMENTATION_STATUS: stack=(?:java\.lang\.)?(\w*(?:Error|Exception): [^\n]*)", text):
    print("    !", msg)
m = re.search(r"(OK \(\d+ tests?\)|Tests run: \d+,\s+Failures: \d+)", text)
print(m.group(1) if m else "!! no test summary — see " + sys.argv[1])
PY
echo "raw output: $OUT/reliability.txt"
