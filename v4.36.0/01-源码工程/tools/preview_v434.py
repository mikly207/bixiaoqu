# -*- coding: utf-8 -*-
"""生成 v4.34.0 修复总览图（闪退根因 / 音画同步 / 切后台续播 / 线程调度）"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1400, 2120
BG = (13, 14, 18)
CARD = (24, 26, 32)
CARD2 = (31, 34, 42)
LINE = (52, 56, 66)
FG = (238, 240, 245)
SUB = (150, 156, 168)
DIM = (108, 114, 126)
ACC = (255, 55, 95)
OK = (52, 199, 123)
WARN = (255, 178, 44)
BLU = (62, 123, 250)

CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-%s.ttc"


def font(px, bold=False):
    p = CJK % ("Bold" if bold else "Regular")
    if os.path.exists(p):
        for idx in (2, 0, 3, 1):
            try:
                f = ImageFont.truetype(p, max(6, int(px)), index=idx)
                if f.getmask("中").getbbox() is not None:
                    return f
            except Exception:
                pass
    return ImageFont.load_default()


F = lambda px, b=False: font(px, b)


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def txt(d, x, y, s, f, fill=FG, anchor=None):
    d.text((x, y), s, font=f, fill=fill, anchor=anchor)


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# ---------- 标题 ----------
txt(d, 60, 54, "哔小趣 v4.34.0", F(40, True), FG)
txt(d, 60, 108, "闪退修复 · 音画同步 · 切屏续播 · 线程调度 · 漏洞自查",
    F(21), SUB)
d.line([(60, 152), (W - 60, 152)], fill=LINE, width=2)

Y = 186


def panel(title, sub=None, h=0):
    """画一个面板，返回内容区起点（标题下方）"""
    global Y
    h = h or 250
    top = Y
    rr(d, (60, top, W - 60, top + h), 16, fill=CARD)
    txt(d, 88, top + 26, title, F(23, True), FG)
    if sub:
        txt(d, 88, top + 60, sub, F(16), DIM)
    Y = top + h + 22
    return top + (88 if sub else 70)


# ================= 1. 闪退根因 =================
y = panel("① 动态页点左栏头像闪退 —— 根因与修法",
      "ClassCastException：左栏头像容器是 FrameLayout，代码却强转成 LinearLayout", 268)
steps = [
    ("renderSide()", "有 try-catch", "异常被吞掉，头像照常显示 → 平时看不出来", OK),
    ("setFilter()", "无 try 保护", "主线程抛异常 → 进程退出 → 闪退", ACC),
    ("applySideStyle()", "强转 FrameLayout→LinearLayout", "就是崩溃的那一行", ACC),
]
for i, (a, b, c, col) in enumerate(steps):
    x = 88 + i * 412
    rr(d, (x, y, x + 380, y + 118), 12, fill=CARD2)
    d.rectangle((x, y, x + 5, y + 118), fill=col)
    txt(d, x + 20, y + 16, a, F(18, True), FG)
    txt(d, x + 20, y + 46, b, F(15), col)
    txt(d, x + 20, y + 72, c, F(14), SUB)
txt(d, 88, y + 134, "修法：全部改成「先判类型、再取子视图」，样式刷新失败也不影响功能",
    F(16), OK)


# ================= 2. 音画同步 =================
y = panel("② 1080p 音画不同步 —— 三档纠偏策略（原来只有「差 0.5 秒就 seek」）",
      "音频轨很轻永远在跑，视频轨重、缓冲耗尽会停住 → 声音越走越远", 306)
rows = [
    ("|Δ| ≤ 200ms", "听感阈值内", "不动（乱动只会引入新抖动）", OK),
    ("|Δ| < 550ms", "轻微漂移", "微调速率 ±3%/6% 渐进拉回，绝不 seek", WARN),
    ("|Δ| ≥ 550ms", "严重失步", "硬对齐（seek）—— 渐进追不上，一步到位", ACC),
]
for i, (a, b, c, col) in enumerate(rows):
    yy = y + i * 52
    rr(d, (88, yy, 88 + 300, yy + 42), 8, fill=CARD2)
    txt(d, 104, yy + 12, a, F(17, True), col)
    txt(d, 410, yy + 12, b, F(16), FG)
    txt(d, 570, yy + 13, c, F(15), SUB)

yy = y + 3 * 52 + 6
rr(d, (88, yy, W - 88, yy + 76), 12, fill=(30, 22, 18))
d.rectangle((88, yy, 93, yy + 76), fill=WARN)
txt(d, 112, yy + 14, "关键补充：画面卡住时先冻结音频", F(18, True), WARN)
txt(d, 112, yy + 44, "画面缓冲耗尽 → 声音继续跑 = 用户听到的「音画分离」。冻结后等画面恢复再一起对齐。",
    F(15), SUB)


# ================= 3. 老设备降档 =================
y = panel("③ 老设备 / 冷门机型 —— 设备能力分级 + 并行段数自适应",
      "1080p 在低端机上不是「慢一点」，而是解码器跟不上 → 缓冲反复耗尽 → 音画分离", 258)
tiers = [
    ("低端", "≤1.5G 内存 / 四核 / Android 5.x", "最高 480p · 并行 1 段 · 单连接", ACC),
    ("中端", "2~3G 内存 / 老系统", "最高 720p · 并行 2 段", WARN),
    ("高端", "6G+ 内存 / 8 核 / 新系统", "不限制 · 并行 3 段", OK),
]
for i, (a, b, c, col) in enumerate(tiers):
    x = 88 + i * 412
    rr(d, (x, y, x + 380, y + 108), 12, fill=CARD2)
    d.rectangle((x, y, x + 5, y + 108), fill=col)
    txt(d, x + 20, y + 14, a, F(20, True), col)
    txt(d, x + 20, y + 46, b, F(14), SUB)
    txt(d, x + 20, y + 74, c, F(15), FG)
txt(d, 88, y + 122, "用户手动选过画质就不自动降档 —— 明确选 1080p 就给 1080p，只在提示后让用户自己改",
    F(15), DIM)


# ================= 4. 切后台续播 =================
y = panel("④ 切到别的 App 再回来，视频又从头播",
      "系统 VideoView 在 Surface 销毁时会 release 播放器，回来后从头 openVideo", 262)
cases = [
    ("Surface 被重建", "保存位置 → 新实例 onPrepared 自动 seek 回去", OK),
    ("Surface 还在", "回到前台检测到是暂停态 → 直接续播", OK),
    ("本来就是暂停的", "标记 bgPausePending → 回来不自己播", BLU),
]
for i, (a, b, col) in enumerate(cases):
    yy = y + i * 46
    rr(d, (88, yy, 88 + 280, yy + 36), 8, fill=CARD2)
    txt(d, 104, yy + 9, a, F(16, True), col)
    txt(d, 392, yy + 10, b, F(15), SUB)


# ================= 5. 线程调度 =================
y = panel("⑤ 网络优先级 —— 之前的「优先级」全是无效代码",
      "ART 不理会 Java 的 Thread.setPriority()，真正生效的是 Process.setThreadPriority()", 286)
bars = [
    ("视频分段 · 代理转发 · 起播取址", -4, ACC),
    ("封面（可见项）", 0, WARN),
    ("封面预取 · 落盘 · 首片预热", 10, DIM),
    ("日志 · 后台清理", 19, DIM),
]
maxw = 520
for i, (name, v, col) in enumerate(bars):
    yy = y + i * 38
    txt(d, 88, yy + 2, name, F(15), FG)
    x0 = 300
    w = int(maxw * (1.0 - (v + 19) / 38.0))
    rr(d, (x0, yy + 4, x0 + maxw, yy + 24), 6, fill=(38, 41, 50))
    rr(d, (x0, yy + 4, x0 + max(w, 14), yy + 24), 6, fill=col)
    txt(d, x0 + maxw + 16, yy + 4, str(v), F(14, True), col)
txt(d, 88, y + 5 * 38 + 4, "越高越优先。改完之后：视频数据永远排在封面之前，落盘和预热彻底让路。",
    F(15), SUB)


# ================= 6. 线路亲和 =================
y = panel("⑥ 平板横屏连点推荐 —— 换片不必重新竞速线路",
      "原来精确亲和的 key 是 host+path，换个视频 path 就变 → 首片又要 3 条线路重新竞速", 268)
boxes = [
    ("旧", "3 条线路竞速\nDNS + TCP + TLS ×3\n≈ 300~900ms", ACC),
    ("新", "跨视频 host 亲和\n命中上次的赢家 → 单连直连\n≈ 50~150ms", OK),
]
for i, (tag, body, col) in enumerate(boxes):
    x = 88 + i * 300
    rr(d, (x, y, x + 268, y + 140), 12, fill=CARD2)
    d.rectangle((x, y, x + 5, y + 140), fill=col)
    txt(d, x + 20, y + 16, tag, F(22, True), col)
    for j, line in enumerate(body.split("\n")):
        txt(d, x + 20, y + 56 + j * 28, line, F(15), FG if j == 0 else SUB)

txt(d, 700, y + 16, "顺带修好的一个隐藏 bug", F(18, True), WARN)
txt(d, 700, y + 48, "精确亲和路径的 finally 里无条件 disconnect()，", F(15), SUB)
txt(d, 700, y + 72, "把「读干净就回池复用」的成果亲手掐掉 ——", F(15), SUB)
txt(d, 700, y + 96, "几十个分片每个都重新握手，v4.31.0 的连接复用", F(15), SUB)
txt(d, 700, y + 120, "在这条最热的路径上从来没生效过。", F(15), SUB)


# ================= 7. 自查 =================
rr(d, (60, Y, W - 60, Y + 150), 16, fill=(18, 28, 22))
d.rectangle((60, Y, 65, Y + 150), fill=OK)
txt(d, 88, Y + 22, "⑦ 程序漏洞自查", F(23, True), OK)
txt(d, 88, Y + 58, "除上面这些，另外还修掉了：取色页子视图强转、游戏大厅 parent 为空的 NPE、", F(15), SUB)
txt(d, 88, Y + 84, "以及 16 处「写了但根本没生效」的线程优先级 —— 它们是「网络调度调不动」的真正原因。", F(15), SUB)
txt(d, 88, Y + 116, "守卫 278 项 · 单测 8 套 178 项 · 全部通过", F(16, True), OK)

out = "/workspace/哔小趣-v4.34.0-修复总览.png"
img.save(out)
print(out, img.size)
