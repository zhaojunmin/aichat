#!/usr/bin/env python3
"""Is the app leaking? Compare Java heap dumps taken between rounds of the same churn.

    build/perfetto-venv/bin/python quality/scripts/heap_leak.py \\
        build/leak-results/heap-0.pftrace heap-1.pftrace heap-2.pftrace \\
        [--cycles 10] [--mapping app/build/outputs/mapping/release/mapping.txt]

The dumps come from leak-loop.sh: the same cycles (activity, bubble, panel) run between
each pair. For every class it counts the objects of the app heap that are still reachable
(trace processor works that out from the GC roots, so uncollected garbage doesn't count):

  - Suspects: classes whose count grows in every interval, by at least one object per
    cycle on average. Steady-state caches level off after the warm-up; a leak keeps
    adding the same objects each cycle.
    For the top suspects the instances of the first and the last dump are grouped by the
    tail of their shortest path from a GC root, and the groups that grew are printed: that
    names the holder (e.g. OkHttp's HPACK table, which is bounded, vs. an app field).
  - Lifecycle checks: objects that must exist at most once at a time (the bubble
    controller and its views, the activity). An extra one means an old instance is still
    held; the shortest reference path from a GC root shows what holds it.

--mapping de-obfuscates R8 class and field names. Needs the `perfetto` Python package.
"""
import argparse
import sys
from collections import Counter, defaultdict, deque

from perfetto.trace_processor import TraceProcessor

HEAP = "HEAP_TYPE_APP"
# Original class name → most instances alive at once. Classes R8 merged away are skipped.
LIFECYCLE = {
    "com.octos.aichat.bubble.BubbleController": 1,
    "com.octos.aichat.bubble.FloatingBubble": 1,
    "com.octos.aichat.bubble.BubblePanel": 1,
    "com.octos.aichat.capture.ChatCapture": 1,
    "com.octos.aichat.ui.MainActivity": 1,
    # Rebound each time accessibility is switched back on; an old one is only released once
    # the system drops its binder to it, so a few may linger — growth is what matters.
    "com.octos.aichat.accessibility.ChatAccessibilityService": 1,
}
TOP = 20
EXPLAIN = 8  # suspects whose growth is broken down by holder
TAIL = 4  # path hops kept in a holder group


def load_mapping(path):
    """R8 mapping.txt → {obf class: original}, {(obf class, obf field): original field}."""
    classes, fields, current = {}, {}, None
    if not path:
        return classes, fields
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if not line.startswith(" ") and " -> " in line:
                orig, obf = line.rstrip(":\n").split(" -> ")
                current = obf.strip()
                classes[current] = orig.strip()
            elif current and " -> " in line and "(" not in line:
                left, obf = line.strip().split(" -> ")
                fields[(current, obf.strip())] = left.split()[-1]
    return classes, fields


class Names:
    def __init__(self, mapping):
        self.classes, self.fields = load_mapping(mapping)

    def cls(self, name):
        base, dims = name, ""
        while base.endswith("[]"):
            base, dims = base[:-2], dims + "[]"
        return self.classes.get(base, base) + dims

    def field(self, name):
        """`y5.e.b` → `com.octos.aichat.bubble.BubbleController.bubble`."""
        owner, _, field = (name or "?").rpartition(".")
        return f"{self.cls(owner)}.{self.fields.get((owner, field), field)}" if owner else name


def class_counts(tp):
    """{class: (objects, bytes)} for the reachable objects of the app heap."""
    return {r.name: (r.n, r.size or 0) for r in tp.query(f"""
        select c.name as name, count(*) as n, sum(o.self_size) as size
        from heap_graph_object o join heap_graph_class c on o.type_id = c.id
        where o.reachable and o.heap_type = '{HEAP}' group by c.name""")}


def retention_path(tp, target_ids, names):
    """{object id: [(class, field), …] from a GC root down to the object} — shortest paths."""
    cls_of = {r.id: r.name for r in tp.query("""
        select o.id as id, c.name as name from heap_graph_object o
        join heap_graph_class c on o.type_id = c.id where o.reachable""")}
    roots = [r.id for r in tp.query(
        "select id from heap_graph_object where root_type is not null and reachable")]
    edges = defaultdict(list)
    for r in tp.query("""select owner_id, owned_id, field_name from heap_graph_reference
                         where owned_id is not null"""):
        edges[r.owner_id].append((r.owned_id, r.field_name))
    parent = {rid: None for rid in roots}
    queue, left = deque(roots), set(target_ids)
    while queue and left:
        cur = queue.popleft()
        for nxt, field in edges.get(cur, ()):
            if nxt not in parent:
                parent[nxt] = (cur, field)
                left.discard(nxt)
                queue.append(nxt)
    paths = {}
    for oid in target_ids:
        if oid not in parent:
            continue
        hops, cur = [], oid
        while parent[cur] is not None:
            prev, field = parent[cur]
            hops.append((names.cls(cls_of.get(cur, "?")), names.field(field)))
            cur = prev
        hops.append((names.cls(cls_of.get(cur, "?")), "GC root"))
        paths[oid] = list(reversed(hops))
    return paths


