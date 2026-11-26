#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.38.0 修复总览：硬币图标重绘 + 1080p弱网提速 + 带宽快降慢升 + 调度重启生效"""
import io, os
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = ROOT + "/app/res/drawable/"
OLD = "/root/.codebuddy/artifact/bixiaoqu_bak_v436/app/res/drawable/"
OUT = "/workspace/哔小趣-v4.38.0-修复总览.png"

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
text(d, (60, 40), "哔小趣 v4.38.0", 42, FG, True)
bar(d, 60, 98, 1280, PINK, 3)
text(d, (60, 110), "硬币图标重绘 · 1080p 弱网专项 · 带宽快降慢升 · 调度重启生效", 21, SUB)

# 面板1：硬币图标新旧对比
panel(d, 40, 150, 1320, 340)
bar(d, 64, 174, 6, PINK, 24)
text(d, (84, 170), "① 硬币图标按你发的照片重做", 26, FG, True)
text(d, (84, 212), "旧版是「圆盘 + ¥」，与照片不符；本版改成照片里的篆体「币」字（顶横 + 中竖 + 两侧下弯弧）", 16, SUB)
# 旧图标
old_coin = os.path.join(OLD, "ic_coin.xml")
oic = render_icon(old_coin, "#ffffff", 120)
img.paste(oic, (200, 250), oic)
text(d, (260, 390), "旧版 ¥（不对）", 17, RED, True, anchor="mm")
# 箭头
text(d, (620, 320), "→", 54, AMBER, True, anchor="mm")
# 新图标
nic = render_icon(NEW + "ic_coin.xml", "#ffffff", 120)
img.paste(nic, (900, 250), nic)
text(d, (960, 390), "本版 币字（一致）", 17, GREEN, True, anchor="mm")

# 面板2：1080p 弱网专项
panel(d, 40, 510, 1320, 470)
bar(d, 64, 534, 6, BLUE, 24)
text(d, (84, 530), "② 高分辨率（尤其 1080p）弱网专项优化", 26, FG, True)
text(d, (84, 572), "分辨率全程自动不锁死；提速来自「选对轨 + 填满管道 + 不白等」，弱网也能稳住", 16, SUB)
items2 = [
    ("弱网带宽快降慢升", "网络掉速立刻降码率（新样本权重 0.75），避免继续冲高码率卡死；回升谨慎不被一次快分片骗到"),
    ("弱网 1080p 多开一段", "单条 TCP 受 BDP 限制吃不满带宽，收敛到 2 段填满管道；设备能力是硬顶，老设备绝不硬开"),
    ("按带宽选轨保底", "自动档按实测带宽压到扛得住的档：极弱网 360P / 弱网 480P / 中等 720P，快网不压"),
    ("看门狗自适应", "弱网/超流畅档放宽首帧等待（最长 14s 硬上限），避免过早回退合流的二次卡顿与黑屏"),
]
for i, (t, s) in enumerate(items2):
    y = 624 + i * 80
    d.ellipse((86, y + 6, 98, y + 18), fill=BLUE)
    text(d, (114, y - 4), t, 18, FG, True)
    text(d, (470, y + 2), s, 15, SUB)

# 面板3：带宽/线程/端口
panel(d, 40, 1000, 1320, 300)
bar(d, 64, 1024, 6, AMBER, 24)
text(d, (84, 1020), "③ 线程 / 端口 / 带宽全面加大", 24, FG, True)
cols = [
    ("HTTP 连接池", "上限 16 → 24", "并行段+视频+音轨+预取+接口同时复用，少做 TLS 握手"),
    ("IO 转发线程", "超清晰 12 / 平衡 10", "档位越高线程越多，老设备档保持精简"),
    ("端口 backlog", "256 · 缓冲 1MB", "换片瞬间旧连接未退、新连接涌入不会被拒在门外"),
]
for i, (t, big, s) in enumerate(cols):
    x = 84 + i * 424
    d.rounded_rectangle((x, 1064, x + 400, 1260), radius=12, fill="#242832", outline=LINE)
    text(d, (x + 20, 1080), t, 18, AMBER, True)
    text(d, (x + 20, 1116), big, 25, FG, True)
    text(d, (x + 20, 1162), s, 15, SUB)

# 面板4：调度生效 + 超流畅保底
panel(d, 40, 1320, 1320, 380)
bar(d, 64, 1344, 6, GREEN, 24)
text(d, (84, 1340), "④ 播放调度可靠生效 · 超流畅弱网不卡", 26, FG, True)
text(d, (84, 1382), "连接池等 JVM 属性只在启动时读取：切换调度后弹窗提示「立即重启」，让新调度从第一条视频就生效", 16, SUB)
text(d, (84, 1424), "五档调度（超流畅 / 超清晰 / 平衡推荐 / 节能 / 自定义）保留，档位参数随重启完整应用", 16, SUB)
# 超流畅保底说明条
d.rounded_rectangle((84, 1466, 1316, 1600), radius=12, fill="#1f2a26", outline="#2f5a49")
text(d, (108, 1482), "超流畅 · 弱网保底", 19, GREEN, True)
text(d, (108, 1516), "480P 封顶 + 单段低功耗 + 弱网选轨保底 + 看门狗放宽 + 起播海报铺底", 16, FG)
text(d, (108, 1552), "目标：弱网不卡、不加载久、不黑屏、不卡死", 17, GREEN, True)

text(d, (84, 1650), "versionCode 138 · 双版编译通过 · 守卫 330 项 / 单测 10 套全通过", 16, SUB)
bar(d, 60, 1690, 1280, LINE, 2)
text(d, (60, 1712), "两版差异保护仍在：平板横屏分屏完整保留，手机版仍走全屏", 16, SUB)

img.save(OUT)
print("saved:", OUT, img.size)
