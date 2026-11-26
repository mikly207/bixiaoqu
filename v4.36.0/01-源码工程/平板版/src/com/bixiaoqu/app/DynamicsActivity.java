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
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 用户动态：文字 / 图片 / 视频投稿，点击视频卡片直接播放 */
public class DynamicsActivity extends Activity {


    static class Dyn {
        String text = "", name = "", face = "", time = "";
        String image = "", bvid = "", videoTitle = "", videoCover = "", videoDur = "";
        String statLine = "";
        boolean placeholder;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<Dyn> rows = new ArrayList<>();
    String mid = "", uname = "", offset = "", tip = "加载中…";
    boolean hasMore = false, loading = false;
    ListView list;
    DynAdapter adapter;
    TextView footer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        mid = getIntent().getStringExtra("mid");
        uname = getIntent().getStringExtra("name");
        if (mid == null) mid = "";

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
        title.setText("动态");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(6);
        top.addView(title, tp);

        if (uname != null && !uname.isEmpty()) {
            TextView who = new TextView(this);
            who.setText(uname);
            who.setTextColor(MainActivity.C_SUB);
            who.setTextSize(12);
            who.setSingleLine(true);
            who.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(who);
        }
        root.addView(top);

        list = new ListView(this);
        list.setBackgroundColor(MainActivity.C_BG);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setPadding(dp(12), dp(2), dp(12), dp(12));
        list.setClipToPadding(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);

