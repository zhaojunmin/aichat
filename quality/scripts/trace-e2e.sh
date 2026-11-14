#!/usr/bin/env bash
# End-to-end timing of the reading / bubble paths on a real phone.
#
#   quality/scripts/trace-e2e.sh [seconds]      (default: 30)
#
# Starts an atrace capture of the app's trace sections (diagnostics/Tracing.kt), waits while
# you use the app — open a chat, scroll, tap the bubble — then stops and prints
# count / P50 / P95 / max per section. Works on the release build (the app is
# profileable). ADB defaults to `adb`; on WSL with a Windows-side device set
# ADB=adb.exe.
#
# Sections: a11y.onEvent (main thread, per accessibility event), a11y.parse
# (node-tree parse on bubble tap, IO thread), bubble.tapToResult (tap → suggestions shown) and its
# stages bubble.resolveConversation / bubble.ocr / bubble.memoryBlock /
# bubble.generate, llm.chat (on-device inference).
#
# Note: tapping the bubble in cloud mode sends that chat to the configured AI
# provider, exactly as in normal use.
set -euo pipefail

ADB=${ADB:-adb}
SECONDS_TO_RECORD=${1:-30}
PKG=com.octos.aichat
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/benchmark-results
mkdir -p "$OUT"
TRACE=$OUT/e2e-trace.txt

"$ADB" shell atrace --async_start -a "$PKG" -b 16384 >/dev/null
echo "recording ${SECONDS_TO_RECORD}s — use the app now (chat screen, scroll, tap the bubble)…"
sleep "$SECONDS_TO_RECORD"
"$ADB" exec-out atrace --async_stop -a "$PKG" > "$TRACE"
python3 -I "$ROOT/quality/scripts/trace_summary.py" "$TRACE"
echo "raw trace: $TRACE"
