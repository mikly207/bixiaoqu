package com.bixiaoqu.app;

/**
 * 【v4.35.0】播放调度档位（ScheduleProfile）单元测试。
 *
 * 覆盖的是本次新增的「播放调度」功能里最容易算错、也最影响体验的几条：
 *   ① 四档预设在**清晰度上限 / 并行段数 / 预取 / 自动降档 / 连接池**上的取值；
 *   ② 并行段数必须**不超过设备能力** —— 老设备上开 3 条连接反而更卡；
 *   ③ 「超清晰」档要**禁止自动降档**（用户明确要清晰，别偷偷改画质）；
 *   ④ 脏数据（越界档位 / 非法高度）不能把 App 变成「没有方案可用」；
 *   ⑤ 自定义档的取值必须被夹到合法区间。
 */
public class ScheduleProfileTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    public static void main(String[] a) {
        System.out.println("=== 播放调度档位 ===");

        // ---- 预设档 ----
        ScheduleProfile.Plan smooth = ScheduleProfile.preset(ScheduleProfile.MODE_SMOOTH);
        ScheduleProfile.Plan sharp = ScheduleProfile.preset(ScheduleProfile.MODE_SHARP);
        ScheduleProfile.Plan balance = ScheduleProfile.preset(ScheduleProfile.MODE_BALANCED);
        ScheduleProfile.Plan saver = ScheduleProfile.preset(ScheduleProfile.MODE_SAVER);

        ok("超流畅：清晰度封顶 480p", smooth.maxHeight == 480);
        ok("超流畅：单段并行", smooth.segs == 1);
        ok("超流畅：不预取", !smooth.prefetch);
        ok("超流畅：允许自动降档", smooth.autoDowngrade);
        ok("超流畅：连接池收窄（< 平衡档）",
                smooth.connPool < balance.connPool);

        ok("超清晰：清晰度不封顶", sharp.maxHeight == 0);
        ok("超清晰：并行段数交给设备能力", sharp.segs == 0);
        ok("超清晰：预取拉满", sharp.prefetch);
        ok("超清晰：禁止自动降档", !sharp.autoDowngrade);
        ok("超清晰：连接池最大", sharp.connPool >= balance.connPool);

        ok("平衡：清晰度封顶 1080p", balance.maxHeight == 1080);
        ok("平衡：默认 720p", balance.impliedHeight == 720);
        ok("平衡：预取开", balance.prefetch);
        ok("平衡：允许自动降档", balance.autoDowngrade);
        ok("平衡：并行段数自适应（0）", balance.segs == 0);

        ok("节能：清晰度封顶 360p", saver.maxHeight == 360);
        ok("节能：单段并行", saver.segs == 1);
        ok("节能：不预取", !saver.prefetch);
        ok("节能：连接池最小", saver.connPool <= smooth.connPool);
        ok("节能：IO 线程最少", saver.ioThreads <= smooth.ioThreads);

        // ---- 档位名 ----
        ok("档位名：超流畅", "超流畅".equals(ScheduleProfile.nameOf(ScheduleProfile.MODE_SMOOTH)));
        ok("档位名：超清晰", "超清晰".equals(ScheduleProfile.nameOf(ScheduleProfile.MODE_SHARP)));
        ok("档位名：平衡带推荐标记",
                ScheduleProfile.nameOf(ScheduleProfile.MODE_BALANCED).contains("推荐"));
        ok("越界档位回落到平衡",
                ScheduleProfile.nameOf(999).equals(
                        ScheduleProfile.nameOf(ScheduleProfile.MODE_BALANCED)));
        ok("负数档位回落到平衡",
                ScheduleProfile.nameOf(-1).equals(
                        ScheduleProfile.nameOf(ScheduleProfile.MODE_BALANCED)));

        // ---- 脏数据兜底 ----
        ok("越界 mode 落到平衡而不是崩",
                ScheduleProfile.planOf(999, 0, 0, true, false, true).mode
                        == ScheduleProfile.MODE_BALANCED);
        ok("负数 mode 落到平衡",
                ScheduleProfile.planOf(-5, 0, 0, true, false, true).mode
                        == ScheduleProfile.MODE_BALANCED);

        // ---- 并行段数：档位 与 设备能力 取小 ----
        ok("超清晰在高端机上拿 3 段",
                ScheduleProfile.effectiveSegs(sharp, 3) == 3);
        ok("超清晰在中端机上降到 2 段",
                ScheduleProfile.effectiveSegs(sharp, 2) == 2);
        ok("超清晰在低端机上只给 1 段（老设备不能多开连接）",
                ScheduleProfile.effectiveSegs(sharp, 1) == 1);
        ok("平衡在高端机上拿满设备能力",
                ScheduleProfile.effectiveSegs(balance, 3) == 3);
        ok("平衡在中端机上 2 段",
                ScheduleProfile.effectiveSegs(balance, 2) == 2);
        ok("超流畅在任何设备上都只 1 段",
                ScheduleProfile.effectiveSegs(smooth, 3) == 1);
        ok("节能在任何设备上都只 1 段",
                ScheduleProfile.effectiveSegs(saver, 3) == 1);
        ok("设备能力异常（0）时至少给 1 段",
                ScheduleProfile.effectiveSegs(balance, 0) == 1);
        ok("方案为 null 时退回设备能力",
                ScheduleProfile.effectiveSegs(null, 2) == 2);

        // ---- 自定义档 ----
        ScheduleProfile.Plan custom = ScheduleProfile.custom(720, 2, true, true, false);
        ok("自定义：高度按设定", custom.maxHeight == 720);
        ok("自定义：默认清晰度按设定", custom.impliedHeight == 720);
        ok("自定义：段数按设定", custom.segs == 2);
        ok("自定义：预取按设定", custom.prefetch);
        ok("自定义：弹幕按设定", custom.danmaku);
        ok("自定义：降档按设定", !custom.autoDowngrade);
        ok("自定义：高度 0 = 不限", ScheduleProfile.custom(0, 0, false, false, true).maxHeight == 0);
        ok("自定义：段数上限夹到 3",
                ScheduleProfile.custom(720, 9, true, false, true).segs == 3);
        ok("自定义：负段数当成自动",
                ScheduleProfile.custom(720, -2, true, false, true).segs == 0);

        // ---- 高度 → qn ----
        ok("qn：不限 → 120", ScheduleProfile.qnForHeight(0) == 120);
        ok("qn：1080p → 116", ScheduleProfile.qnForHeight(1080) == 116);
        ok("qn：720p → 64", ScheduleProfile.qnForHeight(720) == 64);
        ok("qn：480p → 32", ScheduleProfile.qnForHeight(480) == 32);
        ok("qn：360p → 16", ScheduleProfile.qnForHeight(360) == 16);

        // ---- 自定义档由 planOf 分流 ----
        ok("planOf 自定义档走自定义参数",
                ScheduleProfile.planOf(ScheduleProfile.MODE_CUSTOM, 480, 1, false, false, true)
                        .maxHeight == 480);
        ok("planOf 预设档忽略自定义参数",
                ScheduleProfile.planOf(ScheduleProfile.MODE_SAVER, 480, 1, false, false, true)
                        .maxHeight == 360);

        System.out.println("--------------------------------------");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("结果：" + (fail == 0 ? "ALL PASS" : "FAIL"));
        if (fail > 0) System.exit(1);
    }
}
