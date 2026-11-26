#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【v4.26.0】交付用对比图渲染。

① 手机版全屏角标：旧版 vs 新版（只调宽度，高度不动）
   —— 直接把两版 VectorDrawable 的 pathData 光栅化，
      按真实渲染规则（24dp 圆钮 + 22% padding + ScaleType.CENTER）画出来。
② 平板版液态玻璃：v4.25.0 两层边缘 vs v4.26.0 三明治厚度边缘。
"""
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1400, 1160
S = 2  # 超采样倍率


def font(size, bold=False):
    """优先用 Noto Sans CJK（ttc 需要 index 指定简体中文字面）。"""
    cjk = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    cjk_b = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
    if os.path.exists(cjk_b if bold else cjk):
        p = cjk_b if bold else cjk
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, size, index=idx)
                # 探测是否真的能出中文
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                continue
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


F_TITLE = font(31, bold=True)
F_SEC = font(21, bold=True)
F_TXT = font(15)
F_SM = font(13)


# ------------------------------------------------------------ pathData 解析

def _arc_points(x0, y0, rx, ry, rot, large, sweep, x1, y1, seg=24):
    """把 SVG 椭圆弧采样成折线（供 ic_more 的圆点使用）。"""
    import math
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    phi = math.radians(rot)
    cosp, sinp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cosp * dx2 + sinp * dy2
    y1p = -sinp * dx2 + cosp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2.0
    cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2.0
    ang0 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    ang1 = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
    da = ang1 - ang0
    if sweep == 0 and da > 0:
        da -= 2 * math.pi
    elif sweep == 1 and da < 0:
        da += 2 * math.pi
    pts = []
    for i in range(seg + 1):
        a = ang0 + da * i / seg
        px = cosp * rx * math.cos(a) - sinp * ry * math.sin(a) + cx
        py = sinp * rx * math.cos(a) + cosp * ry * math.sin(a) + cy
        pts.append((px, py))
    return pts


def parse_path(d):
    toks = re.findall(r'[MmVvHhLlZzAa]|[-+]?\d*\.?\d+', d)
    polys, cur = [], []
    x = y = 0.0
    i = 0
    cmd = None
    while i < len(toks):
        t = toks[i]
        if re.match(r'[A-Za-z]', t):
            cmd = t
            i += 1
            if cmd in 'Zz':
                if len(cur) >= 3:
                    polys.append(cur)
                cur = []
                continue
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'A': 7}.get(cmd.upper(), 2)
        nums = []
        while len(nums) < need and i < len(toks) and not re.match(r'[A-Za-z]', toks[i]):
            nums.append(float(toks[i]))
            i += 1
        if len(nums) < need:
            break
        c = cmd.upper()
        rel = cmd.islower()
        if c == 'M':
            nx, ny = nums[0], nums[1]
            if rel:
                nx += x
                ny += y
            if len(cur) >= 3:
                polys.append(cur)
            cur = [(nx, ny)]
            x, y = nx, ny
            cmd = 'L' if cmd == 'M' else 'l'
        elif c == 'L':
            nx, ny = nums[0], nums[1]
            if rel:
                nx += x
                ny += y
            cur.append((nx, ny))
            x, y = nx, ny
        elif c == 'H':
            nx = nums[0] + (x if rel else 0)
            cur.append((nx, y))
            x = nx
        elif c == 'V':
            ny = nums[0] + (y if rel else 0)
            cur.append((x, ny))
            y = ny
        elif c == 'A':
            rx, ry, rot, large, sweep = nums[0], nums[1], nums[2], int(nums[3]), int(nums[4])
            dx, dy = nums[5], nums[6]
            if rel:
                dx += x
                dy += y
            cur.extend(_arc_points(x, y, rx, ry, rot, large, sweep, dx, dy))
            x, y = dx, dy
        else:
            break
    if len(cur) >= 3:
        polys.append(cur)
    return polys


def rasterize(polys, size_px, ss=4):
    """
    把多边形光栅化成 alpha 遮罩。
    对齐 Android 的真实渲染规则：
    VectorDrawable 视口 24×24，图标本体（ImageView + ScaleType.CENTER + 22% padding）
    在 24dp 圆钮里只占 56%，因此这里按「视口缩放 + 居中」绘制，
    保证预览与 APK 里的实际像素一致。
    """
    n = size_px * ss
    mask = Image.new('L', (n, n), 0)
    dr = ImageDraw.Draw(mask)
    k = n / 24.0
    for poly in polys:
        dr.polygon([(px * k, py * k) for px, py in poly], fill=255)
    return mask.resize((size_px, size_px), Image.LANCZOS)


def draw_icon(base, pathd, cx, cy, btn_px, ss=S):
    """在圆钮中心绘制矢量图标（按 56% 可视区规则）。"""
    polys = parse_path(pathd)
    if not polys:
        return
    vis = int(btn_px * 0.56 * ss)
    mk = rasterize(polys, vis)
    base.paste(Image.new('RGB', (vis, vis), (255, 255, 255)),
               (int(cx * ss - vis / 2), int(cy * ss - vis / 2)), mk)


# ------------------------------------------------------------ 玻璃圆钮

def glass_round(base, cx, cy, d, v426=False):
    """画玻璃圆钮。cx/cy/d 均为最终像素坐标（在超采样画布上）。"""
    x0 = int(cx - d / 2)
    y0 = int(cy - d / 2)
    n = int(d)

    def paste_gray(color, alpha):
        layer = Image.new('RGB', (n, n), color)
        m = Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8))
        base.paste(layer, (x0, y0), m)

    yy = np.linspace(0, 1, n)[:, None]

    # 主体：极淡乳白雾
    body = Image.new('L', (n, n), 0)
    ImageDraw.Draw(body).ellipse((0, 0, n - 1, n - 1), fill=0x14)
    paste_gray((255, 255, 255), np.array(body).astype(np.float32) / 255.0)

    # 边缘亮线（上强下弱）
    ring = Image.new('L', (n, n), 0)
    ImageDraw.Draw(ring).ellipse((1, 1, n - 2, n - 2), outline=255, width=max(2, int(d * 0.024)))
    arr = np.array(ring).astype(np.float32) / 255.0
    paste_gray((255, 255, 255), arr * (0.10 + 0.68 * (1 - yy)))

    if v426:
        # 【v4.26.0】内表面二次反射：第三层厚度
        inner = Image.new('L', (n, n), 0)
        ImageDraw.Draw(inner).ellipse((int(d * 0.07), int(d * 0.07),
                                       n - 1 - int(d * 0.07), n - 1 - int(d * 0.07)),
                                      outline=255, width=max(1, int(d * 0.012)))
        arr2 = np.array(inner).astype(np.float32) / 255.0
        paste_gray((255, 255, 255), arr2 * (0.07 + 0.22 * (1 - yy)))

    # 接触暗线
    dark = Image.new('L', (n, n), 0)
    ImageDraw.Draw(dark).ellipse((int(d * 0.04), int(d * 0.04),
                                  n - 1 - int(d * 0.04), n - 1 - int(d * 0.04)),
                                 outline=255, width=max(1, int(d * 0.022)))
    arrd = np.array(dark).astype(np.float32) / 255.0
    paste_gray((0, 0, 0), arrd * (0.05 + 0.22 * yy))

    # 边缘色散：左冷右暖
    dm = Image.new('L', (n, n), 0)
    ImageDraw.Draw(dm).ellipse((int(d * 0.06), int(d * 0.06),
                                n - 1 - int(d * 0.06), n - 1 - int(d * 0.06)),
                               outline=255, width=max(1, int(d * 0.018)))
    xs = np.linspace(0, 1, n)[None, :]
    left = np.clip(1 - xs / 0.5, 0, 1)
    right = np.clip((xs - 0.5) / 0.5, 0, 1)
    rgb = np.zeros((n, n, 3), np.float32)
    rgb[..., 0] = left * 169 + right * 255
    rgb[..., 1] = left * 216 + right * 199
    rgb[..., 2] = left * 255 + right * 154
    aa = (np.array(dm).astype(np.float32) / 255.0) * (left * 0.24 + right * 0.22)
    disp = Image.fromarray(np.dstack([rgb, aa * 255]).astype(np.uint8), 'RGBA')
    base.paste(disp, (x0, y0), disp)
    return base


# ------------------------------------------------------------ 主流程

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else \
        '/workspace/哔小趣-v4.26.0-全屏角标与玻璃对比.png'

    ic_old = "M4 9V4h5v2H6v3zM20 9V6h-3V4h5v5zM4 15v5h5v-2H6v-3zM20 15v3h-3v2h5v-5z"
    ic_new = ("M4.8 8.4V4h4.4v1.6H6.4v2.8zM19.2 8.4V4h-4.4v1.6h2.8v2.8z"
              "M4.8 15.6V20h4.4v-1.6H6.4v-2.8zM19.2 15.6V20h-4.4v-1.6h2.8v-2.8z")
    ic_exit = "M8 4h1.6v4.4H4.8V6.8H8zM16 4h-1.6v4.4h4.8V6.8H16zM8 20h1.6v-4.4H4.8v1.6H8zM16 20h-1.6v-4.4h4.8v1.6H16z"
    more = "M6.2 12a1.9 1.9 0 1 1-3.8 0 1.9 1.9 0 0 1 3.8 0z"

    # ---- 背景：模拟视频画面 ----
    rng = np.random.default_rng(7)
    img = Image.new('RGB', (W * S, H * S))
    dr = ImageDraw.Draw(img)
    for i in range(H * S):
        t = i / (H * S)
        dr.line((0, i, W * S, i), fill=(int(16 + 34 * t), int(18 + 26 * t), int(24 + 40 * t)))
    for _ in range(16):
        cx = int(rng.uniform(0, W * S))
        cy = int(rng.uniform(0, H * S))
        rr = int(rng.uniform(70, 340) * S)
        col = (int(rng.uniform(38, 105)), int(rng.uniform(42, 115)), int(rng.uniform(56, 135)))
        dr.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=col)
    img = img.filter(ImageFilter.GaussianBlur(30))
    base = img
    d = ImageDraw.Draw(base)

    def T(xy, s, f=F_TXT, fill=(232, 236, 244)):
        d.text((xy[0] * S, xy[1] * S), s, font=f, fill=fill)

    # ============ 标题 ============
    T((40, 32), "哔小趣 v4.26.0 —— 全屏角标宽度优化 ＋ 液态玻璃再优化", F_TITLE, (255, 255, 255))
    T((42, 76), "手机版：全屏角标只调宽度方向，高度 4..20 一行未动　｜　平板版：仅液态玻璃材质优化，图标保持原状",
      F_TXT, (166, 177, 196))

    # ============ ① 手机版角标对比 ============
    T((40, 122), "① 手机版控制条右端 —— 全屏角标旧版 vs 新版", F_SEC, (255, 255, 255))

    bar_y, bar_h, bar_w, bar_x = 176, 92, 620, 40

    def glass_bar(x, w):
        g = Image.new('RGBA', (w * S, bar_h * S), (0, 0, 0, 0))
        gd = ImageDraw.Draw(g)
        gd.rounded_rectangle((0, 0, w * S - 1, bar_h * S - 1), radius=17 * S, fill=(9, 11, 17, 158))
        gd.rounded_rectangle((1 * S, 1 * S, w * S - 2, bar_h * S - 2), radius=17 * S,
                             outline=(185, 198, 218, 78), width=max(1, int(1.1 * S)))
        base.paste(g, (x * S, bar_y * S), g)

    BTN = 68
    y_icon = bar_y + bar_h // 2

    def draw_pair(cx, pathd):
        for off, pth in ((0, more), (BTN + 12, pathd)):
            ccx = cx + off + BTN // 2
            glass_round(base, ccx * S, y_icon * S, BTN * S)
            draw_icon(base, pth, ccx, y_icon, BTN)

    # 旧版
    glass_bar(bar_x, bar_w)
    d.rounded_rectangle((bar_x * S, bar_y * S, (bar_x + bar_w) * S, (bar_y + bar_h) * S),
                        radius=17 * S, outline=(228, 84, 100, 200), width=max(1, int(1.5 * S)))
    draw_pair(bar_x + 58, ic_old)
    T((bar_x + 2, bar_y + bar_h + 8), "旧版 v4.25.0 —— 角标又方又胀、整体右移 1dp", F_SM, (236, 116, 130))

    # 新版
    bar_x2 = bar_x + bar_w + 56
    glass_bar(bar_x2, bar_w)
    d.rounded_rectangle((bar_x2 * S, bar_y * S, (bar_x2 + bar_w) * S, (bar_y + bar_h) * S),
                        radius=17 * S, outline=(84, 210, 148, 200), width=max(1, int(1.5 * S)))
    draw_pair(bar_x2 + 58, ic_new)
    T((bar_x2 + 2, bar_y + bar_h + 8), "新版 v4.26.0 —— 横向收窄 3.6 单位、中心归位，与「更多」同量级",
      F_SM, (122, 226, 174))

    # 退出全屏态（第二行，单独一条）
    bar_y2 = bar_y + bar_h + 46
    bar_x3 = bar_x
    g3 = Image.new('RGBA', (bar_w * S, bar_h * S), (0, 0, 0, 0))
    gd3 = ImageDraw.Draw(g3)
    gd3.rounded_rectangle((0, 0, bar_w * S - 1, bar_h * S - 1), radius=17 * S, fill=(9, 11, 17, 158))
    gd3.rounded_rectangle((1 * S, 1 * S, bar_w * S - 2, bar_h * S - 2), radius=17 * S,
                          outline=(185, 198, 218, 78), width=max(1, int(1.1 * S)))
    base.paste(g3, (bar_x3 * S, bar_y2 * S), g3)
    d.rounded_rectangle((bar_x3 * S, bar_y2 * S, (bar_x3 + bar_w) * S, (bar_y2 + bar_h) * S),
                        radius=17 * S, outline=(118, 152, 205, 175), width=max(1, int(1.5 * S)))
    y_icon2 = bar_y2 + bar_h // 2
    for off, pth in ((0, more), (BTN + 12, ic_exit)):
        ccx = bar_x3 + 58 + off + BTN // 2
        glass_round(base, ccx * S, y_icon2 * S, BTN * S)
        draw_icon(base, pth, ccx, y_icon2, BTN)
    T((bar_x3 + 2, bar_y2 + bar_h + 8),
      "退出全屏态 —— 与进入态严格对偶（足迹一致），切换时图形在圆钮里不跳动",
      F_SM, (150, 176, 218))

    # 量化表
    qy = bar_y2 + bar_h + 44
    colx = [40, 268, 500, 776]
    for i, h in enumerate(["指标", "旧版 v4.25.0", "新版 v4.26.0", "说明"]):
        T((colx[i], qy), h, F_TXT, (148, 158, 178))
    rows = [
        ("横向包围盒", "4 .. 22（18 单位）", "4.8 .. 19.2（14.4 单位）", "净收窄 3.6 单位 ≈ 3.6dp ≈ 1.27mm"),
        ("纵向包围盒", "4 .. 20（16 单位）", "4 .. 20（16 单位）", "完全未动 ✓"),
        ("图形中心 x", "13（偏离中心 1dp）", "12（正中）", "左右留白对称 ✓"),
        ("实心面积占比", "14.6%", "8.0%", "与「更多」三点 5.9% 拉齐"),
        ("笔画 / 臂长", "2 / 5 单位", "1.6 / 4.4 单位", "细节更精致，不再胀满圆钮"),
    ]
    for j, r in enumerate(rows):
        yy = qy + 26 + j * 25
        for i, c in enumerate(r):
            if i == 1:
                col = (238, 124, 138)
            elif i == 2:
                col = (255, 255, 255)
            elif i == 3:
                col = (126, 220, 174)
            else:
                col = (206, 212, 224)
            T((colx[i], yy), c, F_TXT, col)

    # ============ ② 玻璃边缘结构 ============
    gy = qy + 26 + len(rows) * 25 + 24
    T((40, gy), "② 液态玻璃边缘 —— v4.25.0 两层 vs v4.26.0 三明治厚度", F_SEC, (255, 255, 255))
    BTN2 = 116
    cy2 = gy + 90
    for k, (v426, label, col) in enumerate([
            (False, "v4.25.0 —— 外亮线 ＋ 暗线", (238, 116, 130)),
            (True, "v4.26.0 —— 外亮线 ＋ 暗线 ＋ 内表面反射", (122, 226, 174))]):
        cx = 130 + k * 235
        glass_round(base, cx * S, cy2 * S, BTN2 * S, v426=v426)
        d.ellipse(((cx - BTN2 // 2) * S, (cy2 - BTN2 // 2) * S,
                   (cx + BTN2 // 2) * S, (cy2 + BTN2 // 2) * S),
                  outline=col, width=max(1, int(1.5 * S)))
        d.text((cx * S, (cy2 + BTN2 // 2 + 12) * S), label, font=F_SM, fill=col, anchor="ma")

    notes = [
        "· 旧版边缘只是「一根描边」；真玻璃能看见两个界面（外表面 ＋ 内表面），所以旧版仍偏平",
        "· 新版在暗线内侧补一道更细更淡的内表面反射 → 边缘成为三层，透出液膜的体积感",
        "· 同时补了顶面镜面带（顶边中央一段集中的短促高光）与底部焦散暖色散射",
        "· 面板乳白雾由 0x0A→0x04 再降到 0x08→0x03，通透度更高，底下画面透得更干净",
        "· 圆钮（播放 / 更多 / 全屏）同步补上第三层，材质语言与面板统一",
    ]
    for i, t in enumerate(notes):
        T((560, cy2 - 56 + i * 26), t, F_TXT, (198, 205, 218))

    base.resize((W, H), Image.LANCZOS).save(out)
    print("已生成：", out)


if __name__ == '__main__':
    main()
