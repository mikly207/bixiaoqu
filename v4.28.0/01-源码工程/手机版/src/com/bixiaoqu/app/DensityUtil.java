package com.bixiaoqu.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * 【v4.14.2】全局界面缩放（DPI）。
 *
 * 原理：本项目所有尺寸都是代码里算出的 dp 值，而
 *       px = dp × getResources().getDisplayMetrics().density
 * 所以只要在进程内按倍率调整 density / densityDpi，
 * 全部页面（自绘 View、图标、列表、字号、间距）都会等比缩放，
 * 无需改动任何一处的 dp() 调用 —— 一处生效，全局覆盖。
 *
 * 特别适合手表 / 小屏设备：选「紧凑 85%」即可让一屏显示更多内容。
 */
public final class DensityUtil {

    /** 三个推荐档位（百分比）：标准 / 紧凑（手表小屏推荐）/ 宽松 */
    public static final int[] PRESETS = {100, 85, 120};
    public static final String[] PRESET_LABELS = {
            "标准 · 100%（跟随系统）",
            "紧凑 · 85%（手表 / 小屏推荐）",
            "宽松 · 120%（元素更大更好点）"
    };

    /** 进程启动时锁定的系统原始密度基准，只记录一次，避免重复应用叠加 */
    private static float baseDensity = -1f;
    private static int baseDensityDpi = -1;

    /** 在 Application 早期调用，锁定系统原始密度 */
    public static void captureBase(Context c) {
        if (baseDensity > 0) return;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            baseDensity = dm.density;
            baseDensityDpi = dm.densityDpi;
        } catch (Throwable ignore) { }
    }

    /** 读取当前倍率百分比（50~200，默认 100） */
    public static int percent(Context c) {
        int v = Prefs.uiScale(c);
        return (v < 50 || v > 200) ? 100 : v;
    }

    /**
     * 应用当前倍率到进程 Resources。
     * 幂等：已是目标 densityDpi 时直接返回，重复调用安全、不叠加。
     */
    public static void apply(Context c) {
        captureBase(c);
        if (baseDensity <= 0 || baseDensityDpi <= 0) return;
        int pct = percent(c);
        int targetDpi = Math.round(baseDensityDpi * (pct / 100f));
        try {
            Resources res = c.getResources();
            Configuration cfg = res.getConfiguration();
            if (cfg.densityDpi == targetDpi) return;
            cfg.densityDpi = targetDpi;
            res.updateConfiguration(cfg, res.getDisplayMetrics());
        } catch (Throwable ignore) { }
    }

    /** 当前设置对应的展示文案 */
    public static String label(Context c) {
        int pct = percent(c);
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == pct) return PRESET_LABELS[i];
        }
        return "自定义 · " + pct + "%";
    }

    private DensityUtil() { }
}
