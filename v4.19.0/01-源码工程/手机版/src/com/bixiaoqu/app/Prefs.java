package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地设置项（全部走 SharedPreferences，无第三方依赖） */
public final class Prefs {

    static final String NAME = "bxq_settings";

    public static final int DEC_AUTO = 0;
    public static final int DEC_HW = 1;
    public static final int DEC_COMPAT = 2;
    public static final int DEC_LOW = 3;

    /**
     * 【v4.17.0】文案同步更新：默认档明确说明「优先硬解」，
     * 让用户知道系统解码器走的是 GPU 硬件加速而不是软解。
     */
    public static final String[] DEC_NAMES = {
            "自动（优先硬解·推荐）", "硬解（DASH 双流）", "兼容（合流）", "兼容 360p"
    };

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ===== 主题（v4.10.0）=====
    public static final int THEME_SYSTEM = 0;   // 跟随系统
    public static final int THEME_DARK = 1;     // 强制深色
    public static final int THEME_LIGHT = 2;    // 强制浅色

    public static int themeMode(Context c) {
        return sp(c).getInt("theme_mode", THEME_DARK);
    }

    public static void setThemeMode(Context c, int v) {
        sp(c).edit().putInt("theme_mode", v).apply();
    }

    /** 推荐页是否双列（默认单列，保持旧版观感） */
    public static boolean twoColumn(Context c) {
        return sp(c).getBoolean("two_column", true);    // 【v4.15.7】默认双页排布
    }

    public static void setTwoColumn(Context c, boolean v) {
        sp(c).edit().putBoolean("two_column", v).apply();
    }

    /** 是否显示开屏动画（默认开） */
    public static boolean splash(Context c) {
        return sp(c).getBoolean("splash", true);
    }

    public static void setSplash(Context c, boolean v) {
        sp(c).edit().putBoolean("splash", v).apply();
    }

    /** 默认解码方式：0 自动 / 1 硬解 / 2 兼容合流（高码率单文件） / 3 兼容 360p */
    public static int decoder(Context c) {
        int v = sp(c).getInt("decoder", DEC_AUTO);
        return (v < 0 || v > 3) ? DEC_AUTO : v;
    }

    public static void setDecoder(Context c, int v) {
        sp(c).edit().putInt("decoder", v).apply();
    }

    public static String decoderName(int v) {
        switch (v) {
            case DEC_HW: return "硬解（DASH 双流）";
            case DEC_COMPAT: return "兼容（合流，绕开 DASH）";
            case DEC_LOW: return "兼容模式（360p 合流）";
            default: return "自动（优先硬解）";
        }
    }

    /** 播放器默认是否开弹幕 */
    public static boolean danmaku(Context c) {
        return sp(c).getBoolean("danmaku", false);   // 【v4.15.7】默认关闭弹幕
    }

    public static void setDanmaku(Context c, boolean v) {
        sp(c).edit().putBoolean("danmaku", v).apply();
    }

    // ---- 【v4.8】弹幕样式：颜色 / 透明度 / 字号 ----

    /** 弹幕颜色：0 = 跟随弹幕自带颜色（B站彩色弹幕），否则为 0xRRGGBB */
    public static int dmColor(Context c) {
        return sp(c).getInt("dm_color", 0) & 0xFFFFFF;
    }

    public static void setDmColor(Context c, int v) {
        sp(c).edit().putInt("dm_color", v & 0xFFFFFF).apply();
    }

    /** 弹幕不透明度百分比（10~100） */
    public static int dmAlpha(Context c) {
        int v = sp(c).getInt("dm_alpha", 100);
        return v < 10 ? 10 : (v > 100 ? 100 : v);
    }

    public static void setDmAlpha(Context c, int v) {
        sp(c).edit().putInt("dm_alpha", Math.max(10, Math.min(100, v))).apply();
    }

    /** 弹幕字号百分比（60~180） */
    public static int dmSize(Context c) {
        int v = sp(c).getInt("dm_size", 100);
        return v < 60 ? 60 : (v > 180 ? 180 : v);
    }

    public static void setDmSize(Context c, int v) {
        sp(c).edit().putInt("dm_size", Math.max(60, Math.min(180, v))).apply();
    }

    // ---- 【v4.10.6】弹幕更多调节：速度 / 显示区域 / 密度 ----