def holder_groups(tp, classes, names):
    """{class: Counter(path tail → instances)} for the reachable app-heap instances."""
    ids = {}
    for cls in classes:
        ids.update({r.id: cls for r in tp.query(f"""
            select o.id as id from heap_graph_object o join heap_graph_class c
            on o.type_id = c.id where o.reachable and o.heap_type = '{HEAP}'
            and c.name = '{cls}'""")})
    paths = retention_path(tp, list(ids), names)
    groups = defaultdict(Counter)
    for oid, cls in ids.items():
        hops = paths.get(oid)
        if not hops:
            groups[cls]["(no path)"] += 1
            continue
        tail = hops[:-1][-TAIL:] or hops
        groups[cls][" / ".join(
            ("ROOT" if f == "GC root" else f.rsplit(".", 1)[-1]) + ":" + c.rsplit(".", 1)[-1]
            for c, f in tail)] += 1
    return groups


def explain_growth(first, last, classes, names):
    before, after = holder_groups(first, classes, names), holder_groups(last, classes, names)
    print(f"\nwhere the growth sits (first → last dump, grouped by the last {TAIL} hops "
          "of the path from a GC root):")
    for cls in classes:
        a, b = before[cls], after[cls]
        grew = sorted(((b[k] - a.get(k, 0), k) for k in b if b[k] > a.get(k, 0)), reverse=True)
        print(f"  {names.cls(cls)}")
        for n, k in grew[:3]:
            print(f"    +{n:<5} {a.get(k, 0)} → {b[k]:<6} {k[:200]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("traces", nargs="+", help="heap dumps, oldest first")
    ap.add_argument("--cycles", type=int, default=10, help="cycles run between two dumps")
    ap.add_argument("--mapping")
    args = ap.parse_args()
    names = Names(args.mapping)
    obf_of = {orig: obf for obf, orig in names.classes.items()}

    counts, first, last = [], None, None
    for i, path in enumerate(args.traces):
        tp = TraceProcessor(trace=path)
        counts.append(class_counts(tp))
        if i == 0:
            first = tp
        elif last:
            last.close()
        if i > 0:
            last = tp
    last = last or first

    print("dump  reachable app objects  bytes")
    for i, c in enumerate(counts):
        print(f"{i:>4}  {sum(n for n, _ in c.values()):>21,}  {sum(b for _, b in c.values()):>12,}")

    leaking = False
    if len(counts) > 1:
        need = args.cycles * (len(counts) - 1)  # ≥ 1 object per cycle on average
        suspects = []
        for name in set().union(*counts):
            series = [c.get(name, (0, 0)) for c in counts]
            objs = [n for n, _ in series]
            if all(b > a for a, b in zip(objs, objs[1:])) and objs[-1] - objs[0] >= need:
                suspects.append((series[-1][1] - series[0][1], name, objs))
        suspects.sort(reverse=True)
        print(f"\nsuspects — growing in every interval, ≥ {need} objects over "
              f"{args.cycles * (len(counts) - 1)} cycles: {len(suspects)}")
        for grew, name, objs in suspects[:TOP]:
            per = (objs[-1] - objs[0]) / (args.cycles * (len(counts) - 1))
            print(f"  {per:6.1f}/cycle  +{grew:>9,} B  {' → '.join(map(str, objs)):<24} "
                  f"{names.cls(name)[:90]}")
        leaking = bool(suspects)
        if suspects:
            explain_growth(first, last, [name for _, name, _ in suspects[:EXPLAIN]], names)

    print("\nlifecycle objects (at most one alive at a time):")
    held = {}
    for orig, most in LIFECYCLE.items():
        obf = obf_of.get(orig)
        if obf is None:
            if names.classes:
                print(f"  {'-':>12}  {orig}  (not in this build: merged or inlined by R8)")
                continue
            obf = orig
        objs = [c.get(obf, (0, 0))[0] for c in counts]
        flag = "  ← LEAK" if objs[-1] > most else ""
        print(f"  {' → '.join(map(str, objs)):>12}  {orig}{flag}")
        if objs[-1] > most:
            held[obf] = orig

    if held:
        leaking = True
        ids = {}
        for obf in held:
            ids.update({r.id: obf for r in last.query(f"""
                select o.id as id from heap_graph_object o join heap_graph_class c
                on o.type_id = c.id where o.reachable and c.name = '{obf}'""")})
        paths = retention_path(last, list(ids), names)
        print("\nwhat holds them (last dump, shortest path from a GC root):")
        for oid, obf in ids.items():
            print(f"  {held[obf]} #{oid}")
            for cls, field in paths.get(oid, [("?", "no path found")]):
                print(f"      {field:<60} {cls}")
    last.close()
    if first is not last:
        first.close()
    print("\nverdict:", "possible leak — see above" if leaking else "no leak found")
    return 1 if leaking else 0


if __name__ == "__main__":
    sys.exit(main())
