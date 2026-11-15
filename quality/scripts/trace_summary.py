#!/usr/bin/env python3
"""Summarize the app's trace sections from an atrace text capture.

    python3 -I quality/scripts/trace_summary.py trace.txt [--prefix a11y. --prefix bubble. ...]
        [--csv history.csv --label <run label>] [--budget]

Reads ftrace `tracing_mark_write` events:
  B|pid|name        sync begin (paired with the next E on the same thread)
  E|pid[|...]       sync end
  S|pid|name|cookie async begin
  F|pid|name|cookie async end
and prints count / P50 / P95 / max (ms) per section name. Only sections whose
name starts with one of the prefixes are shown (default: the app's own
sections, see Tracing.kt).

--csv appends one row per section (label, section, count, p50, p95, max) so runs
can be compared over time; --budget checks the result against the performance
budgets in docs/quality-attributes.md and exits non-zero if one is exceeded.
"""
import argparse
import csv
import os
import re
import sys
from collections import defaultdict

# section -> (percentile, limit ms): the budgets in docs/quality-attributes.md.
BUDGETS = {
    "bubble.tapToResult": [(50, 2500.0), (95, 5000.0)],  # P1: tap → suggestions shown
    "a11y.onEvent": [(95, 8.0)],                          # P4: main thread per event
}

DEFAULT_PREFIXES = ("a11y.", "bubble.", "llm.", "embed.")

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
    ap.add_argument("--csv", help="append the summary rows to this CSV file")
    ap.add_argument("--label", default="", help="run label written into the CSV (time, commit…)")
    ap.add_argument("--budget", action="store_true", help="check the budgets; non-zero exit if exceeded")
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

    if args.csv:
        new = not os.path.exists(args.csv)
        with open(args.csv, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["label", "section", "count", "p50_ms", "p95_ms", "max_ms"])
            for name, vals in rows:
                w.writerow([args.label, name, len(vals), f"{percentile(vals, 50):.2f}",
                            f"{percentile(vals, 95):.2f}", f"{vals[-1]:.2f}"])

    if args.budget:
        found = dict(rows)
        failed = False
        for name, checks in BUDGETS.items():
            vals = found.get(name)
            if not vals:
                print(f"budget {name:28} — no samples")
                continue
            for p, limit in checks:
                v = percentile(vals, p)
                ok = v <= limit
                failed |= not ok
                print(f"budget {name:28} P{p} {v:9.1f} ms  <= {limit:7.0f}  {'PASS' if ok else 'FAIL'}")
        return 1 if failed else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
