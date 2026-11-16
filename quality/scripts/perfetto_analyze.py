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

--html PATH also writes a self-contained page with one flame graph per stage (plus
a11y.onEvent, counted on the thread that ran it): click a frame to zoom, hover for counts.

Needs the `perfetto` Python package (trace_processor); perf-loop.sh docs show the venv.
"""
import argparse
import html
import json
import os
import re
import shutil
import subprocess
import sys
from collections import Counter, defaultdict

from perfetto.trace_processor import TraceProcessor

STAGES = ("bubble.resolveConversation", "bubble.ocr", "bubble.memoryBlock", "bubble.generate")
OUTER = "bubble.tapToResult"
EVENT = "a11y.onEvent"  # sync, main thread: only that thread's samples count
LINE = re.compile(r"-(\d+)\s+(?:\(\s*\d+\)\s+)?\[\d+\]\s+\S+\s+([\d.]+):\s+tracing_mark_write:\s+(.*)$")


def sections(path):
    """[(name, start_s, end_s, tid)] for the app's sync and async sections (tid None if async)."""
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
                out.append((name, start, ts, int(tid)))
            elif parts[0] == "S" and len(parts) >= 4:
                open_async[(parts[2], parts[3])] = ts
            elif parts[0] == "F" and len(parts) >= 4:
                start = open_async.pop((parts[2], parts[3]), None)
                if start is not None:
                    out.append((parts[2], start, ts, None))
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
    ap.add_argument("--html", help="also write flame graphs per stage to this file")
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
    tids = {r.utid: r.tid for r in tp.query("select utid, tid from thread")}
    samples = [(r.ts / 1e9, r.callsite_id, tids.get(r.utid)) for r in tp.query(
        "select ts, callsite_id, utid from perf_sample where callsite_id is not null")]
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

    events = [s for s in secs if s[0] == EVENT]
    by_stage = defaultdict(list)
    for t, cs, tid in samples:
        st = stage_of(t)
        if st:
            by_stage[st].append(stack(cs))
        if any(e[1] <= t <= e[2] and e[3] == tid for e in events):
            by_stage[EVENT].append(stack(cs))

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

    if args.html:
        order = list(STAGES) + [OUTER + " (other)", EVENT]
        walls = defaultdict(float)
        for s in secs:
            walls[s[0]] += (s[2] - s[1]) * 1000
        pages = [(st, len([e for e in secs if e[0] == st]), walls[st], by_stage.get(st, []))
                 for st in order]
        write_html(args.html, pages, len(taps), total_tap_ms / len(taps), classes, methods)
        print(f"\nflame graphs: {args.html}")
    return 0


def demangler(names):
    """{mangled: readable} for the C++ symbols in [names], via c++filt when installed."""
    mangled = sorted({n for n in names if n and n.startswith("_Z")})
    tool = shutil.which("c++filt") or shutil.which("llvm-cxxfilt")
    if not mangled or not tool:
        return {}
    out = subprocess.run([tool], input="\n".join(mangled), capture_output=True, text=True).stdout
    return dict(zip(mangled, out.splitlines()))


def kind_of(name, lib):
    lib = os.path.basename(lib or "")
    if "com.octos.aichat" in name:
        return "app"
    if "base.apk" in lib or lib in ("libembedjni.so", "libllmjni.so"):
        return "native-app"  # MNN, ML Kit OCR, our JNI
    if "." in name and not name.startswith("_Z") and "(" not in name and "::" not in name:
        return "java"
    return "system"


