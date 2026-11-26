# -*- coding: utf-8 -*-
"""v4.28.0 对比渲染：多余的光影 / 结构线被删除。

旧版（v4.27.0）每个控件上叠了 7~8 道 STROKE，每道内缩半径不同，
在圆角处就形成一圈圈同心弧 —— 用户截图里红圈框住的正是它们。
新版只留 1 道极淡描边。

渲染全部用 numpy 做 alpha 混合（不用 PIL 的半透明 fill，
那在 RGB 图上会直接把区域涂成不透明色块）。
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SS = 2   # 遮罩超采样


# ---------------------------------------------------------------- 字体

def font(size, bold=False):
    cjk = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    cjk_b = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
    p = cjk_b if (bold and os.path.exists(cjk_b)) else cjk
    if os.path.exists(p):
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, size, index=idx)
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                pass
    return ImageFont.load_default()


F_T = font(30, True)
F_S = font(19, True)
F_B = font(16)
F_M = font(14)
F_XS = font(12)


# ---------------------------------------------------------------- 遮罩

def rr_mask(w, h, r, inset=0.0, width=None):
    """圆角矩形遮罩。width=None → 实心；否则只留一圈描边。"""
    Ws, Hs = int(w * SS), int(h * SS)
    m = Image.new('L', (Ws, Hs), 0)
    d = ImageDraw.Draw(m)
    i = inset * SS
    box = (i, i, Ws - 1 - i, Hs - 1 - i)
    rad = max(0.0, (r - inset) * SS)
    if width is None:
        d.rounded_rectangle(box, radius=rad, fill=255)
    else:
        d.rounded_rectangle(box, radius=rad, outline=255,
                            width=max(1, int(width * SS)))
    return np.asarray(m.resize((int(w), int(h)), Image.LANCZOS), np.float32) / 255.0


def circ_mask(w, h, inset=0.0, width=None):
    Ws, Hs = int(w * SS), int(h * SS)
    m = Image.new('L', (Ws, Hs), 0)
    d = ImageDraw.Draw(m)
    i = inset * SS
    box = (i, i, Ws - 1 - i, Hs - 1 - i)
    if width is None:
        d.ellipse(box, fill=255)
    else:
        d.ellipse(box, outline=255, width=max(1, int(width * SS)))
    return np.asarray(m.resize((int(w), int(h)), Image.LANCZOS), np.float32) / 255.0


# ---------------------------------------------------------------- 混合

def paint(cv, x0, y0, mask, rgb, alpha):
    """把 rgb 以 alpha（0~1，可为 (h,w) 数组或标量）叠到 cv 的 (x0,y0) 处。"""
    H, W = int(cv.shape[0]), int(cv.shape[1])
    h, w = int(mask.shape[0]), int(mask.shape[1])
    x0, y0 = int(x0), int(y0)
    # 裁到画布内（负坐标 / 右侧越界都安全）
    cx0, cy0 = max(x0, 0), max(y0, 0)
    cx1, cy1 = min(x0 + w, W), min(y0 + h, H)
    if cx1 <= cx0 or cy1 <= cy0:
        return
    sx, sy = cx0 - x0, cy0 - y0
    m = mask[sy:sy + (cy1 - cy0), sx:sx + (cx1 - cx0)]
    al = alpha if np.isscalar(alpha) else np.asarray(alpha, np.float32)[sy:sy + (cy1 - cy0), sx:sx + (cx1 - cx0)]
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


def rgrad(h, w, cx, cy, rad, inner, outer):
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.clip(np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / max(rad, 1.0), 0, 1)
    return inner + (outer - inner) * d


A = lambda v: ((v >> 24) & 0xFF) / 255.0   # alpha 在高 8 位
RGB = lambda v: ((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)


# ---------------------------------------------------------------- 控制条

def render_bar(cv, px, py, w_dp, h_dp, r_dp, ver, S):
    """在 cv 的 (px,py) 处画一根控制条；S = 每 dp 像素数。"""
    w, h = int(w_dp * S), int(h_dp * S)
    r = r_dp * S
    mask = rr_mask(w, h, r)

    # 基底压暗（水平：两端实、中段透）
    paint(cv, px, py, mask, (0, 0, 0),
          hgrad(h, w, [(0, 0.27), (0.09, 0.245), (0.5, 0.21),
                       (0.91, 0.245), (1, 0.27)]))

    if ver == 'old':
        # 乳白雾
        paint(cv, px, py, mask, (255, 255, 255), vgrad(h, w, A(0x06FFFFFF), A(0x02FFFFFF)))
        # 顶部受光（30% 高）
        paint(cv, px, py, mask, (255, 255, 255),
              np.clip(1 - np.linspace(0, 1, h)[:, None] / 0.30, 0, 1) * A(0x0AFFFFFF) * np.ones((1, w)))
        # 左上径向反光
        paint(cv, px, py, mask, (255, 255, 255),
              rgrad(h, w, w * 0.22, -h * 0.35, h * 1.9, A(0x1AFFFFFF), 0.0))
        # 端面接地暗影
        paint(cv, px, py, mask, (0, 0, 0),
              hgrad(h, w, [(0, A(0x26000000)), (0.09, A(0x06000000)), (0.5, 0.0),
                           (0.91, A(0x06000000)), (1, A(0x26000000))]))
        # 噪点（仅示意：叠一层细颗粒）
        rng = np.random.default_rng(3)
        paint(cv, px, py, mask, (255, 255, 255),
              rng.uniform(0, A(0x08FFFFFF), (h, w)))

        d = S
        # ⑦ 端面高光 0xE6→0x2E→0（水平）
        paint(cv, px, py, rr_mask(w, h, r, inset=0.85 * d * 0.4, width=0.85 * d),
              (255, 255, 255),
              hgrad(h, w, [(0, A(0xE6FFFFFF)), (0.09, A(0x2EFFFFFF)), (0.5, 0.0),
                           (0.91, A(0x2EFFFFFF)), (1, A(0xE6FFFFFF))]))
        # 端面高光热点（径向，左端）
        paint(cv, px, py, rr_mask(w, h, r, inset=0.85 * d * 0.4, width=0.85 * d),
              (255, 255, 255),
              rgrad(h, w, w * 0.05, h * 0.26, h * 1.7, A(0x66FFFFFF), 0.0))
        # ⑧ 厚度暗线 0x30
        paint(cv, px, py, rr_mask(w, h, r, inset=1.5 * d, width=0.7 * d),
              (0, 0, 0),
              hgrad(h, w, [(0, A(0x30000000)), (0.09, A(0x08000000)), (0.5, 0.0),
                           (0.91, A(0x08000000)), (1, A(0x30000000))]))
        # ⑨ 内表面二次反射 0x3A
        paint(cv, px, py, rr_mask(w, h, r, inset=2.6 * d, width=0.42 * d),
              (255, 255, 255),
              hgrad(h, w, [(0, A(0x3AFFFFFF)), (0.09, A(0x0CFFFFFF)), (0.5, 0.0),
                           (0.91, A(0x0CFFFFFF)), (1, A(0x3AFFFFFF))]))
        # ⑫ 色散（左冷右暖）
        disp = hgrad(h, w, [(0, A(0x42A9D8FF)), (0.10, 0.0), (0.9, 0.0), (1, A(0x3EFFC79A))])
        dm = rr_mask(w, h, r, inset=2.2 * d, width=0.6 * d)
        paint(cv, px, py, dm, RGB(0xA9D8FF), disp)
        paint(cv, px, py, dm, RGB(0xFFC79A), hgrad(h, w, [(0, 0.0), (0.9, 0.0), (1, A(0x3EFFC79A))]))
        # ⑩ 顶棱镜面（左右两段 0xC8）
        span = min(w * 0.20, r * 2.4)
        for ex0 in (int(1.0 * d), int(w - span)):
            tm = rr_mask(int(span), int(h), r, inset=1.0 * d, width=0.55 * d)
            tw = int(span)
            th = h
            m2 = np.zeros((th, tw), np.float32)
            cut = max(1, int(0.55 * d))
            m2[:cut + 2, :] = 1.0
            paint(cv, px + ex0, py, np.minimum(m2, rr_mask(tw, th, r, inset=1.0 * d, width=0.55 * d)),
                  (255, 255, 255), A(0xC8FFFFFF))
        # ⑬ 底缘反光（左右两段 0x55）
        fh = max(1, int(0.8 * d))
        for ex0 in (0, int(w - span)):
            mm = np.zeros((h, int(span)), np.float32)
            mm[max(0, h - fh - 2):h, :] = 1.0
            region = np.zeros((int(span),), np.float32)
            region[int(span * 0.12):int(span * 0.88)] = 1.0
            mm *= region[None, :]
            paint(cv, px + ex0, py, mm, (255, 255, 255), A(0x55FFFFFF))
    else:
        # 新版：只有一道描边
        rim = max(1.0, 0.55 * S)
        paint(cv, px, py, rr_mask(w, h, r, inset=rim * 0.5, width=rim),
              (255, 255, 255), vgrad(h, w, A(0x2EFFFFFF), A(0x18FFFFFF)))


# ---------------------------------------------------------------- 圆钮

def render_btn(cv, px, py, size_dp, ver, S):
    w = h = int(size_dp * S)
    mask = circ_mask(w, h)
    # 主体雾
    paint(cv, px, py, mask, (255, 255, 255), vgrad(h, w, A(0x0AFFFFFF), A(0x04FFFFFF)))

    if ver == 'old':
        d = S
        # 底缘聚暗
        paint(cv, px, py, mask, (0, 0, 0),
              np.clip((np.linspace(0, 1, h)[:, None] - 0.45) / 0.55, 0, 1) * A(0x14000000) * np.ones((1, w)))
        # ① 边缘高光 径向 0xB4
        paint(cv, px, py, circ_mask(w, h, inset=0.85 * d, width=0.75 * d), (255, 255, 255),
              rgrad(h, w, w * 0.26, -h * 0.18, w * 0.95, A(0xB4FFFFFF), A(0x10FFFFFF)))
        # ③ 热点
        paint(cv, px, py, circ_mask(w, h, inset=0.85 * d, width=0.75 * d), (255, 255, 255),
              rgrad(h, w, w * 0.2, -h * 0.2, h * 2.2, A(0x60FFFFFF), 0.0))
        # ④ 接触暗线
        paint(cv, px, py, circ_mask(w, h, inset=1.5 * d, width=0.7 * d), (0, 0, 0),
              rgrad(h, w, w * 0.26, -h * 0.18, w * 0.9, A(0x28000000), A(0x06000000)))
        # ⑤ 色散
        dm = circ_mask(w, h, inset=2.3 * d, width=0.6 * d)
        paint(cv, px, py, dm, RGB(0xA9D8FF),
              hgrad(h, w, [(0, A(0x30A9D8FF)), (0.5, 0.0), (1, 0.0)]))
        paint(cv, px, py, dm, RGB(0xFFC79A),
              hgrad(h, w, [(0, 0.0), (0.5, 0.0), (1, A(0x2CFFC79A))]))
        # ⑥ 顶棱高光 0xA8
        pm = circ_mask(w, h, inset=0.4 * d, width=0.6 * d)
        top = np.zeros((h, w), np.float32)
        cut = max(1, int(h * 0.34))
        top[:cut, :] = 1.0
        paint(cv, px, py, np.minimum(top, pm), (255, 255, 255), A(0xA8FFFFFF))
        # 内表面二次反射
        paint(cv, px, py, circ_mask(w, h, inset=2.8 * d, width=0.42 * d), (255, 255, 255),
              vgrad(h, w, A(0x30FFFFFF), A(0x0AFFFFFF)))
        # 底缘反光
        fm = np.zeros((h, w), np.float32)
        fh = max(1, int(0.8 * d))
        fm[h - fh - 2:h, int(w * 0.12):int(w * 0.88)] = 1.0
        paint(cv, px, py, fm, (255, 255, 255), A(0x46FFFFFF))
    else:
        rim = max(1.0, 0.6 * S)
        paint(cv, px, py, circ_mask(w, h, inset=rim * 0.5, width=rim), (255, 255, 255),
              rgrad(h, w, w * 0.26, -h * 0.18, w * 0.95, A(0x38FFFFFF), A(0x14FFFFFF)))


# ---------------------------------------------------------------- 背景

def make_bg(W, H):
    yy, xx = np.mgrid[0:H, 0:W]
    g = np.clip(1.05 - (xx / W) * 0.95, 0, 1)
    bg = np.zeros((H, W, 3), np.float32)
    bg[..., 0] = 20 + 70 * g
    bg[..., 1] = 26 + 64 * g
    bg[..., 2] = 38 + 56 * g
    for (cx, cy, rad, amp) in [(0.2, 0.25, 0.34, 44), (0.78, 0.72, 0.3, 32)]:
        r2 = ((xx - W * cx) ** 2 + (yy - H * cy) ** 2) / (W * rad) ** 2
        bg += (np.clip(1 - r2, 0, 1) * amp)[..., None]
    rng = np.random.default_rng(11)
    bg += rng.normal(0, 2.2, bg.shape)
    return np.clip(bg, 0, 255)


def T(cv_img, xy, s, f, fill):
    ImageDraw.Draw(cv_img).text(xy, s, font=f, fill=fill)


# ---------------------------------------------------------------- 主流程

def main(out):
    W, H = 1440, 1120
    cv = make_bg(W, H)

    bx, bw = 56, 1000
    S1 = 2.5
    bar_y1, bar_y2 = 168, 320
    tx, zs = 1140, 10.0
    zy1 = 168
    zy2 = zy1 + 40 * zs + 30
    bs = 4.0
    btn_y = 460

    # ── 图形层（不含任何文字）
    render_bar(cv, bx, bar_y1, bw / S1, 40, 18, 'old', S1)
    render_bar(cv, bx, bar_y2, bw / S1, 40, 18, 'new', S1)
    render_bar(cv, tx, zy1, 12, 40, 18, 'old', zs)
    render_bar(cv, tx, zy2, 12, 40, 18, 'new', zs)
    render_btn(cv, bx, btn_y, 24, 'old', bs)
    render_btn(cv, bx + 24 * bs + 60, btn_y, 24, 'new', bs)

    img = Image.fromarray(np.clip(cv, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)

    # ── 文字层
    T(img, (56, 34), "v4.28.0  删掉多余的光影与结构线", F_T, (240, 244, 250))
    T(img, (56, 76), "同一个控制条：旧版叠了 8 道 STROKE，新版只剩 1 道", F_B, (156, 168, 186))

    T(img, (bx, 132), "旧版 v4.27.0", F_S, (238, 122, 136))
    T(img, (bx, 284), "新版 v4.28.0", F_S, (112, 222, 162))

    T(img, (tx, 132), "左端放大 10×", F_S, (204, 212, 224))
    T(img, (bx + 24 * bs + 60, btn_y - 30), "玻璃圆钮 4×", F_S, (204, 212, 224))
    T(img, (bx, btn_y + 24 * bs + 12), "旧：7 道描边", F_XS, (238, 152, 162))
    T(img, (bx + 24 * bs + 60, btn_y + 24 * bs + 12), "新：1 道", F_XS, (132, 222, 172))

    T(img, (tx, zy1 + 40 * zs + 16), "旧：多圈同心弧", F_XS, (238, 152, 162))
    T(img, (tx, zy2 + 40 * zs + 16), "新：1 道淡线", F_XS, (132, 222, 172))

    # ── 量化表
    ty = 700
    T(img, (56, ty), "改动量化", F_S, (240, 244, 250))
    rows = [
        ("控制条描边层数", "8 道", "1 道"),
        ("圆钮描边层数", "7 道", "1 道"),
        ("面板 Shader 数", "13 个", "2 个"),
        ("圆钮 Shader 数", "8 个", "2 个"),
        ("边缘最亮值", "90%（0xE6）", "18%（0x2E）"),
        ("噪点 Bitmap", "2 张（64² + 22²）", "0"),
        ("封面内存缓存", "固定 48MB", "堆/5 自适应"),
        ("首片预热", "96KB", "192KB"),
        ("缓存命中补预热", "不补（永久 miss）", "补（一次去重）"),
    ]
    cy = ty + 42
    for x, s2 in ((56, "项目"), (430, "旧版 v4.27.0"), (700, "新版 v4.28.0")):
        T(img, (x, cy), s2, F_M, (150, 162, 180))
    d.line((56, cy + 25, 980, cy + 25), fill=(72, 82, 98), width=1)
    cy += 36
    for i, (a, b, c) in enumerate(rows):
        col = (232, 238, 248) if i % 2 == 0 else (204, 212, 226)
        T(img, (56, cy), a, F_M, col)
        T(img, (430, cy), b, F_M, (228, 142, 152))
        T(img, (700, cy), c, F_M, (128, 218, 168))
        cy += 29

    note = [
        "结构线从哪来：每一层光学都用一道 STROKE 实现，内缩半径各不相同，",
        "在圆角处叠成多圈同心弧 —— 这就是截图里被红框圈住的那些亮圈。",
        "现在只保留一道 0.55dp / 18% 的折光线，其余 6 层全部删除。",
    ]
    ny = cy + 18
    for s3 in note:
        T(img, (56, ny), s3, F_XS, (150, 162, 180))
        ny += 22

    img.save(out)
    print("saved", out, img.size)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/v428.png")
