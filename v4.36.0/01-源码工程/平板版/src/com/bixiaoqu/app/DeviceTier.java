package com.bixiaoqu.app;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

/**
 * 【v4.34.0】设备能力分级 —— 纯逻辑 + 少量系统查询，可单测（判定部分）。
 *
 * 为什么要它：
 * 「1080p 音画不同步 / 卡顿」在不同机型上的表现完全不一样：
 *   · 老机型（Android 5~6、2G 内存、四核）：软件解 1080p 直接跑不动，
 *     解码器跟不上 → 视频轨缓冲反复耗尽 → 声音继续往前跑 → 音画分离；
 *   · 中端机：能解 1080p，但多实例解码 + 代理多线程会互相抢 CPU，
 *     表现为「能播但一顿一顿」；
 *   · 高端机：基本没问题。
 *
 * 所以要按设备把「能承受的上限」算出来，在**用户允许自动降级**的前提下
 * 提前压一档，而不是等播到一半卡住再救火。
 *
 * 判定只依赖四项：系统版本、内存、CPU 核心数、有没有 AVC 硬解。
 */
public class DeviceTier {

    /** 低端机：建议最高 480p，且优先走合流模式 */
    public static final int TIER_LOW = 0;
    /** 中端机：建议最高 720p */
    public static final int TIER_MID = 1;
    /** 高端机：允许 1080p */
    public static final int TIER_HIGH = 2;

    /** 缓存：分级结果与上限（进程内只算一次） */
    private static volatile int cachedTier = -1;
    private static volatile int cachedMaxHeight = 0;
    private static volatile boolean cachedAvc = false;

    private DeviceTier() { }

    /**
     * 设备分级。
     *
     * @param memMb   可用物理内存（MB），<= 0 表示查不到
     * @param cores   CPU 核心数，<= 0 表示查不到
     * @param sdk     Android API 等级
     * @param avc     是否有 AVC 硬解器
     */
    public static int tierOf(int memMb, int cores, int sdk, boolean avc) {
        if (!avc) return TIER_LOW;                 // 只能软解 1080p → 必卡
        if (sdk <= 22) return TIER_LOW;            // Android 5.1 及以前：解码器能力普遍弱
        if (memMb > 0 && memMb <= 1536) return TIER_LOW;   // ≤1.5G
        if (cores > 0 && cores <= 4 && memMb > 0 && memMb <= 3072) {
            return TIER_MID;                       // 4 核 + 内存不大
        }
        if (memMb > 0 && memMb <= 3072) return TIER_MID;
        if (cores > 0 && cores <= 4) return TIER_MID;
        return TIER_HIGH;
    }

    /**
     * 该设备建议的最高清晰度（像素高度）。
     *
     * 分级只是一刀切的粗判，再按内存/CPU 细分：
     */
    public static int maxHeightOf(int tier, int memMb, int cores, int sdk) {
        int h;
        switch (tier) {
            case TIER_LOW:  h = 480; break;
            case TIER_MID:  h = 720; break;
            default:        h = 1080; break;
        }
        // 内存特别小（≤1G）再压一档
        if (memMb > 0 && memMb <= 1024 && h > 360) h = 360;
        // Android 5.x 上 720p 也偶发吃力，压到 480p 更稳
        if (sdk <= 22 && h > 480) h = 480;
        return h;
    }

    /** 设备分级（带缓存） */
    public static int tier(Context c) {
        int t = cachedTier;
        if (t < 0) {
            t = tierOf(memMb(c), cores(), sdk(), hasAvcDecoder());
            cachedTier = t;
        }
        return t;
    }

    /** 建议的最高清晰度（带缓存） */
    public static int maxHeight(Context c) {
        if (cachedMaxHeight <= 0) {
            cachedMaxHeight = maxHeightOf(tier(c), memMb(c), cores(), sdk());
        }
        return cachedMaxHeight;
    }

    /** 是否有 AVC 硬解器 */
    public static boolean hasAvcDecoder() {
        if (cachedAvc) return true;
        boolean ok = false;
        boolean queriedOk = false;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaCodecInfo[] infos = list.getCodecInfos();
            queriedOk = true;
            for (int i = 0; infos != null && i < infos.length; i++) {
                MediaCodecInfo ci = infos[i];
                if (ci == null) continue;
                if (!ci.isEncoder()) {
                    String[] types = ci.getSupportedTypes();
                    for (int j = 0; types != null && j < types.length; j++) {
                        if (types[j] != null && types[j].toLowerCase().contains("avc")) {
                            ok = true;
                            break;
                        }
                    }
                }
                if (ok) break;
            }
        } catch (Throwable ignore) { }
        queried = queriedOk;
        // 查不到（老系统 MediaCodecList 不可用）不武断判死：按"有硬解"处理，
        // 交给运行时的卡顿降档兜底
        cachedAvc = ok || !queriedOk;
        return cachedAvc;
    }

    /** 标记 MediaCodecList 是否查询成功过（失败时不据此判死） */
    private static volatile boolean queried = false;

    private static int sdk() {
        try { return Build.VERSION.SDK_INT; } catch (Throwable ignore) { return 22; }
    }

    private static int cores() {
        try { return Runtime.getRuntime().availableProcessors(); } catch (Throwable ignore) { return 0; }
    }

    /** 物理内存（MB） */
    public static int memMb(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long total = mi.totalMem;
            if (total <= 0) total = mi.availMem;   // 老系统拿不到 totalMem 时退回可用值
            if (total <= 0) return 0;
            return (int) (total / (1024 * 1024));
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /**
     * 【v4.34.0】该设备适合的并行分段数。
     *
     * 并行拉流用「多开连接」换吞吐，但收益只在网络与 CPU 都跟得上时成立：
     * 老设备上 3 条连接 = 3 个线程 + 3 个 socket + 3 份 TLS 状态，
     * CPU 先被吃光，表现反而是「越并行越卡」。
     *   低端 → 1 段（走单连接，行为与 v4.29 一致）
     *   中端 → 2 段
     *   高端 → 3 段
     */
    public static int parallelSegs(Context c) {
        int t = tier(c);
        if (t == TIER_LOW) return 1;
        if (t == TIER_MID) return 2;
        return 3;
    }

    /** 供测试用：重置缓存 */
    public static void resetForTest() {
        cachedTier = -1;
        cachedMaxHeight = 0;
        cachedAvc = false;
        queried = false;
    }
}
