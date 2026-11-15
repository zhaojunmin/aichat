#!/usr/bin/env python3
"""Where does the CPU go in each stage of a bubble tap?

    build/perfetto-venv/bin/python quality/scripts/perfetto_analyze.py \\
        build/benchmark-results/perf-round-1.txt build/benchmark-results/perf-round-1.pftrace \\
        [--mapping app/build/outputs/mapping/release/mapping.txt] [--top 12]

Joins the two captures perf-loop.sh (PROFILE=1) takes of the same taps:
  - the atrace text: the app's own sections (bubble.tapToResult and its stages);
  - the Perfetto trace: 1 kHz callstack samples of the app (quality/perfetto/tap-profile.pbtxt).
Both use the boot clock, so every sample is attributed to the innermost stage running at
that moment. For each stage it prints the sample count (~CPU ms per busy thread), the
libraries, and the hottest functions — self (leaf frame) and inclusive (anywhere on the
stack). --mapping de-obfuscates R8 class/method names in Java frames.

Needs the `perfetto` Python package (trace_processor); perf-loop.sh docs show the venv.
"""
import argparse
import os
import re
import sys
from collections import Counter, defaultdict

from perfetto.trace_processor import TraceProcessor

STAGES = ("bubble.resolveConversation", "bubble.ocr", "bubble.memoryBlock", "bubble.generate")
OUTER = "bubble.tapToResult"
LINE = re.compile(r"-(\d+)\s+(?:\(\s*\d+\)\s+)?\[\d+\]\s+\S+\s+([\d.]+):\s+tracing_mark_write:\s+(.*)$")


def sections(path):
    """[(name, start_s, end_s)] for the app's sync and async sections."""
    out, stacks, open_async = [], defaultdict(list), {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE.search(line)
            if not m:
                continue
            tid, ts, parts = m.group(1), float(m.group(2)), m.group(3).strip().split("|")
            if parts[0] == "B" and len(parts) >= 3:
                stacks[tid].append((parts[2], ts))
            elif parts[0] == "E" and stacks[tid]:
                name, start = stacks[tid].pop()
                out.append((name, start, ts))
            elif parts[0] == "S" and len(parts) >= 4:
                open_async[(parts[2], parts[3])] = ts
            elif parts[0] == "F" and len(parts) >= 4:
                start = open_async.pop((parts[2], parts[3]), None)
                if start is not None:
                    out.append((parts[2], start, ts))
    return out


def load_mapping(path):
    """R8 mapping.txt → {obfuscated class: original}, {(obf class, obf method): original}."""
    classes, methods, current = {}, {}, None
    if not path:
        return classes, methods
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if not line.startswith(" ") and "->" in line:
                orig, obf = line.rstrip(":\n").split(" -> ")
                classes[obf.strip()] = orig.strip()
                current = obf.strip()
            elif current and "->" in line and "(" in line:
                left, obf = line.strip().split(" -> ")
                name = left.split("(")[0].split()[-1].split(":")[-1]
                methods[(current, obf.strip())] = name
    return classes, methods


def pretty(frame, mapping_lib, classes, methods):
    if frame:
        cls, _, meth = frame.rpartition(".")
        if cls in classes:
            return f"{classes[cls]}.{methods.get((cls, meth), meth)}"
        return frame
    return f"[{os.path.basename(mapping_lib or '?')}]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("atrace")
    ap.add_argument("pftrace")
    ap.add_argument("--mapping")
    ap.add_argument("--top", type=int, default=12)
    args = ap.parse_args()

    secs = sections(args.atrace)
    taps = [s for s in secs if s[0] == OUTER]
    stages = [s for s in secs if s[0] in STAGES]
    if not taps:
        print("no bubble.tapToResult sections in the atrace capture")
        return 1
    classes, methods = load_mapping(args.mapping)

    tp = TraceProcessor(trace=args.pftrace)
    frames = {r.id: (r.name, r.mapping) for r in tp.query(
        "select f.id, f.name, m.name as mapping from stack_profile_frame f "
        "join stack_profile_mapping m on m.id = f.mapping")}
    callsites = {r.id: (r.parent_id, r.frame_id) for r in tp.query(
        "select id, parent_id, frame_id from stack_profile_callsite")}
    # Only the target process is unwound, so samples with a callstack are the app's.
    samples = [(r.ts / 1e9, r.callsite_id) for r in tp.query(
        "select ts, callsite_id from perf_sample where callsite_id is not null")]
    tp.close()

    def stack(cs):
        out = []
        while cs is not None:
            parent, frame = callsites[cs]
            out.append(frames[frame])
            cs = parent
        return out  # leaf first

    def stage_of(t):
        inner = [s for s in stages if s[1] <= t <= s[2]]
        if inner:
            return min(inner, key=lambda s: s[2] - s[1])[0]
        if any(s[1] <= t <= s[2] for s in taps):
            return OUTER + " (other)"
        return None

    by_stage = defaultdict(list)
    for t, cs in samples:
        st = stage_of(t)
        if st:
            by_stage[st].append(stack(cs))

    total_tap_ms = sum((s[2] - s[1]) * 1000 for s in taps)
    print(f"{len(taps)} taps, {total_tap_ms / len(taps):.0f} ms mean wall time; "
          f"{len(samples)} app samples in the trace")
    for st in list(STAGES) + [OUTER + " (other)"]:
        stacks = by_stage.get(st, [])
        wall = sum((s[2] - s[1]) * 1000 for s in stages if s[0] == st)
        print(f"\n=== {st}: {len(stacks)} samples (~CPU ms), wall {wall:.0f} ms over all taps")
        if not stacks:
            continue
        libs, self_fn, incl_fn = Counter(), Counter(), Counter()
        for stk in stacks:
            leaf_name, leaf_lib = stk[0]
            libs[os.path.basename(leaf_lib or "?")] += 1
            self_fn[pretty(leaf_name, leaf_lib, classes, methods)] += 1
            for name in {pretty(n, lib, classes, methods) for n, lib in stk}:
                incl_fn[name] += 1
        n = len(stacks)
        print("  libraries (self):")
        for lib, c in libs.most_common(6):
            print(f"    {c * 100 / n:5.1f}%  {lib}")
        print("  hottest functions, self:")
        for fn, c in self_fn.most_common(args.top):
            print(f"    {c * 100 / n:5.1f}%  {fn[:110]}")
        print("  hottest functions, inclusive:")
        for fn, c in incl_fn.most_common(args.top):
            print(f"    {c * 100 / n:5.1f}%  {fn[:110]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
