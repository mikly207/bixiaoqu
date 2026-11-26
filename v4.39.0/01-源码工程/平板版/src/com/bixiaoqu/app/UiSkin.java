package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * 【v4.31.0】界面皮肤的开关与 iOS 设计令牌。
 *
 * 为什么用「manifest meta-data」而不是两套源码：
 *   · 两套源码 = 两份要同步维护的 UI，改一处漏一处，最后一定跑偏；
 *   · 资源 bool 需要 R 类，而本工程走手工 aapt2、没有生成 R；
 *   · meta-data 是 aapt2 链接时读的，构建脚本只要在打包前改一个字符串，
 *     就能产出「原版 / iOS 版」两个 APK，Java 源码完全一致。
 *
 * 取值：{@code <meta-data android:name="bxq.ios_ui" android:value="true|false"/>}
 *
 * iOS 令牌取自 iOS 17/18 的系统色板（深色 / 浅色各一套）：
 *   背景 #000000 / #F2F2F7     卡片 #1C1C1E / #FFFFFF
 *   主文字 #FFFFFF / #000000   次文字 #8E8E93
 *   强调 #FF375F / #FF2D55（品牌粉的 iOS 色阶）
 *   圆角：卡片 12、封面 10、胶囊 = 高度一半
 *   间距：页面左右 16、卡片之间 12、分组之间 24
 */
public final class UiSkin {

    /** null = 还没读过 */
    private static Boolean cached;

    private UiSkin() { }

    /** 当前 APK 是否为 iOS 皮肤版本 */
    public static boolean ios(Context c) {
        if (cached != null) return cached;
        boolean v = false;
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(c.getPackageName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null) {
                v = ai.metaData.getBoolean("bxq.ios_ui", false);
            }
        } catch (Throwable ignore) { }
        cached = v;
        return v;
    }

    // ------------------------------------------------------------ 色板

    public static int bg(boolean dark) { return dark ? 0xFF000000 : 0xFFF2F2F7; }

    public static int card(boolean dark) { return dark ? 0xFF1C1C1E : 0xFFFFFFFF; }

    public static int card2(boolean dark) { return dark ? 0xFF2C2C2E : 0xFFFFFFFF; }

    public static int fg(boolean dark) { return dark ? 0xFFFFFFFF : 0xFF000000; }

    public static int sub(boolean dark) { return 0xFF8E8E93; }

    public static int acc(boolean dark) { return dark ? 0xFFFF375F : 0xFFFF2D55; }

    /** 搜索框 / 分段控件的填充底色（iOS 的 fill 语义） */
    public static int fill(boolean dark) { return dark ? 0x2E767680 : 0x14767680; }

    /** 分隔线（hairline） */
    public static int sep(boolean dark) { return dark ? 0x2E545458 : 0x1F3C3C43; }

    public static int placeholder(boolean dark) {
        return dark ? 0xFF1C1C1E : 0xFFE5E5EA;
    }

    // ------------------------------------------------------------ 尺寸

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 纯色圆角块（iOS 的 fill / 卡片都用它，不带描边、不带渐变） */
    public static GradientDrawable fill(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 分组卡片：卡片底色 + 12dp 圆角 */
    public static GradientDrawable group(Context c, boolean dark) {
        GradientDrawable g = fill(c, card(dark), 12);
        // iOS 在纯黑背景上会有一道极淡的边缘，避免卡片"浮"不出来
        if (dark) g.setStroke(1, 0x14FFFFFF);
        return g;
    }
}