    /** 滚动速度百分比（50~200，100 = 标准） */
    public static int dmSpeed(Context c) {
        int v = sp(c).getInt("dm_speed", 100);
        return v < 50 ? 50 : (v > 200 ? 200 : v);
    }

    public static void setDmSpeed(Context c, int v) {
        sp(c).edit().putInt("dm_speed", Math.max(50, Math.min(200, v))).apply();
    }

    /** 弹幕铺满的高度占屏幕比例（40~100） */
    public static int dmArea(Context c) {
        int v = sp(c).getInt("dm_area", 100);
        return v < 40 ? 40 : (v > 100 ? 100 : v);
    }

    public static void setDmArea(Context c, int v) {
        sp(c).edit().putInt("dm_area", Math.max(40, Math.min(100, v))).apply();
    }

    /** 同屏最多几条（10~80） */
    public static int dmDensity(Context c) {
        int v = sp(c).getInt("dm_density", 40);
        return v < 10 ? 10 : (v > 80 ? 80 : v);
    }

    public static void setDmDensity(Context c, int v) {
        sp(c).edit().putInt("dm_density", Math.max(10, Math.min(80, v))).apply();
    }

    /** 竖屏视频全屏时是否做「高度适配 + 左右虚化」优化 */
    public static boolean portraitOptimize(Context c) {
        return sp(c).getBoolean("portrait_opt", true);
    }

    public static void setPortraitOptimize(Context c, boolean v) {
        sp(c).edit().putBoolean("portrait_opt", v).apply();
    }

    /** 记住上次播放倍速（默认 1x） */
    public static float speed(Context c) {
        float v = sp(c).getFloat("speed", 1f);
        return (v < 0.4f || v > 3f) ? 1f : v;
    }

    public static void setSpeed(Context c, float v) {
        sp(c).edit().putFloat("speed", v).apply();
    }

    // ------------------------------------------------------------ 清晰度

    public static final int Q_AUTO = 0;
    public static final int Q_4K = 1;
    public static final int Q_2K = 2;
    public static final int Q_1080P60 = 3;
    public static final int Q_1080P = 4;
    public static final int Q_720P = 5;
    public static final int Q_480P = 6;
    public static final int Q_360P = 7;

    public static final String[] Q_NAMES = {
            "自动（最高可用）", "4K 超清", "2K 超清", "1080P60", "1080P", "720P", "480P", "360P"
    };

    /**
     * 清晰度选项。
     *
     * 【v4.17.0】默认值由「自动（最高可用）」改为 720P，并换用新的存储键
     * （quality_v2），让升级上来的老用户也统一从 720P 起步。
     * 原因：默认 4K/1080P 高码率流首帧要 6~10 秒，且持续吃带宽，
     * 用户抱怨「打开很慢」。720P 起播几乎 1~2 秒，画面在该机屏幕上也够清晰；
     * 需要更高画质时，播放页顶部「画质」按钮一键切回即可（之后的选择会被记住）。
     */
    public static int quality(Context c) {
        int v = sp(c).getInt("quality_v2", Q_720P);
        return (v < 0 || v >= Q_NAMES.length) ? Q_720P : v;
    }

    public static void setQuality(Context c, int v) {
        sp(c).edit().putInt("quality_v2", v).apply();
    }

    public static String qualityName(int v) {
        return (v >= 0 && v < Q_NAMES.length) ? Q_NAMES[v] : Q_NAMES[0];
    }

    /** 该档位对应的目标高度（px），自动/4K/2K 返回 0 表示「尽量高」 */
    public static int qualityHeight(int v) {
        switch (v) {
            case Q_4K: return 2160;
            case Q_2K: return 1440;
            case Q_1080P60: return 1080;
            case Q_1080P: return 1080;
            case Q_720P: return 720;
            case Q_480P: return 480;
            case Q_360P: return 360;
            default: return 0;
        }
    }

    /** 该档位请求 playurl 时用的 qn */
    public static int qualityQn(int v) {
        switch (v) {
            case Q_4K: return 120;
            case Q_2K: return 116;      // B 站无原生 2K，取 1080P60 高码位，实际按分辨率挑
            case Q_1080P60: return 116;
            case Q_1080P: return 80;
            case Q_720P: return 64;
            case Q_480P: return 32;
            case Q_360P: return 16;
            default: return 120;        // 自动也请求最高，尽量让接口把 4K 一起返回
        }
    }

    // ------------------------------------------------------------ 推荐流

