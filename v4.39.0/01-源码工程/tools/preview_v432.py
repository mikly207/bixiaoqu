# -*- coding: utf-8 -*-
"""v4.32.0 预览：「更多」弹窗材质加深 + 新增「后台播放」。

左 = 改前（card() 主体 0x3D ≈ 24%，画面亮部直接透上来）
右 = 改后（sheet() 主体 0x7D ≈ 49%，同一处亮部只剩一层柔光）

刻意在弹窗背后放了一块"画面亮部"：通透的弹窗会让它整片透出来，
压住菜单本身的黑白对比；加深后亮部还在，但不再和文字抢。
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 1.55
W, H = 390, 640
GAP = 26
HEAD = 46
NOTE_H = 96

C_BG_TOP, C_BG_BOT = (0x3A, 0x2F, 0x2A), (0x12, 0x0F, 0x0E)


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


def dp(v):
    return int(round(v * S))


def scene():
    """模拟一帧竖屏视频：暖色渐变 + 中部一大块亮部（最容易"透"出来的那种画面）"""
    img = Image.new("RGB", (dp(W), dp(H)))
    d = ImageDraw.Draw(img)
    for y in range(dp(H)):
        t = y / dp(H)
        d.line([(0, y), (dp(W), y)], fill=(
            int(C_BG_TOP[0] + (C_BG_BOT[0] - C_BG_TOP[0]) * t),
            int(C_BG_TOP[1] + (C_BG_BOT[1] - C_BG_TOP[1]) * t),
            int(C_BG_TOP[2] + (C_BG_BOT[2] - C_BG_TOP[2]) * t)))

    blobs = Image.new("RGB", (dp(W), dp(H)), (0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    bd.ellipse([dp(-30), dp(250), dp(300), dp(450)], fill=(248, 226, 198))   # 亮部
    bd.ellipse([dp(210), dp(330), dp(400), dp(520)], fill=(150, 120, 100))
    blobs = blobs.filter(ImageFilter.GaussianBlur(dp(30)))
    img = Image.blend(img, blobs, 0.72)

    d = ImageDraw.Draw(img)
    d.text((dp(40), dp(578)), "虞女士，我愿意为自己的行为负责", font=font(dp(14)),
           fill=(228, 226, 226))
    return img


def dialog_glass(base, dh, alpha_main, alpha_top, alpha_bot, stroke):
    w, h = base.size
    x0, y0 = dp(34), dp(64)
    x1 = w - dp(34)
    y1 = y0 + dh
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    n = y1 - y0
    for i in range(n):
        t = i / max(1, n - 1)
        if t < 0.16:
            # 顶缘柔光 → 主体：alpha 与色值同时插值，避免画出一条假的"硬边"
            k = t / 0.16
            a = int(alpha_top + (alpha_main - alpha_top) * k)
            col = (int(255 + (0x26 - 255) * k), int(255 + (0x2B - 255) * k),
                   int(255 + (0x34 - 255) * k), a)
        else:
            k = (t - 0.16) / 0.84
            a = int(alpha_main + (alpha_bot - alpha_main) * k)
            col = (0x26, 0x2B, 0x34, a)
        d.line([(x0, y0 + i), (x1, y0 + i)], fill=col)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([x0, y0, x1, y1], radius=dp(20), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(layer, (0, 0), mask)
    base.alpha_composite(out)

    d2 = ImageDraw.Draw(base)
    d2.rounded_rectangle([x0, y0, x1, y1], radius=dp(20),
                         outline=(0xFF, 0xFF, 0xFF, stroke), width=max(1, dp(0.8)))
    return x0, y0, x1, y1


def draw_menu(base, box, items):
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(base)
    cx = (x0 + x1) // 2
    d.text((cx, y0 + dp(16)), "更多", font=font(dp(20), True),
           fill=(0xF4, 0xF5, 0xF8), anchor="ma")

    y = y0 + dp(54)
    for it in items:
        new = it.startswith("后台播放")
        d.text((x0 + dp(26), y), it, font=font(dp(16)),
               fill=(0xFF, 0x9F, 0xB8) if new else (0xEC, 0xEE, 0xF2))
        if new:
            d.ellipse([x0 + dp(16), y + dp(7), x0 + dp(20), y + dp(11)],
                      fill=(0xFB, 0x72, 0x99))
        y += dp(40)

    d.line([(x0 + dp(18), y1 - dp(46)), (x1 - dp(18), y1 - dp(46))],
           fill=(0xFF, 0xFF, 0xFF, 0x22), width=1)
    d.text((cx, y1 - dp(32)), "取消", font=font(dp(16)),
           fill=(0xA0, 0xA6, 0xB0), anchor="ma")


def side(dh, alpha_main, alpha_top, alpha_bot, stroke, items):
    base = scene().convert("RGBA")
    box = dialog_glass(base, dh, alpha_main, alpha_top, alpha_bot, stroke)
    draw_menu(base, box, items)
    return base.convert("RGB")


ITEMS = ["清晰度：720P", "解码方式：自动", "发弹幕", "倍速",
         "后台播放：开", "缓存到本地", "复制视频链接"]
ITEMS_OLD = ITEMS[:4] + ITEMS[5:]

left = side(dp(360), 0x3D, 0x14, 0x33, 0x14, ITEMS_OLD)
right = side(dp(400), 0x7D, 0x24, 0x6E, 0x1F, ITEMS)

CW, CH = left.size
canvas = Image.new("RGB", (CW * 2 + GAP * 3, CH + HEAD + GAP * 2 + NOTE_H + 40),
                   (0x0A, 0x0B, 0x0D))
d = ImageDraw.Draw(canvas)
ft = font(dp(17), True)
fs = font(dp(12))

d.text((GAP, GAP + dp(2)), "改前 · 24% 玻璃", font=ft, fill=(0xE8, 0xEA, 0xF0))
d.text((GAP, GAP + dp(26)), "画面亮部整片透上来，压住菜单的黑白对比", font=fs,
       fill=(0x8B, 0x91, 0x9C))
d.text((CW + GAP * 2, GAP + dp(2)), "改后 · 49% 玻璃", font=ft, fill=(0xFB, 0x72, 0x99))
d.text((CW + GAP * 2, GAP + dp(26)), "亮部只剩柔光；「更多」里多了「后台播放」", font=fs,
       fill=(0x8B, 0x91, 0x9C))

Y = HEAD + GAP // 2
canvas.paste(left, (GAP, Y))
canvas.paste(right, (CW + GAP * 2, Y))

# 底部：后台播放的常驻通知示意（横跨整幅，避免和弹窗挤在一起）
ny0 = Y + CH + GAP
nx0, nx1 = GAP, canvas.size[0] - GAP
d.rounded_rectangle([nx0, ny0, nx1, ny0 + NOTE_H], radius=dp(16), fill=(0x2A, 0x2D, 0x35))
d.rounded_rectangle([nx0, ny0, nx1, ny0 + NOTE_H], radius=dp(16),
                    outline=(0xFF, 0xFF, 0xFF, 0x18), width=1)
d.text((nx0 + dp(18), ny0 + dp(14)), "哔小趣 · 正在后台播放",
       font=font(dp(14), True), fill=(0xF0, 0xF1, 0xF4))
d.text((nx0 + dp(18), ny0 + dp(42)),
       "开启后切到别的 App、锁屏都继续出声 · 通知栏可一键停止",
       font=font(dp(12)), fill=(0xA8, 0xAD, 0xB6))
d.text((nx1 - dp(20), ny0 + dp(20)), "停止", font=font(dp(13)),
       fill=(0xFB, 0x72, 0x99), anchor="ra")

out = "/workspace/哔小趣-v4.32.0-弹窗与后台播放.png"
canvas.save(out)
print("saved", canvas.size, out)
