package com.bixiaoqu.app;

/**
 * 【v4.37.0】加载调速器 —— 在「分辨率自动」的前提下提升加载速度、减少卡顿。
 *
 * 背景：
 *  旧版把默认画质写死成 720P，用它来「换」首帧速度。这既不是真正的自动分辨率，
 *  也没有利用上已经算好的实测带宽（StartupDiag.adaptHeight 一直写好却没接线）。
 *  本类把散落的判断收敛成纯决策，不依赖 Android，可在 JVM 上直接单测：
 *
 *   1. 自动档目标高度：按实测带宽挑「扛得住的最高档」，快网给高清、弱网给流畅，
 *      再把设备能力 / 调度档位上限作为硬顶，绝不盲目冲到软解 4K。
 *   2. 起播保护窗口：首帧到达后保留多长的「只让视频/音轨占带宽」时间。
 *      窗口越长越稳但连滑越慢；这里按实测首帧耗时自适应 —— 快网短、弱网长。
 *
 *  所有「提速」都不通过锁死低分辨率实现：分辨率交给自动 + 带宽自适应，
 *  提速来自更早释放被让路的请求、以及避免首帧去抢扛不住的高码率轨。
 */
public class LoadTuner {

    /** 首帧后让路窗口：快网最短、慢网最长（毫秒） */
    public static final long HOLD_FAST = 500L;
    public static final long HOLD_NORMAL = 850L;
    public static final long HOLD_SLOW = 1200L;

    /** 快网判定：首帧总耗时低于此值 */
    public static final long FRAME_FAST_MS = 1200L;
    /** 慢网判定：首帧总耗时高于此值 */
    public static final long FRAME_SLOW_MS = 2200L;

    private LoadTuner() { }

    /**
     * 自动档下的目标高度。
     *
     * @param want       用户/档位给的目标（<=0 表示自动）
     * @param bandwidth  实测带宽 bps（<=0 表示未知，用保守默认值）
     * @param cap        设备 / 调度档位 / 诊断的硬顶（<=0 表示不限）
     * @return 目标高度；0 表示「不限，按最高可用」。永远不会超过 cap（cap>0 时）。
     */
    public static int autoTargetHeight(int want, long bandwidth, int cap) {
        int h = StartupDiag.adaptHeight(want, bandwidth);
        if (cap > 0) {
            if (h <= 0) h = cap;                 // 自动 + 有硬顶 → 取硬顶
            else h = Math.min(h, cap);
        }
        return h;
    }

    /**
     * 首帧到达后的让路窗口长度（毫秒）。
     * 首帧越快，说明链路越宽裕，预取/详情/弹幕越早恢复并行 → 连滑下一条更快；
     * 首帧慢则多保护一会儿，避免二次抢占把刚起播的视频再次拖卡。
     *
     * @param frameMs 点开 → 首帧总耗时（<0 表示未知，取普通档）
     */
    public static long holdAfterFrame(long frameMs) {
        if (frameMs < 0L) return HOLD_NORMAL;
        if (frameMs <= FRAME_FAST_MS) return HOLD_FAST;
        if (frameMs >= FRAME_SLOW_MS) return HOLD_SLOW;
        return HOLD_NORMAL;
    }
}
