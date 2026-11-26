package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** UP 主空间：资料头 + 投稿视频列表 */
public class UserActivity extends Activity {


    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<VideoItem> rows = new ArrayList<>();

    String mid = "", uname = "", faceUrl = "";
    ImageView avatar;
    TextView name, sign, stat;
    VideoAdapter adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        mid = getIntent().getStringExtra("mid");
        uname = getIntent().getStringExtra("name");
        faceUrl = getIntent().getStringExtra("face");
        if (mid == null) mid = "";
        if (uname == null) uname = "用户";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(14), dp(8));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView title = new TextView(this);
        title.setText(uname);
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(6);
        top.addView(title, tp);

        TextView search = new TextView(this);
        search.setText("搜索");
        search.setTextColor(MainActivity.C_SUB);
        search.setTextSize(13);
        search.setPadding(dp(10), dp(6), dp(4), dp(6));
        top.addView(search);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent it = new Intent(UserActivity.this, SearchActivity.class);
                it.putExtra("keyword", uname);
                startActivity(it);
            }
        });
        root.addView(top);

        ListView list = new ListView(this);
        list.setBackgroundColor(MainActivity.C_BG);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(9));
        list.setPadding(dp(10), 0, dp(10), dp(12));
        list.setClipToPadding(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);

        list.addHeaderView(buildHeader(), null, false);
        adapter = new VideoAdapter(this, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v,
                                              int i, long id) {
                int idx = i - 1; // header 占第 0 位
                if (idx < 0 || idx >= rows.size()) return;
                VideoItem it = rows.get(idx);
                if (it.isPlaceholder()) return;
                Intent pi = new Intent(UserActivity.this, PlayActivity.class);
                pi.putExtra("bvid", it.bvid);
                startActivity(pi);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (mid.isEmpty()) {
            rows.add(new VideoItem("", "缺少用户 ID", "", null));
            adapter.notifyDataSetChanged();
            return;
        }
        load();
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(2), dp(4), dp(2), dp(8));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(MainActivity.C_CARD, 14));
        card.setPadding(dp(16), dp(16), dp(16), dp(14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundColor(ImgLoader.placeholderColor());
        final float rad = 30 * getResources().getDisplayMetrics().density;
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
            }
        });
        row.addView(avatar, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = dp(14);
        row.addView(info, ip);

        name = new TextView(this);
        name.setText(uname);
        name.setTextColor(MainActivity.C_FG);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        sign = new TextView(this);
        sign.setText("加载中…");
        sign.setTextColor(MainActivity.C_SUB);
        sign.setTextSize(11);
        sign.setMaxLines(2);
        sign.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(5);
        info.addView(sign, sp);

        stat = new TextView(this);
        stat.setTextColor(Color.parseColor("#6f7581"));
        stat.setTextSize(11);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(5);
        info.addView(stat, stp);

        card.addView(row);
        head.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView section = new TextView(this);
        section.setText("TA 的投稿");
        section.setTextColor(MainActivity.C_SUB);
        section.setTextSize(12);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setPadding(dp(6), dp(16), dp(6), dp(8));
        head.addView(section);
        return head;
    }

    void load() {
        if (faceUrl != null && !faceUrl.isEmpty()) {
            String f = ImgLoader.https(faceUrl);
            f = ImgLoader.face(f, 160);
            ImgLoader.load(avatar, f, dp(60), dp(60));
        }
        new Thread(new Runnable() { @Override public void run() {
            JSONObject card = null;
            JSONArray vids = null;
            String err = null;
            try { card = BiliApi.userCard(mid); } catch (Throwable ignore) { }
            try { vids = BiliApi.userVideos(mid, 1); }
            catch (Throwable e) { err = e.getMessage(); }

            final JSONObject fc = card;
            final JSONArray fv = vids;
            final String fe = err;
            ui.post(new Runnable() { @Override public void run() {
                if (isFinishing()) return;
                if (fc != null) {
                    JSONObject c = fc.optJSONObject("card");
                    if (c != null) {
                        String n = c.optString("name", uname);
                        name.setText(n);
                        sign.setText(c.optString("sign", "").isEmpty()
                                ? "这个人很神秘，什么都没写" : c.optString("sign"));
                        String face = c.optString("face", "");
                        if (!face.isEmpty()) {
                            face = ImgLoader.https(face);
                            face = ImgLoader.face(face, 160);
                            ImgLoader.load(avatar, face, dp(60), dp(60));
                        }
                    }
                    long fans = fc.optLong("follower", 0);
                    long att = fc.optLong("following", 0);
                    stat.setText("粉丝 " + VideoItem.views(fans)
                            + "　·　关注 " + VideoItem.views(att));
                }
                rows.clear();
                if (fv != null) {
                    for (int i = 0; i < fv.length(); i++) {
                        JSONObject o = fv.optJSONObject(i);
                        if (o == null) continue;
                        String bv = o.optString("bvid", "");
                        if (bv.isEmpty()) continue;
                        int dur = durSec(o.optString("length", "0"));
                        StringBuilder sub = new StringBuilder();
                        long play = o.optLong("play", 0);
                        if (play > 0) sub.append(VideoItem.views(play)).append("播放");
                        if (dur > 0) {
                            if (sub.length() > 0) sub.append("  ·  ");
                            sub.append(VideoItem.dur(dur));
                        }
                        rows.add(new VideoItem(bv,
                                BiliApi.cleanTitle(o.optString("title", "")),
                                sub.toString(), o.optString("pic", "")));
                    }
                }
                if (rows.isEmpty()) {
                    rows.add(new VideoItem("", fe == null
                            ? "TA 还没有投稿" : "投稿加载失败：" + fe, "", null));
                }
                adapter.notifyDataSetChanged();
            } });
        } }).start();
    }

    static int durSec(String s) {
        try {
            String[] p = s.split(":");
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
        } catch (Throwable ignore) { }
        return 0;
    }

    GradientDrawable roundBg(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }
}
