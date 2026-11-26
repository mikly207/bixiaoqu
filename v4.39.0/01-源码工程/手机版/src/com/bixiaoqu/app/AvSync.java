package com.bixiaoqu.app;

/**
 * 【v4.34.0】音画同步策略 —— 纯逻辑，不依赖任何 Android 类，可直接单测。
 *
 * 背景：DASH 是音视频分离的两条流，画面走 VideoView(MediaPlayer)，声音走
 * 另一个 MediaPlayer。两个播放器的时钟天然独立：
 *   · 音频轨很小，永远先到，永远在跑；
 *   · 视频轨重（1080p），网络抖动时缓冲耗尽会 **停住**（position 不前进）；
 * 于是「画面卡住、声音继续往前」= 用户听到的「音画不同步」。
 *
 * 旧版的对策很粗：每 2.5 秒比一次位置，差超过 0.5 秒就把音频 seek 回去。
 * 问题有三个：
 *   ① 阈值 500ms —— 人耳在 200ms 以上就能察觉，这段时间里用户一直听着不同步；
 *   ② 频率 2.5 秒 —— 漂移在两次校正之间能跑到 3 秒；
 *   ③ 用 seek 纠偏 —— 音频流 seek 会整段重新缓冲，老设备上就是一次卡顿，
 *      卡顿 → 漂移 → 再 seek → 更卡，恶性循环。
 *
 * 现在的策略（业界通用做法，ExoPlayer 同款思路）：
 *   · 小漂移（≤ TIGHT_MS）  → 不动，听不出来；
 *   · 中漂移（< SEEK_MS）    → 微调音频速率把它**渐进**拉回来，绝不 seek；
 *   · 大漂移（≥ SEEK_MS）    → 硬对齐（seek），否则会永久失步；
 *   · 视频正在缓冲（stall）  → 冻结音频，等画面恢复再一起对齐。
 */
public class AvSync {

    /** 完全同步：偏差在听感阈值内，什么都不做 */
    public static final int OK = 0;
    /** 小漂移：靠微调速率渐进拉回，不 seek */
    public static final int DRIFT = 1;
    /** 大漂移：必须硬对齐，否则会永久失步 */
    public static final int RESYNC = 2;

    /**
     * 听感阈值（毫秒）。
     * 人耳对「声音比画面早」的感知下限约 45ms，对「晚」约 125ms；
     * 取 200ms 是留了安全余量，低于它再去动音频只会引入更多抖动。
     */
    public static final int TIGHT_MS = 200;

    /**
     * 硬对齐阈值（毫秒）。
     * 渐进修正速率最多只有 6%，追 500ms 的漂移要 8 秒多 —— 期间用户一直听着
     * 不同步。超过 550ms 直接 seek，一步到位。
     */
    public static final int SEEK_MS = 550;

    /** 速率修正的最大幅度 */
    public static final float MAX_K = 0.06f;

    private AvSync() { }

    /**
     * 判定音画漂移等级。
     *
     * @param audioPos 音频当前位置（ms）
     * @param videoPos 画面当前位置（ms）
     */
    public static int classify(int audioPos, int videoPos) {
        int diff = audioPos - videoPos;
        int ad = diff < 0 ? -diff : diff;
        if (ad <= TIGHT_MS) return OK;
        return ad > SEEK_MS ? RESYNC : DRIFT;
    }

    /**
     * 渐进拉回用的速率。
     *
     * 音频超前（audioPos > videoPos）→ 减速；音频落后 → 加速。
     * 幅度按漂移量分档：漂移越大追得越快，但不超过 MAX_K。
     *
     * @param base 用户设置的倍速（1.0 / 1.25 / 2.0 …）
     */
    public static float correctedRate(float base, int audioPos, int videoPos) {
        float b = base <= 0 ? 1f : base;
        int diff = audioPos - videoPos;
        int ad = diff < 0 ? -diff : diff;
        // 漂移越大，追赶用的相对速率差越大：350ms 以内 3%，以上 6%
        float k = ad <= 350 ? 0.03f : MAX_K;
        float r = diff > 0 ? (b * (1f - k)) : (b * (1f + k));
        if (r < 0.5f) r = 0.5f;
        if (r > 3.0f) r = 3.0f;
        return r;
    }

    /**
     * 是否值得为这个漂移去动音频。
     * RESYNC 之外都算「值得」（OK 时也会被调用一次，只是返回原倍速）。
     */
    public static boolean worthAct(int audioPos, int videoPos) {
        return classify(audioPos, videoPos) != OK;
    }

    /**
     * 视频是否卡住（缓冲耗尽）。
     *
     * 判据：连续两次采样之间位置几乎没有前进（≤ stepMs）。
     * 用「相对上一次」而不是绝对值，避免刚起播时 position=0 被误判。
     *
     * @param lastPos 上一次采样的位置
     * @param nowPos  本次采样的位置
     * @param stepMs  两次采样之间允许的最小前进量（一般取采样间隔的一半）
     */
    public static boolean stalled(int lastPos, int nowPos, int stepMs) {
        return nowPos - lastPos <= stepMs;
    }

    /**
     * 连续卡顿判定：streak 达到阈值才算数。
     * 单次不前进可能是正常的解码抖动（I 帧间隔、seek），连续多次才是真卡。
     */
    public static boolean stallConfirmed(int streak, int threshold) {
        return threshold > 0 && streak >= threshold;
    }
}
