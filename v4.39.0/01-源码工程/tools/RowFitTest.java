import com.bixiaoqu.app.RowFit;

/**
 * 【v4.25.0】RowFit.foldMask 单元测试（纯 JVM，不需要 Android 环境）。
 *
 * 覆盖的是本次修复的核心保证：
 *   ① 宽度够 → 一个都不折叠；
 *   ② 宽度不够 → 按优先级从前往后折叠，直到真的放得下；
 *   ③ 业务上不可见的控件（宽度 <= 0）不占宽度、不参与折叠；
 *   ④ **折叠到最后一个可折叠项也放不下时，返回的折叠方案仍然正确** ——
 *      这是「全屏键被挤出屏幕」那个 bug 的最后一道防线。
 *
 * 运行：
 *   javac -encoding UTF-8 -d /tmp/rowfit \
 *     app/src/com/bixiaoqu/app/RowFit.java tools/RowFitTest.java
 *   java -cp /tmp/rowfit RowFitTest
 */
public class RowFitTest {

    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : " · " + detail));
        }
    }

    /** 判断某个下标是否被折叠 */
    static boolean hidden(boolean[] mask, int i) {
        return mask[i];
    }

    /** 折叠后仍然可见的控件总宽度 */
    static int visibleSum(int[] widths, boolean[] mask) {
        int sum = 0;
        for (int i = 0; i < widths.length; i++) {
            if (!mask[i]) sum += widths[i];
        }
        return sum;
    }

    public static void main(String[] args) {
        System.out.println("=== RowFit 折叠算法单测 v4.25.0 ===");

        // ---------------------------------------------------------- ① 宽度充足
        {
            int[] w = {104, 46, 56, 62, 76};   // 发弹幕/选集/下一集/倍速/画质
            boolean[] m = RowFit.foldMask(w, 400);
            boolean none = true;
            for (int i = 0; i < m.length; i++) if (m[i]) none = false;
            check("宽度充足时一个都不折叠", none, "mask=" + java.util.Arrays.toString(m));
            check("宽度充足时折叠数为 0", RowFit.foldCount(w, 400) == 0);
        }

        // ---------------------------------------------------------- ② 按优先级折叠
        {
            int[] w = {104, 46, 56, 62, 76};
            // 只够放下后两个（62+76=138），可用 150
            boolean[] m = RowFit.foldMask(w, 150);
            check("优先折叠最宽的第一个（发弹幕）", hidden(m, 0));
            check("继续折叠第二个直到放得下", hidden(m, 1));
            check("剩余可见项总宽 <= 可用宽度",
                    visibleSum(w, m) <= 150,
                    "visible=" + visibleSum(w, m));
            check("折叠后仍保留尾部控件（选集/下一集等）",
                    !hidden(m, 4) || !hidden(m, 3),
                    "mask=" + java.util.Arrays.toString(m));
        }

        // ---------------------------------------------------------- ③ 业务不可见项
        {
            // nextBtn 宽度 0（单 P 视频没有「下一集」）
            int[] w = {104, 0, 62, 76};
            boolean[] m = RowFit.foldMask(w, 100);
            check("宽度 0 的控件永远不可见", hidden(m, 1));
            check("宽度 0 的控件不占可用宽度",
                    visibleSum(w, m) <= 100,
                    "visible=" + visibleSum(w, m));
        }

        // ---------------------------------------------------------- ④ 极端窄屏
        {
            // 可用 60dp，连最小的都放不下 —— 全折
            int[] w = {104, 46, 56, 62, 76};
            boolean[] m = RowFit.foldMask(w, 60);
            int hiddenCount = 0;
            for (int i = 0; i < m.length; i++) if (m[i]) hiddenCount++;
            check("极窄屏时全部可折叠项都被收起", hiddenCount == 5,
                    "hidden=" + hiddenCount);
            check("极窄屏时可见总宽为 0", visibleSum(w, m) == 0);
        }

        // ---------------------------------------------------------- ⑤ 全空 / 边界
        {
            check("空数组返回空 mask", RowFit.foldMask(new int[0], 100).length == 0);
            check("null 返回空 mask", RowFit.foldMask(null, 100).length == 0);
            boolean[] m = RowFit.foldMask(new int[]{50}, 50);
            check("刚好放得下就不折叠", !m[0]);
            boolean[] m2 = RowFit.foldMask(new int[]{51}, 50);
            check("差 1dp 就折叠", m2[0]);
            boolean[] m3 = RowFit.foldMask(new int[]{0}, 50);
            check("唯一控件宽度为 0 → 不可见", m3[0]);
        }

        // ------------------------------------------- ⑥ 复现并锁死本次的真实场景
        {
            // 手机竖屏看横屏视频：rowTools 实际可用 318dp（360 - 20 边距 - 22 玻璃板 padding）
            // 固定区（播放/时间/弹幕开关/更多/全屏）约 217dp 永不折叠
            int fixed = 217;
            int avail = 318;
            int[] w = {109, 51, 61, 67, 83};   // 发弹幕/选集/下一集/倍速/画质
            boolean[] m = RowFit.foldMask(w, avail - fixed);
            check("竖屏场景下可见项总宽 + 固定区 <= 可用宽度",
                    visibleSum(w, m) + fixed <= avail,
                    "total=" + (visibleSum(w, m) + fixed) + " > " + avail);
            check("竖屏场景下「发弹幕」输入框被优先收起", hidden(m, 0));
        }

        // ---------------------------------------------------------- ⑦ 折叠方案稳定性
        {
            // 同样的输入必须得到同样的结果（幂等），否则每帧重排会抖动
            int[] w = {104, 46, 56, 62, 76};
            boolean[] a = RowFit.foldMask(w, 300);
            boolean[] b = RowFit.foldMask(w, 300);
            boolean same = java.util.Arrays.equals(a, b);
            check("同样输入得到同样折叠方案（幂等）", same);
        }

        System.out.println("--------------------------------------");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("结果：" + (failed == 0 ? "ALL PASS" : "FAIL"));
        if (failed > 0) System.exit(1);
    }
}
