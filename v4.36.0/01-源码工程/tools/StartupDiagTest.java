package com.bixiaoqu.app;

import com.bixiaoqu.app.StartupDiag;

/**
 * 【v4.22.0】起播诊断 / 自适应码率的穷举测试（纯 JVM，不依赖设备）。
 *
 * 覆盖：
 *  · classify 的边界值（1399/1400、2199/2200、BOTH 门槛）
 *  · adaptHeight 的全档位 × 带宽档组合（含 0 = 自动）
 *  · 「连续两次才降档」「恢复正常要连续两次」「只降不升」三条状态机规则
 *  · 没有取址打点时不得误判弱网（合流/本地播放路径）
 */
public class StartupDiagTest {

    static int pass = 0, fail = 0;

    static void eq(String name, long got, long want) {
        if (got == want) { pass++; }
        else { fail++; System.out.println("  [FAIL] " + name + " 期望 " + want + " 实际 " + got); }
    }

    public static void main(String[] args) {
        System.out.println("=== StartupDiagTest ===");

        // ---------- classify ----------
        eq("0/0 正常", StartupDiag.classify(0, 0), StartupDiag.OK);
        eq("1399/2199 正常（都在阈值内）", StartupDiag.classify(1399, 2199), StartupDiag.OK);
        eq("1400/0 网络慢（阈值闭区间）", StartupDiag.classify(1400, 0), StartupDiag.NET_SLOW);
        eq("0/2200 解码慢", StartupDiag.classify(0, 2200), StartupDiag.DECODE_SLOW);
        eq("1000/2199 正常", StartupDiag.classify(1000, 2199), StartupDiag.OK);
        eq("1500/5000 两者都慢", StartupDiag.classify(1500, 5000), StartupDiag.BOTH);
        eq("3000/5000 取址太慢 → 只算网络", StartupDiag.classify(3000, 5000), StartupDiag.NET_SLOW);
        eq("1400/4399 刚好不到 BOTH", StartupDiag.classify(1400, 4399), StartupDiag.NET_SLOW);
        // 取址慢时不能重复算成解码慢（否则弱网被降分辨率也没用）
        eq("2000/9000 取址略慢且解码更慢 → 两者都慢", StartupDiag.classify(2000, 9000), StartupDiag.BOTH);

        // ---------- adaptHeight ----------
        StartupDiag.resetForTest();
        eq("自动档 + 极弱网(0.9Mbps) → 480", StartupDiag.adaptHeight(0, 900_000L), 480);
        eq("自动档 + 弱网(2Mbps) → 720", StartupDiag.adaptHeight(0, 2_000_000L), 720);
        eq("自动档 + 好网(10Mbps) → 不限", StartupDiag.adaptHeight(0, 10_000_000L), 0);
        eq("手选 1080 + 极弱网 → 480", StartupDiag.adaptHeight(1080, 900_000L), 480);
        eq("手选 720 + 弱网 → 720（不越权升）", StartupDiag.adaptHeight(720, 2_000_000L), 720);
        eq("手选 2160 + 好网 → 2160", StartupDiag.adaptHeight(2160, 10_000_000L), 2160);
        eq("手选 480 + 极弱网 → 480（最低档不降）", StartupDiag.adaptHeight(480, 300_000L), 480);
        eq("带宽未知(0) 不干预", StartupDiag.adaptHeight(1080, 0L), 1080);

        // ---------- 状态机：网络慢 ----------
        StartupDiag.resetForTest();
        StartupDiag.feedForTest(1600, 3000);          // 1 次：不降
        eq("网络慢 1 次不降档", StartupDiag.autoCap(), 0);
        StartupDiag.feedForTest(1600, 3000);          // 2 次：降到 720
        eq("网络慢连续 2 次 → 限 720", StartupDiag.autoCap(), 720);
        eq("限档后 1080 被压到 720", StartupDiag.adaptHeight(1080, 10_000_000L), 720);
        eq("限档后 480 不受影响", StartupDiag.adaptHeight(480, 10_000_000L), 480);

        // 恢复：需要连续两次正常
        StartupDiag.feedForTest(600, 900);
        eq("正常 1 次仍限档", StartupDiag.autoCap(), 720);
        StartupDiag.feedForTest(600, 900);
        eq("正常连续 2 次解除限档", StartupDiag.autoCap(), 0);

        // ---------- 状态机：解码慢 ----------
        StartupDiag.resetForTest();
        StartupDiag.feedForTest(800, 3200);           // dec=2400 → 解码慢
        eq("解码慢 1 次不降", StartupDiag.autoCap(), 0);
        StartupDiag.feedForTest(800, 3200);
        eq("解码慢连续 2 次 → 限 1080", StartupDiag.autoCap(), 1080);

        // ---------- 只降不升 ----------
        StartupDiag.feedForTest(1600, 3000);
        StartupDiag.feedForTest(1600, 3000);
        eq("已限 720 时再来解码慢，不回升", StartupDiag.autoCap(), 720);

        // ---------- 抖动不误伤 ----------
        StartupDiag.resetForTest();
        StartupDiag.feedForTest(1500, 1500);          // 慢
        StartupDiag.feedForTest(600, 900);            // 立刻正常
        StartupDiag.feedForTest(1500, 1500);          // 又慢
        StartupDiag.feedForTest(600, 900);            // 又正常
        eq("慢-正-慢-正 抖动不触发降档", StartupDiag.autoCap(), 0);

        // ---------- 无取址打点 ----------
        StartupDiag.resetForTest();
        StartupDiag.onFrame(9000);                    // 合流路径：只有总耗时
        eq("无取址打点不判定弱网", StartupDiag.autoCap(), 0);
        eq("无取址打点 verdict 保持 OK", StartupDiag.verdict(), StartupDiag.OK);

        // ---------- 样本统计 ----------
        StartupDiag.resetForTest();
        StartupDiag.onFrame(1000);
        StartupDiag.onFrame(3000);
        eq("平均首帧 (1000+3000)/2", StartupDiag.avgFrameMs(), 2000);
        eq("诊断名可读", StartupDiag.verdictName(StartupDiag.DECODE_SLOW).isEmpty() ? 1 : 0, 0);

        // ---------- 旁路：summary 不抛异常 ----------
        try { StartupDiag.summary(); pass++; }
        catch (Throwable t) { fail++; System.out.println("  [FAIL] summary 抛异常 " + t); }

        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("结果：" + (fail == 0 ? "ALL PASS" : "FAIL"));
        if (fail != 0) System.exit(1);
    }
}
