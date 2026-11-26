package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 评论页：视频评论列表（按热度）+ 底部发表框。
 * 只读展示 + 发表，不做楼中楼，保持轻量。
 */
public class CommentActivity extends Activity {


    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<JSONObject> rows = new ArrayList<>();
    Adapter adapter;

    long aid;
    String bvid = "";
    String title = "";
    int total = 0;

    TextView emptyTip;
    LinearLayout bottomBar;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try {
            aid = getIntent() != null ? getIntent().getLongExtra("aid", 0) : 0;
            bvid = getIntent() != null ? getIntent().getStringExtra("bvid") : "";
            title = getIntent() != null ? getIntent().getStringExtra("title") : "";
            total = getIntent() != null ? getIntent().getIntExtra("total", 0) : 0;
            if (bvid == null) bvid = "";
            if (title == null) title = "";
            setContentView(build());
            load();
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("评论页打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(30, 60, 30, 30);
            setContentView(tv);
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(14), dp(8));

        ImageView back = Ic.view(this, "back", MainActivity.C_FG, 22);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView t = new TextView(this);
        t.setText(total > 0 ? "评论 " + total : "评论");
        t.setTextColor(MainActivity.C_FG);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(4);
        top.addView(t, tp);

        TextView sub = new TextView(this);
        sub.setText("按热度");
        sub.setTextColor(MainActivity.C_SUB);
        sub.setTextSize(11);
        top.addView(sub);
        root.addView(top);

        // 列表
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setPadding(dp(14), 0, dp(14), dp(10));
        lv.setClipToPadding(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter(this);
        lv.setAdapter(adapter);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyTip = new TextView(this);
        emptyTip.setText("加载中…");
        emptyTip.setTextColor(MainActivity.C_SUB);
        emptyTip.setTextSize(12);
        emptyTip.setGravity(Gravity.CENTER);
        emptyTip.setPadding(0, dp(26), 0, dp(26));
        root.addView(emptyTip);

        // 底部输入条
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(MainActivity.C_CARD);
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(8));

        final EditText input = new EditText(this);
        input.setHint("发一条友善的评论");
        input.setHintTextColor(Color.parseColor("#6f7581"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setBackground(round(0xFF1E2229, 18));
        input.setPadding(dp(14), dp(7), dp(14), dp(7));
        bottomBar.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button send = new Button(this);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(12);
        send.setAllCaps(false);
        send.setBackground(round(MainActivity.C_ACC, 16));
        send.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        sp.leftMargin = dp(8);
        bottomBar.addView(send, sp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String txt = input.getText() != null ? input.getText().toString().trim() : "";
                if (txt.isEmpty()) {
                    Toast.makeText(CommentActivity.this, "先写点内容吧", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (BiliApi.loggedInUid() == null) {
                    Toast.makeText(CommentActivity.this, "请先登录 B 站账号", Toast.LENGTH_SHORT).show();
                    return;
                }
                send.setEnabled(false);
                new Thread(new Runnable() { @Override public void run() {
                    final String[] err = {null};
                    try {
                        BiliApi.sendReply(aid, txt);
                    } catch (Throwable e) {
                        err[0] = e.getMessage();
                    }
                    ui.post(new Runnable() { @Override public void run() {
                        send.setEnabled(true);
                        if (err[0] != null) {
                            Toast.makeText(CommentActivity.this, err[0], Toast.LENGTH_LONG).show();
                            return;
                        }
                        input.setText("");
                        try {
                            InputMethodManager imm = (InputMethodManager)
                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                        } catch (Throwable ignore) { }
                        Toast.makeText(CommentActivity.this, "评论已发布", Toast.LENGTH_SHORT).show();
                        load();
                    } });
                } }).start();
            }
        });

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    void load() {
        if (aid <= 0 && !bvid.isEmpty()) {
            // 只有 BV 号时先反查 aid
            new Thread(new Runnable() { @Override public void run() {
                try {
                    JSONObject v = BiliApi.archiveMeta(bvid);
                    aid = v.optLong("aid", 0);
                } catch (Throwable ignore) { }
                fetch();
            } }).start();
            return;
        }
        fetch();
    }

    void fetch() {
        new Thread(new Runnable() { @Override public void run() {
            JSONArray arr = null;
            String err = null;
            try {
                arr = BiliApi.replies(aid, 1);
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final JSONArray fArr = arr;
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                rows.clear();
                if (fArr != null) {
                    for (int i = 0; i < fArr.length(); i++) {
                        JSONObject o = fArr.optJSONObject(i);
                        if (o != null) rows.add(o);
                    }
                }
                adapter.notifyDataSetChanged();
                if (fErr != null) {
                    emptyTip.setText("加载失败：" + fErr);
                    emptyTip.setVisibility(View.VISIBLE);
                } else if (rows.isEmpty()) {
                    emptyTip.setText("还没有评论，来抢个沙发");
                    emptyTip.setVisibility(View.VISIBLE);
                } else {
                    emptyTip.setVisibility(View.GONE);
                }
            } });
        } }).start();
    }

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

    /** 每条评论一行，全部新建视图（绝不在 getView 里 addView 到 AdapterView，避免历史闪退问题） */
    class Adapter extends BaseAdapter {
        final Context ctx;
        Adapter(Context c) { ctx = c; }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            JSONObject o = rows.get(pos);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(12), 0, dp(12));

            JSONObject member = o.optJSONObject("member");
            String face = member == null ? "" : member.optString("avatar", "");
            ImageView av = new ImageView(ctx);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setShape(GradientDrawable.OVAL);
            ph.setColor(0xFF2A2E36);
            av.setBackground(ph);
            av.setClipToOutline(true);
            if (!face.isEmpty()) ImgLoader.load(av, face, dp(32), dp(32));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(32), dp(32));
            ap.rightMargin = dp(10);
            row.addView(av, ap);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(ctx);
            name.setText(member == null ? "用户" : member.optString("uname", "用户"));
            name.setTextColor(Color.parseColor("#AEB4C0"));
            name.setTextSize(12);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);

            TextView body = new TextView(ctx);
            JSONObject content = o.optJSONObject("content");
            body.setText(content == null ? "" : content.optString("message", ""));
            body.setTextColor(MainActivity.C_FG);
            body.setTextSize(13);
            body.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(4);
            col.addView(body, bp);

            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.topMargin = dp(6);

            TextView time = new TextView(ctx);
            time.setText(timeAgo(o.optLong("ctime", 0)));
            time.setTextColor(Color.parseColor("#6F7581"));
            time.setTextSize(10);
            foot.addView(time, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView lk = Ic.view(ctx, "like", Color.parseColor("#8A909C"), 13);
            foot.addView(lk, new LinearLayout.LayoutParams(dp(13), dp(13)));

            TextView lkn = new TextView(ctx);
            lkn.setText(count(o.optInt("like", 0)));
            lkn.setTextColor(Color.parseColor("#6F7581"));
            lkn.setTextSize(10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(4);
            foot.addView(lkn, lp);

            col.addView(foot, fp);
            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }
}
