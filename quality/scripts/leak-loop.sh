#!/usr/bin/env bash
# Memory-leak loop on a real phone: churn the app's lifecycles over and over, dump the Java
# heap with Perfetto between rounds, and report what keeps growing (heap_leak.py).
#
#   quality/scripts/leak-loop.sh [cycles] [dumps]   # default 10 cycles between dumps, 3 dumps
#
# One cycle:
#   1. open the app's home screen, then Home              MainActivity created / destroyed
#   2. accessibility off → bubble gone → on → bubble back  BubbleController destroyed / created
#   3. TAP=1: tap the bubble in the reference chat, Home   panel, capture, suggestion generation
# Two warm-up cycles run first (first-use caches are not leaks), then a heap dump after every
# [cycles] cycles. Output: build/leak-results/heap-<n>.pftrace and the report.
#
# Safety — the phone is someone's phone:
#   - Step 3 needs perf-loop.sh's reference title strip (`perf-loop.sh reference` with the
#     test chat open) and aborts if the screen is not that chat. TAP=0 skips it.
#   - The suggestions panel is closed with Home, never by tapping.
#   - In cloud mode every tap sends the chat to the AI provider, as in normal use.
#   - Step 2 switches the accessibility service off; the original setting is restored on exit.
#   - A heap dump is the app's whole Java memory — chat text, the API key. The dumps are
#     deleted once the report is written; KEEP=1 keeps them for a closer look.
#
# Env: ADB (default adb; adb.exe on WSL), TAP (default 1), BUBBLE_X/BUBBLE_Y (default
# 141/990 px), WAIT (seconds per tap, default 12), CHAT_PKG (default WeChat), PY (Python with
# the `perfetto` package, default build/perfetto-venv/bin/python), MAPPING (R8 mapping of the
# installed build, default app/build/outputs/mapping/release/mapping.txt), KEEP=1 to keep the dumps.
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.octos.aichat
CYCLES=${1:-10}
DUMPS=${2:-3}
TAP=${TAP:-1}
CHAT_PKG=${CHAT_PKG:-com.tencent.mm}
BUBBLE_X=${BUBBLE_X:-141}
BUBBLE_Y=${BUBBLE_Y:-990}
WAIT=${WAIT:-12}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/leak-results
REF=$ROOT/build/benchmark-results/perf-reference-title.png
PY=${PY:-$ROOT/build/perfetto-venv/bin/python}
MAPPING=${MAPPING:-$ROOT/app/build/outputs/mapping/release/mapping.txt}
GUARD="python3 -I $ROOT/quality/scripts/perf_guard.py"
HEAP_CONFIG=$ROOT/quality/perfetto/java-heap.pbtxt
HEAP_DEVICE=/data/misc/perfetto-traces/leak-heap.pftrace
A11Y_SERVICE=$PKG/$PKG.accessibility.ChatAccessibilityService
mkdir -p "$OUT"

if [ "$TAP" = 1 ] && [ ! -f "$REF" ]; then
  echo "no reference chat — run: quality/scripts/perf-loop.sh reference (or TAP=0)"
  exit 2
fi

get() { "$ADB" shell "$@" | tr -d '\r'; }
A11Y=$(get settings get secure enabled_accessibility_services)
restore() {
  if [[ -n $A11Y && $A11Y != null ]]; then
    "$ADB" shell settings put secure enabled_accessibility_services "$A11Y" || true
    "$ADB" shell settings put secure accessibility_enabled 1 || true
  fi
}
trap restore EXIT

# The bubble is an overlay window of the app (not a service).
bubble_showing() {
  local windows
  windows=$("$ADB" exec-out dumpsys window windows)
  awk -v pkg="$PKG" 'BEGIN { RS = "Window #" }
      { split($0, head, "\n") }
      index(head[1], pkg) && /APPLICATION_OVERLAY/ { found = 1 }
      END { exit !found }' <<<"$windows"
}

wait_for() { # wait_for <what> <seconds> <command...>
  local what=$1 limit=$2
  shift 2
  for _ in $(seq 1 $((limit * 2))); do
    if "$@"; then return 0; fi
    sleep 0.5
  done
  echo "timed out after ${limit}s: $what"
  exit 4
}
bubble_gone() { ! bubble_showing; }

cycle() {
  # 1. activity
  "$ADB" shell am start -W -f 0x10008000 -n "$PKG/.ui.MainActivity" >/dev/null
  sleep 1.5
  "$ADB" shell input keyevent KEYCODE_HOME
  sleep 1
  # 2. bubble lifecycle, following the accessibility service
  local others
  others=$(tr ':' '\n' <<<"$A11Y" | grep -vx "$A11Y_SERVICE" | paste -sd: || true)
  if [ -n "$others" ]; then
    "$ADB" shell settings put secure enabled_accessibility_services "$others"
  else
    "$ADB" shell settings delete secure enabled_accessibility_services >/dev/null
  fi
  wait_for "bubble removed with accessibility off" 15 bubble_gone
  restore
  wait_for "bubble back with accessibility on" 20 bubble_showing
  # 3. panel
  if [ "$TAP" = 1 ]; then
    "$ADB" shell monkey -p "$CHAT_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 2.5
    if ! "$ADB" exec-out screencap -p | $GUARD check "$REF"; then
      "$ADB" shell input keyevent KEYCODE_HOME
      echo "aborted: screen is not the reference chat"
      exit 3
    fi
    "$ADB" shell input tap "$BUBBLE_X" "$BUBBLE_Y"
    sleep "$WAIT"
    "$ADB" shell input keyevent KEYCODE_HOME # close the panel without touching it
    sleep 2
  fi
}

dump() { # dump <n>
  sleep 3 # let the last cycle's work settle
  "$ADB" shell perfetto --txt -c - -o "$HEAP_DEVICE" < "$HEAP_CONFIG" >/dev/null 2>&1
  "$ADB" exec-out cat "$HEAP_DEVICE" > "$OUT/heap-$1.pftrace"
  "$ADB" shell rm -f "$HEAP_DEVICE"
  echo "heap dump $1: $OUT/heap-$1.pftrace ($(wc -c < "$OUT/heap-$1.pftrace") bytes)"
}

bubble_showing || { echo "the bubble isn't showing — are accessibility and the overlay permission on?"; exit 3; }
echo "warm-up: 2 cycles"
cycle
cycle
traces=()
for n in $(seq 0 $((DUMPS - 1))); do
  if [ "$n" -gt 0 ]; then
    for c in $(seq 1 "$CYCLES"); do
      cycle
      echo "round $n: cycle $c/$CYCLES done"
    done
  fi
  dump "$n"
  traces+=("$OUT/heap-$n.pftrace")
done

map=()
if [ -f "$MAPPING" ]; then map=(--mapping "$MAPPING"); else echo "no R8 mapping: class names stay obfuscated"; fi
status=0
"$PY" "$ROOT/quality/scripts/heap_leak.py" "${traces[@]}" --cycles "$CYCLES" "${map[@]}" \
  > "$OUT/report.txt" || status=$?
cat "$OUT/report.txt"
[ "${KEEP:-0}" = 1 ] || rm -f "${traces[@]}"
exit $status