        footer = new TextView(this);
        footer.setText("加载更多");
        footer.setTextColor(MainActivity.C_ACC);
        footer.setTextSize(13);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, dp(18));
        footer.setVisibility(View.GONE);
        footer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!loading && hasMore) load(false);
            }
        });
        list.addFooterView(footer, null, false);

        adapter = new DynAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                if (i < 0 || i >= rows.size()) return;
                Dyn d = rows.get(i);
                if (d.placeholder || d.bvid.isEmpty()) return;
                Intent it = new Intent(DynamicsActivity.this, PlayActivity.class);
                it.putExtra("bvid", d.bvid);
                startActivity(it);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (mid.isEmpty()) {
            tip = "缺少用户 ID";
            adapter.notifyDataSetChanged();
            return;
        }
        load(true);
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    void load(final boolean first) {
        if (loading) return;
        loading = true;
        if (first) {
            rows.clear();
            tip = "加载中…";
            adapter.notifyDataSetChanged();
        }
        footer.setText("加载中…");
        if (hasMore) footer.setVisibility(View.VISIBLE);

        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<Dyn> got = new ArrayList<>();
            final String[] err = {null};
            final String[] next = {""};
            final boolean[] more = {false};
            try {
                JSONObject d = BiliApi.dynamicFeed(mid, first ? "" : offset);
                next[0] = d.optString("offset", "");
                more[0] = d.optBoolean("has_more", false);
                JSONArray items = d.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.optJSONObject(i);
                        Dyn dy = parse(o);
                        if (dy != null) got.add(dy);
                    }
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                loading = false;
                if (isFinishing()) return;
                if (err[0] != null) {
                    tip = "加载失败：" + err[0];
                    if (first) rows.clear();
                    adapter.notifyDataSetChanged();
                    footer.setVisibility(View.GONE);
                    Toast.makeText(DynamicsActivity.this, tip, Toast.LENGTH_LONG).show();
                    return;
                }
                offset = next[0];
                hasMore = more[0];
                if (first) rows.clear();
                rows.addAll(got);
                if (rows.isEmpty()) tip = "TA 还没有发过动态";
                adapter.notifyDataSetChanged();
                footer.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                footer.setText("加载更多");
            } });
        } }).start();
    }

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
            }
            JSONObject dyn = modules.optJSONObject("module_dynamic");
            if (dyn != null) {
                JSONObject desc = dyn.optJSONObject("desc");
                if (desc != null) d.text = desc.optString("text", "");

                JSONObject major = dyn.optJSONObject("major");
                if (major != null) {
                    JSONObject arc = major.optJSONObject("archive");
                    if (arc != null) {
                        d.bvid = arc.optString("bvid", "");
                        d.videoTitle = arc.optString("title", "");
                        d.videoCover = arc.optString("cover", "");
                        d.videoDur = arc.optString("duration_text", "");
                        if (d.text.isEmpty()) d.text = arc.optString("desc", "");
                    }
                    JSONObject draw = major.optJSONObject("draw");
                    if (draw != null) {
                        JSONArray items = draw.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            d.image = items.optJSONObject(0) != null
                                    ? items.optJSONObject(0).optString("src", "") : "";
                        }
                    }
                }
            }
            JSONObject stat = modules.optJSONObject("module_stat");
            if (stat != null) {
                long cm = stat.optJSONObject("comment") != null
                        ? stat.getJSONObject("comment").optLong("count", 0) : 0;
                long lk = stat.optJSONObject("like") != null
                        ? stat.getJSONObject("like").optLong("count", 0) : 0;
                StringBuilder sb = new StringBuilder();
                if (lk > 0) sb.append("赞 ").append(VideoItem.views(lk));
                if (cm > 0) {
                    if (sb.length() > 0) sb.append("   ·   ");
                    sb.append("评论 ").append(VideoItem.views(cm));
                }
                d.statLine = sb.toString();
            }
            if (d.text.isEmpty() && d.image.isEmpty() && d.bvid.isEmpty()) return null;
            return d;
        } catch (Throwable e) {
            return null;
        }
    }

    class DynAdapter extends BaseAdapter {
        final Context ctx;
        DynAdapter(Context c) { ctx = c; }

        @Override public int getCount() { return rows.isEmpty() ? 1 : rows.size(); }
        @Override public Object getItem(int i) { return i < rows.size() ? rows.get(i) : null; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup p) {
            if (rows.isEmpty()) {
                TextView tv = new TextView(ctx);
                tv.setText(tip);
                tv.setTextColor(MainActivity.C_SUB);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(16), dp(60), dp(16), dp(16));
                tv.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return tv;
            }
            Card c = (cv instanceof Card) ? (Card) cv : new Card(ctx);
            c.bind(rows.get(i));
            return c;
        }
    }

    /** 一条动态卡片 */
    static class Card extends LinearLayout {
        ImageView avatar, image, vcover;
        TextView name, time, text, stat, vtitle, vdur;
        LinearLayout videoBox;
        Dyn item;

        Card(Context c) {
            super(c);
            float den = c.getResources().getDisplayMetrics().density;
            setOrientation(VERTICAL);
            setBackground(roundBg(MainActivity.C_CARD, 14, c));
            setPadding((int) (13 * den), (int) (12 * den),
                    (int) (13 * den), (int) (12 * den));
            setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout head = new LinearLayout(c);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            avatar = new ImageView(c);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackgroundColor(ImgLoader.placeholderColor());
            final float rad = 18 * den;
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            head.addView(avatar, new LinearLayout.LayoutParams((int) (36 * den), (int) (36 * den)));

            LinearLayout who = new LinearLayout(c);
            who.setOrientation(VERTICAL);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wp.leftMargin = (int) (10 * den);
            head.addView(who, wp);

            name = new TextView(c);
            name.setTextColor(MainActivity.C_FG);
            name.setTextSize(13);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            who.addView(name);

            time = new TextView(c);
            time.setTextColor(Color.parseColor("#6f7581"));
            time.setTextSize(10);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = (int) (2 * den);
            who.addView(time, tlp);

            addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            text = new TextView(c);
            text.setTextColor(MainActivity.C_FG);
            text.setTextSize(13.5f);
            text.setLineSpacing((int) (3 * den), 1f);
            text.setMaxLines(8);
            text.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            txp.topMargin = (int) (10 * den);
            addView(text, txp);

            image = new FeedAdapter.RatioImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(ImgLoader.placeholderColor());
            image.setClipToOutline(true);
            final float irad = 8 * den;
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), irad);
                }
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.topMargin = (int) (10 * den);
            addView(image, ip);

            videoBox = new LinearLayout(c);
            videoBox.setOrientation(HORIZONTAL);
            videoBox.setBackground(roundBg(Color.parseColor("#252932"), 8, c));
            videoBox.setPadding((int) (7 * den), (int) (7 * den),
                    (int) (9 * den), (int) (7 * den));
            LinearLayout.LayoutParams vbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vbp.topMargin = (int) (10 * den);
            addView(videoBox, vbp);

            vcover = new ImageView(c);
            vcover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            vcover.setBackgroundColor(ImgLoader.placeholderColor());
            videoBox.addView(vcover, new LinearLayout.LayoutParams(
                    (int) (108 * den), (int) (66 * den)));

            LinearLayout vinfo = new LinearLayout(c);
            vinfo.setOrientation(VERTICAL);
            LinearLayout.LayoutParams vip = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            vip.leftMargin = (int) (10 * den);
            videoBox.addView(vinfo, vip);

            vtitle = new TextView(c);
            vtitle.setTextColor(MainActivity.C_FG);
            vtitle.setTextSize(12.5f);
            vtitle.setMaxLines(2);
            vtitle.setEllipsize(TextUtils.TruncateAt.END);
            vinfo.addView(vtitle);

            vdur = new TextView(c);
            vdur.setTextColor(MainActivity.C_SUB);
            vdur.setTextSize(10);
            LinearLayout.LayoutParams vdp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vdp.topMargin = (int) (5 * den);
            vinfo.addView(vdur, vdp);

            stat = new TextView(c);
            stat.setTextColor(Color.parseColor("#6f7581"));
            stat.setTextSize(10);
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stp.topMargin = (int) (10 * den);
            addView(stat, stp);
        }

        void bind(Dyn d) {
            item = d;
            name.setText(d.name);
            time.setText(d.time);
            if (d.text == null || d.text.isEmpty()) {
                text.setVisibility(GONE);
            } else {
                text.setVisibility(VISIBLE);
                text.setText(d.text);
            }
            if (d.face.isEmpty()) {
                avatar.setImageBitmap(null);
            } else {
                String f = ImgLoader.https(d.face);
                f = ImgLoader.face(f, 96);
                ImgLoader.load(avatar, f, dp(36), dp(36));
            }
            if (d.image.isEmpty()) {
                image.setVisibility(GONE);
            } else {
                image.setVisibility(VISIBLE);
                String u = ImgLoader.https(d.image);
                ImgLoader.load(image, u, dp(320), dp(180));
            }
            if (d.bvid.isEmpty()) {
                videoBox.setVisibility(GONE);
            } else {
                videoBox.setVisibility(VISIBLE);
                vtitle.setText(d.videoTitle);
                vdur.setText(d.videoDur);
                String cv = ImgLoader.https(d.videoCover);
                ImgLoader.load(vcover, cv, dp(108), dp(66));
            }
            if (d.statLine.isEmpty()) stat.setVisibility(GONE);
            else {
                stat.setVisibility(VISIBLE);
                stat.setText(d.statLine);
            }
        }

        int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }
    }

    static GradientDrawable roundBg(int color, float radDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * c.getResources().getDisplayMetrics().density);
        return g;
    }
}
