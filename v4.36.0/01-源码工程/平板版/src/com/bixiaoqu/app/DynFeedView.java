package com.bixiaoqu.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 「关注」动态流（底栏第二格）。
 *
 * 【v4.10.5 重写】上一版是 ListView + header + footer + Adapter 的组合，
 * 在部分机型上渲染第一屏时会直接闪退（点「动态」立刻退出）。
 * 这一版改成 ScrollView + LinearLayout 逐条填充：
 *   · 没有 Adapter、没有复用、没有 position 偏移，ListView 那套坑全部不存在；
 *   · 每一张卡片单独 try-catch，某条数据异常只跳过这一条，不影响整页；
 *   · 网络回调前判 attach，页面已销毁就不再碰 UI。
 * 一页十几条，逐条建视图的开销可以忽略。
 */
public class DynFeedView extends FrameLayout {

    public interface Listener {
        void onOpenVideo(String bvid, String title);
        void onOpenUp(String mid, String name);
    }

    /** 一条动态 */
    static class Dyn {
        String name = "", face = "", time = "", action = "", text = "", mid = "";
        String bvid = "", cover = "", title = "", dur = "", image = "";
        long play = 0, danmaku = 0, like = 0, comment = 0, forward = 0;
        boolean liked = false;
    }

    final ArrayList<Dyn> items = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final Listener listener;

    /**
     * 【v4.15.3】圆形头像轮廓：旧版每张卡片 buildCard 都 new 一个匿名
     * ViewOutlineProvider，快速滑动时反复分配。提取为全类共享的静态常量，零分配。
     */
    static final ViewOutlineProvider OVAL_OUTLINE = new ViewOutlineProvider() {
        @Override public void getOutline(View v, android.graphics.Outline o) {
            o.setOval(0, 0, Math.max(1, v.getWidth()), Math.max(1, v.getHeight()));
        }
    };

