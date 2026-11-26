package com.bixiaoqu.app;

/**
 * 【v4.30.0】并行分段下载的区间规划 —— 纯计算，不碰 Android API，可单独单测。
 *
 * 背景：单条 TCP 的吞吐受 BDP（带宽 × 往返时延）限制。在 4G/弱网下，
 * 一条连接常常只能跑到链路带宽的一半左右 —— 这就是「换个 App 就快、
 * 这里一直转圈」的真正原因：不是带宽不够，是**一条连接吃不满**。
 *
 * 拆成 k 条连接各拉一段，每条独立慢启动，合起来才能把管道填满。
 * 这里只负责把 [start, start+len) 均匀切开，且保证：
 *   · 段与段首尾相接、无空洞、无重叠；
 *   · 最后一段的末尾正好是 start+len-1（不会多要一个字节，
 *     否则 CDN 会返回 416）；
 *   · 段数不超过 3（再多对 CDN 不礼貌，也容易触发同资源并发风控）。
 */
final class ParallelPlan {

    /** 最多拆几段：3 段在移动网络上已经能拿到 1.8~2.6 倍，再拆收益递减、风控风险上升 */
    static final int MAX_SEGS = 3;

    /** 小于这个长度不值得拆：握手/调度开销会吃掉并行收益 */
    static final long MIN_BYTES = 4L * 1024 * 1024;

    private ParallelPlan() {
    }

    /** 是否值得为这条请求开多连接：够长 + 候选线路够多（不同 host 才能分摊风控风险） */
    static boolean worth(long len, int candidates) {
        return len >= MIN_BYTES && candidates >= 2;
    }

    /**
     * 把 [start, start+len) 切成 k 段，返回每段 {from, to} 的闭区间。
     * k 会被夹到 [2, MAX_SEGS]；len 太小（小于 k）时退化成单段，绝不产生空区间。
     */
    static long[][] split(long start, long len, int k) {
        if (len <= 0) return new long[][]{{start, start - 1L}};   // 空区间，调用方不会读到数据
        if (k > MAX_SEGS) k = MAX_SEGS;
        if (k < 2 || len < k) {
            return new long[][]{{start, start + Math.max(0L, len - 1L)}};
        }
        long[][] out = new long[k][2];
        long per = len / k;
        long from = start;
        for (int i = 0; i < k; i++) {
            // 前 k-1 段等长，余数全部给最后一段 —— 这样无论 len 是否能整除，
            // 末段都恰好收在 start+len-1，覆盖完整且不越界。
            long to = (i == k - 1) ? (start + len - 1L) : (from + per - 1L);
            out[i][0] = from;
            out[i][1] = to;
            from = to + 1L;
        }
        return out;
    }

    /**
     * Content-Range: bytes 123-456/789 → 段起点是不是 123。
     * 并行拉流必须校验它：有的 CDN 会忽略 Range 直接回 200 整包，
     * 那种响应拿到的不是我们要的那一段，拼进去就是一段错位数据。
     */
    static boolean rangeStartsAt(String cr, long from) {
        if (cr == null) return false;
        try {
            int sp = cr.indexOf(' ');
            if (sp < 0) return false;
            int dash = cr.indexOf('-', sp);
            if (dash < 0) return false;
            return Long.parseLong(cr.substring(sp + 1, dash).trim()) == from;
        } catch (Throwable t) {
            return false;
        }
    }
}
