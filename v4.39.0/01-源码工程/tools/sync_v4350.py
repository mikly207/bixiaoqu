#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""【v4.35.0】把手机版（app_phone）里本轮新增的改动同步到平板版（app）。

为什么需要它：
    平板版与手机版共用同一套源码，但 MainActivity / SearchActivity / PlayActivity
    三个文件存在**真实差异**（平板有分屏、推荐右栏等），不能整文件覆盖。
    本轮改动集中在这三个文件里若干「两版原本完全一致」的片段上，
    所以用「片段搬运」的方式同步：按起止标记取出手机版的新片段，
    替换掉平板版对应的旧片段 —— 既安全，又能在缺失时立刻报出来。

运行：python3 tools/sync_v4350.py
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TAB = os.path.join(ROOT, "app/src/com/bixiaoqu/app/")
PHN = os.path.join(ROOT, "app_phone/src/com/bixiaoqu/app/")

# ---- ① 两版本来就完全一致的文件：直接覆盖 ----
SYNC_FILES = [
    "ScheduleProfile.java",   # v4.35.0 新增
    "Prefs.java",              # 调度档位读写
    "DynFeedView.java",        # 点头像直出视频
    "SettingsActivity.java",   # 播放调度设置项
    "BiliApi.java",            # 连接池 / 预取开关
    "BxqApp.java",             # 启动时应用调度档位
    "LocalProxy.java",         # IO 线程池按档位调整
]

# ---- ② PlayActivity：片段搬运（平板旧片段 → 手机新片段）----
# (平板旧起, 平板旧止, 手机新起, 手机新止)
PA_REGIONS = [
    # 加载提示相关字段
    ("    // 【v4.0】加载界面：圆角方块闭眼小电视 + 「正在加载喵~」+ Dynamo 署名",
     "    boolean loadAnimShowing = false;",
     "    // 【v4.35.0】加载提示：改成「视频区顶边一条 2dp 细线」。",
     "    String loadMsgShown = \"\";"),
    # buildLoadAnim
    ("    /**\n     * 【v4.0】加载界面：圆角方块闭眼小电视（应用图标本体）",
     "        loadBox.setVisibility(View.GONE);\n    }",
     "    /**\n     * 【v4.35.0】加载提示重做：**视频区顶边一条 2dp 细线**。",
     "        loadBox.setVisibility(View.GONE);\n    }"),
    # showLoadAnim + applyVerticalLoadStyle
    ("    /** 显示/隐藏加载动画。show=true 同时把文案设成 msg（默认「加载中喵~」）。 */",
     "    /** 圆角纯色背景（半径直接给 px） */",
     "    /**\n     * 【v4.35.0】显示/隐藏加载提示。",
     "    /** 圆角纯色背景（半径直接给 px） */"),
    # chooseHeight + 调度档位上限/降档开关
    ("    int chooseHeight(int q, int wantH) {",
     "    /**\n     * 【v4.18.0】音轨延后起播",
     "    int chooseHeight(int q, int wantH) {",
     "    /**\n     * 【v4.18.0】音轨延后起播"),
    # prefetchAllowed + prefetchSwipeNeighborsNow
    ("    void prefetchSwipeNeighborsNow() {",
     "    /**\n     * 【v4.19.0】用已预取的 playurl 探测一条最快线路",
     "    /**\n     * 【v4.35.0】是否允许后台预取（播放调度档位控制）。",
     "    /**\n     * 【v4.19.0】用已预取的 playurl 探测一条最快线路"),
    # prefetchRowsNow
    ("    void prefetchRowsNow(final ArrayList<VideoItem> rows, final int n) {",
     "    void prefetchCovers(final ArrayList<VideoItem> rows, final int n) {",
     "    void prefetchRowsNow(final ArrayList<VideoItem> rows, final int n) {",
     "    void prefetchCovers(final ArrayList<VideoItem> rows, final int n) {"),
    # warmupOne
    ("    void warmupOne(VideoItem it) {",
     "    /** 预取封面到内存缓存（ImgLoader 有 LRU，滑到时直接命中，不再等网络） */",
     "    void warmupOne(VideoItem it) {",
     "    /** 预取封面到内存缓存（ImgLoader 有 LRU，滑到时直接命中，不再等网络） */"),
]