    /** 【v4.15.3】V 认证徽章背景：纯色圆形，全类共享，避免每张卡重复创建。 */
    static GradientDrawable vBadgeBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFFF5B942);
        return g;
    }

    ScrollView scroll;
    LinearLayout col, listBox;
    /** 【v4.16.1】左侧 UP 主头像栏（参照 B 站动态页：左栏选人 + 右侧卡片流） */
    LinearLayout sideCol, sideList;
    int sideW = 78;
    /** 当前筛选的 UP 主 mid，空 = 全部动态 */
    String filterMid = "";
    TextView segAll, segVideo, statusText, moreBtn, titleText;

    int type = 0;
    String offset = "";
    boolean hasMore = true, loading = false, everLoaded = false;
    // 【v4.13.3 动态串数据修复】请求序号 + 类型快照 + 重新挂载标记。
    // 切 tab 会把本视图 removeAllViews（detach），旧的异步回调返回时若已重新 attach，
    // 可能把「上一次请求/另一个分段」的数据 apply 进来，表现为动态里出现别的视频。
    int loadSeq = 0;
    int lastTypeLoaded = -1;
    boolean detachedSinceLoad = false;
    int cardW, coverW;
    /** 【v4.31.0】渲染批次号：列表重建后旧的续建任务要立刻作废 */
    int renderSeq = 0;

    // ---- 【v4.35.0】UP 空间内容 ----
    /**
     * 选中某个 UP 主后，直接去**他空间**取内容，而不是在「关注动态流」里本地筛。
     *
     * 旧逻辑的问题：左栏头像来自关注列表，而关注流这一页里未必有他的动态 ——
     * 点一下只会得到一句「该 UP 主 最近的动态里没有内容」，
     * 想看到东西还得自己点「加载更多」碰运气（还不一定成功）。
     *
     * 现在两条链路并行，先回先渲染：
     *   ① 投稿视频（arc/search）→ 他发过的视频，按投稿时间倒序；
     *   ② 空间动态（feed/space）→ 含视频的动态。
     * 两边按 bvid 去重合并，晚到的补齐空缺。
     */
    /**
     * 【v4.35.0】UP 空间链路的独立请求序号。
     *
     * 为什么不能和关注流共用 loadSeq：
     * 关注流还在飞的时候用户点了左栏头像，如果把 loadSeq 加一，
     * 关注流的回调会被判成「过期」直接丢掉 → items 永远是空的 →
     * 用户再点一下头像取消筛选，回来的是一片空白。
     * 两条链路各有各的序号，互不牵连。
     */
    int upSeq = 0;
    /** 投稿视频当前页码（1 起） */
    int upPage = 1;
    /** 空间动态翻页游标 */
    String upOffset = "";
    /** 合并后的 UP 内容 */
    final ArrayList<Dyn> upItems = new ArrayList<>();
    /** 去重键（视频按 bvid，图文动态按 mid+时间+摘要） */
    final HashSet<String> upSeen = new HashSet<>();
    /** 两条链路各自是否已结束（都结束且列表为空 → 才算「真的没有」） */
    boolean upVideosDone = false, upDynDone = false;
    /** 【v4.35.0】投稿视频链路是否还有下一页 */
    boolean upMoreVideos = true;
    /** 【v4.35.0】空间动态链路是否还有下一页 */
    boolean upMoreDyn = true;
    /** 【v4.35.0】投稿视频链路的错误（多数情况是「这个 UP 没有投稿」，不算故障） */
    String upErrVideos = "";
    /** 【v4.35.0】空间动态链路的错误（这个才是真故障） */
    String upErrDyn = "";
    /** 上一次筛选用的 UP 名（卡片作者名兜底用） */
    String filterFace = "";

    public DynFeedView(Context c, Listener l) {
        super(c);
        listener = l;
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        // 横屏 / 平板侧栏更宽一点，头像不至于挤成一条（与主页宽屏判定一致）
        sideW = dp(MainActivity.isWideScreen(c) ? 96 : 78);
        cardW = screen - sideW - dp(32);  // 卡片左右各 16dp 外边距
        coverW = cardW - dp(24);          // 卡片内部左右各 12dp 内边距
        try {
            build();
        } catch (Throwable e) {
            note("build", e);
        }
        // 【v4.13.3】构造时不再 load(true)：此时视图尚未 attach，回调会被 alive() 丢弃。
        // 统一由 MainActivity 在 addView 后调用 refreshOnShow() 拉数据，避免请求浪费与串数据。
        loadVisits();
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 非致命异常写进 boot.txt，方便在「我的 → 崩溃日志」里查 */
    static void note(String where, Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            CrashHandler.note("dyn/" + where, sw.toString());
        } catch (Throwable ignore) { }
    }

    boolean alive() {
        try { return isAttachedToWindow(); } catch (Throwable e) { return false; }
    }

    // 【v4.13.3 动态串数据修复】切走 tab 时本视图被 detach，进行中的请求回调会因 !alive() 被丢弃
    // （数据丢失）；切回来时若只靠 refreshIfEmpty（items 非空就不重载），就会残留旧数据或状态错乱。
    // 这里在 detach 时打标记，重新 attach 后强制拉一次当前分段的新鲜数据，覆盖旧内容。
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detachedSinceLoad = true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    /** 由 MainActivity.switchTab(1) / onResume 调用：重新挂载后若数据陈旧则强制重载当前分段 */
    public void refreshOnShow() {
        try {
            if (BiliApi.loggedInUid() == null) {
                status("登录后可以看到关注的人的最新动态");
                return;
            }
            // 数据属于别的分段、或 detach 期间丢过数据 → 强制重新拉
            if (detachedSinceLoad || lastTypeLoaded != type || items.isEmpty()) {
                detachedSinceLoad = false;
                loadSeq++;                 // 让飞行中的旧回调全部作废
                loading = false;
                offset = "";
                hasMore = true;
                load(true);
            }
        } catch (Throwable e) {
            note("refreshOnShow", e);
        }
    }

    // ------------------------------------------------------------ 结构

    void build() {
        // 【v4.16.1】整页改成「左头像栏 + 右内容流」两栏
        LinearLayout mainRow = new LinearLayout(getContext());
        mainRow.setOrientation(LinearLayout.HORIZONTAL);

        mainRow.addView(buildSideCol(), new LinearLayout.LayoutParams(
                sideW, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout rightBox = new FrameLayout(getContext());

        scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(96));      // 给悬浮底栏留出空间

        col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(buildHeader());

        listBox = new LinearLayout(getContext());
        listBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(getContext());
        statusText.setTextSize(12.5f);
        statusText.setTextColor(MainActivity.C_SUB);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(24), dp(28), dp(24), dp(20));
        statusText.setText("加载中…");
        col.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        moreBtn = new TextView(getContext());
        moreBtn.setTextSize(12.5f);
        moreBtn.setTextColor(MainActivity.C_SUB);
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setPadding(0, dp(14), 0, dp(24));
        moreBtn.setText("加载更多");
        moreBtn.setVisibility(View.GONE);
        moreBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { load(false); }
        });
        col.addView(moreBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rightBox.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainRow.addView(rightBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        addView(mainRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 【v4.16.1】左侧头像栏：顶部「全部动态」+ 关注的 UP 主头像竖列。
     * 点头像即筛选该 UP 主 的动态，再点一次取消筛选。
     */
    LinearLayout buildSideCol() {
        sideCol = new LinearLayout(getContext());
        sideCol.setOrientation(LinearLayout.VERTICAL);
        sideCol.setBackgroundColor(MainActivity.DARK ? 0xFF15171C : 0xFFF1F3F7);

        // ---- 顶部：全部动态 ----
        LinearLayout allCell = new LinearLayout(getContext());
        allCell.setOrientation(LinearLayout.VERTICAL);
        allCell.setGravity(Gravity.CENTER_HORIZONTAL);
        allCell.setPadding(0, dp(8), 0, dp(4));

        TextView allIcon = new TextView(getContext());
        allIcon.setText("全");
        allIcon.setTextColor(Color.WHITE);
        allIcon.setTextSize(13);
        allIcon.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable allBg =
                new android.graphics.drawable.GradientDrawable();
        allBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        allBg.setColor(MainActivity.C_ACC);
        allIcon.setBackground(allBg);
        allCell.addView(allIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView allText = new TextView(getContext());
        allText.setText("全部");
        allText.setTextSize(10);
        allText.setGravity(Gravity.CENTER);
        allText.setSingleLine(true);
        allCell.addView(allText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sideCol.addView(allCell, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        allCell.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { setFilter(""); }
        });
        allAllCell = allCell;

        View sep = new View(getContext());
        sep.setBackgroundColor(MainActivity.DARK ? 0xFF23262C : 0xFFE2E6EC);
        LinearLayout.LayoutParams sepp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        sepp.topMargin = dp(6);
        sideCol.addView(sep, sepp);

        // ---- UP 主头像竖列（可滚动） ----
        android.widget.ScrollView sideScroll = new android.widget.ScrollView(getContext());
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setHorizontalScrollBarEnabled(false);
        sideList = new LinearLayout(getContext());
        sideList.setOrientation(LinearLayout.VERTICAL);
        sideScroll.addView(sideList, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sideCol.addView(sideScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return sideCol;
    }

    /** 全部动态入口（用于高亮） */
    LinearLayout allAllCell;

    /** 设置筛选的 UP 主；mid 为空表示全部 */
    void setFilter(String mid) {
        try {
            String m = (mid == null) ? "" : mid;
            boolean changed = !m.equals(filterMid);
            filterMid = m;
            applySideStyle();
            if (changed) render();      // 没变化就不重建卡片，省一次重绘
            if (titleText != null) {
                titleText.setText(filterMid.isEmpty() ? "关注"
                        : (filterName.isEmpty() ? "关注" : filterName));
            }
            if (changed) {
                if (filterMid.isEmpty()) {
                    // 取消筛选：回到关注流，顺手清掉 UP 空间的临时数据
                    upSeq++;          // 【v4.35.0】在飞的 UP 请求直接作废
                    upItems.clear();
                    upSeen.clear();
                    upVideosDone = false;
                    upDynDone = false;
                    upErrVideos = "";
                    upErrDyn = "";
                    if (statusText != null) statusText.setVisibility(View.GONE);
                } else {
                    // 【v4.35.0】选中某个 UP 后立刻去他空间取内容 —— 不再只做本地筛选
                    loadUp(filterMid, true);
                }
            }
        } catch (Throwable e) {
            // 【v4.34.0】筛选是纯交互增强，失败时退化成「保持原样」，绝不能连带闪退
            note("filter", e);
        }
    }

    String filterName = "";

    /**
     * 【v4.34.0 闪退修复】侧栏头像高亮。
     *
     * 旧版这里写的是：
     *     ImageView av = (ImageView) ((LinearLayout) cell.getChildAt(0)).getChildAt(0);
     * 但左栏每一格的第 0 个子 View 是 avBox —— 一个 **FrameLayout**（头像 + 描边层），
     * 不是 LinearLayout。这行强转在运行时会抛 ClassCastException：
     *
     *   · 渲染时 renderSide() 的调用点被 try-catch 包着 → 异常被吞掉，
     *     所以左栏头像照样显示得出（异常发生在 addView 之后），平时完全看不出问题；
     *   · 点头像时走 setFilter() → applySideStyle()，**这条路径没有任何 try 保护**
     *     → 主线程抛异常 → 进程直接退出 → 用户看到的就是「点一下左栏头像就闪退」。
     *
     * 修法：全部改成「先判断类型、再取子视图」，任何一步不符合预期就跳过这一格，
     * 绝不让一次样式刷新干掉整个页面。
     */
    void applySideStyle() {
        try {
            if (sideList == null) return;
            if (allAllCell instanceof ViewGroup) {
                View t = ((ViewGroup) allAllCell).getChildAt(1);
                if (t instanceof TextView) {
                    ((TextView) t).setTextColor(filterMid.isEmpty()
                            ? MainActivity.C_ACC : MainActivity.C_SUB);
                }
            }
            for (int i = 0; i < sideList.getChildCount(); i++) {
                View c = sideList.getChildAt(i);
                if (!(c instanceof ViewGroup)) continue;
                ViewGroup cell = (ViewGroup) c;
                Object tag = cell.getTag();
                if (!(tag instanceof String)) continue;
                boolean on = ((String) tag).equals(filterMid);
                // 名字（第 1 个子 View）
                View nv = cell.getChildAt(1);
                if (nv instanceof TextView) {
                    ((TextView) nv).setTextColor(on ? MainActivity.C_ACC : MainActivity.C_SUB);
                }
                // 头像（第 0 个子 View 是 avBox，里面第 0 个才是 ImageView）
                View box = cell.getChildAt(0);
                if (box instanceof ViewGroup) {
                    View av = ((ViewGroup) box).getChildAt(0);
                    if (av != null) av.setBackground(ringBg(on));
                }
            }
        } catch (Throwable ignore) {
            // 样式刷新失败绝不能影响功能：最多头像不高亮，功能照常
        }
    }

    /** 头像外圈：选中时粉色描边，未选中透明 */
    android.graphics.drawable.GradientDrawable ringBg(boolean on) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        // 未加载出图时用占位灰，选中时加粉色描边
        g.setColor(MainActivity.DARK ? 0xFF2A2E37 : 0xFFDDE1E8);
        if (on) g.setStroke(dp(2), MainActivity.C_ACC);
        return g;
    }

    View buildHeader() {
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.VERTICAL);

        // ---- 标题栏：关注 + 刷新 ----
        FrameLayout titleBar = new FrameLayout(getContext());
        TextView title = new TextView(getContext());
        title.setText("关注");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleBar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        titleText = title;

        TextView refresh = new TextView(getContext());
        refresh.setText("刷新");
        refresh.setTextColor(MainActivity.C_SUB);
        refresh.setTextSize(12);
        refresh.setPadding(dp(14), dp(10), dp(16), dp(10));
        refresh.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                load(true);
                loadVisits();
            }
        });
        titleBar.addView(refresh, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        head.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 全部 / 视频 ----
        LinearLayout segRow = new LinearLayout(getContext());
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setPadding(dp(14), 0, dp(14), dp(10));
        segAll = segCell("全部");
        segVideo = segCell("视频");
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        s1.rightMargin = dp(4);
        segRow.addView(segAll, s1);
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        s2.leftMargin = dp(4);
        segRow.addView(segVideo, s2);
        segAll.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { switchType(0); }
        });
        segVideo.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { switchType(1); }
        });
        head.addView(segRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View gap = new View(getContext());
        gap.setBackgroundColor(MainActivity.DARK ? 0xFF0D0E11 : 0xFFEDEFF3);
        head.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        applySegStyle();
        return head;
    }

    TextView segCell(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    void switchType(int t) {
        if (t == type && everLoaded) return;
        type = t;
        applySegStyle();
        offset = "";
        hasMore = true;
        items.clear();
        // 【v4.35.0】分段变了，UP 空间的内容也要重新按新分段筛
        upItems.clear();
        upSeen.clear();
        upPage = 1;
        upOffset = "";
        upVideosDone = false;
        upDynDone = false;
        upMoreVideos = true;
        upMoreDyn = true;
        if (listBox != null) listBox.removeAllViews();
        // 【v4.13.3】切分段后必须作废旧分段的在飞请求，否则旧回调会把别的分段数据填进来（串视频）
        loadSeq++;
        upSeq++;          // 【v4.35.0】UP 空间链路同样作废
        loading = false;
        load(true);
    }

    void applySegStyle() {
        if (segAll == null || segVideo == null) return;
        boolean dark = MainActivity.DARK;
        // 【v4.15.2】选中文字改白色（在粉玻璃上更清晰），未选中用粉色主色
        segAll.setTextColor(type == 0 ? Color.WHITE : MainActivity.C_ACC);
        segVideo.setTextColor(type == 1 ? Color.WHITE : MainActivity.C_ACC);
        segAll.setTypeface(type == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        segVideo.setTypeface(type == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        segAll.setBackground(GlassUtil.seg(type == 0, dark, getContext()));
        segVideo.setBackground(GlassUtil.seg(type == 1, dark, getContext()));
    }

    // ------------------------------------------------------------ 最常访问

    /**
     * 【v4.16.1】拉取关注列表 → 渲染到左栏（不再在页面顶部放横排「最常访问」，
     * 那份布局与左栏功能重复，删掉后代码更干净）。
     */
    void loadVisits() {
        if (BiliApi.loggedInUid() == null) return;
        new Thread(new Runnable() { @Override public void run() {
            final JSONArray list;
            try { list = BiliApi.followings(); } catch (Throwable e) { return; }
            if (list == null || list.length() == 0) return;
            ui.post(new Runnable() { @Override public void run() {
                if (!alive()) return;
                try { renderSide(list); } catch (Throwable e) { note("visits", e); }
            } });
        } }).start();
    }

    /** 渲染左栏：全部动态 + 关注的 UP 主头像 */
    void renderSide(JSONArray list) {
        if (sideList == null) return;
        sideList.removeAllViews();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            final String mid = o.optString("mid", "");
            final String uname = o.optString("uname", "");
            if (mid.isEmpty()) continue;
            String face = ImgLoader.https(o.optString("face", ""));
            face = ImgLoader.face(face, 120);
            final String faceUrl = face;

            LinearLayout one = new LinearLayout(getContext());
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER_HORIZONTAL);
            one.setPadding(0, dp(7), 0, dp(7));
            one.setTag(mid);
            one.setClickable(true);

            FrameLayout avBox = new FrameLayout(getContext());
            ImageView av = new ImageView(getContext());
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            av.setBackgroundColor(ImgLoader.placeholderColor());
            av.setClipToOutline(true);
            av.setOutlineProvider(OVAL_OUTLINE);
            avBox.addView(av, new FrameLayout.LayoutParams(dp(42), dp(42)));
            ImgLoader.load(av, face, dp(42), dp(42));
            one.addView(avBox, new LinearLayout.LayoutParams(dp(42), dp(42)));

            TextView nm = new TextView(getContext());
            nm.setText(uname);
            nm.setTextColor(MainActivity.C_SUB);
            nm.setTextSize(10);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(4);
            one.addView(nm, nlp);

            one.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        // 【v4.34.0】再点同一个头像 = 取消筛选，标题也要跟着回「关注」，
                        // 旧版只清了 filterMid，filterName 还留着，标题会一直显示上一个名字
                        if (filterMid.equals(mid)) {
                            filterName = "";
                            filterFace = "";
                            setFilter("");
                        } else {
                            filterName = uname;
                            filterFace = faceUrl;
                            setFilter(mid);
                        }
                    } catch (Throwable e) {
                        note("side", e);
                    }
                }
            });
            sideList.addView(one, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        applySideStyle();
    }

    // ------------------------------------------------------------ 数据


    void status(String msg) {
        if (statusText != null) {
            statusText.setText(msg);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 【v4.35.0】当前该显示哪一份数据。
     * 全部动态 → 关注流 items；选了某个 UP → 他的空间内容 upItems。
     */
    ArrayList<Dyn> sourceItems() {
        return filterMid.isEmpty() ? items : upItems;
    }

    void load(final boolean first) {
        if (loading) return;
        // 【v4.35.0】选了 UP 主时走空间链路，不走关注流
        if (!filterMid.isEmpty()) {
            loadUp(filterMid, first);
            return;
        }
        if (BiliApi.loggedInUid() == null) {
            status("登录后可以看到关注的人的最新动态");
            if (moreBtn != null) moreBtn.setVisibility(View.GONE);
            return;
        }
        if (!first && !hasMore) return;
        loading = true;
        detachedSinceLoad = false;
        final int seq = ++loadSeq;              // 【v4.13.3】本次请求序号
        final int typeAtRequest = type;         // 【v4.13.3】捕获分段快照，防止请求飞行中被改
        if (first) {
            status("加载中…");
            if (moreBtn != null) moreBtn.setVisibility(View.GONE);
        } else if (moreBtn != null) {
            moreBtn.setText("加载中…");
        }

            final int typeF = typeAtRequest;
            new Thread(new Runnable() { @Override public void run() {
            final ArrayList<Dyn> got = new ArrayList<>();
            final String[] err = {null};
            final String[] next = {""};
            final boolean[] more = {false};
            try {
                JSONObject d = BiliApi.followDynamic(typeF, first ? "" : offset);
                next[0] = d.optString("offset", "");
                more[0] = d.optBoolean("has_more", false);
                JSONArray its = d.optJSONArray("items");
                if (its != null) {
                    for (int i = 0; i < its.length(); i++) {
                        Dyn dy = parse(its.optJSONObject(i));
                        if (dy != null) got.add(dy);
                    }
                }
            } catch (Throwable e) {
                err[0] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                // 【v4.13.3】过期/串段请求直接丢弃：序号被后续请求顶替，或分段已切换
                if (seq != loadSeq || typeF != type) return;
                loading = false;
                everLoaded = true;
                lastTypeLoaded = typeF;
                if (!alive()) return;   // detach 期间数据不 apply，等重新 attach 后由 refreshOnShow 重载
                try {
                    applyLoaded(first, got, err[0], next[0], more[0]);
                } catch (Throwable e) {
                    note("apply", e);
                    status("渲染出错：" + e.getClass().getSimpleName());
                }
            } });
        } }).start();
    }

    // ------------------------------------------------------------ 【v4.35.0】UP 空间内容

    /** 链路标记：投稿视频 / 空间动态 */
    private static final int PART_VIDEOS = 0, PART_DYNAMIC = 1;

    /**
     * 加载某个 UP 主 的内容：投稿视频 + 空间动态，两条链路并行。
     *
     * 先回来先渲染 —— 用户点完头像立刻就能看到东西，不用等两条都到齐；
     * 后回来的那条按 bvid 去重后补进来，不会出现重复卡片。
     */
    void loadUp(final String mid, final boolean first) {
        if (mid == null || mid.isEmpty()) return;
        if (!first && !upHasMore()) return;
        final int seq = ++upSeq;      // 【v4.35.0】与关注流分开计数，互不误伤
        final int typeF = type;
        loading = true;
        detachedSinceLoad = false;
        // 【v4.35.0】新一轮开始：两条链路的完成标记都要清掉，
        // 否则上一轮已经置 true → 这一轮刚开始就误判「都回来了」
        upVideosDone = false;
        upDynDone = false;
        if (first) {
            upItems.clear();
            upSeen.clear();
            upPage = 1;
            upOffset = "";
            upMoreVideos = true;
            upMoreDyn = true;
            upErrVideos = "";
            upErrDyn = "";
            // 【v4.35.0】先把关注流里已有的、属于他的内容铺上去（命中本地缓存 = 0 延迟）
            seedUpFromFeed(mid);
            render();
            if (upItems.isEmpty()) {
                status("正在加载 " + (filterName.isEmpty() ? "该 UP 主" : filterName) + " 的内容…");
            } else if (statusText != null) {
                statusText.setVisibility(View.GONE);
            }
            if (moreBtn != null) moreBtn.setVisibility(View.GONE);
        } else if (moreBtn != null) {
            moreBtn.setText("加载中…");
        }

        final int page = upPage;
        final String off = upOffset;
        // ① 投稿视频：他发过的视频，按投稿时间倒序
        startUpPart(mid, seq, typeF, PART_VIDEOS, page, off, first);
        // ② 空间动态：含视频的动态
        startUpPart(mid, seq, typeF, PART_DYNAMIC, page, off, first);
    }

    /** 把关注流里已有的该 UP 内容先塞进 upItems（本地命中，0 延迟） */
    void seedUpFromFeed(String mid) {
        for (int i = 0; i < items.size(); i++) {
            Dyn d = items.get(i);
            if (d == null) continue;
            if (!mid.equals(d.mid)) continue;
            if (upSeen.add(keyOf(d))) upItems.add(d);
        }
    }

    /** 一条内容的去重键 */
    static String keyOf(Dyn d) {
        if (d == null) return "";
        if (!d.bvid.isEmpty()) return "v:" + d.bvid;
        return "d:" + d.mid + ":" + d.time + ":" + d.text.hashCode();
    }

    /** 发起一条 UP 空间链路 */
    void startUpPart(final String mid, final int seq, final int typeF, final int part,
                     final int page, final String off, final boolean first) {
        try {
            new Thread(new Runnable() { @Override public void run() {
                NetPrio.apply(NetPrio.IMAGE);
                final ArrayList<Dyn> got = new ArrayList<>();
                final String[] err = {null};
                final String[] next = {""};
                final boolean[] more = {false};
                try {
                    if (part == PART_VIDEOS) {
                        JSONArray vs = BiliApi.userVideos(mid, page);
                        if (vs != null) {
                            for (int i = 0; i < vs.length(); i++) {
                                Dyn dy = fromVlist(vs.optJSONObject(i), mid);
                                if (dy != null) got.add(dy);
                            }
                        }
                        // 投稿列表每页 30 条；返回满页就认为还有下一页
                        more[0] = vs != null && vs.length() >= 30;
                    } else {
                        JSONObject d = BiliApi.dynamicFeed(mid, off);
                        next[0] = d.optString("offset", "");
                        more[0] = d.optBoolean("has_more", false);
                        JSONArray its = d.optJSONArray("items");
                        if (its != null) {
                            for (int i = 0; i < its.length(); i++) {
                                Dyn dy = parse(its.optJSONObject(i));
                                if (dy != null) got.add(dy);
                            }
                        }
                    }
                } catch (Throwable e) {
                    err[0] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                ui.post(new Runnable() { @Override public void run() {
                    if (seq != upSeq || typeF != type || !mid.equals(filterMid)) return;
                    if (!alive()) return;
                    try {
                        applyUpPart(mid, part, got, err[0], next[0], more[0], first, page);
                    } catch (Throwable e) {
                        note("up", e);
                    }
                } });
            } }, "bxq-up").start();
        } catch (Throwable e) {
            note("upStart", e);
        }
    }

    /** 合并一条链路回来的内容 */
    void applyUpPart(String mid, int part, ArrayList<Dyn> got, String err, String next,
                     boolean more, boolean first, int page) {
        if (part == PART_DYNAMIC) {
            upDynDone = true;
            if (err == null) {
                if (next != null && !next.isEmpty()) upOffset = next;
                upMoreDyn = more;
            } else {
                upErrDyn = err;
            }
        } else {
            upVideosDone = true;
            if (err == null) {
                upMoreVideos = more;
                upPage = page + 1;
            } else {
                // 投稿接口报错最常见的原因是「这个 UP 根本没投稿」，
                // 不算故障 —— 空间动态那一路照样能出内容。
                upErrVideos = err;
            }
        }
        int added = 0;
        for (int i = 0; i < got.size(); i++) {
            Dyn d = got.get(i);
            if (d == null) continue;
            if (d.mid.isEmpty()) d.mid = mid;
            // 投稿列表里没有作者字段，用左栏记住的名字/头像补上
            if (d.name.isEmpty()) d.name = filterName;
            if (d.face.isEmpty()) d.face = filterFace;
            // 「视频」分段只留带 bvid 的
            if (type == 1 && d.bvid.isEmpty()) continue;
            if (!upSeen.add(keyOf(d))) continue;
            upItems.add(d);
            added++;
        }
        if (added > 0) {
            render();
            if (statusText != null) statusText.setVisibility(View.GONE);
        }
        // 两条都回来了才下结论
        if (upVideosDone && upDynDone) {
            loading = false;
            everLoaded = true;
            lastTypeLoaded = type;
            if (upItems.isEmpty()) {
                // 只有空间动态这一路失败才算真故障；
                // 投稿接口挂掉多半只是「这个 UP 没投稿」，别吓人。
                status(upErrDyn.isEmpty()
                        ? "该 UP 主 还没有发布内容"
                        : "加载失败：" + upErrDyn + "\n点右上角「刷新」重试");
            }
        }
        if (moreBtn != null) {
            boolean more2 = upHasMore();
            moreBtn.setText(more2 ? "加载更多" : "没有更多了");
            moreBtn.setEnabled(more2);
            moreBtn.setVisibility(upItems.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** 【v4.35.0】UP 内容是否还有下一页（两条链路任一还有就还能翻） */
    boolean upHasMore() {
        return upMoreVideos || upMoreDyn;
    }

    /** 投稿视频条目 → 动态卡片数据 */
    static Dyn fromVlist(JSONObject v, String mid) {
        try {
            if (v == null) return null;
            Dyn d = new Dyn();
            d.mid = mid == null ? "" : mid;
            d.bvid = v.optString("bvid", "");
            if (d.bvid.isEmpty()) return null;
            d.title = v.optString("title", "");
            d.cover = v.optString("pic", "");
            d.play = v.optLong("play", 0);
            d.dur = v.optString("length", "");
            d.action = "投稿视频";
            long created = v.optLong("created", 0);
            if (created > 0) d.time = dateText(created * 1000L);
            return d;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 时间戳 → 「2024-05-06」 */
    static String dateText(long ms) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            return f.format(new java.util.Date(ms));
        } catch (Throwable e) {
            return "";
        }
    }

    void applyLoaded(boolean first, ArrayList<Dyn> got, String err,
                     String next, boolean more) {
        if (err != null) {
            if (filterMid.isEmpty()) status("加载失败：" + err);
            return;
        }
        offset = next;
        hasMore = more;
        if (first) items.clear();
        items.addAll(got);
        if (!filterMid.isEmpty()) {
            // 【v4.35.0】选了 UP 主时，页面显示的是 UP 空间内容；
            // 关注流这一路只是「后台补货」，不碰空态文案，也不碰「加载更多」按钮
            // （那是 UP 链路自己在管）。否则两路会互相覆盖状态。
            return;
        }
        render();
        // 【v4.16.1】左栏筛选时按「筛选后可见条数」判断空态
        ArrayList<Dyn> src = sourceItems();
        int visible = 0;
        for (int i = 0; i < src.size(); i++) {
            Dyn d = src.get(i);
            if (d == null) continue;
            if (filterMid.isEmpty() || filterMid.equals(d.mid)) visible++;
        }
        if (visible == 0) {
            // 【v4.35.0】走到这里一定是「全部动态」分支（选了 UP 主会走 loadUp），
            // 不再出现「该 UP 主 没有内容」这种误报。
            status(type == 1 ? "关注的人最近没有投稿" : "还没有动态，去关注几个 UP 主吧");
        } else {
            statusText.setVisibility(View.GONE);
        }
        moreBtn.setText(hasMore ? "加载更多" : "没有更多了");
        moreBtn.setEnabled(hasMore);
        moreBtn.setVisibility(src.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 重建列表：每条单独 try-catch，坏数据只跳过自己。
     *
     * 【v4.31.0 首屏分批】旧版一次 for 循环建完 20 条卡片：每条都要 new 十几个 View、
     * 发两三个图片请求，全部挤在同一个 UI 帧里 —— 于是「点动态」要黑一下才出内容，
     * 而且 40 多个图片请求同时排队，最先露出来的那几张反而排在后面。
     * 现在：前 6 条同步建（正好一屏），其余按每帧 6 条续建；
     * 同时把后面 8 条的封面先推进低优先级队列 —— 边建边下，滑下去就是内存直出。
     */
    void render() {
        if (listBox == null) return;
        // 【v4.35.0】重建列表会清空子 View，ScrollView 的滚动位置会被一起清零。
        // 「加载更多」时用户正停在列表底部，一重建就被弹回顶部 —— 必须记住并还原。
        final int keepY = (scroll != null) ? scroll.getScrollY() : 0;
        listBox.removeAllViews();
        final int seq = ++renderSeq;
        final ArrayList<Dyn> vis = new ArrayList<>();
        ArrayList<Dyn> src = sourceItems();
        for (int i = 0; i < src.size(); i++) {
            Dyn d = src.get(i);
            if (d == null) continue;
            // 【v4.16.1】左栏筛选：只看某个 UP 主 的动态
            if (!filterMid.isEmpty() && !filterMid.equals(d.mid)) continue;
            vis.add(d);
        }
        int first = Math.min(vis.size(), 6);
        for (int i = 0; i < first; i++) addCard(vis.get(i));
        prefetchCovers(vis, first, 8);
        if (vis.size() > first) {
            ui.post(new Runnable() { @Override public void run() {
                renderRest(vis, first, seq, keepY);
            } });
        } else {
            restoreScroll(keepY);
        }
        // 【v4.35.0】空态文案统一由加载器决定（两条链路都回来之前不下结论），
        // 这里不再抢着写「没有内容」—— 否则会出现「刚点开就报没有，点一下又有了」。
    }

    /** 【v4.31.0】每帧续建一批，避免一次建太多把首屏卡住 */
    void renderRest(final ArrayList<Dyn> vis, final int from, final int seq, final int keepY) {
        if (seq != renderSeq || listBox == null) return;
        int end = Math.min(vis.size(), from + 6);
        for (int i = from; i < end; i++) addCard(vis.get(i));
        if (end < vis.size()) {
            ui.post(new Runnable() { @Override public void run() {
                renderRest(vis, end, seq, keepY);
            } });
        } else {
            restoreScroll(keepY);
        }
    }

    /** 【v4.35.0】列表重建后把滚动位置还回去（用户没动过才还） */
    void restoreScroll(final int y) {
        if (y <= 0 || scroll == null) return;
        ui.post(new Runnable() { @Override public void run() {
            try {
                if (scroll != null && scroll.getScrollY() == 0) scroll.scrollTo(0, y);
            } catch (Throwable ignore) { }
        } });
    }

    void addCard(Dyn d) {
        try {
            listBox.addView(buildCard(d));
        } catch (Throwable e) {
            note("card", e);
        }
    }

    /** 【v4.31.0】预取还没建出来的那几条的封面（低优先级，不抢可见项） */
    void prefetchCovers(ArrayList<Dyn> vis, int from, int count) {
        try {
            if (coverW <= 0) return;
            int end = Math.min(vis.size(), from + count);
            for (int i = from; i < end; i++) {
                Dyn d = vis.get(i);
                if (d == null) continue;
                String u = !d.cover.isEmpty() ? d.cover : d.image;
                if (u == null || u.isEmpty()) continue;
                ImgLoader.prefetch(ImgLoader.https(u), coverW, coverW * 9 / 16);
            }
        } catch (Throwable ignore) { }
    }

    // ------------------------------------------------------------ 卡片

    View buildCard(final Dyn d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        // 【v4.15.2】动态卡片改为液态玻璃材质，与底栏 / 搜索页 / 设置页统一
        card.setBackground(GlassUtil.card(MainActivity.DARK, 12, getContext()));
        card.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(16);
        clp.rightMargin = dp(16);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // ---- 作者行 ----
        LinearLayout headBox = new LinearLayout(getContext());
        headBox.setOrientation(LinearLayout.HORIZONTAL);
        headBox.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(getContext());
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundColor(ImgLoader.placeholderColor());
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(OVAL_OUTLINE);   // 【v4.15.3】复用静态轮廓，避免每次分配
        headBox.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));

        String face = ImgLoader.https(d.face);
        face = ImgLoader.face(face, 96);
        ImgLoader.load(avatar, face, dp(38), dp(38));

        LinearLayout names = new LinearLayout(getContext());
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(10);
        headBox.addView(names, nlp);

        LinearLayout nameRow = new LinearLayout(getContext());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(getContext());
        name.setText(d.name);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(MainActivity.C_ACC);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView vBadge = new TextView(getContext());
        vBadge.setText("V");
        vBadge.setTextColor(Color.WHITE);
        vBadge.setTextSize(7);
        vBadge.setGravity(Gravity.CENTER);
        vBadge.setBackground(vBadgeBg());
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(13), dp(13));
        vlp.leftMargin = dp(4);
        nameRow.addView(vBadge, vlp);
        names.addView(nameRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(getContext());
        StringBuilder sb = new StringBuilder();
        if (!d.time.isEmpty()) sb.append(d.time);
        if (!d.action.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(d.action);
        }
        sub.setText(sb.toString());
        sub.setTextSize(11.5f);
        sub.setTextColor(MainActivity.C_SUB);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        names.addView(sub, slp);

        ImageView more = Ic.view(getContext(), "more", MainActivity.C_SUB, 16);
        more.setPadding(dp(8), dp(8), dp(2), dp(8));
        more.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { showMore(d); }
        });
        headBox.addView(more, new LinearLayout.LayoutParams(dp(30), dp(30)));

        card.addView(headBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 正文 ----
        if (!d.text.isEmpty()) {
            TextView text = new TextView(getContext());
            text.setText(d.text);
            text.setTextSize(13.5f);
            text.setTextColor(MainActivity.C_FG);
            text.setLineSpacing(dp(3), 1f);
            text.setMaxLines(5);
            text.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(8);
            card.addView(text, tlp);
        }

        // ---- 视频封面 ----
        boolean hasVideo = !d.bvid.isEmpty();
        if (hasVideo) {
            FrameLayout vbox = new FrameLayout(getContext());
            vbox.setClipToOutline(true);
            vbox.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, Math.max(1, v.getWidth()),
                            Math.max(1, v.getHeight()), dp(8));
                }
            });

            ImageView cover = new ImageView(getContext());
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(ImgLoader.placeholderColor());
            vbox.addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImgLoader.load(cover, ImgLoader.https(d.cover), coverW, coverW * 9 / 16);

            LinearLayout bar = new LinearLayout(getContext());
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xCC000000, 0x00000000}));
            bar.setPadding(dp(8), dp(4), dp(8), dp(5));

            TextView vDur = new TextView(getContext());
            vDur.setText(d.dur);
            vDur.setTextColor(Color.WHITE);
            vDur.setTextSize(10.5f);
            bar.addView(vDur, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView vMeta = new TextView(getContext());
            // 【v4.35.0】投稿视频条目没有弹幕数，别显示成「0弹幕」
            String meta = VideoItem.views(d.play) + "播放";
            if (d.danmaku > 0) meta += "  " + VideoItem.views(d.danmaku) + "弹幕";
            vMeta.setText(meta);
            vMeta.setTextColor(0xCCFFFFFF);
            vMeta.setTextSize(10.5f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = dp(8);
            bar.addView(vMeta, mlp);

            vbox.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));

            LinearLayout.LayoutParams vbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, coverW * 9 / 16);
            vbp.topMargin = dp(9);
            card.addView(vbox, vbp);
        }

        // ---- 图片动态 ----
        boolean hasImg = !hasVideo && !d.image.isEmpty();
        if (hasImg) {
            ImageView img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(ImgLoader.placeholderColor());
            img.setClipToOutline(true);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, Math.max(1, v.getWidth()),
                            Math.max(1, v.getHeight()), dp(8));
                }
            });
            ImgLoader.load(img, ImgLoader.https(d.image), coverW, coverW * 9 / 16);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, coverW * 9 / 16);
            ilp.topMargin = dp(9);
            card.addView(img, ilp);
        }

        // ---- 标题 ----
        if (hasVideo && !d.title.isEmpty()) {
            TextView title = new TextView(getContext());
            title.setText(d.title);
            title.setTextSize(15.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(MainActivity.C_FG);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl2.topMargin = dp(9);
            card.addView(title, tl2);
        }

        // ---- 转发 / 评论 / 点赞 ----
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        alp.topMargin = dp(4);
        card.addView(actions, alp);

        actions.addView(actionCell("share",
                d.forward > 0 ? VideoItem.views(d.forward) : "转发",
                MainActivity.C_SUB, false, d), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        actions.addView(actionCell("comment",
                d.comment > 0 ? VideoItem.views(d.comment) : "评论",
                MainActivity.C_SUB, false, d), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        actions.addView(actionCell("like",
                d.like > 0 ? VideoItem.views(d.like) : "点赞",
                MainActivity.C_SUB, true, d), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        card.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!d.bvid.isEmpty() && listener != null) {
                    listener.onOpenVideo(d.bvid, d.title);
                }
            }
        });
        return card;
    }

    /** 一个操作格：图标 + 文案；isLike 时挂点赞逻辑 */
    LinearLayout actionCell(String iconName, String label, int color,
                            final boolean isLike, final Dyn d) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setClickable(true);

        final ImageView ic = Ic.view(getContext(), iconName, color, 17);
        cell.addView(ic, new LinearLayout.LayoutParams(dp(17), dp(17)));

        final TextView tx = new TextView(getContext());
        tx.setText(label);
        tx.setTextColor(color);
        tx.setTextSize(12);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(5);
        cell.addView(tx, p);

        if (isLike) {
            cell.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (d.bvid.isEmpty()) return;
                    if (BiliApi.loggedInUid() == null) {
                        Toast.makeText(getContext(), "请先登录", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    d.liked = !d.liked;
                    d.like += d.liked ? 1 : -1;
                    if (d.like < 0) d.like = 0;
                    tx.setText(d.like > 0 ? VideoItem.views(d.like) : "点赞");
                    int c = d.liked ? MainActivity.C_ACC : MainActivity.C_SUB;
                    tx.setTextColor(c);
                    Ic.set(ic, d.liked ? "like_on" : "like", c);
                    final boolean on = d.liked;
                    new Thread(new Runnable() { @Override public void run() {
                        try { BiliApi.setLike(d.bvid, on); } catch (Throwable ignore) { }
                    } }).start();
                }
            });
        }
        return cell;
    }

    void showMore(final Dyn d) {
        final String[] its = {"打开 UP 主空间", "复制链接"};
        try {
            new android.app.AlertDialog.Builder(getContext())
                    .setItems(its, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dlg, int w) {
                            if (w == 0) {
                                if (listener != null) listener.onOpenUp(d.mid, d.name);
                            } else {
                                String link = "https://www.bilibili.com/video/" + d.bvid;
                                ClipboardManager cm = (ClipboardManager) getContext()
                                        .getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("bili", link));
                                    Toast.makeText(getContext(), "已复制", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    }).show();
        } catch (Throwable e) {
            note("more", e);
        }
    }

    // ------------------------------------------------------------ 解析

    static Dyn parse(JSONObject o) {
        try {
            if (o == null) return null;
            Dyn d = new Dyn();
            JSONObject modules = o.optJSONObject("modules");
            if (modules == null) return null;
            JSONObject author = modules.optJSONObject("module_author");
            if (author != null) {
                d.name = author.optString("name", "");
                d.face = author.optString("face", "");
                d.time = author.optString("pub_time", "");
                d.action = author.optString("pub_action", "");
                d.mid = author.optString("mid", "");
            }
            JSONObject md = modules.optJSONObject("module_dynamic");
            if (md != null) {
                JSONObject desc = md.optJSONObject("desc");
                if (desc != null) d.text = desc.optString("text", "");
                JSONObject major = md.optJSONObject("major");
                if (major != null) {
                    JSONObject arc = major.optJSONObject("archive");
                    if (arc != null) {
                        d.bvid = arc.optString("bvid", "");
                        d.title = arc.optString("title", "");
                        d.cover = arc.optString("cover", "");
                        d.dur = arc.optString("duration_text", "");
                        JSONObject st = arc.optJSONObject("stat");
                        if (st != null) {
                            d.play = st.optLong("play", 0);
                            d.danmaku = st.optLong("danmaku", 0);
                        }
                    }
                    JSONObject draw = major.optJSONObject("draw");
                    if (draw != null) {
                        JSONArray its = draw.optJSONArray("items");
                        if (its != null && its.length() > 0 && its.optJSONObject(0) != null) {
                            d.image = its.optJSONObject(0).optString("src", "");
                        }
                    }
                }
            }
            JSONObject stat = modules.optJSONObject("module_stat");
            if (stat != null) {
                JSONObject c = stat.optJSONObject("comment");
                JSONObject f = stat.optJSONObject("forward");
                JSONObject l = stat.optJSONObject("like");
                d.comment = c != null ? c.optLong("count", 0) : 0;
                d.forward = f != null ? f.optLong("count", 0) : 0;
                d.like = l != null ? l.optLong("count", 0) : 0;
            }
            if (d.text.isEmpty() && d.bvid.isEmpty() && d.image.isEmpty()) return null;
            return d;
        } catch (Throwable e) {
            return null;
        }
    }
}
