#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.37.0 修复总览：按参考图重绘实心图标 + 自动分辨率提速 + 去 iOS"""
import io, os
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = ROOT + "/app/res/drawable/"
OLD = "/root/.codebuddy/artifact/bixiaoqu_bak_v436/app/res/drawable/"
OUT = "/workspace/哔小趣-v4.37.0-修复总览.png"

W, H = 1400, 1780
BG = "#111318"; PANEL = "#1b1e24"; LINE = "#2a2e37"
FG = "#e8eaf0"; SUB = "#9aa0ab"; PINK = "#fb7299"
GREEN = "#4ec9a0"; AMBER = "#ffc53d"; RED = "#ff6b6b"; BLUE = "#5aa9e6"

CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"
_cache = {}
def font(px, bold=False):
    key = (px, bold)
    if key in _cache: return _cache[key]
    for idx, name in ((1, "Bold"), (0, "Regular")):
        if bold and idx == 0: continue
        p = CJK % name
        if os.path.exists(p):
            f = ImageFont.truetype(p, px, index=idx); _cache[key] = f; return f
    f = ImageFont.load_default(); _cache[key] = f; return f
F = lambda px, b=False: font(px, b)

def text(d, xy, s, size=18, fill=FG, bold=False, anchor=None):
    d.text(xy, s, font=F(size, bold), fill=fill, anchor=anchor)
def panel(d, x, y, w, h):
    d.rounded_rectangle((x, y, x + w, y + h), radius=18, fill=PANEL, outline=LINE, width=1)
def bar(d, x, y, w, color, h=4):
    d.rounded_rectangle((x, y, x + w, y + h), radius=h / 2, fill=color)

A = "{http://schemas.android.com/apk/res/android}"
def render_icon(xml_path, color, size=96):
    root = ET.parse(xml_path).getroot()
    vw = root.get(A + "viewportWidth", "24"); vh = root.get(A + "viewportHeight", "24")
    parts = []
    def walk(node):
        for p in node:
            if p.tag == "path":
                dd = p.get(A + "pathData")
                if not dd: continue
                fill = color or p.get(A + "fillColor") or "none"
                rule = p.get(A + "fillType") or "nonzero"
                rule = rule.lower()   # Android evenOdd -> SVG evenodd（大小写敏感）
                sc = p.get(A + "strokeColor"); sw = p.get(A + "strokeWidth")
                lc = p.get(A + "strokeLineCap"); lj = p.get(A + "strokeLineJoin")
                attrs = ['d="%s"' % dd, 'fill="%s"' % fill, 'fill-rule="%s"' % rule]
                if sc and sc != "@null": attrs.append('stroke="%s"' % (color or sc))
                if sw: attrs.append('stroke-width="%s"' % sw)
                if lc: attrs.append('stroke-linecap="%s"' % lc.lower())
                if lj: attrs.append('stroke-linejoin="%s"' % lj.lower())
                parts.append("<path %s/>" % " ".join(attrs))
            elif p.tag == "group": walk(p)
    walk(root)
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" width="%d" height="%d">%s</svg>') % (vw, vh, size, size, "".join(parts))
    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")

NAMES = [("like", "点赞"), ("comment", "评论"), ("coin", "投币"), ("star", "收藏"), ("share", "转发")]
def icon_row(img, d, x, y, directory, color, size=84, gap=220, use_on=False):
    for i, (key, label) in enumerate(NAMES):
        p = os.path.join(directory, "ic_%s.xml" % key)
        if use_on and key in ("like", "star"):
            q = os.path.join(directory, "ic_%s_on.xml" % key)
            if os.path.exists(q): p = q
        ic = render_icon(p, color, size)
        cx = x + i * gap
        img.paste(ic, (cx, y), ic)
        text(d, (cx + size / 2, y + size + 14), label, 17, FG, anchor="mm")

img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)

# 标题
text(d, (60, 40), "哔小趣 v4.37.0", 42, FG, True)
bar(d, 60, 98, 1280, PINK, 3)
text(d, (60, 110), "图标按参考图重做 · 自动分辨率提速 · 去掉 iOS 版", 21, SUB)