def write_html(path, pages, taps, mean_ms, classes, methods):
    """One icicle (root at the top) per stage; frames merge by readable name."""
    names = {n for _, _, _, stacks in pages for stk in stacks for n, _ in stk}
    readable = demangler(names)

    def label(name, lib):
        name = readable.get(name, name)
        return pretty(name, lib, classes, methods)

    data = []
    for stage, count, wall, stacks in pages:
        root = {"n": stage, "v": 0, "k": "root", "c": {}}
        selfs = Counter()
        for stk in stacks:
            root["v"] += 1
            node = root
            for name, lib in reversed(stk):  # outermost frame first
                key = label(name, lib)
                child = node["c"].get(key)
                if child is None:
                    child = node["c"][key] = {"n": key, "v": 0, "k": kind_of(key, lib), "c": {}}
                child["v"] += 1
                node = child
            selfs[label(*stk[0])] += 1

        def plain(nd):
            kids = sorted(nd["c"].values(), key=lambda c: -c["v"])
            return {"n": nd["n"], "v": nd["v"], "k": nd["k"], "c": [plain(c) for c in kids]}

        data.append({
            "stage": stage, "count": count, "wall": round(wall), "samples": len(stacks),
            "tree": plain(root),
            "top": [[fn, c] for fn, c in selfs.most_common(15)],
        })
    page = HTML.replace("__TITLE__", html.escape(f"{taps} taps, {mean_ms:.0f} ms mean"))
    page = page.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    with open(path, "w", encoding="utf-8") as f:
        f.write(page)


HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tap Hot Stacks</title>
<style>
:root { --bg:#fbfbfa; --fg:#1d1d1b; --muted:#6b6b66; --line:#deded9; --card:#fff;
  --app:#d9480f; --native-app:#e8a33d; --java:#4c8bd6; --system:#9aa39b; --root:#5d5d58; }
@media (prefers-color-scheme: dark) { :root { --bg:#161615; --fg:#ececea; --muted:#9b9b95;
  --line:#33332f; --card:#1f1f1d; --system:#6e776f; --root:#8a8a84; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--fg);
  font:14px/1.5 -apple-system, "PingFang SC", "Noto Sans SC", system-ui, sans-serif; }
main { max-width:1200px; margin:0 auto; padding:24px 16px 48px; }
h1 { font-size:20px; margin:0 0 4px; } .sub { color:var(--muted); margin:0 0 16px; }
.tabs { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px; }
.tabs button { border:1px solid var(--line); background:var(--card); color:var(--fg);
  padding:6px 10px; border-radius:6px; cursor:pointer; font:inherit; }
.tabs button.on { border-color:var(--fg); font-weight:600; }
.stats { color:var(--muted); margin:4px 0 10px; }
.legend { display:flex; gap:14px; flex-wrap:wrap; color:var(--muted); font-size:12px; margin-bottom:8px; }
.legend i { display:inline-block; width:10px; height:10px; border-radius:2px; margin-right:4px; vertical-align:-1px; }
#crumbs { font-size:12px; color:var(--muted); min-height:18px; margin-bottom:4px; }
#crumbs a { color:inherit; cursor:pointer; text-decoration:underline; }
#flame { position:relative; width:100%; overflow:hidden; border:1px solid var(--line);
  border-radius:6px; background:var(--card); }
.f { position:absolute; height:19px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis;
  font:11px/19px ui-monospace, Menlo, monospace; color:#111; padding:0 3px; cursor:pointer;
  border-right:1px solid var(--card); border-bottom:1px solid var(--card); }
.f:hover { filter:brightness(1.12); }
#tip { position:fixed; pointer-events:none; background:var(--fg); color:var(--bg); padding:6px 8px;
  border-radius:4px; font-size:12px; max-width:560px; display:none; z-index:2; word-break:break-all; }
