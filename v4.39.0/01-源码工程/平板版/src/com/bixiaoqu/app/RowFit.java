package com.bixiaoqu.app;

/**
 * 【v4.25.0】弹性行折叠算法（纯逻辑，无 Android 依赖，可直接单测）。
 *
 * 【为什么要有它】
 *   v4.24.0 的控制条靠一组写死的 dp 阈值（615 / 557 / 506 / 448）决定收哪些控件。
 *   那组数字是按「横屏手机」估的，竖屏看横屏视频时可用宽度根本对不上：
 *   阈值以为放得下，实际差了一大截，于是排在最后的「全屏」被挤出屏幕右边界，
 *   按钮被裁掉一半，还和「更多」挤在一起 —— 看得见却按不到。
 *
 *   正确的做法是量出每个控件的**真实宽度**，再按优先级折叠到真的放得下为止。
 *   这个类只负责「给定宽度需求和可用宽度，算出哪些要折叠」，不含任何 View 操作，
 *   所以能在 JVM 上直接跑单测（见 tools/RowFitTest.java）。
 */
public final class RowFit {

    private RowFit() { }

    /**
     * 计算折叠方案。
     *
     * @param widths    每个可折叠控件的自然宽度（px）。
     *                  数组顺序即折叠优先级 —— 越靠前越先被折叠。
     *                  宽度 &lt;= 0 表示该控件当前本来就不该显示（例如单 P 视频没有「下一集」）。
     * @param available 留给这些控件的可用宽度（px）
     * @return hide[i] = true 表示第 i 个控件应当隐藏
     */
    public static boolean[] foldMask(int[] widths, int available) {
        int n = widths == null ? 0 : widths.length;
        boolean[] hide = new boolean[n];
        if (n == 0) return hide;

        // 第一遍：宽度 <= 0 的控件「本来就不该显示」（业务上不存在，比如单 P 视频没有「下一集」），
        //   无条件标记为隐藏。
        //   这一遍必须在折叠循环之前独立做完 —— 否则一旦折叠循环提前 break，
        //   排在后面的零宽控件就会被漏掉，导致「本来该隐藏的却显示出来」。
        int total = 0;
        for (int i = 0; i < n; i++) {
            if (widths[i] <= 0) {
                hide[i] = true;
            } else {
                total += widths[i];
            }
        }

        // 第二遍：按优先级（数组顺序）从前往后折叠，直到真的放得下为止。
        for (int i = 0; i < n; i++) {
            if (total <= available) break;
            if (hide[i]) continue;          // 宽度 0，已经算作隐藏
            total -= widths[i];
            hide[i] = true;                 // 折叠掉，腾出宽度
        }
        return hide;
    }

    /**
     * 只要个数版本（便于断言）。
     *
     * @see #foldMask(int[], int)
     */
    public static int foldCount(int[] widths, int available) {
        boolean[] hide = foldMask(widths, available);
        int n = 0;
        for (int i = 0; i < hide.length; i++) {
            if (hide[i]) n++;
        }
        return n;
    }
}
