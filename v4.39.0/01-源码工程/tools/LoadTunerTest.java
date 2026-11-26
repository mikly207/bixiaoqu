package com.bixiaoqu.app;

/**
 * 【v4.37.0】加载调速器的穷举测试（纯 JVM，不依赖设备）。
 *
 * 覆盖：
 *  · autoTargetHeight：自动/手动目标 × 带宽档 × 硬顶 cap 的组合，
 *    保证「分辨率自动 + 带宽自适应」且永不突破设备/档位硬顶
 *  · holdAfterFrame：快网短窗口 / 慢网长窗口 / 未知取中，边界值全覆盖
 */
public class LoadTunerTest {

    static int pass = 0, fail = 0;

    static void eq(String name, long got, long want) {
        if (got == want) { pass++; }
        else { fail++; System.out.println("  [FAIL] " + name + " 期望 " + want + " 实际 " + got); }
    }

    public static void main(String[] args) {
        System.out.println("=== LoadTunerTest ===");

        // ---------- autoTargetHeight：无硬顶 ----------
        eq("自动 + 未知带宽 → 不限(0)", LoadTuner.autoTargetHeight(0, 0, 0), 0);
        eq("自动 + 极弱网 0.9M → 480", LoadTuner.autoTargetHeight(0, 900_000L, 0), 480);
        eq("自动 + 1.5M → 720", LoadTuner.autoTargetHeight(0, 1_500_000L, 0), 720);
        eq("自动 + 快网 5M → 不限(0，选最高可用)", LoadTuner.autoTargetHeight(0, 5_000_000L, 0), 0);
        eq("手动 1080 + 弱网 0.9M → 压到 480", LoadTuner.autoTargetHeight(1080, 900_000L, 0), 480);
        eq("手动 720 + 快网 → 尊重 720", LoadTuner.autoTargetHeight(720, 5_000_000L, 0), 720);

        // ---------- autoTargetHeight：有硬顶 cap ----------
        eq("自动 + 快网 + 设备顶 1080 → 1080", LoadTuner.autoTargetHeight(0, 5_000_000L, 1080), 1080);
        eq("自动 + 弱网 + 设备顶 1080 → 480", LoadTuner.autoTargetHeight(0, 900_000L, 1080), 480);
        eq("自动 + 快网 + 节能顶 360 → 360", LoadTuner.autoTargetHeight(0, 5_000_000L, 360), 360);
        eq("手动 2160 + 设备顶 1080 → 1080（绝不越顶）", LoadTuner.autoTargetHeight(2160, 5_000_000L, 1080), 1080);
        eq("手动 480 + 弱网 0.9M + 顶 720 → 480", LoadTuner.autoTargetHeight(480, 900_000L, 720), 480);
        eq("硬顶 cap=0 表示不限，保留自动结果", LoadTuner.autoTargetHeight(0, 1_500_000L, 0), 720);

        // ---------- holdAfterFrame ----------
        eq("未知首帧 → 普通 850", LoadTuner.holdAfterFrame(-1), LoadTuner.HOLD_NORMAL);
        eq("0ms 首帧 → 快 500", LoadTuner.holdAfterFrame(0), LoadTuner.HOLD_FAST);
        eq("1200ms 边界 → 快 500", LoadTuner.holdAfterFrame(LoadTuner.FRAME_FAST_MS), LoadTuner.HOLD_FAST);
        eq("1201ms → 普通 850", LoadTuner.holdAfterFrame(LoadTuner.FRAME_FAST_MS + 1), LoadTuner.HOLD_NORMAL);
        eq("2199ms → 普通 850", LoadTuner.holdAfterFrame(LoadTuner.FRAME_SLOW_MS - 1), LoadTuner.HOLD_NORMAL);
        eq("2200ms 边界 → 慢 1200", LoadTuner.holdAfterFrame(LoadTuner.FRAME_SLOW_MS), LoadTuner.HOLD_SLOW);
        eq("5000ms 首帧 → 慢 1200", LoadTuner.holdAfterFrame(5000), LoadTuner.HOLD_SLOW);

        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) System.exit(1);
    }
}
