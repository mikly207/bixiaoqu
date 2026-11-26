package com.bixiaoqu.app;

/**
 * 【v4.35.0】播放调度档位 —— 把「这台机器 + 这条网络」该用多激进的参数，
 * 收成几个用户看得懂的名字，让用户自己选，而不是让程序偷偷猜。
 *
 * 为什么要有它：
 * 之前「流畅度」是散落在代码里的一堆隐式规则（设备分级、清晰度默认值、
 * 并行段数、连接池大小、预取开不开），用户只能通过「改画质」间接影响，
 * 而且改了画质又被「用户手动改过就不自动降档」这条规则反过来锁死 ——
 * 结果就是老设备上唯一的办法是手动一档一档试。
 *
 * 现在把这些规则收成四档 + 一档自定义：
 *
 *   超流畅 —— 老旧设备 / 网络差时用。清晰度封顶 480p、单段串行、不预取、
 *            连接池收窄、允许卡顿时自动降档。目标只有一个：不卡。
 *   超清晰 —— 性能稍好 + 网络很快时用。清晰度不封顶、多段并行、预取拉满、
 *            不自动降档（用户要的就是清晰，别偷偷改）。目标：爽。
 *   平衡   —— 默认推荐档。兼顾流畅与清晰：清晰度封顶 1080p、并行段数按设备
 *            能力自适应、预取开、允许自动降档。
 *   节能   —— 最低需求。360p、单段、不预取、连接池最小，只求不卡。
 *   自定义 —— 逐项自己定。
 *
 * 这个类**不依赖 Android**，是纯逻辑，可以直接跑单测；
 * 读取用户设置、落盘由 {@link Prefs} 负责。
 */
public class ScheduleProfile {

    /** 自定义（逐项手调） */
    public static final int MODE_CUSTOM = 0;
    /** 超流畅：老设备 / 网络差，优先不卡 */
    public static final int MODE_SMOOTH = 1;
    /** 超清晰：好设备 + 快网络，优先清晰 */
    public static final int MODE_SHARP = 2;
    /** 平衡（推荐）：兼顾流畅与清晰 */
    public static final int MODE_BALANCED = 3;
    /** 节能：最低需求，只求不卡 */
    public static final int MODE_SAVER = 4;

    public static final int MODE_COUNT = 5;

    public static final String[] NAMES = {
            "自定义", "超流畅", "超清晰", "平衡（推荐）", "节能"
    };

    /** 每个档位的一句话说明，设置页里直接展示 */
    public static final String[] HINTS = {
            "逐项自己定",
            "老设备 / 网络差 · 优先不卡",
            "好设备 + 快网络 · 优先清晰",
            "默认档 · 兼顾流畅与清晰",
            "最低需求 · 只求不卡"
    };

    /** 一份完整的调度方案（不可变） */
    public static final class Plan {
        /** 档位编号 */
        public final int mode;
        /** 清晰度上限（像素高度），0 = 不限制 */
        public final int maxHeight;
        /** 用户没手动选画质时采用的清晰度（像素高度） */
        public final int impliedHeight;
        /** 请求 playurl 时用的 qn 上限 */
        public final int maxQn;
        /** 并行分段数，0 = 按设备能力自适应 */
        public final int segs;
        /** 是否预取下一条 / 首片预热 */
        public final boolean prefetch;
        /** 弹幕默认值 */
        public final boolean danmaku;
        /** 卡顿时是否允许自动降档 */
        public final boolean autoDowngrade;
        /** HTTP keep-alive 连接池上限 */
        public final int connPool;
        /** IO 线程数上限 */
        public final int ioThreads;

        Plan(int mode, int maxHeight, int impliedHeight, int maxQn, int segs,
             boolean prefetch, boolean danmaku, boolean autoDowngrade,
             int connPool, int ioThreads) {
            this.mode = mode;
            this.maxHeight = maxHeight;
            this.impliedHeight = impliedHeight;
            this.maxQn = maxQn;
            this.segs = segs;
            this.prefetch = prefetch;
            this.danmaku = danmaku;
            this.autoDowngrade = autoDowngrade;
            this.connPool = connPool;
            this.ioThreads = ioThreads;
        }

        @Override public String toString() {
            return "Plan{" + NAMES[mode < NAMES.length ? mode : 0]
                    + ", h=" + maxHeight + ", qn=" + maxQn + ", segs=" + segs
                    + ", pre=" + prefetch + ", dm=" + danmaku
                    + ", down=" + autoDowngrade + ", pool=" + connPool + "}";
        }
    }

