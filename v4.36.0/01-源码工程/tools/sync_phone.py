#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把平板版 v4.24.0 的玻璃/控制条改动同步到手机版（按代码块精确搬运）。"""
import re, shutil, os, sys

TAB = "app/src/com/bixiaoqu/app/"
PHN = "app_phone/src/com/bixiaoqu/app/"

def read(p):
    return open(p, encoding="utf-8").read()

def write(p, s):
    open(p, "w", encoding="utf-8").write(s)

def block(src, start_marker, end_marker, after_start=0):
    """取出 [start_marker, end_marker] 之间的代码块（含两端标记）"""
    i = src.index(start_marker, after_start)
    j = src.index(end_marker, i)
    return src[i:j + len(end_marker)]

def swap(dst, start_marker, end_marker, new_block, label):
    i = dst.index(start_marker)
    j = dst.index(end_marker, i)
    write(PHN + "PlayActivity.java", dst[:i] + new_block + dst[j + len(end_marker):])
    print("  [ok] " + label)

tab = read(TAB + "PlayActivity.java")
phn = read(PHN + "PlayActivity.java")

shutil.copy(TAB + "GlassPanel.java", PHN + "GlassPanel.java")
shutil.copy(TAB + "GlassChip.java", PHN + "GlassChip.java")
print("  [ok] GlassPanel.java / GlassChip.java 已复制")

# A. 字段
phn = phn.replace("    LinearLayout ctrlBar;",
"""    LinearLayout ctrlBar;
    /**
     * 【v4.24.0】横屏控制条容器 = 玻璃板（居中自适应） + 独立进度线（满宽）。
     * 两者必须同父，否则切分屏/全屏时控制条被搬到画面浮层，进度线会留在原地。
     */
    LinearLayout ctrlWrap;
    /** 【v4.24.0】当前控制条可用宽度（dp），窄屏据此逐级收起次要控件 */
    int ctrlAvailDp = -1;
    /** 【v4.24.0】窄屏下是否还放得下「下一集」 */
    boolean ctrlNextAllowed = true;
    /** 【v4.24.0】当前分 P 数，供窄屏自适应后恢复「下一集」用 */
    int ctrlPages = 0;""", 1)
write(PHN + "PlayActivity.java", phn)
print("  [ok] 字段声明")

# B. styleSeek 高度
phn = read(PHN + "PlayActivity.java")
phn = phn.replace("""    /**
     * 进度条样式。轨道越粗、圆点越大，越容易被看出「这里可以拖」。
     * 播放器主控制条用 3dp / 13dp：一眼就是可拖的进度条，而不是一条装饰线。
     */
    void styleSeek(SeekBar s, int trackDp, int thumbDp) {
        int track = dp(trackDp);
        int vh = dp(Math.max(20, thumbDp + 10));""",
"""    /**
     * 进度条样式。轨道越粗、圆点越大，越容易被看出「这里可以拖」。
     * 【v4.24.0】容器高度从 20dp 起步压到 14dp —— 横屏控制条要省高度，
     * 圆点本身仍是实心白球，视觉上足够大，不会退化成一条装饰线。
     */
    void styleSeek(SeekBar s, int trackDp, int thumbDp) {
        int track = dp(trackDp);
        int vh = dp(Math.max(14, thumbDp + 6));""", 1)
write(PHN + "PlayActivity.java", phn)
print("  [ok] styleSeek 高度")

# C/D/E. pillBg + glassText + pill + syncPagesButtons/setCtrlVisible/fitCtrlRow
phn = read(PHN + "PlayActivity.java")

new_pill = block(tab,
    "    /**\n     * 胶囊底：一片「薄玻璃」。",
    "        if (l != null) {\n            t.setOnClickListener(l);\n            t.setClickable(true);\n        }\n        return t;\n    }")
i = phn.index("    /** 胶囊底：半透明深色 + 1px 高光描边。压在亮画面上白字也稳 */")
j = phn.index("        if (l != null) {\n            t.setOnClickListener(l);\n            t.setClickable(true);\n        }\n        return t;\n    }", i)
phn = phn[:i] + new_pill + phn[j + len("        if (l != null) {\n            t.setOnClickListener(l);\n            t.setClickable(true);\n        }\n        return t;\n    }"):]
write(PHN + "PlayActivity.java", phn)
print("  [ok] pillBg / glassText / pill")

# E. syncPagesButtons + setCtrlVisible + fitCtrlRow
phn = read(PHN + "PlayActivity.java")
new_sync = block(tab,
    "    void syncPagesButtons(int pages) {",
    "        ctrlNextAllowed = showNext;\n        syncPagesButtons(ctrlPages);\n    }")
