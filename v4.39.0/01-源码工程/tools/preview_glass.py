#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【v4.25.0】播放器玻璃材质 / 控制条布局 1:1 预览。

严格按 Android Canvas 的绘制顺序和 shader 参数复现，不做主观美化。

v4.25.0 的两个主题：
  ① 横屏视频竖屏看时，全屏键不再被挤出屏幕 —— FoldRow 按实测宽度折叠。
  ② 玻璃去塑料感 —— 存在感全部由边缘五道光学承担（高光/热点/暗线/色散/底缘反光）。

对照基准：
  旧 = v4.24.0（玻璃板：0x1E/0x12 乳白雾 + 30/22 噪点 + 0x8E 均匀白描边）
  新 = v4.25.0（玻璃板：0x0A/0x04 乳白雾 + 12/8 噪点 + 0xC4→0x1A 上强下弱边缘光学）
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from PIL import ImageFilter
import math, os

DEN = 1.0          # 1 px = 1 dp
CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
CJK_B = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
MONO = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
OUT = "/workspace/哔小趣-v4.25.0-玻璃与控制条对比.png"

def font(path, size):
    return ImageFont.truetype(path, int(size))

# ---------------------------------------------------------------- 基础工具

def rr_mask(w, h, r, ss=4):
    """圆角矩形 alpha 遮罩（超采样抗锯齿）"""
    w, h = int(round(w)), int(round(h))
    r = max(0, min(r, min(w, h) / 2.0))
    m = Image.new("L", (w * ss, h * ss), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], radius=int(r * ss), fill=255)
    return np.asarray(m.resize((w, h), Image.LANCZOS), dtype=np.float32) / 255.0

def lin_a(w, h, y0, y1, a0, a1):
    t = np.clip((np.arange(h) - y0) / max(1e-6, (y1 - y0)), 0, 1)[:, None]
    col = (a0 + (a1 - a0) * t)
    return np.repeat(col, w, axis=1)

def rad_a(w, h, cx, cy, r, a0, a1):
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    t = np.clip(d, 0, 1)
    return a0 + (a1 - a0) * t

def blend(base, x, y, w, h, rgb, alpha):
    reg = base[y:y + h, x:x + w]
    a = alpha[:, :, None].astype(np.float32)
    base[y:y + h, x:x + w] = reg * (1 - a) + np.array(rgb, dtype=np.float32) * a

