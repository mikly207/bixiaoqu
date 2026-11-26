# -*- coding: utf-8 -*-
"""v4.30.0 拉流链路示意图：单连接 → 3 段并行 + 有界队列 + 异步落盘。"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1500, 940
BG = (14, 16, 20)
PANEL = (24, 27, 33)
LINE = (60, 66, 78)
FG = (232, 235, 242)
SUB = (150, 157, 172)
RED = (255, 128, 128)
GRN = (110, 226, 166)
BLU = (120, 176, 255)
ACC = (251, 114, 153)


def font(px, bold=False):
    base = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"
    p = base % ("Bold" if bold else "Regular")
    if os.path.exists(p):
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, px, index=idx)
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                pass
    return ImageFont.load_default()


F_T = font(38, True)
F_H = font(24, True)
F_B = font(19)
F_S = font(16)
F_XS = font(14)


def box(d, xy, r=12, fill=PANEL, outline=LINE, w=2):
    d.rounded_rectangle(xy, radius=r, fill=fill, outline=outline, width=w)


def arrow(d, p0, p1, col=SUB, w=3, head=9):
    d.line([p0, p1], fill=col, width=w)
    import math
    ang = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
    for s in (+1, -1):
        a = ang + s * 0.45
        d.line([p1, (p1[0] - head * math.cos(a), p1[1] - head * math.sin(a))],
               fill=col, width=w)


def main(out):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    d.text((48, 34), "v4.30.0 视频拉流链路", font=F_T, fill=FG)
    d.text((48, 84), "单连接吃不满链路 → 3 段并行；端口接收与落盘不再挡住转发", font=F_S, fill=SUB)

    # ---------------- 左：旧 ----------------
    lx, ly = 48, 140
    box(d, (lx, ly, lx + 640, ly + 470))
    d.text((lx + 28, ly + 22), "v4.29  ·  单连接串行", font=F_H, fill=RED)

    # 播放器
    box(d, (lx + 28, ly + 80, lx + 208, ly + 150), fill=(38, 42, 52))
    d.text((lx + 52, ly + 104), "MediaPlayer", font=F_B, fill=FG)

    # 代理
    box(d, (lx + 268, ly + 80, lx + 448, ly + 150), fill=(38, 42, 52))
    d.text((lx + 292, ly + 104), "LocalProxy", font=F_B, fill=FG)

    # CDN
    box(d, (lx + 508, ly + 80, lx + 612, ly + 150), fill=(38, 42, 52))
    d.text((lx + 524, ly + 104), "CDN", font=F_B, fill=FG)

    arrow(d, (lx + 212, ly + 115), (lx + 262, ly + 115))
    arrow(d, (lx + 452, ly + 115), (lx + 502, ly + 115))

    # 单管道
    y = ly + 220
    d.line([(lx + 120, y), (lx + 560, y)], fill=(70, 76, 90), width=22)
    d.line([(lx + 120, y), (lx + 300, y)], fill=ACC, width=22)
    d.text((lx + 120, y + 26), "一条 TCP：吞吐 ≈ 带宽 × RTT 窗口", font=F_S, fill=SUB)
    d.text((lx + 120, y + 54), "实测通常只跑到链路带宽的 40%~60%", font=F_S, fill=RED)

    # 串行读→写
    yy = ly + 330
    for i, t in enumerate(("read 256KB", "write", "read 256KB", "write")):
        x0 = lx + 60 + i * 140
        box(d, (x0, yy, x0 + 120, yy + 46), fill=(34, 38, 46))
        d.text((x0 + 14, yy + 12), t, font=F_XS, fill=SUB)
    d.text((lx + 60, yy + 62), "读与写同线程串行；落盘也在同一条线程上（闪存一抖就停）",
           font=F_XS, fill=SUB)

    # ---------------- 右：新 ----------------
    rx = 736
    box(d, (rx, ly, rx + 716, ly + 470))
    d.text((rx + 28, ly + 22), "v4.30  ·  3 段并行", font=F_H, fill=GRN)

    box(d, (rx + 28, ly + 80, rx + 188, ly + 150), fill=(38, 42, 52))
    d.text((rx + 48, ly + 104), "MediaPlayer", font=F_B, fill=FG)

    box(d, (rx + 252, ly + 58, rx + 464, ly + 174), fill=(38, 42, 52))
    d.text((rx + 270, ly + 74), "LocalProxy", font=F_B, fill=FG)
    d.text((rx + 270, ly + 104), "按段序写出", font=F_XS, fill=SUB)
    d.text((rx + 270, ly + 128), "落盘异步 · 不挡转发", font=F_XS, fill=BLU)

    arrow(d, (rx + 192, ly + 115), (rx + 246, ly + 115))

    # 三段
    lanes = [("段 0  0 ~ 4.0MB", 0), ("段 1  4.0 ~ 8.0MB", 1), ("段 2  8.0 ~ 末", 2)]
    for i, (label, k) in enumerate(lanes):
        yy0 = ly + 210 + i * 78
        box(d, (rx + 60, yy0, rx + 232, yy0 + 52), fill=(34, 38, 46))
        d.text((rx + 74, yy0 + 15), label, font=F_XS, fill=FG)
        # 队列
        qx = rx + 262
        for b in range(4):
            d.rectangle([qx + b * 22, yy0 + 12, qx + b * 22 + 16, yy0 + 40],
                        outline=(90, 96, 110), width=2)
        d.text((qx - 6, yy0 + 56), "有界队列 4×128KB（满则阻塞 = 背压）", font=font(12), fill=SUB)
        box(d, (rx + 470, yy0, rx + 566, yy0 + 52), fill=(34, 38, 46))
        d.text((rx + 486, yy0 + 15), "CDN " + str(i + 1), font=F_XS, fill=FG)
        arrow(d, (rx + 236, yy0 + 26), (rx + 258, yy0 + 26), col=GRN, w=2, head=6)
        arrow(d, (rx + 458, yy0 + 26), (rx + 466, yy0 + 26), col=GRN, w=2, head=6)


    # ---------------- 底部：参数 ----------------
    by = 646
    box(d, (48, by, W - 48, by + 250))
    d.text((72, by + 18), "这一版动到的参数", font=F_H, fill=FG)

    rows = [
        ("并行段数", "1（无）", "3 段 × 3 条连接，闸门上限 6"),
        ("启用条件", "—", "≥4MB + 上游 206 + ≥2 条线路；失败立刻回退单连接续传"),
        ("端口 backlog", "32", "128（换片瞬间新连接不会被拒）"),
        ("回环发送缓冲", "512KB", "1MB（write 更少阻塞，上游窗口不收缩）"),
        ("转发线程池", "4~8 / 队列 48", "6~12 / 队列 96"),
        ("落盘", "同步，占转发线程", "异步单线程，队列满即放弃该份缓存"),
        ("HTTP 连接池", "5（系统默认）", "12（并行段不会被池子掐掉重建）"),
    ]
    y = by + 62
    for k, a, b in rows:
        d.text((72, y), k, font=F_S, fill=SUB)
        d.text((300, y), a, font=F_S, fill=RED if a != "—" else SUB)
        d.text((520, y), b, font=F_S, fill=GRN)
        y += 26

    d.text((48, H - 44), "验证：真实 socket 端到端跑通 12MB 全段，逐字节比对一致（含断点起步、"
                         "单线路、上游不支持 Range、某段 503 回退）", font=F_XS, fill=SUB)
    img.save(out)
    print("saved", out, img.size)


if __name__ == "__main__":
    main("/workspace/哔小趣-v4.30.0-并行拉流链路.png")
