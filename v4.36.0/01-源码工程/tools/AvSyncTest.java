package com.bixiaoqu.app;

/**
 * 【v4.34.0】音画同步策略 + 设备能力分级的单测。
 *
 * 这两块是「老设备 1080p 音画不同步 / 卡顿」的修复核心，
 * 判据一旦算错，表现就是「越修越糟」：
 *   · 漂移判定太松 → 失步一直不管，声音越走越远；
 *   · 漂移判定太紧 → 频繁微调速率，音频一直在抖，反而更难听；
 *   · 设备分级太激进 → 高端机也被按到 720p，用户白白损失画质。
 */
public class AvSyncTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] a) {
        System.out.println("=== 音画同步策略 ===");

        // ---- 漂移分级 ----
        ok("完全同步（偏差 0）判 OK", AvSync.classify(10000, 10000) == AvSync.OK);
        ok("偏差 100ms 判 OK（听感阈值内）", AvSync.classify(10100, 10000) == AvSync.OK);
        ok("偏差 200ms 正好在阈值上判 OK", AvSync.classify(10200, 10000) == AvSync.OK);
        ok("偏差 201ms 判 DRIFT", AvSync.classify(10201, 10000) == AvSync.DRIFT);
        ok("偏差 400ms 判 DRIFT", AvSync.classify(10400, 10000) == AvSync.DRIFT);
        ok("偏差 551ms 判 RESYNC", AvSync.classify(10551, 10000) == AvSync.RESYNC);
        ok("音频落后同样分级（对称）", AvSync.classify(10000, 10400) == AvSync.DRIFT);
        ok("音频落后 1 秒判 RESYNC", AvSync.classify(10000, 11000) == AvSync.RESYNC);
        ok("负偏差取绝对值判定（落后 1 秒也是 RESYNC）",
                AvSync.classify(9000, 10000) == AvSync.RESYNC);
        ok("负偏差 400ms 判 DRIFT", AvSync.classify(9600, 10000) == AvSync.DRIFT);

        // ---- 渐进速率 ----
        float slow = AvSync.correctedRate(1f, 10300, 10000);   // 音频超前 → 减速
        ok("音频超前 → 速率下调", slow < 1f);
        float fast = AvSync.correctedRate(1f, 9700, 10000);    // 音频落后 → 加速
        ok("音频落后 → 速率上调", fast > 1f);
        ok("偏差小用 3% 档", Math.abs(slow - 0.97f) < 0.001f);
        ok("偏差大用 6% 档",
                Math.abs(AvSync.correctedRate(1f, 10500, 10000) - 0.94f) < 0.001f);
        ok("倍速基准被保留（2.0 基准下仍在 2.0 附近）",
                AvSync.correctedRate(2f, 10300, 10000) > 1.8f
                        && AvSync.correctedRate(2f, 10300, 10000) < 2f);
        ok("速率不会跑到离谱范围",
                AvSync.correctedRate(1f, 10000, 10000) >= 0.5f
                        && AvSync.correctedRate(1f, 10000, 10000) <= 3f);
        ok("base<=0 时按 1.0 处理",
                Math.abs(AvSync.correctedRate(0f, 10300, 10000) - 0.97f) < 0.001f);

        // ---- 卡顿判定 ----
        ok("位置不动 → 判定卡住", AvSync.stalled(10000, 10005, 60));
        ok("位置前进超过阈值 → 不算卡住", !AvSync.stalled(10000, 10500, 60));
        ok("刚好等于阈值 → 算卡住（保守）", AvSync.stalled(10000, 10060, 60));
        ok("连续 1 次不算确认卡顿", !AvSync.stallConfirmed(1, 2));
        ok("连续 2 次确认卡顿", AvSync.stallConfirmed(2, 2));

        System.out.println("=== 设备能力分级 ===");

        // ---- 分级 ----
        ok("无硬解 → 低端", DeviceTier.tierOf(8192, 8, 34, false) == DeviceTier.TIER_LOW);
        ok("Android 5.1 → 低端", DeviceTier.tierOf(8192, 8, 22, true) == DeviceTier.TIER_LOW);
        ok("1.5G 内存 → 低端", DeviceTier.tierOf(1536, 8, 30, true) == DeviceTier.TIER_LOW);
        ok("2G 内存 → 中端", DeviceTier.tierOf(2048, 8, 30, true) == DeviceTier.TIER_MID);
        ok("4 核 + 3G → 中端", DeviceTier.tierOf(3072, 4, 30, true) == DeviceTier.TIER_MID);
        ok("6G + 8 核 + 新系统 → 高端", DeviceTier.tierOf(6144, 8, 34, true) == DeviceTier.TIER_HIGH);
        ok("内存查不到时不武断判低端", DeviceTier.tierOf(0, 8, 34, true) == DeviceTier.TIER_HIGH);
        ok("核数查不到时按内存判断", DeviceTier.tierOf(2048, 0, 30, true) == DeviceTier.TIER_MID);

        // ---- 清晰度上限 ----
        ok("低端上限 480", DeviceTier.maxHeightOf(DeviceTier.TIER_LOW, 1536, 8, 30) == 480);
        ok("中端上限 720", DeviceTier.maxHeightOf(DeviceTier.TIER_MID, 2048, 8, 30) == 720);
        ok("高端上限 1080", DeviceTier.maxHeightOf(DeviceTier.TIER_HIGH, 6144, 8, 34) == 1080);
        ok("1G 内存再压到 360",
                DeviceTier.maxHeightOf(DeviceTier.TIER_MID, 1024, 8, 30) == 360);
        ok("Android 5.x 压到 480",
                DeviceTier.maxHeightOf(DeviceTier.TIER_HIGH, 8192, 8, 22) == 480);
        ok("高端机在新系统上不压",
                DeviceTier.maxHeightOf(DeviceTier.TIER_HIGH, 8192, 8, 34) == 1080);

        System.out.println();
        System.out.println("AvSyncTest: 通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
