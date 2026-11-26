#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【v4.27.0】玻璃材质对比图渲染器
按 GlassPanel / GlassChip 的真实绘制逻辑，离线渲染「旧版 v4.26.0」与
「新版 v4.27.0」的横向控制条，用来直观验证：
  ① 上下贯穿边线是否消失（长边中段不再被两条水平线夹住）
  ② 边框是否收窄
  ③ 塑料感是否减弱、玻璃质感是否增强
"""
import os
import re
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SS = 3  # 超采样倍率

# ---------------------------------------------------------------- 字体

def font(size):
    for p in ["/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


F_TITLE = font(30)
F_SEC = font(20)
F_TXT = font(15)
F_SM = font(13)
F_MONO = font(14)


# ---------------------------------------------------------------- 路径解析

def parse_path(d):
    """支持 M/V/h/v/H/L/Z 的 pathData → 多边形列表"""
    toks = re.findall(r'[MmVvHhLlZz]|[-+]?\d*\.?\d+', d)
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
        nums = []
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1}.get(cmd.upper(), 2)
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
        else:
            break
    if len(cur) >= 3:
        polys.append(cur)
    return polys


def rasterize(polys, size_px, ss=4):
    n = size_px * ss
    mask = Image.new('L', (n, n), 0)
    dr = ImageDraw.Draw(mask)
    k = n / 24.0
    for poly in polys:
        dr.polygon([(px * k, py * k) for px, py in poly], fill=255)
    return mask.resize((size_px, size_px), Image.LANCZOS)


# ---------------------------------------------------------------- 材质

def lerp_grad(stops, t):
    """stops = [(pos, (r,g,b,a)), ...] → 按 t 插值"""
    if t <= stops[0][0]:
        return stops[0][1]
    if t >= stops[-1][0]:
        return stops[-1][1]
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        if p0 <= t <= p1:
            f = (t - p0) / max(1e-6, (p1 - p0))
            return tuple(c0[j] + (c1[j] - c0[j]) * f for j in range(4))
    return stops[-1][1]



def _paste(base, ox, oy, n, m, rgb, alpha):
    """按 (m,n) alpha 数组叠加一层纯色到 base 的 (ox,oy) 位置"""
    if rgb == (0, 0, 0):
        img = Image.new('RGB', (n, m), (0, 0, 0))
    else:
        img = Image.new('RGB', (n, m), rgb)
    a = np.asarray(alpha, dtype=np.float32)
    if a.ndim == 1:
        a = np.repeat(a[:, None], n, axis=1)
    if a.shape != (m, n):
        a = np.resize(a, (m, n))
    base.paste(img, (ox, oy), Image.fromarray(
        np.clip(a, 0, 255).astype(np.uint8)))


def glass_bar(base, x0, y0, w, h, radius, version, tint=0x4A):
    """
    按 GlassPanel.onDraw 的真实层次绘制控制条。
    version = 'old' (v4.26.0) | 'new' (v4.27.0)
    """
    n = w * SS
    m = h * SS
    ox, oy = x0 * SS, y0 * SS

    # ---- ① 接触阴影（三层，越新版越淡越散）----
    if version == 'old':
        shadow_spec = [(2.6, 0x22), (5.2, 0x0E), (7.8, 0x05)]
    else:
        shadow_spec = [(2.6, 0x1A), (5.2, 0x0A), (7.8, 0x04)]
    for i, (off, alpha) in enumerate(shadow_spec):
        # 半透明阴影必须画在 RGBA 叠层上再 alpha_composite，
        # 直接往 RGB 图上 fill=(0,0,0,alpha) 会把整块涂成不透明黑。
        sl = Image.new('RGBA', (n + 40 * SS, m + 40 * SS), (0, 0, 0, 0))
        sd = ImageDraw.Draw(sl)
        o = off * SS
        sd.rounded_rectangle((20 * SS + o * 0.25, 20 * SS + o * 0.9,
                              20 * SS + n - o * 0.25, 20 * SS + m + o * 0.8),
                             radius=radius * SS, fill=(0, 0, 0, alpha))
        base.paste(sl, (ox - 20 * SS, oy - 20 * SS), sl)

    # ---- 遮罩层（圆角矩形内）----
    yy = np.linspace(0, 1, m)[:, None]
    xx = np.linspace(0, 1, n)[None, :]
    ys = np.repeat(yy, n, axis=1)

    def blend(color, alpha_arr):
        """在 (ox,oy) 区域把 color 按 alpha_arr 半透明叠加到 base（就地写回）"""
        a = np.asarray(alpha_arr, np.float32)
        if a.ndim == 1:
            a = np.repeat(a[:, None], n, axis=1)
        if a.shape != (m, n):
            a = np.resize(a, (m, n))
        # 兼容两种量纲：>1 视为 0~255 的 alpha 值
        amax = float(np.abs(a).max()) if a.size else 0.0
        if amax > 1.5:
            a = a / 255.0
        a = np.clip(a, 0.0, 1.0)
        region = np.asarray(base.crop((ox, oy, ox + n, oy + m)), np.float32)
        col = np.zeros((m, n, 3), np.float32)
        for c in range(3):
            col[..., c] = color[c]
        out = region * (1 - a[..., None]) + col * a[..., None]
        base.paste(Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)), (ox, oy))

    if version == 'old':
        # 基底压暗：垂直（上 0.72 → 下 1.0）
        tint_a = tint
        a_top = tint_a * 0.72
        a_bot = tint_a
        blend((0, 0, 0), (a_top + (a_bot - a_top) * ys) / 255.0)
        # 磨砂雾 0x08→0x03
        blend((255, 255, 255), (0x08 + (0x03 - 0x08) * ys) / 255.0)
        # 顶部受光 0x14 → 0x00（45% 高）
        blend((255, 255, 255), np.clip(1 - ys / 0.45, 0, 1) * (0x14 / 255.0))
        # 底部厚度暗部 0x00 → 0x2E（55% → 100%）
        blend((0, 0, 0), np.clip((ys - 0.55) / 0.45, 0, 1) * (0x2E / 255.0))
    else:
        # 基底压暗：水平（两端 1.0 → 中段 0.78 → 两端 1.0）
        tint_a = tint
        xs = np.repeat(np.linspace(0, 1, n)[None, :], m, axis=0)
        mid = np.abs(xs - 0.5) * 2.0
        blend((0, 0, 0), (tint_a * (0.78 + 0.22 * mid)) / 255.0)
        # 磨砂雾 0x06→0x02
        blend((255, 255, 255), (0x06 + (0x02 - 0x06) * ys) / 255.0)
        # 顶部受光 0x0A → 0x00（30% 高）
        blend((255, 255, 255), np.clip(1 - ys / 0.30, 0, 1) * (0x0A / 255.0))
        # 端面接地暗影：只压两端（水平）
        blend((0, 0, 0), mid * (0x26 / 255.0) * np.clip(ys * 1.6, 0, 1))

    # ---- 圆角遮罩 ----
    mask = Image.new('L', (n, m), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n - 1, m - 1),
                                            radius=radius * SS, fill=255)

    # ---- 边缘光学 ----
    edge_layer = Image.new('RGBA', (n, m), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge_layer)

    if version == 'old':
        rim_w = max(1, int(1.3 * SS))
        in_w = max(1, int(1.1 * SS))
        in2_w = max(1, int(0.55 * SS))
        dp_w = max(1, int(0.9 * SS))
        ts_w = max(1, int(0.7 * SS))
        # 边缘高光：垂直渐变 上 0xC4 → 下 0x1A
        rim_alpha = np.repeat((0xC4 + (0x1A - 0xC4) * yy) / 255.0, n, axis=1)
        for i in range(m):
            ed.line((0, i, n, i), fill=(255, 255, 255, int(rim_alpha[i, 0] * 255)))
        # 描边（画在矩形边）
        ed.rounded_rectangle((rim_w * 0.4, rim_w * 0.4, n - rim_w * 0.4, m - rim_w * 0.4),
                             radius=radius * SS, outline=(255, 255, 255, 255), width=rim_w)
        # 接触暗线 0x08→0x30
        inner_alpha = np.repeat((0x08 + (0x30 - 0x08) * yy) / 255.0, n, axis=1)
        il = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        idr = ImageDraw.Draw(il)
        for i in range(m):
            idr.line((0, i, n, i), fill=(0, 0, 0, int(inner_alpha[i, 0] * 255)))
        idr.rounded_rectangle((1.7 * SS, 1.7 * SS, n - 1.7 * SS, m - 1.7 * SS),
                              radius=max(0, (radius - 1.7) * SS),
                              outline=(0, 0, 0, 255), width=in_w)
        # 顶面镜面带（贯穿全宽）
        ts = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        tsd = ImageDraw.Draw(ts)
        ts_alpha = np.clip(1 - yy / (5.0 * SS / m * m / m) * 0, 0, 1)
        tsd.rectangle((1.1 * SS, 1.1 * SS, n - 1.1 * SS, 1.1 * SS + ts_w),
                      fill=(255, 255, 255, 192))
        # 底缘反光（贯穿全宽）
        ft = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        ftd = ImageDraw.Draw(ft)
        ftd.rectangle((radius * SS * 0.45, m - 1 * SS - 1,
                      n - radius * SS * 0.45, m - 1),
                     fill=(255, 255, 255, 85))
    else:
        rim_w = max(1, int(0.85 * SS))
        in_w = max(1, int(0.7 * SS))
        in2_w = max(1, int(0.42 * SS))
        dp_w = max(1, int(0.6 * SS))
        ts_w = max(1, int(0.55 * SS))
        POS = [0.0, 0.09, 0.5, 0.91, 1.0]

        def hgrad(stops):
            """水平渐变 → (m,n) alpha 数组"""
            xs = np.linspace(0, 1, n)[None, :]
            out = np.zeros((1, n), np.float32)
            for j in range(len(stops) - 1):
                p0, a0 = stops[j]
                p1, a1 = stops[j + 1]
                sel = (xs >= p0) & (xs <= p1)
                if p1 - p0 < 1e-9:
                    out += np.where(sel, a0, 0)
                    continue
                f = np.clip((xs - p0) / (p1 - p0), 0, 1)
                out += np.where(sel, a0 + (a1 - a0) * f, 0)
            return out

        # 端面高光：两端亮，中段透明
        rim_a = hgrad([(0.0, 0xE6), (0.09, 0x2E), (0.5, 0x00),
                        (0.91, 0x2E), (1.0, 0xE6)]) / 255.0
        rim_a = np.repeat(rim_a, m, axis=0) if rim_a.shape[0] == 1 else rim_a
        el = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        eld = ImageDraw.Draw(el)
        eld.rounded_rectangle((rim_w * 0.4, rim_w * 0.4, n - rim_w * 0.4, m - rim_w * 0.4),
                              radius=radius * SS, outline=(255, 255, 255, 255), width=rim_w)
        el.putalpha(Image.fromarray(
            (np.minimum(np.array(el.getchannel('A'), np.float32), rim_a * 255)).astype(np.uint8)))
        edge_layer.alpha_composite(el)

        # 厚度暗线：两端暗，中段透明
        inner_a = hgrad([(0.0, 0x30), (0.09, 0x08), (0.5, 0x00),
                         (0.91, 0x08), (1.0, 0x30)]) / 255.0
        inner_a = np.repeat(inner_a, m, axis=0) if inner_a.shape[0] == 1 else inner_a
        il = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        idr = ImageDraw.Draw(il)
        idr.rounded_rectangle((1.5 * SS, 1.5 * SS, n - 1.5 * SS, m - 1.5 * SS),
                              radius=max(0, (radius - 1.5) * SS),
                              outline=(0, 0, 0, 255), width=in_w)
        il.putalpha(Image.fromarray(
            (np.minimum(np.array(il.getchannel('A'), np.float32), inner_a * 255)).astype(np.uint8)))
        edge_layer.alpha_composite(il)

        # 内表面反射：两端，中段透明
        in2_a = hgrad([(0.0, 0x3A), (0.09, 0x0C), (0.5, 0x00),
                       (0.91, 0x0C), (1.0, 0x3A)]) / 255.0
        in2_a = np.repeat(in2_a, m, axis=0) if in2_a.shape[0] == 1 else in2_a
        i2 = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        i2d = ImageDraw.Draw(i2)
        i2d.rounded_rectangle((2.6 * SS, 2.6 * SS, n - 2.6 * SS, m - 2.6 * SS),
                              radius=max(0, (radius - 2.6) * SS),
                              outline=(255, 255, 255, 255), width=in2_w)
        i2.putalpha(Image.fromarray(
            (np.minimum(np.array(i2.getchannel('A'), np.float32), in2_a * 255)).astype(np.uint8)))
        edge_layer.alpha_composite(i2)

        # 端面色散：左冷右暖，中段透明
        xs = np.linspace(0, 1, n)[None, :]
        disp_rgb = np.zeros((m, n, 3), np.float32)
        disp_rgb[..., 0] = np.where(xs < 0.5, 169, 255)
        disp_rgb[..., 1] = np.where(xs < 0.5, 216, 199)
        disp_rgb[..., 2] = np.where(xs < 0.5, 255, 154)
        disp_a = hgrad([(0.0, 0x42), (0.10, 0x00), (0.5, 0x00),
                        (0.90, 0x00), (1.0, 0x3E)]) / 255.0
        disp_a = np.repeat(disp_a, m, axis=0) if disp_a.shape[0] == 1 else disp_a
        dm = Image.new('L', (n, m), 0)
        dmd = ImageDraw.Draw(dm)
        dmd.rounded_rectangle((2.2 * SS, 2.2 * SS, n - 2.2 * SS, m - 2.2 * SS),
                              radius=max(0, (radius - 2.2) * SS),
                              outline=255, width=dp_w)
        dm = Image.fromarray(np.minimum(np.array(dm, np.float32), disp_a * 255).astype(np.uint8))
        dl = Image.fromarray(np.dstack([disp_rgb, np.array(dm, np.float32)]).astype(np.uint8), 'RGBA')
        edge_layer.alpha_composite(dl)

        # 顶棱镜面：只左右两段
        end_span = min(n * 0.20, radius * SS * 2.4)
        ts = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        tsd = ImageDraw.Draw(ts)
        tsd.rounded_rectangle((1.0 * SS, 1.0 * SS, end_span, 1.0 * SS + ts_w),
                              radius=ts_w, fill=(255, 255, 255, 200))
        tsd.rounded_rectangle((n - end_span, 1.0 * SS, n - 1.0 * SS, 1.0 * SS + ts_w),
                              radius=ts_w, fill=(255, 255, 255, 200))
        edge_layer.alpha_composite(ts)

        # 底部焦散：只左右两段
        cz = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        czd = ImageDraw.Draw(cz)
        czd.rectangle((0, m - 0.28 * m, end_span, m), fill=(255, 217, 176, 34))
        czd.rectangle((n - end_span, m - 0.28 * m, n, m), fill=(255, 217, 176, 34))
        edge_layer.alpha_composite(cz)

        # 底缘反光：只左右两段
        ft = Image.new('RGBA', (n, m), (0, 0, 0, 0))
        ftd = ImageDraw.Draw(ft)
        fh = max(1, int(0.8 * SS))
        ftd.rectangle((0, m - fh - 0.5 * SS, end_span, m - 0.5 * SS),
                      fill=(255, 255, 255, 85))
        ftd.rectangle((n - end_span, m - fh - 0.5 * SS, n, m - 0.5 * SS),
                      fill=(255, 255, 255, 85))
        edge_layer.alpha_composite(ft)

    # 旧版的镜面带/底缘反光也要合并
    if version == 'old':
        edge_layer.alpha_composite(ts)
        edge_layer.alpha_composite(ft)

    # 遮罩裁剪 + 合成
    edge_layer.putalpha(Image.fromarray(
        (np.array(edge_layer.getchannel('A'), np.float32)
         * (np.array(mask, np.float32) / 255.0)).astype(np.uint8)))
    base.paste(edge_layer, (ox, oy), edge_layer)
    return base


# ---------------------------------------------------------------- 主流程

def main(out):
    W, H = 1500, 1180
    base = Image.new('RGB', (W, H), (18, 20, 24))
    d = ImageDraw.Draw(base)

    # 背景：模拟视频画面（高对比：左亮右暗 + 光斑 + 颗粒），
    # 玻璃的「通透」和「边线」只有在有明暗落差时才看得清楚。
    bg = np.zeros((H, W, 3), np.float32)
    yy2, xx2 = np.mgrid[0:H, 0:W]
    # 大面积明暗：左侧明亮（像天空/高光），右侧深暗
    grad = np.clip(1.05 - (xx2 / W) * 0.95, 0, 1)
    bg[..., 0] = 24 + 96 * grad
    bg[..., 1] = 30 + 88 * grad
    bg[..., 2] = 44 + 74 * grad
    # 两处高光斑
    for (cx, cy, rad, amp) in [(0.22, 0.22, 0.30, 56), (0.78, 0.68, 0.26, 42)]:
        r2 = ((xx2 - W * cx) ** 2 + (yy2 - H * cy) ** 2) / (W * rad) ** 2
        bg += (np.clip(1 - r2, 0, 1) * amp)[..., None]
    # 横向条纹（模拟画面内容，让「透过去」可感）
    bg += (np.sin(yy2 / 9.0) * 4.0)[..., None]
    rng = np.random.default_rng(7)
    bg += rng.normal(0, 2.6, bg.shape)
    base = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(base)

    def T(pos, txt, f, fill):
        d.text(pos, txt, font=f, fill=fill)

    T((40, 26), "哔小趣 v4.27.0 —— 液态玻璃重做：删除上下贯穿边线 · 边框收窄 · 去塑料感",
      F_TITLE, (238, 242, 248))
    T((40, 66), "核心改动：把玻璃的光学从「上下」转到「左右端面」—— 长边中段完全融入画面，"
                "只剩左右端头折射发光", F_TXT, (150, 162, 180))

    BW, BH, BR = 1180, 92, 26
    BY = 130

    # 旧版
    glass_bar(base, 90, BY, BW, BH, BR, 'old')
    d.rounded_rectangle((90, BY, 90 + BW, BY + BH), radius=BR,
                        outline=(232, 92, 108), width=2)
    T((90, BY + BH + 10), "旧版 v4.26.0 —— 上下各有一条贯穿长线（边缘高光 0xC4 + 顶面镜面带 + "
                          "底缘反光），把控制条「框」成一块板", F_SM, (236, 124, 138))

    # 新版
    BY2 = BY + BH + 66
    glass_bar(base, 90, BY2, BW, BH, BR, 'new')
    d.rounded_rectangle((90, BY2, 90 + BW, BY2 + BH), radius=BR,
                        outline=(86, 214, 150), width=2)
    T((90, BY2 + BH + 10), "新版 v4.27.0 —— 上下贯穿线消失，长边中段直接融进视频画面；"
                           "光学集中在左右端头（外亮线→暗线→内反射 三层厚度）",
      F_SM, (126, 228, 178))

    # 端面特写
    T((40, BY2 + BH + 52), "【端面特写】左端头发光更强（折射最集中），长边中段只剩极淡的通透层",
      F_SEC, (214, 222, 234))
    zoom_y = BY2 + BH + 86
    ZW, ZH = 300, 132
    for k, (ver, label, col) in enumerate([
            ('old', "旧版：上下两条贯穿线", (232, 92, 108)),
            ('new', "新版：长边中段无贯穿线", (86, 214, 150))]):
        zx = 90 + k * (ZW + 60)
        # 截取条形左端 300px
        crop = base.crop((90, BY if ver == 'old' else BY2,
                          90 + ZW, (BY if ver == 'old' else BY2) + BH)).resize(
            (ZW * 2, ZH * 2), Image.LANCZOS)
        base.paste(crop, (zx, zoom_y))
        d.rectangle((zx, zoom_y, zx + ZW * 2, zoom_y + ZH * 2), outline=col, width=2)
        T((zx, zoom_y + ZH * 2 + 8), label, F_SM, col)

    # 量化表
    T((40, zoom_y + ZH * 2 + 52), "【量化对比】", F_SEC, (214, 222, 234))
    ty = zoom_y + ZH * 2 + 86
    rows = [
        ("边缘高光渐变方向", "垂直（上 0xC4 → 下 0x1A）", "水平（两端 0xE6 → 中段 0x00）"),
        ("上边线（长边中段）", "贯穿全宽，alpha 0xC4", "完全消失，alpha 0x00"),
        ("下边线（长边中段）", "贯穿全宽，底缘反光 0x55", "完全消失，alpha 0x00"),
        ("边缘高光描边宽度", "1.3dp", "0.85dp（−35%）"),
        ("厚度暗线宽度", "1.1dp", "0.70dp（−36%）"),
        ("内表面反射宽度", "0.55dp", "0.42dp（−24%）"),
        ("色散描边宽度", "0.9dp", "0.60dp（−33%）"),
        ("多层描边叠加总厚", "≈2.0dp", "≈1.1dp（−45%）"),
        ("乳白雾（身体）", "0x08 → 0x03", "0x06 → 0x02（更透）"),
        ("噪点颗粒 alpha", "12 / 8", "8 / 5（细磨砂，非砂纸）"),
        ("顶部受光", "0x14，覆盖 45% 高", "0x0A，覆盖 30% 高"),
        ("基底压暗分布", "垂直（下沿更实）", "水平（两端实、中段透）"),
    ]
    x0, x1, x2 = 90, 420, 830
    d.rectangle((x0, ty, x2 + 340, ty + 30), fill=(30, 34, 42))
    T((x0 + 10, ty + 6), "对比项", F_TXT, (200, 210, 224))
    T((x1 + 10, ty + 6), "旧版 v4.26.0", F_TXT, (236, 124, 138))
    T((x2 + 10, ty + 6), "新版 v4.27.0", F_TXT, (126, 228, 178))
    ry = ty + 34
    for i, (a, b, c) in enumerate(rows):
        if i % 2 == 0:
            d.rectangle((x0, ry, x2 + 340, ry + 27), fill=(24, 27, 33))
        T((x0 + 10, ry + 6), a, F_TXT, (186, 196, 210))
        T((x1 + 10, ry + 6), b, F_TXT, (214, 140, 148))
        T((x2 + 10, ry + 6), c, F_TXT, (150, 224, 190))
        ry += 27

    base.save(out)
    print("已生成：" + out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/workspace/glass_v427.png")
