# -*- coding: utf-8 -*-
"""v4.29.0 对比渲染：控制条压到画面下沿 + 删掉「简介 / 评论」分段。

左边 = v4.28.0：控制条自占一行，背后是 #16181d 页面底色 → 玻璃无物可透，
               观感是一条黑底横带；下面还有一行「简介 / 评论 50」。
右边 = v4.29.0：控制条整体上移自身高度，压在视频画面最下沿，背后是真实帧；
               那行分段删掉，页面流一共省下约 91dp。

渲染用 numpy 做 alpha 混合（PIL 在 RGB 图上 fill 半透明会画成不透明色块）。
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

S = 2.6           # 每 dp 像素数
PAGE_W = 372      # 页面宽（dp）
VIDEO_H = 230     # videoHeight
BAR_H = 57        # 玻璃板 36 + 缝 1 + 进度槽 16 + 4
BAR_ROW = BAR_H + 8   # 旧版：控制条整行占位（含底边距）
TAB_ROW = 26      # 「简介 / 评论」分段行高（6+2 padding + 18 文字）

C_BG = (22, 24, 29)


# ---------------------------------------------------------------- 基础

def font(size, bold=False):
    base = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"
    p = base % ("Bold" if bold else "Regular")
    if os.path.exists(p):
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, size, index=idx)
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                pass
    return ImageFont.load_default()


F_LBL = font(int(13 * S), True)
F_T = font(int(14 * S), True)
F_N = font(int(12 * S), True)
F_B = font(int(11 * S), True)
F_XS = font(int(9 * S), True)


def rr_mask(w, h, r, inset=0.0, width=None, ss=3):
    W, H = int(w * ss), int(h * ss)
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    i = inset * ss
    box = (i, i, W - 1 - i, H - 1 - i)
    rad = max(0.0, (r - inset) * ss)
    if width is None:
        d.rounded_rectangle(box, radius=rad, fill=255)
    else:
        d.rounded_rectangle(box, radius=rad, outline=255, width=max(1, int(width * ss)))
    return np.asarray(m.resize((int(w), int(h)), Image.LANCZOS), np.float32) / 255.0


def paint(cv, x0, y0, mask, rgb, alpha):
    H, W = cv.shape[:2]
    h, w = mask.shape
    x0, y0 = int(x0), int(y0)
    cx0, cy0 = max(x0, 0), max(y0, 0)
    cx1, cy1 = min(x0 + w, W), min(y0 + h, H)
    if cx1 <= cx0 or cy1 <= cy0:
        return
    sx, sy = cx0 - x0, cy0 - y0
    m = mask[sy:sy + (cy1 - cy0), sx:sx + (cx1 - cx0)]
    al = alpha if np.isscalar(alpha) else \
        np.asarray(alpha, np.float32)[sy:sy + (cy1 - cy0), sx:sx + (cx1 - cx0)]
    a = np.clip(m * al, 0.0, 1.0)[..., None]
    reg = cv[cy0:cy1, cx0:cx1]
    for c in range(3):
        reg[..., c] = reg[..., c] * (1 - a[..., 0]) + rgb[c] * a[..., 0]


def vgrad(h, w, top, bot):
    t = np.linspace(0.0, 1.0, h)[:, None]
    return np.repeat(top + (bot - top) * t, w, axis=1)


def hgrad(h, w, stops):
    xs = np.linspace(0.0, 1.0, w)
    vals = np.interp(xs, [s[0] for s in stops], [s[1] for s in stops])
    return np.repeat(vals[None, :], h, axis=0)


# ---------------------------------------------------------------- 画面

def video_frame(w, h):
    """一张"正在播放的帧"：夜戏，有亮部有暗部，方便看出玻璃透不透。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    base = np.zeros((h, w, 3), np.float32)
    t = (yy / h)[..., None]
    top = np.array([16, 26, 44], np.float32)
    bot = np.array([70, 52, 58], np.float32)
    base[:] = top + (bot - top) * t

    for cx, cy, rad, col, k in (
            (w * 0.74, h * 0.34, h * 0.62, (255, 198, 142), 0.62),
            (w * 0.18, h * 0.66, h * 0.48, (84, 146, 255), 0.40),
            (w * 0.52, h * 0.92, h * 0.40, (255, 120, 150), 0.30)):
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / rad
        g = np.clip(1 - d, 0, 1) ** 2.4
        for c in range(3):
            base[..., c] += col[c] * g * k
    # 地平线附近的柔和压暗（不要硬边）
    ramp = np.clip((yy - h * 0.72) / (h * 0.28), 0, 1)[..., None]
    base *= (1.0 - 0.38 * ramp)
    # 画面下部的暖光带：控制条压上去时，玻璃透出来的就是它
    band = np.exp(-((yy - h * 0.90) / (h * 0.09)) ** 2)
    for c, col in enumerate((210, 158, 122)):
        base[..., c] += col * band * 0.55

    # 轻微噪点，像压缩过的视频
    rng = np.random.default_rng(7)
    base += rng.normal(0, 3.0, base.shape[:2])[..., None]
    return np.clip(base, 0, 255)


