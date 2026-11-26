package com.bixiaoqu.app;

/**
 * 【v4.38.0】流调优器的穷举测试（纯 JVM，不依赖设备）。
 *
 * 覆盖：
 *  · smoothBandwidth：快降慢升，弱网骤降立刻跟下去、回升谨慎、限幅
 *  · rescueHeight：按带宽给弱网保底高度，不改变带宽未知的情况
 *  · segsFor：设备能力是硬顶，弱网 1080p 才多开一段
 *  · watchdogMs：弱网/超流畅放宽、硬上限防黑屏
 */
public class StreamTunerTest {

    static int pass = 0, fail = 0;

    static void eq(String name, long got, long want) {
        if (got == want) { pass++; }
        else { fail++; System.out.println("  [FAIL] " + name + " 期望 " + want + " 实际 " + got); }
    }

    public static void main(String[] args) {
        System.out.println("=== StreamTunerTest ===");

        // ---------- smoothBandwidth：快降慢升 ----------
        // 掉速：10M → 1M，新样本权重 0.75 → 一口气降到约 3.25M，明显快于旧算法的 7.15M
        long drop = StreamTuner.smoothBandwidth(10_000_000L, 1_000_000L);
        eq("掉速立即大幅下调", drop, (long) (10_000_000L * 0.25 + 1_000_000L * 0.75));
        if (drop < 5_000_000L) pass++; else { fail++; System.out.println("  [FAIL] 掉速应破 5M"); }
        // 回升：1M → 10M，新样本权重仅 0.2 → 约 2.8M，谨慎不被骗
        long rise = StreamTuner.smoothBandwidth(1_000_000L, 10_000_000L);
        eq("回升谨慎", rise, (long) (1_000_000L * 0.8 + 10_000_000L * 0.2));
        if (rise < drop) pass++; else { fail++; System.out.println("  [FAIL] 回升应慢于掉速"); }
        eq("零样本不变", StreamTuner.smoothBandwidth(5_000_000L, 0), 5_000_000L);
        eq("下限夹取", StreamTuner.smoothBandwidth(1_000_000L, 1L), StreamTuner.FLOOR_BPS);
        eq("上限夹取", StreamTuner.smoothBandwidth(90_000_000L, 900_000_000L), StreamTuner.CEIL_BPS);

        // ---------- rescueHeight ----------
        eq("极弱网 0.6M → 360", StreamTuner.rescueHeight(1080, 600_000L), 360);
        eq("弱网 1.5M → 480", StreamTuner.rescueHeight(1080, 1_500_000L), 480);
        eq("中等 3M → 720", StreamTuner.rescueHeight(1080, 3_000_000L), 720);
        eq("快网 6M → 不压", StreamTuner.rescueHeight(1080, 6_000_000L), 1080);
        eq("带宽未知 → 不动", StreamTuner.rescueHeight(1080, 0), 1080);
        eq("自动不限 + 弱网 → 给 480", StreamTuner.rescueHeight(0, 1_000_000L), 480);
        eq("只压不升：auto 低于带宽档", StreamTuner.rescueHeight(480, 6_000_000L), 480);

        // ---------- segsFor ----------
        eq("超流畅单段档保持单段", StreamTuner.segsFor(1, 3, 800_000L, 480), 1);
        eq("节能单段档保持单段", StreamTuner.segsFor(1, 3, 500_000L, 360), 1);
        eq("老设备硬顶1，弱网1080也不开多段", StreamTuner.segsFor(0, 1, 1_000_000L, 1080), 1);
        eq("弱网1080自适应→多开一段填满管道", StreamTuner.segsFor(0, 3, 1_000_000L, 1080), 2);
        eq("快网1080不强开", StreamTuner.segsFor(0, 3, 6_000_000L, 1080), 3);
        eq("自适应+设备上限2", StreamTuner.segsFor(0, 2, 5_000_000L, 1080), 2);
        eq("指定段数受设备夹取", StreamTuner.segsFor(3, 2, 5_000_000L, 720), 2);
        eq("弱网低分辨率不加段", StreamTuner.segsFor(1, 3, 1_000_000L, 480), 1);

        // ---------- watchdogMs ----------
        eq("正常网络用基准", StreamTuner.watchdogMs(8000L, 6_000_000L, false), 8000L);
        eq("弱网放宽到10s", StreamTuner.watchdogMs(8000L, 1_000_000L, false), 10000L);
        eq("超流畅放宽到12s", StreamTuner.watchdogMs(8000L, 6_000_000L, true), 12000L);
        eq("硬上限14s防黑屏", StreamTuner.watchdogMs(20000L, 500_000L, true), 14000L);
        eq("带宽未知+普通不放大", StreamTuner.watchdogMs(8000L, 0, false), 8000L);

        System.out.println("结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