# ---- ③ PlayActivity：单行/小块替换 ----
PA_PAIRS = [
    ("        // ---------------- 【v3.9】加载动画：跳舞的小电视 + 正在加载喵~ ----------------",
     "        // ---------------- 【v4.35.0】加载提示：视频区顶边一条 2dp 细线 ----------------"),
    ("                showLoadAnim(false, null);   // 画面就绪，收起跳舞动画",
     "                showLoadAnim(false, null);   // 画面就绪，收起加载提示"),
    ('        scheduleLoadAnim("正在打开缓存…", 200);',
     '        scheduleLoadAnim("正在打开缓存…", 600);'),
    ("        scheduleLoadAnim(playLoadMsg, immersive ? 1300 : 900);",
     "        // 【v4.35.0】1300/900 → 2200/1500：加载提示改成贴顶细线后不再抢视线，\n"
     "        // 于是可以更晚才出现 —— 正常网络下首帧早于它，用户全程看不到任何加载提示。\n"
     "        scheduleLoadAnim(playLoadMsg, immersive ? 2200 : 1500);"),
    ('        tipText.setText(immersive ? "" : "获取播放地址…");',
     "        // 【v4.35.0】不再抢 tipText 写「获取播放地址…」：\n"
     "        // 加载提示已经统一由 showLoadAnim 负责（顶边细线 + 同一处文案），\n"
     "        // 这里留着空串，失败/切线路等真提示才有地方显示。\n"
     '        tipText.setText("");'),
    ("            // 【v4.34.0】并行段数按设备能力定：老设备上多开连接反而更卡\n"
     "            LocalProxy.setSegLimit(DeviceTier.parallelSegs(this));",
     "            // 【v4.35.0】并行段数 = 播放调度档位 与 设备能力 取小：\n"
     "            // 档位是用户的选择，设备能力是硬上限（老设备上多开连接反而更卡）\n"
     "            ScheduleProfile.Plan plan = Prefs.schedulePlan(this);\n"
     "            LocalProxy.setSegLimit(ScheduleProfile.effectiveSegs(\n"
     "                    plan, DeviceTier.parallelSegs(this)));\n"
     "            LocalProxy.setIoLimit(plan == null ? 16 : plan.ioThreads * 2);"),
    ("                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);",
     "                    // 【v4.35.0】原来这行 setPriority 在 ART 上无效，\n"
     "                    // 换成真正生效的 Process 优先级（预热让位给起播）\n"
     "                    NetPrio.apply(NetPrio.BACKGROUND);"),
    # 画质读取统一走「生效画质」
    ('qualityBtn = pill(null, "画质 " + qualityShort(Prefs.quality(this)),',
     'qualityBtn = pill(null, "画质 " + qualityShort(Prefs.effectiveQuality(this)),'),
    ('final int cur = Prefs.quality(this);',
     'final int cur = Prefs.effectiveQuality(this);'),
    ('    void applyQuality() {\n        int q = Prefs.quality(this);',
     '    void applyQuality() {\n        int q = Prefs.effectiveQuality(this);'),
    ('                int q = Prefs.quality(PlayActivity.this);',
     '                int q = Prefs.effectiveQuality(PlayActivity.this);'),
    ('            int q = Prefs.quality(this);\n            // 【v4.27.0】必须用 QN_ALL',
     '            int q = Prefs.effectiveQuality(this);\n            // 【v4.27.0】必须用 QN_ALL'),
    ('final int targetH = Prefs.qualityHeight(Prefs.quality(this));',
     'final int targetH = Prefs.qualityHeight(Prefs.effectiveQuality(this));'),
    ('                    Prefs.qualityHeight(Prefs.quality(this)));',
     '                    Prefs.qualityHeight(Prefs.effectiveQuality(this)));'),
    ('final int targetH = Prefs.qualityHeight(Prefs.quality(PlayActivity.this));',
     'final int targetH = Prefs.qualityHeight(Prefs.effectiveQuality(PlayActivity.this));'),
    ('final String[] items = {"清晰度：" + qualityShort(Prefs.quality(this)),',
     'final String[] items = {"清晰度：" + qualityShort(Prefs.effectiveQuality(this)),'),
]


def cut(text, start, end):
    i = text.index(start)
    j = text.index(end, i)
    return i, j + len(end)


def main():
    # ① 完全一致的文件：直接覆盖
    for f in SYNC_FILES:
        src = os.path.join(PHN, f)
        dst = os.path.join(TAB, f)
        if not os.path.exists(src):
            print("  [跳过] 源文件不存在：%s" % f)
            continue
        shutil.copyfile(src, dst)
        print("  [ok] 覆盖 %s" % f)

    # ② PlayActivity：片段搬运
    tp = os.path.join(TAB, "PlayActivity.java")
    pp = os.path.join(PHN, "PlayActivity.java")
    tab = open(tp, encoding="utf-8").read()
    phn = open(pp, encoding="utf-8").read()

    miss = 0
    for ts, te, ps, pe in PA_REGIONS:
        try:
            _, tend = cut(tab, ts, te)
            pstart, pend = cut(phn, ps, pe)
        except ValueError:
            print("  [缺失] 片段：%s" % ts.strip().splitlines()[0][:60])
            miss += 1
            continue
        tab = tab[:_] + phn[pstart:pend] + tab[tend:]
        print("  [ok] 片段：%s" % ts.strip().splitlines()[0][:56])

    for old, new in PA_PAIRS:
        if new in tab and old not in tab:
            print("  [已同步] %s" % old.strip().splitlines()[0][:56])
            continue
        if old not in tab:
            print("  [缺失] %s" % old.strip().splitlines()[0][:60])
            miss += 1
            continue
        tab = tab.replace(old, new, 1)

    open(tp, "w", encoding="utf-8").write(tab)
    print("  PlayActivity 同步完成，缺失 %d 处" % miss)
    return 1 if miss else 0


if __name__ == "__main__":
    sys.exit(main())
