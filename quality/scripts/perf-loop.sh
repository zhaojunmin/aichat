#!/usr/bin/env bash
# Closed-loop performance run on a real phone: tap the bubble repeatedly in a chat you
# chose, trace every tap, summarise per section, check the budgets, and append the
# result to a history CSV so runs (and code changes) can be compared.
#
#   quality/scripts/perf-loop.sh reference          # once, with the test chat open on the phone
#   quality/scripts/perf-loop.sh run [taps] [rounds] # default 5 taps, 1 round
#   quality/scripts/perf-loop.sh switch [times]      # app switching only, no taps (default 10)
#
# Output: build/benchmark-results/perf-history.csv (one row per section per round),
#         build/benchmark-results/perf-round-<n>.txt (raw atrace of the last run).
#
# Safety — the phone is someone's phone:
#   - Before every tap the chat's title bar is compared with the reference strip saved by
#     `reference`; anything else on screen (another chat, the chat list, another app, a
#     dialog) aborts the run. Only that strip is kept; full screenshots never hit disk.
#   - The suggestions panel is closed with Home, never by tapping — a tap could pick one.
#   - Use local inference mode so nothing read leaves the phone. In cloud mode the chat is
#     sent to the AI provider, exactly as in normal use.
#   - Leaving the chat app is also when the app learns (learnIfDue); in local mode that
#     inference can overlap the next tap, as it would in real use.
#
# Env: ADB (default adb; adb.exe on WSL), BUBBLE_X/BUBBLE_Y (where the bubble sits,
# default 141/990 px), WAIT (seconds per tap, default 15), CHAT_PKG (default WeChat),
# PROFILE=1 to also sample callstacks with Perfetto (quality/perfetto/tap-profile.pbtxt;
# needs `adb shell setprop persist.traced_perf.enable 1`) — analyse with
# quality/scripts/perfetto_analyze.py.
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.octos.aichat
CHAT_PKG=${CHAT_PKG:-com.tencent.mm}
BUBBLE_X=${BUBBLE_X:-141}
BUBBLE_Y=${BUBBLE_Y:-990}
WAIT=${WAIT:-15}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/benchmark-results
REF=$OUT/perf-reference-title.png
PROFILE=${PROFILE:-0}
PF_CONFIG=$ROOT/quality/perfetto/tap-profile.pbtxt
PF_DEVICE=/data/misc/perfetto-traces/perf-loop.pftrace
GUARD="python3 -I $ROOT/quality/scripts/perf_guard.py"
mkdir -p "$OUT"

open_chat_app() {
  "$ADB" shell monkey -p "$CHAT_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2.5
}

case "${1:-}" in
  reference)
    open_chat_app
    "$ADB" exec-out screencap -p | $GUARD save "$REF"
    echo "check that $REF shows the test chat's title, then: $0 run"
    ;;
  run)
    TAPS=${2:-5}
    ROUNDS=${3:-1}
    [ -f "$REF" ] || { echo "no reference — run: $0 reference"; exit 2; }
    SHA=$(git -C "$ROOT" rev-parse --short HEAD)
    # `|| true`: grep -m1 stops early, dumpsys then dies of SIGPIPE, and pipefail would end the run.
    INSTALLED=$("$ADB" shell dumpsys package "$PKG" | grep -m1 lastUpdateTime | tr -d '\r' | sed 's/.*=//' || true)
    status=0
    # Launch the app once so the bubble service is up (e.g. after a reinstall), then check it.
    "$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
    sleep 3
    "$ADB" shell input keyevent KEYCODE_HOME
    # Capture first: `dumpsys | grep -q` lets grep exit early, dumpsys dies of SIGPIPE, and
    # pipefail would turn a match into a failure.
    services=$("$ADB" shell dumpsys activity services "$PKG")
    if ! grep -q "FloatingBubbleService" <<<"$services"; then
      echo "the bubble service isn't running — is the overlay permission on?"
      exit 3
    fi
    for round in $(seq 1 "$ROUNDS"); do
      TRACE=$OUT/perf-round-$round.txt
      if [ "$PROFILE" = 1 ]; then
        "$ADB" shell perfetto --txt -c - -o "$PF_DEVICE" --detach=perfloop < "$PF_CONFIG" >/dev/null
      fi
      "$ADB" shell atrace --async_start -a "$PKG" -b 16384 >/dev/null
      for tap in $(seq 1 "$TAPS"); do
        open_chat_app
        if ! "$ADB" exec-out screencap -p | $GUARD check "$REF"; then
          "$ADB" exec-out atrace --async_stop >/dev/null 2>&1 || true
          if [ "$PROFILE" = 1 ]; then
            "$ADB" shell perfetto --attach=perfloop --stop >/dev/null 2>&1 || true
          fi
          "$ADB" shell input keyevent KEYCODE_HOME
          echo "aborted at round $round tap $tap: screen is not the reference chat"
          exit 3
        fi
        "$ADB" shell input tap "$BUBBLE_X" "$BUBBLE_Y"
        sleep "$WAIT"
        "$ADB" shell input keyevent KEYCODE_HOME # close the panel without touching it
        sleep 2
        echo "round $round: tap $tap/$TAPS done"
      done
      # exec-out: `adb shell` would turn \n into \r\n on Windows and garble the output.
      "$ADB" exec-out atrace --async_stop > "$TRACE"
      if [ "$PROFILE" = 1 ]; then
        "$ADB" shell perfetto --attach=perfloop --stop >/dev/null 2>&1 || true
        "$ADB" exec-out cat "$PF_DEVICE" > "$OUT/perf-round-$round.pftrace"
        "$ADB" shell rm -f "$PF_DEVICE"
        echo "callstacks: $OUT/perf-round-$round.pftrace (app pid $("$ADB" shell pidof "$PKG" | tr -d '\r'))"
      fi
      LABEL="$(date '+%F %T') sha=$SHA installed=$INSTALLED taps=$TAPS round=$round"
      echo "== $LABEL"
      python3 -I "$ROOT/quality/scripts/trace_summary.py" "$TRACE" \
        --csv "$OUT/perf-history.csv" --label "$LABEL" --budget || status=1
    done
    exit $status
    ;;
  switch)
    # Bring the chat app to the front and leave it, repeatedly: exercises the foreground
    # tracking (a11y.onEvent / a11y.foregroundCheck) without tapping, so nothing is read
    # or sent anywhere.
    TIMES=${2:-10}
    TRACE=$OUT/perf-switch.txt
    SHA=$(git -C "$ROOT" rev-parse --short HEAD)
    "$ADB" shell atrace --async_start -a "$PKG" -b 16384 >/dev/null
    for i in $(seq 1 "$TIMES"); do
      open_chat_app
      "$ADB" shell input keyevent KEYCODE_HOME
      sleep 1.5
    done
    "$ADB" exec-out atrace --async_stop > "$TRACE"
    LABEL="$(date '+%F %T') sha=$SHA switch=$TIMES"
    echo "== $LABEL"
    python3 -I "$ROOT/quality/scripts/trace_summary.py" "$TRACE" --prefix a11y. \
      --csv "$OUT/perf-history.csv" --label "$LABEL" --budget || exit 1
    ;;
  *)
    sed -n '2,13p' "$0"
    exit 2
    ;;
esac
