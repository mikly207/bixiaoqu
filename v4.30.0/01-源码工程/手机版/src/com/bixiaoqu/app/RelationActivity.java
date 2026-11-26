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

/** 关注 / 粉丝列表：头像 + 昵称 + 签名，点击进入 TA 的空间 */
public class RelationActivity extends Activity {


    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<JSONObject> users = new ArrayList<>();
    String mode = "following";
    String uid = "";
    String uname = "";
    String tip = "加载中…";
    UAdapter adapter;
    ListView list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        mode = getIntent().getStringExtra("mode");
        if (!"followers".equals(mode)) mode = "following";
        uid = getIntent().getStringExtra("mid");
        uname = getIntent().getStringExtra("name");
        if (uid == null) uid = BiliApi.loggedInUid();
        if (uid == null) uid = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏
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
        title.setText("following".equals(mode) ? "关注" : "粉丝");
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
        list.setDividerHeight(dp(8));
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        list.setClipToPadding(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new UAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                if (i < 0 || i >= users.size()) return;
                JSONObject o = users.get(i);
                Intent it = new Intent(RelationActivity.this, UserActivity.class);
                it.putExtra("mid", o.optString("mid", ""));
                it.putExtra("name", o.optString("uname", ""));
                it.putExtra("face", o.optString("face", ""));
                startActivity(it);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        load();
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    void load() {
        new Thread(new Runnable() { @Override public void run() {
            try {
                JSONArray arr = "followers".equals(mode)
                        ? BiliApi.followers(uid, 1) : BiliApi.followings();
                final ArrayList<JSONObject> got = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && !o.optString("uname", "").isEmpty()) got.add(o);
                    }
                }
                ui.post(new Runnable() { @Override public void run() {
                    users.clear();
                    users.addAll(got);
                    tip = got.isEmpty()
                            ? ("following".equals(mode) ? "还没有关注任何人" : "还没有粉丝")
                            : "";
                    adapter.notifyDataSetChanged();
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    tip = "加载失败：" + e.getMessage();
                    adapter.notifyDataSetChanged();
                    Toast.makeText(RelationActivity.this, tip, Toast.LENGTH_LONG).show();
                } });
            }
        } }).start();
    }

    class UAdapter extends BaseAdapter {
        final Context ctx;
        UAdapter(Context c) { ctx = c; }

        @Override public int getCount() { return users.isEmpty() ? 1 : users.size(); }
        @Override public Object getItem(int i) { return i < users.size() ? users.get(i) : null; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup p) {
            if (users.isEmpty()) {
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
            Row r = (cv instanceof Row) ? (Row) cv : new Row(ctx);
            JSONObject o = users.get(i);
            r.name.setText(o.optString("uname", "用户"));
            String sign = o.optString("sign", "").trim();
            r.sign.setText(sign.isEmpty() ? "这个人很神秘，什么都没写" : sign);
            String face = o.optString("face", "");
            if (!face.isEmpty()) {
                face = ImgLoader.https(face);
                face = ImgLoader.face(face, 120);
                ImgLoader.load(r.avatar, face, dp(46), dp(46));
            } else {
                r.avatar.setImageBitmap(null);
            }
            return r;
        }
    }

    static GradientDrawable roundBg(int color, float radDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * c.getResources().getDisplayMetrics().density);
        return g;
    }

    static class Row extends LinearLayout {
        ImageView avatar;
        TextView name, sign;

        Row(Context c) {
            super(c);
            float den = c.getResources().getDisplayMetrics().density;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (12 * den), (int) (11 * den),
                    (int) (12 * den), (int) (11 * den));
            setBackground(roundBg(MainActivity.C_CARD, 12, c));
            setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            avatar = new ImageView(c);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackgroundColor(ImgLoader.placeholderColor());
            final float rad = 23 * den;
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            addView(avatar, new LinearLayout.LayoutParams((int) (46 * den), (int) (46 * den)));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = (int) (12 * den);
            addView(col, cp);

            name = new TextView(c);
            name.setTextColor(MainActivity.C_FG);
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            sign = new TextView(c);
            sign.setTextColor(MainActivity.C_SUB);
            sign.setTextSize(11);
            sign.setSingleLine(true);
            sign.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = (int) (4 * den);
            col.addView(sign, sp);

            TextView arrow = new TextView(c);
            arrow.setText("›");
            arrow.setTextColor(Color.parseColor("#5c626d"));
            arrow.setTextSize(20);
            addView(arrow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