table { border-collapse:collapse; width:100%; margin-top:16px; font-size:13px; }
td, th { border-bottom:1px solid var(--line); padding:4px 6px; text-align:left; vertical-align:top; }
td.n { text-align:right; white-space:nowrap; font-variant-numeric:tabular-nums; width:70px; }
td.fn { font-family:ui-monospace, Menlo, monospace; font-size:12px; word-break:break-all; }
</style></head><body><main>
<h1>Tap Hot Stacks</h1>
<p class="sub">Perfetto 1 kHz callstack samples by tap stage · __TITLE__ · 1 sample ≈ 1 ms CPU on one thread</p>
<div class="tabs" id="tabs"></div>
<div class="stats" id="stats"></div>
<div class="legend"><span><i style="background:var(--app)"></i>App code</span>
<span><i style="background:var(--native-app)"></i>Bundled native (MNN / ML Kit / JNI)</span>
<span><i style="background:var(--java)"></i>Java / framework</span>
<span><i style="background:var(--system)"></i>System native</span></div>
<div id="crumbs"></div><div id="flame"></div>
<h3 style="margin:20px 0 0;font-size:15px">Hottest functions (self)</h3>
<table id="top"></table>
</main><div id="tip"></div>
<script>
const DATA = __DATA__;
const H = 20, tabs = document.getElementById("tabs"), flame = document.getElementById("flame");
const tip = document.getElementById("tip"), crumbs = document.getElementById("crumbs");
let cur = 0;
function color(k) { return getComputedStyle(document.documentElement).getPropertyValue("--" + k); }
function esc(s) { return s.replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c])); }
function show(i) {
  cur = i; [...tabs.children].forEach((b, j) => b.classList.toggle("on", j === i));
  const d = DATA[i];
  document.getElementById("stats").textContent =
    d.samples + " samples · " + d.count + " sections · " + d.wall + " ms wall in total";
  document.getElementById("top").innerHTML = "<tr><th>Samples</th><th>%</th><th>Function</th></tr>" +
    d.top.map(([f, c]) => "<tr><td class=n>" + c + "</td><td class=n>" +
      (100 * c / Math.max(d.samples, 1)).toFixed(1) + "</td><td class=fn>" + esc(f) + "</td></tr>").join("");
  zoom([d.tree]);
}
function zoom(path) {
  const top = path[path.length - 1], total = DATA[cur].tree.v || 1;
  crumbs.innerHTML = path.map((n, j) => j === path.length - 1 ? esc(n.n) :
    "<a data-j=" + j + ">" + esc(n.n) + "</a>").join(" › ");
  crumbs.querySelectorAll("a").forEach(a => a.onclick = () => zoom(path.slice(0, +a.dataset.j + 1)));
  flame.innerHTML = ""; let depth = 0;
  const frag = document.createDocumentFragment();
  (function draw(n, x, w, dep) {
    if (w < 0.0015) return;
    depth = Math.max(depth, dep);
    const el = document.createElement("div");
    el.className = "f"; el.textContent = n.n;
    el.style.left = (x * 100) + "%"; el.style.width = (w * 100) + "%"; el.style.top = (dep * H) + "px";
    el.style.background = color(n.k);
    el.onmousemove = e => { tip.style.display = "block";
      tip.innerHTML = esc(n.n) + "<br>" + n.v + " samples · " + (100 * n.v / total).toFixed(1) + "% of stage";
      tip.style.left = Math.min(e.clientX + 12, innerWidth - 580) + "px"; tip.style.top = (e.clientY + 14) + "px"; };
    el.onmouseleave = () => tip.style.display = "none";
    el.onclick = () => { const p = []; let found = false;
      (function walk(q, acc) { if (found) return; acc = acc.concat(q);
        if (q === n) { p.push(...acc); found = true; return; } q.c.forEach(c => walk(c, acc)); })(DATA[cur].tree, []);
      zoom(p); };
    frag.appendChild(el);
    let cx = x;
    n.c.forEach(c => { const cw = w * c.v / (n.v || 1); draw(c, cx, cw, dep + 1); cx += cw; });
  })(top, 0, 1, 0);
  flame.appendChild(frag); flame.style.height = ((depth + 1) * H + 2) + "px";
}
DATA.forEach((d, i) => { const b = document.createElement("button");
  b.textContent = d.stage + " (" + d.samples + ")"; b.onclick = () => show(i); tabs.appendChild(b); });
show(0);
</script></body></html>
"""


if __name__ == "__main__":
    sys.exit(main())
