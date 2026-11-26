package com.bixiaoqu.app;

/**
 * 【v4.21.0】播放页布局策略（纯逻辑，不依赖 Android）。
 *
 * 为什么把这几个判断单独抽出来：
 * v4.20.0 平板版的「横屏分屏里点推荐 → 黑屏只剩声音 → 再返回变成手机竖屏」，
 * 根因就是布局判断散落在 6 个地方（onCreate / applyMeta / play 的宽高回调 /
 * exitFullscreen / onConfigurationChanged / switchVideo），每处各写一半条件：
 *   applyMeta 只判了「竖屏视频 + 非全屏」，没判「分屏」，于是平板横屏分屏里
 *   点一个竖屏推荐，就会在横向 root 上执行 enterPortraitFill()：
 *   holder 被改成 MATCH_PARENT + weight，右栏被挤成 0 宽，画面盒按整屏重算，
 *   SurfaceView 在 prepare 期间被重新测量 → Surface 重建，解码器不再出图，
 *   声音却由独立音轨照常播放。返回时 exitPortraitFill 只还原详情页尺寸，
 *   分屏不会回来，于是停在「手机版」的窄视频页。
 *
 * 现在所有判断都走这里，并且这个类可以脱离设备做穷举模拟测试
 * （见 tools/LayoutPolicyTest.java）。
 */
public final class LayoutPolicy {

    /** 普通详情页：上方视频小窗 + 下方简介/推荐 */
    public static final int MODE_DETAIL = 0;
    /** 横屏分屏：左画面 + 右简介/推荐（平板默认） */
    public static final int MODE_SPLIT = 1;
    /** 纯画面全屏 */
    public static final int MODE_FULL = 2;
    /** 竖屏铺满（沉浸式，B 站竖屏样式） */
    public static final int MODE_FILL = 3;

    /** 返回键：退出全屏 */
    public static final int BACK_EXIT_FULL = 0;
    /** 返回键：退出竖屏铺满 */
    public static final int BACK_EXIT_FILL = 1;
    /** 返回键：结束播放页（分屏 / 详情页都走这里） */
    public static final int BACK_FINISH = 2;

    private LayoutPolicy() { }

    /**
     * 竖屏铺满的唯一判定入口。
     * 四个条件缺一不可，尤其 split：分屏是横向布局，任何「把 holder 改成整屏」
     * 的动作都会把右栏挤没并触发 Surface 重建。
     */
    public static boolean canPortraitFill(boolean portraitVideo, boolean pref,
                                          boolean split, boolean fullscreen, boolean landscape) {
        return portraitVideo && pref && !split && !fullscreen && !landscape;
    }

    /** 进入播放页时应处的模式 */
    public static int targetMode(boolean landscape, boolean wide, boolean fullscreen,
                                 boolean portraitVideo, boolean pref) {
        if (fullscreen) return MODE_FULL;
        if (landscape) return wide ? MODE_SPLIT : MODE_FULL;
        return canPortraitFill(portraitVideo, pref, false, false, false) ? MODE_FILL : MODE_DETAIL;
    }

    /**
     * 换视频后的模式：分屏下必须留在分屏。
     * 旧版换视频会经过 exitPortraitFill / exitFullscreen，任何一次误判都会
     * 把分屏拆掉，用户看到的就是「点一下推荐，界面变成手机版」。
     */
    public static int modeAfterSwitch(int currentMode, boolean landscape, boolean wide,
                                      boolean portraitVideo, boolean pref) {
        if (currentMode == MODE_SPLIT) return MODE_SPLIT;
        if (currentMode == MODE_FULL) return MODE_FULL;
        return targetMode(landscape, wide, false, portraitVideo, pref);
    }

    /** 返回键语义 */
    public static int backAction(boolean split, boolean fullscreen, boolean fill) {
        if (fullscreen) return BACK_EXIT_FULL;
        if (fill) return BACK_EXIT_FILL;
        return BACK_FINISH;   // 分屏不再退回「手机版详情页」，直接离开播放页
    }

    /**
     * 分屏画面盒：按视频比例完整显示，上下（或左右）留黑边，不变形。
     * 关键约束：容器尺寸未知（0）时用兜底值，返回值永远 >= 2px ——
     * 0 宽/0 高的 Surface 既不出图也不会报错，只会「有声音没画面」。
     */
    public static int[] splitBox(int paneW, int paneH, float aspect) {
        int w = paneW > 0 ? paneW : 640;
        int h = paneH > 0 ? paneH : 360;
        float ar = sanitize(aspect, 16f / 9f);
        int vw = w;
        int vh = Math.round(w / ar);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * ar);
        }
        return new int[]{Math.max(2, vw), Math.max(2, vh)};
    }

    /** 竖屏铺满的画面盒：cover 裁切（不留黑边） */
    public static int[] coverBox(int boxW, int boxH, float aspect) {
        int w = boxW > 0 ? boxW : 1080;
        int h = boxH > 0 ? boxH : 1920;
        float ar = sanitize(aspect, 9f / 16f);
        float screenAr = w / (float) h;
        int vw, vh;
        if (screenAr < ar) {
            vh = h;
            vw = Math.round(h * ar);
        } else {
            vw = w;
            vh = Math.round(w / ar);
        }
        return new int[]{Math.max(2, vw), Math.max(2, vh)};
    }

    /** 宽高比合法性：0 / 负数 / 极端值一律回落到兜底，避免算出 0 尺寸 */
    public static float sanitize(float aspect, float fallback) {
        if (Float.isNaN(aspect) || Float.isInfinite(aspect)) return fallback;
        if (aspect < 0.2f || aspect > 5f) return fallback;
        return aspect;
    }

    /** 竖屏判定阈值：高比宽大 15% 以上才算竖屏 */
    public static boolean isPortraitAspect(float aspect) {
        return aspect > 0f && aspect < 1f / 1.15f;
    }

    /**
     * 起播海报是否应该贴出来。
     *
     * 【v4.21.0】旧版是「play() 里 post 一个贴图任务」，prepared 抢先时
     * 海报会在出画之后贴上来，然后一直盖到 8 秒兜底 —— 就是「黑屏但有声音」。
     * 现在这个任务执行前必须过这道闸：首帧已到 / 播放器已就绪 / 正在切清晰度，一律不贴。
     */
    public static boolean shouldShowPoster(boolean frameDone, boolean playerReady,
                                           boolean qualitySwitching, boolean switchPaused) {
        if (frameDone || playerReady) return false;
        if (qualitySwitching || switchPaused) return false;
        return true;
    }

    /**
     * 音轨起播位置：有明确断点用断点，否则对齐画面当前进度。
     * 负值（-1 = 没有断点）绝不能直接 seekTo，否则音轨会跳到上个视频的位置 → 音画不同步。
     */
    public static int audioStartTarget(int pending, int videoPos) {
        if (pending >= 0) return pending;
        return videoPos > 0 ? videoPos : 0;
    }
}
