# -*- coding: utf-8 -*-
"""v4.31.0 UI 对比：原版皮肤 vs iOS 皮肤（同一份代码，manifest 一个开关切换）。

两侧所有尺寸/颜色都直接取自源码：
  原版：页边距 10、卡片圆角 12、标题 13sp、搜索框玻璃胶囊 38dp/19r、
        底栏选中套 46dp 胶囊、控制条 18r / 2dp 进度
  iOS ：页边距 16、卡片圆角 10、标题 14.5sp、搜索框填充 36dp/10r、
        底栏只染色不套胶囊、控制条 22r（胶囊）/ 3dp 进度
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 1.75                       # dp → px
PAGE_W = 390
VIEW_H = 800                   # 只画到 800dp（底部留白没意义）
FRAME = 14

_TXT = ImageDraw.Draw(Image.new("RGB", (1, 1)))

THEMES = {
    "base": dict(
        name="原版", bg=(0x15, 0x17, 0x1C), card=(0x1E, 0x21, 0x28),
        fg=(0xE8, 0xEA, 0xF0), sub=(0x9A, 0xA0, 0xAB), acc=(0xFB, 0x72, 0x99),
        pad=10, gap=10, cover_r=12, title_sp=13.0, sub_sp=11.0,
        avatar=36, search_h=38, search_r=19, search_glass=True,
        hint="搜索你感兴趣的内容", hint_sp=12.5,
        nav_icon=20, nav_label=9.5, nav_pill=True,
        ctrl_r=18, seek_h=2, thumb=10, ctrl_tint=0x3C,
    ),
    "ios": dict(
        name="iOS", bg=(0, 0, 0), card=(0x1C, 0x1C, 0x1E),
        fg=(0xFF, 0xFF, 0xFF), sub=(0x8E, 0x8E, 0x93), acc=(0xFF, 0x37, 0x5F),
        pad=16, gap=12, cover_r=10, title_sp=14.5, sub_sp=11.5,
        avatar=32, search_h=36, search_r=10, search_glass=False,
        hint="搜索", hint_sp=14.0,
        nav_icon=23, nav_label=10.0, nav_pill=False,
        ctrl_r=22, seek_h=3, thumb=12, ctrl_tint=0x2E,
    ),
}


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


def rmask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius=int(r), fill=255)
    return m


def glass(img, box, r, tint_alpha, blur=14):
    x0, y0, x1, y1 = [int(v) for v in box]
    w, h = x1 - x0, y1 - y0
    if w <= 0 or h <= 0:
        return
    region = img.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(blur)).convert("RGBA")
    region = Image.alpha_composite(
        region, Image.new("RGBA", (w, h), (8, 10, 14, int(tint_alpha))))
    img.paste(region, (x0, y0), rmask((w, h), r))
    ImageDraw.Draw(img).rounded_rectangle(
        [x0, y0, x1 - 1, y1 - 1], radius=int(r), outline=(255, 255, 255, 32), width=1)


def make_art(w, h, seed):
    """生成一张"视频截图"：渐变天空 + 光斑 + 山脊"""
    art = Image.new("RGB", (w, h))
    px = art.load()
    top = [(30, 36, 66), (16, 44, 52), (54, 30, 58), (44, 32, 26)][seed % 4]
    bot = [(206, 142, 126), (108, 162, 152), (176, 116, 152), (210, 172, 96)][seed % 4]
    for y in range(h):
        t = (y / max(1, h - 1)) ** 0.9
        c = (int(top[0] + (bot[0] - top[0]) * t),
             int(top[1] + (bot[1] - top[1]) * t),
             int(top[2] + (bot[2] - top[2]) * t))
        for x in range(w):
            px[x, y] = c
    d = ImageDraw.Draw(art, "RGBA")
    for i in range(3):
        cx = int(w * (0.18 + 0.32 * i))
        cy = int(h * (0.22 + 0.12 * i))
        rad = int(h * (0.15 + 0.06 * i))
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(255, 238, 206, 30))
    for k in range(2):
        pts = [(0, h)]
        for x in range(0, w + 6, 6):
            yy = h * (0.66 + 0.09 * k) - math.sin(x / (w / (2.4 + k)) + seed) * h * 0.05
            pts.append((x, int(yy)))
        pts.append((w, h))
        d.polygon(pts, fill=(12, 14, 24, 200 - k * 70))
    return art


def paste_art(img, box, r, seed):
    x0, y0, x1, y1 = [int(v) for v in box]
    w, h = x1 - x0, y1 - y0
    if w <= 0 or h <= 0:
        return
    img.paste(make_art(w, h, seed), (x0, y0), rmask((w, h), r))


def txt(d, xy, s, f, fill, anchor=None):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def tw(s, f):
    return _TXT.textlength(s, font=f)


def wrap2(s, f, maxw):
    """按字符折行，最多两行，第二行放不下就省略"""
    lines, cur = [], ""
    for ch in s:
        if tw(cur + ch, f) <= maxw:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
            if len(lines) == 2:
                break
    if cur and len(lines) < 2:
        lines.append(cur)
    if len(lines) == 2 and tw("".join(lines), f) < tw(s, f):
        while lines[1] and tw(lines[1] + "…", f) > maxw:
            lines[1] = lines[1][:-1]
        lines[1] += "…"
    return lines or [""]


# ---------------------------------------------------------------- 通用块

def status_bar(img, o):
    d = ImageDraw.Draw(img)
    f = font(dp(12), True)
    txt(d, (dp(24), dp(12)), "9:41", f, o["fg"])
    x = W - dp(80)
    for i in range(4):
        hgt = dp(3 + i * 2)
        d.rounded_rectangle([x + i * dp(4), dp(21) - hgt, x + i * dp(4) + dp(3), dp(21)],
                            radius=dp(1), fill=o["fg"])
    d.rounded_rectangle([W - dp(48), dp(13), W - dp(29), dp(21)], radius=dp(2),
                        outline=o["fg"], width=1)
    d.rounded_rectangle([W - dp(46), dp(15), W - dp(32), dp(19)], radius=dp(1), fill=o["fg"])


def draw_icon(d, kind, cx, cy, size, col):
    s = size / 2.0
    w = max(1, int(size * 0.10))
    if kind == "home":
        d.polygon([(cx, cy - s), (cx + s * 0.95, cy - s * 0.1),
                   (cx - s * 0.95, cy - s * 0.1)], outline=col, width=w)
        d.rectangle([cx - s * 0.68, cy - s * 0.1, cx + s * 0.68, cy + s],
                    outline=col, width=w)
    elif kind == "dyn":
        d.rounded_rectangle([cx - s, cy - s * 0.82, cx + s, cy + s * 0.82],
                            radius=s * 0.35, outline=col, width=w)
        for k in (-1, 0, 1):
            d.line([cx - s * 0.5, cy + k * s * 0.4, cx + s * 0.5, cy + k * s * 0.4],
                   fill=col, width=w)
    else:
        d.ellipse([cx - s * 0.70, cy - s, cx + s * 0.70, cy + s * 0.4],
                  outline=col, width=w)
        d.arc([cx - s, cy - s * 0.1, cx + s, cy + s * 1.5], 205, 335, fill=col, width=w)


def top_bar(img, o, ios):
    d = ImageDraw.Draw(img)
    pad = dp(o["pad"])
    y = dp(40)
    av = dp(o["avatar"])
    avi = Image.new("RGBA", (av, av), (0, 0, 0, 0))
    da = ImageDraw.Draw(avi)
    da.ellipse([0, 0, av - 1, av - 1], fill=(0x46, 0x2E, 0x3C, 255))
    da.ellipse([0, 0, av - 1, av - 1], fill=(0xFB, 0x72, 0x99, 46))
    img.paste(avi, (pad, y), avi)
    txt(d, (pad + av / 2, y + av / 2), "趣", font(dp(12), True), (255, 238, 242), anchor="mm")

    mail = dp(34)
    sx0 = pad + av + dp(10)
    sx1 = W - pad - mail - dp(8)
    sy0 = y + (av - dp(o["search_h"])) // 2
    sy1 = sy0 + dp(o["search_h"])
    if o["search_glass"]:
        glass(img, (sx0, sy0, sx1, sy1), dp(o["search_r"]), 0x30, blur=8)
    else:
        d.rounded_rectangle([sx0, sy0, sx1, sy1], radius=dp(o["search_r"]),
                            fill=(0x76, 0x76, 0x80, 46))
    cx, cy = sx0 + dp(15), (sy0 + sy1) // 2
    d.ellipse([cx - dp(4.6), cy - dp(4.6), cx + dp(2.8), cy + dp(2.8)],
              outline=o["sub"], width=max(1, dp(1.1)))
    d.line([cx + dp(2.6), cy + dp(2.6), cx + dp(6), cy + dp(6)], fill=o["sub"],
           width=max(1, dp(1.3)))
    txt(d, (sx0 + dp(26), cy), o["hint"], font(dp(o["hint_sp"])), o["sub"], anchor="lm")

    mx = sx1 + dp(8)
    d.rounded_rectangle([mx, y + (av - mail) // 2, mx + mail, y + (av + mail) // 2],
                        radius=dp(10), fill=(255, 255, 255, 18))
    bx0, bx1 = mx + dp(9), mx + mail - dp(9)
    by0, by1 = y + av // 2 - dp(5), y + av // 2 + dp(5)
    d.rectangle([bx0, by0, bx1, by1], outline=o["fg"], width=1)
    d.line([bx0, by0, (bx0 + bx1) // 2, by0 + dp(4)], fill=o["fg"], width=1)
    d.line([bx1, by0, (bx0 + bx1) // 2, by0 + dp(4)], fill=o["fg"], width=1)
    return y + av


def channel_bar(img, o, ios, y):
    d = ImageDraw.Draw(img)
    names = ["推荐", "热门", "动画", "影视", "新番"]
    x = dp(16 if ios else 8)
    fy = y + dp(19)
    f_on = font(dp(14 if ios else 15.5), True)
    f_off = font(dp(14 if ios else 14.5), False)
    for i, n in enumerate(names):
        on = i == 0
        f = f_on if on else f_off
        w = tw(n, f) + dp(22 if ios else 20)
        if on and ios:
            d.rounded_rectangle([x, y + dp(5), x + w, y + dp(28)], radius=dp(9),
                                fill=(255, 255, 255, 38))
        col = o["fg"] if (on and ios) else (o["acc"] if on else o["sub"])
        txt(d, (x + dp(10), fy), n, f, col, anchor="lm")
        x += w + (dp(2) if ios else dp(2))
    for i in range(3):
        d.ellipse([W - dp(30) + i * dp(4), fy - dp(1), W - dp(28) + i * dp(4), fy + dp(1)],
                  fill=o["sub"])


def feed(img, o, ios, y0):
    d = ImageDraw.Draw(img)
    pad, gap = dp(o["pad"]), dp(o["gap"])
    colw = (W - pad * 2 - gap) // 2
    coverh = int(colw * 9 / 16)
    ft = font(dp(o["title_sp"]))
    fs = font(dp(o["sub_sp"]))
    line_h = int(dp(o["title_sp"]) * 1.42)
    sub_h = int(dp(o["sub_sp"]) * 1.6)
    block = coverh + dp(8) + line_h * 2 + dp(4) + sub_h + dp(14)
    items = [
        ("在凌晨四点的菜市场，我看见了生活本来的样子", "新起点curtain · 12.3万播放", "12:34"),
        ("【4K】把整条长江装进 8 分钟，每一帧都能当壁纸", "光影笔记 · 45.6万播放", "08:12"),
        ("这可能是今年最被低估的国产动画", "动画研究所 · 88.1万播放", "24:06"),
        ("一个人的川西自驾：第 7 天，翻过折多山", "在路上 · 23.9万播放", "15:38"),
        ("用 3000 张照片，拼出我这一年的四季", "时光机 · 9.4万播放", "06:21"),
        ("深夜食堂：一碗面，治愈了加班到十一点的我", "味觉档案 · 31.7万播放", "11:02"),
        ("把老家的院子改造成玻璃房，用了 47 天", "造物集 · 67.2万播放", "19:44"),
        ("跑步一年，我拍下了 1000 个清晨", "晨跑日记 · 5.8万播放", "07:15"),
    ]
    for i, (title, sub, dur) in enumerate(items):
        cy = y0 + (i // 2) * block
        if cy + block > dp(VIEW_H) - dp(78):
            break
        cx = pad + (i % 2) * (colw + gap)
        paste_art(img, (cx, cy, cx + colw, cy + coverh), dp(o["cover_r"]), i)
        bw, bh = dp(40), dp(17)
        d.rounded_rectangle([cx + colw - bw - dp(6), cy + coverh - bh - dp(6),
                             cx + colw - dp(6), cy + coverh - dp(6)],
                            radius=dp(4), fill=(0, 0, 0, 140))
        txt(d, (cx + colw - bw / 2 - dp(6), cy + coverh - bh / 2 - dp(6)), dur,
            font(dp(9)), (255, 255, 255), anchor="mm")
        ty = cy + coverh + dp(8)
        for k, line in enumerate(wrap2(title, ft, colw)):
            txt(d, (cx, ty + k * line_h), line, ft, o["fg"])
        txt(d, (cx, ty + line_h * 2 + dp(2)), sub, fs, o["sub"])


def nav_bar(img, o, ios):
    d = ImageDraw.Draw(img)
    h = dp(52)
    y0 = dp(VIEW_H) - h - dp(16)
    x0, x1 = dp(16), W - dp(16)
    glass(img, (x0, y0, x1, y0 + h), h / 2, 0x40 if o["nav_pill"] else 0x26, blur=16)
    if not o["nav_pill"]:
        d.line([x0 + dp(12), y0 + 1, x1 - dp(12), y0 + 1], fill=(255, 255, 255, 26))
    cw = (x1 - x0) / 3.0
    names, icons = ["首页", "动态", "我的"], ["home", "dyn", "mine"]
    fn = font(dp(o["nav_label"]))
    for i in range(3):
        cx = x0 + cw * (i + 0.5)
        col = o["acc"] if i == 0 else o["sub"]
        if i == 0 and o["nav_pill"]:
            pw, ph = cw - dp(10), dp(46)
            d.rounded_rectangle([cx - pw / 2, y0 + (h - ph) / 2, cx + pw / 2, y0 + (h + ph) / 2],
                                radius=ph / 2, fill=(0x43, 0x48, 0x52, 160),
                                outline=(255, 255, 255, 36))
        iy = y0 + (dp(14) if o["nav_pill"] else dp(11))
        draw_icon(d, icons[i], cx, iy + dp(o["nav_icon"]) / 2, dp(o["nav_icon"]), col)
        txt(d, (cx, iy + dp(o["nav_icon"]) + dp(5)), names[i], fn, col, anchor="ma")


# ---------------------------------------------------------------- 播放页

def play_page(img, o, ios):
    d = ImageDraw.Draw(img)
    vh = int(dp(VIEW_H) * 0.55)
    paste_art(img, (0, 0, W, vh), 0, 5)
    # 上方渐变压暗，让状态栏可读
    ov = Image.new("RGBA", (W, dp(110)), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    for yy in range(dp(110)):
        od.line([(0, yy), (W, yy)], fill=(0, 0, 0, int(120 * (1 - yy / dp(110)))))
    img.alpha_composite(ov, (0, 0))

    # ---- 控制条（压在画面下沿）----
    bar_h = dp(36)
    by1 = vh - dp(8)
    by0 = by1 - bar_h
    bx0, bx1 = dp(14), W - dp(14)
    glass(img, (bx0, by0, bx1, by1), dp(o["ctrl_r"]), o["ctrl_tint"], blur=12)

    cy = (by0 + by1) / 2.0
    x = bx0 + dp(13)
    d.rounded_rectangle([x, cy - dp(6.5), x + dp(2.2), cy + dp(6.5)], radius=dp(1),
                        fill=(255, 255, 255, 238))
    d.rounded_rectangle([x + dp(4.6), cy - dp(6.5), x + dp(6.8), cy + dp(6.5)],
                        radius=dp(1), fill=(255, 255, 255, 238))
    x += dp(15)
    txt(d, (x, cy), "00:12 / 03:45", font(dp(10)), (255, 255, 255, 236), anchor="lm")
    x += dp(78)
    txt(d, (x, cy), "弹", font(dp(13), True), (255, 255, 255, 224), anchor="lm")
    x += dp(19)
    iw = dp(72)
    d.rounded_rectangle([x, cy - dp(10), x + iw, cy + dp(10)], radius=dp(10),
                        fill=(255, 255, 255, 26))
    txt(d, (x + dp(9), cy), "发个弹幕…", font(dp(9.5)), (255, 255, 255, 190), anchor="lm")
    x += iw + dp(6)
    for label in ("倍速 1.0x", "1080P"):
        w = tw(label, font(dp(9.5))) + dp(16)
        d.rounded_rectangle([x, cy - dp(10), x + w, cy + dp(10)], radius=dp(10),
                            fill=(255, 255, 255, 22))
        txt(d, (x + dp(8), cy), label, font(dp(9.5)), (255, 255, 255, 226), anchor="lm")
        x += w + dp(6)
    for i in range(3):
        d.ellipse([x + i * dp(4), cy - dp(1), x + dp(2) + i * dp(4), cy + dp(1)],
                  fill=(255, 255, 255, 228))
    fx = bx1 - dp(19)
    L = dp(4.6)
    for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        px, py = fx + sx * dp(4.6), cy + sy * dp(4.6)
        d.line([px, py, px - sx * L, py], fill=(255, 255, 255, 234), width=max(1, dp(1.1)))
        d.line([px, py, px, py - sy * L], fill=(255, 255, 255, 234), width=max(1, dp(1.1)))

    # ---- 进度条 ----
    sy = by1 + dp(8)
    th = max(2, dp(o["seek_h"]))
    tx0, tx1 = dp(14), W - dp(14)
    d.rounded_rectangle([tx0, sy, tx1, sy + th], radius=th, fill=(255, 255, 255, 64))
    prog = int((tx1 - tx0) * 0.22)
    d.rounded_rectangle([tx0, sy, tx0 + prog, sy + th], radius=th, fill=(255, 255, 255, 236))
    r = dp(o["thumb"]) / 2.0
    d.ellipse([tx0 + prog - r, sy + th / 2 - r, tx0 + prog + r, sy + th / 2 + r],
              fill=(255, 255, 255))

    # ---- 信息区 ----
    iy = vh + dp(12)
    av = dp(38)
    avi = Image.new("RGBA", (av, av), (0, 0, 0, 0))
    da = ImageDraw.Draw(avi)
    da.ellipse([0, 0, av - 1, av - 1], fill=(0x4C, 0x34, 0x42, 255))
    img.paste(avi, (dp(16), iy), avi)
    txt(d, (dp(16) + av / 2, iy + av / 2), "新", font(dp(12), True), (255, 236, 240), anchor="mm")
    txt(d, (dp(16) + av + dp(10), iy + dp(1)), "新起点curtain",
        font(dp(13.5), not ios), o["acc"])
    txt(d, (dp(16) + av + dp(10), iy + dp(21)), "12.3万粉丝 · 今日更新 2 条",
        font(dp(10.5)), o["sub"])
    bw2 = dp(58)
    bx = W - dp(16) - bw2
    d.rounded_rectangle([bx, iy + dp(6), bx + bw2, iy + dp(30)], radius=dp(12),
                        fill=o["acc"])
    txt(d, (bx + bw2 / 2, iy + dp(18)), "关注", font(dp(11), True), (255, 255, 255), anchor="mm")

    ty = iy + av + dp(12)
    title = "在凌晨四点的菜市场，我看见了生活本来的样子"
    f = font(dp(15), True)
    for k, line in enumerate(wrap2(title, f, W - dp(32))):
        txt(d, (dp(16), ty + k * dp(21)), line, f, o["fg"])
    ty += dp(21) * 2 + dp(4)
    txt(d, (dp(16), ty), "3509播放 · 50评论 · 2弹幕", font(dp(10.5)), o["sub"])

    # ---- 推荐列表 ----
    ry = ty + dp(26)
    recs = [("一个人的川西自驾：第 7 天，翻过折多山", "在路上 · 23.9万播放"),
            ("把整条长江装进 8 分钟，每一帧都能当壁纸", "光影笔记 · 45.6万播放")]
    for i, (t, s2) in enumerate(recs):
        rh = dp(76)
        if ry + rh > dp(VIEW_H) - dp(16):
            break
        paste_art(img, (dp(16), ry, dp(16) + dp(124), ry + rh), dp(8 if ios else 10), 6 + i)
        ft = font(dp(13.5))
        for k, line in enumerate(wrap2(t, ft, W - dp(16) - (dp(16) + dp(134)))):
            txt(d, (dp(16) + dp(134), ry + dp(4) + k * dp(20)), line, ft, o["fg"])
        txt(d, (dp(16) + dp(134), ry + dp(48)), s2, font(dp(10.5)), o["sub"])
        ry += rh + dp(12)


# ---------------------------------------------------------------- 组装

def phone(theme_key, page):
    global W
    o = THEMES[theme_key]
    W = dp(PAGE_W)
    H = dp(VIEW_H)
    img = Image.new("RGBA", (W, H), o["bg"] + (255,))
    if page == "feed":
        y = top_bar(img, o, theme_key == "ios")
        channel_bar(img, o, theme_key == "ios", y + dp(2))
        feed(img, o, theme_key == "ios", y + dp(38))
        nav_bar(img, o, theme_key == "ios")
    else:
        play_page(img, o, theme_key == "ios")
    status_bar(img, o)

    mask = rmask((W, H), dp(30))
    body = Image.new("RGBA", (W + FRAME * 2, H + FRAME * 2), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.rounded_rectangle([0, 0, W + FRAME * 2 - 1, H + FRAME * 2 - 1], radius=dp(42),
                         fill=(24, 25, 29, 255), outline=(78, 82, 92, 255), width=2)
    body.paste(img, (FRAME, FRAME), mask)
    return body


def main(out):
    global W
    # 先算一次尺寸
    W = dp(PAGE_W)
    ph_w = dp(PAGE_W) + FRAME * 2
    ph_h = dp(VIEW_H) + FRAME * 2

    gap_x, gap_y = 56, 44
    Wc = 48 * 2 + ph_w * 2 + gap_x
    head = 120
    legend_h = 5 * 34 + 40
    Hc = head + 40 + ph_h + gap_y + 40 + ph_h + 30 + legend_h

    canvas = Image.new("RGB", (Wc, Hc), (9, 10, 13))
    d = ImageDraw.Draw(canvas)
    F_T = font(36, True)
    F_H = font(21, True)
    F_S = font(15)

    txt(d, (48, 30), "哔小趣 v4.31.0 · 界面皮肤对比", F_T, (240, 242, 247))
    txt(d, (48, 80), "同一份源码，只有 manifest 里 bxq.ios_ui 一个开关；左＝原版，右＝iOS 皮肤（深色主题）",
        F_S, (150, 157, 172))

    red, grn = (255, 128, 128), (110, 226, 166)
    ylabel = head
    for i, key in enumerate(("base", "ios")):
        x = 48 + i * (ph_w + gap_x)
        txt(d, (x + 10, ylabel), ("首页 · 原版" if key == "base" else "首页 · iOS 皮肤"),
            F_H, red if key == "base" else grn)
    y1 = ylabel + 34
    for i, key in enumerate(("base", "ios")):
        x = 48 + i * (ph_w + gap_x)
        canvas.paste(phone(key, "feed"), (x, y1), phone(key, "feed"))

    y2 = y1 + ph_h + gap_y
    for i, key in enumerate(("base", "ios")):
        x = 48 + i * (ph_w + gap_x)
        txt(d, (x + 10, y2 - 34), ("播放页 · 原版" if key == "base" else "播放页 · iOS 皮肤"),
            F_H, red if key == "base" else grn)
    for i, key in enumerate(("base", "ios")):
        x = 48 + i * (ph_w + gap_x)
        canvas.paste(phone(key, "play"), (x, y2), phone(key, "play"))

    ly = y2 + ph_h + 20
    lines = [
        ("①", "搜索栏", "玻璃胶囊 38dp / 圆角 19 / 带描边", "系统填充 36dp / 圆角 10 / 无描边"),
        ("②", "频道栏", "选中变粉色加粗，字号 14.5→15.5（会抖动）", "选中＝白色胶囊+半粗，字号恒定"),
        ("③", "卡片", "封面圆角 12 · 页边距 10 · 标题 13sp", "封面圆角 10 · 页边距 16 · 标题 14.5sp"),
        ("④", "底栏", "选中套 46dp 玻璃胶囊 · 图标 20", "只染色不套胶囊 · 图标 23 · 标签 10sp"),
        ("⑤", "控制条", "圆角 18 · 压暗 23% · 进度 2dp/10dp", "胶囊 22 · 压暗 18% · 进度 3dp/12dp"),
    ]
    for i, (n, k, a, b) in enumerate(lines):
        yy = ly + i * 34
        d.ellipse([48, yy + 2, 72, yy + 26], fill=(58, 64, 76))
        txt(d, (60, yy + 14), n, font(12, True), (236, 239, 245), anchor="mm")
        txt(d, (86, yy + 2), k, font(15, True), (226, 230, 238))
        txt(d, (190, yy + 2), a, F_S, (255, 152, 152))
        txt(d, (700, yy + 2), b, F_S, (126, 226, 166))

    canvas.save(out)
    print("saved", out, canvas.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/workspace/哔小趣-v4.31.0-UI对比.png")
