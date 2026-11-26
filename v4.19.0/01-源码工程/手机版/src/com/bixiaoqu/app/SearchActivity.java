package com.bixiaoqu.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 搜索页：关键词 / BV号 / av号 / b23短链（封面列表，与主页统一） */
public class SearchActivity extends Activity {

    EditText kw;
    TextView status;
    ListView list;
    LinearLayout histBox;            // 最近搜索区域
    LinearLayout recBox;             // 搜索推荐区域
    /** 【v4.16.1】宽屏时右侧的「bilibili 热搜」侧栏（占屏宽 1/3） */
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<VideoItem> rows = new ArrayList<>();

    VideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.applyTheme(this);
        buildUi();
        String pre = getIntent() != null ? getIntent().getStringExtra("keyword") : null;
        if (pre != null && !pre.isEmpty()) {
            kw.setText(pre);
            doSearch();
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    /**
     * 【v4.16.1】旋屏 / 分屏改变窗口尺寸后重建页面：
     * 宽屏要显示右侧热搜栏，窄屏不显示，布局不同必须重建；
     * 重建时保留当前关键词与搜索结果（rows 是成员变量，不会丢）。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        try {
            String cur = kw != null ? kw.getText().toString() : "";
            buildUi();
            if (!cur.isEmpty() && kw != null) kw.setText(cur);
        } catch (Throwable e) {
            CrashHandler.note("search/orient", android.util.Log.getStackTraceString(e));
        }
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        kw = new EditText(this);
        kw.setHint("搜索关键词 / BV号 / av号");
        kw.setTextColor(MainActivity.C_FG);
        kw.setHintTextColor(Color.parseColor("#565b64"));
        // 【v4.14.5】液态玻璃搜索框：半透明渐变 + 细高光描边
        kw.setBackground(GlassUtil.card(MainActivity.DARK, 12, this));
        kw.setPadding(dp(12), dp(9), dp(12), dp(9));
        kw.setTextSize(13);
        kw.setSingleLine(true);
        // 【v4.14.6】与搜索按钮同高，统一视觉
        row.addView(kw, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button go = new Button(this);
        go.setText("搜索");
        go.setTextColor(Color.WHITE);
        go.setTextSize(13);
        go.setAllCaps(false);
        // 【v4.14.6】粉色方角按钮改为圆角渐变，与玻璃输入框统一 UI 风格
        android.graphics.drawable.GradientDrawable goBg =
                new android.graphics.drawable.GradientDrawable();
        goBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        goBg.setCornerRadius(dp(12));
        goBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        goBg.setColors(new int[]{Color.parseColor("#FF8FA6"), MainActivity.C_ACC});
        go.setBackground(goBg);
        go.setMinHeight(0);
        go.setMinimumHeight(0);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(72), dp(42));
        gp.leftMargin = dp(6);
        row.addView(go, gp);
        go.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                // 【v4.14.7】搜索按钮 Q 弹反馈
                Bounce.pop(v, 1.14f);
                doSearch();
            }
        });

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 【v4.15.0】ListView 作为页面主体，占满搜索框以下的全部空间。
        // 状态提示 / 最近搜索 / 大家都在搜 统一做成 ListView 的 HeaderView，
        // 随列表一起滚动，标签再多也不会被固定区域挤到屏幕底部裁掉。
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(9));
        list.setPadding(0, 0, 0, dp(10));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        // 【v4.14.8】滚动流畅：减少滚动时的过度绘制与缓存抖动
        list.setScrollingCacheEnabled(false);
        list.setAnimationCacheEnabled(false);
        list.setSmoothScrollbarEnabled(true);

        // 【v4.15.0】头部容器：状态提示 + 最近搜索 + 大家都在搜。
        // 作为 ListView 的 addHeaderView，随列表滚动，内容再多也能完整显示、不被裁切。
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        // 【v4.15.1 关键修复】HeaderView 底部兜底留白：ListView 测量 header 时会漏算
        // 嵌套子 View 的 bottomMargin，加 padding 保证最后一行标签完整不被裁。
        header.setPadding(0, 0, 0, dp(14));

        status = new TextView(this);
        status.setText("输入后点搜索；点击结果直接播放");
        status.setTextColor(MainActivity.C_SUB);
        status.setTextSize(12);
        status.setPadding(dp(2), dp(9), 0, dp(3));
        header.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 最近搜索
        histBox = new LinearLayout(this);
        histBox.setOrientation(LinearLayout.VERTICAL);
        header.addView(histBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 【v4.14.6】搜索推荐：内置热门词，点击即搜
        recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        header.addView(recBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 必须在 setAdapter 之前添加 HeaderView
        list.addHeaderView(header, null, false);

        adapter = new VideoAdapter(this, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              android.view.View v, int i, long id) {
                // HeaderView 占了一个 position，需减去修正为数据索引
                i -= list.getHeaderViewsCount();
                if (i < 0 || i >= rows.size()) return;
                VideoItem it = rows.get(i);
                if (it.isPlaceholder()) return;
                android.content.Intent intent = new android.content.Intent(
                        SearchActivity.this, PlayActivity.class);
                intent.putExtra("bvid", it.bvid);
                intent.putExtra("cid", it.cid);
                intent.putExtra("aspect", it.aspect);
                intent.putExtra("ptitle", it.title);
                intent.putExtra("pdur", it.durSec);
                startActivity(intent);
            }
        });
        // 【v4.16.1】宽屏（横屏 / 平板）时右侧加一栏热搜，宽度约为结果的 1/2（≈屏宽 1/3）
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = dp(2);
        body.addView(list, lp);

        // 【手机版】单栏：结果列表直接铺满，不做宽屏热搜右栏

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(body, bp);

        setContentView(root);
        refreshHistory();
        buildRecommend();
    }



    /** 【v4.14.6】搜索推荐：内置热门词胶囊，点击直接搜索 */
    void buildRecommend() {
        if (recBox == null) return;
        recBox.removeAllViews();
        TextView head = new TextView(this);
        head.setText("大家都在搜");
        head.setTextColor(MainActivity.C_SUB);
        head.setTextSize(12);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // 【v4.15.1】标签区整体下移一点点，底部留白收紧，配合缩小后的胶囊
        head.setPadding(dp(2), dp(20), dp(2), dp(6));
        recBox.addView(head);

        final String[] hot = {"周杰伦", "原神", "罗翔", "三体", "AI", "篮球", "美食", "搞笑"};
        // 简单的自动换行流式布局（API22 兼容，手动测宽换行）
        int maxW = getResources().getDisplayMetrics().widthPixels - dp(24);
        LinearLayout line = newLine();
        int lineW = 0;
        for (final String word : hot) {
            TextView chip = hotChip(word);
            chip.measure(0, 0);
            int cw = chip.getMeasuredWidth() + dp(14);
            if (lineW + cw > maxW && lineW > 0) {
                recBox.addView(line);
                line = newLine();
                lineW = 0;
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 【v4.15.1】横向间距保留；纵向间距已移到 newLine() 的 bottomPadding，
            // 不再用 bottomMargin（ListView 测量 header 时会漏算 margin 导致裁切）。
            cp.rightMargin = dp(14);
            line.addView(chip, cp);
            lineW += cw;
        }
        if (line.getChildCount() > 0) recBox.addView(line);
    }

    LinearLayout newLine() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        // 【v4.15.1 关键修复】行间距用 padding 而非子 View 的 bottomMargin：
        // padding 一定计入 LinearLayout 自身高度，ListView 测量 header 时不会漏算，
        // 最后一行标签底部才不会被裁。
        l.setPadding(0, 0, 0, dp(10));
        return l;
    }

    TextView hotChip(final String word) {
        TextView chip = new TextView(this);
        chip.setText(word);
        chip.setTextColor(MainActivity.C_FG);
        chip.setTextSize(12f);
        chip.setSingleLine(true);
        chip.setBackground(GlassUtil.chip(MainActivity.DARK, 12, this));
        chip.setPadding(dp(11), dp(5), dp(11), dp(5));
        chip.setClickable(true);
        chip.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) Bounce.pressIn(v);
                else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) Bounce.release(v);
                return false;
            }
        });
        chip.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                kw.setText(word);
                doSearch();
            }
        });
        return chip;
    }

    /** 刷新「最近搜索」：标题 + 可横向滑动的词条胶囊 */
    void refreshHistory() {
        if (histBox == null) return;
        histBox.removeAllViews();
        final ArrayList<String> hs = SearchHistory.list(this);
        if (hs.isEmpty()) { histBox.setVisibility(android.view.View.GONE); return; }
        histBox.setVisibility(android.view.View.VISIBLE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 【v4.14.9】标题留白加大，与推荐区层次一致
        head.setPadding(dp(2), dp(6), dp(2), dp(9));
        TextView t = new TextView(this);
        t.setText("最近搜索");
        t.setTextColor(MainActivity.C_SUB);
        t.setTextSize(12);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clr = new TextView(this);
        clr.setText("清空");
        clr.setTextColor(MainActivity.C_ACC);
        clr.setTextSize(12);
        clr.setPadding(dp(10), dp(3), dp(2), dp(3));
        clr.setClickable(true);
        clr.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                SearchHistory.clear(SearchActivity.this);
                refreshHistory();
            }
        });
        head.addView(clr);
        histBox.addView(head);

        android.widget.HorizontalScrollView hsView = new android.widget.HorizontalScrollView(this);
        hsView.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String kq : hs) {
            TextView chip = new TextView(this);
            chip.setText(kq);
            chip.setTextColor(MainActivity.C_FG);
            chip.setTextSize(12f);
            chip.setSingleLine(true);
            // 【v4.15.1】历史词条尺寸与推荐区统一缩小
            chip.setBackground(GlassUtil.chip(MainActivity.DARK, 12, this));
            chip.setPadding(dp(11), dp(5), dp(11), dp(5));
            chip.setClickable(true);
            chip.setOnTouchListener(new android.view.View.OnTouchListener() {
                @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                    if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) Bounce.pressIn(v);
                    else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                            || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) Bounce.release(v);
                    return false;
                }
            });
            chip.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    kw.setText(kq);
                    kw.setSelection(kq.length());
                    doSearch();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 【v4.14.9】最近搜索胶囊间距加大
            lp.rightMargin = dp(13);
            row.addView(chip, lp);
        }
        hsView.addView(row, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        histBox.addView(hsView);
    }

    void doSearch() {
        final String k = kw.getText().toString().trim();
        if (k.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show();
            return;
        }
        // 收起软键盘，露出结果
        try {
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(kw.getWindowToken(), 0);
        } catch (Throwable ignore) { }
        status.setText("搜索中…");
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                String bvid = BiliApi.extractBvid(k);
                if (bvid == null) {
                    String av = BiliApi.extractAvid(k);
                    if (av != null) bvid = "av" + av;
                }
                if (bvid != null) {
                    JSONObject v = BiliApi.view(
                            bvid.startsWith("av") ? null : bvid,
                            bvid.startsWith("av") ? bvid.substring(2) : null);
                    String up = v.optJSONObject("owner") != null
                            ? v.getJSONObject("owner").optString("name", "") : "";
                    int dur = v.optInt("duration", 0);
                    long play = v.optJSONObject("stat") != null
                            ? v.getJSONObject("stat").optLong("view", 0) : 0;
                    StringBuilder sub = new StringBuilder();
                    if (!up.isEmpty()) sub.append(up);
                    if (play > 0) {
                        if (sub.length() > 0) sub.append("  ·  ");
                        sub.append(VideoItem.views(play)).append("播放");
                    }
                    if (dur > 0) {
                        if (sub.length() > 0) sub.append("  ·  ");
                        sub.append(VideoItem.dur(dur));
                    }
                    got.add(new VideoItem(bvid,
                            BiliApi.cleanTitle(v.optString("title", bvid)),
                            sub.toString(), v.optString("pic", "")));
                } else {
                    JSONArray res = BiliApi.searchVideos(k, 1);
                    if (res != null) {
                        for (int i = 0; i < res.length(); i++) {
                            JSONObject o = res.getJSONObject(i);
                            if (!"video".equals(o.optString("type", "video"))) continue;
                            String bv = o.optString("bvid", "");
                            if (bv.isEmpty()) continue;
                            String up = o.optString("author", "");
                            String dur = o.optString("duration", "");
                            String play = o.optString("play", "");
                            StringBuilder sub = new StringBuilder();
                            if (!up.isEmpty()) sub.append(up);
                            if (!play.isEmpty()) {
                                if (sub.length() > 0) sub.append("  ·  ");
                                sub.append(play).append("播放");
                            }
                            if (!dur.isEmpty()) {
                                if (sub.length() > 0) sub.append("  ·  ");
                                sub.append(dur);
                            }
                            got.add(new VideoItem(bv,
                                    BiliApi.cleanTitle(o.optString("title", "")),
                                    sub.toString(), o.optString("pic", "")));
                        }
                    }
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                rows.clear();
                rows.addAll(got);
                if (rows.isEmpty()) {
                    rows.add(new VideoItem("", "没有结果（" + (err[0] == null ? "空" : err[0]) + "）", "", null));
                }
                adapter.notifyDataSetChanged();
                if (got.isEmpty()) {
                    status.setText("无结果");
                } else {
                    status.setText("共 " + got.size() + " 条，点击播放");
                    SearchHistory.add(SearchActivity.this, k);   // 记入最近搜索
                    refreshHistory();
                }
            } });
        } }).start();
    }
}