    private ScheduleProfile() { }

    /** 四个预设档 */
    public static Plan preset(int mode) {
        switch (mode) {
            case MODE_SMOOTH:
                // 老设备 / 弱网：480p 封顶、单段、不预取；连接够用即可（弱网不在池多，在多段填满管道）
                return new Plan(mode, 480, 480, 32, 1, false, false, true, 10, 5);
            case MODE_SHARP:
                // 好设备 + 快网：不封顶、并行段数交给设备能力、预取拉满、不自动降档；池子拉满吃满带宽
                return new Plan(mode, 0, 1080, 120, 0, true, true, false, 24, 12);
            case MODE_BALANCED:
                // 默认档：1080p 封顶、并行段数自适应、预取开、允许自动降档
                return new Plan(mode, 1080, 720, 116, 0, true, false, true, 18, 10);
            case MODE_SAVER:
                // 最低需求：360p、单段、不预取、连接池最小
                return new Plan(mode, 360, 360, 16, 1, false, false, true, 6, 3);
            default:
                return null;
        }
    }

    /** 自定义档：把用户逐项选的值包成一份方案 */
    public static Plan custom(int height, int segs, boolean prefetch,
                              boolean danmaku, boolean autoDowngrade) {
        int h = height <= 0 ? 0 : height;
        int sg = segs <= 0 ? 0 : Math.min(segs, 3);
        int qn = qnForHeight(h);
        return new Plan(MODE_CUSTOM, h, h > 0 ? h : 720, qn, sg,
                prefetch, danmaku, autoDowngrade,
                poolFor(sg, prefetch), ioFor(sg, prefetch));
    }

    /**
     * 统一的取方案入口。
     *
     * @param mode 档位；非预设档（含越界值）一律落到「平衡」，
     *              保证任何脏数据都不会让 App 变成「没有方案可用」。
     */
    public static Plan planOf(int mode, int cHeight, int cSegs, boolean cPrefetch,
                              boolean cDanmaku, boolean cDowngrade) {
        if (mode == MODE_CUSTOM) {
            return custom(cHeight, cSegs, cPrefetch, cDanmaku, cDowngrade);
        }
        Plan p = preset(mode);
        return p == null ? preset(MODE_BALANCED) : p;
    }

    /** 高度 → playurl 的 qn */
    public static int qnForHeight(int h) {
        if (h <= 0) return 120;      // 不限 → 最高可用
        if (h >= 2160) return 120;
        if (h >= 1440) return 116;
        if (h >= 1080) return 116;
        if (h >= 720) return 64;
        if (h >= 480) return 32;
        return 16;
    }

    /** 并行段数 → 连接池大小：段数越多，需要的并发连接越多 */
    public static int poolFor(int segs, boolean prefetch) {
        int base = segs <= 0 ? 18 : Math.max(6, 6 + segs * 4);
        return prefetch ? Math.min(24, base) : Math.max(4, base - 4);
    }

    /** 并行段数 → IO 线程数（代理的转发线程池上限 = 这里 × 2） */
    public static int ioFor(int segs, boolean prefetch) {
        int base = segs <= 0 ? 10 : Math.max(3, 2 + segs * 2);
        return prefetch ? Math.min(12, base) : Math.max(2, base - 2);
    }

    /**
     * 最终并行段数 = 档位要求 与 设备能力 取小。
     *
     * 为什么不直接用档位里的数字：
     * 「超清晰」在老设备上开 3 条连接，等于 3 个线程 + 3 个 socket + 3 份 TLS 状态
     * 去抢一颗弱 CPU，表现反而是「越并行越卡」。所以设备能力是硬上限，
     * 档位只能在它之下挑 —— 用户选了超清晰也只会在**好设备**上真正拿到 3 段。
     */
    public static int effectiveSegs(Plan p, int deviceSegs) {
        if (p == null) return deviceSegs < 1 ? 1 : deviceSegs;
        int dev = deviceSegs < 1 ? 1 : deviceSegs;
        if (p.segs <= 0) return dev;
        return Math.max(1, Math.min(p.segs, dev));
    }

    /** 档位名（越界回落到「平衡」） */
    public static String nameOf(int mode) {
        return NAMES[(mode >= 0 && mode < NAMES.length) ? mode : MODE_BALANCED];
    }

    /** 档位说明（越界回落到「平衡」） */
    public static String hintOf(int mode) {
        return HINTS[(mode >= 0 && mode < HINTS.length) ? mode : MODE_BALANCED];
    }
}
