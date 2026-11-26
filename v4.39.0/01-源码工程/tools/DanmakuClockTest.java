import com.bixiaoqu.app.DanmakuClock;

/** 【v4.39.0】弹幕平滑时钟单测：验证 60fps 外推、倍速、暂停、seek、防飞出。 */
public class DanmakuClockTest {
    static int pass = 0, fail = 0;
    static void ck(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }
    static boolean near(long a, long b, int tol) { return Math.abs(a - b) <= tol; }

    public static void main(String[] args) {
        System.out.println("=== DanmakuClockTest ===");

        // 1x：锚点 1000ms，速度 1，过 16ms（一帧）→ 位置前进约 16ms
        ck("1x 单帧平滑推进", near(DanmakuClock.extrapolate(1000, 1f, 16, 300), 1016, 1));
        // 60fps 连续 3 帧：每帧都推进，而不是钉在锚点不动（旧 bug 的核心）
        long f1 = DanmakuClock.extrapolate(1000, 1f, 16, 300);
        long f2 = DanmakuClock.extrapolate(1000, 1f, 32, 300);
        long f3 = DanmakuClock.extrapolate(1000, 1f, 48, 300);
        ck("逐帧连续推进(不再静止)", f1 < f2 && f2 < f3);
        ck("帧间步长均匀≈16ms", near(f2 - f1, 16, 1) && near(f3 - f2, 16, 1));

        // 2x 倍速：80ms 采样间隔内位置前进 160ms → 估算需几次采样收敛到≈2
        float v2 = 1f;
        for (int i = 0; i < 8; i++) v2 = DanmakuClock.estimateSpeed(v2, 160, 80_000_000L);
        ck("倍速估算收敛≈2", Math.abs(v2 - 2f) < 0.25f);
        long bf = DanmakuClock.extrapolate(1000, v2, 16, 300);
        ck("倍速外推更快", (bf - 1000) > 20);

        // 暂停：采样位置不前进 → 速度归零 → 位置冻结
        ck("暂停速度归零", DanmakuClock.estimateSpeed(1f, 0, 80_000_000L) == 0f);
        ck("冻结采样判定", DanmakuClock.isFrozen(1000, 1000));
        ck("位置冻结(速度0不动)", DanmakuClock.extrapolate(1000, 0f, 100, 300) == 1000);

        // 回退 seek
        ck("回退判定", DanmakuClock.isSeekBack(500, 3000));
        ck("小幅波动不算回退", !DanmakuClock.isSeekBack(2990, 3000));
        ck("首次采样无回退", !DanmakuClock.isSeekBack(0, -1));

        // 防飞出：外推被上限夹住
        ck("外推上限夹取", DanmakuClock.extrapolate(1000, 1f, 5000, 300) == 1300);
        // 负流逝时间被夹为 0
        ck("负流逝不回退", DanmakuClock.extrapolate(1000, 1f, -50, 300) == 1000);
        // 异常大速度被忽略，保持原值
        ck("异常速度忽略", DanmakuClock.estimateSpeed(1f, 5000, 1_000_000L) == 1f);

        System.out.println("结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
