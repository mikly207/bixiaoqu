package com.bixiaoqu.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【v4.25.0】可折叠的横向行：放不下时按「实测宽度」自动收起次要控件。
 *
 * 【v4.24.0 为什么会把「全屏」挤出屏幕】
 *   旧版控制条是一排普通子 View，靠一组写死的 dp 阈值（615 / 557 / 506 / 448）
 *   决定藏哪几个。那组数字是按「横屏手机」估的，竖屏看横屏视频时对不上：
 *   所有子 View 的自然宽度加起来比可用宽度多出几十 dp。
 *   而 LinearLayout 在放不下时有个很讨厌的行为 ——
 *   它不会裁掉最后一个子 View，而是把它排到容器右边界之外，
 *   于是「全屏」按钮只露出半个，还和「更多」挤在一起：看得见，却按不到。
 *
 * 【这一版怎么修】
 *   ① 折叠决策改成「量真实宽度」而不是「猜 dp 阈值」：见 RowFit.foldMask()；
 *   ② 每一轮测量都重新算，屏幕一横一竖自动重排，转回来也不会少按钮；
 *   ③ 「播放 / 时间 / 弹幕开关 / 更多 / 全屏」是固定区，永不参与折叠 ——
 *      全屏键永远不会跑到屏幕外去。
 *
 * 【为什么必须在测量前就把「要收谁」定下来】
 *   LinearLayout 是边测边排的：宽度不够时它先把可压缩的子 View（文字胶囊）
 *   挤瘦，挤不下了才把固定宽度的图标推到边界外。
 *   等布局完再处理，第一帧就已经是错的（图标已经跑到玻璃板外面了）。
 *
 * 【关于性能】
 *   控制条上的时间每秒钟变一次，如果每次测量都重新量所有控件的文字宽度，
 *   就是每秒十几次 measure —— 播放时最容易抖的地方。
 *   所以按「文字内容」做缓存：内容没变就不重量（见 natW / textSig）。
 */
public class FoldRow extends LinearLayout {

    /** 可折叠控件；顺序即折叠优先级，越靠前越先被收起 */
    private final List<View> foldables = new ArrayList<View>();
    /** 业务可见性（与折叠无关）：例如单 P 视频本来就没有「下一集」 */
    private final Map<View, Boolean> biz = new HashMap<View, Boolean>();
    /** 上一次折叠结果：foldState[i] = true 表示第 i 个是被折叠掉的 */
    private boolean[] foldState = new boolean[0];
    /** 上一次折叠时用的可用宽度，用于跳过无意义的重复计算 */
    private int lastAvail = -1;
    /** wrap_content 控件的自然宽度缓存（px） */
    private final Map<View, Integer> natW = new HashMap<View, Integer>();
    /** 缓存自然宽度时对应的文字内容；文字变了才重新量 */
    private final Map<View, String> textSig = new HashMap<View, String>();
    /** 正在测量中（此时抑制 requestLayout，防止多跑一轮布局） */
    private boolean measuring;

    public FoldRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
    }

    /**
     * 登记一个可折叠控件。
     * 注册顺序即折叠优先级：先注册的先被收起。
     */
    public FoldRow foldable(View v) {
        if (v == null || foldables.contains(v)) return this;
        foldables.add(v);
        biz.put(v, Boolean.TRUE);
        lastAvail = -1;
        return this;
    }

    /**
     * 设置控件的「业务可见性」。
     * 业务上不该显示的（例如单 P 视频没有「下一集」），宽度记 0，永远不会被折叠逻辑弄乱。
     */
    public void setBusinessVisible(View v, boolean vis) {
        if (v == null) return;
        if (!biz.containsKey(v)) foldable(v);
        Boolean old = biz.get(v);
        if (old != null && old == vis) return;
        biz.put(v, vis);
        lastAvail = -1;
        requestLayout();
    }

    /** 当前被「折叠」掉的控件数（不含业务上本来就隐藏的） */
    public int foldedCount() {
        int n = 0;
        for (int i = 0; i < foldables.size() && i < foldState.length; i++) {
            if (foldState[i] && isBizVisible(foldables.get(i))) n++;
        }
        return n;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int avail = MeasureSpec.getSize(widthSpec);
        if (avail > 0) {
            measuring = true;
            try {
                foldTo(avail);
            } finally {
                measuring = false;
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /**
     * 按真实宽度决定折叠谁（幂等）。
     *
     * 固定区（不在 foldables 里的子 View）先占掉一部分宽度，
     * 剩下的分给可折叠控件；放不下就按优先级从前往后收。
     *
     * @param availPx 这一行可用的宽度（px）
     */
    public int foldTo(int availPx) {
        if (availPx <= 0) return 0;
        final int n = foldables.size();
        if (n == 0) return 0;
        if (availPx == lastAvail && foldState.length == n) return foldedCount();

        // 注意：宽度只看「业务可见性」，不看当前是不是被折叠掉了。
        // 否则被折叠的控件宽度记 0 → 算出来「放得下」→ 又把它放出来 → 反复横跳。
        final int[] w = new int[n];
        int fixed = 0;
        for (int i = 0; i < n; i++) {
            View v = foldables.get(i);
            w[i] = isBizVisible(v) ? naturalWidth(v, availPx) : 0;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (foldables.contains(c)) continue;
            fixed += naturalWidth(c, availPx);
        }

        boolean[] hide = RowFit.foldMask(w, availPx - fixed);
        if (foldState.length != n) foldState = new boolean[n];
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (foldState[i] != hide[i]) changed = true;
            foldState[i] = hide[i];
        }
        lastAvail = availPx;
        if (!changed) return foldedCount();

        for (int i = 0; i < n; i++) {
            View v = foldables.get(i);
            boolean vis = isBizVisible(v) && !hide[i];
            int want = vis ? VISIBLE : GONE;
            if (v.getVisibility() != want) v.setVisibility(want);
        }
        return foldedCount();
    }

    private boolean isBizVisible(View v) {
        Boolean b = biz.get(v);
        return b == null || b;
    }

    /**
     * 控件的自然宽度（px）：
     * 固定宽度的直接取 LayoutParams；
     * wrap_content 的按可用宽度量一次，拿到文字本身的宽度
     * （和 LinearLayout 真实排版时的口径一致）。
     * 文字内容没变就用缓存 —— 控制条每秒跳一次时间，不能每次都重量。
     */
    private int naturalWidth(View v, int availPx) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int w;
        if (lp != null && lp.width > 0) {
            w = lp.width;
        } else {
            String sig = (v instanceof TextView) ? ((TextView) v).getText().toString() : null;
            Integer cached = natW.get(v);
            if (cached != null && (sig == null || sig.equals(textSig.get(v)))) {
                w = cached;
            } else {
                v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, availPx), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                w = v.getMeasuredWidth();
                natW.put(v, w);
                if (sig != null) textSig.put(v, sig);
            }
        }
        if (v instanceof TextView) {
            int mx = ((TextView) v).getMaxWidth();
            if (mx > 0 && w > mx) w = mx;
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            w += mlp.leftMargin + mlp.rightMargin;
        }
        return w;
    }

    /**
     * 测量过程中不向外请求布局 —— 折叠结果会在同一轮测量里生效，
     * 多跑一轮只会让控制条闪一下。
     */
    @Override
    public void requestLayout() {
        if (measuring) return;
        super.requestLayout();
    }
}
