package com.bixiaoqu.app;

/**
 * 【v4.30.0】并行分段规划的单测。
 *
 * 这一层最怕的不是"慢"，是**算错**：段与段之间多一个字节或少一个字节，
 * 播放器收到的就是一段错位的视频 —— 花屏、卡死、音画不同步，而且极难复现。
 * 所以这里把「覆盖完整 / 无重叠 / 无空洞 / 末段不越界」全部逐项断言。
 */
public class ParallelPlanTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    static boolean contiguous(long[][] p, long start, long len) {
        long cur = start;
        for (long[] seg : p) {
            if (seg[0] != cur) return false;     // 有空洞或重叠
            if (seg[1] < seg[0]) return false;   // 空区间
            cur = seg[1] + 1;
        }
        return cur == start + len;               // 末段正好收在尾部
    }

    public static void main(String[] a) {
        System.out.println("=== 分段规划 ===");

        // 1. 4MB 整段拆 3 段
        long len = 4L * 1024 * 1024;
        long[][] p = ParallelPlan.split(0, len, 3);
        ok("4MB → 3 段", p.length == 3);
        ok("4MB 覆盖完整且无重叠", contiguous(p, 0, len));
        ok("4MB 末段收在 len-1", p[2][1] == len - 1);

        // 2. 不能被 3 整除的长度（余数全给末段）
        long odd = 10_000_003L;
        p = ParallelPlan.split(12345L, odd, 3);
        ok("奇数长度 → 3 段", p.length == 3);
        ok("奇数长度覆盖完整（起点 12345）", contiguous(p, 12345L, odd));
        ok("前三段等长、余数归末段",
                p[0][1] - p[0][0] == p[1][1] - p[1][0]
                        && p[2][1] - p[2][0] > p[0][1] - p[0][0]);

        // 3. 边界：正好卡在阈值上
        p = ParallelPlan.split(0, ParallelPlan.MIN_BYTES, 3);
        ok("阈值长度可拆且完整", contiguous(p, 0, ParallelPlan.MIN_BYTES));

        // 4. 短区间：要么每段非空，要么退化成单段，绝不产生空区间
        p = ParallelPlan.split(100L, 5L, 3);
        ok("长度 5 > 段数 3 → 仍分 3 段", p.length == 3);
        ok("长度 5 每段非空且覆盖完整", contiguous(p, 100L, 5L));
        p = ParallelPlan.split(100L, 2L, 3);
        ok("长度 2 < 段数 3 → 退化成单段", p.length == 1);
        ok("退化后仍是完整区间", contiguous(p, 100L, 2L));

        // 5. len = 0 不产生假区间
        p = ParallelPlan.split(777L, 0L, 3);
        ok("len=0 → 空区间（不会多读一个字节）", p.length == 1 && p[0][1] == 776L);

        // 6. k 越界被夹住
        ok("k=9 被夹到 3", ParallelPlan.split(0, 20L * 1024 * 1024, 9).length == 3);
        ok("k=1 退化成单段", ParallelPlan.split(0, 20L * 1024 * 1024, 1).length == 1);

        // 7. 大文件下不出现整数溢出
        long big = 8L * 1024 * 1024 * 1024;   // 8GB
        p = ParallelPlan.split(0, big, 3);
        ok("8GB 不溢出且覆盖完整", contiguous(p, 0, big));

        System.out.println("=== 是否值得并行 ===");
        ok("3MB 不拆（握手开销吃掉收益）", !ParallelPlan.worth(3L * 1024 * 1024, 6));
        ok("4MB + 6 线路 → 拆", ParallelPlan.worth(4L * 1024 * 1024, 6));
        ok("4MB 但只有 1 条线路 → 不拆", !ParallelPlan.worth(4L * 1024 * 1024, 1));
        ok("4MB 两条线路 → 拆（2 条也能提速）", ParallelPlan.worth(4L * 1024 * 1024, 2));

        System.out.println("=== Content-Range 校验 ===");
        ok("对得上：bytes 1048576-2097151/8388608",
                ParallelPlan.rangeStartsAt("bytes 1048576-2097151/8388608", 1048576L));
        ok("对不上 → false",
                !ParallelPlan.rangeStartsAt("bytes 0-1048575/8388608", 1048576L));
        ok("空头 → false", !ParallelPlan.rangeStartsAt(null, 0L));
        ok("垃圾串 → false", !ParallelPlan.rangeStartsAt("garbage", 0L));
        ok("无空格前缀（bytes=）→ false", !ParallelPlan.rangeStartsAt("bytes=0-99/100", 0L));

        System.out.println("--------------------------------------");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("结果：" + (fail == 0 ? "ALL PASS" : "FAIL"));
        if (fail > 0) System.exit(1);
    }
}
