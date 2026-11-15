#!/usr/bin/env python3
"""Generate the article's figures as SVG, then render each to PNG (2x) with cairosvg.

    python3 build_figures.py        # writes figures/*.svg and figures/*.png

All chat examples in the figures are made up; no real conversation content.
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
def fig1_utility():
    rows = [
        ("隐私与安全", "H/H", "读的是私聊内容,还握着无障碍、悬浮窗、截屏三项高敏权限", "本地模式聊天内容 0 字节出设备;Key 不以明文存在;不进云备份"),
        ("可靠性 / 正确性", "H/H", "「我 / 对方」分错就答非所问;服务一崩会被系统禁用", "回调异常不崩溃;失败给出可读错误;学习失败不丢、不重复"),
        ("性能", "H/M", "用户在聊天中途等结果,超过 3 秒就自己打字了", "点击到出结果 P50 ≤ 2.5s、P95 ≤ 5s;单个事件主线程 ≤ 8ms"),
        ("可维护 / 可测试", "M/H", "解析规则、prompt、记忆策略会频繁调整", "纯逻辑能在 JVM 上测;改动有回归测试兜底"),
        ("兼容 / 可适配", "M/H", "第三方 App 改版会让控件 id 失效(QQ 的 id 还是混淆过的)", "规则失效时自动降级,不崩溃"),
        ("易用性", "M/M", "权限引导、失败提示、填入失败的回退", "每个失败都给出可操作的下一步"),
        ("可观测性", "M/L", "出了问题只能连 adb 复现,用户侧完全不可见", "错误码 + 本地诊断 + 用户主动导出"),
    ]
    y0, rh = 150, 96
    body = text(48, 132, "H/M/L = 业务重要性 / 实现难度;越靠上越优先,冲突时上面的说了算", 18, 400, GRAY)
    colors = [RED, AMBER, TEAL, NAVY, NAVY, GRAY, GRAY]
    for i, (name, hl, why, metric) in enumerate(rows):
        y = y0 + i * rh
        barw = 300 - i * 26
        body += rect(48, y + 10, W - 96, rh - 14, LIGHT if i % 2 else "#FFFFFF", BORDER, 12)
        body += rect(48, y + 10, 8, rh - 14, colors[i], colors[i], 3, 0)
        body += badge(92, y + 10 + (rh - 14) / 2, str(i + 1), colors[i], 18, 20)
        body += text(124, y + 50, name, 24, 700, INK)
        c, _ = chip(124, y + 60, hl, colors[i], size=14, h=22)
        body += c
        body += rect(330, y + 22, barw, 10, colors[i], colors[i], 5, 0)
        body += text(330, y + 58, why, 18, 400, INK)
        body += text(330, y + 84, "目标:" + metric, 17, 400, GRAY)
    h = y0 + len(rows) * rh + 50
    return svg(h, body + footer(h, "图 1 · 质量属性效用树"), "先排优先级:七个质量属性", None)


def fig2_system():
    h = 860
    body = text(48, 106, "从「0 测试、0 CI」到五层安全网 + 守门约定", 20, 400, GRAY)
    layers = [
        ("线上:本地诊断信息", "错误码 · 进程退出原因 · 耗时分布 · 用户主动导出", NAVY, "#E4EAF2"),
        ("真机黑盒:可靠性", "quality/reliability · 崩溃 / 内存压力 / 收权限 / 断网 / 配置变化", RED, RED_L),
        ("真机:端到端 Trace", "trace-e2e.sh · 读屏事件、点击到出结果的分段耗时", AMBER_D, AMBER_L),
        ("真机:微基准 / 宏基准", "向量、召回、解析的耗时与分配;冷热启动、滚动帧", "#8E7CC3", "#EFEBF8"),
        ("JVM:单元测试 + Robolectric", "165 个 · 每次提交都跑 · 含隐私哨兵、事务回滚、迁移", TEAL, TEAL_L),
    ]
    top, lh, cx = 140, 120, 360
    for i, (name, desc, col, fill) in enumerate(layers):
        y = top + i * lh
        half = 150 + i * 42
        x1, x2 = cx - half, cx + half
        body += f'<path d="M{x1+18},{y} L{x2-18},{y} L{x2},{y+lh-12} L{x1},{y+lh-12} Z" fill="{fill}" stroke="{col}" stroke-width="2"/>'
        body += text(cx, y + 48, name, 23, 700, col, "middle")
        # wrap desc into ≤ 2 lines that fit the layer
        maxw = 2 * half - 60
        words, cur, out = desc.split(" · "), "", []
        for wd in words:
            cand = wd if not cur else cur + " · " + wd
            if tw(cand, 16) > maxw and cur:
                out.append(cur)
                cur = wd
            else:
                cur = cand
        out.append(cur)
        body += lines(cx, y + 76, out[:2], 16, 400, INK, 22, "middle")
    body += text(cx, top + 5 * lh + 26, "越往上越接近真实使用,越往下越快、越便宜", 17, 400, GRAY, "middle")

    # right: gates
    gx, gy = 720, 140
    body += rect(gx, gy, 312, 590, LIGHT, BORDER, 14)
    body += text(gx + 24, gy + 44, "守门与约定", 24, 700, NAVY)
    gates = [
        ("GitHub Actions CI", "每个 PR:单测 + Lint + 编译"),
        ("Lint baseline", "接纳存量,只约束不新增"),
        ("本地先过一遍", "test + lint + assemble 通过才开 PR"),
        ("真机用 release 包", "R8 混淆后的才是用户跑的"),
        ("热路径改动附数据", "用 Trace 证明没有变慢"),
        ("隐私不靠自觉", "接口不收文本 + 哨兵测试"),
    ]
    for i, (t1, t2) in enumerate(gates):
        y = gy + 80 + i * 84
        body += circle(gx + 34, y + 8, 9, TEAL)
        body += text(gx + 54, y + 15, t1, 19, 700, INK)
        body += text(gx + 54, y + 45, t2, 16, 400, GRAY)
    return svg(h, body + footer(h, "图 2 · 工程质量体系"), "五层安全网", None)


def fig3_timeline():
    prs = [
        ("P0 安全网与隐私", NAVY, [
            ("#14", "单元测试 + Lint baseline + CI"),
            ("#15", "修复:本地模式记忆学习被云端 Key 卡死"),
            ("#16", "关闭备份;调试节点树按需;解析移出主线程"),
            ("#18", "API Key 改用 Keystore 加密,旧明文迁移"),
        ]),
        ("可靠性", RED, [
            ("#19", "云端错误分类、可取消、30 秒时限;本地推理时限"),
            ("#20", "可靠性单测;修复空串、\"[]\" 显示成候选"),
        ]),
        ("性能", AMBER_D, [
            ("#21-22", "真机微基准 + 宏基准;更正基准查询长度"),
            ("#23", "聊天解析改为点击时按需进行"),
            ("#24", "端到端 Trace;跳过前台 App 自身滚动事件"),
            ("#25", "点击路径主线程开销挪走;本地学习不抢锁"),
        ]),
        ("正确性(真机发现)", "#8E7CC3", [
            ("#26", "微信截图识别把一个气泡拆成几条"),
            ("#27", "群聊发言人名字被当成对方消息"),
        ]),
        ("可靠性(续)", RED, [
            ("#28", "学习进度与写记忆同一事务;迁移测试"),
            ("#29", "真机可靠性黑盒 6 个场景"),
        ]),
        ("可观测性", TEAL, [
            ("#30", "设计文档(评审后定稿)"),
            ("#31-34", "错误码、诊断开关、接入 28 处、导出、耗时分布"),
        ]),
    ]
    y = 140
    body = text(48, 106, "21 个 PR,每个都可单独回滚;分支一律以 main 为 base", 20, 400, GRAY)
    body += line(120, 130, 120, 0, BORDER, 4, None)  # placeholder; real axis drawn after
    axis_top = y
    for phase, col, items in prs:
        body += rect(48, y, 150, 34, col, col, 17, 0)
        body += text(123, y + 24, phase, 16, 700, "#FFFFFF", "middle")
        y += 64
        for num, title in items:
            body += circle(123, y - 7, 8, col)
            body += text(160, y, num, 19, 700, col, family=MONO)
            body += text(260, y, title, 20, 400, INK)
            y += 42
        y += 14
    body = body.replace(line(120, 130, 120, 0, BORDER, 4, None), line(123, axis_top + 30, 123, y - 40, BORDER, 4, None))
    h = y + 40
    return svg(h, body + footer(h, "图 3 · 过程时间线"), "过程:先补安全网,再按属性逐个击破", None)


def fig4_privacy():
    h = 830
    body = text(48, 106, "哪些数据能出设备、在什么条件下出 —— 每条边界都有代码和测试守着", 20, 400, GRAY)
    # phone
    px, py, pw, ph = 48, 140, 620, 640
    body += rect(px, py, pw, ph, "#FFFFFF", NAVY, 34, 3)
    body += text(px + pw / 2, py + 44, "用户的手机", 24, 700, NAVY, "middle")
    boxes = [
        (80, 210, 250, 92, "聊天 App 屏幕", "微信 / QQ / Soul …", BLUE_L, NAVY),
        (370, 210, 270, 92, "读屏(点击时才解析)", "原文不常驻内存", BLUE_L, NAVY),
        (80, 360, 250, 92, "本地模型推理", "MNN · Qwen2.5", TEAL_L, TEAL),
        (370, 360, 270, 92, "生成候选回复", "只给用户选,不自动发送", TEAL_L, TEAL),
        (80, 510, 250, 92, "记忆库(Room)", "只学「我」说的话", AMBER_L, AMBER_D),
        (370, 510, 270, 92, "API Key", "Keystore AES-GCM 加密", AMBER_L, AMBER_D),
        (80, 660, 560, 92, "诊断信息(files/diagnostics)", "只有错误码、耗时、异常类名 · 无聊天内容、无 Key、无 message", "#E4EAF2", NAVY),
    ]
    for x, y, w, hh, t1, t2, fill, col in boxes:
        body += rect(x, y, w, hh, fill, col, 14, 2)
        body += text(x + w / 2, y + 38, t1, 20, 700, col, "middle")
        body += text(x + w / 2, y + 70, t2, 15, 400, INK, "middle")
    body += line(330, 256, 366, 256, NAVY, 2.5, "arr")
    body += line(505, 302, 505, 356, NAVY, 2.5, "arr")
    body += line(334, 406, 366, 406, TEAL, 2.5, "arrT")

    # outside targets
    def target(y, t1, t2, ok):
        col = TEAL if ok else RED
        s = rect(760, y, 280, 104, TEAL_L if ok else RED_L, col, 16, 2)
        s += text(900, y + 42, t1, 20, 700, col, "middle")
        s += text(900, y + 74, t2, 15, 400, INK, "middle")
        return s
    body += target(170, "云端 AI 服务", "仅云端模式:当前对话 · HTTPS", True)
    body += target(330, "云端(本地模式时)", "0 字节:未就绪也不回退云端", False)
    body += target(490, "系统云备份 / 换机", "数据库、prefs、文件全部排除", False)
    body += target(650, "第三方崩溃 SDK", "不接入;诊断只由用户手动分享", False)
    body += path("M640,240 C700,240 700,222 756,222", TEAL, 3, "arrT")
    for yy in (382, 542, 702):
        body += line(668, yy, 756, yy, RED, 3, "arrR", "8,6")
        body += cross(712, yy)
    h2 = py + ph + 30
    return svg(h, body + footer(h, "图 4 · 隐私数据边界"), "安全与隐私:数据边界", None)


def fig5_threads():
    h = 920
    body = text(48, 106, "一次「点悬浮球」在各线程上的工作(优化前),红色编号是复盘时找到的问题", 20, 400, GRAY)
    lanes = [
        ("聊天 App 主线程", "(另一个进程)"),
        ("本 App 主线程", "悬浮窗 · 无障碍回调 · 界面"),
        ("IO 线程", "网络 · 数据库"),
        ("Default 线程", "OCR 后处理"),
        ("local-llm", "端侧推理(单线程 + 锁)"),
    ]
    top, lh, lx = 140, 104, 250
    for i, (n, d) in enumerate(lanes):
        y = top + i * lh
        body += rect(48, y, W - 96, lh - 12, LIGHT if i % 2 == 0 else "#FFFFFF", BORDER, 10)
        body += text(64, y + 40, n, 19, 700, INK)
        body += text(64, y + 68, d, 14, 400, GRAY)

    def blk(lane, x, w, label, fill=BLUE_L, col=NAVY, size=15):
        y = top + lane * lh + 18
        return rect(x, y, w, lh - 48, fill, col, 8, 1.5) + text(x + w / 2, y + (lh - 48) / 2 + size * 0.36, label, size, 700, col, "middle")

    # lane 0: chat app UI thread answering per-node IPC
    for k in range(9):
        body += rect(lx + k * 46, top + 30, 30, lh - 72, RED_L, RED, 4, 1)
    body += text(lx + 9 * 46 + 10, top + 50, "每个无障碍事件都遍历整棵树:", 15, 700, RED)
    body += text(lx + 9 * 46 + 10, top + 74, "每个节点一次跨进程查询,由对方主线程应答", 15, 400, RED)
    body += badge(lx - 22, top + 46, "④")
    # lane 1: main
    body += blk(1, lx, 70, "点击")
    body += blk(1, lx + 84, 250, "整树定位 + 13MB 位图拷贝", RED_L, RED)
    body += badge(lx + 84 + 250 - 4, top + lh + 18, "③")
    body += blk(1, lx + 470, 190, "memoryBlock 91ms", RED_L, RED)
    body += badge(lx + 470 + 190 - 4, top + lh + 18, "②")
    body += blk(1, lx + 672, 92, "显示结果", TEAL_L, TEAL)
    # lane 2: IO
    body += blk(2, lx + 340, 120, "写回复日志")
    # lane 3: default
    body += blk(3, lx + 340, 120, "OCR 后处理")
    # lane 4: llm
    body += blk(4, lx + 470, 210, "后台学习先拿到锁", RED_L, RED)
    body += badge(lx + 470 + 210 - 4, top + 4 * lh + 18, "①")
    body += blk(4, lx + 690, 82, "本地生成", AMBER_L, AMBER_D)
    body += line(lx + 462, top + 2 * lh + 50, lx + 540, top + 4 * lh + 16, RED, 2, "arrR", "6,5")
    body += text(lx + 520, top + 3 * lh + 52, "触发学习", 14, 700, RED)

    fixes = [
        ("①", "本地模式下,学习和生成抢唯一的推理锁 → 改为离开聊天 App 时才学习(#25)"),
        ("②", "语义记忆的查询向量在主线程算(实测 91ms)→ 移到 Default 线程(#25)"),
        ("③", "OCR:整树定位、整屏位图拷贝都在主线程 → IO / 后台 executor(#25)"),
        ("④", "每个无障碍事件都遍历整棵控件树,占用聊天 App 主线程 → 点击时才解析(#23)"),
    ]
    fy = top + 5 * lh + 20
    body += rect(48, fy, W - 96, 210, "#FFFFFF", BORDER, 12)
    for i, (b, t) in enumerate(fixes):
        body += badge(80, fy + 36 + i * 46, b, RED, 15, 16)
        body += text(108, fy + 43 + i * 46, t, 18, 400, INK)
    return svg(h, body + footer(h, "图 5 · 运行时视图与热点"), "性能:先看清代码跑在哪个线程上", None)


def fig6_perf():
    h = 1010
    body = text(48, 106, "真机 release 包实测(骁龙 8s Gen3 / Android 16)", 20, 400, GRAY)
    # Panel A: tap-to-result breakdown (stacked bar)
    ay = 150
    body += text(48, ay, "A · 点击到出结果 2.71 s(云端)拆开看", 22, 700, NAVY)
    total = 2713
    segs = [("读屏解析", 128, NAVY), ("记忆召回", 91, AMBER), ("模型生成", 2412, TEAL), ("其他", total - 128 - 91 - 2412, "#CBD5E1")]
    x, bw = 48, W - 96
    for name, ms, col in segs:
        w = bw * ms / total
        body += rect(x, ay + 24, w, 54, col, col, 0, 0)
        x += w
    body += rect(48, ay + 24, bw, 54, "none", BORDER, 6, 1.5)
    lx = 48
    for name, ms, col in segs:
        body += rect(lx, ay + 100, 18, 18, col, col, 4, 0)
        body += text(lx + 26, ay + 115, f"{name} {ms}ms", 17, 400, INK)
        lx += 230
    body += text(48, ay + 156, "瓶颈在模型生成;客户端自己的工作约占 8%,所以优化重点是「不卡主线程」,而不是抠总耗时", 17, 400, GRAY)

    # Panel B: before/after bars
    by = ay + 210
    body += text(48, by, "B · 优化前后(以及一次测量更正)", 22, 700, NAVY)
    items = [
        ("滑动聊天时,主线程处理的无障碍事件(次)", 36, 0, "30–42", "0", "跳过前台 App 自身的滚动事件(#24)"),
        ("点击时主线程上的记忆召回(ms)", 91, 0, "91", "0", "挪到后台线程(#25)"),
        ("bge 查询向量基准(ms)", 93, 52, "93", "52", "基准误用 30 行查询,实际只取 12 行(#22)"),
    ]
    maxv = 100
    for i, (label, before, after, bl, al, note) in enumerate(items):
        y = by + 40 + i * 130
        body += text(48, y + 18, label, 18, 700, INK)
        full = 560
        body += rect(48, y + 32, full * before / maxv, 28, RED_L, RED, 6, 1.5)
        body += text(48 + full * before / maxv + 10, y + 53, "前 " + bl, 16, 700, RED)
        aw = max(full * after / maxv, 4)
        body += rect(48, y + 68, aw, 28, TEAL_L, TEAL, 6, 1.5)
        body += text(48 + aw + 10, y + 89, "后 " + al, 16, 700, TEAL)
        body += text(720, y + 72, note, 16, 400, GRAY)

    # Panel C: budgets
    cy = by + 450
    body += text(48, cy, "C · 其他基线", 22, 700, NAVY)
    stats = [("330 ms", "冷启动 TTID"), ("83 ms", "热启动 TTID"), ("8.8 ms", "首页滚动 P99 帧"), ("2.1 ms", "500 条记忆词法召回")]
    for i, (v, k) in enumerate(stats):
        x = 48 + i * 248
        body += rect(x, cy + 20, 232, 100, LIGHT, BORDER, 12)
        body += text(x + 116, cy + 66, v, 28, 700, NAVY, "middle")
        body += text(x + 116, cy + 100, k, 16, 400, GRAY, "middle")
    return svg(h, body + footer(h, "图 6 · 性能实测"), "性能:用数据说话", None)


def fig7_txn():
    h = 760
    body = text(48, 106, "学习一轮要做两件事:写记忆、推进「已学到第几条」。两步不在一个事务里会怎样?", 20, 400, GRAY)
    # before
    y = 150
    body += text(48, y, "修复前", 24, 700, RED)
    body += rect(48, y + 20, 300, 80, AMBER_L, AMBER, 12, 2) + text(198, y + 70, "写记忆(Room)", 20, 700, INK, "middle")
    body += line(348, y + 60, 430, y + 60, GRAY, 2.5)
    body += cross(389, y + 60, 14)
    body += text(389, y + 20, "进程被杀", 14, 700, RED, "middle")
    body += rect(430, y + 20, 330, 80, "#FFFFFF", GRAY, 12, 2, "8,6") + text(595, y + 56, "推进进度(prefs)", 20, 700, GRAY, "middle") + text(595, y + 84, "apply() 异步,可能没落盘", 15, 400, GRAY, "middle")
    body += line(760, y + 60, 840, y + 60, GRAY, 2.5)
    body += rect(840, y + 20, 192, 80, RED_L, RED, 12, 2) + text(936, y + 56, "重启后", 18, 700, RED, "middle") + text(936, y + 84, "同一批再学一遍", 15, 400, RED, "middle")
    body += text(48, y + 140, "进程恰好在两步之间被杀:记忆已写入,进度却没前进 → 重试时重复学习,记忆里出现重复条目", 17, 400, INK)

    # after
    y2 = y + 210
    body += text(48, y2, "修复后(#28)", 24, 700, TEAL)
    body += rect(48, y2 + 20, 984, 120, TEAL_L, TEAL, 14, 2)
    body += text(72, y2 + 54, "BEGIN TRANSACTION", 17, 700, TEAL, family=MONO)
    steps = ["更新冲突记忆", "软删除过时记忆", "插入新记忆", "进度 → learning_state"]
    for i, s in enumerate(steps):
        x = 72 + i * 236
        body += rect(x, y2 + 68, 214, 54, "#FFFFFF", TEAL, 10, 1.5) + text(x + 107, y2 + 102, s, 17, 700, INK, "middle")
        if i < 3:
            body += line(x + 214, y2 + 95, x + 234, y2 + 95, TEAL, 2, "arrT")
    body += text(1008, y2 + 54, "COMMIT", 17, 700, TEAL, "end", MONO)
    body += text(48, y2 + 176, "任何一步失败或进程被杀 → 整体回滚;下次重试只学一次。旧版本 prefs 里的进度首次读取时迁入数据库。", 17, 400, INK)

    # tests
    y3 = y2 + 220
    body += rect(48, y3, 984, 150, LIGHT, BORDER, 14)
    body += text(72, y3 + 40, "怎么证明它真的对", 20, 700, NAVY)
    body += lines(72, y3 + 74, [
        "· Robolectric + 真实 Room:写到一半失败、写完但存进度失败,都断言「一切如初」",
        "· 迁移测试:按 v1–v4 的建表语句造库,经真实迁移链打开,数据不丢",
        "· 变异检查:故意删掉 v5 表的主键,4 个迁移用例全部失败 —— 测试能抓到错",
    ], 17, 400, INK, 26)
    return svg(h, body + footer(h, "图 7 · 学习事务"), "可靠性:把「两步」变成「一步」", None)


def fig8_ocr():
    h = 900
    body = text(48, 106, "真机测试发现的两个识别错误,示例为虚构对话", 20, 400, GRAY)
    # phone screenshot mock
    sx, sy, sw, sh = 48, 140, 470, 560
    body += rect(sx, sy, sw, sh, "#DCEBF5", "#9DB7CC", 22, 2)
    body += text(sx + 20, sy + 34, "群聊截图(浅色壁纸)", 16, 700, "#4B6A85")
    # avatar + name + bubble with two lines
    body += rect(sx + 24, sy + 70, 56, 56, "#B8C7D6", "none", 10, 0)
    body += text(sx + 98, sy + 88, "小周", 16, 400, "#5B6B7A")
    body += rect(sx + 96, sy + 100, 340, 106, "#FFFFFF", "none", 10, 0)
    body += text(sx + 116, sy + 140, "周末一起去爬山吗,顺便", 22, 400, INK)
    body += text(sx + 116, sy + 180, "带上相机拍点照片", 22, 400, INK)
    # OCR boxes
    body += rect(sx + 94, sy + 70, 44, 26, "none", "#8E7CC3", 4, 2, "5,4")
    body += rect(sx + 112, sy + 116, 300, 32, "none", RED, 4, 2, "5,4")
    body += rect(sx + 112, sy + 156, 210, 32, "none", RED, 4, 2, "5,4")
    # sample points
    body += circle(sx + 104, sy + 132, 6, TEAL)
    body += circle(sx + 104, sy + 172, 6, TEAL)
    body += circle(sx + 200, sy + 152, 6, TEAL)
    body += circle(sx + 88, sy + 82, 6, AMBER)
    # second bubble (mine, green)
    body += rect(sx + 160, sy + 250, 270, 64, "#A9E08F", "none", 10, 0)
    body += text(sx + 180, sy + 290, "好啊,几点出发?", 22, 400, INK)
    body += rect(sx + 446 - 70, sy + 250, 0, 0, "none", "none")
    # legend in mock
    body += rect(sx + 20, sy + 360, sw - 40, 176, "#FFFFFFCC", "#9DB7CC", 12, 1)
    body += lines(sx + 40, sy + 394, [
        "红框:OCR 把一个气泡识别成两块",
        "紫框:群聊发言人名字",
        "绿点:取色点(行左侧内边距、行间空隙)",
        "橙点:名字左侧(落在壁纸上)",
    ], 16, 400, INK, 32)

    # right: rules
    rx = 548
    body += text(rx, 160, "问题 1:一个气泡被拆成几条", 22, 700, RED)
    body += lines(rx, 196, [
        "合并相邻两块,需同时满足:",
        "· 间距 ≤ 一个行高",
        "· 左边缘偏差 ≤ 一个行高",
        "  (OCR 框每次抖动约 20px,半个行高会漏合)",
        "· 两行左侧和行间空隙是同一种气泡底色",
        "· 时间戳、系统提示先过滤再合并",
    ], 17, 400, INK, 30)
    body += text(rx, 400, "问题 2:发言人名字被当成消息", 22, 700, "#8E7CC3")
    body += lines(rx, 436, [
        "三项同时满足才剔除:",
        "· 单行短文本,正下方紧跟一个气泡",
        "· 字号更小(名字 39–42px,消息 48–52px)",
        "· 名字左侧是壁纸,气泡左侧是气泡底色",
    ], 17, 400, INK, 30)
    body += rect(rx, 580, 484, 120, RED_L, RED, 12, 1.5)
    body += lines(rx + 18, 612, [
        "踩坑:浅色壁纸 #E7F0F9 与白色气泡色差只有 45,",
        "而合并用的容差是 48(为抗锯齿留的)。",
        "比较两块纯色时改用更严的容差 12。",
    ], 16, 400, INK, 28)

    # bottom: before / after
    by = 740
    body += rect(48, by, 480, 120, RED_L, RED, 12, 1.5)
    body += text(68, by + 34, "修复前:对方发了 3 条", 18, 700, RED)
    body += lines(68, by + 66, ["对方: 小周", "对方: 周末一起去爬山吗,顺便  /  对方: 带上相机拍点照片"], 15, 400, INK, 28)
    body += rect(552, by, 480, 120, TEAL_L, TEAL, 12, 1.5)
    body += text(572, by + 34, "修复后:对方发了 1 条", 18, 700, TEAL)
    body += lines(572, by + 66, ["对方: 周末一起去爬山吗,顺便带上相机拍点照片"], 15, 400, INK, 28)
    return svg(h, body + footer(h, "图 8 · 截图识别的两个修复"), "正确性:真机上才看得见的问题", None)


def fig9_obs():
    h = 860
    body = text(48, 106, "用户那边出了问题怎么知道?—— 不接第三方 SDK,数据只在用户手里", 20, 400, GRAY)
    # sources
    srcs = [("28 处调用点", "原来 27 处异常被静默吞掉"), ("崩溃处理器", "同步写 APP-901,再交还系统"), ("ApplicationExitInfo", "下次启动读上次退出原因")]
    for i, (t1, t2) in enumerate(srcs):
        y = 150 + i * 120
        body += rect(48, y, 280, 96, BLUE_L, NAVY, 14, 2)
        body += text(188, y + 40, t1, 19, 700, NAVY, "middle")
        body += text(188, y + 72, t2, 15, 400, INK, "middle")
        body += line(328, y + 48, 404, 280, GRAY, 2)
    # DiagLog
    body += rect(408, 210, 280, 150, TEAL_L, TEAL, 16, 2.5)
    body += text(548, 252, "DiagLog.record()", 20, 700, TEAL, "middle", MONO)
    body += lines(548, 288, ["只收错误码 + 数字 + 类名", "热路径:一次内存写入", "关闭开关 → 直接返回"], 15, 400, INK, 24, "middle")
    # storage
    body += line(688, 285, 762, 285, GRAY, 2.5)
    body += text(725, 270, "每 5 秒", 14, 400, GRAY, "middle")
    body += rect(766, 200, 266, 170, AMBER_L, AMBER, 16, 2)
    body += text(899, 240, "files/diagnostics/", 18, 700, AMBER_D, "middle", MONO)
    body += lines(899, 276, ["events.log ×2(256KB 轮转)", "summary:计数 + 耗时分桶", "不进云备份"], 15, 400, INK, 26, "middle")
    # export
    body += line(899, 370, 899, 440, GRAY, 2.5)
    body += rect(700, 444, 332, 150, "#FFFFFF", NAVY, 16, 2)
    body += text(866, 484, "高级设置 → 导出", 19, 700, NAVY, "middle")
    body += lines(866, 520, ["先预览全文", "用户点「分享 / 复制」", "才离开这台手机"], 15, 400, INK, 26, "middle")

    # export sample
    body += rect(48, 520, 620, 230, "#0F172A", "#0F172A", 14, 0)
    sample = [
        "== 上次进程退出原因 ==",
        "2026-11-15 01:41:49  CRASH",
        "== 错误计数 ==",
        "AI-108       12  网络不可用",
        "BUB-501       1  启动前台服务失败",
        "== 耗时 ==",
        "点击到出结果  n=86  P50 ≤ 3s  P95 ≤ 5s",
    ]
    body += lines(72, 556, sample, 16, 400, "#E2E8F0", 28, family=MONO)
    body += text(48, 784, "导出示例(数字为示意)", 15, 400, GRAY)

    # privacy rail
    body += rect(700, 620, 332, 150, RED_L, RED, 14, 1.5)
    body += text(866, 656, "隐私护栏", 19, 700, RED, "middle")
    body += lines(866, 690, ["不记聊天文本、Key、Base URL", "不记异常 message 和调用栈", "包名只在读屏 / OCR 记录里带"], 15, 400, INK, 25, "middle")
    return svg(h, body + footer(h, "图 9 · 可观测性"), "可观测性:错误码 + 本地诊断", None)


FIGS = [
    ("fig1-utility-tree", fig1_utility),
    ("fig2-quality-system", fig2_system),
    ("fig3-timeline", fig3_timeline),
    ("fig4-privacy-boundary", fig4_privacy),
    ("fig5-threads", fig5_threads),
    ("fig6-performance", fig6_perf),
    ("fig7-learning-transaction", fig7_txn),
    ("fig8-ocr-fixes", fig8_ocr),
    ("fig9-observability", fig9_obs),
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, fn in FIGS:
        s = fn()
        sp = os.path.join(OUT, name + ".svg")
        with open(sp, "w", encoding="utf-8") as f:
            f.write(s)
        cairosvg.svg2png(bytestring=s.encode("utf-8"), write_to=os.path.join(OUT, name + ".png"), scale=2)
        print("ok", name)
