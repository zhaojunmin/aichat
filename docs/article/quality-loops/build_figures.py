#!/usr/bin/env python3
"""Generate the article's figures as SVG, then render each to PNG (2x) with cairosvg.

    python3 build_figures.py        # writes figures/*.svg and figures/*.png

Numbers come from docs/performance-testing.md, docs/reliability-testing.md and
build/benchmark-results/perf-history.csv (real-device runs); no chat content.
"""
import os
from xml.sax.saxutils import escape

import cairosvg

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "figures")
W = 1080

# Palette
NAVY = "#1F3A5F"
TEAL = "#2A9D8F"
TEAL_L = "#E3F4F1"
RED = "#E76F51"
RED_L = "#FDECE7"
AMBER = "#F4A261"
AMBER_L = "#FEF3E6"
AMBER_D = "#B45309"  # amber text on light fills
BLUE_L = "#E8F0FA"
GRAY = "#6B7280"
LIGHT = "#F5F7FA"
BORDER = "#D7DEE8"
INK = "#1F2937"
SANS = "Noto Sans CJK SC"
MONO = "Noto Sans Mono CJK SC"


# ---------------------------------------------------------------- primitives
def svg(h, body, title=None, subtitle=None):
    head = ""
    if title:
        head += text(48, 64, title, 34, 700, NAVY)
    if subtitle:
        head += text(48, 102, subtitle, 20, 400, GRAY)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" viewBox="0 0 {W} {h}">'
        '<defs>'
        f'<marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{GRAY}"/></marker>'
        f'<marker id="arrT" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{TEAL}"/></marker>'
        f'<marker id="arrR" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{RED}"/></marker>'
        '</defs>'
        f'<rect width="{W}" height="{h}" fill="#FFFFFF"/>' + head + body + "</svg>"
    )


def text(x, y, s, size=20, weight=400, fill=INK, anchor="start", family=SANS, italic=False):
    st = ' font-style="italic"' if italic else ""
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}"{st}>{escape(s)}</text>')


def lines(x, y, rows, size=20, weight=400, fill=INK, gap=None, anchor="start", family=SANS):
    gap = gap or int(size * 1.5)
    return "".join(text(x, y + i * gap, r, size, weight, fill, anchor, family) for i, r in enumerate(rows))


