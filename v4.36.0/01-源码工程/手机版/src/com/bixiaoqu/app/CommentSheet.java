package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 【v4.9.6】评论抽屉。
 *
 * 【为什么不再用独立 Activity】
 * 旧版点评论会 startActivity(CommentActivity)，整页被评论糊满，视频直接看不见，
 * 想对照画面看评论得退出去再进来。这里改成**从底部升起的抽屉**：
 *   · 高度约屏幕 62%，上方至少还有三分之一画面可见；
 *   · 视频继续播放（不暂停、不销毁）；
 *   · 顶部圆角 + 拖拽把手，下滑 / 点「×」收起；
 *   · 列表、发表、点赞数全部保留。
 *
 * 抽屉只创建一次，之后复用（open() 重新绑定 aid 并刷新）。
 */
public class CommentSheet {


    final Activity act;
    final FrameLayout host;

    LinearLayout panel;
    ListView list;
    TextView titleView, tipView, sortView;
    View dragBar;
    EditText input;
    Button send;

    final ArrayList<JSONObject> rows = new ArrayList<>();
    Adapter adapter;
    final Handler ui = new Handler(Looper.getMainLooper());

    long aid;
    String bvid = "";
    int total = 0;

    // ---- 【v4.10.6】楼中楼 ----
    /** 主评论 rpid → 已拉到的二级回复 */
    final java.util.HashMap<Long, ArrayList<JSONObject>> childRows = new java.util.HashMap<>();
    /** 已展开的主评论 rpid */
    final java.util.HashSet<Long> expanded = new java.util.HashSet<>();
    /** 正在请求二级回复的主评论 rpid */
    final java.util.HashSet<Long> childLoading = new java.util.HashSet<>();
    /** 当前回复目标：root=主评论，parent=被回复的那条（0 表示直接回主评论） */
    long replyRoot = 0, replyParent = 0;
    String replyName = "";
    TextView cancelReply;
    boolean showing = false;
    boolean loading = false;
    long lastLoadedAid = -1;

    // ---- 【v4.36.0】评论点赞：本会话内的乐观状态，不落盘 ----
    /** 已点赞的评论 rpid（主评论与二级回复共用） */
    final java.util.HashSet<Long> likedReplies = new java.util.HashSet<>();
    /** 点赞数增量：rpid → +1 / -1 */
    final java.util.HashMap<Long, Integer> likeDelta = new java.util.HashMap<>();
    /** 正在请求的 rpid，防连点重复提交 */
    final java.util.HashSet<Long> likePending = new java.util.HashSet<>();
    /** 【v4.36.0】楼中楼内容版本号：只有数据真的变了才重建那块 View */
    int threadStamp = 1;

    public CommentSheet(Activity act, FrameLayout host) {
        this.act = act;
        this.host = host;
        build();
    }

