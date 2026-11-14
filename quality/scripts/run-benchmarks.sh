#!/usr/bin/env bash
# Run the on-device performance tests and print a summary.
#
#   quality/scripts/run-benchmarks.sh [micro|macro|all]     (default: all)
#
# Needs one connected phone. ADB defaults to `adb`; on WSL with a Windows-side
# device set ADB=adb.exe. Builds with the local release signing key (see
# local.properties) so installs replace the existing app *without* wiping its
# data (API key, memories, downloaded models).
#
# Side effects on the phone, all restored at the end:
#  - the app is temporarily replaced by the unminified "benchmark" build (micro),
#    then the release build is reinstalled;
#  - macrobenchmarks force-stop the app for cold starts, and this ROM removes a
#    force-stopped app from the enabled accessibility services — we re-enable it.
set -euo pipefail

ADB=${ADB:-adb}
WHAT=${1:-all}
PKG=com.octos.aichat
A11Y_SVC=$PKG/$PKG.accessibility.ChatAccessibilityService
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/benchmark-results
mkdir -p "$OUT"

# adb.exe wants Windows paths.
apk_path() { if [[ $ADB == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi; }
install() { "$ADB" install -r -t "$(apk_path "$1")" | tail -1; }

restore_a11y() {
    "$ADB" shell settings put secure enabled_accessibility_services "$A11Y_SVC"
    "$ADB" shell settings put secure accessibility_enabled 1
}

# Pull "<name>  <metric> min/median/max" style lines out of `am instrument -r` output.
summarize() {
    python3 - "$1" <<'EOF'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for block in re.findall(r"android\.studio\.display\.benchmark=(.*?)(?=\nINSTRUMENTATION_STATUS: android\.studio\.v2display)", text, re.S):
    print(re.sub(r"\]\(file://[^)]*\)", "]", re.sub(r"\[(Trace|Method Trace)\]\(file://[^)]*\)\s*", "", block)).rstrip())
m = re.search(r"(OK \(\d+ tests?\)|Tests run: \d+,\s+Failures: \d+)", text)
print(m.group(1) if m else "!! no test summary — see " + sys.argv[1])
EOF
}

cd "$ROOT"

if [[ $WHAT == micro || $WHAT == all ]]; then
    echo "== microbenchmarks (app androidTest, benchmark build type)"
    ./gradlew -q :app:assembleBenchmark :app:assembleBenchmarkAndroidTest
    install app/build/outputs/apk/benchmark/app-benchmark.apk
    install app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk
    # The Gradle benchmark plugin normally does this; benchmarks refuse to run JIT-only.
    "$ADB" shell cmd package compile -m speed -f "$PKG" | tail -1
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell am instrument -w -r -e androidx.benchmark.output.enable true \
        "$PKG.test/androidx.benchmark.junit4.AndroidBenchmarkRunner" > "$OUT/micro.txt" 2>&1 || true
    summarize "$OUT/micro.txt"
fi

if [[ $WHAT == macro || $WHAT == all ]]; then
    echo "== macrobenchmarks (release build: startup, scroll)"
    ./gradlew -q :app:assembleRelease :quality:macrobenchmark:assembleRelease
    install app/build/outputs/apk/release/app-release.apk
    install quality/macrobenchmark/build/outputs/apk/release/macrobenchmark-release.apk
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell am instrument -w -r "$PKG.macrobenchmark/androidx.test.runner.AndroidJUnitRunner" \
        > "$OUT/macro.txt" 2>&1 || true
    restore_a11y
    summarize "$OUT/macro.txt"
fi

# Leave the phone on the normal release build, with test APKs and traces removed.
if [[ $WHAT == micro ]]; then
    ./gradlew -q :app:assembleRelease
    install app/build/outputs/apk/release/app-release.apk
fi
"$ADB" uninstall "$PKG.test" >/dev/null 2>&1 || true
"$ADB" uninstall "$PKG.macrobenchmark" >/dev/null 2>&1 || true
"$ADB" shell rm -rf "/sdcard/Android/media/$PKG" "/sdcard/Android/media/$PKG.macrobenchmark" || true
restore_a11y
echo "raw output: $OUT"
