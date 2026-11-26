package com.bixiaoqu.app;

/**
 * 【v4.39.0】弹幕平滑时钟（纯逻辑，无 Android 依赖，可直接 JVM 单测）。
 *
 * 背景：旧版弹幕位置只来自播放器每 80ms 一次的采样，帧率被钉死在 ~12fps，
 * 两次采样之间完全静止，肉眼可见「一顿一顿」。
 *
 * 解决：把采样当作锚点，每个 vsync 帧用「锚点 + 速度 × 已流逝时间」外推出
 * 当前位置。本类只负责这套外推/速度估算/边界判定，不含任何 View 操作。
 *
 * 关键不变式：
 *  · 播放中（1x）：相邻采样间隔 T，位置每帧前进约 speed(=1) × 帧间隔，帧间平滑。
 *  · 倍速（2x）：采样差值是 1x 的两倍 → 估算出的 speed≈2，外推随之加快。
 *  · 暂停：采样位置不再前进 → speed 归零，弹幕静止。
 *  · 轮询异常中断：外推时间被 maxExtrapMs 夹住，不会「飞出屏幕」。
 *  · 回退/拖动：isSeekBack 为真，调用方清屏重定位。
 */
public final class DanmakuClock {

    private DanmakuClock() { }

    /**
     * 由锚点外推当前位置。
     * @param anchorMs   最近一次采样的媒体位置
     * @param speed      估算播放速度（1x≈1.0）
     * @param elapsedMs  距采样的墙钟流逝毫秒
     * @param maxExtrap  外推上限毫秒（防轮询中断飞出）
     */
    public static long extrapolate(long anchorMs, float speed, long elapsedMs, long maxExtrap) {
        if (elapsedMs < 0) elapsedMs = 0;
        if (maxExtrap > 0 && elapsedMs > maxExtrap) elapsedMs = maxExtrap;
        if (speed < 0f) speed = 0f;
        return anchorMs + (long) (speed * elapsedMs);
    }

    /**
     * 由相邻两次采样估算播放速度，并做指数平滑抑制抖动。
     * @param prev     上一次的速度
     * @param dMs      采样位置差（新 - 旧，毫秒）
     * @param dWallNs  采样墙钟差（纳秒）
     * @return 新速度；非法/静止时返回 0，异常大时保持原值
     */
    public static float estimateSpeed(float prev, long dMs, long dWallNs) {
        if (dWallNs <= 0) return prev;
        if (dMs <= 0) return 0f;
        float v = dMs * 1_000_000f / dWallNs;
        if (v < 0f || v > 4f) return prev;   // 异常值：忽略这次采样
        return prev * 0.4f + v * 0.6f;        // 平滑，抑制抖动
    }

    /** 采样位置未前进（暂停/缓冲） */
    public static boolean isFrozen(long ms, long anchorMs) {
        return anchorMs >= 0 && ms == anchorMs;
    }

    /** 明显回退（seek/拖动），阈值 1500ms */
    public static boolean isSeekBack(long ms, long anchorMs) {
        return anchorMs >= 0 && ms < anchorMs - 1500;
    }
}