    int dp(float v) {
        return (int) (act.getResources().getDisplayMetrics().density * v);
    }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * act.getResources().getDisplayMetrics().density);
        return g;
    }

    int screenH() {
        return act.getResources().getDisplayMetrics().heightPixels;
    }

    /** 抽屉目标高度：62% 屏高；横屏时按屏高封顶，避免超过屏幕 */
    int sheetH() {
        int sh = screenH();
        int h = (int) (sh * 0.62f);
        int floor = Math.min(dp(380), (int) (sh * 0.9f));
        h = Math.max(h, floor);
        return Math.min(h, (int) (sh * 0.78f));
    }

    // ------------------------------------------------------------ 构建

    void build() {
        panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MainActivity.C_BG);
        float r = dp(20);
        // 只有上方两角圆角（下方贴着屏幕底边）
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setElevation(dp(16));
        panel.setVisibility(View.GONE);
        // 关键：面板要吃掉触摸。LinearLayout 默认不点击，空白处的事件会穿透到
        // 下层的视频 overlay，变成单击暂停 / 双击 / 上下滑翻页，非常容易误操作。
        panel.setClickable(true);
        panel.setFocusable(true);

        // ---- 顶部把手 ----
        FrameLayout dragArea = new FrameLayout(act);
        dragArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        dragBar = new View(act);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF3A3F48);
        barBg.setCornerRadius(dp(3));
        dragBar.setBackground(barBg);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(38), dp(5), Gravity.CENTER);
        dragArea.addView(dragBar, bp);
        panel.addView(dragArea);

        // ---- 标题行 ----
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(2), dp(10), dp(8));

        titleView = new TextView(act);
        titleView.setText("评论");
        titleView.setTextColor(MainActivity.C_FG);
        titleView.setTextSize(15.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(titleView);

        sortView = new TextView(act);
        sortView.setText("按热度");
        sortView.setTextColor(MainActivity.C_SUB);
        sortView.setTextSize(10.5f);
        LinearLayout.LayoutParams srt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srt.leftMargin = dp(8);
        head.addView(sortView, srt);

        View spacer = new View(act);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView close = new TextView(act);
        close.setText("×");
        close.setTextColor(MainActivity.C_SUB);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        });
        head.addView(close);
        panel.addView(head);

        // ---- 列表 ----
        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(14), 0, dp(14), dp(8));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter(act);
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tipView = new TextView(act);
        tipView.setText("加载中…");
        tipView.setTextColor(MainActivity.C_SUB);
        tipView.setTextSize(12);
        tipView.setGravity(Gravity.CENTER);
        tipView.setPadding(0, dp(30), 0, dp(30));
        panel.addView(tipView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 底部输入条 ----
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(MainActivity.C_CARD);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(10));

        input = new EditText(act);
        input.setHint("发个友善的评论…");
        input.setHintTextColor(0xFF6F7581);
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setBackground(round(0xFF1E2229, 18));
        input.setPadding(dp(14), dp(7), dp(14), dp(7));
        bottom.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 回复态才出现的「取消」：点一下退出楼中楼回复
        cancelReply = new TextView(act);
        cancelReply.setText("×");
        cancelReply.setTextColor(0xFF8A909C);
        cancelReply.setTextSize(17);
        cancelReply.setGravity(Gravity.CENTER);
        cancelReply.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(26), dp(28));
        cp.leftMargin = dp(4);
        bottom.addView(cancelReply, cp);
        cancelReply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setReplyTarget(0, 0, ""); }
        });

        send = new Button(act);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(12);
        send.setAllCaps(false);
        send.setBackground(round(MainActivity.C_ACC, 16));
        send.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        sp.leftMargin = dp(8);
        bottom.addView(send, sp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSend(); }
        });
        panel.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 挂到 host 上 ----
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sheetH(), Gravity.BOTTOM);
        host.addView(panel, lp);
        panel.setTranslationY(sheetH());

        // 把手区：下滑关闭（跟手位移，松手过半或速度够就收）
        dragArea.setOnTouchListener(new View.OnTouchListener() {
            float downY = 0, lastY = 0;
            long lastT = 0, downT = 0;
            float vel = 0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = lastY = e.getY();
                        downT = lastT = System.currentTimeMillis();
                        vel = 0;
                        panel.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getY() - lastY;
                        long now = System.currentTimeMillis();
                        if (now > lastT) vel = vel * 0.6f + (dy / (now - lastT) * 1000f) * 0.4f;
                        lastY = e.getY();
                        lastT = now;
                        float ty = (e.getY() - downY);
                        if (ty < 0) ty = ty * 0.25f;          // 上滑阻尼
                        panel.setTranslationY(Math.max(-dp(20), ty));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float ty = e.getY() - downY;
                        boolean fast = vel > dp(700);
                        if (ty > sheetH() * 0.28f || fast) {
                            hide();
                        } else {
                            panel.animate().translationY(0)
                                    .setDuration(180)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .start();
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------ 显示 / 收起

    public void open(long aid, String bvid, int total, String title) {
        this.aid = aid;
        this.bvid = bvid == null ? "" : bvid;
        this.total = total;
        showing = true;
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationY(0)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        refreshTitle();
        // 同一个视频再次打开不重复拉取（避免刚打开就整列表闪一下）；
        // 换视频后 aid 变了才重新加载。
        if (this.aid != lastLoadedAid) {
            lastLoadedAid = this.aid;
            rows.clear();
            childRows.clear();
            expanded.clear();
            childLoading.clear();
            likedReplies.clear();
            likeDelta.clear();
            likePending.clear();
            setReplyTarget(0, 0, "");
            { threadStamp++; adapter.notifyDataSetChanged(); }
            load();
        }
    }

    public void hide() {
        showing = false;
        hideKeyboard();
        panel.animate().translationY(sheetH())
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (!showing) panel.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    public boolean isShowing() { return showing; }

    void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Throwable ignore) { }
    }

    void refreshTitle() {
        if (titleView != null) {
            titleView.setText(total > 0 ? "评论 " + total : "评论");
        }
    }

    // ------------------------------------------------------------ 数据

    void load() {
        if (loading) return;
        loading = true;
        if (rows.isEmpty()) {
            tipView.setText("加载中…");
            tipView.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() { @Override public void run() {
            final long useAid;
            if (aid <= 0 && !bvid.isEmpty()) {
                long a = 0;
                try {
                    JSONObject v = BiliApi.archiveMeta(bvid);
                    a = v.optLong("aid", 0);
                } catch (Throwable ignore) { }
                aid = a;
            }
            useAid = aid;
            JSONArray arr = null;
            String err = null;
            if (useAid > 0) {
                try { arr = BiliApi.replies(useAid, 1); }
                catch (Throwable e) { err = e.getMessage(); }
            } else {
                err = "缺少视频 id";
            }
            final JSONArray fArr = arr;
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                loading = false;
                rows.clear();
                if (fArr != null) {
                    for (int i = 0; i < fArr.length(); i++) {
                        JSONObject o = fArr.optJSONObject(i);
                        if (o != null) rows.add(o);
                    }
                }
                { threadStamp++; adapter.notifyDataSetChanged(); }
                if (fErr != null) {
                    tipView.setText("评论加载失败：" + fErr);
                    tipView.setVisibility(View.VISIBLE);
                } else if (rows.isEmpty()) {
                    tipView.setText("还没有评论，来抢个沙发");
                    tipView.setVisibility(View.VISIBLE);
                } else {
                    tipView.setVisibility(View.GONE);
                }
                // 【v4.10.6】刷新后把仍处于展开状态的楼层补回来
                try {
                    for (Long rp : new ArrayList<>(expanded)) loadChild(rp);
                } catch (Throwable ignore) { }
            } });
        } }).start();
    }

    void doSend() {
        final String txt = input.getText() != null ? input.getText().toString().trim() : "";
        if (txt.isEmpty()) {
            Toast.makeText(act, "先写点内容吧", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BiliApi.loggedInUid() == null) {
            Toast.makeText(act, "请先登录 B 站账号", Toast.LENGTH_SHORT).show();
            return;
        }
        final long useRoot = replyRoot, useParent = replyParent;
        send.setEnabled(false);
        new Thread(new Runnable() { @Override public void run() {
            final String[] err = {null};
            try { BiliApi.sendReply(aid, txt, useRoot, useParent); }
            catch (Throwable e) { err[0] = e.getMessage(); }
            ui.post(new Runnable() { @Override public void run() {
                send.setEnabled(true);
                if (err[0] != null) {
                    Toast.makeText(act, err[0], Toast.LENGTH_LONG).show();
                    return;
                }
                input.setText("");
                hideKeyboard();
                setReplyTarget(0, 0, "");
                Toast.makeText(act, useRoot > 0 ? "回复已发布" : "评论已发布",
                        Toast.LENGTH_SHORT).show();
                if (useRoot > 0) {
                    // 刚回复的那一楼：展开并丢掉旧缓存，等 load() 回来重拉
                    expanded.add(useRoot);
                    childRows.remove(useRoot);
                }
                load();
            } });
        } }).start();
    }

    // ------------------------------------------------------------ 楼中楼

    /** 点「回复」：把输入框切到回复模式；root=0 表示回到普通评论 */
    void setReplyTarget(long root, long parent, String name) {
        replyRoot = root;
        replyParent = parent > 0 ? parent : root;
        replyName = name == null ? "" : name;
        if (input != null) {
            if (replyRoot > 0) {
                input.setHint(replyName.isEmpty() ? "回复…" : "回复 @" + replyName + "：");
            } else {
                input.setHint("发个友善的评论…");
            }
        }
        if (cancelReply != null) {
            cancelReply.setVisibility(replyRoot > 0 ? View.VISIBLE : View.GONE);
        }
        if (replyRoot > 0 && input != null) {
            input.requestFocus();
            try {
                InputMethodManager imm = (InputMethodManager)
                        act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignore) { }
        }
    }

    /** 展开 / 收起某条主评论的二级回复 */
    void toggleThread(JSONObject o) {
        long rpid = o.optLong("rpid", 0);
        if (rpid <= 0) return;
        if (expanded.contains(rpid)) {
            expanded.remove(rpid);
            { threadStamp++; adapter.notifyDataSetChanged(); }
            return;
        }
        expanded.add(rpid);
        ArrayList<JSONObject> list = childRows.get(rpid);
        if (list == null) {
            list = new ArrayList<>();
            JSONArray prev = o.optJSONArray("replies");   // 主接口自带的前几条预览
            if (prev != null) {
                for (int i = 0; i < prev.length(); i++) {
                    JSONObject c = prev.optJSONObject(i);
                    if (c != null) list.add(c);
                }
            }
            childRows.put(rpid, list);
        }
        { threadStamp++; adapter.notifyDataSetChanged(); }
        if (o.optInt("rcount", 0) > list.size()) loadChild(rpid);
    }

    /** 拉某条主评论下的全部回复（最多 20 条一页） */
    void loadChild(final long rpid) {
        if (rpid <= 0 || childLoading.contains(rpid)) return;
        childLoading.add(rpid);
        { threadStamp++; adapter.notifyDataSetChanged(); }
        new Thread(new Runnable() { @Override public void run() {
            JSONArray arr = null;
            try { arr = BiliApi.replyReplies(aid, rpid, 1); } catch (Throwable ignore) { }
            final JSONArray f = arr;
            ui.post(new Runnable() { @Override public void run() {
                childLoading.remove(rpid);
                if (f != null) {
                    ArrayList<JSONObject> list = new ArrayList<>();
                    for (int i = 0; i < f.length(); i++) {
                        JSONObject c = f.optJSONObject(i);
                        if (c != null) list.add(c);
                    }
                    childRows.put(rpid, list);
                }
                { threadStamp++; adapter.notifyDataSetChanged(); }
            } });
        } }).start();
    }

    /** 一条主评论下面的楼中楼区块 */
    View buildThread(final JSONObject o) {
        final long rpid = o.optLong("rpid", 0);
        final int rcount = o.optInt("rcount", 0);
        JSONArray prev = o.optJSONArray("replies");
        int prevN = prev == null ? 0 : prev.length();
        boolean open = expanded.contains(rpid);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        if (!open) {
            if (rcount <= 0 && prevN <= 0) return null;   // 没有回复就不占位
            TextView more = new TextView(act);
            more.setText("共 " + Math.max(rcount, prevN) + " 条回复   展开 ∨");
            more.setTextColor(0xFF7E96BA);
            more.setTextSize(11.5f);
            more.setPadding(dp(44), dp(6), 0, dp(2));
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleThread(o); }
            });
            box.addView(more);
            return box;
        }

        ArrayList<JSONObject> list = childRows.get(rpid);
        if (list == null) list = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            box.addView(childRow(list.get(i), rpid));
        }

        if (childLoading.contains(rpid)) {
            TextView t = new TextView(act);
            t.setText("正在加载回复…");
            t.setTextColor(0xFF7A8290);
            t.setTextSize(11);
            t.setPadding(dp(44), dp(6), 0, dp(2));
            box.addView(t);
        } else if (rcount > list.size()) {
            TextView all = new TextView(act);
            all.setText("查看全部 " + rcount + " 条回复");
            all.setTextColor(0xFF7E96BA);
            all.setTextSize(11.5f);
            all.setPadding(dp(44), dp(6), 0, dp(2));
            all.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { loadChild(rpid); }
            });
            box.addView(all);
        }

        TextView fold = new TextView(act);
        fold.setText("收起 ∧");
        fold.setTextColor(0xFF7A8290);
        fold.setTextSize(11);
        fold.setPadding(dp(44), dp(6), 0, dp(2));
        fold.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded.remove(rpid);
                { threadStamp++; adapter.notifyDataSetChanged(); }
            }
        });
        box.addView(fold);
        return box;
    }

    /** 一条二级回复：缩进 + 小头像 + 「昵称：内容」 */
    View childRow(JSONObject c, final long rootRpid) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(44), dp(7), 0, dp(2));

        JSONObject member = c.optJSONObject("member");
        String face = member == null ? "" : member.optString("avatar", "");
        ImageView av = new ImageView(act);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setShape(GradientDrawable.OVAL);
        ph.setColor(0xFF2A2E36);
        av.setBackground(ph);
        av.setClipToOutline(true);
        if (!face.isEmpty()) ImgLoader.load(av, face, dp(22), dp(22));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(22), dp(22));
        ap.rightMargin = dp(8);
        row.addView(av, ap);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);

        final String uname = member == null ? "用户" : member.optString("uname", "用户");
        JSONObject content = c.optJSONObject("content");
        String msg = content == null ? "" : content.optString("message", "");

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int st = sb.length();
        sb.append(uname);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF7FA8D8),
                st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("：").append(msg);

        TextView line = new TextView(act);
        line.setText(sb);
        line.setTextSize(12.5f);
        line.setTextColor(MainActivity.C_FG);
        line.setLineSpacing(dp(2), 1f);
        col.addView(line);

        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(3);

        TextView meta = new TextView(act);
        meta.setText(timeAgo(c.optLong("ctime", 0)));
        meta.setTextColor(MainActivity.DARK ? 0xFF6F7581 : MainActivity.C_SUB);
        meta.setTextSize(10);
        foot.addView(meta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 【v4.36.0】二级回复也能点赞
        foot.addView(likeBox(c, new ImageView[1], new TextView[1]));

        TextView reply = new TextView(act);
        reply.setText("回复");
        reply.setTextColor(MainActivity.DARK ? 0xFF8A909C : MainActivity.C_SUB);
        reply.setTextSize(10);
        reply.setPadding(dp(8), 0, 0, 0);
        reply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setReplyTarget(rootRpid, c.optLong("rpid", 0), uname);
            }
        });
        foot.addView(reply);

        col.addView(foot, fp);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ------------------------------------------------------------ 列表

    static String timeAgo(long sec) {
        if (sec <= 0) return "";
        long d = System.currentTimeMillis() / 1000L - sec;
        if (d < 60) return "刚刚";
        if (d < 3600) return (d / 60) + "分钟前";
        if (d < 86400) return (d / 3600) + "小时前";
        if (d < 86400 * 30) return (d / 86400) + "天前";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return f.format(new java.util.Date(sec * 1000L));
    }

    static String count(int n) {
        if (n >= 10000) return String.format("%.1f万", n / 10000f);
        return String.valueOf(n);
    }

    // ------------------------------------------------------------ 评论点赞【v4.36.0】

    /** 评论点赞：先乐观更新 UI，网络失败再回滚；不阻塞列表滚动 */
    void toggleLike(final JSONObject c, final ImageView icon, final TextView tx) {
        final long rpid = c.optLong("rpid", 0);
        if (rpid <= 0 || likePending.contains(rpid)) return;
        if (BiliApi.loggedInUid() == null) {
            Toast.makeText(act, "请先登录 B 站账号", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean want = !likedReplies.contains(rpid);
        likePending.add(rpid);
        if (want) likedReplies.add(rpid); else likedReplies.remove(rpid);
        likeDelta.put(rpid, want ? 1 : -1);
        applyLike(c, icon, tx);

        new Thread(new Runnable() { @Override public void run() {
            final String[] err = {null};
            try { BiliApi.replyLike(aid, rpid, want); }
            catch (Throwable e) { err[0] = e.getMessage(); }
            ui.post(new Runnable() { @Override public void run() {
                likePending.remove(rpid);
                if (err[0] != null) {
                    if (want) likedReplies.remove(rpid); else likedReplies.add(rpid);
                    likeDelta.remove(rpid);
                    Toast.makeText(act, err[0], Toast.LENGTH_SHORT).show();
                }
                applyLike(c, icon, tx);
            }});
        }}).start();
    }

    /** 按当前乐观状态刷新一个点赞控件 */
    void applyLike(JSONObject c, ImageView icon, TextView tx) {
        if (icon == null || tx == null) return;
        long rpid = c.optLong("rpid", 0);
        boolean on = likedReplies.contains(rpid);
        int n = c.optInt("like", 0);
        Integer d = likeDelta.get(rpid);
        if (d != null) n += d;
        if (n < 0) n = 0;
        tx.setText(count(n) + " 赞");
        // 选中用主题强调色；未选中在深色用灰白、浅色用更深的主文字次级色，保证可读
        int col = on ? MainActivity.C_ACC
                : (MainActivity.DARK ? 0xFF8A909C : MainActivity.C_SUB);
        tx.setTextColor(col);
        icon.setColorFilter(col);
        // 复用行时状态没变就不重设资源，省掉一次 setImageResource 重绘
        Object tag = icon.getTag();
        if (!(tag instanceof Boolean) || ((Boolean) tag).booleanValue() != on) {
            icon.setImageResource(Ic.id(act, on ? "like_on" : "like"));
            icon.setTag(Boolean.valueOf(on));
        }
    }

    /** 造一个「图标 + 数字」的点赞控件；当前评论存在 tag 里，行复用后仍能点到正确那条 */
    LinearLayout likeBox(final JSONObject c, ImageView[] outIcon, TextView[] outTx) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(10), 0, dp(4), 0);
        final ImageView icon = new ImageView(act);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(13), dp(13)));
        final TextView tx = new TextView(act);
        tx.setTextSize(10);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(3);
        box.addView(icon);
        box.addView(tx, tp);
        outIcon[0] = icon;
        outTx[0] = tx;
        box.setTag(c);
        applyLike(c, icon, tx);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                JSONObject cur = (JSONObject) v.getTag();
                if (cur != null) toggleLike(cur, icon, tx);
            }
        });
        return box;
    }

    class Adapter extends BaseAdapter {
        final Context ctx;
        Adapter(Context c) { ctx = c; }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        /** 【v4.36.0】用 rpid 做稳定 id，ListView 才能正确复用行 */
        @Override public long getItemId(int i) {
            JSONObject o = rows.get(i);
            return o == null ? i : o.optLong("rpid", i);
        }

        /** 【v4.36.0】行控件复用：滚动时不再 new 一整套 View，消除 GC 抖动 */
        class Holder {
            ImageView av, likeIcon;
            TextView name, body, time, likeText, reply;
            LinearLayout likeBox, threadBox;
            JSONObject row;
            int threadStamp;
            long threadRpid;
        }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            final JSONObject o = rows.get(pos);
            Holder h;
            LinearLayout row;
            if (convert != null && convert.getTag() instanceof Holder) {
                row = (LinearLayout) convert;
                h = (Holder) convert.getTag();
            } else {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(10), 0, dp(10));
                h = new Holder();

                h.av = new ImageView(ctx);
                h.av.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable ph = new GradientDrawable();
                ph.setShape(GradientDrawable.OVAL);
                ph.setColor(0xFF2A2E36);
                h.av.setBackground(ph);
                h.av.setClipToOutline(true);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(34), dp(34));
                ap.rightMargin = dp(10);
                row.addView(h.av, ap);

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);

                h.name = new TextView(ctx);
                h.name.setTextColor(MainActivity.DARK ? 0xFFAEB4C0 : MainActivity.C_SUB);
                h.name.setTextSize(12);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(h.name);

                h.body = new TextView(ctx);
                h.body.setTextColor(MainActivity.C_FG);
                h.body.setTextSize(13);
                h.body.setLineSpacing(dp(3), 1f);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.topMargin = dp(4);
                col.addView(h.body, bp);

                LinearLayout foot = new LinearLayout(ctx);
                foot.setOrientation(LinearLayout.HORIZONTAL);
                foot.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fp.topMargin = dp(5);

                h.time = new TextView(ctx);
                h.time.setTextColor(MainActivity.DARK ? 0xFF6F7581 : MainActivity.C_SUB);
                h.time.setTextSize(10);
                foot.addView(h.time, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h.likeBox = likeBox(o, new ImageView[]{h.likeIcon}, new TextView[]{h.likeText});
                foot.addView(h.likeBox);

                h.reply = new TextView(ctx);
                h.reply.setText("回复");
                h.reply.setTextColor(MainActivity.DARK ? 0xFF8A909C : MainActivity.C_SUB);
                h.reply.setTextSize(10);
                foot.addView(h.reply);
                col.addView(foot, fp);

                // 楼中楼容器固定存在，只换内容，不再重建整行
                h.threadBox = new LinearLayout(ctx);
                h.threadBox.setOrientation(LinearLayout.VERTICAL);
                col.addView(h.threadBox);

                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final Holder fh = h;
                h.reply.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        JSONObject cur = fh.row;
                        if (cur == null) return;
                        JSONObject m = cur.optJSONObject("member");
                        setReplyTarget(cur.optLong("rpid", 0), 0,
                                m == null ? "" : m.optString("uname", ""));
                    }
                });
                row.setTag(h);
            }

            h.row = o;
            JSONObject member = o.optJSONObject("member");
            h.name.setText(member == null ? "用户" : member.optString("uname", "用户"));
            JSONObject content = o.optJSONObject("content");
            h.body.setText(content == null ? "" : content.optString("message", ""));
            h.time.setText(timeAgo(o.optLong("ctime", 0)));
            ImgLoader.load(h.av, member == null ? "" : member.optString("avatar", ""),
                    dp(34), dp(34));
            h.likeBox.setTag(o);
            applyLike(o, h.likeIcon, h.likeText);

            // 楼中楼只在数据变化 / 换了评论时重建，滚动复用时直接跳过，省掉整块 View 创建
            long rpid = o.optLong("rpid", 0);
            if (h.threadStamp != threadStamp || h.threadRpid != rpid) {
                h.threadBox.removeAllViews();
                try {
                    View thread = buildThread(o);
                    if (thread != null) h.threadBox.addView(thread);
                } catch (Throwable ignore) { }
                h.threadStamp = threadStamp;
                h.threadRpid = rpid;
            }
            return row;
        }
    }
}