# ---------------------------------------------------------------- 控件

def glass_panel(cv, px, py, w, h, r, tint, shadow=True):
    if shadow:
        for off, col, a in ((3.0, (0, 0, 0), 0.08), (6.0, (0, 0, 0), 0.05)):
            m = rr_mask(w + off * 0.6, h + off, r, ss=2)
            paint(cv, px - off * 0.3, py + off * 0.75, m, col, a)
    m = rr_mask(w, h, r)
    paint(cv, px, py, m, (0, 0, 0),
          hgrad(int(h), int(w), [(0, tint), (0.09, tint * 0.88), (0.5, tint * 0.78),
                                 (0.91, tint * 0.88), (1, tint)]))
    rim = max(1.0, 0.55 * S)
    paint(cv, px, py, rr_mask(w, h, r, inset=rim * 0.5, width=rim), (255, 255, 255),
          vgrad(int(h), int(w), 0x2E / 255.0, 0x18 / 255.0))


def pill(cv, px, py, w, h, label=None, f=None):
    m = rr_mask(w, h, h / 2.0)
    paint(cv, px, py, m, (255, 255, 255), 0.10)
    paint(cv, px, py, rr_mask(w, h, h / 2.0, inset=0.5 * S, width=0.6 * S),
          (255, 255, 255), 0.14)


def circle_btn(cv, px, py, d_px, icon):
    m = rr_mask(d_px, d_px, d_px / 2.0)
    paint(cv, px, py, m, (255, 255, 255), 0.09)
    paint(cv, px, py, rr_mask(d_px, d_px, d_px / 2.0, inset=0.5 * S, width=0.6 * S),
          (255, 255, 255), 0.13)
    c = d_px / 2.0
    if icon == "pause":
        bw = d_px * 0.13
        for sx in (-1, 1):
            b = np.zeros((int(d_px), int(d_px)), np.float32)
            r0 = int(c - d_px * 0.17)
            r1 = int(c + d_px * 0.17)
            x0 = int(c + sx * d_px * 0.17 - (bw if sx < 0 else 0))
            b[r0:r1, max(0, int(x0)):int(x0 + bw)] = 1.0
            paint(cv, px, py, b, (255, 255, 255), 0.95)
    elif icon == "full":
        b = np.zeros((int(d_px), int(d_px)), np.float32)
        L = int(d_px * 0.22)
        W_ = max(1, int(0.9 * S))
        x0, y0 = int(c - d_px * 0.26), int(c - d_px * 0.26)
        x1, y1 = int(c + d_px * 0.26), int(c + d_px * 0.26)
        b[int(y0):int(y0) + W_, int(x0):int(x0 + L)] = 1
        b[int(y0):int(y0) + L, int(x0):int(x0) + W_] = 1
        b[int(y0):int(y0) + W_, int(x1 - L):int(x1)] = 1
        b[int(y1 - L):int(y1), int(x1 - W_):int(x1)] = 1
        b[int(y1 - W_):int(y1), int(x0):int(x0 + L)] = 1
        b[int(y0):int(y0) + L, int(x1 - W_):int(x1)] = 1
        paint(cv, px, py, b, (255, 255, 255), 0.95)
    elif icon == "more":
        b = np.zeros((int(d_px), int(d_px)), np.float32)
        for k in (-1, 0, 1):
            cx = c + k * d_px * 0.20
            yy, xx = np.mgrid[0:int(d_px), 0:int(d_px)]
            b = np.maximum(b, (np.sqrt((xx - cx) ** 2 + (yy - c) ** 2) < d_px * 0.055).astype(np.float32))
        paint(cv, px, py, b, (255, 255, 255), 0.95)