# 面板1：按参考图重绘的实心图标
panel(d, 40, 150, 1320, 400)
bar(d, 64, 174, 6, PINK, 24)
text(d, (84, 170), "① 五个图标严格按你发的照片重绘 —— 纯实心、饱满", 26, FG, True)
text(d, (84, 212), "上一版收藏/转发是空心描边，与照片不符；本版全部改为实心面块，和参考图一致", 16, SUB)
text(d, (84, 262), "上一版（空心描边，不对）", 17, RED)
icon_row(img, d, 150, 286, OLD, "#ffffff", 76, 220)
text(d, (84, 412), "本版（纯实心，与照片一致）", 17, GREEN)
icon_row(img, d, 150, 436, NEW, "#ffffff", 76, 220, use_on=True)

# 面板2：浅色模式
panel(d, 40, 570, 1320, 300)
bar(d, 64, 594, 6, AMBER, 24)
text(d, (84, 590), "② 浅色模式可读性保留：图标跟随主文字色，不再白底白图标", 24, FG, True)
d.rounded_rectangle((84, 634, 1316, 800), radius=12, fill="#f4f5f7")
text(d, (100, 644), "底部互动栏落在浅色面板上 → 自动变深色", 15, "#61666d")
icon_row(img, d, 160, 672, NEW, "#18191c", 60, 220, use_on=True)
text(d, (1120, 760), "清晰可读", 16, GREEN, True, anchor="mm")

# 面板3：自动分辨率提速
panel(d, 40, 890, 1320, 470)
bar(d, 64, 914, 6, BLUE, 24)
text(d, (84, 910), "③ 自动分辨率前提下提速、减卡顿", 26, FG, True)
text(d, (84, 952), "不再写死 720P：接上一直空置的带宽自适应决策，自动挑「扛得住的最高档」", 16, SUB)
items = [
    ("带宽自适应选档", "快网给高清、弱网给流畅；设备/档位始终是硬顶，绝不盲目冲软解 4K"),
    ("一次取回全部清晰度", "playurl 仍请求最高可用，本地秒切，不重复发网络请求"),
    ("让路窗口自适应", "首帧越快越早放行预取/详情/弹幕，连滑下一条更快；慢网则多保护"),
    ("去除冗余调用", "删掉两处固定 endStartup(1200)，统一由首帧耗时决策"),
]
for i, (t, s) in enumerate(items):
    y = 1004 + i * 72
    d.ellipse((86, y + 6, 98, y + 18), fill=BLUE)
    text(d, (114, y - 4), t, 18, FG, True)
    text(d, (430, y + 2), s, 16, SUB)
text(d, (84, 1300), "提速不靠锁低画质：分辨率全程自动，省的是「选错轨 + 白等」的时间", 16, GREEN, True)

# 面板4：去 iOS + 质量
panel(d, 40, 1380, 1320, 300)
bar(d, 64, 1404, 6, GREEN, 24)
text(d, (84, 1400), "④ 只保留平板版 + 手机版", 26, FG, True)
text(d, (84, 1442), "按你的要求去掉 iOS 皮肤版：构建脚本移除 ios 分支，交付只剩两个 APK", 16, SUB)
d.rounded_rectangle((84, 1484, 640, 1536), radius=12, fill="#242832", outline=LINE)
text(d, (362, 1510), "哔小趣-v4.37.0-平板版.apk", 17, FG, anchor="mm")
d.rounded_rectangle((680, 1484, 1316, 1536), radius=12, fill="#242832", outline=LINE)
text(d, (998, 1510), "哔小趣-v4.37.0-手机版.apk", 17, FG, anchor="mm")
text(d, (84, 1570), "versionCode 137 · 双版编译通过 · 守卫 320 项 / 单测 249 项全通过", 16, SUB)

bar(d, 60, 1710, 1280, LINE, 2)
text(d, (60, 1730), "两版差异保护仍在：平板横屏分屏完整保留，手机版仍走全屏", 16, SUB)

img.save(OUT)
print("saved:", OUT, img.size)
