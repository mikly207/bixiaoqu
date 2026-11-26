package com.bixiaoqu.app;

import android.content.Context;

/**
 * 【v4.33.0】全局主题色。
 *
 * 旧版整个 App 的强调色是写死的 B 站粉（#FB7299），散布在几十个文件里。
 * 这里把它收成一个可读可写的单点：
 *
 *   · {@link #acc()}          —— 当前强调色（ARGB）
 *   · {@link #c(int)}        —— 把「带 alpha 的旧粉色」换算成同 alpha 的主题色
 *   · {@link #a(int)}        —— 按百分比取一个半透明主题色
 *
 * 设计上刻意**不**缓存成静态 final：换色后所有界面靠 MainActivity.themeStampOf
 * 的指纹变化触发 recreate，重建时自然会重新取一遍。
 *
 * iOS 皮肤不读用户色，走 {@code UiSkin.acc(dark)} 的系统粉 —— 用户要求这个功能
 * 只加在非 iOS 版，所以 c() 统一以 MainActivity.C_ACC 为准，皮肤差异自动成立。
 */
public final class Theme {

    /** 默认色 = B 站粉，没设置过的老用户观感不变 */
    public static final int DEFAULT = 0xFFFB7299;

    /**
     * 8 个推荐色：红 橙 黄 绿 青 蓝 紫 粉。
     * 都是「中高饱和 + 中亮」的档位 —— 深色底上当强调色够醒目，
     * 浅色底上也不会因为太亮而糊成一片（黄色特意压到接近琥珀色）。
     */
    public static final int[] PRESETS = {
            0xFFE5484D,   // 红
            0xFFFF8A3D,   // 橙
            0xFFE9A825,   // 黄（琥珀，纯柠檬黄在浅色底上读不出来）
            0xFF30A46C,   // 绿
            0xFF12A594,   // 青
            0xFF3E7BFA,   // 蓝
            0xFF8E4EC6,   // 紫
            0xFFFB7299,   // 粉（默认）
    };

    public static final String[] NAMES = {"红", "橙", "黄", "绿", "青", "蓝", "紫", "粉"};

    static volatile int acc = DEFAULT;

    public static int acc() {
        return acc;
    }

    public static void load(Context c) {
        try { acc = Prefs.accent(c); } catch (Throwable e) { acc = DEFAULT; }
    }

    public static void set(Context c, int color) {
        acc = color | 0xFF000000;
        Prefs.setAccent(c, acc);
    }

    /**
     * 把一个「写死 alpha 的旧粉色」换成同 alpha 的主题色。
     * 用法：把写死的 0x1AFB7299 改成 Theme.c(0x1AFB7299)。
     * alpha 位原样保留，RGB 换成当前主题色。
     */
    public static int c(int argb) {
        return (argb & 0xFF000000) | (MainActivity.C_ACC & 0x00FFFFFF);
    }

    /** 按百分比取半透明主题色：Theme.a(12) = 12% 不透明度 */
    public static int a(int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return ((pct * 255 / 100) << 24) | (MainActivity.C_ACC & 0x00FFFFFF);
    }

    /** 是否正好是某个推荐色（返回下标，-1 = 自定义） */
    public static int presetIndex(int color) {
        for (int i = 0; i < PRESETS.length; i++) {
            if ((PRESETS[i] & 0xFFFFFF) == (color & 0xFFFFFF)) return i;
        }
        return -1;
    }

    /** 给设置页显示的中文名 */
    public static String nameOf(Context c) {
        int i = presetIndex(acc);
        return i >= 0 ? NAMES[i] : "自定义";
    }

    private Theme() { }
}
