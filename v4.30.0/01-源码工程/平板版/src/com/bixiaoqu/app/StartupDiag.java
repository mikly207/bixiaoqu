package com.bixiaoqu.app;

/**
 * 【v4.22.0】起播诊断 + 自适应清晰度。
 *
 * 用户反馈「加载慢」其实有两种完全不同的原因，混在一起就永远调不对：
 *
 *  ① <b>网络拉得慢</b>：点开 → 拿到播放地址就要 1.5 秒以上，或者代理层实测吞吐
 *     撑不住当前档位的码率。对症的是「降码率 / 换线路」，降分辨率并不会更快，
 *     反而白丢画质。
 *  ② <b>硬件解码慢</b>：地址秒回（&lt;1.2s），但首帧迟迟不出（&gt;2.2s）。
 *     这多半是 4K/2K 走了软解，或同时开了弹幕重绘 + 高分辨率解码，GPU 顶不住。
 *     对症的是「降分辨率 / 关弹幕」，这时候再怎么加带宽都没用。
 *
 * 这里只做纯计算（不碰 Android API），方便在 JVM 上跑穷举测试；
 * 打点由 PlayActivity 调用 onClick/onFetch/onFrame 完成。
 */
public class StartupDiag {

    public static final int OK = 0;
    public static final int NET_SLOW = 1;
    public static final int DECODE_SLOW = 2;
    public static final int BOTH = 3;

    /** 点开 → 取得播放地址，超过这个值判「网络慢」 */
    public static final long NET_SLOW_MS = 1400L;
    /** 取得地址 → 首帧，超过这个值判「解码慢」（前提是取址本身不慢） */
    public static final long DECODE_SLOW_MS = 2200L;

    /** 连续多少次同样的判定才认账（防止单次抖动就降码率） */
    static final int CONFIRM = 2;

    static volatile long clickAt = 0L;
    static volatile long fetchAt = 0L;
    static volatile long netMs = -1L;
    static volatile long decodeMs = -1L;
    static volatile int verdict = OK;
    static volatile int slowStreak = 0;
    static volatile int okStreak = 0;
    /** 自动降码率在当前网络下的目标高度上限，0 表示不限 */
    static volatile int autoCap = 0;

    /** 最近 8 次「首帧总耗时」，用于设置页展示与排障 */
    static final long[] HIST = new long[8];
    static int histN = 0;
    static int histI = 0;

    static long now() {
        try { return android.os.SystemClock.uptimeMillis(); } catch (Throwable t) { return 0L; }
    }

    /** 换片/点开瞬间打点 */
    public static void onClick() {
        clickAt = now();
        fetchAt = 0L;
        netMs = -1L;
    }

    /** 拿到播放地址（pickVideo 成功那一刻）打点 */
    public static void onFetch() {
        if (clickAt <= 0L) return;
        long t = now();
        if (t <= 0L) return;
        fetchAt = t;
        long d = t - clickAt;
        // 上限保护：后台挂起/息屏回来会算出几万毫秒，别让它污染判定
        netMs = Math.min(d, 60000L);
    }

    /** 首帧回调打点。totalMs = 点开 → 首帧；netMs 已知时反推解码耗时。 */
    public static void onFrame(long totalMs) {
        if (totalMs <= 0L) return;
        // 没有取址打点（合流/本地文件路径）→ 只留样本，不做「网络慢」判定，
        // 否则会把「合流首帧稍慢」误判成弱网，白白降用户的画质。
        if (netMs < 0L) {
            push(totalMs);
            return;
        }
        long net = netMs;
        long dec = Math.max(0L, totalMs - net);
        decodeMs = dec;
        push(totalMs);
        int v = classify(net, dec);
        verdict = v;
        if (v == OK) {
            okStreak++;
            slowStreak = 0;
            if (okStreak >= CONFIRM) autoCap = 0;      // 连续两次正常 → 解除限档
        } else {
            slowStreak++;
            okStreak = 0;
            if (slowStreak >= CONFIRM) {
                int cap;
                if (v == NET_SLOW) cap = 720;
                else if (v == DECODE_SLOW) cap = 1080;
                else cap = 720;
                // 只降不升：同一次弱网里不要来回跳档
                autoCap = (autoCap <= 0) ? cap : Math.min(autoCap, cap);
            }
        }
    }

