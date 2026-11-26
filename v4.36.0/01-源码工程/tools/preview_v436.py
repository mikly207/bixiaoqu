#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.36.0 修复总览：图标新旧对比 + 浅色模式可读性 + 评论点赞 + 流畅度"""
import io, os
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = ROOT + "/app/res/drawable/"
OLD = "/root/.codebuddy/artifact/bixiaoqu_bak_v435/app/res/drawable/"
OUT = "/workspace/哔小趣-v4.36.0-修复总览.png"

W, H = 1400, 1880
BG = "#111318"
PANEL = "#1b1e24"
LINE = "#2a2e37"
FG = "#e8eaf0"
SUB = "#9aa0ab"
PINK = "#fb7299"
GREEN = "#4ec9a0"
AMBER = "#ffc53d"
RED = "#ff6b6b"

CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"
_cache = {}
def font(px, bold=False):
    key = (px, bold)
    if key in _cache: return _cache[key]
    for idx, name in ((1, "Bold"), (0, "Regular")):
        if bold and idx == 0: continue
        p = CJK % name
        if os.path.exists(p):
            f = ImageFont.truetype(p, px, index=idx)
            _cache[key] = f
            return f
    f = ImageFont.load_default()
    _cache[key] = f
    return f

F = lambda px, b=False: font(px, b)

def text(d, xy, s, size=18, fill=FG, bold=False, anchor=None):
    d.text(xy, s, font=F(size, bold), fill=fill, anchor=anchor)

def panel(d, x, y, w, h):
    d.rounded_rectangle((x, y, x + w, y + h), radius=18, fill=PANEL, outline=LINE, width=1)

def bar(d, x, y, w, color, h=4):
    d.rounded_rectangle((x, y, x + w, y + h), radius=h / 2, fill=color)

A = "{http://schemas.android.com/apk/res/android}"

def render_icon(xml_path, color, size=96):
    """把 Android vector 转成 PNG；color 模拟 setColorFilter 的 tint"""
    root = ET.parse(xml_path).getroot()
    vw = root.get(A + "viewportWidth", "24")
    vh = root.get(A + "viewportHeight", "24")
    parts = []

    def walk(node, prefix=""):
        tr = ""
        g = node
        if g.tag == "group":
            sx = g.get(A + "scaleX")
            sy = g.get(A + "scaleY")
            px = float(g.get(A + "pivotX", "12"))
            py = float(g.get(A + "pivotY", "12"))
            if sx or sy:
                sx = float(sx or 1); sy = float(sy or 1)
                tr = "translate(%.3f %.3f) scale(%.3f %.3f)" % (
                    px * (1 - sx), py * (1 - sy), sx, sy)
        for p in node:
            if p.tag == "path":
                d = p.get(A + "pathData")
                if not d: continue
                fill = color or p.get(A + "fillColor") or "none"
                rule = p.get(A + "fillType") or "nonzero"
                sc = p.get(A + "strokeColor")
                sw = p.get(A + "strokeWidth")
                lc = p.get(A + "strokeLineCap")
                lj = p.get(A + "strokeLineJoin")
                attrs = ['d="%s"' % d, 'fill="%s"' % fill,
                         'fill-rule="%s"' % rule]
                if sc and sc != "@null":
                    attrs.append('stroke="%s"' % (color or sc))
                if sw: attrs.append('stroke-width="%s"' % sw)
                if lc: attrs.append('stroke-linecap="%s"' % lc.lower())
                if lj: attrs.append('stroke-linejoin="%s"' % lj.lower())
                parts.append("<path %s/>" % " ".join(attrs))
            elif p.tag == "group":
                walk(p)

    walk(root)
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" '
           'width="%d" height="%d">%s</svg>') % (
               vw, vh, size, size, "".join(parts))
    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"),
                           output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")

NAMES = [("like", "点赞"), ("comment", "评论"), ("coin", "投币"),
         ("star", "收藏"), ("share", "转发")]

def icon_row(img, d, x, y, directory, color, size=88, gap=205, on_map=None):
    for i, (key, label) in enumerate(NAMES):
        p = os.path.join(directory, "ic_%s.xml" % key)
        if on_map and key in on_map:
            q = os.path.join(directory, "ic_%s_on.xml" % on_map[key])
            if os.path.exists(q): p = q
        ic = render_icon(p, color, size)
        cx = x + i * gap
        img.paste(ic, (cx, y), ic)
        text(d, (cx + size / 2, y + size + 16), label, 17, FG, anchor="mm")

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# ---------------- 标题 ----------------
text(d, (60, 42), "哔小趣 v4.36.0", 42, FG, True)
bar(d, 60, 100, 1280, PINK, 3)
text(d, (60, 112), "交互图标重绘 · 评论点赞 · 流畅度再优化", 21, SUB)

