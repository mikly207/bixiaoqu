package com.bixiaoqu.app;

/**
 * 【v4.38.0】流调优器 —— 在「分辨率自动」的前提下，专门解决三件事：
 *
 *   ① 弱网带宽骤降时反应太慢：旧估算 0.35 新 + 0.65 旧，降档和升档一样迟缓，
 *      网络从 10Mbps 掉到 1Mbps 时还按高码率拉流 → 卡顿 / 缓冲耗尽。
 *      这里改成「快降慢升」：掉速立刻跟下去（少卡），回升谨慎（不被一次快分片
 *      骗去冲高码率又卡死）。
 *
 *   ② 高分辨率（1080p）弱网单连接吃不满带宽：单条 TCP 受 BDP 限制，弱网下常
 *      只跑到链路带宽的一半。弱网 + 1080p 时在设备能力允许的范围内多开一段，
 *      把管道填满 —— 这是「分辨率不降也能更流畅」的关键。
 *
 *   ③ 超流畅档弱网要「不卡、不黑屏、不加载久」：起播保底压到扛得住的档、
 *      看门狗给足首帧时间（避免过早回退合流造成二次卡顿），但设硬上限防黑屏。
 *
 * 纯逻辑、不依赖 Android，可在 JVM 上直接单测。
 */
public class StreamTuner {

    /** 弱网判定线：低于此带宽认为「网络吃紧」，需要提速/保底 */
    public static final long WEAK_BPS = 2_500_000L;
    /** 1080p 需要的带宽：低于此值冲 1080p 高码率必卡 */
    public static final long HD1080_BPS = 3_500_000L;
    /** 带宽估算硬下限 / 上限 */
    public static final long FLOOR_BPS = 400_000L;
    public static final long CEIL_BPS = 100_000_000L;

    private StreamTuner() { }

    static long clamp(long v) {
        if (v < FLOOR_BPS) return FLOOR_BPS;
        if (v > CEIL_BPS) return CEIL_BPS;
        return v;
    }

    /**
     * 带宽平滑：快降慢升。
     * 掉速时新样本权重高（0.75），立刻降码率避免卡死；
     * 回升时新样本权重低（0.2），避免一次快分片把估算带飞、又冲高码率卡住。
     */
    public static long smoothBandwidth(long oldBps, long sampleBps) {
        if (sampleBps <= 0L) return oldBps <= 0L ? FLOOR_BPS : oldBps;
        if (oldBps <= 0L) return clamp(sampleBps);
        long next;
        if (sampleBps < oldBps) {
            next = (long) (oldBps * 0.25 + sampleBps * 0.75);
        } else {
            next = (long) (oldBps * 0.80 + sampleBps * 0.20);
        }
        return clamp(next);
    }

    /**
     * 弱网选轨保底：分辨率自动时，按实测带宽把目标高度压到「扛得住」的档，
     * 避免弱网去冲 1080p 高码率导致卡顿。分辨率仍是自动的（按带宽选），并非锁死低画质。
     *
     * @param autoH 自动决策已给出的高度（<=0 表示不限）
     * @param bw    实测带宽 bps
     * @return 保底后的高度；不改变本来就没有约束的情况
     */
    public static int rescueHeight(int autoH, long bw) {
        if (bw <= 0L) return autoH;                 // 带宽未知：不擅自压画质
        int byBw;
        if (bw < 800_000L) byBw = 360;
        else if (bw < WEAK_BPS) byBw = 480;
        else if (bw < HD1080_BPS) byBw = 720;
        else byBw = 0;                              // 带宽够：不限
        if (byBw <= 0) return autoH;
        if (autoH <= 0) return byBw;
        return Math.min(autoH, byBw);
    }

    /**
     * 并行段数决策。
     *
     * @param planSegs   调度档位的段数（0=自适应，1/2/3=指定）
     * @param deviceSegs 设备能力硬上限（1=老设备只能单段）
     * @param bw         实测带宽
     * @param height     目标清晰度高度
     * @return 实际段数（1~3）
     */
    public static int segsFor(int planSegs, int deviceSegs, long bw, int height) {
        int dev = deviceSegs < 1 ? 1 : (deviceSegs > 3 ? 3 : deviceSegs);
        int base = planSegs <= 0 ? dev : Math.min(planSegs, dev);
        if (base < 1) base = 1;
        // 弱网 + 1080p：单条连接受 BDP 限制吃不满带宽，但也不要开满 3 条去互抢。
        // 收敛到 2 段刚好填满管道：单段太慢、三段在弱网反而拥塞 + 多耗线程。
        // 设备能力是硬顶 —— 老设备（dev=1）只有单段，绝不硬开。
        if (bw > 0L && bw < HD1080_BPS && height >= 1080 && dev >= 2) {
            return 2;
        }
        return base;
    }

    /**
     * DASH 准备看门狗超时：弱网 / 超流畅档给足首帧时间，避免过早回退合流
     * （回退 = 重新取址 + 重新拉流 = 二次卡顿 / 黑屏感）。设硬上限防止真的拉不下来时干等。
     *
     * @param baseMs 基准超时
     * @param bw     实测带宽
     * @param smooth 是否超流畅档（老设备解码也可能慢，需要更多时间）
     */
    public static long watchdogMs(long baseMs, long bw, boolean smooth) {
        long v = baseMs;
        if (bw > 0L && bw < WEAK_BPS) v = Math.max(v, 10000L);
        if (smooth) v = Math.max(v, 12000L);
        if (v > 14000L) v = 14000L;       // 硬上限：再久没 prepared 就必须兜底，不能黑屏
        return v;
    }
}
