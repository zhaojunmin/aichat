#!/usr/bin/env python3
"""Generate the article's figures as SVG, then render each to PNG (2x) with cairosvg.

    python3 build_figures.py        # writes figures/*.svg and figures/*.png

Same palette and primitives as ../quality-retrospective so the two articles match.
No real chat content appears in any figure.
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
AMBER_D = "#B45309"
PURPLE = "#8E7CC3"
PURPLE_L = "#F1EEF9"
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
    markers = "".join(
        f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
        f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{col}"/></marker>'
        for mid, col in (("arr", GRAY), ("arrT", TEAL), ("arrR", RED), ("arrN", NAVY), ("arrP", PURPLE))
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" viewBox="0 0 {W} {h}">'
        f"<defs>{markers}</defs>"
        f'<rect width="{W}" height="{h}" fill="#FFFFFF"/>' + head + body + "</svg>"
    )


def text(x, y, s, size=20, weight=400, fill=INK, anchor="start", family=SANS, italic=False):
    st = ' font-style="italic"' if italic else ""
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}"{st} xml:space="preserve">{escape(s)}</text>')


def lines(x, y, rows, size=20, weight=400, fill=INK, gap=None, anchor="start", family=SANS):
    gap = gap or int(size * 1.5)
    return "".join(text(x, y + i * gap, r, size, weight, fill, anchor, family) for i, r in enumerate(rows))


def rect(x, y, w, h, fill=LIGHT, stroke=BORDER, rx=12, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{d}/>')


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


def tw(s, size):
    """Rough text width: CJK ≈ 1em, ASCII ≈ 0.56em (0.6em mono)."""
    return sum(size if ord(c) > 0x2E80 else size * 0.58 for c in s)


def chip(x, y, label, fill, color="#FFFFFF", size=16, padx=10, h=28):
    w = tw(label, size) + 2 * padx
    return (rect(x, y, w, h, fill, fill, rx=h // 2, sw=1)
            + text(x + w / 2, y + h * 0.68, label, size, 700, color, "middle")), w


def cross(cx, cy, r=14, color=RED):
    return (circle(cx, cy, r + 3, "#FFFFFF")
            + f'<line x1="{cx-r*0.6}" y1="{cy-r*0.6}" x2="{cx+r*0.6}" y2="{cy+r*0.6}" stroke="{color}" stroke-width="5" stroke-linecap="round"/>'
            + f'<line x1="{cx-r*0.6}" y1="{cy+r*0.6}" x2="{cx+r*0.6}" y2="{cy-r*0.6}" stroke="{color}" stroke-width="5" stroke-linecap="round"/>')


def check(cx, cy, r=14, color=TEAL):
    return (circle(cx, cy, r + 3, "#FFFFFF") + circle(cx, cy, r, color)
            + f'<path d="M{cx-r*0.5},{cy} L{cx-r*0.1},{cy+r*0.4} L{cx+r*0.55},{cy-r*0.4}" '
              f'stroke="#FFFFFF" stroke-width="4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')


def code(x, y, rows, size=17, fill=INK, gap=None):
    return lines(x, y, rows, size, 400, fill, gap or int(size * 1.55), family=MONO)


def footer(h, note):
    return text(W - 48, h - 24, note, 15, 400, "#9CA3AF", "end")


# ---------------------------------------------------------------- figures
def fig1_timeline():
    phases = [
        ("命名", PURPLE, [("#40", "Bubble 一词两义 → 统一:Bubble 只指悬浮球,消息用 Message")]),
        ("代码规范", NAVY, [
            ("#41", "Android Kotlin 风格指南 → ktlint + Spotless,CI 加 style 任务"),
            ("", "全仓库修正 85 个文件;超 100 列的行 138 → 0"),
        ]),
        ("静态检查", AMBER_D, [("#42", "存量 Lint 19 条清零;删掉 baseline,warningsAsErrors")]),
        ("架构规则", TEAL, [
            ("#43", "Konsist:包依赖白名单、命名规则写成单测"),
            ("#44", "compose-lints:Compose 专项检查"),
        ]),
        ("架构重构", RED, [
            ("#45", "调试数据移到 DebugCapture;删死代码"),
            ("#46", "ScreenAccess 接口 + AccessibilityBridge,删掉 AppBus"),
            ("#47", "读取流程抽成 ChatCapture,补 5 个单测"),
        ]),
        ("验证", GRAY, [("真机", "release 包冒烟:OCR 路径 + 控件树路径,0 崩溃")]),
    ]
    y = 150
    body = text(48, 112, "一天 8 个 PR:每个都可单独回滚,分支一律以 main 为 base", 20, 400, GRAY)
    axis_top = y + 30
    for phase, col, items in phases:
        c, _ = chip(48, y, phase, col, size=17, h=34, padx=16)
        body += c
        y += 66
        for num, title in items:
            if num:
                body += circle(96, y - 7, 8, col)
                body += text(124, y, num, 19, 700, col, family=MONO)
            body += text(214, y, title, 20, 400, INK if num else GRAY)
            y += 42
        y += 12
    body = line(96, axis_top, 96, y - 40, BORDER, 4, None) + body
    h = y + 40
    return svg(h, body + footer(h, "图 1 · 当天时间线"), "从一个命名问题开始的一天", None)


def fig2_naming():
    h = 830
    body = text(48, 112, "同一个词在代码里指两样东西,读的人只能靠上下文猜", 20, 400, GRAY)
    # the word
    body += rect(420, 150, 240, 84, NAVY, NAVY, 42, 0)
    body += text(540, 205, "Bubble", 36, 700, "#FFFFFF", "middle", MONO)
    # two meanings
    body += path("M480,234 C420,280 300,270 270,310", TEAL, 3, "arrT")
    body += path("M600,234 C660,280 780,270 810,310", RED, 3, "arrR")
    body += rect(60, 316, 440, 190, TEAL_L, TEAL, 14)
    body += text(84, 356, "意思 A:悬浮球", 24, 700, TEAL)
    body += code(84, 396, ["bubble/ 包", "FloatingBubbleService", "BubblePanel · BubbleStyle", "Tracing.BUBBLE_*"], 17)
    body += rect(580, 316, 440, 190, RED_L, RED, 14)
    body += text(604, 356, "意思 B:聊天消息气泡", 24, 700, RED)
    body += code(604, 396, ["BubbleLocator · BubbleBox", "BubbleLineMerger", "locateBubbles()", "sameBubble · gapIsBubble"], 17)
    # the confusing spot
    body += rect(60, 536, 960, 120, LIGHT, BORDER, 14)
    body += text(84, 572, "最容易读错的地方(FloatingBubbleService)", 18, 700, GRAY)
    body += code(84, 606, ["// Hide our own bubble so it isn't part of the captured image", "boxes = reader.locateBubbles(pkg)   // ← 找自己的悬浮球?"], 17)
    body += text(48, 704, "约定:代码标识符里 Bubble 只指悬浮球;聊天消息一律用 Message", 20, 700, NAVY)
    body += text(48, 738, "BubbleLocator → MessageBoundsLocator · BubbleLineMerger → MessageLineMerger · …", 17, 400, INK, family=MONO)
    body += text(48, 772, "描述外观的注释(bubble background / padding)保留——那是屏幕上真实的视觉气泡。", 18, 400, GRAY)
    return svg(h, body + footer(h, "图 2 · 一词两义"), "起点:Bubble 到底指什么?", None)


def fig3_gates():
    h = 790
    body = text(48, 112, "规则都写在仓库里,本地与 CI 跑同一组命令", 20, 400, GRAY)
    # PR source
    body += rect(48, 300, 120, 80, NAVY, NAVY, 14, 0)
    body += text(108, 348, "PR", 26, 700, "#FFFFFF", "middle")
    # style job
    def job(x, y, w, title, col, fill, items):
        s = rect(x, y, w, 46 + 64 * len(items), fill, col, 14)
        s += text(x + 20, y + 32, title, 20, 700, col)
        for i, (name, rule, src) in enumerate(items):
            yy = y + 54 + i * 64
            s += rect(x + 16, yy, w - 32, 54, "#FFFFFF", BORDER, 10)
            s += text(x + 30, yy + 23, name, 18, 700, INK)
            s += text(x + 30, yy + 45, rule, 15, 400, GRAY)
            s += text(x + w - 30, yy + 23, src, 14, 400, col, "end", MONO)
        return s
    body += job(230, 150, 380, "style 任务(~1 分钟)", TEAL, TEAL_L, [
        ("Kotlin 规范", "ktlint android_studio 风格 · 100 列", ".editorconfig"),
        ("架构 / 命名规则", "Konsist:包依赖白名单、命名约定", "quality/architecture"),
    ])
    body += job(230, 370, 380, "build 任务", NAVY, BLUE_L, [
        ("单元测试", "JVM + Robolectric · 181 个", "app/src/test"),
        ("Android Lint", "无 baseline · warningsAsErrors", "lint { }"),
        ("Compose 检查", "compose-lints(Slack)", "lintChecks"),
        ("构建", "assembleDebug · 基准编译", ""),
    ])
    body += path("M168,330 C200,330 200,250 228,250", GRAY, 2.5)
    body += path("M168,350 C200,350 200,520 228,520", GRAY, 2.5)
    # merge
    body += rect(700, 300, 150, 80, TEAL, TEAL, 14, 0)
    body += text(775, 348, "可合并", 24, 700, "#FFFFFF", "middle")
    body += path("M610,250 C660,250 660,330 698,330", TEAL, 2.5, "arrT")
    body += path("M610,520 C660,520 660,350 698,350", TEAL, 2.5, "arrT")
    # side notes
    body += lines(880, 300, ["本地先跑", "同一组命令", "全绿才开 PR"], 18, 400, GRAY)
    body += rect(48, 700, 984, 1, BORDER, BORDER, 0, 0)
    body += text(48, 740, "原则:新问题直接让 CI 失败,不靠 baseline 冻结存量;误报就地 @SuppressLint / tools:ignore 并写明理由。", 18, 400, INK)
    return svg(h, body + footer(h, "图 3 · 质量门禁"), "四道静态门禁 + 测试", None)


def fig4_style_pitfalls():
    cards = [
        ("守护进程缓存了 .editorconfig", RED,
         "改了「关闭尾随逗号规则」,格式化却仍按旧配置删掉了一批逗号",
         "改规则后先 ./gradlew --stop;干脆改为强制尾随逗号,误删的自动补回"),
        ("Spotless 每个文件只报第一条", AMBER_D,
         "报告 16 处违规,实际超 100 列的行有 138 行",
         "自己用脚本按字符数扫全仓库,心里先有真实规模"),
        ("匹配范围扫进了 build/", PURPLE,
         "quality/**/src/** 命中了生成源码,和打包任务产生隐式依赖,构建报错",
         "target 收窄到 quality/*/src,并 targetExclude(\"**/build/**\")"),
        ("ktlint 管不到的规则", NAVY,
         "超长的纯注释行、反引号测试名、approxMB 这类缩写都没报",
         "对照指南逐条补扫:注释折行、6 个测试名改驼峰、approxMB → approxMb"),
    ]
    y0, ch = 140, 150
    body = text(48, 112, "工具跑通 ≠ 规范落地:每个坑都是「以为过了,其实没过」", 20, 400, GRAY)
    for i, (title, col, sym, fix) in enumerate(cards):
        y = y0 + i * (ch + 18)
        body += rect(48, y, 984, ch, "#FFFFFF", BORDER, 14)
        body += rect(48, y, 10, ch, col, col, 4, 0)
        body += badge(96, y + 40, str(i + 1), col, 18, 20)
        body += text(128, y + 48, title, 24, 700, INK)
        body += text(128, y + 88, "现象:" + sym, 18, 400, GRAY)
        body += text(128, y + 124, "处理:" + fix, 18, 400, col)
    y = y0 + 4 * (ch + 18)
    body += rect(48, y, 984, 110, LIGHT, BORDER, 14)
    body += text(72, y + 38, "提交怎么拆", 20, 700, NAVY)
    c1, w1 = chip(72, y + 56, "① 配置 + CI", NAVY, size=16)
    c2, w2 = chip(72 + w1 + 40, y + 56, "② 全仓库格式化(无逻辑改动)", TEAL, size=16)
    c3, _ = chip(72 + w1 + w2 + 80, y + 56, "③ 把 ② 记入 .git-blame-ignore-revs", PURPLE, size=16)
    body += c1 + c2 + c3
    body += line(72 + w1 + 6, y + 70, 72 + w1 + 34, y + 70, GRAY, 2)
    body += line(72 + w1 + w2 + 46, y + 70, 72 + w1 + w2 + 74, y + 70, GRAY, 2)
    h = y + 110 + 60
    return svg(h, body + footer(h, "图 4 · 规范检查的四个坑"), "把风格指南变成 CI 门禁", None)


def fig5_lint():
    h = 850
    body = text(48, 112, "19 条存量问题(1 error + 18 warning)全部修掉,baseline 文件删除", 20, 400, GRAY)
    # NewApi before/after
    body += text(48, 166, "唯一的 error:NewApi —— 版本判断藏在变量里,Lint 跟不过去", 22, 700, INK)
    body += rect(48, 186, 474, 250, RED_L, RED, 14)
    body += text(72, 222, "改前", 18, 700, RED)
    body += code(72, 260, [
        "val useOcr = pkg in ocrSet &&",
        "    SDK_INT >= R",
        "if (!useOcr) { …; return conv }",
        "…",
        "reader.captureScreenshot(pkg)",
        "      ^ NewApi: 需要 API 30",
    ], 16)
    body += rect(558, 186, 474, 250, TEAL_L, TEAL, 14)
    body += text(582, 222, "改后", 18, 700, TEAL)
    body += code(582, 260, [
        "return if (pkg in ocrSet &&",
        "    SDK_INT >= R) {",
        "    readViaOcr(pkg)",
        "} else readFromNodeTree(pkg)",
        "",
        "@RequiresApi(R) fun readViaOcr(…)",
    ], 16)
    body += line(524, 311, 554, 311, GRAY, 3)
    body += text(48, 470, "运行时其实不会出错,但这说明代码不够直白——Lint 看不懂的,人也容易看漏。", 18, 400, GRAY)
    # the rest
    body += text(48, 520, "其余 18 条", 22, 700, INK)
    rows = [
        ("InlinedApi", "API 34 的前台服务类型从 API 29 就开始传 → 改为 34 起才传", RED),
        ("PluralsCandidate ×4", "英文「1 messages」→ 改成「Messages: 1」这种标签写法", AMBER_D),
        ("SetTextI18n ×2", "悬浮球 \"AI\"、候选序号 → 不翻译的字符串资源", AMBER_D),
        ("UseKtx ×3", "SharedPreferences.edit { } · String.toUri()", NAVY),
        ("Monochrome / Unused", "补单色主题图标(眼睛镂空);删掉无用的圆形图标", NAVY),
        ("其余", "v26 目录合并、tools:targetApi、依赖进 version catalog、ChromeOS 检查注明理由后关闭", GRAY),
    ]
    for i, (rule, what, col) in enumerate(rows):
        y = 552 + i * 38
        c, _ = chip(48, y, rule, col, size=14, h=26)
        body += c
        body += text(270, y + 19, what, 17, 400, INK)
    body += text(48, 800, "之后:lint { warningsAsErrors = true },不再设 baseline——warning 会悄悄堆积,这 18 条就是这么来的。", 17, 700, NAVY)
    return svg(h + 20, body + footer(h + 20, "图 5 · Lint 清零"), "Lint 清零,并且不留后门", None)


def fig6_appbus_before():
    h = 720
    body = text(48, 112, "一个根包里的 object,装了四件不相干的事", 20, 400, GRAY)
    # AppBus box
    bx, by, bw, bh = 330, 170, 420, 380
    body += rect(bx, by, bw, bh, AMBER_L, AMBER, 16, 2.5)
    body += text(bx + bw / 2, by + 44, "AppBus(根包)", 26, 700, AMBER_D, "middle", MONO)
    parts = [
        ("无障碍能力", "chatWindowReader / replyFiller", "两个字段分开赋值;连接状态不能订阅", NAVY),
        ("前台 App", "foreground: StateFlow", "本身没问题,只是放错了地方", TEAL),
        ("调试数据", "conversation / lastRawDump", "含聊天原文,和服务连接无关", PURPLE),
        ("悬浮球引用", "bubbleService", "只写不读的死代码 + 内存泄漏压制", RED),
    ]
    for i, (name, field, note, col) in enumerate(parts):
        y = by + 70 + i * 74
        body += rect(bx + 18, y, bw - 36, 64, "#FFFFFF", col, 10)
        body += text(bx + 34, y + 26, name, 18, 700, col)
        body += text(bx + 34, y + 50, field, 14, 400, GRAY, family=MONO)
    # services
    body += rect(48, 250, 220, 90, BLUE_L, NAVY, 14)
    body += lines(158, 286, ["ChatAccessibility", "Service"], 17, 700, NAVY, 22, "middle", MONO)
    body += rect(48, 400, 220, 90, BLUE_L, NAVY, 14)
    body += lines(158, 436, ["FloatingBubble", "Service"], 17, 700, NAVY, 22, "middle", MONO)
    body += rect(812, 320, 220, 90, BLUE_L, NAVY, 14)
    body += text(922, 372, "设置界面 ui/", 18, 700, NAVY, "middle")
    body += line(268, 290, 344, 280, GRAY, 2.5)
    body += text(274, 270, "写", 16, 400, GRAY)
    body += line(268, 445, 344, 280, GRAY, 2.5)
    body += line(268, 455, 344, 480, GRAY, 2.5)
    body += text(282, 500, "读+写", 16, 400, GRAY)
    body += line(810, 365, 752, 365, GRAY, 2.5)
    # cycle warning
    body += path(f"M{bx+bw/2-60},{by+bh} C{bx+bw/2-60},{by+bh+60} 158,{by+bh+40} 158,492", RED, 2.5, "arrR", dash="7 6")
    body += text(bx + 40, by + bh + 52, "包循环:根包 ⇄ accessibility / bubble 互相 import", 17, 700, RED)
    issues = ["服务定位器:真实依赖藏在全局可变字段里", "悬浮球依赖具体的 reader / filler,JVM 上换不成假实现",
              "读屏流程(控件树 / OCR 分流、截图、裁键盘)写在 536 行的 Service 里"]
    body += lines(48, by + bh + 104, ["· " + s for s in issues], 17, 400, INK, 28)
    return svg(h + 20, body + footer(h + 20, "图 6 · 重构前"), "重新审视 AppBus", None)


def fig7_after():
    h = 760
    body = text(48, 112, "中转站保留(无障碍服务的 onBind 是 final,别人绑不上它),但职责拆开、依赖接口", 20, 400, GRAY)
    # left: a11y service implementing ScreenAccess
    body += rect(48, 170, 290, 200, BLUE_L, NAVY, 14)
    body += lines(193, 206, ["ChatAccessibility", "Service"], 18, 700, NAVY, 24, "middle", MONO)
    body += rect(66, 266, 254, 86, "#FFFFFF", NAVY, 10)
    body += text(193, 296, "AccessibilityScreen", 16, 700, INK, "middle", MONO)
    body += text(193, 324, "ChatWindowReader + ReplyFiller", 14, 400, GRAY, "middle", MONO)
    # bridge
    body += rect(400, 170, 300, 200, TEAL_L, TEAL, 14, 2.5)
    body += text(550, 206, "AccessibilityBridge", 19, 700, TEAL, "middle", MONO)
    body += code(420, 248, ["screen:", "  StateFlow<ScreenAccess?>", "foreground:", "  StateFlow<ForegroundApp?>"], 15, INK, 26)
    body += line(338, 230, 396, 230, NAVY, 2.5, "arrN")
    body += text(367, 220, "连接", 15, 400, NAVY, "middle")
    body += line(338, 330, 396, 330, NAVY, 2.5, "arrN", dash="6 5")
    body += text(367, 354, "断开", 15, 400, NAVY, "middle")
    # overlay
    body += rect(762, 170, 270, 200, BLUE_L, NAVY, 14)
    body += lines(897, 206, ["FloatingBubble", "Service"], 18, 700, NAVY, 24, "middle", MONO)
    body += lines(897, 274, ["窗口 · 拖拽 · 面板", "生成 · 剪贴板"], 16, 400, GRAY, 26, "middle")
    body += text(897, 346, "实现 CaptureOverlay", 15, 700, PURPLE, "middle", MONO)
    body += line(760, 260, 704, 260, TEAL, 2.5, "arrT")
    body += text(732, 250, "订阅", 14, 400, TEAL, "middle")
    # ScreenAccess interface
    body += rect(400, 420, 300, 120, "#FFFFFF", TEAL, 14, 2, dash="8 6")
    body += text(550, 452, "«interface» ScreenAccess", 16, 700, TEAL, "middle", MONO)
    body += code(424, 484, ["readConversation · fill", "locateMessageBounds", "captureScreenshot · imeBounds"], 14, GRAY, 22)
    body += line(193, 372, 398, 470, NAVY, 2, "arrN", dash="4 4")
    body += text(250, 440, "实现", 14, 400, NAVY)
    # ChatCapture
    body += rect(762, 420, 270, 140, PURPLE_L, PURPLE, 14)
    body += text(897, 456, "ChatCapture", 19, 700, PURPLE, "middle", MONO)
    body += lines(897, 488, ["控件树 / OCR 分流", "截图 · 裁键盘 · 引导 OCR"], 15, 400, INK, 24, "middle")
    body += line(897, 372, 897, 416, PURPLE, 2.5, "arrP")
    body += line(760, 480, 704, 480, PURPLE, 2.5, "arrP")
    body += text(732, 470, "只依赖", 14, 400, PURPLE, "middle")
    # DebugCapture + OCR
    body += rect(762, 600, 130, 60, LIGHT, BORDER, 10)
    body += text(827, 636, "OcrReader", 15, 700, GRAY, "middle", MONO)
    body += rect(902, 600, 130, 60, LIGHT, BORDER, 10)
    body += text(967, 636, "DebugCapture", 15, 700, GRAY, "middle", MONO)
    body += line(840, 562, 830, 596, GRAY, 2)
    body += line(950, 562, 960, 596, GRAY, 2)
    # rules box
    body += rect(48, 590, 650, 140, LIGHT, BORDER, 14)
    body += text(72, 624, "Konsist 把结构钉住(违反就 CI 失败)", 18, 700, NAVY)
    body += lines(72, 656, [
        "· bubble 读屏只能用 AccessibilityBridge,读取一律经 capture",
        "· capture 只能经 ScreenAccess 访问读屏,碰不到具体实现",
        "· 根包只放 ChatAssistantApp —— 不让下一个 AppBus 长出来",
    ], 16, 400, INK, 26)
    return svg(h, body + footer(h, "图 7 · 重构后"), "端口 + 中转站 + 用例", None)


def fig8_prs():
    h = 660
    body = text(48, 112, "三步各自可合并;分支依次叠加,但 PR 的 base 都是 main", 20, 400, GRAY)
    ys = {"main": 200, "pr1": 300, "pr2": 390, "pr3": 480}
    # main line
    body += line(80, ys["main"], 1000, ys["main"], NAVY, 5, None)
    body += text(80, ys["main"] - 18, "main", 20, 700, NAVY, family=MONO)
    def commit(x, y, col, label=None):
        s = circle(x, y, 12, col, "#FFFFFF")
        if label:
            s += text(x, y + 38, label, 15, 400, GRAY, "middle", MONO)
        return s
    body += commit(140, ys["main"], NAVY)
    rows = [
        ("pr1", "refactor/debug-capture", "#45 DebugCapture", 140, 260, PURPLE),
        ("pr2", "refactor/accessibility-bridge", "#46 ScreenAccess + Bridge", 260, 380, TEAL),
        ("pr3", "refactor/chat-capture", "#47 ChatCapture + 单测", 380, 500, RED),
    ]
    prev_y = ys["main"]
    for key, br, title, x0, x1, col in rows:
        y = ys[key]
        body += path(f"M{x0},{prev_y} C{x0},{y} {x0+40},{y} {x0+80},{y}", col, 4, None)
        body += line(x0 + 80, y, x1, y, col, 4, None)
        body += commit(x1, y, col)
        body += text(x1 + 26, y + 7, title, 18, 700, col)
        body += text(x1 + 26, y + 32, br, 14, 400, GRAY, family=MONO)
        prev_y = y
    # merges into main
    for i, (key, _, title, _, x1, col) in enumerate(rows):
        mx = 780 + i * 90
        sx = x1 + 26 + tw(title, 18) + 14  # start after the label, so the curve never crosses it
        y = ys[key]
        body += path(f"M{sx},{y - 6} C{mx},{y - 6} {mx},{ys['main'] + 60} {mx},{ys['main'] + 14}",
                     col, 2.5, None, dash="6 6")
        body += commit(mx, ys["main"], col)
    body += text(840, ys["main"] - 24, "按 #45 → #46 → #47 合并", 16, 700, NAVY, "middle")
    body += rect(48, 530, 984, 80, LIGHT, BORDER, 12)
    body += text(72, 562, "反例:#46 以 #45 的分支为 base —— #45 合并、分支删除后,#46 会被关闭或失去目标(以前踩过)。", 16, 400, INK)
    body += text(72, 592, "base=main:前一个合并后,后一个 PR 的 diff 自动收缩到只剩自己的提交。", 16, 700, TEAL)
    return svg(h, body + footer(h, "图 8 · 三个 PR 的分支策略"), "拆成三步,但不叠成栈", None)


def fig9_smoke():
    h = 720
    body = text(48, 112, "不看界面内容判断走了哪条路:用 atrace 里的 trace 段", 20, 400, GRAY)
    # two taps
    def lane(y, app, where, sections, col, verdict):
        s = rect(48, y, 984, 150, "#FFFFFF", BORDER, 14)
        s += text(72, y + 40, app, 22, 700, INK)
        s += text(72, y + 70, where, 16, 400, GRAY)
        x = 330
        for name, c in sections:
            cc, w = chip(x, y + 24, name, c, size=15, h=30, padx=12)
            s += cc
            x += w + 14
        s += check(1000, y + 40, 14)
        s += text(330, y + 110, verdict, 17, 700, col)
        return s
    body += lane(150, "微信 · 文件传输助手", "OCR 名单里的 App", [
        ("bubble.tapToResult", NAVY), ("bubble.ocr", PURPLE), ("bubble.generate", GRAY)], PURPLE,
        "走截图 OCR,没有 a11y.parse;截图时悬浮球已隐藏")
    body += lane(320, "Soul · 个人主页", "控件树 App;不进任何聊天", [
        ("bubble.tapToResult", NAVY), ("a11y.parse", TEAL), ("bubble.generate", GRAY)], TEAL,
        "走控件树,没有 bubble.ocr;读到页面文本并出候选")
    # pitfalls
    body += text(48, 516, "冒烟时踩到的坑", 20, 700, INK)
    pits = [
        ("adb.exe shell 导出 trace", "Windows 侧把 \\n 改成 \\r\\n,压缩数据直接损坏 → 改用 adb exec-out", RED),
        ("统计全是 0", "trace 行格式是「: S|pid|名称|cookie」,正则多写了一个 |", AMBER_D),
        ("QQ 被「健康使用手机」挡住", "不点「继续使用」绕过,换 Soul 个人主页验证控件树路径", GRAY),
    ]
    for i, (t, d, col) in enumerate(pits):
        y = 540 + i * 50
        body += cross(66, y + 14, 10, col) if col != GRAY else circle(66, y + 14, 10, col)
        body += text(92, y + 21, t, 18, 700, col)
        body += text(400, y + 21, d, 16, 400, INK)
    return svg(h, body + footer(h, "图 9 · 真机冒烟"), "真机冒烟:两条读取路径", None)


FIGS = [
    ("fig1-timeline", fig1_timeline),
    ("fig2-naming", fig2_naming),
    ("fig3-quality-gates", fig3_gates),
    ("fig4-style-pitfalls", fig4_style_pitfalls),
    ("fig5-lint", fig5_lint),
    ("fig6-appbus-before", fig6_appbus_before),
    ("fig7-appbus-after", fig7_after),
    ("fig8-three-prs", fig8_prs),
    ("fig9-device-smoke", fig9_smoke),
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