def seek_bar(cv, px, py, w, prog=0.03):
    h = 2 * S
    m = rr_mask(w, h, h / 2.0)
    paint(cv, px, py, m, (255, 255, 255), 0.19)
    ww = int(w * prog)
    paint(cv, px, py, rr_mask(w, h, h / 2.0)[:, :ww] if ww > 2 else np.zeros((int(h), 1), np.float32),
          (251, 114, 153), 0.90)
    # 圆点
    d = 10 * S
    yy, xx = np.mgrid[0:int(d), 0:int(d)]
    dot = (np.sqrt((xx - d / 2) ** 2 + (yy - d / 2) ** 2) < d / 2).astype(np.float32)
    paint(cv, px + ww - d / 2, py + h / 2 - d / 2, dot, (255, 255, 255), 1.0)


def draw_txt(cv, xy, s, f, col=(255, 255, 255), a=1.0):
    img = Image.fromarray(np.clip(cv, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    d.text(xy, s, font=f, fill=tuple(int(c) for c in col) + (int(a * 255),))
    cv[:] = np.asarray(img, np.float32)


# ---------------------------------------------------------------- 控制条装配

def ctrl_strip(cv, px, py, w, tint):
    """一整个 ctrlWrap：玻璃胶囊 + 进度线。"""
    cap_w = 296            # 胶囊宽度（内容自适应后的近似值）
    cap_h = 36
    capx = px + (w - cap_w) * S / 2.0
    glass_panel(cv, capx, py, cap_w * S, cap_h * S, 18 * S, tint)
    cx = capx
    cy = py

    d = 28 * S
    circle_btn(cv, cx + 11 * S, cy + 4 * S, d, "pause")
    x = cx + 11 * S + d + 9 * S
    draw_txt(cv, (x, cy + 4 * S + d / 2 - 7 * S), "00:03 / 02:46", F_XS, (242, 242, 242), 0.95)
    x += 80 * S

    # 「弹」开关
    m = rr_mask(28 * S, 28 * S, 8 * S)
    paint(cv, x, cy + 4 * S, m, (255, 255, 255), 0.13)
    draw_txt(cv, (x + 8 * S, cy + 4 * S + 3 * S), "弹", F_XS, (255, 255, 255), 0.95)
    x += 33 * S

    for label in ("选集", "倍速 1.25×"):
        wlab = (26 if label == "选集" else 60) * S
        pill(cv, x, cy + 9 * S, wlab, 26 * S)
        draw_txt(cv, (x + 6 * S, cy + 9 * S + 6 * S), label, F_XS, (255, 255, 255), 0.9)
        x += wlab + 5 * S

    circle_btn(cv, x, cy + 10 * S, 24 * S, "more")
    x += 24 * S + 7 * S
    circle_btn(cv, x, cy + 10 * S, 24 * S, "full")

    seek_bar(cv, px + 10 * S, py + (BAR_H - 8) * S, (w - 20) * S)


def info_block(cv, px, py, w_dp, with_tabs):
    """视频信息区（w_dp = 页面宽，单位 dp）。"""
    w = w_dp * S
    y = py
    if with_tabs:
        y += 10 * S                     # infoWrap paddingTop
        draw_txt(cv, (px + 14 * S, y - 2 * S), "简介", F_B, (251, 114, 153), 0.95)
        draw_txt(cv, (px + 14 * S + w * 0.42, y - 2 * S), "评论 50", F_B, (150, 156, 168), 0.95)
        y += TAB_ROW * S
    else:
        y += 10 * S

    # UP 主行
    av = 34 * S
    yy, xx = np.mgrid[0:int(av), 0:int(av)]
    avm = (np.sqrt((xx - av / 2) ** 2 + (yy - av / 2) ** 2) < av / 2).astype(np.float32)
    paint(cv, px + 14 * S, y, avm, (60, 150, 190), 1.0)
    paint(cv, px + 14 * S, y, avm, (255, 255, 255), 0.10)
    nx = px + 14 * S + av + 9 * S
    nw = px + w - 14 * S - 76 * S - 8 * S - nx     # 关注按钮左侧的可用宽度
    draw_txt(cv, (nx, y + 10 * S), fit_text("新起点curtain", F_N, nw), F_N, (251, 114, 153), 1.0)
    # 关注按钮
    bw, bh = 76 * S, 30 * S
    m = rr_mask(bw, bh, bh / 2)
    paint(cv, px + w - 14 * S - bw, y + 2 * S, m, (251, 114, 153), 1.0)
    draw_txt(cv, (px + w - 14 * S - bw + 20 * S, y + 8 * S), "+ 关注", F_B, (255, 255, 255), 1.0)
    y += 34 * S + 10 * S

    # 标题
    for line in ("【联发科不用低人一等】小米联发科MTK永久解锁？",
                 "LK(伪)永久解锁并可自定义选择伪回锁！"):
        draw_txt(cv, (px + 14 * S, y), line, F_T, (240, 241, 245), 1.0)
        y += 20 * S
    y += 6 * S
    draw_txt(cv, (px + 14 * S, y), "3509播放 · 50评论 · 2弹幕 · 2026-11-22", F_XS, (138, 144, 156), 1.0)
    y += 14 * S + 10 * S

    # 互动栏
    counts = ("156", "50", "22", "179", "12")
    step = w / 5.0
    for i, c in enumerate(counts):
        cx = px + step * i + step / 2
        d = 24 * S
        m = rr_mask(d, d, 4 * S)
        paint(cv, cx - d / 2, y, m, (255, 255, 255), 0.16)
        paint(cv, cx - d / 2, y, rr_mask(d, d, 4 * S, inset=0.5 * S, width=0.6 * S),
              (255, 255, 255), 0.10)
        tw = draw_txt_w(c, F_XS)
        draw_txt(cv, (cx - tw / 2, y + d + 4 * S), c, F_XS, (200, 204, 214), 1.0)
    return y + 24 * S + 20 * S - py


_TXT_CACHE = {}


def fit_text(s, f, maxw):
    """按控件实际宽度截断，加省略号（模拟 TextView 的 ellipsize=END）。"""
    if draw_txt_w(s, f) <= maxw:
        return s
    for i in range(len(s) - 1, 0, -1):
        t = s[:i] + "…"
        if draw_txt_w(t, f) <= maxw:
            return t
    return "…"


def draw_txt_w(s, f):
    if s not in _TXT_CACHE:
        tmp = Image.new("L", (10, 10))
        d = ImageDraw.Draw(tmp)
        _TXT_CACHE[s] = d.textlength(s, font=f)
    return _TXT_CACHE[s]


# ---------------------------------------------------------------- 主体

def page(cv, px, py, w, new):
    paint(cv, px, py, np.ones((int(VIDEO_H * S), int(w * S)), np.float32), (0, 0, 0), 1.0)
    frame = video_frame(int(w * S), int(VIDEO_H * S))
    cv[int(py):int(py + VIDEO_H * S), int(px):int(px + w * S)] = frame

    # 画面下沿参考线（极细，仅用于说明边界）
    paint(cv, px, py + VIDEO_H * S - 1, np.ones((1, int(w * S)), np.float32),
          (255, 255, 255), 0.22)

    if new:
        # 控制条压在画面下沿
        ctrl_strip(cv, px, py + (VIDEO_H - BAR_H) * S, w, 0x3C / 255.0)
        return info_block(cv, px, py + VIDEO_H * S, w, False)
    else:
        # 旧版：控制条自占一行，背后是页面底色
        paint(cv, px, py + VIDEO_H * S, np.ones((int(BAR_ROW * S), int(w * S)), np.float32),
              C_BG, 1.0)
        ctrl_strip(cv, px, py + VIDEO_H * S, w, 0x4A / 255.0)
        paint(cv, px, py + (VIDEO_H + BAR_ROW) * S,
              np.ones((int(30 * S), int(w * S)), np.float32), C_BG, 1.0)
        return info_block(cv, px, py + (VIDEO_H + BAR_ROW) * S, w, True)


def callout(d, xy, n, color, r=17):
    x, y = int(xy[0]), int(xy[1])
    d.ellipse([x - r, y - r, x + r, y + r], fill=color)
    d.text((x, y), str(n), font=F_N, fill=(18, 20, 24), anchor="mm")


def annotate(img, pad, w_px, y0, S):
    d = ImageDraw.Draw(img)
    red = (255, 126, 126)
    green = (110, 226, 166)
    b0 = y0 + VIDEO_H * S                 # 画面下沿

    # ① 旧版控制条整行（黑底）
    d.rectangle([pad + 2, b0 + 2, pad + w_px - 2, b0 + BAR_ROW * S - 2], outline=red, width=3)
    callout(d, (pad + w_px - 30, b0 + BAR_ROW * S / 2), 1, red)

    # ② 简介 / 评论 分段
    t0 = b0 + BAR_ROW * S
    d.rectangle([pad + 2, t0 + 2, pad + w_px - 2, t0 + TAB_ROW * S - 2], outline=red, width=3)
    callout(d, (pad + w_px - 30, t0 + TAB_ROW * S / 2), 2, red)

    # ③ 新版：玻璃压在画面上
    x1 = pad * 2 + w_px
    callout(d, (x1 + 22, b0 - BAR_H * S * 0.5), 3, green)

    # 图例
    ly = y0 + int(700 * S) + 18
    lh = int(24 * S)
    for i, (n, col, txt) in enumerate((
            (1, red, "v4.28：控制条自占一整行，背后是 #16181d 页面底色 —— 玻璃无物可透，观感是一条黑底横带"),
            (2, red, "「简介 / 评论 50」不是页签：点不动、不切页，只复述下面的统计，白占约 26dp"),
            (3, green, "v4.29：整条控制条上移自身高度，压在画面下沿，背后是正在播放的帧 —— 玻璃才有东西可透"))):
        callout(d, (pad + 14, ly + i * lh + 8), n, col)
        d.text((pad + 40, ly + i * lh), txt, font=F_XS, fill=(198, 203, 213))


def main(out):
    w_px, h_px = int(PAGE_W * S), int(700 * S)
    pad = int(26 * S)
    canvas = np.zeros((h_px + 120 + int(72 * S), w_px * 2 + pad * 3, 3), np.float32)
    canvas[:] = (10, 11, 14)

    for i, new in enumerate((False, True)):
        x0 = pad + i * (w_px + pad)
        y0 = 100
        sub = np.zeros((int(700 * S), w_px, 3), np.float32)
        sub[:] = C_BG
        page(sub, 0, 0, PAGE_W, new)
        canvas[y0:y0 + sub.shape[0], x0:x0 + w_px] = sub

        label = "v4.29.0 · 玻璃压在画面下沿" if new else "v4.28.0 · 控制条自占一行（黑底）"
        col = (120, 230, 170) if new else (255, 150, 150)
        draw_txt(canvas, (x0 + 4, 56), label, F_LBL, col, 1.0)

        note = "省 91dp 竖向空间" if new else "控制条背后 = 页面底色"
        draw_txt(canvas, (x0 + 4, 52 + 26 * S), note, F_XS,
                 (200, 205, 215) if new else (190, 150, 150), 1.0)

    img = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8))
    annotate(img, pad, w_px, y0, S)
    img.save(out)
    print("saved", out, img.size)


if __name__ == "__main__":
    main("/workspace/哔小趣-v4.29.0-控制条玻璃化对比.png")
