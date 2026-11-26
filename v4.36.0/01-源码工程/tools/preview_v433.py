# -*- coding: utf-8 -*-
"""v4.33.0 预览：开屏动画时间线 + 主题色 8 色。

上：开屏 4 帧（0.00s / 0.24s / 0.72s / 2.62s），数值取自 MainActivity.showSplash：
    图标 88dp 圆角 22dp、柔光 210dp、名称 27sp、作者 12sp、标语 11sp、
    进度线 2dp 主题色、收尾 2460ms + 230ms。
下：8 个推荐色（Theme.PRESETS），每个色卡画「关注」胶囊 + 进度条 + 选中环。
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 1.0
FRAME_W, FRAME_H = 268, 520
GAP = 20
HEAD = 58

C_BG = (0x0B, 0x0C, 0x0E)
ICON = "app/res/mipmap-xxxhdpi/ic_launcher.png"

PRESETS = [
    (0xE5, 0x48, 0x4D, "红"), (0xFF, 0x8A, 0x3D, "橙"), (0xE9, 0xA8, 0x25, "黄"),
    (0x30, 0xA4, 0x6C, "绿"), (0x12, 0xA5, 0x94, "青"), (0x3E, 0x7B, 0xFA, "蓝"),
    (0x8E, 0x4E, 0xC6, "紫"), (0xFB, 0x72, 0x99, "粉"),
]


def font(px, bold=False):
    base = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"
    p = base % ("Bold" if bold else "Regular")
    if os.path.exists(p):
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, max(6, int(px)), index=idx)
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                pass
    return ImageFont.load_default()


def backdrop(w, h, zoom=1.0):
    """模拟 splash 插画：暖色渐变 + 两团模糊光斑，再叠径向暗角"""
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=(
            int(0x3E - 0x2A * t), int(0x33 - 0x22 * t), int(0x2E - 0x1E * t)))
    blobs = Image.new("RGB", (w, h), (0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    bd.ellipse([int(w * 0.05), int(h * 0.18), int(w * 0.75), int(h * 0.52)],
               fill=(126, 96, 76))
    bd.ellipse([int(w * 0.55), int(h * 0.55), int(w * 1.05), int(h * 0.9)],
               fill=(92, 74, 66))
    blobs = blobs.filter(ImageFilter.GaussianBlur(int(w * 0.10)))
    img = Image.blend(img, blobs, 0.7)

    # 径向暗角
    veil = Image.new("L", (w, h), 0)
    vd = ImageDraw.Draw(veil)
    steps = 60
    for i in range(steps, 0, -1):
        t = i / steps
        rr = t * max(w, h) * 0.78
        a = int(210 * (1 - t) ** 1.4)
        vd.ellipse([w / 2 - rr, h / 2 - rr, w / 2 + rr, h / 2 + rr],
                   outline=a)
    veil = veil.filter(ImageFilter.GaussianBlur(8))
    dark = Image.new("RGB", (w, h), (0, 0, 0))
    img = Image.composite(img, dark, Image.eval(veil, lambda v: 255 - min(255, v)))
    return img


def rounded(img, r):
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w, h], radius=r, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def frame(t, acc=(0xFB, 0x72, 0x99)):
    """t = 0.00 / 0.24 / 0.72 / 2.62 四个时间点"""
    w, h = int(FRAME_W * S), int(FRAME_H * S)
    img = backdrop(w, h, 1.0).convert("RGBA")

    # 图标：0.86→1.0，640ms 内完成；0.24s 约到 0.97
    if t <= 0:
        k = 0.0
    elif t > 0.72:
        k = 1.0
    else:
        p = min(1.0, (t - 0.0) / 0.64)
        k = p ** 0.55     # Decelerate 近似
    alpha = 0 if t <= 0 else min(1.0, t / 0.5)
    icon_px = int(88 * k) if k > 0 else 0
    icon_alpha = alpha

    # 柔光：0.76s 亮起，之后收到 0.62
    glow_a = 0 if t <= 0 else min(0.95, t / 0.6) * (0.62 / 0.95 if t > 0.78 else 1.0)
    glow_px = int(210 * (0.72 + 0.28 * min(1.0, t / 0.76))) if t > 0 else 0

    cx, cy = w // 2, int(h * 0.44)
    if glow_px > 0:
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        for i in range(24, 0, -1):
            rr = glow_px / 2 * (i / 24)
            a = int(70 * glow_a * (1 - i / 24) ** 0.7)
            gd.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                       fill=acc + (a,))
        glow = glow.filter(ImageFilter.GaussianBlur(10))
        img.alpha_composite(glow)

    if icon_px > 4 and os.path.exists(ICON):
        ic = Image.open(ICON).convert("RGBA").resize((icon_px, icon_px))
        ic = rounded(ic, int(icon_px * 0.25))
        if icon_alpha < 1:
            a = ic.split()[3].point(lambda v: int(v * icon_alpha))
            ic.putalpha(a)
        img.alpha_composite(ic, (cx - icon_px // 2, cy - icon_px // 2))

    d = ImageDraw.Draw(img)
    # 文字：170ms 名称、300ms 作者、390ms 细线、440ms 标语
    ty = cy + int(88 * 0.5) + int(14 * S)
    if t >= 0.17:
        a = min(1.0, (t - 0.17) / 0.36)
        d.text((cx, ty), "哔小趣", font=font(int(27 * S), True), anchor="ma",
               fill=(255, 255, 255, int(255 * a)))
    if t >= 0.30:
        a = min(1.0, (t - 0.30) / 0.34)
        d.text((cx, ty + int(30 * S)), "软件作者 Dynamo", font=font(int(12 * S)),
               anchor="ma", fill=(255, 255, 255, int(179 * a)))
    if t >= 0.44:
        a = min(1.0, (t - 0.44) / 0.34)
        d.text((cx, ty + int(58 * S)), "轻量 · 无广告 · 高清", font=font(int(11 * S)),
               anchor="ma", fill=(255, 255, 255, int(140 * a)))

    # 进度线：2420ms 匀速
    prog = min(1.0, max(0.0, t / 2.42))
    bw = int(w * prog)
    if bw > 0:
        d.rectangle([0, h - 3, bw, h - 1], fill=acc + (255,))

    # 收尾 2.62s：整体淡出 + 放大 1.02
    if t >= 2.46:
        k2 = min(1.0, (t - 2.46) / 0.23)
        img = Image.blend(img, Image.new("RGBA", img.size, (0, 0, 0, 0)),
                          k2 * 0.95)
    return img.convert("RGB")


def swatch_card(acc, name):
    cw, ch = 250, 92
    img = Image.new("RGB", (cw, ch), (0x1E, 0x21, 0x28))
    d = ImageDraw.Draw(img)
    # 色点
    d.ellipse([16, 20, 52, 56], fill=acc)
    if name == "粉":
        d.ellipse([10, 14, 58, 62], outline=(255, 255, 255), width=2)
        d.line([(30, 36), (36, 42), (44, 30)], fill=(255, 255, 255), width=3)
    d.text((64, 20), name, font=font(15, True), fill=(0xE8, 0xEA, 0xF0))
    # 胶囊
    d.rounded_rectangle([64, 48, 120, 74], radius=13, fill=acc)
    d.text((92, 61), "关注", font=font(12), anchor="mm", fill=(255, 255, 255))
    # 进度条
    d.rounded_rectangle([132, 60, 232, 64], radius=2, fill=(0x3A, 0x3F, 0x49))
    d.rounded_rectangle([132, 60, 190, 64], radius=2, fill=acc)
    return img


frames = [frame(t) for t in (0.10, 0.26, 0.72, 2.62)]
labels = ["0.10s · 图标淡入", "0.26s · 继续放大", "0.72s · 全部落位", "2.62s · 轻推淡出"]

cols = 4
FW = int(FRAME_W * S)
top_w = cols * FW + (cols + 1) * GAP
CW, CH = 250, 92
card_cols = 4
card_gap = 14
bottom_w = card_cols * CW + (card_cols + 1) * card_gap

W = max(top_w, bottom_w)
H = HEAD + int(FRAME_H * S) + 46 + 34 + 2 * (CH + card_gap) + 40

canvas = Image.new("RGB", (W, H), C_BG)
d = ImageDraw.Draw(canvas)

d.text((GAP, 18), "开屏动画（2.69s）", font=font(18, True), fill=(0xF0, 0xF1, 0xF4))
d.text((GAP, 40), "中央图标 + 主题色柔光，文字分三层错峰淡入，收尾轻推进后淡出",
       font=font(11.5), fill=(0x8B, 0x91, 0x9C))

y = HEAD
for i, (f, lb) in enumerate(zip(frames, labels)):
    x = GAP + i * (FW + GAP)
    canvas.paste(f, (x, y))
    d.rounded_rectangle([x, y, x + FW, y + int(FRAME_H * S)], radius=12,
                        outline=(0x2A, 0x2E, 0x36), width=1)
    d.text((x, y + int(FRAME_H * S) + 10), lb, font=font(11.5), fill=(0xA8, 0xAD, 0xB6))

y2 = y + int(FRAME_H * S) + 46
d.text((GAP, y2), "全局主题色 · 8 个推荐色", font=font(18, True), fill=(0xF0, 0xF1, 0xF4))
d.text((GAP, y2 + 22), "设置 → 外观 → 主题颜色：底栏、频道栏、按钮、进度条、播放器控件同步换色",
       font=font(11.5), fill=(0x8B, 0x91, 0x9C))

y3 = y2 + 50
for i, (r, g, b, nm) in enumerate(PRESETS):
    rI, cI = divmod(i, card_cols)
    x = card_gap + cI * (CW + card_gap)
    yy = y3 + rI * (CH + card_gap)
    canvas.paste(swatch_card((r, g, b), nm), (x, yy))
    d.rounded_rectangle([x, yy, x + CW, yy + CH], radius=14,
                        outline=(0x2A, 0x2E, 0x36), width=1)

out = "/workspace/哔小趣-v4.33.0-开屏与主题色.png"
canvas.save(out)
print("saved", canvas.size, out)
