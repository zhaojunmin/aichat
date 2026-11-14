#!/usr/bin/env python3
"""Summarize the app's trace sections from an atrace text capture.

    python3 -I quality/scripts/trace_summary.py trace.txt [--prefix a11y. --prefix bubble. ...]

Reads ftrace `tracing_mark_write` events:
  B|pid|name        sync begin (paired with the next E on the same thread)
  E|pid[|...]       sync end
  S|pid|name|cookie async begin
  F|pid|name|cookie async end
and prints count / P50 / P95 / max (ms) per section name. Only sections whose
name starts with one of the prefixes are shown (default: the app's own
sections, see Tracing.kt).
"""
import argparse
import re
import sys
from collections import defaultdict

DEFAULT_PREFIXES = ("a11y.", "bubble.", "llm.")

# "  comm-1234  (  567) [003] ..... 12345.678901: tracing_mark_write: B|567|name"
LINE = re.compile(r"-(\d+)\s+(?:\(\s*\d+\)\s+)?\[\d+\]\s+\S+\s+([\d.]+):\s+tracing_mark_write:\s+(.*)$")


def percentile(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def parse(lines):
    durations = defaultdict(list)          # name -> [ms]
    stacks = defaultdict(list)             # tid -> [(name, ts)]
    async_open = {}                        # (pid, name, cookie) -> ts
    for line in lines:
        m = LINE.search(line)
        if not m:
            continue
        tid, ts, payload = m.group(1), float(m.group(2)), m.group(3).strip()
        parts = payload.split("|")
        kind = parts[0]
        if kind == "B" and len(parts) >= 3:
            stacks[tid].append((parts[2], ts))
        elif kind == "E":
            if stacks[tid]:
                name, start = stacks[tid].pop()
                durations[name].append((ts - start) * 1000)
        elif kind == "S" and len(parts) >= 4:
            async_open[(parts[1], parts[2], parts[3])] = ts
        elif kind == "F" and len(parts) >= 4:
            start = async_open.pop((parts[1], parts[2], parts[3]), None)
            if start is not None:
                durations[parts[2]].append((ts - start) * 1000)
    return durations


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--prefix", action="append", help="section name prefix to show (repeatable)")
    args = ap.parse_args()
    prefixes = tuple(args.prefix) if args.prefix else DEFAULT_PREFIXES

    with open(args.trace, encoding="utf-8", errors="replace") as f:
        durations = parse(f)

    rows = sorted((n, sorted(v)) for n, v in durations.items() if n.startswith(prefixes))
    if not rows:
        print("no matching sections — was the app traced (atrace -a com.octos.aichat) and exercised?")
        return 1
    print(f"{'section':32} {'count':>6} {'p50 ms':>9} {'p95 ms':>9} {'max ms':>9}")
    for name, vals in rows:
        print(f"{name:32} {len(vals):>6} {percentile(vals, 50):>9.2f} {percentile(vals, 95):>9.2f} {vals[-1]:>9.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