def rect(x, y, w, h, fill=LIGHT, stroke=BORDER, rx=12, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>'


def line(x1, y1, x2, y2, color=GRAY, sw=2, marker="arr", dash=None):
    m = f' marker-end="url(#{marker})"' if marker else ""
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{sw}"{m}{d}/>'


def path(d, color=GRAY, sw=2, marker="arr", fill="none", dash=None):
    m = f' marker-end="url(#{marker})"' if marker else ""
    da = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<path d="{d}" stroke="{color}" stroke-width="{sw}" fill="{fill}"{m}{da}/>'


def circle(cx, cy, r, fill, stroke="none"):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" stroke="{stroke}"/>'


def badge(cx, cy, label, fill=RED, r=17, size=19):
    return circle(cx, cy, r, fill) + text(cx, cy + size * 0.36, label, size, 700, "#FFFFFF", "middle")


def chip(x, y, label, fill, color="#FFFFFF", size=16, padx=10, h=28):
    w = tw(label, size) + 2 * padx
    return rect(x, y, w, h, fill, fill, rx=h // 2, sw=1) + text(x + w / 2, y + h * 0.68, label, size, 700, color, "middle"), w


def tw(s, size):
    """Rough text width: CJK ≈ 1em, ASCII ≈ 0.56em."""
    return sum(size if ord(c) > 0x2E80 else size * 0.56 for c in s)


def cross(cx, cy, r=16, color=RED):
    return (circle(cx, cy, r + 3, "#FFFFFF") +
            f'<line x1="{cx-r*0.6}" y1="{cy-r*0.6}" x2="{cx+r*0.6}" y2="{cy+r*0.6}" stroke="{color}" stroke-width="5" stroke-linecap="round"/>'
            f'<line x1="{cx-r*0.6}" y1="{cy+r*0.6}" x2="{cx+r*0.6}" y2="{cy-r*0.6}" stroke="{color}" stroke-width="5" stroke-linecap="round"/>')


def footer(h, note):
    return text(W - 48, h - 24, note, 15, 400, "#9CA3AF", "end")


# ---------------------------------------------------------------- figures
def wrap_cell(x, y, w, h, rows, fill=LIGHT, stroke=BORDER, size=15, color=INK):
    """A box with centred multi-line text."""
    gap = int(size * 1.45)
    top = y + h / 2 - (len(rows) - 1) * gap / 2 + size * 0.35
    return rect(x, y, w, h, fill, stroke, rx=10) + "".join(
        text(x + w / 2, top + i * gap, r, size, 400, color, "middle") for i, r in enumerate(rows))


def fig1_loops():
    h = 700
    stages = ["测量", "定位", "修改", "验证", "留档", "守护"]
    rows = [
        ("性能", TEAL, TEAL_L, [
            ["perf-loop", "自动点击+打点"], ["Perfetto 采样", "按阶段火焰图"], ["bge 4 线程", "Power_High"],
            ["背靠背 A/B", "看 P50 / P95"], ["perf-history", ".csv"], ["对照 P1–P5", "预算检查"]]),
        ("内存", AMBER, AMBER_L, [
            ["leak-loop", "生命周期轮转"], ["Java 堆 dump", "heap_leak 差分"], ["裁剪后立即", "回收整屏截图"],
            ["生命周期对象", "始终只有 1 个"], ["report.txt", "dump 默认删除"], ["发现泄漏", "退出码 = 1"]]),
        ("可靠性", RED, RED_L, [
            ["6 种黑盒扰动", "dropbox 查崩溃"], ["错误码", "退出原因·耗时"], ["前台服务保活", "后台运行入口"],
            ["崩溃用例核对", "APP-901 / 902"], ["诊断导出", "基线表"], ["白盒进 CI", "黑盒按需跑"]]),
    ]
    x0, lw, cw, gap = 48, 112, 138, 8
    y0 = 150
    body = ""
    for i, s in enumerate(stages):
        cx = x0 + lw + i * (cw + gap) + cw / 2
        body += circle(cx, y0, 18, NAVY) + text(cx, y0 + 6, str(i + 1), 17, 700, "#FFFFFF", "middle")
        body += text(cx, y0 + 46, s, 22, 700, NAVY, "middle")
        if i < 5:
            body += line(cx + 26, y0, cx + cw + gap - 26, y0, GRAY, 2)
    ry = y0 + 76
    for r, (name, col, light, cells) in enumerate(rows):
        y = ry + r * 112
        body += rect(x0, y, lw - 12, 96, col, col, rx=10) + text(x0 + (lw - 12) / 2, y + 56, name, 22, 700, "#FFFFFF", "middle")
        for i, c in enumerate(cells):
            body += wrap_cell(x0 + lw + i * (cw + gap), y, cw, 96, c, light, light)
    # loop-back arrow
    yb = ry + 3 * 112 + 14
    xl, xr = x0 + lw + cw / 2, x0 + lw + 5 * (cw + gap) + cw / 2
    body += path(f"M{xr},{yb - 8} L{xr},{yb + 22} L{xl},{yb + 22} L{xl},{yb - 4}", TEAL, 2.5, "arrT")
    body += rect(W / 2 - 230, yb + 6, 460, 34, "#FFFFFF", "#FFFFFF", rx=6)
    body += text(W / 2, yb + 30, "改完代码,回到第 1 步再跑一遍:同一个脚本,同一组指标", 18, 700, TEAL, "middle")
    return svg(h, body + footer(h, "三条回路共用一个骨架;每一格都对应仓库里的一个脚本、测试或文件"),
               "图 1 三条回路,一个骨架", "性能、内存、可靠性都做成「可重复跑」的闭环,而不是一次性的测试")


def hbar_panel(x, y, w, label_w, rows, vmax, unit, ticks, title):
    """rows: (label, p50, p95, budget or None, budget_label). P50 solid, P95 light."""
    out = text(x, y, title, 20, 700, NAVY)
    bx, bw = x + label_w, w - label_w - 70
    y += 22
    rh = 54
    for t in ticks:
        tx = bx + bw * t / vmax
        out += line(tx, y, tx, y + rh * len(rows), BORDER, 1, None)
        out += text(tx, y + rh * len(rows) + 22, f"{t:g}{unit}", 14, 400, GRAY, "middle")
    for i, (lab, p50, p95, bud, blab) in enumerate(rows):
        yy = y + i * rh + 12
        out += text(bx - 12, yy + 20, lab, 17, 400, INK, "end", MONO)
        w95 = max(bw * min(p95, vmax) / vmax, 3)
        w50 = max(bw * min(p50, vmax) / vmax, 3)
        out += rect(bx, yy, w95, 28, TEAL_L, TEAL_L, rx=4) + rect(bx, yy, w50, 28, TEAL, TEAL, rx=4)
        lab_x = bx + w95 + 8
        if bud is not None:
            lab_x = max(lab_x, bx + bw * bud / vmax + 10)
        out += text(lab_x, yy + 20, f"{p50:g} / {p95:g}", 15, 700, INK)
        if bud is not None:
            tx = bx + bw * bud / vmax
            out += line(tx, yy - 6, tx, yy + 34, RED, 3, None)
            out += text(tx, yy - 10, blab, 13, 700, RED, "middle")
    return out


def fig2_tap():
    h = 700
    body = hbar_panel(48, 160, 984, 230, [
        ("bubble.tapToResult", 2.18, 2.83, 3.1, "P1+P2 ≤3.1s(截图路径)"),
        ("bubble.generate", 1.67, 2.05, None, ""),
    ], 5, "s", [0, 1, 2, 3, 4, 5], "秒级:一次点击里,大头是云端生成")
    body += hbar_panel(48, 370, 984, 230, [
        ("resolveConversation", 423, 649, 600, "P2 截图+识别 ≤600ms"),
        ("bubble.ocr", 160, 326, None, ""),
        ("bubble.memoryBlock", 35, 65, 50, "P5 ≤50ms"),
        ("a11y.onEvent", 3.2, 6.9, 8, "P4 ≤8ms"),
    ], 800, "ms", [0, 200, 400, 600, 800], "毫秒级:代码侧能动的部分")
    lx = 760
    body += rect(lx, 144, 18, 18, TEAL, TEAL, rx=3) + text(lx + 26, 159, "P50", 15, 400, GRAY)
    body += rect(lx + 76, 144, 18, 18, TEAL_L, TEAL_L, rx=3) + text(lx + 102, 159, "P95", 15, 400, GRAY)
    body += line(lx + 160, 144, lx + 160, 162, RED, 3, None) + text(lx + 170, 159, "预算", 15, 400, GRAY)
    return svg(h, body + footer(h, "perf-history.csv,2026-11-16,微信「文件传输助手」,云端模式,单轮 3 次点击;各阶段分别取分位数,不可相加"),
               "图 2 一次点击的时间账", "点悬浮球 → 出候选,按打点阶段拆开看,对照预算 P1–P5")


def fig3_perfloop():
    h = 780
    body = ""
    # tap loop
    steps = [("① 记参考", ["打开测试聊天", "只存标题栏一条"]), ("② 比对标题栏", ["同聊天 0%", "换聊天 ≈7%", "阈值 0.2%"]),
             ("③ 点悬浮球", ["等出结果", "(默认 15 s)"]), ("④ 按 Home", ["关面板", "从不点面板"])]
    x, y, bw, bh, gp = 48, 200, 212, 130, 45
    for i, (t, rows) in enumerate(steps):
        bx = x + i * (bw + gp)
        body += rect(bx, y, bw, bh, BLUE_L, BLUE_L, rx=12) + text(bx + bw / 2, y + 34, t, 20, 700, NAVY, "middle")
        body += lines(bx + bw / 2, y + 68, rows, 16, 400, INK, 24, "middle")
        if i < 3:
            body += line(bx + bw + 4, y + bh / 2, bx + bw + gp - 4, y + bh / 2, GRAY, 2)
    # loop back from 4 to 2
    x2 = x + 1 * (bw + gp) + bw / 2
    x4 = x + 3 * (bw + gp) + bw / 2
    body += path(f"M{x4},{y + bh + 4} L{x4},{y + bh + 34} L{x2},{y + bh + 34} L{x2},{y + bh + 8}", TEAL, 2.5, "arrT")
    body += text((x2 + x4) / 2, y + bh + 58, "重复 N 次点击 × M 轮", 16, 700, TEAL, "middle")
    # abort branch
    xa = x + 1 * (bw + gp) + bw / 2
    body += line(xa, y - 4, xa, y - 30, RED, 2, None)
    body += chip(xa - 90, y - 60, "不是参考聊天 → 整轮中止", RED, size=15)[0]
    # recording lanes
    ly = 450
    body += text(48, ly, "同时记录", 20, 700, NAVY)
    lanes = [("atrace", "App 打点(a11y.* / bubble.* / embed.forward)", TEAL_L, TEAL),
             ("Perfetto(PROFILE=1)", "调用栈采样 1 kHz,本进程用户态", AMBER_L, AMBER_D)]
    for i, (n, d, f, c) in enumerate(lanes):
        yy = ly + 20 + i * 62
        body += rect(48, yy, 560, 48, f, f, rx=8) + text(64, yy + 31, n, 17, 700, c, family=MONO) + text(270, yy + 31, d, 15, 400, INK)
    body += path(f"M612,{ly + 44} L660,{ly + 44} L660,{ly + 75} L700,{ly + 75}", GRAY, 2)
    body += path(f"M612,{ly + 106} L660,{ly + 106} L660,{ly + 75}", GRAY, 2, None)
    body += wrap_cell(704, ly + 30, 328, 90, ["按 BOOTTIME 时钟对齐", "把采样归到各个阶段"], LIGHT, BORDER, 17)
    # outputs
    oy = 620
    outs = [("trace_summary.py", ["每阶段 P50/P95/max", "对照预算 → 退出码"]),
            ("perf-history.csv", ["一行 = 一轮 × 一阶段", "带提交号和安装时间"]),
            ("perfetto_analyze.py", ["热点库 / 函数", "--html 按阶段火焰图"])]
    for i, (t, rows) in enumerate(outs):
        bx = 48 + i * 336
        body += rect(bx, oy, 312, 104, TEAL_L, TEAL_L, rx=12) + text(bx + 156, oy + 32, t, 17, 700, TEAL, "middle", MONO)
        body += lines(bx + 156, oy + 62, rows, 15, 400, INK, 23, "middle")
    body += text(1032, ly - 4, "这台荣耀上 traced_probes 被厂商停用:Perfetto 采不到 App 打点,所以用 atrace 补", 14, 400, AMBER_D, "end")
    return svg(h, body + footer(h, "quality/scripts/perf-loop.sh · perf_guard.py · trace_summary.py · perfetto_analyze.py"),
               "图 3 性能回路:把手动点测变成可重复的闭环", "手机是真人在用的:每次点击前先确认屏幕上是那个测试聊天")


def fig4_bge():
    h = 680
    body = ""
    # left: microbench vs real, and what the samples show
    lx = 48
    body += text(lx, 150, "同一段 bge 推理,真机点击里慢 2.7 倍", 20, 700, NAVY)
    bars = [("微基准 embedQuery", 52, GRAY), ("真机点击 memoryBlock", 140, RED)]
    for i, (n, v, c) in enumerate(bars):
        yy = 180 + i * 64
        body += text(lx, yy + 20, n, 16, 400, INK)
        body += rect(lx + 190, yy, v * 1.9, 32, c, c, rx=4) + text(lx + 198 + v * 1.9, yy + 23, f"≈{v} ms", 16, 700, INK)
    body += text(lx, 340, "调用栈采样:记忆块时段的 CPU 花在哪", 18, 700, NAVY)
    sx, sw = lx, 470
    body += rect(sx, 360, sw * 0.34, 44, RED, RED, rx=4) + rect(sx + sw * 0.34, 360, sw * 0.66, 44, TEAL, TEAL, rx=4)
    body += text(sx + sw * 0.17, 388, "34%", 18, 700, "#FFFFFF", "middle")
    body += text(sx + sw * 0.67, 388, "66% bge 前向计算", 17, 700, "#FFFFFF", "middle")
    body += lines(sx, 436, ["红色:MNN 线程池 sched_yield 忙等(线程在空转等活)",
                            "同一时段:厂商调度器在暂停大核",
                            "→ 不是模型慢,是线程配置和调度配合不好"], 15, 400, INK, 25)
    # right: A/B
    rx0 = 580
    body += text(rx0, 150, "背靠背 A/B:线程数 × 功耗档位", 20, 700, NAVY)
    cfg = [("4 线程 normal(原)", 137.5, 190), ("4 线程 high ✓", 120.5, 136.5), ("2 线程 high", 143, 168), ("6 线程 high", 160, 243)]
    bx, bwid, vmax = rx0 + 190, 240, 250
    for t in (0, 100, 200):
        tx = bx + bwid * t / vmax
        body += line(tx, 180, tx, 180 + 4 * 76, BORDER, 1, None) + text(tx, 180 + 4 * 76 + 20, f"{t} ms", 13, 400, GRAY, "middle")
    for i, (n, p50, p95) in enumerate(cfg):
        yy = 190 + i * 76
        good = "✓" in n
        body += text(bx - 10, yy + 26, n, 15, 700 if good else 400, TEAL if good else INK, "end")
        body += rect(bx, yy, bwid * p50 / vmax, 22, TEAL if good else GRAY, TEAL if good else GRAY, rx=3)
        body += rect(bx, yy + 26, bwid * p95 / vmax, 22, TEAL_L if good else "#E5E7EB", TEAL_L if good else "#E5E7EB", rx=3)
        body += text(bx + bwid * p50 / vmax + 6, yy + 17, f"P50 {p50:g}", 13, 400, INK)
        body += text(bx + bwid * p95 / vmax + 6, yy + 43, f"P95 {p95:g}", 13, 400, INK)
    body += lines(rx0, 530, ["线程不是越多越好:6 线程 P95 反而最差",
                             "采用 4 线程 + Power_High:P95 约 190 → 137 ms",
                             "参数从 JNI 传入,不再写死在 so 里"], 15, 400, INK, 25)
    return svg(h, body + footer(h, "docs/performance-testing.md;4 线程两组各跑两次取均值,2/6 线程各一次;每组 6–8 次点击"),
               "图 4 让调用栈说话:记忆块为什么慢", "微基准说 52 ms,真机点击里却要 140 ms,差在哪里")


def fig5_leakloop():
    h = 700
    body = ""
    cyc = [("① 首页 → Home", "MainActivity / Compose / ViewModel"),
           ("② 无障碍 关 → 开", "BubbleController / 悬浮窗 / 协程"),
           ("③ 点悬浮球 → Home", "面板 / 读屏·OCR / 生成(TAP=1)")]
    x, y = 48, 150
    body += rect(x, y, 470, 280, BLUE_L, BLUE_L, rx=14) + text(x + 20, y + 36, "一轮(覆盖会反复创建 / 销毁的对象)", 18, 700, NAVY)
    for i, (a, b) in enumerate(cyc):
        yy = y + 60 + i * 70
        body += rect(x + 20, yy, 430, 58, "#FFFFFF", BORDER, rx=10)
        body += text(x + 36, yy + 26, a, 17, 700, INK) + text(x + 36, yy + 48, b, 14, 400, GRAY)
    # timeline of dumps
    tx, ty = 560, 190
    body += text(tx, 160, "轮次与堆 dump", 18, 700, NAVY)
    seq = [("预热 ×2", LIGHT, GRAY), ("dump 0", AMBER, "#FFFFFF"), ("×10 轮", LIGHT, GRAY), ("dump 1", AMBER, "#FFFFFF"),
           ("×10 轮", LIGHT, GRAY), ("dump 2", AMBER, "#FFFFFF")]
    for i, (s, f, c) in enumerate(seq):
        yy = ty + i * 40
        body += rect(tx, yy, 150, 32, f, f, rx=8) + text(tx + 75, yy + 22, s, 16, 700, c, "middle")
    body += lines(tx + 170, ty + 22, ["首次使用的缓存不算泄漏,先预热",
                                      "Perfetto android.java_hprof",
                                      "正式包是 profileable 的,",
                                      "不用 debug 包,不往 App 里加代码",
                                      "一次 dump 约 20 MB"], 15, 400, INK, 26)
    body += line(518, 290, 552, 290, GRAY, 2)
    # analyzer
    ay = 470
    body += text(48, ay, "heap_leak.py 怎么判断", 20, 700, NAVY)
    rules = [("只看可达对象", ["App 堆里从 GC root", "还能走到的;垃圾不算"]),
             ("疑似泄漏", ["每个区间都在涨", "且平均每轮 ≥ 1 个"]),
             ("生命周期对象", ["同一时刻最多 1 个", "多了就打印引用链"]),
             ("按持有者归因", ["按引用链末尾几跳分组", "区分 App 字段与库缓存"])]
    for i, (t, rows) in enumerate(rules):
        bx = 48 + i * 248
        body += rect(bx, ay + 20, 232, 116, AMBER_L, AMBER_L, rx=12) + text(bx + 116, ay + 52, t, 18, 700, AMBER_D, "middle")
        body += lines(bx + 116, ay + 84, rows, 15, 400, INK, 23, "middle")
    body += text(1032, h - 54, "堆 dump 是 App 的全部 Java 内存(含聊天内容和 API Key):报告写完默认删除,KEEP=1 才保留", 14, 700, RED, "end")
    return svg(h, body + footer(h, "quality/scripts/leak-loop.sh · heap_leak.py · quality/perfetto/java-heap.pbtxt"),
               "图 5 内存泄漏回路:反复折腾生命周期,比较堆里谁在涨", "不靠肉眼看一次 dump,而是比较多次 dump 之间的趋势")


def fig6_heap():
    h = 720
    body = ""
    # mini chart
    body += text(48, 150, "App 堆可达对象(20 轮)", 18, 700, NAVY)
    pts = [(0, 5.60, "149,441"), (1, 5.64, "149,865"), (2, 5.70, "151,190")]
    cx0, cy0, cw, chh = 80, 190, 300, 120
    def py(v): return cy0 + chh - (v - 5.55) / 0.2 * chh
    body += line(cx0, cy0 + chh, cx0 + cw, cy0 + chh, BORDER, 1.5, None)
    body += text(cx0 + cw, cy0 + chh - 8, "纵轴从 5.55 MB 起", 12, 400, GRAY, "end")
    pp = " ".join(f"{cx0 + 20 + p[0] * 130},{py(p[1])}" for p in pts)
    body += f'<polyline points="{pp}" fill="none" stroke="{AMBER}" stroke-width="3"/>'
    for i, v, n in pts:
        px = cx0 + 20 + i * 130
        body += circle(px, py(v), 6, AMBER) + text(px, py(v) - 14, f"{v:.2f} MB", 15, 700, INK, "middle")
        body += text(px, cy0 + chh + 22, f"dump {i}", 14, 400, GRAY, "middle") + text(px, cy0 + chh + 42, n, 13, 400, GRAY, "middle")
    body += lines(450, 190, ["20 轮只多了约 105 KB,对象 +1,749 个。",
                             "涨是涨了,但每一组增长都找到了持有者,",
                             "没有一组落在 App 自己的字段上。"], 17, 400, INK, 30)
    body += chip(450, 290, "BubbleController / FloatingBubble / MainActivity:三次 dump 都是 1 个", TEAL, size=15)[0]
    # table
    ty = 400
    cols = [48, 330, 520, 860]
    for t, x in zip(["在涨的类", "变化", "持有者", "判断"], cols):
        body += text(x, ty, t, 16, 700, GRAY)
    rows = [("Header / ByteString / byte[]", "+80", "OkHttp HTTP/2 HPACK 表", "有上限", TEAL),
            ("ViewRootImpl$W 等", "18 → 65", "窗口 Binder 桩,system_server 持有", "系统侧", AMBER_D),
            ("Cleaner / CleanerThunk", "+80", "待回收的 native 分配链表", "等 GC", GRAY),
            ("Long", "+105", "ART 内部 Long[][]", "运行时", GRAY),
            ("ChatAccessibilityService", "3 → 7 → 9", "框架 Binder 桩的 mContext", "系统侧", AMBER_D)]
    for i, (c, d, hld, v, col) in enumerate(rows):
        yy = ty + 20 + i * 52
        body += rect(40, yy, 1000, 44, LIGHT if i % 2 == 0 else "#FFFFFF", "#FFFFFF", rx=6)
        body += text(cols[0], yy + 29, c, 15, 400, INK, family=MONO) + text(cols[1], yy + 29, d, 15, 700, INK)
        body += text(cols[2], yy + 29, hld, 15, 400, INK) + chip(cols[3], yy + 8, v, col, size=14)[0]
    return svg(h, body + footer(h, "2026-11-16,荣耀 ELP-AN00,预热 2 轮 + 2×10 轮,TAP=1 云端;docs/performance-testing.md"),
               "图 6 首轮结果:涨的都不是我们的", "最像泄漏的一行(无障碍服务实例 3→7→9)其实被系统进程持有,且会被陆续释放")


def fig7_reliability():
    h = 840
    body = ""
    cx, cy = W / 2, 470
    body += circle(cx, cy, 78, NAVY) + text(cx, cy - 4, "可靠性", 24, 700, "#FFFFFF", "middle") + text(cx, cy + 24, "回路", 18, 400, "#FFFFFF", "middle")
    quads = [
        (48, 140, "白盒 · 每次 CI", TEAL, TEAL_L, ["Room 事务:写一半失败整体回滚", "v1–v4 迁移按旧建表语句验证", "云端 / 本地超时、畸形输出", "JVM + Robolectric,秒级"]),
        (612, 140, "黑盒 · 真机按需", AMBER_D, AMBER_L, ["am crash、内存压力", "运行中收回悬浮窗 / 关无障碍", "断网点「测试连接」", "深色模式 + 旋转;查 dropbox"]),
        (48, 620, "线上 · 用户手机", RED, RED_L, ["错误码(AI / A11Y / OCR …)", "APP-901 崩溃时同步写下", "APP-902 下次启动读退出原因", "耗时 9 个固定分桶,不含聊天"]),
        (612, 620, "环境 · 厂商系统", NAVY, BLUE_L, ["划掉最近任务:进程被杀", "无障碍设置也被清掉", "→ 悬浮球显示期间前台服务保活", "→ 引导「允许后台运行」"]),
    ]
    for x, y, t, c, f, rows in quads:
        body += rect(x, y, 420, 168, f, f, rx=14) + text(x + 20, y + 34, t, 19, 700, c)
        body += lines(x + 20, y + 68, rows, 15, 400, INK, 25)
    d = 78 * 0.72
    body += line(470, 300, cx - d - 4, cy - d - 4, TEAL, 2.5, "arrT")
    body += line(610, 300, cx + d + 4, cy - d - 4, AMBER, 2.5)
    body += line(cx - d - 4, cy + d + 4, 470, 628, RED, 2.5, "arrR")
    body += line(cx + d + 4, cy + d + 4, 610, 628, GRAY, 2.5)
    body += rect(660, 420, 372, 100, "#FFFFFF", AMBER, rx=10, dash="6 4")
    body += lines(676, 452, ["黑盒崩溃用例顺便验收诊断:", "APP-901 恰好 +1,", "最新退出原因是 CRASH"], 15, 700, AMBER_D, 24)
    body += rect(48, 420, 372, 100, "#FFFFFF", NAVY, rx=10, dash="6 4")
    body += lines(64, 452, ["测试测不到的:", "厂商在「划掉」时做了什么,", "只能在真机上被碰到后补"], 15, 700, NAVY, 24)
    return svg(h, body + footer(h, "docs/reliability-testing.md · docs/observability-design.md · KeepLiveService / BackgroundRunSettings"),
               "图 7 可靠性回路:四个来源,互相校验", "白盒守逻辑,黑盒守恢复,诊断守线上,还有一块是厂商系统")


def fig8_recovery():
    h = 520
    rows = [("崩溃后 无障碍服务拉起", 65), ("崩溃后 悬浮窗服务拉起", 80), ("无障碍关 → 开 重新绑定", 52), ("悬浮窗权限收回 → 恢复", 43)]
    body = text(48, 150, "被打断之后多久恢复(毫秒)", 20, 700, NAVY)
    bx, bw, vmax = 330, 560, 100
    for t in (0, 25, 50, 75, 100):
        tx = bx + bw * t / vmax
        body += line(tx, 170, tx, 170 + 4 * 56, BORDER, 1, None) + text(tx, 170 + 4 * 56 + 20, f"{t}", 13, 400, GRAY, "middle")
    for i, (n, v) in enumerate(rows):
        yy = 180 + i * 56
        body += text(bx - 14, yy + 24, n, 17, 400, INK, "end")
        body += rect(bx, yy, bw * v / vmax, 32, TEAL, TEAL, rx=4) + text(bx + bw * v / vmax + 8, yy + 23, f"{v} ms", 16, 700, INK)
    oy = 440
    body += chip(48, oy, "断网点「测试连接」:1.6 s 给出「网络连接失败」(要求 ≤10 s)", NAVY, size=15)[0]
    body += chip(48, oy + 40, "内存压力 6 级 / 深色模式 + 旋转:不崩溃,同一进程", TEAL, size=15)[0]
    return svg(h, body + footer(h, "2026-11-15,荣耀 ELP-AN00 / Android 16,云端模式;连续两轮 6/6 通过"),
               "图 8 黑盒可靠性基线", "每个用例结束时都查 dumpsys dropbox:这段时间有没有崩溃或 ANR")


def fig9_safety():
    h = 760
    rows = [("点到别的聊天", "每次点击前比对标题栏;不是参考聊天就中止,整屏截图不落盘"),
            ("关面板时误选候选", "关面板只按 Home,从不点面板"),
            ("云端会把聊天发出去", "测试只在「文件传输助手」里点;要不出设备就切本地模式"),
            ("关无障碍会改系统设置", "开始前记下原值,退出(包括中途失败)时恢复"),
            ("堆 dump 里有聊天和 API Key", "报告写完默认删除,KEEP=1 才保留"),
            ("monkey 会点中「清空记忆」", "不做随机点击压力测试"),
            ("force-stop 会关掉无障碍(这台 ROM)", "崩溃场景改用 am crash"),
            ("UiAutomation 会压制被测的无障碍服务", "首次使用前设 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES"),
            ("release 包的 logcat 被屏蔽", "崩溃 / ANR 改从 dumpsys dropbox 读,要求带固定表头")]
    body = text(48, 150, "风险", 18, 700, RED) + text(420, 150, "护栏", 18, 700, TEAL)
    for i, (r, g) in enumerate(rows):
        yy = 168 + i * 62
        body += rect(40, yy, 1000, 52, LIGHT if i % 2 == 0 else "#FFFFFF", "#FFFFFF", rx=6)
        body += text(56, yy + 33, r, 16, 700, INK) + line(380, yy + 26, 404, yy + 26, GRAY, 2)
        body += text(420, yy + 33, g, 15, 400, INK)
    return svg(h, body + footer(h, "perf-loop.sh · leak-loop.sh · run-reliability.sh · quality/reliability/Phone.kt"),
               "图 9 在一台真人在用的手机上跑回路", "每条护栏背后都有一次「差点出事」或「真的踩到」")


FIGS = {
    "fig1-loops": fig1_loops, "fig2-tap-budget": fig2_tap, "fig3-perf-loop": fig3_perfloop,
    "fig4-bge": fig4_bge, "fig5-leak-loop": fig5_leakloop, "fig6-heap-growth": fig6_heap,
    "fig7-reliability-loop": fig7_reliability, "fig8-recovery": fig8_recovery, "fig9-safety": fig9_safety,
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, fn in FIGS.items():
        s = fn()
        with open(os.path.join(OUT, name + ".svg"), "w", encoding="utf-8") as f:
            f.write(s)
        cairosvg.svg2png(bytestring=s.encode("utf-8"), write_to=os.path.join(OUT, name + ".png"), scale=2)
        print("wrote", name)