    /** 仅供测试：直接注入一次「取址耗时 + 首帧总耗时」 */
    static void feedForTest(long net, long total) {
        netMs = net;
        onFrame(total);
    }

    /** 仅供测试：清空所有状态 */
    static void resetForTest() {
        clickAt = 0L;
        fetchAt = 0L;
        netMs = -1L;
        decodeMs = -1L;
        verdict = OK;
        slowStreak = 0;
        okStreak = 0;
        autoCap = 0;
        histN = 0;
        histI = 0;
        for (int i = 0; i < HIST.length; i++) HIST[i] = 0L;
    }

    static void push(long v) {
        HIST[histI] = v;
        histI = (histI + 1) % HIST.length;
        if (histN < HIST.length) histN++;
    }

    public static int verdict() { return verdict; }

    public static long lastNetMs() { return netMs; }

    public static long lastDecodeMs() { return decodeMs; }

    public static int autoCap() { return autoCap; }

    public static String verdictName(int v) {
        switch (v) {
            case NET_SLOW: return "网络拉流慢";
            case DECODE_SLOW: return "硬件解码慢";
            case BOTH: return "网络与解码都慢";
            default: return "正常";
        }
    }

    /** 最近若干次首帧的平均耗时（ms），没有样本返回 0 */
    public static long avgFrameMs() {
        if (histN <= 0) return 0L;
        long s = 0;
        for (int i = 0; i < histN; i++) s += HIST[i];
        return s / histN;
    }

    /** 诊断一句话，写进日志/设置页 */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(verdictName(verdict));
        if (netMs >= 0L) sb.append(" · 取址 ").append(netMs).append("ms");
        if (decodeMs >= 0L) sb.append(" · 首帧 ").append(decodeMs).append("ms");
        if (autoCap > 0) sb.append(" · 已限 ").append(autoCap).append("P");
        return sb.toString();
    }

    // ------------------------------------------------------------ 纯逻辑

    /**
     * 判定起播慢在哪一环。
     *
     * @param netMs    点开 → 取到播放地址
     * @param decodeMs 取到地址 → 首帧
     */
    public static int classify(long netMs, long decodeMs) {
        boolean net = netMs >= NET_SLOW_MS;
        // 取址慢的时候首帧自然晚，不能重复算成「解码慢」——
        // 否则弱网会被判成 BOTH，白白降分辨率也救不回来。
        boolean dec = !net && decodeMs >= DECODE_SLOW_MS;
        if (net && decodeMs >= DECODE_SLOW_MS * 2 && netMs < NET_SLOW_MS * 2) return BOTH;
        if (net) return NET_SLOW;
        if (dec) return DECODE_SLOW;
        return OK;
    }

    /**
     * 「自动」档位下按实测带宽给一个目标高度。
     *
     * 只用码率硬约束（1080P≈3Mbps / 720P≈1.5Mbps / 480P≈0.8Mbps），
     * 并在连续确认弱网时再压一档。用户手选档位时本方法不参与（尊重选择），
     * 由调用方决定是否采用。
     */
    public static int adaptHeight(int want, long bps) {
        int cap = 0;
        if (bps > 0L && bps < 1_200_000L) cap = 480;
        else if (bps > 0L && bps < 2_500_000L) cap = 720;
        if (autoCap > 0) cap = (cap <= 0) ? autoCap : Math.min(cap, autoCap);
        if (cap <= 0) return want;
        if (want <= 0) return cap;              // 自动档：直接用约束值
        return Math.min(want, cap);             // 手选档：只做「不超过」的限幅
    }
}
