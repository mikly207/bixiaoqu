# -*- coding: utf-8 -*-
"""生成 v4.35.0 修复总览图（动态点头像直出 / 加载提示去弹窗 / 播放调度档位）"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1400, 1960
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


def wrap(d, s, f, maxw):
    """按像素宽度折行（中文按字断行即可）"""
    lines, cur = [], ""
    for ch in s:
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        t = cur + ch
        if d.textlength(t, font=f) > maxw and cur:
            lines.append(cur)
            cur = ch
        else:
            cur = t
    if cur:
        lines.append(cur)
    return lines


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

# ---------------- 标题 ----------------
txt(d, 60, 54, "哔小趣 v4.35.0", F(40, True), FG)
txt(d, 60, 108, "动态点头像直出视频 · 加载提示去弹窗 · 播放调度档位 · 端口线程提速",
    F(21), SUB)
d.line([(60, 152), (W - 60, 152)], fill=LINE, width=2)

Y = 186


def panel(title, sub=None, h=0):
    global Y
    h = h or 250
    top = Y
    rr(d, (60, top, W - 60, top + h), 16, fill=CARD)
    txt(d, 88, top + 26, title, F(23, True), FG)
    if sub:
        txt(d, 88, top + 60, sub, F(16), DIM)
    Y = top + h + 22
    return top + (88 if sub else 70)


# ============ ① 动态点头像 ============
y = panel("① 动态左栏点头像 —— 之前「查不到视频」，还得手动点加载更多碰运气",
          "根因：头像点击只在「关注动态流」里做本地筛选 —— 那一页里未必有他的动态", 382)

# 左：旧版
ox = 88
rr(d, (ox, y, ox + 380, y + 186), 12, fill=CARD2)
d.rectangle((ox, y, ox + 5, y + 186), fill=ACC)
txt(d, ox + 20, y + 14, "旧版：本地筛选", F(19, True), ACC)
txt(d, ox + 20, y + 48, "点头像 → 只在已加载的关注流里找", F(14), SUB)
txt(d, ox + 20, y + 74, "→ 「该 UP 主 最近的动态里没有内容」", F(14), ACC)
txt(d, ox + 20, y + 104, "→ 自己点「加载更多」碰运气", F(14), ACC)
txt(d, ox + 20, y + 134, "→ 还不一定成功（可能压根没拉到）", F(14), ACC)
txt(d, ox + 20, y + 162, "表现：点一下头像，右侧一片空白", F(13), DIM)

# 右：新版
nx = ox + 412
rr(d, (nx, y, nx + 380, y + 186), 12, fill=CARD2)
d.rectangle((nx, y, nx + 5, y + 186), fill=OK)
txt(d, nx + 20, y + 14, "新版：直达 UP 空间", F(19, True), OK)
txt(d, nx + 20, y + 48, "点头像 → 两条链路并行去他空间取", F(14), SUB)
txt(d, nx + 20, y + 74, "① 投稿视频（arc/search）", F(14), FG)
txt(d, nx + 20, y + 100, "② 空间动态（feed/space）", F(14), FG)
txt(d, nx + 20, y + 130, "先回先渲染 · 按 bvid 去重合并", F(14), OK)
txt(d, nx + 20, y + 162, "表现：点一下，视频立刻一条条排出来", F(13), DIM)

yy = y + 200
rr(d, (88, yy, W - 88, yy + 62), 12, fill=(16, 28, 22))
d.rectangle((88, yy, 93, yy + 62), fill=OK)
txt(d, 112, yy + 12, "顺带修掉的两个体验问题", F(17, True), OK)
txt(d, 112, yy + 40, "空态不再误报「没有内容」（两条链路都回来才下结论）· 列表重建保留滚动位置（加载更多不再弹回顶部）",
    F(14), SUB)

# ============ ② 加载提示去弹窗 ============
y = panel("② 播放页加载提示 —— 不要居中弹窗动效，改成顶边一条细线",
          "旧版：画面正中 88dp 吉祥物 + 粉色药丸 + 上下跳的无限循环动画，还把画面遮掉一大块", 352)

ox = 88
rr(d, (ox, y, ox + 380, y + 170), 12, fill=CARD2)
d.rectangle((ox, y, ox + 5, y + 170), fill=ACC)
txt(d, ox + 20, y + 14, "旧版：居中药丸（弹窗动效）", F(18, True), ACC)
# 画一个假视频区 + 居中药丸
vy = y + 48
rr(d, (ox + 20, vy, ox + 360, vy + 104), 10, fill=(18, 20, 25))
rr(d, (ox + 148, vy + 24, ox + 232, vy + 88), 16, fill=(36, 38, 46))
txt(d, ox + 190, vy + 44, "加载中喵~", F(13), ACC, anchor="mm")
txt(d, ox + 34, vy + 86, "（画面正中压着大图标）", F(12), DIM)

nx = ox + 412
rr(d, (nx, y, nx + 380, y + 170), 12, fill=CARD2)
d.rectangle((nx, y, nx + 5, y + 170), fill=OK)
txt(d, nx + 20, y + 14, "新版：顶边 2dp 细线", F(18, True), OK)
vy = y + 48
rr(d, (nx + 20, vy, nx + 360, vy + 104), 10, fill=(18, 20, 25))
# 顶边细线
d.rectangle((nx + 20, vy, nx + 360, vy + 2), fill=(51, 51, 51))
d.rectangle((nx + 20, vy, nx + 92, vy + 2), fill=ACC)
txt(d, nx + 190, vy + 54, "画面完全不被遮挡", F(13), SUB, anchor="mm")
txt(d, nx + 190, vy + 76, "亮条匀速跑动，无弹跳", F(12), DIM, anchor="mm")

yy = y + 182
rr(d, (88, yy, W - 88, yy + 78), 12, fill=(30, 26, 16))
d.rectangle((88, yy, 93, yy + 78), fill=WARN)
txt(d, 112, yy + 12, "延迟上屏再放宽：1300/900ms → 2200/1500ms", F(17, True), WARN)
txt(d, 112, yy + 42, "正常网络下首帧远早于它 → 用户全程看不到任何加载提示；真慢时也还有一条细线兜底。",
    F(14), SUB)

# ============ ③ 播放调度档位 ============
y = panel("③ 设置 → 播放调度：四档预置 + 逐项自定义",
          "以前「流畅度」散成一堆隐式规则，用户只能靠反复改画质去试；现在收成看得懂的档位", 512)

modes = [
    ("超流畅", "老设备 / 网络差 · 优先不卡", "480p 封顶 · 单段 · 不预取", ACC),
    ("超清晰", "好设备 + 快网络 · 优先清晰", "不限清晰度 · 预取拉满", BLU),
    ("平衡", "默认档 · 兼顾流畅与清晰", "1080p 封顶 · 段数自适应", OK),
    ("节能", "最低需求 · 只求不卡", "360p · 单段 · 连接池最小", WARN),
]
mw = (W - 88 * 2 - 3 * 14) / 4.0
for i, (name, hint, spec, col) in enumerate(modes):
    x = 88 + i * (mw + 14)
    rec = name == "平衡"
    rr(d, (x, y, x + mw, y + 150), 12, fill=(20, 34, 26) if rec else CARD2,
       outline=OK if rec else None, width=2)
    txt(d, x + 18, y + 16, name, F(22, True), col)
    if rec:
        tagw = d.textlength("推荐", font=F(12, True)) + 16
        rr(d, (x + mw - tagw - 14, y + 16, x + mw - 14, y + 40), 10, fill=OK)
        txt(d, x + mw - tagw / 2 - 14, y + 21, "推荐", F(12, True), (10, 22, 15), anchor="mm")
    for j, ln in enumerate(wrap(d, hint, F(13), mw - 36)):
        txt(d, x + 18, y + 52 + j * 20, ln, F(13), SUB)
    for j, ln in enumerate(wrap(d, spec, F(13), mw - 36)):
        txt(d, x + 18, y + 104 + j * 20, ln, F(13), FG)

yy = y + 164
items = [
    ("清晰度上限", "480p / 1080p / 不限 / 360p，参与最终封顶"),
    ("并行分段数", "档位要求 与 设备能力 取小 —— 老设备绝不硬开 3 段"),
    ("预取下一条", "节能 / 超流畅关掉后台预取，少占线程与流量"),
    ("卡顿自动降档", "超清晰档禁止 —— 你要清晰，就不偷偷改画质"),
    ("HTTP 连接池", "4~16 条，按档位收放，握手好的连接不被挤掉"),
]
boxh = 40 + len(items) * 25 + 14
rr(d, (88, yy, W - 88, yy + boxh), 12, fill=CARD2)
d.rectangle((88, yy, 93, yy + boxh), fill=BLU)
txt(d, 112, yy + 14, "每一档具体控制什么", F(17, True), BLU)
for j, (a, b) in enumerate(items):
    ry = yy + 48 + j * 25
    txt(d, 116, ry, "· " + a, F(14, True), FG)
    txt(d, 340, ry, b, F(13), SUB)

yy = yy + boxh + 12
rr(d, (88, yy, W - 88, yy + 76), 12, fill=(16, 28, 22))
d.rectangle((88, yy, 93, yy + 76), fill=OK)
txt(d, 112, yy + 12, "自定义档：清晰度上限 / 并行段数 / 预取 / 弹幕 / 自动降档 逐项自己定",
    F(17, True), OK)
txt(d, 112, yy + 42, "用户自己改过画质 / 弹幕之后，调度档位就不再覆盖他的选择 —— 档位管「性能」，用户管「画质」。",
    F(14), SUB)

# ============ ④ 端口与线程 ============
y = panel("④ 端口与线程效率优化 + 冗余代码清理", None, 300)

rows = [
    ("无效的 Thread.setPriority 全部清除", "ART 根本不认 Java 层优先级，16 处「优化」其实是空转", OK),
    ("IO 线程池按档位收放", "代理转发线程 4~16 条，老设备不再常驻 16 条空线程", OK),
    ("HTTP keep-alive 连接池按档位调整", "http.maxConnections 4~16，握手好的连接不被挤掉重做 TLS", OK),
    ("UP 空间请求用独立序号", "关注流与 UP 空间各用各的 seq，互不误伤（否则取消筛选会空列表）", WARN),
    ("删掉只写不读的字段与无用 getter", "loadAnimShowing / connPool() / ioLimit() 等", DIM),
]
for j, (a, b, col) in enumerate(rows):
    ry = y + j * 42
    rr(d, (88, ry, 88 + 8, ry + 30), 3, fill=col)
    txt(d, 112, ry + 4, a, F(16, True), FG)
    txt(d, 640, ry + 6, b, F(14), SUB)

# ---------------- 底部 ----------------
txt(d, 60, H - 96,
    "版本 4.35.0（versionCode 135）· 手机版 / 平板版 × 原版 / iOS 皮肤 共 4 个包",
    F(17, True), FG)
txt(d, 60, H - 62,
    "守卫 301 项 + 单测 8 套 230 项全部通过 · 平板版横屏分屏功能完整保留",
    F(15), DIM)

out = "/workspace/哔小趣-v4.35.0-修复总览.png"
img.save(out)
print("saved:", out, img.size)
