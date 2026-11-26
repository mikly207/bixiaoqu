package com.bixiaoqu.app;

import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 【v4.14.7】统一 Q 弹动画工具。
 *
 * 提供一组「有蓄力、有回弹、有余振」的弹性动画，让全 App 交互更 Q 弹有活力：
 *   - pop()     : 点击按下—弹起，带 overshoot 回弹（按钮 / 图标 / 标签）
 *   - pressIn() : 卡片按下缩小蓄力
 *   - release() : 抬手回弹归位（配合 pressIn）
 *   - enterUp() : 内容从下方带回弹地入场
 *   - spring()  : 生成高张力弹簧插值器（API11+，API22 通用）
 *
 * 全部基于 ViewPropertyAnimator + 系统 Interpolator，无第三方依赖。
 */
public final class Bounce {

    /** 高张力弹簧插值器：overshoot 明显、回弹干脆 */
    public static Interpolator spring(float tension) {
        return new OvershootInterpolator(tension);
    }

    /** 蓄力—弹起插值器：先反向蓄力再冲过头，最有「Q」的手感 */
    public static Interpolator springWithAnticipation(float tension) {
        return new AnticipateOvershootInterpolator(1.25f, tension);
    }

    /**
     * 点击弹一下：快速放大蓄力，再带明显 overshoot 弹回原尺寸。
     * @param v      目标视图
     * @param amount 放大峰值，例如 1.28f
     */
    public static void pop(View v, float amount) {
        if (v == null) return;
        try {
            v.animate().cancel();
            v.animate().scaleX(amount).scaleY(amount).setDuration(100)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            try {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(430)
                                        .setInterpolator(spring(4.2f)).start();
                            } catch (Throwable ignore) { }
                        }
                    }).start();
        } catch (Throwable ignore) { }
    }

    /** 卡片按下：轻微缩小蓄力，短促 */
    public static void pressIn(View v) {
        if (v == null) return;
        try {
            v.animate().cancel();
            v.animate().scaleX(0.945f).scaleY(0.945f).setDuration(90)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } catch (Throwable ignore) { }
    }

    /** 卡片抬手：带 overshoot 弹回 1，产生余振 */
    public static void release(View v) {
        if (v == null) return;
        try {
            v.animate().cancel();
            v.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(springWithAnticipation(3.4f)).start();
        } catch (Throwable ignore) { }
    }

    /**
     * 内容入场：从下方 + 略微缩小，带回弹地滑入。
     */
    public static void enterUp(View v, float distancePx) {
        if (v == null) return;
        try {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(distancePx);
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(420).setInterpolator(spring(2.2f)).start();
        } catch (Throwable ignore) { }
    }


    private Bounce() { }
}