    /** 推荐列表翻页/换批计数，保证每次刷新请求不同的分页，内容自然不同 */
    public static int recSeed(Context c) {
        return sp(c).getInt("rec_seed", 0);
    }

    public static int bumpRecSeed(Context c) {
        int v = recSeed(c) + 1;
        sp(c).edit().putInt("rec_seed", v % 100000).apply();
        return v;
    }

    // ------------------------------------------------------------ 彩蛋：跳一跳

    /** 跳一跳历史最高分 */
    public static int jumpBest(Context c) {
        return sp(c).getInt("jump_best", 0);
    }

    public static void setJumpBest(Context c, int v) {
        if (v > jumpBest(c)) sp(c).edit().putInt("jump_best", v).apply();
    }

    /** 某档难度的历史最高分（简单/中等/困难 分开记录） */
    public static int jumpBestAt(Context c, int diff) {
        return sp(c).getInt("jump_best_" + diff, 0);
    }

    public static void setJumpBestAt(Context c, int diff, int v) {
        if (v > jumpBestAt(c, diff)) {
            sp(c).edit().putInt("jump_best_" + diff, v).apply();
        }
    }

    /** 上次选择的难度：0 简单 / 1 中等 / 2 困难 */
    public static int jumpDifficulty(Context c) {
        return sp(c).getInt("jump_diff", 1);
    }

    public static void setJumpDifficulty(Context c, int d) {
        sp(c).edit().putInt("jump_diff", d).apply();
    }

    // ===== 【v4.13.1】分区页「快捷访问」收藏的分区索引 =====
    /** 返回收藏的分区索引（逗号分隔，如 "0,4,13"）；默认空 */
    public static String zoneFavorites(Context c) {
        return sp(c).getString("zone_favs", "");
    }

    public static void setZoneFavorites(Context c, String v) {
        sp(c).edit().putString("zone_favs", v == null ? "" : v).apply();
    }

    // ===== 【v4.14.0】录音棚：在线转文字（百度语音，用户自填密钥）=====
    /** 百度语音 API Key（用户自行申请，App 不内置任何凭证） */
    public static String asrApiKey(Context c) {
        return sp(c).getString("asr_ak", "");
    }

    public static String asrSecretKey(Context c) {
        return sp(c).getString("asr_sk", "");
    }

    public static void setAsrKeys(Context c, String ak, String sk) {
        sp(c).edit().putString("asr_ak", ak == null ? "" : ak.trim())
                .putString("asr_sk", sk == null ? "" : sk.trim()).apply();
    }

    public static boolean hasAsrKeys(Context c) {
        return !asrApiKey(c).isEmpty() && !asrSecretKey(c).isEmpty();
    }

    // ===== 【v4.14.0】小说吧：护眼模式与阅读设置 =====
    /** 护眼背景：0 普通(跟随主题) / 1 米黄 / 2 豆沙绿 / 3 夜间 */
    public static int novelEyeMode(Context c) {
        int v = sp(c).getInt("novel_eye", 0);
        return (v < 0 || v > 3) ? 0 : v;
    }

    public static void setNovelEyeMode(Context c, int v) {
        sp(c).edit().putInt("novel_eye", Math.max(0, Math.min(3, v))).apply();
    }

    /** 字号（sp，16~30） */
    public static int novelTextSize(Context c) {
        int v = sp(c).getInt("novel_ts", 20);
        return v < 16 ? 16 : (v > 30 ? 30 : v);
    }

    public static void setNovelTextSize(Context c, int v) {
        sp(c).edit().putInt("novel_ts", Math.max(16, Math.min(30, v))).apply();
    }

    /** 书源 JSON（书源列表，由用户自行添加，App 不内置任何内容源） */
    public static String novelSources(Context c) {
        return sp(c).getString("novel_sources", "");
    }

    public static void setNovelSources(Context c, String v) {
        sp(c).edit().putString("novel_sources", v == null ? "" : v).apply();
    }

    // ===== 【v4.14.2】全局界面缩放（DPI）：百分比 50~200，默认 100 =====
    public static int uiScale(Context c) {
        int v = sp(c).getInt("ui_scale", 100);
        return v < 50 ? 50 : (v > 200 ? 200 : v);
    }

    public static void setUiScale(Context c, int v) {
        sp(c).edit().putInt("ui_scale", Math.max(50, Math.min(200, v))).apply();
    }

    private Prefs() { }
}