def blend_mask(base, x, y, w, h, rgb, alpha, mask):
    """把 rgb 以 alpha 叠到 base[y:y+h, x:x+w]（越界自动裁剪）"""
    x, y, w, h = int(x), int(y), int(w), int(h)
    H, Wd = base.shape[0], base.shape[1]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(Wd, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0:
        return
    sa = alpha[y0 - y:y1 - y, x0 - x:x1 - x]
    sm = mask[y0 - y:y1 - y, x0 - x:x1 - x]
    a = (sa * sm)[:, :, None].astype(np.float32)
    reg = base[y0:y1, x0:x1]
    base[y0:y1, x0:x1] = reg * (1 - a) + np.array(rgb, dtype=np.float32) * a

def safe_region(base, x, y, w, h):
    """返回画布内可用的 (x0,y0,x1,y1) 与偏移量，超界自动裁剪"""
    H, Wd = base.shape[0], base.shape[1]
    x0, y0 = max(0, int(x)), max(0, int(y))
    x1, y1 = min(Wd, int(x + w)), min(H, int(y + h))
    return x0, y0, x1, y1

def draw_shadow(base, x, y, w, h, r, a):
    """在画布内绘制一层投影（自动裁剪，避免越界）"""
    tmp = np.zeros((h, w), dtype=np.float32)
    m = rr_mask(w, h, r * 0.95)
    tmp[:] = m * (a / 255.0)
    x0, y0, x1, y1 = safe_region(base, x, y, w, h)
    if x1 <= x0 or y1 <= y0:
        return base
    reg = base[y0:y1, x0:x1]
    sub = tmp[y0 - int(y):y1 - int(y), x0 - int(x):x1 - int(x)]
    base[y0:y1, x0:x1] = reg * (1 - sub[:, :, None])
    return base

def blend_rgba(base, x, y, w, h, rgb, alpha, mask):
    """rgb: (h,w,3) 逐像素颜色；alpha: (h,w) 逐像素透明度（越界自动裁剪）"""
    x, y, w, h = int(x), int(y), int(w), int(h)
    H, Wd = base.shape[0], base.shape[1]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(Wd, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0:
        return
    sa = alpha[y0 - y:y1 - y, x0 - x:x1 - x]
    sm = mask[y0 - y:y1 - y, x0 - x:x1 - x]
    a = (sa * sm)[:, :, None].astype(np.float32)
    reg = base[y0:y1, x0:x1]
    base[y0:y1, x0:x1] = reg * (1 - a) + rgb[y0 - y:y1 - y, x0 - x:x1 - x] * a

def noise_layer(w, h, seed, maxa, scale=1):
    rs = np.random.RandomState(seed)
    n = rs.rand(max(2, h // scale + 2), max(2, w // scale + 2))
    img = np.array(Image.fromarray((n * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR),
                   dtype=np.float32) / 255.0
    return img * (maxa / 255.0)

def stroke_alpha(w, h, r, inset, width):
    """圆角矩形描边的 alpha 遮罩（inset 内缩，width 线宽）"""
    ss = 4
    w, h, inset = int(round(w)), int(round(h)), int(round(inset))
    outer = rr_mask(w, h, max(0, r - inset), ss)
    inner = rr_mask(max(1, w - 2 * inset), max(1, h - 2 * inset),
                    max(0, r - inset), ss)
    im = np.zeros((h, w), dtype=np.float32)
    im[inset:inset + inner.shape[0], inset:inset + inner.shape[1]] = inner
    band = np.clip(outer - im, 0, 1)
    m = Image.fromarray((band * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(width * ss * 0.35))
    return np.asarray(m, dtype=np.float32) / 255.0

def disp_gradient(w, h, c0, c1, c2):
    """
    三点水平色散渐变：左冷(c0) → 中透明(c1) → 右暖(c2)。
    c0/c1/c2 = (r, g, b, a)，a 为 0~255。
    返回 (rgb_hwb3, alpha_hw)：颜色与 alpha 各自线性插值。
    """
    xs = np.linspace(0, 1, w)
    cols = []
    alphas = []
    for x in xs:
        if x <= 0.5:
            t = x / 0.5
            cols.append(np.array(c0[:3], dtype=np.float32) * (1 - t) + np.array(c1[:3], dtype=np.float32) * t)
            alphas.append((c0[3] * (1 - t) + c1[3] * t) / 255.0)
        else:
            t = (x - 0.5) / 0.5
            cols.append(np.array(c1[:3], dtype=np.float32) * (1 - t) + np.array(c2[:3], dtype=np.float32) * t)
            alphas.append((c1[3] * (1 - t) + c2[3] * t) / 255.0)
    rgb = np.repeat(np.array(cols, dtype=np.float32)[None, :, :], h, axis=0) / 255.0
    a = np.repeat(np.array(alphas, dtype=np.float32)[None, :], h, axis=0)
    return rgb, a

def hexrgb(v):
    return np.array([(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF], dtype=np.float32) / 255.0

def hexa(v):
    return (v & 0xFF) / 255.0

# ---------------------------------------------------------------- 视频背景

def video_bg(w, h, seed=7):
    """模拟一段深色视频画面"""
    base = np.zeros((h, w, 3), dtype=np.float32)
    yy = np.linspace(0, 1, h)[:, None, None]
    base += np.array([0.05, 0.06, 0.10]) * (1 - yy) + np.array([0.02, 0.03, 0.06]) * yy
    base = base.copy()
    rs = np.random.RandomState(seed)
    for _ in range(26):
        bx = rs.randint(0, max(1, w - 60))
        by = rs.randint(0, max(1, h - 40))
        bw = rs.randint(40, max(41, min(260, w - bx)))
        bh = rs.randint(18, max(19, min(70, h - by)))
        col = np.array([0.10, 0.16, 0.26]) + rs.rand(3) * 0.18
        a = rs.uniform(0.35, 0.85)
        reg = base[by:by + bh, bx:bx + bw]
        base[by:by + bh, bx:bx + bw] = reg * (1 - a) + col * a
    if w > 200 and h > 100:
        base[h // 3:h // 3 + 46, w // 6:w // 6 + min(300, w - w // 6)] = np.array([0.86, 0.88, 0.92])
    base += noise_layer(w, h, 3, 16)[:, :, None]
    return np.clip(base, 0, 1)

# ---------------------------------------------------------------- 玻璃板 v4.24.0（旧）

def glass_panel_v424(base, x, y, w, h, r, tint=0x4A):
    """【v4.24.0】旧玻璃板：厚乳白雾 + 重噪点 + 均匀白描边 → 磨砂塑料板"""
    # ① 外投影：旧版四层小偏移硬影
    for i, a in [(4, 0x2E), (3, 0x1A), (2, 0x0D), (1, 0x06)]:
        off = i * 1.6
        mx, my = x + int(off * 0.3), y + int(off * 0.85)
        base = draw_shadow(base, mx, my, w, h, r, a)

    mask = rr_mask(w, h, r)
    # ② 基底压暗
    a = lin_a(w, h, 0, h, tint / 255.0, max(0, tint - 18) / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    # ③ 磨砂乳白雾（旧：0x1E → 0x12）
    a = lin_a(w, h, 0, h, 0x1E / 255.0, 0x12 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ④ 双层颗粒（旧：30 / 22）
    a = noise_layer(w, h, 11, 30) + noise_layer(w, h, 12, 22, scale=3)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑤ 顶部受光
    a = lin_a(w, h, 0, h * 0.45, 0x26 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑥ 左上斜向反光
    a = rad_a(w, h, w * 0.26, -h * 0.28, h * 1.65, 0x1C / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑦ 底缘内暗
    a = lin_a(w, h, h * 0.52, h, 0.0, 0x3A / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    # ⑧ 边缘：外暗线 + 内亮线（均匀一圈白 = 塑料特征）
    blend_mask(base, x, y, w, h, (0, 0, 0), stroke_alpha(w, h, r, 0, 1.0) * (0x30 / 255.0), mask)
    ea = lin_a(w, h, 0, h, 0x8E / 255.0, 0x1C / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), ea * stroke_alpha(w, h, r, 1.0, 1.1), mask)
    # ⑨ 顶棱高光
    sa = lin_a(w, h, 0, 4.0, 0xC4 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), sa * stroke_alpha(w, h, r, 0.5, 0.9), mask)
    # ⑩ 底缘反光
    band = np.zeros((h, w), dtype=np.float32)
    band[max(0, h - 2):h, :] = 1.0
    xs = np.linspace(0, 1, w)
    prof = np.clip(1 - np.abs(xs - 0.5) / 0.42, 0, 1) ** 1.5
    blend_mask(base, x, y, w, h, (1, 1, 1), band * prof[None, :] * (0x40 / 255.0), mask)
    return base

# ---------------------------------------------------------------- 玻璃板 v4.25.0（新）

def glass_panel_v425(base, x, y, w, h, r, tint=0x4A):
    """【v4.25.0】玻璃板：身体近透明，存在感全部交给边缘五道光学"""
    # ① 接触阴影：三层、范围大、alpha 极低（玻璃是「离表面很近地浮着」）
    for i in (3, 2, 1):
        off = i * 2.2
        a = 0x22 if i == 1 else (0x0E if i == 2 else 0x05)
        mx, my = x + int(off * 0.25), y + int(off * 0.9)
        base = draw_shadow(base, mx, my, w, h, r, a)

    mask = rr_mask(w, h, r)
    # ② 基底压暗：上缘略透、下缘略实
    a = lin_a(w, h, 0, h, (tint * 0.72) / 255.0, tint / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    # ③ 磨砂乳白雾：0x0A → 0x04（旧版 0x1E → 0x12，四层白叠起来 ≈ 40% 均匀白 = 塑料板）
    a = lin_a(w, h, 0, h, 0x0A / 255.0, 0x04 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ④ 顶部受光：只占上缘一点点白
    a = lin_a(w, h, 0, h * 0.45, 0x14 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑤ 左上斜向反光
    a = rad_a(w, h, w * 0.22, -h * 0.35, h * 1.9, 0x1A / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑥ 磨砂颗粒：旧 30/22 → 新 12/8（颗粒太重会像砂纸，塑料感来源之一）
    a = noise_layer(w, h, 11, 12) + noise_layer(w, h, 12, 8, scale=3)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ⑦ 底部厚度暗部
    a = lin_a(w, h, h * 0.55, h, 0.0, 0x2E / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)

    # ⑧ 边缘高光：上强下弱（垂直渐变，不是均匀描边）
    rimW = 1.3
    ea = lin_a(w, h, 0, h, 0xC4 / 255.0, 0x1A / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1),
               ea * stroke_alpha(w, h, r, rimW * 0.4, rimW), mask)
    # ⑨ 高光热点：左上角更亮一点，避免高光呆板成一条直线
    a = rad_a(w, h, w * 0.16, -h * 0.25, h * 2.2, 0x78 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1),
               a * stroke_alpha(w, h, r, rimW * 0.4, rimW), mask)
    # ⑩ 接触暗线：高光内侧紧挨的一道细暗线 —— 玻璃的厚度就在这一笔
    ia = lin_a(w, h, 0, h, 0x08 / 255.0, 0x30 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0),
               ia * stroke_alpha(w, h, r, 1.7, 1.1), mask)
    # ⑪ 边缘色散：左冷右暖 —— 塑料永远没有的一笔
    #     ARGB 0x38A9D8FF → (a=0x38, r=A9, g=D8, b=FF)；0x34FFC79A → (a=0x34, r=FF, g=C7, b=9A)
    rgb, al = disp_gradient(w, h,
                            (0xA9, 0xD8, 0xFF, 0x38),
                            (0xFF, 0xFF, 0xFF, 0x00),
                            (0xFF, 0xC7, 0x9A, 0x34))
    blend_rgba(base, x, y, w, h, rgb, al, stroke_alpha(w, h, r, 2.6, 0.9))
    # ⑫ 底缘反光：中间亮两头淡
    band = np.zeros((h, w), dtype=np.float32)
    band[max(0, h - 2):h, :] = 1.0
    xs = np.linspace(0, 1, w)
    prof = np.clip(1 - np.abs(xs - 0.5) / 0.42, 0, 1) ** 1.5
    blend_mask(base, x, y, w, h, (1, 1, 1), band * prof[None, :] * (0x55 / 255.0), mask)
    return base

# ---------------------------------------------------------------- 玻璃片 v4.24.0（旧）

def pill_v424(base, x, y, w, h, r):
    mask = rr_mask(w, h, r)
    a = lin_a(w, h, 0, h, 0x28 / 255.0, 0x14 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    a = lin_a(w, h, h * 0.45, h, 0.0, 0x34 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    ea = lin_a(w, h, 0, h, 0x8E / 255.0, 0x1C / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), ea * stroke_alpha(w, h, r, 1.0, 1.0), mask)
    sa = lin_a(w, h, 0, 3.5, 0xC6 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), sa * stroke_alpha(w, h, r, 0.5, 0.8), mask)
    xs = np.linspace(0, 1, w)
    prof = np.clip(1 - np.abs(xs - 0.5) / 0.40, 0, 1) ** 1.5
    band = np.zeros((h, w), dtype=np.float32)
    band[max(0, h - 2):h, :] = 1.0
    blend_mask(base, x, y, w, h, (1, 1, 1), band * prof[None, :] * (0x42 / 255.0), mask)
    return base

def round_v424(base, cx, cy, d):
    x, y, w, h = cx - d // 2, cy - d // 2, d, d
    mask = rr_mask(w, h, d / 2)
    a = lin_a(w, h, 0, h, 0x28 / 255.0, 0x14 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    a = lin_a(w, h, h * 0.45, h, 0.0, 0x34 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    ea = lin_a(w, h, 0, h, 0x74 / 255.0, 0x18 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), ea * stroke_alpha(w, h, d / 2, 1.0, 1.0), mask)
    sa = lin_a(w, h, 0, 3.5, 0xA0 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), sa * stroke_alpha(w, h, d / 2, 0.5, 0.8), mask)
    return base

# ---------------------------------------------------------------- 玻璃片 v4.25.0（新）

def pill_v425(base, x, y, w, h, r):
    """【v4.25.0】玻璃片：主体近透明 + 边缘五道光学"""
    mask = rr_mask(w, h, r)
    # ① 身体：极淡的乳白雾 0x12 → 0x08
    a = lin_a(w, h, 0, h, 0x12 / 255.0, 0x08 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    # ② 底缘聚暗（很轻）0x24
    a = lin_a(w, h, h * 0.45, h, 0.0, 0x24 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    # ③ 边缘高光：上强下弱 0xB4 → 0x1E
    ea = lin_a(w, h, 0, h, 0xB4 / 255.0, 0x1E / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), ea * stroke_alpha(w, h, r, 1.0, 1.2), mask)
    # ④ 高光热点：左上角 0x60
    a = rad_a(w, h, w * 0.2, -h * 0.2, h * 2.2, 0x60 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a * stroke_alpha(w, h, r, 1.0, 1.2), mask)
    # ⑤ 接触暗线：玻璃的厚度 0x08 → 0x28
    ia = lin_a(w, h, 0, h, 0x08 / 255.0, 0x28 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), ia * stroke_alpha(w, h, r, 1.9, 1.1), mask)
    # ⑥ 边缘色散：左冷右暖
    rgb, al = disp_gradient(w, h,
                            (0xA9, 0xD8, 0xFF, 0x30),
                            (0xFF, 0xFF, 0xFF, 0x00),
                            (0xFF, 0xC7, 0x9A, 0x2C))
    blend_rgba(base, x, y, w, h, rgb, al, stroke_alpha(w, h, r, 2.8, 0.9))
    # ⑦ 顶棱高光 0xA8
    sa = lin_a(w, h, 0, 3.5, 0xA8 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), sa * stroke_alpha(w, h, r, 0.5, 0.8), mask)
    # ⑧ 底缘反光 0x46
    xs = np.linspace(0, 1, w)
    prof = np.clip(1 - np.abs(xs - 0.5) / 0.40, 0, 1) ** 1.5
    band = np.zeros((h, w), dtype=np.float32)
    band[max(0, h - 2):h, :] = 1.0
    blend_mask(base, x, y, w, h, (1, 1, 1), band * prof[None, :] * (0x46 / 255.0), mask)
    return base

def round_v425(base, cx, cy, d):
    x, y, w, h = cx - d // 2, cy - d // 2, d, d
    mask = rr_mask(w, h, d / 2)
    a = lin_a(w, h, 0, h, 0x12 / 255.0, 0x08 / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a, mask)
    a = lin_a(w, h, h * 0.45, h, 0.0, 0x24 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), a, mask)
    ea = lin_a(w, h, 0, h, 0xB4 / 255.0, 0x1E / 255.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), ea * stroke_alpha(w, h, d / 2, 1.0, 1.2), mask)
    a = rad_a(w, h, w * 0.2, -h * 0.2, h * 2.2, 0x60 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), a * stroke_alpha(w, h, d / 2, 1.0, 1.2), mask)
    ia = lin_a(w, h, 0, h, 0x08 / 255.0, 0x28 / 255.0)
    blend_mask(base, x, y, w, h, (0, 0, 0), ia * stroke_alpha(w, h, d / 2, 1.9, 1.1), mask)
    rgb, al = disp_gradient(w, h,
                            (0xA9, 0xD8, 0xFF, 0x30),
                            (0xFF, 0xFF, 0xFF, 0x00),
                            (0xFF, 0xC7, 0x9A, 0x2C))
    blend_rgba(base, x, y, w, h, rgb, al, stroke_alpha(w, h, d / 2, 2.8, 0.9))
    sa = lin_a(w, h, 0, 3.5, 0xA8 / 255.0, 0.0)
    blend_mask(base, x, y, w, h, (1, 1, 1), sa * stroke_alpha(w, h, d / 2, 0.5, 0.8), mask)
    return base

# ---------------------------------------------------------------- 图标 / 文字

def text(base, x, y, s, size, color=(1, 1, 1), bold=False, shadow=True, mono=False):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    f = font(CJK_B if bold else (MONO if mono else CJK), size)
    if shadow:
        sh = Image.new("L", img.size, 0)
        ds = ImageDraw.Draw(sh)
        ds.text((x, y + 1), s, font=f, fill=180)
        sh = sh.filter(ImageFilter.GaussianBlur(2))
        base[:] = np.asarray(img, dtype=np.float32) / 255.0
        img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
        img.paste(Image.new("RGB", img.size, (0, 0, 0)), (0, 0), sh)
        d = ImageDraw.Draw(img)
    d.text((x, y), s, font=f, fill=tuple(int(c * 255) for c in color))
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def icon_play(base, cx, cy, size, color=(1, 1, 1)):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    s = size
    d.rounded_rectangle([cx - s * 0.28, cy - s * 0.34, cx - s * 0.08, cy + s * 0.34],
                        radius=2, fill=tuple(int(c * 255) for c in color))
    d.rounded_rectangle([cx + s * 0.08, cy - s * 0.34, cx + s * 0.28, cy + s * 0.34],
                        radius=2, fill=tuple(int(c * 255) for c in color))
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def icon_full(base, cx, cy, size, color=(1, 1, 1)):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    s = size * 0.34
    w = max(2, int(size * 0.09))
    for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        x0 = cx + sx * s - (s if sx < 0 else 0)
        y0 = cy + sy * s - (s if sy < 0 else 0)
        d.rectangle([x0, y0, x0 + s, y0 + w], fill=tuple(int(c * 255) for c in color))
        d.rectangle([x0, y0, x0 + w, y0 + s], fill=tuple(int(c * 255) for c in color))
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def icon_more(base, cx, cy, size, color=(1, 1, 1)):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    r = max(1.5, size * 0.09)
    for i in (-1, 0, 1):
        d.ellipse([cx + i * size * 0.22 - r, cy - r, cx + i * size * 0.22 + r, cy + r],
                  fill=tuple(int(c * 255) for c in color))
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def seekbar(base, x, y, w, h, progress=0.08, new=True):
    track = 2.0 if new else 3.0
    cy = y + h // 2
    if new:
        # 轨道：极淡的一条白线
        reg = base[int(cy - 1):int(cy + 1), x:x + w]
        base[int(cy - 1):int(cy + 1), x:x + w] = reg * 0.7 + np.array([1, 1, 1]) * 0.3
    else:
        base = pill_v424(base, x, int(cy - track / 2), w, int(track), track / 2)
        reg = base[int(cy - track / 2):int(cy + track / 2), x:x + w]
        base[int(cy - track / 2):int(cy + track / 2), x:x + w] = reg * 0.85 + np.array([1, 1, 1]) * 0.15
    pw = int(w * progress)
    if new:
        reg = base[int(cy - 1):int(cy + 1), x:x + pw]
        base[int(cy - 1):int(cy + 1), x:x + pw] = reg * 0.1 + np.array([0xFB / 255, 0x72 / 255, 0x99 / 255]) * 0.9
    else:
        base = pill_v424(base, x, int(cy - track / 2), pw, int(track), track / 2)
        reg = base[int(cy - track / 2):int(cy + track / 2), x:x + pw]
        base[int(cy - track / 2):int(cy + track / 2), x:x + pw] = reg * 0.1 + np.array([0xFB / 255, 0x72 / 255, 0x99 / 255]) * 0.9
    r = 5.0 if new else 6.5
    cx = x + pw
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255))
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def dashed_rect(base, x0, y0, x1, y1, color=(1, 0.35, 0.42), width=2, dash=7):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    c = tuple(int(v * 255) for v in color)
    for (ax, ay, bx, by) in ((x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)):
        L = math.hypot(bx - ax, by - ay)
        n = max(1, int(L // (dash * 2)))
        for i in range(n):
            t0 = i * 2 * dash / L
            t1 = (i * 2 * dash + dash) / L
            d.line([ax + (bx - ax) * t0, ay + (by - ay) * t0,
                    ax + (bx - ax) * t1, ay + (by - ay) * t1], fill=c, width=width)
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def cross_mark(base, cx, cy, r=13, color=(1, 0.30, 0.38)):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    c = tuple(int(v * 255) for v in color)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=3)
    d.line([cx - r * 0.45, cy - r * 0.45, cx + r * 0.45, cy + r * 0.45], fill=c, width=3)
    d.line([cx + r * 0.45, cy - r * 0.45, cx - r * 0.45, cy + r * 0.45], fill=c, width=3)
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def check_mark(base, cx, cy, r=13, color=(0.35, 0.95, 0.62)):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    c = tuple(int(v * 255) for v in color)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=3)
    d.line([cx - r * 0.5, cy, cx - r * 0.1, cy + r * 0.42], fill=c, width=3)
    d.line([cx - r * 0.1, cy + r * 0.42, cx + r * 0.55, cy - r * 0.42], fill=c, width=3)
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

def arrow(base, x0, y0, x1, y1, color=(1, 0.42, 0.50), width=2):
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    c = tuple(int(v * 255) for v in color)
    d.line([x0, y0, x1, y1], fill=c, width=width)
    ang = math.atan2(y1 - y0, x1 - x0)
    for s in (2.6, -2.6):
        d.line([x1, y1, x1 + 9 * math.cos(ang + s), y1 + 9 * math.sin(ang + s)],
               fill=c, width=width)
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    return base

# ---------------------------------------------------------------- 组装

W = 1280

def phone_frame(base, x, y, w, h, title, sub, title_color):
    """画一个手机外框（竖屏 360dp 宽），返回内容区起点"""
    # 机身
    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x, y, x + w, y + h], radius=22,
                        fill=(10, 11, 14), outline=(62, 66, 76), width=2)
    base[:] = np.asarray(img, dtype=np.float32) / 255.0
    text(base, x + 4, y - 30, title, 16, color=title_color, bold=True)
    text(base, x + 4, y - 11, sub, 12, color=(0.62, 0.66, 0.74))
    return x, y

def build():
    H = 1370
    base = np.zeros((H, W, 3), dtype=np.float32)
    bg = video_bg(W, 220)

    # ================= ① 手机竖屏看横屏视频：控制条溢出 =================
    text(base, 40, 34, "① 横屏视频竖屏看 —— 全屏键曾经被挤出屏幕，按不到", 21, bold=True)
    text(base, 40, 62, "手机 360dp 宽：页面边距 10+10 → 控制条可用 340dp，玻璃板内边距 11+11 → rowTools 实际可用 318dp",
         13, color=(0.66, 0.70, 0.78))

    py = 110
    ph = 250          # 手机屏幕（视频区 212 + 控制条）
    vh = 212

    # ---------- 旧版 v4.24.0：dp 阈值法，宽度靠猜 ----------
    px, _ = phone_frame(base, 60, py, 360, ph, "旧版 v4.24.0", "dp 阈值法猜宽度 → 全屏键跑出屏幕", (1.0, 0.45, 0.52))
    scr = video_bg(360, vh, seed=7)
    rowy = vh - 36
    # 玻璃板被父容器限死 340dp（页面边距 10+10），内容却要 352dp
    scr = glass_panel_v424(scr, 10, rowy, 340, 36, 18)
    # rowTools 内容起点 = 玻璃板左边 + padding 11 = 21
    cx = 21
    scr = round_v424(scr, cx + 14, rowy + 18, 28)
    scr = icon_play(scr, cx + 14, rowy + 18, 18)
    cx += 37                                   # 播放 28 + 间距 9
    scr = text(scr, cx, rowy + 13, "00:01 / 30:53", 10.5, mono=True)
    cx += 98                                   # 时间 88 + 间距 10
    scr = round_v424(scr, cx + 14, rowy + 18, 28)
    scr = text(scr, cx + 8, rowy + 11, "弹", 11)
    cx += 33                                   # 弹幕开关 28 + 间距 5
    scr = pill_v424(scr, cx, rowy + 5, 42, 26, 13)   # 选集（阈值法不管它，一直留着）
    scr = text(scr, cx + 11, rowy + 11, "选集", 11)
    cx += 47                                   # 选集 42 + 间距 5
    scr = pill_v424(scr, cx, rowy + 5, 77, 26, 13)   # 倍速（阈值法也不管它）
    scr = text(scr, cx + 11, rowy + 11, "倍速 1.25×", 11)
    cx += 82                                   # 倍速 77 + 间距 5
    scr = round_v424(scr, cx + 12, rowy + 18, 24)     # 更多 —— 已越过玻璃板右缘 350
    scr = icon_more(scr, cx + 12, rowy + 18, 14)
    cx += 31                                   # 更多 24 + 间距 7
    scr = round_v424(scr, cx + 12, rowy + 18, 24)     # 全屏 —— 349 起，屏幕只到 360
    scr = icon_full(scr, cx + 12, rowy + 18, 14)
    # 屏幕右边界
    scr = dashed_rect(scr, 356, rowy - 4, 360, rowy + 40)
    base[py:py + vh, px:px + 360] = scr
    base = dashed_rect(base, px + 296, rowy - 8, px + 360, rowy + 44)
    base = cross_mark(base, px + 348, rowy + 18, 11)
    base = text(base, px + 4, py + ph + 12, "内容实需 352dp，可用只有 318dp → 溢出 34dp",
                12, color=(1.0, 0.50, 0.56))
    base = text(base, px + 4, py + ph + 30, "「更多」压出玻璃板右缘，「全屏」被推到屏幕外，只剩半个",
                12, color=(1.0, 0.50, 0.56))
    base = text(base, px + 4, py + ph + 48, "看得见却按不到 —— 这就是用户报的那个键",
                12, color=(1.0, 0.50, 0.56))

    # ---------- 新版 v4.25.0：FoldRow 按实测宽度折叠 ----------
    qx, _ = phone_frame(base, 620, py, 360, ph, "新版 v4.25.0", "FoldRow 实测宽度折叠 → 全屏键恒在右端", (0.38, 0.95, 0.64))
    scr = video_bg(360, vh, seed=7)
    rowy2 = vh - 36
    # 折叠后：发弹幕(109) → 下一集(0) → 画质(83) → 倍速(82) 依次收起，只留「选集」(47)
    # 固定区 223 + 选集 47 = 270dp ≤ 318dp；玻璃板 270+22 = 292dp，居中
    glass_w2 = 292
    gx2 = 10 + (340 - glass_w2) // 2
    scr = glass_panel_v425(scr, gx2, rowy2, glass_w2, 36, 18)
    cx = gx2 + 11
    scr = round_v425(scr, cx + 14, rowy2 + 18, 28)
    scr = icon_play(scr, cx + 14, rowy2 + 18, 18)
    cx += 37
    scr = text(scr, cx, rowy2 + 13, "00:01 / 30:53", 10.5, mono=True)
    cx += 98
    scr = round_v425(scr, cx + 14, rowy2 + 18, 28)
    scr = text(scr, cx + 8, rowy2 + 11, "弹", 11)
    cx += 33
    scr = pill_v425(scr, cx, rowy2 + 5, 42, 26, 13)
    scr = text(scr, cx + 11, rowy2 + 11, "选集", 11)
    cx += 47
    scr = round_v425(scr, cx + 12, rowy2 + 18, 24)
    scr = icon_more(scr, cx + 12, rowy2 + 18, 14)
    cx += 31
    scr = round_v425(scr, cx + 12, rowy2 + 18, 24)
    scr = icon_full(scr, cx + 12, rowy2 + 18, 14)
    scr = check_mark(scr, 346, rowy2 + 18, 10)
    base[py:py + vh, qx:qx + 360] = scr
    base = text(base, qx + 4, py + ph + 12, "折叠后实占 270dp ≤ 318dp，余量 48dp",
                12, color=(0.45, 0.93, 0.68))
    base = text(base, qx + 4, py + ph + 30, "「播放/时间/弹幕/更多/全屏」是固定区，永不参与折叠",
                12, color=(0.45, 0.93, 0.68))
    base = text(base, qx + 4, py + ph + 48, "收起的功能在「更多」里：发弹幕 / 倍速 / 画质，一件不丢",
                12, color=(0.78, 0.82, 0.90))

    # ================= ② 宽屏（平板 / 横屏手机）控制条材质对比 =================
    sy = 470
    text(base, 40, sy, "② 控制条材质对比 —— 平板版与横屏宽屏同样受益", 21, bold=True)
    text(base, 40, sy + 28, "同一套 GlassPanel / GlassChip 参数，平板版与手机版完全一致",
         13, color=(0.66, 0.70, 0.78))

    by = sy + 60
    base[by:by + 150, 40:W - 40] = video_bg(W - 80, 150, seed=11)
    # 旧版
    gx = 70
    gy = by + 150 - 58
    base = glass_panel_v424(base, gx, gy, 560, 36, 18)
    cx = gx + 11
    base = round_v424(base, cx + 14, gy + 18, 28)
    base = icon_play(base, cx + 14, gy + 18, 18)
    cx += 28 + 9
    base = text(base, cx, gy + 13, "00:01 / 30:53", 10.5, mono=True)
    cx += 80 + 10
    base = round_v424(base, cx + 14, gy + 18, 28)
    base = text(base, cx + 8, gy + 11, "弹", 11)
    cx += 28 + 5
    base = pill_v424(base, cx, gy + 5, 104, 26, 13)
    base = text(base, cx + 12, gy + 11, "发个弹幕…", 11)
    cx += 104 + 5
    base = pill_v424(base, cx, gy + 5, 42, 26, 13)
    base = text(base, cx + 11, gy + 11, "选集", 11)
    cx += 42 + 5
    base = pill_v424(base, cx, gy + 5, 62, 26, 13)
    base = text(base, cx + 11, gy + 11, "倍速 1.25×", 11)
    cx += 62 + 5
    base = pill_v424(base, cx, gy + 5, 78, 26, 13)
    base = text(base, cx + 11, gy + 11, "画质 1080P", 11)
    cx += 78 + 7
    base = round_v424(base, cx + 12, gy + 18, 24)
    base = icon_more(base, cx + 12, gy + 18, 14)
    cx += 24 + 7
    base = round_v424(base, cx + 12, gy + 18, 24)
    base = icon_full(base, cx + 12, gy + 18, 14)
    base = text(base, gx, gy - 22, "旧 v4.24.0", 14, color=(1.0, 0.45, 0.52), bold=True)

    # 新版
    gx = 660
    base = glass_panel_v425(base, gx, gy, 560, 36, 18)
    cx = gx + 11
    base = round_v425(base, cx + 14, gy + 18, 28)
    base = icon_play(base, cx + 14, gy + 18, 18)
    cx += 28 + 9
    base = text(base, cx, gy + 13, "00:01 / 30:53", 10.5, mono=True)
    cx += 80 + 10
    base = round_v425(base, cx + 14, gy + 18, 28)
    base = text(base, cx + 8, gy + 11, "弹", 11)
    cx += 28 + 5
    base = pill_v425(base, cx, gy + 5, 104, 26, 13)
    base = text(base, cx + 12, gy + 11, "发个弹幕…", 11)
    cx += 104 + 5
    base = pill_v425(base, cx, gy + 5, 42, 26, 13)
    base = text(base, cx + 11, gy + 11, "选集", 11)
    cx += 42 + 5
    base = pill_v425(base, cx, gy + 5, 62, 26, 13)
    base = text(base, cx + 11, gy + 11, "倍速 1.25×", 11)
    cx += 62 + 5
    base = pill_v425(base, cx, gy + 5, 78, 26, 13)
    base = text(base, cx + 11, gy + 11, "画质 1080P", 11)
    cx += 78 + 7
    base = round_v425(base, cx + 12, gy + 18, 24)
    base = icon_more(base, cx + 12, gy + 18, 14)
    cx += 24 + 7
    base = round_v425(base, cx + 12, gy + 18, 24)
    base = icon_full(base, cx + 12, gy + 18, 14)
    base = text(base, gx, gy - 22, "新 v4.25.0", 14, color=(0.38, 0.95, 0.64), bold=True)

    # ================= ③ 材质特写 =================
    ty = by + 190
    tile = video_bg(W - 80, 300, seed=23) * 0.62
    base[ty:ty + 300, 40:W - 40] = tile
    text(base, 60, ty + 18, "旧版：磨砂塑料板", 20, bold=True, color=(1.0, 0.55, 0.60))
    text(base, 680, ty + 18, "新版：薄玻璃", 20, bold=True, color=(0.45, 0.95, 0.68))
    base = glass_panel_v424(base, 60, ty + 54, 560, 44, 20)
    base = text(base, 74, ty + 68, "v4.24.0 玻璃板", 13, color=(0.85, 0.87, 0.92))
    base = glass_panel_v425(base, 680, ty + 54, 560, 44, 20)
    base = text(base, 694, ty + 68, "v4.25.0 玻璃板", 13, color=(0.85, 0.87, 0.92))

    base = pill_v424(base, 60, ty + 112, 150, 34, 16)
    base = text(base, 74, ty + 120, "发个弹幕…", 11.5)
    base = round_v424(base, 232, ty + 129, 40)
    base = icon_play(base, 232, ty + 129, 26)
    base = text(base, 292, ty + 120, "深灰实色 + 1px 白描边", 12, color=(0.80, 0.83, 0.90))
    base = pill_v425(base, 680, ty + 112, 150, 34, 16)
    base = text(base, 694, ty + 120, "发个弹幕…", 11.5)
    base = round_v425(base, 852, ty + 129, 40)
    base = icon_play(base, 852, ty + 129, 26)
    base = text(base, 912, ty + 120, "主体近透明 + 边缘五道光学", 12, color=(0.80, 0.83, 0.90))

    lines = [
        ("旧：乳白雾 0x1E→0x12 + 噪点 30/22 + 均匀一圈 0x8E 白描边", (0.85, 0.55, 0.60)),
        ("　　= 四层白叠成约 40% 的均匀白 → 磨砂塑料板", (0.72, 0.55, 0.58)),
        ("新：乳白雾压到 0x0A→0x04、噪点 12/8，身体几乎透明", (0.45, 0.95, 0.68)),
        ("　　玻璃的「存在感」全部交给边缘：上强下弱的高光 + 左上热点", (0.55, 0.85, 0.72)),
        ("　　+ 内侧接触暗线（厚度）+ 左冷右暖色散 + 底缘反光（悬浮感）", (0.55, 0.85, 0.72)),
        ("平板版：宽度充足不会触发折叠，本版只享受材质升级；手机版：布局 + 材质双修", (0.85, 0.87, 0.92)),
    ]
    for i, (s, c) in enumerate(lines):
        base = text(base, 60, ty + 164 + i * 21, s, 13, color=c)

    # ================= ④ 参数对照表 =================
    qy = ty + 320
    text(base, 40, qy, "④ 材质参数对照（源码级）", 19, bold=True)
    rows = [
        ("玻璃板 乳白雾", "0x1E → 0x12", "0x0A → 0x04"),
        ("玻璃板 磨砂噪点", "30 / 22", "12 / 8"),
        ("玻璃板 边缘高光", "0x8E → 0x1C（均匀）", "0xC4 → 0x1A（上强下弱）"),
        ("玻璃板 高光热点", "无", "0x78（左上径向）"),
        ("玻璃板 接触暗线", "无", "0x08 → 0x30 黑"),
        ("玻璃板 边缘色散", "无", "0x38A9D8FF → 0x34FFC79A"),
        ("玻璃片 主体雾", "0x28 → 0x14", "0x12 → 0x08"),
        ("玻璃片 边缘高光", "0x8E → 0x1C", "0xB4 → 0x1E"),
        ("玻璃片 顶棱 / 暗线 / 色散", "0xC6 / 无 / 无", "0xA8 / 0x08→0x28 / 0x30→0x2C"),
        ("投影", "4 层硬影", "3 层大范围淡影"),
    ]
    colx = [60, 340, 640]
    for i, (a, b, c) in enumerate(rows):
        yy = qy + 32 + i * 24
        base = text(base, colx[0], yy, a, 13, color=(0.80, 0.83, 0.90))
        base = text(base, colx[1], yy, b, 13, color=(1.0, 0.50, 0.56))
        base = text(base, colx[2], yy, c, 13, color=(0.45, 0.93, 0.68))
    # 表格横线：用真正的矩形线，避免不同字体的连字符宽度不一
    for yy in (qy + 26, qy + 32 + len(rows) * 24):
        img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
        d = ImageDraw.Draw(img)
        d.line([colx[0], yy, colx[0] + 900, yy], fill=(72, 76, 88), width=1)
        base[:] = np.asarray(img, dtype=np.float32) / 255.0

    img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    f = font(CJK_B, 15)
    d.text((40, 8), "哔小趣 v4.25.0  全屏键防溢出排版 + 液态玻璃去塑料感",
           font=f, fill=(255, 255, 255))
    img.save(OUT)
    print("saved ->", OUT)

build()
