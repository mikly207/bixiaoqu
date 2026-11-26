#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【v4.21.0】源码回归守卫：检查「分屏被拆掉 / 黑屏」那几条危险写法有没有被重新引入。

运行：python3 tools/guard_check.py
"""
import os
import re
import sys

ROOT = "app/src/com/bixiaoqu/app/"
PHONE = "app_phone/src/com/bixiaoqu/app/"

checks = []


def add(name, ok, detail=""):
    checks.append((name, ok, detail))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


tab = read(ROOT + "PlayActivity.java")
phone = read(PHONE + "PlayActivity.java")
policy = read(ROOT + "LayoutPolicy.java")
tx = read(ROOT + "TxVideoView.java")
proxy_t = read(ROOT + "LocalProxy.java")
proxy_p = read(PHONE + "LocalProxy.java")
plan_t = read(ROOT + "ParallelPlan.java")
plan_p = read(PHONE + "ParallelPlan.java")

# 1) 平板：enterPortraitFill 只允许在 maybePortraitFill 内部被调用
calls = [m.start() for m in re.finditer(r"(?<!void )enterPortraitFill\(\);", tab)]
add("平板 enterPortraitFill 调用点 %d 处（应只在统一入口内）" % len(calls), len(calls) == 1,
    "多处直调 = 条件会写漏")

# 2) 平板：applyMeta 里不允许直接进铺满
m = re.search(r"void applyMeta\(", tab)
if m:
    seg = tab[m.start():m.start() + 8000]
    add("applyMeta 不再直接调用 enterPortraitFill", "enterPortraitFill()" not in seg,
        "旧版就是这里把分屏改成竖屏铺满")
    add("applyMeta 使用统一入口 maybePortraitFill", "maybePortraitFill()" in seg)
else:
    add("找到 applyMeta", False)

# 3) 平板：switchVideo 里不允许退出分屏
m = re.search(r"void switchVideo\(String bv, long cid, long durSec, String title, float aspect\)", tab)
if m:
    seg = tab[m.start():m.start() + 3000]
    add("switchVideo 不调用 exitSplitMode", "exitSplitMode" not in seg, "换片不许拆掉分屏")
    add("switchVideo 保留分屏画面盒", "applySplitSizeNow()" in seg)
else:
    add("找到 switchVideo(aspect)", False)

# 4) 平板：竖屏铺满自身带硬门槛
m = re.search(r"void enterPortraitFill\(\) \{", tab)
if m:
    seg = tab[m.start():m.start() + 900]
    add("enterPortraitFill 自带 splitMode 硬门槛", "splitMode" in seg)
    add("enterPortraitFill 自带横屏硬门槛", "isLandscapeNow()" in seg)
else:
    add("找到 enterPortraitFill", False)

# 5) 翻页只允许竖屏铺满
m = re.search(r"void enterSwipeMode\(\) \{", tab)
add("enterSwipeMode 有 portraitFill / 横屏门槛",
    bool(m) and "portraitFill" in tab[m.start():m.start() + 600],
    "翻页跑到分屏里会重排两槽")

# 6) 返回键语义集中在 LayoutPolicy
add("平板返回键走 LayoutPolicy.backAction", tab.count("LayoutPolicy.backAction") >= 2)
add("手机版返回键走 LayoutPolicy.backAction", phone.count("LayoutPolicy.backAction") >= 2)

# 7) 海报闸门 & 解码器释放
add("海报贴图前过 shouldShowPoster", "LayoutPolicy.shouldShowPoster" in tab
    and "LayoutPolicy.shouldShowPoster" in phone)
add("TxVideoView 有 reset 兜底释放", "releaseInnerPlayer" in tx and "mMediaPlayer" in tx)
add("TxVideoView 首帧判定检查视图尺寸", "getWidth() <= 0" in tx and "isShown()" in tx)
add("TxVideoView 提供 Surface 重建", "rebuildSurface" in tx)

# 8) 音轨起点不能直接用 -1
add("音轨起播位置走 audioStartTarget", "audioStartTarget" in tab and "audioStartTarget" in phone)

# 9) 两版都引入策略类
add("平板包含 LayoutPolicy", "public final class LayoutPolicy" in policy)
add("手机版包含 LayoutPolicy", "canPortraitFill" in phone)

# ---------------------------------------------------------------- v4.22.0
lproxy = read(ROOT + "LocalProxy.java")
app = read(ROOT + "BxqApp.java")
diag = read(ROOT + "StartupDiag.java")
cache = read(ROOT + "DiskCache.java")

# 10) 相关推荐不再被起播窗口推迟（「慢一拍」的根因）
add("平板推荐请求不再走 guardStartup", 'guardStartup("rec", new' not in tab,
    "推荐是几百字节 JSON，排队等首帧就是用户看到的「慢一拍」")
add("手机版推荐请求不再走 guardStartup", 'guardStartup("rec", new' not in phone)

# 11) 推荐缓存 + 骨架 + 换片刷新
for name, src, tag in (("平板", tab, ""), ("手机版", phone, "")):
    add(name + "推荐命中内存缓存", "BiliApi.relatedCached" in src)
    add(name + "推荐有骨架占位", "fillRecSkeleton" in src)
    add(name + "换片后刷新右栏（从右栏点进来的除外）",
        "if (!swipeMode && !keepRec) loadRec();" in src)

# 12) 相邻预加载 & 首片预热
add("预取前两条都带首片预热", "done < 2" in tab and "done < 2" in phone)
add("首片预热只读 192KB（覆盖首个 GOP，边缘缓存真正捂热）",
    "192 * 1024" in read(ROOT + "BiliApi.java")
    and "bytes=0-196607" in read(ROOT + "BiliApi.java"))
add("预热走独立低优先级线程池", "WARM_POOL" in read(ROOT + "BiliApi.java"))
add("封面异步预取在推荐上屏后触发", "prefetchCovers(got, 6)" in tab)

# 13) 磁盘缓存
add("DiskCache 单文件上限 48MB", "MAX_FILE = 48L * 1024 * 1024" in cache)
add("DiskCache 总量上限 400MB", "MAX_TOTAL = 400L * 1024 * 1024" in cache)
add("DiskCache key 用 URL path（跨会话可用）", "DiskCache.keyOfUrl" in lproxy)
add("代理先查磁盘缓存", "DiskCache.lookup" in lproxy)
add("代理边转发边落盘（v4.30 起走异步 DiskSink）", "sink.write(buf, 0, n)" in lproxy)
add("半截文件不提交", "bytes == len" in lproxy)
add("应用启动初始化磁盘缓存", "DiskCache.init" in app)

# 14) 网络/解码双诊断
add("起播打点三件套齐全",
    "StartupDiag.onClick" in tab and "StartupDiag.onFetch" in tab and "StartupDiag.onFrame" in tab)
add("手机版同样具备起播诊断",
    "StartupDiag.onClick" in phone and "StartupDiag.onFrame" in phone)
add("判定区分网络慢与解码慢", "DECODE_SLOW" in diag and "NET_SLOW" in diag)
add("自动降档需连续确认", "slowStreak >= CONFIRM" in diag)
add("选档走 chooseHeight 限幅", "chooseHeight(q, Prefs.qualityHeight(q))" in tab)
add("平板退出时反射释放解码器", "video.stopPlayback(); } catch (Throwable ignore) { }" in tab
    and "video.reset(); } catch (Throwable ignore) { }" in tab)
add("手机版退出时反射释放解码器", "video.reset(); } catch (Throwable ignore) { }" in phone)
add("骨架行会替换上一个视频的旧推荐", "stale" in tab and "stale" in phone)


# 13) 【v4.23.0】播放器液态玻璃 + 控件可读性
glass = read(ROOT + "GlassPanel.java")
blur = read(ROOT + "GlassBlur.java")
nav = read(ROOT + "LiquidNavBar.java")
add("玻璃面板是自绘 ViewGroup（可原地替换 LinearLayout）",
    "public class GlassPanel extends LinearLayout" in glass)
add("玻璃不再铺磨砂颗粒层（v4.28.0 删掉噪点，只留一道折光线）",
    "BitmapShader" not in glass and "ensureNoise" not in glass)
add("玻璃只保留一道描边（不再有顶部受光/径向热点等叠加层）",
    "RadialGradient" not in glass and "edge.setStrokeWidth" in glass)
add("玻璃有扫光动画且随视图销毁取消", "playSheen" in glass and "sheenAnim.cancel()" in glass)
add("模糊内核抽成公共类", "class GlassBlur" in blur)
add("底栏复用公共模糊内核（不再各写一份）", "GlassBlur.blurInPlace" in nav and "boxBlur" not in nav)
add("横屏控制条用玻璃面板", "ctrlBar = new GlassPanel(this)" in tab)
add("竖屏控制条用玻璃面板", "vBottom = new GlassPanel(this)" in phone and "vBottom = new GlassPanel(this)" in tab)
add("玻璃基底压暗降到 23%（背后视频透得出来）",
    "tint(iosUi ? 0x2E : 0x3C)" in tab or ".tint(ios ? 0x2E : 0x3C)" in tab
    or ".tint(0x3C)" in tab)
add("玻璃卡留外边距，圆角不贴屏幕", "lp.leftMargin = dp(10)" in tab
    and "lp.leftMargin = dp(10)" in phone)
add("弹窗统一玻璃材质", "void glassDialog" in tab and tab.count("glassDialog(") >= 4)

# 14) 【v4.23.0】控件一眼看懂
add("有图标+文字的胶囊按钮", "TextView pill(" in tab and "setCompoundDrawables" in tab)
add("播放键独立成 28dp 玻璃圆钮", 'GlassChip.icon(this, "pause"' in tab)
add("画质按钮带功能名", '"画质 " + qualityShort' in tab)
add("倍速按钮带功能名", '"倍速 " + rateLabel' in tab)
add("下一集带文字（不再是裸箭头）", '"下一集"' in tab and '"下一集"' in phone)
add("选集/下一集只在多P显示", "void syncPagesButtons" in tab and "pages > 1" in tab
    and "void syncPagesButtons" in phone and "pages > 1" in phone)
add("进度条压薄到 2dp/10dp 圆点（iOS 版 3dp/12dp 圆点）",
    "styleSeek(seek, 2, 10)" in tab
    or "styleSeek(seek, ios ? 3 : 2" in tab
    or "styleSeek(seek, iosUi ? 3 : 2" in tab)
add("时间用「已播 / 总长」格式", 'mmss(pos) + " / " + mmss(len)' in tab)
add("常显的「解码」按钮已移除", "codecBtn" not in tab)

# 15) 【v4.23.0】启动与带宽
crash = read(ROOT + "CrashHandler.java")
add("日志写盘移出主线程（队列+批量）", "LinkedBlockingQueue" in crash and "flushPending" in crash)
add("崩溃前先冲刷日志队列", "flushPending();" in crash)
add("磁盘缓存容量统计在后台线程", "bxq-cache-scan" in cache
    and "NetPrio.BACKGROUND" in cache)
add("预取并发 3", "Semaphore(3)" in read(ROOT + "BiliApi.java"))
add("代理 socket 发送缓冲 ≥512KB（v4.30 提到 1MB）",
    "setSendBufferSize(1024 * 1024)" in lproxy)


add("切模式只调玻璃浓度，不盖实色背景", "ctrlBar.setBackgroundColor" not in tab)
add("手机版同样不盖实色背景", "ctrlBar.setBackgroundColor" not in phone)
add("玻璃着色器按尺寸缓存", "shFill" in glass and "shW = 0" in glass)
add("显隐时触发扫光", "onVisibilityChanged" in glass and "playSheen" in glass)

print("=== 源码回归守卫 v4.25.0 ===")

# ---------------------------------------------------------------- v4.24.0
chip = read(ROOT + "GlassChip.java")
chip_p = read(PHONE + "GlassChip.java")

# ---------------------------------------------------------------- v4.25.0
# 【核心】① 播放栏去塑料感 ② 全屏键不再被挤出屏幕

# 1) 去塑料感：身体的「均匀白」必须压到极淡，存在感交给边缘光学
add("玻璃片主体雾降到极淡", "0x0AFFFFFF, 0x04FFFFFF" in chip)
add("旧版厚白参数已彻底移除",
    "0x1EFFFFFF, 0x12FFFFFF" not in glass and "0x28FFFFFF, 0x14FFFFFF" not in chip
    and "0x8EFFFFFF" not in glass)
add("两版玻璃材质完全一致",
    glass == read(PHONE + "GlassPanel.java") and chip == chip_p)

# 2) 控制条排版：全屏键永远在屏幕里
add("两版都有折叠算法 RowFit（纯逻辑，可单测）",
    "public final class RowFit" in read(ROOT + "RowFit.java")
    and "public final class RowFit" in read(PHONE + "RowFit.java"))
add("两版都有自适应行 FoldRow", "public class FoldRow extends LinearLayout" in read(ROOT + "FoldRow.java")
    and "public class FoldRow extends LinearLayout" in read(PHONE + "FoldRow.java"))
add("两版控制条都改用 FoldRow",
    "FoldRow rowTools = new FoldRow(this)" in tab
    and "FoldRow rowTools = new FoldRow(this)" in phone)
add("「更多 / 全屏」刻意不注册为可折叠（固定区，永不折叠）",
    "rowTools.foldable(moreBtn)" not in tab and "rowTools.foldable(moreBtn)" not in phone
    and "rowTools.foldable(fullIcon)" not in tab and "rowTools.foldable(fullIcon)" not in phone)
add("折叠优先级按「发弹幕 → 下一集 → 画质 → 倍速 → 选集」注册",
    "rowTools.foldable(dmHint);" in tab and "rowTools.foldable(nextBtn);" in tab
    and "rowTools.foldable(qualityBtn);" in tab and "rowTools.foldable(speedBtn);" in tab
    and "rowTools.foldable(pickBtn);" in tab
    and "rowTools.foldable(dmHint);" in phone and "rowTools.foldable(nextBtn);" in phone
    and "rowTools.foldable(qualityBtn);" in phone and "rowTools.foldable(speedBtn);" in phone
    and "rowTools.foldable(pickBtn);" in phone)
add("旧的 dp 阈值折叠法已删除（615/557/506/448）",
    "availDp >= 615" not in tab and "availDp >= 615" not in phone
    and "availDp >= 448" not in tab and "availDp >= 448" not in phone)
add("折叠改为量真实宽度", "foldTo(int availPx)" in read(ROOT + "FoldRow.java")
    and "naturalWidth" in read(ROOT + "FoldRow.java"))
add("下一集的业务可见性交回 FoldRow（不再直接 setVisibility）",
    "ctrlRow.setBusinessVisible(nextBtn" in tab and "ctrlRow.setBusinessVisible(nextBtn" in phone)
add("被折叠的功能在「更多」菜单里仍有入口",
    '"发弹幕", "倍速"' in tab and '"发弹幕", "倍速"' in phone)
add("FoldRow 单测存在", "RowFit.foldMask" in read("tools/RowFitTest.java"))

add("平板有薄玻璃片材质类", "public class GlassChip extends Drawable" in chip)
add("手机版有薄玻璃片材质类", "public class GlassChip extends Drawable" in chip_p)

# 1) 塑料感的三个根源必须被拆掉
add("胶囊底不再是 60% 不透明深灰实色", "0x992A2F3A" not in tab and "0x992A2F3A" not in phone)
add("胶囊底改用薄玻璃片", "GlassChip.pill(this" in tab and "GlassChip.pill(this" in phone)
add("玻璃片主体透明（只留光学，不留实色）",
    "0x0AFFFFFF, 0x04FFFFFF" in chip and "public void setColorFilter" in chip)
add("玻璃片支持按下提亮态", "state_pressed" in chip and "pressed" in chip)

# 2) 玻璃面板本体
add("玻璃面板基底不再是高浓度实色", "0xBE" not in tab and "0xB4" not in tab)

# 3) 横屏控制条高度：132dp → 58dp
add("横屏控制条改为单行 + 独立进度线",
    "ctrlWrap = new LinearLayout(this)" in tab and "ctrlWrap.addView(seek, sk)" in tab)
add("手机版横屏控制条同样重构",
    "ctrlWrap = new LinearLayout(this)" in phone and "ctrlWrap.addView(seek, sk)" in phone)
add("控制条玻璃板高度收到 36dp（按钮 28dp + 上下 4dp）",
    "dp(28), dp(28)" in tab and "dp(11), dp(4), dp(11), dp(4)" in tab)
add("控制条圆角 18dp（原版）/ 22dp（iOS 胶囊）",
    (".radius(18f)" in tab or "iosUi ? 22f : 18f" in tab or "ios ? 22f : 18f" in tab)
    and ("iosUi ? 22f : 18f" in phone or ".radius(18f)" in phone))
add("进度线独立满宽且带 2dp 轨道（iOS 3dp）",
    "sk.leftMargin = dp(12)" in tab
    and ("styleSeek(seek, 2, 10)" in tab or "styleSeek(seek, ios ? 3 : 2" in tab))
add("进度线容器高度压到 16dp", "dp(16), 1f" in tab or "ViewGroup.LayoutParams.MATCH_PARENT, dp(16)" in tab)

# 4) 窄屏自适应与显隐统一入口
add("窄屏自适应入口存在（折叠交给 FoldRow）", "void fitCtrlRow(int availPx)" in tab
    and "void fitCtrlRow(int availPx)" in phone)
add("控制条显隐统一走 setCtrlVisible",
    "void setCtrlVisible(int vis)" in tab and "void setCtrlVisible(int vis)" in phone)
add("切模式搬的是整个容器（玻璃板 + 进度线）",
    "View box = ctrlWrap != null ? ctrlWrap : ctrlBar;" in tab
    and "View box = ctrlWrap != null ? ctrlWrap : ctrlBar;" in phone)
add("玻璃上的文字自带投影（半透明底盘上才读得清）",
    "void glassText(TextView t)" in tab and "setShadowLayer(dp(2)" in tab)

print("=== 源码回归守卫 v4.24.0 ===")

# ---------------------------------------------------------------- v4.26.0
# 【范围】① 手机版全屏角标只调宽度（高度一行不动）② 平板版液态玻璃再优化
# 【约束】平板版全屏图标必须保持 v4.25.0 原状（用户明确：平板版本没任何问题，不需要修改）

ic_full_phone = read("app_phone/res/drawable/ic_full.xml")
ic_exit_phone = read("app_phone/res/drawable/ic_exitfull.xml")
ic_full_tab = read("app/res/drawable/ic_full.xml")
ic_exit_tab = read("app/res/drawable/ic_exitfull.xml")

# 1) 手机版全屏角标：横向收窄 + 中心归位，纵向 4..20 一行未动
add("手机版 ic_full 横向收窄到 4.8..19.2（只调宽度）",
    "M4.8 8.4V4h4.4v1.6H6.4v2.8z" in ic_full_phone
    and "M19.2 15.6V20h-4.4v-1.6h2.8v-2.8z" in ic_full_phone)
add("手机版 ic_full 纵向仍为 4..20（高度方向一行未动）",
    "V4h4.4" in ic_full_phone and "V20h4.4" in ic_full_phone
    and "M4.8 8.4" in ic_full_phone and "M4.8 15.6" in ic_full_phone)
add("手机版 ic_full 笔画由 2 收到 1.6（不再是又方又胀的粗角标）",
    "v1.6" in ic_full_phone and "h4.4" in ic_full_phone)
add("手机版旧版 pathData 已彻底移除",
    "M4 9V4h5v2H6v3z" not in ic_full_phone)
add("手机版 ic_exitfull 与 ic_full 严格对偶（足迹一致）",
    "M8 4h1.6v4.4H4.8V6.8H8z" in ic_exit_phone
    and "M16 20h-1.6v-4.4h4.8v1.6H16z" in ic_exit_phone)
add("手机版 ic_exitfull 旧版 pathData 已彻底移除",
    "M9 4v5H4V7h3V4z" not in ic_exit_phone)

# 2) 平板版全屏图标：必须与 v4.25.0 逐字节一致（用户要求平板版不修改）
add("平板版 ic_full 保持 v4.25.0 原状（用户要求平板版不修改）",
    "M4 9V4h5v2H6v3zM20 9V6h-3V4h5v5zM4 15v5h5v-2H6v-3zM20 15v3h-3v2h5v-5z" in ic_full_tab
    and "M4.8 8.4V4h4.4" not in ic_full_tab)
add("平板版 ic_exitfull 保持 v4.25.0 原状（用户要求平板版不修改）",
    "M9 4v5H4V7h3V4zM15 4h2v3h3v2h-5zM9 20H7v-3H4v-2h5zM15 20v-5h5v2h-3v3z" in ic_exit_tab
    and "M8 4h1.6v4.4" not in ic_exit_tab)

# 3) 平板版液态玻璃再优化：边缘「三明治厚度」
add("两版玻璃材质完全一致（v4.26.0 后仍然同步）",
    glass == read(PHONE + "GlassPanel.java") and chip == chip_p)
add("图标几何校验器存在且校验两态对偶",
    "def check_more" in read("tools/IconGeomTest.py")
    and "两态对偶" in read("tools/IconGeomTest.py"))

# ---------------------------------------------------------------- v4.27.0
# 【范围】① 删除控制条上下贯穿边线（端面光学）② 边框收窄 ③ 去塑料感
#        ④ 优化视频加载速度（两版都改）
# 【约束】手机版 PlayActivity 是「真实裁剪分屏代码」，与平板版本就不同，
#         绝不能被平板版覆盖 —— 必须始终保留两版的差异化实现。

tab_play = read("app/src/com/bixiaoqu/app/PlayActivity.java")
ph_play = read("app_phone/src/com/bixiaoqu/app/PlayActivity.java")
tab_api = read("app/src/com/bixiaoqu/app/BiliApi.java")
ph_api = read("app_phone/src/com/bixiaoqu/app/BiliApi.java")
tab_proxy = read("app/src/com/bixiaoqu/app/LocalProxy.java")
ph_proxy = read("app_phone/src/com/bixiaoqu/app/LocalProxy.java")

# 1) 端面光学：上下贯穿边线必须消失
#    判据：边缘高光 / 厚度暗线 / 内反射 / 色散 的渐变方向一律是「水平」，
#    且中段 alpha 归零（POS 数组中 0.5f 处必须是 0x00FFFFFF / 0x00000000）。
add("玻璃面板基底压暗改为水平分布（两端实、中段透）",
    "shFill = new LinearGradient(0, 0, w, 0," in glass)

# 2) 边框收窄：v4.26.0 → v4.27.0 的宽度必须严格变小

# 3) 进一步去塑料感

# 4) 加载速度优化
add("修复预热缓存 key 不匹配（改用 QN_ALL）",
    "urlCacheKey(it.bvid, it.cid, BiliApi.QN_ALL)" in tab_play
    and "urlCacheKey(it.bvid, it.cid, qn)" not in tab_play)
add("首片预热按用户画质同轨（pickVideo 而非固定最高码率）",
    "warmFirstChunkOf(final JSONObject playData, final int targetH)" in tab_api
    and "pickVideo(playData, targetH)" in tab_api)
add("新增音频轨首片预热（DASH 音视频分离）",
    "warmAudioChunkOf" in tab_api and "warmAudioChunkOf(d)" in tab_api)
add("预取链路传入 targetH 保证预热与正式播放同轨",
    "BiliApi.QN_ALL, done < 2, targetH" in tab_play)
add("滑动相邻条目拉首片（连滑起播只剩解码）",
    "BiliApi.QN_ALL, warm, targetH" in tab_play)
add("代理竞速错峰收紧（16ms → 8ms）",
    "Thread.sleep(trialIndex * 8L)" in tab_proxy
    and "Thread.sleep(trialIndex * 16L)" not in tab_proxy)
add("预热连接入池可复用（不再 disconnect 掐掉 TLS 成果）",
    "keepAlive" in tab_proxy and "if (!keepAlive)" in tab_proxy)
add("预热异常记入 CrashHandler（便于后续定位）",
    "CrashHandler.note(\"warmup\"" in tab_play)

# 5) 两版一致性 + 手机版差异化代码必须保留
add("两版 BiliApi 完全一致",
    tab_api == ph_api)
add("两版 LocalProxy 完全一致",
    tab_proxy == ph_proxy)
add("两版玻璃材质完全一致（v4.27.0 后仍然同步）",
    glass == read(PHONE + "GlassPanel.java") and chip == chip_p)
add("手机版 PlayActivity 保留自身差异化实现（未被平板版覆盖）",
    ph_play != tab_play and len(ph_play) > 200000)
add("手机版 PlayActivity 保留裁剪分屏代码",
    "swipe" in ph_play and ph_play.count("void ") > 100)

# ---------------------------------------------------------------- v4.28.0
# 【范围】① 删掉多余的光影与结构线（每个控件只留一道描边）
#        ② 进一步优化内存占用  ③ 提升视频加载速度
# 【判据】用户截图：控制条 + 每个胶囊 + 每个圆钮都被一整圈白线框住。
#         根源是每一层光学都用一道 STROKE 实现，叠成了七个框。

# 1) 多余的光影与结构线必须全部消失
add("玻璃面板删掉全部装饰光（端面高光/热点/厚度暗线/内表面反射/顶棱镜面/焦散/色散/底缘反光/顶部受光/左上径向）",
    all(k not in glass for k in ("shHaze", "shRim", "shRimHot", "shInner",
                                 "shInner2", "shTopSpec", "shCaustic",
                                 "shDisp", "shFootL", "shBottom",
                                 "shRadial", "shSheen")))
add("玻璃面板只剩 2 个着色器（压暗 + 唯一一道描边）",
    "Shader shFill, shEdge;" in glass)
add("玻璃面板删除两张噪点 Bitmap（省内存 + 去掉颗粒）",
    "noisePaint" not in glass and "makeNoise" not in glass
    and "Bitmap noise" not in glass)
add("玻璃面板只画一道描边（不再是七道内缩的框）",
    glass.count("cv.drawRoundRect(innerR") == 1)
add("玻璃面板描边亮度压到 18%→9%（不再是醒目的白色结构线）",
    "0x2EFFFFFF, 0x18FFFFFF" in glass)
add("玻璃面板描边收到 0.55dp（细如界面折光）",
    "den * 0.55f" in glass)
add("玻璃面板阴影降到两层且更淡",
    glass.count("shadow.setColor(") == 1
    and "0x14000000" in glass and "0x08000000" in glass)
add("玻璃面板基底压暗仍为水平（两端实、中段透，长边融进画面）",
    "shFill = new LinearGradient(0, 0, w, 0," in glass)

add("玻璃圆钮删掉全部装饰线（热点/接触暗线/内表面反射/色散/顶棱/底缘反光）",
    all(k not in chip for k in ("pHot", "pInner", "pInner2", "pDisp",
                                "pSpec", "shHot", "shInner", "shInner2",
                                "shDisp", "shSpec", "shFoot")))
add("玻璃圆钮只剩 2 个着色器（雾 + 唯一一道折射线）",
    "Shader shFog, shEdge;" in chip)
add("玻璃圆钮只画一道描边", chip.count("cv.drawRoundRect(inset") == 1)
add("玻璃圆钮折射线左上 22% → 右下 8%（一条线表达方向感）",
    "0x38FFFFFF, 0x14FFFFFF" in chip)
add("玻璃圆钮描边收到 0.6dp", "den * 0.6f" in chip)
add("两版玻璃材质完全一致（v4.28.0 后仍然同步）",
    glass == read(PHONE + "GlassPanel.java") and chip == chip_p)
add("图标几何校验器存在且校验两态对偶",
    "def check_more" in read("tools/IconGeomTest.py")
    and "两态对偶" in read("tools/IconGeomTest.py"))

# 2) 内存优化：按堆自适应，低端机不再顶到 GC
add("封面内存缓存改为按堆上限 1/5 自适应（48MB 封顶、16MB 保底）",
    "memCapBytes()" in read(ROOT + "ImgLoader.java")
    and "long cap = heap / 5;" in read(ROOT + "ImgLoader.java")
    and "48L * 1024 * 1024" in read(ROOT + "ImgLoader.java"))
add("玻璃类 Shader 数量从 21 个降到 4 个（每实例）",
    "Shader shFill, shEdge;" in glass and "Shader shFog, shEdge;" in chip)

# 3) 加载提速（v4.28.0 新增）
add("地址缓存命中时补做首片预热（修「缓存命中即 return 导致永不预热」）",
    "URL_WARMED" in tab_api and "markWarmIfNew" in tab_api
    and "if (warm && markWarmIfNew(key))" in tab_api)
add("预热去重集合有容量上限（不会无限增长占内存）",
    "URL_WARMED.size() > 512" in tab_api)
add("首片预热 96KB → 192KB（覆盖首个 GOP）",
    "bytes=0-196607" in tab_api and "192 * 1024" in tab_api)
add("预热读取缓冲 16KB → 32KB（少一半 read 系统调用）",
    "new byte[32 * 1024]" in tab_api)
add("两版 BiliApi 与 ImgLoader 完全一致",
    tab_api == ph_api
    and read(ROOT + "ImgLoader.java") == read(PHONE + "ImgLoader.java"))

# 30) 【v4.29.0】删掉「简介 / 评论」冗余分段；控制条压到画面下沿做成真玻璃
for nm, src in (("平板", tab), ("手机", phone)):
    add("%s：简介/评论分段已删（不是页签，纯冗余）" % nm,
        "tabComment" not in src and "tabIntro" not in src and "tabRow" not in src)
add("两版都不再构造简介/评论分段", "简介 / 评论 分段" not in tab and "简介 / 评论 分段" not in phone)
add("两版控制条都压到画面下沿（不再自占一行黑底）",
    "root.addView(ctrlWrap, flowCtrlLp());" in tab
    and "root.addView(ctrlWrap, flowCtrlLp());" in phone)
add("上移量 = 自身高度（对页面流贡献为 0，显隐不跳动）",
    "lp.topMargin = -ctrlBarHeight();" in tab
    and "lp.topMargin = -ctrlBarHeight();" in phone)
add("控制条高度按 dp 逐项累加（不写死 57dp，density 变了也对得上）",
    "return dp(4) * 2 + dp(28) + dp(1) + dp(16) + dp(4);" in tab
    and "return dp(4) * 2 + dp(28) + dp(1) + dp(16) + dp(4);" in phone)
add("归位时同步降浓度（从全屏/分屏退回来不会带回 27% 深色板）",
    "ctrlTint(ctrlTintValue());" in tab and "ctrlTint(ctrlTintValue());" in phone)
add("不再有 40% 的「压住页面底色」浓度残留",
    "ctrlTint(0x66)" not in tab and "ctrlTint(0x66)" not in phone)
add("两版 flowCtrlLp / ctrlBarHeight 完全一致",
    "LinearLayout.LayoutParams flowCtrlLp()" in tab
    and "LinearLayout.LayoutParams flowCtrlLp()" in phone
    and "int ctrlBarHeight()" in tab and "int ctrlBarHeight()" in phone)


# 31) 【v4.30.0】并行拉流 / 端口接收效率 / 网络优先级
add("两版 LocalProxy 完全一致", proxy_t == proxy_p)
add("两版 ParallelPlan 完全一致", plan_t == plan_p)
add("并行分段有纯计算类（可单测）", "final class ParallelPlan" in plan_t)
add("分段覆盖到 len-1（不会多要一个字节触发 416）",
    "start + len - 1L" in plan_t and "len <= 0" in plan_t)
add("Content-Range 起点校验在纯类里（可在 JVM 单测）",
    "static boolean rangeStartsAt" in plan_t and "ParallelPlan.rangeStartsAt" in proxy_t)
add("只有上游确认真 206 才并行", 'okCode == 206 && !req.suffix' in proxy_t)
add("并行有闸门（同时最多 6 条帮工连接）",
    "PAR_GATE =" in proxy_t and "tryAcquire(n)" in proxy_t)
add("段队列有界（内存不会被跑得快的段撑爆）",
    "PAR_QUEUE = 4" in proxy_t and "new java.util.concurrent.ArrayBlockingQueue<byte[]>(PAR_QUEUE)" in proxy_t)
add("主线程严格按段序写出（不会乱序）",
    "// 主线程只做一件事：按段序把数据写给播放器" in proxy_t)
add("段失败只回退到断点续传（不写半截乱序数据）",
    "if (err[i] != null) break;" in proxy_t and "bytes = pre;" in proxy_t)
add("落盘改异步单线程（不挡转发）",
    "static final class DiskSink" in proxy_t and "DISK_POOL" in proxy_t)
add("落盘线程有 60s 自愈（写者死了不会永久占线程）",
    "q.poll(60L, TimeUnit.SECONDS)" in proxy_t)
add("backlog → 256（换片瞬间不丢 SYN）", "new ServerSocket(0, 256," in proxy_t)
add("回环发送缓冲 512KB → 1MB", "s.setSendBufferSize(1024 * 1024)" in proxy_t)
add("读缓冲按线程复用（不再每请求 new 256KB）",
    "bufPool()" in proxy_t and "byte[] buf = new byte[256 * 1024]" not in proxy_t)
add("转发线程不被提到 MAX（避免抢 UI）",
    "Thread.MAX_PRIORITY" not in proxy_t)
add("http 连接池 → 16（并行段不会被池子掐连接）",
    '"http.maxConnections", "16"' in tab_api)


# 32) 【v4.31.0】封面三模块提速 / 连接复用 / 竖屏提速 / 冗余清理 / iOS 皮肤
img_t = read(ROOT + "ImgLoader.java")
img_p = read(PHONE + "ImgLoader.java")
api_t = read(ROOT + "BiliApi.java")
api_p = read(PHONE + "BiliApi.java")
main_t = read(ROOT + "MainActivity.java")
main_p = read(PHONE + "MainActivity.java")
sear_t = read(ROOT + "SearchActivity.java")
sear_p = read(PHONE + "SearchActivity.java")
dyn_t = read(ROOT + "DynFeedView.java")
dyn_p = read(PHONE + "DynFeedView.java")
skin_t = read(ROOT + "UiSkin.java")
skin_p = read(PHONE + "UiSkin.java")
glass_t = read(ROOT + "GlassUtil.java")

add("两版 ImgLoader 完全一致", img_t == img_p)
add("两版 UiSkin 完全一致", skin_t == skin_p)
add("封面下载成功后不断开连接（keep-alive 复用）",
    "if (!ok && c != null)" in img_t and "keep-alive" in img_t)
add("封面不再每张图 disconnect（旧写法已消失）",
    "if (c.getResponseCode() != 200) { c.disconnect(); return null; }" not in img_t)
add("封面解码走内存字节数组（不经磁盘中转）",
    "decodeBytes(data, w, h)" in img_t and "BitmapFactory.decodeByteArray" in img_t)
add("封面落盘异步（单线程低优先级）",
    "static void saveAsync" in img_t and "bxq-img-disk" in img_t)
add("封面磁盘缓存有上限 + LRU 清理",
    "DISK_CAP" in img_t and "sweepDisk()" in img_t)
add("封面下载有体积上限（异常大图不撑爆堆）",
    "MAX_BYTES = 12 * 1024 * 1024" in img_t)
add("封面连接池放大到 16", '"http.maxConnections", "16"' in img_t)
add("首页预取 14 张（≈1.5 屏）", "prefetchCovers(first + visible, 14)" in main_t)
add("搜索页滚动预取", "prefetchRows(first - list.getHeaderViewsCount() + visible, 6)" in sear_t
    and "prefetchRows(first - list.getHeaderViewsCount() + visible, 6)" in sear_p)
add("动态流首屏分批建卡（每帧 6 条）",
    "void renderRest(final ArrayList<Dyn> vis" in dyn_t
    and "void renderRest(final ArrayList<Dyn> vis" in dyn_p)
add("动态流预取后续封面",
    "void prefetchCovers(ArrayList<Dyn> vis" in dyn_t
    and "void prefetchCovers(ArrayList<Dyn> vis" in dyn_p)

add("API 请求复用连接（成功不 disconnect）",
    "byte[] raw = bos.toByteArray();" in api_t and "Connection\", \"keep-alive" in api_t)
add("首片预热读满后保持连接（warmOk）", "warmOk = (left == 0)" in api_t)
add("预热使用与正式播放相同的 TLS 工厂", "tlsFactory()" in api_t)
add("代理读干净的连接退回连接池", "if (!keep[0])" in lproxy or "if (!keep[0])" in proxy_t)
add("代理连线显式 keep-alive", '"Connection", "keep-alive"' in proxy_t)
add("连滑补暖接口（地址已缓存才补）", "public static void warmIfCached" in api_t)
add("竖屏相邻两条并行预热",
    "void startWarmThread(final VideoItem it, final long delay)" in tab
    and "void startWarmThread(final VideoItem it, final long delay)" in phone)
add("竖屏弹幕让路首帧", 'guardStartup("dm"' in tab and 'guardStartup("dm"' in phone)

add("不可达的音频工具页已删（301 行死代码）",
    not os.path.exists(ROOT + "AudioToolActivity.java")
    and "AudioToolActivity" not in read("app/AndroidManifest.xml"))
add("AsrUtil 保留（录音页仍在用）", os.path.exists(ROOT + "AsrUtil.java"))
add("弹幕样式 getter 死代码已删", "public int styleColor()" not in read(ROOT + "DanmakuView.java"))
add("FoldRow.isFolded 死代码已删", "public boolean isFolded" not in read(ROOT + "FoldRow.java"))
add("LiquidNavBar 兼容空壳已删", "public void refreshBackdrop" not in read(ROOT + "LiquidNavBar.java"))
add("PlayActivity 解码清单对话框死代码已删", "void showCodecDialog" not in tab)

add("manifest 带皮肤开关", 'android:name="bxq.ios_ui"' in read("app/AndroidManifest.xml")
    and 'android:name="bxq.ios_ui"' in read("app_phone/AndroidManifest.xml"))
add("build.sh 支持 base/ios 两种皮肤",
    'SKIN="${1:-base}"' in open("app/build.sh").read()
    and 'SKIN="${1:-base}"' in open("app_phone/build.sh").read())
add("iOS 皮肤有独立色板", "public static int acc(boolean dark)" in skin_t
    and "0xFFFF375F" in skin_t and "0xFFFF2D55" in skin_t)
add("iOS 卡片是实心分组卡（无渐变）", "if (UiSkin.ios(c)) return UiSkin.group(c, dark);" in glass_t)
add("iOS 搜索框是填充样式", "UiSkin.fill(this, UiSkin.fill(DARK), 10)" in main_t
    and "UiSkin.fill(this, UiSkin.fill(DARK), 10)" in main_p)
add("iOS 底栏选中不套胶囊", "on && !UiSkin.ios(this) ? navPillBg() : null" in main_t
    and "on && !UiSkin.ios(this) ? navPillBg() : null" in main_p)
add("iOS 频道栏是分段控件样式", "channelViews[i].setBackground(on" in main_t
    and "channelViews[i].setBackground(on" in main_p)



# ===== v4.32.0：弹窗材质加深 + 后台播放 =====
svc_t = read(ROOT + "BgPlayService.java")
svc_p = read(PHONE + "BgPlayService.java")
pref_t = read(ROOT + "Prefs.java")
pref_p = read(PHONE + "Prefs.java")
man_t = read("app/AndroidManifest.xml")
man_p = read("app_phone/AndroidManifest.xml")

add("弹窗换成更实的玻璃（sheet，两版一致）",
    "GlassUtil.sheet(true, 20, this)" in tab and "GlassUtil.sheet(true, 20, this)" in phone)
add("弹窗遮罩 0.55 → 0.6", "setDimAmount(0.6f)" in tab and "setDimAmount(0.6f)" in phone)
add("列表 / 设置页卡片仍用原档 card()（未被误改）",
    "if (UiSkin.ios(c)) return UiSkin.group(c, dark);" in glass_t
    and "0x3D2B303B" in glass_t)
add("后台播放开关落到 Prefs（两版一致）",
    "public static boolean bgPlay(Context c)" in pref_t
    and "public static boolean bgPlay(Context c)" in pref_p)
add("「更多」菜单有后台播放项",
    '\"后台播放：\" + (Prefs.bgPlay(this) ? \"开\" : \"关\")' in tab
    and '\"后台播放：\" + (Prefs.bgPlay(this) ? \"开\" : \"关\")' in phone)
add("两版都有 BgPlayService",
    os.path.exists(ROOT + "BgPlayService.java")
    and os.path.exists(PHONE + "BgPlayService.java"))
add("前台服务声明为 mediaPlayback",
    'foregroundServiceType="mediaPlayback"' in man_t
    and 'foregroundServiceType="mediaPlayback"' in man_p)
add("前台服务权限已声明",
    "FOREGROUND_SERVICE_MEDIA_PLAYBACK" in man_t
    and "FOREGROUND_SERVICE_MEDIA_PLAYBACK" in man_p)
add("onPause 时启动保活（赶在前台宽限期内）",
    "BgPlayService.start(this, videoTitle, curBv)" in tab
    and "BgPlayService.start(this, videoTitle, curBv)" in phone)
add("开关关：离开应用自动暂停",
    "if (pauseIfPlaying()) bgAutoPaused = true;" in tab
    and "if (pauseIfPlaying()) bgAutoPaused = true;" in phone)
add("回到前台自动续播（含位置回位）",
    "final boolean wantPlay = bgAutoPaused || bgWasPlaying;" in tab
    and "final boolean wantPlay = bgAutoPaused || bgWasPlaying;" in phone)
add("App 内换页不打断（按进程 importance 判定）",
    "IMPORTANCE_FOREGROUND" in tab and "IMPORTANCE_FOREGROUND" in phone)
add("通知栏「停止」回落到播放页",
    "BgPlayService.cmdSink = new BgPlayService.BgCmd()" in tab
    and "BgPlayService.cmdSink = new BgPlayService.BgCmd()" in phone)
add("服务不长期持有 Activity（销毁即解绑）",
    "BgPlayService.cmdSink = null;" in tab and "BgPlayService.cmdSink = null;" in phone)
add("通知渠道走反射（API22 可编译）",
    "NotificationChannel" in svc_t and "getConstructor(Context.class, String.class)"
    in svc_t and svc_t == svc_p)


# ===== v4.33.0：全局主题色 / 开屏重做 / 换片提速 / 链路预热 =====
theme_t = read(ROOT + "Theme.java")
theme_p = read(PHONE + "Theme.java")
accent_t = read(ROOT + "AccentActivity.java")
accent_p = read(PHONE + "AccentActivity.java")
set_t = read(ROOT + "SettingsActivity.java")
set_p = read(PHONE + "SettingsActivity.java")
app_t = read(ROOT + "BxqApp.java")
app_p = read(PHONE + "BxqApp.java")
lproxy_p = read(PHONE + "LocalProxy.java")
api_p = read(PHONE + "BiliApi.java")

add("主题色单点（Theme 两版一致）",
    "public static int c(int argb)" in theme_t and theme_t == theme_p)
add("8 个推荐色：红橙黄绿青蓝紫粉",
    "红" in theme_t and "红\", \"橙\", \"黄\", \"绿\", \"青\", \"蓝\", \"紫\", \"粉" in theme_t)
add("主题色写进 Prefs（两版一致）",
    "public static int accent(Context c)" in pref_t and "public static int accent(Context c)" in pref_p)
add("设置页有「主题颜色」入口（iOS 版隐藏）",
    "if (!UiSkin.ios(this)) {" in set_t and "addAccentRow(look)" in set_t
    and "addAccentRow(look)" in set_p)
add("自定义调色：色相条 + 饱和度 + 明度",
    "class HueBar extends View" in accent_t and "setOnHue" in accent_t
    and "饱和度" in accent_t and "明度" in accent_t)
add("主题色变化会触发各页重建（指纹含色值）",
    "(Prefs.accent(c) & 0xFFFFFF)" in main_t and "(Prefs.accent(c) & 0xFFFFFF)" in main_p)
add("深色判定与指纹解耦",
    "static boolean isDarkTheme" in main_t and "static boolean isDarkTheme" in main_p)
add("非 iOS 版读用户主题色",
    "C_ACC = Theme.acc();" in main_t and "C_ACC = Theme.acc();" in main_p)
add("播放器强调色跟随主题（不再写死粉）",
    tab.count("Theme.c(0x") >= 12 and phone.count("Theme.c(0x") >= 12
    and 'Color.parseColor("#FB7299")' not in tab
    and 'setTextColor(0xFFFB7299)' not in tab)

add("开屏正中是应用图标", "ic_launcher" in main_t and "ic_launcher" in main_p)
add("开屏有主题色柔光", "RadialGradient" in main_t and "RadialGradient" in main_p)
add("开屏总时长 ≤ 3 秒（收尾 2460ms + 230ms）",
    "}, 2460);" in main_t and "}, 2460);" in main_p)
add("开屏分层错峰（图标→名称→作者→标语）",
    "setStartDelay(170)" in main_t and "setStartDelay(440)" in main_t
    and "setStartDelay(170)" in main_p)

add("起播票据（连点旧任务自退）",
    "AtomicInteger playTicket" in tab and "AtomicInteger playTicket" in phone
    and "if (playTicket.get() != ticket) return;" in tab
    and "if (playTicket.get() != ticket) return;" in phone)
add("起播线程池 3 → 5",
    "newFixedThreadPool(5," in tab and "newFixedThreadPool(5," in phone)
add("右栏推荐让路起播（480ms）",
    "}, 480);" in tab and "}, 480);" in phone)
add("右栏可见区预热（含后两条）",
    "void prefetchRecRange(final int first" in tab
    and "void prefetchRecRange(final int first" in phone)
add("换片海报淡入 / 淡出推进",
    "flipCover.animate().alpha(0f).scaleX(1.035f)" in tab
    and "flipCover.animate().alpha(0f).scaleX(1.035f)" in phone)

add("启动预热 api 长连接", "BiliApi.warmConnection(\"api.bilibili.com\")" in app_t
    and "BiliApi.warmConnection(\"api.bilibili.com\")" in app_p)
add("预热只读一小段且不断开（连接回池）",
    "byte[] b = new byte[256];" in api_t and "if (!ok && c != null)" in api_t)
add("代理 IO 线程按调度档位可调（默认仍是 8/16）",
    "static volatile int ioLimit = 16;" in proxy_t
    and "static volatile int ioLimit = 16;" in lproxy_p
    and "new java.util.concurrent.ThreadPoolExecutor(ioCore, ioMax," in proxy_t
    and "new java.util.concurrent.ThreadPoolExecutor(ioCore, ioMax," in lproxy_p
    and "Math.max(4, ioMax / 2)" in proxy_t)
add("代理接收缓冲 1MB", "setReceiveBufferSize(1024 * 1024)" in proxy_t)


# ===== v4.34.0：动态页闪退 / 音画同步 / 切后台续播 / 线程调度 / 线路亲和 =====
dyn_t = read(ROOT + "DynFeedView.java")
dyn_p = read(PHONE + "DynFeedView.java")
avs_t = read(ROOT + "AvSync.java")
avs_p = read(PHONE + "AvSync.java")
dt_t = read(ROOT + "DeviceTier.java")
dt_p = read(PHONE + "DeviceTier.java")
np_t = read(ROOT + "NetPrio.java")
np_p = read(PHONE + "NetPrio.java")
img_t = read(ROOT + "ImgLoader.java")
img_p = read(PHONE + "ImgLoader.java")
acc_t = read(ROOT + "AccentActivity.java")
game_t = read(ROOT + "GameActivity.java")

# --- 闪��修复 ---
# 注意：旧代码那行还留在注释里（作为「这里曾经出过事」的记录），
# 所以断言只看真正的代码行（行首不是 * 或 //）。
_dyn_code = "\n".join(l for l in dyn_t.split("\n")
                       if "(LinearLayout) cell.getChildAt(0)" in l
                       and not l.strip().startswith("*"))
add("动态页左栏头像不再把 FrameLayout 强转 LinearLayout",
    _dyn_code == "" and "(ViewGroup) box" in dyn_t)
add("动态页高亮改成 ViewGroup 安全取子视图",
    "ViewGroup cell = (ViewGroup) c;" in dyn_t
    and "ViewGroup cell = (ViewGroup) c;" in dyn_p)
add("动态页筛选有异常兜底（点头像不会闪退）",
    'note("filter", e);' in dyn_t and 'note("filter", e);' in dyn_p)
add("动态页再点同一头像会清掉标题里的旧名字",
    'filterName = "";' in dyn_t and 'filterName = "";' in dyn_p)
add("动态页两版一致", dyn_t == dyn_p)

# --- 音画同步 ---
add("音画同步策略类存在（两版一致）",
    "public static int classify(int audioPos, int videoPos)" in avs_t
    and avs_t == avs_p)
add("音画校正频率 2500ms → 700ms",
    "if (now - lastAudioSync < 700) return;" in tab
    and "if (now - lastAudioSync < 700) return;" in phone)
add("小漂移用变速渐进拉回（不再一上来就 seek）",
    "AvSync.correctedRate(rate, aPos, vPos)" in tab
    and "AvSync.correctedRate(rate, aPos, vPos)" in phone)
add("大漂移才硬对齐",
    "AvSync.RESYNC" in tab and "AvSync.RESYNC" in phone)
add("画面卡住时冻结音频（1080p 音画分离的根因）",
    "void detectStall(int pos)" in tab and "void detectStall(int pos)" in phone)
add("画面恢复后音频重新对齐",
    "画面恢复，音频重新对齐" in tab and "画面恢复，音频重新对齐" in phone)
add("换片时清掉音画同步运行时状态",
    "avStallFrozen = false;" in tab and "avStallFrozen = false;" in phone)

# --- 老设备降档 ---
add("设备能力分级类存在（两版一致）",
    "public static int tierOf(int memMb, int cores, int sdk, boolean avc)" in dt_t
    and dt_t == dt_p)
add("老设备自动封顶清晰度",
    "int deviceHeightCap()" in tab and "int deviceHeightCap()" in phone)
add("用户手动选过画质就不再被自动降档",
    "Prefs.qualityTouched(this)" in tab and "Prefs.qualityTouched(this)" in phone)
add("画质触碰标记写入 Prefs（两版一致）",
    "public static boolean qualityTouched(Context c)" in pref_t
    and "public static boolean qualityTouched(Context c)" in pref_p)
add("并行段数按设备能力自适应（低端不再开 3 条连接）",
    "ScheduleProfile.effectiveSegs(" in tab
    and "ScheduleProfile.effectiveSegs(" in phone
    and "DeviceTier.parallelSegs(this)" in tab
    and "DeviceTier.parallelSegs(this)" in phone
    and "static volatile int segLimit" in proxy_t
    and "static volatile int segLimit" in lproxy_p)

# --- 切后台续播 ---
add("切后台前保存播放位置",
    "int bgResumePos = 0;" in tab and "int bgResumePos = 0;" in phone)
add("回到前台恢复播放并回位",
    "void restoreAfterBackground(final int want, final boolean wantPlay, final int attempt)" in tab
    and "void restoreAfterBackground(final int want, final boolean wantPlay, final int attempt)" in phone)
add("用户本来是暂停的，回来不会自己播",
    "bgPausePending" in tab and "bgPausePending" in phone)
add("后台播放开关打开时不受影响",
    "if (Prefs.bgPlay(this)) {" in tab and "if (Prefs.bgPlay(this)) {" in phone)

# --- 线程调度（真生效）---
add("线程优先级工具类存在（两版一致）",
    "public static void apply(int prio)" in np_t and np_t == np_p)
add("优先级通过 Process.setThreadPriority 生效（ART 不认 setPriority）",
    "android.os.Process.setThreadPriority(prio)" in np_t)
add("视频分段/转发线程用真实优先级",
    "NetPrio.wrap(r, NetPrio.VIDEO)" in proxy_t
    and "NetPrio.wrap(r, NetPrio.VIDEO)" in lproxy_p)
add("封面预取与落盘让位给视频",
    "NetPrio.BACKGROUND" in img_t and "NetPrio.BACKGROUND" in img_p
    and "NetPrio.BACKGROUND" in proxy_t)
add("启动预热彻底让位",
    "NetPrio.IDLE" in app_t and "NetPrio.IDLE" in app_p)

# --- 线路亲和 / 端口 ---
add("跨视频 host 亲和（换片不必重新竞速线路）",
    "GLOBAL_HOST" in proxy_t and "GLOBAL_HOST" in lproxy_p
    and "pickGlobalHost(req.urls)" in proxy_t)
add("亲和快路径不再无条件 disconnect（连接复用真正生效）",
    "boolean handed = false;" in proxy_t and "boolean handed = false;" in lproxy_p)
add("竞速胜出的 host 记进全局表",
    "noteGlobalHost(hostOf(usedUrl))" in proxy_t)
add("冷启动预热多个 CDN 域名",
    "upos-sz-mirrorali.bilivideo.com" in app_t
    and "upos-sz-mirrors3.bilivideo.com" in app_p)

# --- 漏洞自查 ---
add("跨线程字段加 volatile（bvid / curBv / curCid）",
    "volatile String bvid;" in tab and "volatile String bvid;" in phone
    and "volatile String curBv" in tab and "volatile String curBv" in phone
    and "volatile long curCid" in tab and "volatile long curCid" in phone)
add("取色页安全取子视图",
    "static TextView childText(ViewGroup g, int idx)" in acc_t)
add("游戏大厅回退时 parent 判空",
    "if (!(p0 instanceof FrameLayout)) return;" in game_t)

# --- 【v4.35.0】动态点头像直出视频 ---
dyn_t = read(ROOT + "DynFeedView.java")
dyn_p = read(PHONE + "DynFeedView.java")
add("点头像改为直接拉 UP 空间内容（不再只做本地筛选）",
    "void loadUp(final String mid, final boolean first)" in dyn_t
    and "void loadUp(final String mid, final boolean first)" in dyn_p)
add("UP 空间两条链路并行（投稿视频 + 空间动态）",
    "PART_VIDEOS" in dyn_t and "PART_DYNAMIC" in dyn_t
    and "BiliApi.userVideos(mid, page)" in dyn_t
    and "BiliApi.dynamicFeed(mid, off)" in dyn_t)
add("两条链路先回先渲染（分片合并，不等齐）",
    "void applyUpPart(" in dyn_t and "upVideosDone && upDynDone" in dyn_t)
add("按 bvid 去重合并，不会出现重复卡片",
    "static String keyOf(Dyn d)" in dyn_t and "upSeen.add(keyOf(d))" in dyn_t)
add("空态文案不再误报「没有内容」（两条链路回来才下结论）",
    "该 UP 主 还没有发布内容" in dyn_t
    and "upVideosDone && upDynDone" in dyn_t
    and 'status("正在加载 "' in dyn_t)
add("关注流与 UP 空间数据分开存放（取消筛选能还原）",
    "ArrayList<Dyn> sourceItems()" in dyn_t
    and "final ArrayList<Dyn> upItems" in dyn_t
    and "items.clear();" in dyn_t)

# --- 【v4.35.0】播放调度档位 ---
sp_t = read(ROOT + "ScheduleProfile.java")
sp_p = read(PHONE + "ScheduleProfile.java")
add("调度档位类存在（两版一致）",
    "public static Plan planOf(int mode" in sp_t and sp_t == sp_p)
add("四档预设齐全（超流畅 / 超清晰 / 平衡 / 节能）",
    "MODE_SMOOTH" in sp_t and "MODE_SHARP" in sp_t
    and "MODE_BALANCED" in sp_t and "MODE_SAVER" in sp_t)
add("默认档是平衡（推荐）",
    'sp(c).getInt("schedule_mode", ScheduleProfile.MODE_BALANCED)' in pref_t)
add("并行段数取「档位 与 设备能力」的较小值",
    "ScheduleProfile.effectiveSegs(" in tab and "ScheduleProfile.effectiveSegs(" in phone)
add("档位上限参与清晰度封顶",
    "int scheduleHeightCap()" in tab and "int scheduleHeightCap()" in phone)
add("超清晰档禁止自动降档",
    "boolean autoDowngradeAllowed()" in tab and "boolean autoDowngradeAllowed()" in phone)
add("连接池上限可按档位调整",
    "public static void applyConnPool(int n)" in read(ROOT + "BiliApi.java")
    and "LocalProxy.setIoLimit(" in tab and "LocalProxy.setIoLimit(" in phone)
add("预取可按档位关闭",
    "public static void setPrefetchEnabled(boolean b)" in read(ROOT + "BiliApi.java")
    and "boolean prefetchAllowed()" in tab and "boolean prefetchAllowed()" in phone)
add("画质读取走「生效画质」（用户选过就听用户的）",
    "Prefs.effectiveQuality(this)" in tab and "Prefs.effectiveQuality(this)" in phone
    and "public static int effectiveQuality(Context c)" in pref_t)

# --- 【v4.35.0】加载提示不再用弹窗动效 ---
add("加载提示改成顶边细线（不再有吉祥物跳舞药丸）",
    "loadBar" in tab and "loadBar" in phone
    and "loadMascot" not in tab and "loadMascot" not in phone)
add("加载提示延迟上屏（正常网络下根本不出现）",
    "immersive ? 2200 : 1500" in tab and "immersive ? 2200 : 1500" in phone)
add("加载文案写进既有 tipText，撤掉时精确清空",
    "loadMsgShown" in tab and "loadMsgShown" in phone
    and "loadMsgShown.equals(String.valueOf(tipText.getText()))" in tab)
add("无效的 Java setPriority 已全部替换为 Process 优先级",
    "Thread.currentThread().setPriority" not in tab
    and "Thread.currentThread().setPriority" not in phone
    and "t.setPriority(Thread.NORM_PRIORITY - 1);" not in read(ROOT + "BiliApi.java"))

# --- 两版差异保护（防止同步时把平板的分屏功能覆盖掉）---
add("平板版仍保留横屏分屏（同步时不能被手机版覆盖）",
    "enterSplitMode()" in tab and "applySplitSizeNow()" in tab
    and "boolean splitMode = false;" in tab)
add("手机版仍是横屏全屏（与平板的行为差异要保留）",
    "enterFullscreen()" in phone and "boolean splitMode" not in phone)
add("两版 PlayActivity 的分屏差异块互不相同",
    "enterSplitMode()" in tab and "enterSplitMode()" not in phone)

# --- 【v4.36.0】交互图标重绘 + 评论点赞 + 流畅度 ---
def resid(name):
    return read("app/res/drawable/" + name + ".xml")

like, like_on = resid("ic_like"), resid("ic_like_on")
cmt, coin = resid("ic_comment"), resid("ic_coin")
star, star_on, share = resid("ic_star"), resid("ic_star_on"), resid("ic_share")
cs = read(ROOT + "CommentSheet.java")
cs_p = read(PHONE + "CommentSheet.java")
api = read(ROOT + "BiliApi.java")

add("五个交互图标已重绘（v4.36.0 标记齐全）",
    all("v4.36.0" in x for x in (like, like_on, cmt, coin, star, star_on, share)))
add("点赞 / 评论 / 投币 / 收藏改为实心面块（更饱满）",
    "fillType=" not in like and "fillType=" not in like_on
    and "fillType=" in cmt and "fillType=" in coin
    and "fillType=" in star and "fillType=" not in star_on)
add("转发图标用圆头粗描边（高级且饱满）",
    "strokeLineCap=\"round\"" in share and 'android:strokeWidth="2.6"' in share)
add("两版图标资源完全一致",
    all(read("app/res/drawable/" + n + ".xml") ==
        read("app_phone/res/drawable/" + n + ".xml")
        for n in ("ic_like", "ic_like_on", "ic_comment", "ic_coin",
                  "ic_star", "ic_star_on", "ic_share")))
add("浅色模式互动图标跟随主文字色（不再白底白图标）",
    "static int idleActColor()" in tab and "static int idleActColor()" in phone
    and "MainActivity.C_FG" in tab)
add("压在视频上的互动栏仍用白色，底部栏跟随页面配色",
    'makeAct("like", "like_on", 24, false, false)' in tab
    and 'makeAct("like", "like_on", 28, false, true)' in tab
    and 'makeAct("like", "like_on", 24, false, false)' in phone)
add("未选中态不再写死 Color.WHITE",
    "setColorFilter(on ? onColor : a.idle)" in tab
    and "setColorFilter(on ? onColor : a.idle)" in phone)
add("评论点赞接口存在（type=1 / action=1|0）",
    "public static void replyLike(long aid, long rpid, boolean on)" in api
    and "&action=" in api and "/x/v2/reply/like" in api)
add("主评论与二级回复都有点赞控件",
    "likeBox(c, new ImageView[1], new TextView[1])" in cs
    and "h.likeBox = likeBox(" in cs and cs == cs_p)
add("点赞先乐观更新、失败回滚，且防连点",
    "likeDelta.put(rpid, want ? 1 : -1)" in cs
    and "likePending.contains(rpid)" in cs
    and "likePending.remove(rpid)" in cs
    and "likedReplies.remove(rpid)" in cs)
add("评论列表用 ViewHolder 复用 + 稳定 id",
    "convert.getTag() instanceof Holder" in cs
    and 'o.optLong("rpid", i)' in cs and cs == cs_p)
add("楼中楼只在数据变化时重建（滚动不反复造 View）",
    "int threadStamp = 1;" in cs and "h.threadStamp != threadStamp" in cs
    and cs == cs_p)
add("版本升到 4.36.0（code 136）",
    'VERSION = "v4.36.0"' in read(ROOT + "BxqApp.java")
    and 'android:versionCode="136"' in read("app/AndroidManifest.xml")
    and 'android:versionCode="136"' in read("app_phone/AndroidManifest.xml"))

bad = 0
for name, ok, detail in checks:
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ((" · " + detail) if (detail and not ok) else ""))
    if not ok:
        bad += 1
print("--------------------------------------")
print("通过 %d 项，失败 %d 项" % (len(checks) - bad, bad))
print("结果：" + ("ALL PASS" if bad == 0 else "FAIL"))
sys.exit(1 if bad else 0)