i = phn.index("    void syncPagesButtons(int pages) {")
j = phn.index("    /** 控制条玻璃浓度：浮在画面上 / 嵌回页面流，两种背景需要的通透度不一样 */", i)
phn = phn[:i] + new_sync + "\n\n" + phn[j:]
write(PHN + "PlayActivity.java", phn)
print("  [ok] syncPagesButtons / setCtrlVisible / fitCtrlRow")

# F. 竖屏三处
phn = read(PHN + "PlayActivity.java")
phn = phn.replace("vBottom = new GlassPanel(this).radius(20f).tint(0xB4);",
                  "vBottom = new GlassPanel(this).radius(18f).tint(0x52);")
phn = phn.replace("""        vPlay = Ic.glass(this, "pause", Color.WHITE, 0x2EFFFFFF, 40, 20);
        LinearLayout.LayoutParams vplp = new LinearLayout.LayoutParams(dp(40), dp(40));""",
"""        vPlay = GlassChip.icon(this, "pause", 38);
        LinearLayout.LayoutParams vplp = new LinearLayout.LayoutParams(dp(38), dp(38));""")
phn = phn.replace("""        vFull = Ic.glass(this, "full", Color.WHITE, 0x24FFFFFF, 36, 18);
        LinearLayout.LayoutParams vflp = new LinearLayout.LayoutParams(dp(36), dp(36));""",
"""        vFull = GlassChip.icon(this, "full", 32);
        LinearLayout.LayoutParams vflp = new LinearLayout.LayoutParams(dp(32), dp(32));""")
write(PHN + "PlayActivity.java", phn)
print("  [ok] 竖屏玻璃材质")

# G. 控制条构建块
phn = read(PHN + "PlayActivity.java")
new_ctrl = block(tab,
    "        // ---------------- 控制条：一片液态玻璃 + 一条细进度线（v4.24.0） ----------------",
    "        root.addView(ctrlWrap, wrapLp);")
i = phn.index("        // ---------------- 控制条：一块液态玻璃悬浮卡（v4.23.0） ----------------")
j = phn.index("        root.addView(ctrlBar, cbp0);", i)
phn = phn[:i] + new_ctrl + phn[j + len("        root.addView(ctrlBar, cbp0);"):]
write(PHN + "PlayActivity.java", phn)
print("  [ok] 横屏控制条重建")

# H. ctrlToOverlay / ctrlToRootFlow
phn = read(PHN + "PlayActivity.java")
new_pos = block(tab, "    void ctrlToOverlay() {",
                "            CrashHandler.note(\"ctrl\", \"控制条归位失败：\" + e);\n        }\n    }")
i = phn.index("    void ctrlToOverlay() {")
j = phn.index("            CrashHandler.note(\"ctrl\", \"控制条归位失败：\" + e);\n        }\n    }", i)
phn = phn[:i] + new_pos + phn[j + len("            CrashHandler.note(\"ctrl\", \"控制条归位失败：\" + e);\n        }\n    }"):]
write(PHN + "PlayActivity.java", phn)
print("  [ok] ctrlToOverlay / ctrlToRootFlow")

# I. ctrlTint 浓度
phn = read(PHN + "PlayActivity.java")
phn = phn.replace("ctrlTint(0xAA);   // 浮在画面上：稍透一点，看出背后是视频",
                  "ctrlTint(0x46);   // 浮在画面上：基底只压 27%，背后视频透得出来")
phn = phn.replace("ctrlTint(0xC6);   // 回到页面流：稍实一点，压住页面底色",
                  "ctrlTint(0x66);   // 回到页面流：压到 40%，压住页面底色保证文字可读")
write(PHN + "PlayActivity.java", phn)
print("  [ok] ctrlTint 浓度")

# J. 显隐统一入口
phn = read(PHN + "PlayActivity.java")
phn = phn.replace("            if (ctrlBar != null) ctrlBar.setVisibility(View.GONE);",
                  "            setCtrlVisible(View.GONE);")
phn = phn.replace("                if (ctrlBar != null) ctrlBar.setVisibility(View.VISIBLE);",
                  "                setCtrlVisible(View.VISIBLE);")
phn = phn.replace("                ctrlBar.setVisibility(ctrlVisible ? View.VISIBLE : View.GONE);",
                  "                setCtrlVisible(ctrlVisible ? View.VISIBLE : View.GONE);")
phn = phn.replace("        ctrlBar.setVisibility(View.VISIBLE);",
                  "        setCtrlVisible(View.VISIBLE);")
phn = phn.replace("            ctrlBar.setVisibility(View.VISIBLE);",
                  "            setCtrlVisible(View.VISIBLE);")
write(PHN + "PlayActivity.java", phn)
print("  [ok] 显隐统一入口")

left = re.findall(r"ctrlBar\.setVisibility", phn)
print("剩余 ctrlBar.setVisibility: %d 处" % len(left))