# ---------------- 面板 1：图标重绘 ----------------
panel(d, 40, 150, 1320, 420)
bar(d, 64, 174, 6, PINK, 24)
text(d, (84, 170), "① 交互图标重绘 —— 从细线稿改为实心面块", 26, FG, True)
text(d, (84, 212), "点赞 / 评论 / 投币 / 收藏 / 转发统一重绘，笔画铺满 24×24 视觉框；投币与收藏用镂空做层次，转发改圆头粗描边", 16, SUB)

text(d, (84, 262), "旧版 · 线稿偏细，视觉发飘", 17, RED)
icon_row(img, d, 148, 286, OLD, "#ffffff", 78, 205)
text(d, (84, 420), "新版 · 实心饱满，面块有分量", 17, GREEN)
icon_row(img, d, 148, 444, NEW, "#ffffff", 78, 205)

# ---------------- 面板 2：浅色模式 ----------------
panel(d, 40, 590, 1320, 400)
bar(d, 64, 614, 6, AMBER, 24)
text(d, (84, 610), "② 浅色模式可读性 —— 白底白图标 → 跟随主文字色", 26, FG, True)
text(d, (84, 652), "根因：互动栏未选中态写死 Color.WHITE；底部栏落在 #f4f5f7 面板上，白图标几乎不可见", 16, SUB)

# 浅色底样块
d.rounded_rectangle((84, 690, 1316, 756), radius=12, fill="#f4f5f7")
text(d, (100, 700), "旧版", 15, "#8a909c")
icon_row(img, d, 180, 692, OLD, "#ffffff", 52, 205)
d.rounded_rectangle((84, 820, 1316, 886), radius=12, fill="#f4f5f7")
text(d, (100, 830), "新版", 15, "#61666d")
icon_row(img, d, 180, 822, NEW, "#18191c", 52, 205)
text(d, (1130, 850), "清晰可读", 16, GREEN, True, anchor="mm")

# ---------------- 面板 3：评论点赞 ----------------
panel(d, 40, 1010, 1320, 430)
bar(d, 64, 1034, 6, GREEN, 24)
text(d, (84, 1030), "③ 评论点赞 —— 主评论与楼中楼都能点", 26, FG, True)
text(d, (84, 1072), "点一下立刻变色 + 数字 +1，后台请求失败自动回滚；同一条正在请求时不会重复提交", 16, SUB)

def comment_row(y, liked, n, color):
    d.ellipse((84, y, 124, y + 40), fill="#2f3540")
    text(d, (140, y + 2), "狂pk血影", 17, "#aeb4c0", True)
    text(d, (140, y + 30), "[黑眼圈_金箍]  18天前", 15, "#7f8794")
    # 点赞按钮
    bx = 820
    d.rounded_rectangle((bx - 10, y + 16, bx + 118, y + 52), radius=16,
                        fill=("#30232b" if liked else "#22262d"))
    ic = render_icon(os.path.join(NEW, "ic_%s.xml" % ("like_on" if liked else "like")),
                     color, 28)
    img.paste(ic, (bx, y + 20), ic)
    text(d, (bx + 36, y + 34), "%s 赞" % n, 16, color, True)

comment_row(1120, False, 12, "#8a909c")
text(d, (1000, 1148), "未点赞 · 灰", 15, SUB)
comment_row(1220, True, 13, PINK)
text(d, (1000, 1248), "已点赞 · 主题色", 15, PINK)

text(d, (84, 1330), "乐观更新 · 失败回滚 · 防连点 · 主评论与二级回复共用状态", 16, GREEN)

# ---------------- 面板 4：流畅度 ----------------
panel(d, 40, 1460, 1320, 300)
bar(d, 64, 1484, 6, "#5aa9e6", 24)
text(d, (84, 1480), "④ 流畅度再优化", 26, FG, True)
items = [
    ("评论行 ViewHolder 复用", "滚动不再 new 一整套 TextView / ImageView，消除 GC 抖动"),
    ("稳定 id 用 rpid", "ListView 才能正确复用行，不再按位置错配"),
    ("楼中楼按需重建", "加内容版本号，只有数据真变了才重建那块 View"),
    ("图标状态免重复设资源", "复用行状态未变时跳过 setImageResource，少一次重绘"),
]
for i, (t, s) in enumerate(items):
    y = 1530 + i * 52
    d.ellipse((86, y + 4, 96, y + 14), fill=PINK)
    text(d, (110, y - 2), t, 18, FG, True)
    text(d, (400, y), s, 16, SUB)

# ---------------- 页脚 ----------------
bar(d, 60, 1790, 1280, LINE, 2)
text(d, (60, 1810), "versionCode 136  ·  守卫 314 项 / 单测 230 项全通过  ·  平板分屏差异完整保留", 16, SUB)

img.save(OUT)
print("saved:", OUT, img.size)
