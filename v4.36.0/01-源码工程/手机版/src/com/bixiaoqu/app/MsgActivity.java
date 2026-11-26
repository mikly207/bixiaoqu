package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 消息中心：私信会话列表。
 * 数据源 api.vc.bilibili.com/session_svr/get_sessions（需登录），昵称头像批量走 user/cards。
 */
public class MsgActivity extends Activity {


    final ArrayList<Item> rows = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    ListView list;
    TextView tip;
    Adapter adapter;

    static class Item {
        long talkerId;
        String name = "";
        String face = "";
        String preview = "";
        String time = "";
        int unread;
        long ts;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try {
            setContentView(build());
            load();
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("消息页加载失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(30, 60, 30, 30);
            setContentView(tv);
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    GradientDrawable round(int color, float radPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radPx);
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
        title.setText("消息");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(6);
        top.addView(title, tp);

        Button readAll = new Button(this);
        readAll.setText("一键已读");
        readAll.setTextColor(MainActivity.C_ACC);
        readAll.setTextSize(11);
        readAll.setAllCaps(false);
        readAll.setPadding(dp(10), 0, dp(10), 0);
        readAll.setBackground(round(Theme.c(0x22FB7299), dp(15)));
        LinearLayout.LayoutParams rap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        rap.rightMargin = dp(6);
        top.addView(readAll, rap);
        readAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { markAllRead(); }
        });

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setTextColor(Color.WHITE);
        refresh.setTextSize(11);
        refresh.setAllCaps(false);
        refresh.setPadding(dp(12), 0, dp(12), 0);
        refresh.setBackground(round(0x33FFFFFF, dp(15)));
        top.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        root.addView(top);

        tip = new TextView(this);
        tip.setText("加载中…");
        tip.setTextColor(MainActivity.C_SUB);
        tip.setTextSize(12);
        tip.setPadding(dp(18), dp(4), dp(18), dp(6));
        root.addView(tip);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(10), 0, dp(10), dp(12));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              View v, int i, long id) {
                if (i < 0 || i >= rows.size()) return;
                Item it = rows.get(i);
                Intent in = new Intent(MsgActivity.this, ChatActivity.class);
                in.putExtra("talkerId", it.talkerId);
                in.putExtra("name", it.name);
                in.putExtra("face", it.face);
                startActivity(in);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    void load() {
        if (BiliApi.loggedInUid() == null) {
            tip.setText("登录后可查看私信");
            rows.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        tip.setText("加载中…");
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<Item> got = new ArrayList<>();
            final String[] err = {null};
            try {
                JSONArray list = BiliApi.sessions();
                ArrayList<Item> tmp = new ArrayList<>();
                StringBuilder uids = new StringBuilder();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    long tid = o.optLong("talker_id", 0);
                    if (tid <= 0) continue;
                    Item it = new Item();
                    it.talkerId = tid;
                    JSONObject last = o.optJSONObject("last_msg");
                    if (last != null) {
                        it.preview = BiliApi.previewOf(last);
                        it.ts = last.optLong("timestamp", 0);
                    }
                    it.unread = o.optInt("unread_count", 0);
                    it.name = o.optString("group_name", "");
                    if (it.name.isEmpty() && last != null) {
                        JSONObject c = null;
                        try { c = new JSONObject(last.optString("content", "")); } catch (Throwable ignore) { }
                        if (c != null) it.name = c.optString("name", "");
                    }
                    it.time = timeText(it.ts);
                    tmp.add(it);
                    if (uids.length() > 0) uids.append(',');
                    uids.append(tid);
                }

                // 批量补昵称 / 头像
                if (uids.length() > 0) {
                    JSONObject cards = BiliApi.userCards(uids.toString());
                    for (Item it : tmp) {
                        JSONObject c = cards.optJSONObject(String.valueOf(it.talkerId));
                        if (c != null) {
                            if (it.name.isEmpty()) it.name = c.optString("name", "");
                            it.face = c.optString("face", "");
                        }
                        if (it.name.isEmpty()) it.name = "用户 " + it.talkerId;
                    }
                }
                got.addAll(tmp);

                // 【v4.9.6】先上屏，别让逐个补头像的请求把整页拖慢
                ui.post(new Runnable() { @Override public void run() {
                    rows.clear();
                    rows.addAll(got);
                    adapter.notifyDataSetChanged();
                    if (rows.isEmpty()) {
                        tip.setText(err[0] != null ? "加载失败：" + err[0]
                                : "暂无会话（B 站私信接口对第三方客户端有风控，可稍后重试）");
                    } else {
                        tip.setText("共 " + rows.size() + " 个会话");
                    }
                } });

                // 【v4.9.6】批量名片接口（api.vc）常被风控 → 头像全空、昵称全变「用户 xxx」。
                // 用公开的 web-interface/card 逐个回补，最多补 24 个，补到就刷新。
                int patched = 0;
                boolean changed = false;
                for (Item it : tmp) {
                    if (!it.face.isEmpty()) continue;
                    if (patched >= 24) break;
                    patched++;
                    JSONObject c = BiliApi.card(it.talkerId);
                    if (c == null) continue;
                    String face = c.optString("face", "");
                    if (face.isEmpty()) continue;
                    it.face = face;
                    String nm = c.optString("name", "");
                    if (!nm.isEmpty() && it.name.startsWith("用户 ")) it.name = nm;
                    changed = true;
                }
                if (changed) {
                    ui.post(new Runnable() { @Override public void run() {
                        adapter.notifyDataSetChanged();
                    } });
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
                ui.post(new Runnable() { @Override public void run() {
                    rows.clear();
                    rows.addAll(got);
                    adapter.notifyDataSetChanged();
                    tip.setText(err[0] != null ? "加载失败：" + err[0] : "暂无会话");
                } });
            }
        } }).start();
    }

    /** 一键已读：对每个有未读的会话调已读接口，成功后清掉本地红点 */
    void markAllRead() {
        final ArrayList<Long> targets = new ArrayList<Long>();
        for (Item it : rows) if (it.unread > 0) targets.add(it.talkerId);
        if (targets.isEmpty()) {
            android.widget.Toast.makeText(this, "没有未读消息", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        tip.setText("正在标记已读…");
        new Thread(new Runnable() { @Override public void run() {
            final int[] ok = {0};
            for (Long tid : targets) {
                try { BiliApi.markSessionRead(tid); ok[0]++; }
                catch (Throwable ignore) { }
            }
            ui.post(new Runnable() { @Override public void run() {
                for (Item it : rows) it.unread = 0;
                adapter.notifyDataSetChanged();
                android.widget.Toast.makeText(MsgActivity.this,
                        ok[0] > 0 ? "已将 " + ok[0] + " 个会话标记为已读" : "已清除红点（部分接口受限）",
                        android.widget.Toast.LENGTH_SHORT).show();
                tip.setText("共 " + rows.size() + " 个会话");
            } });
        } }).start();
    }

    static String timeText(long ts) {
        if (ts <= 0) return "";
        long ms = ts < 100000000000L ? ts * 1000L : ts;
        Date d = new Date(ms);
        long now = System.currentTimeMillis();
        if (now - ms < 86400000L) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
        }
        if (now - ms < 172800000L) return "昨天";
        return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(d);
    }

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        class Holder {
            LinearLayout row;
            ImageView avatar;
            TextView name, pv, time, dot;
        }

        @Override public View getView(int i, View cv, ViewGroup parent) {
            Holder h;
            if (cv instanceof LinearLayout && cv.getTag() instanceof Holder) {
                h = (Holder) cv.getTag();
            } else {
                LinearLayout wrap = new LinearLayout(MsgActivity.this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.setPadding(0, 0, 0, dp(8));

                LinearLayout row = new LinearLayout(MsgActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(12), dp(10));
                row.setBackground(round(MainActivity.C_CARD, dp(12)));

                ImageView avatar = new ImageView(MsgActivity.this);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatar.setBackground(round(0xFF2A2E37, dp(22)));
                avatar.setClipToOutline(true);
                avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
                    }
                });
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(44), dp(44));
                ap.rightMargin = dp(12);
                row.addView(avatar, ap);

                LinearLayout col = new LinearLayout(MsgActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(MsgActivity.this);
                name.setTextColor(MainActivity.C_FG);
                name.setTextSize(14);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(name);

                TextView pv = new TextView(MsgActivity.this);
                pv.setTextColor(MainActivity.C_SUB);
                pv.setTextSize(12);
                pv.setSingleLine(true);
                pv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pp.topMargin = dp(3);
                col.addView(pv, pp);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout right = new LinearLayout(MsgActivity.this);
                right.setOrientation(LinearLayout.VERTICAL);
                right.setGravity(Gravity.END);
                TextView time = new TextView(MsgActivity.this);
                time.setTextColor(0x7A8A93A3);
                time.setTextSize(10);
                right.addView(time);
                TextView dot = new TextView(MsgActivity.this);
                dot.setTextColor(Color.WHITE);
                dot.setTextSize(9);
                dot.setGravity(Gravity.CENTER);
                dot.setPadding(dp(5), 0, dp(5), 0);
                dot.setBackground(round(Theme.c(0xFFFB7299), dp(9)));
                LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(18));
                dp2.topMargin = dp(5);
                dp2.gravity = Gravity.END;
                right.addView(dot, dp2);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.leftMargin = dp(10);
                row.addView(right, rp);

                wrap.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                h = new Holder();
                h.row = row; h.avatar = avatar; h.name = name;
                h.pv = pv; h.time = time; h.dot = dot;
                wrap.setTag(h);
                cv = wrap;
            }

            final Item it = rows.get(i);
            h.name.setText(it.name);
            h.pv.setText(it.preview.isEmpty() ? "暂无消息" : it.preview);
            h.time.setText(it.time);
            if (!it.face.isEmpty()) {
                ImgLoader.load(h.avatar, fixUrl(it.face), dp(44), dp(44));
            } else {
                ImgLoader.load(h.avatar, null, dp(44), dp(44));
                h.avatar.setImageDrawable(round(0xFF2A2E37, dp(22)));
            }
            if (it.unread > 0) {
                h.dot.setVisibility(View.VISIBLE);
                h.dot.setText(it.unread > 99 ? "99+" : String.valueOf(it.unread));
            } else {
                h.dot.setVisibility(View.GONE);
            }

            // 头像点击：进入对方空间查看内容（不触发整行进入聊天）
            h.avatar.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent u = new Intent(MsgActivity.this, UserActivity.class);
                    u.putExtra("mid", String.valueOf(it.talkerId));
                    u.putExtra("name", it.name);
                    u.putExtra("face", fixUrl(it.face));
                    startActivity(u);
                }
            });
            return cv;
        }
    }

    /** 统一交给 ImgLoader.https 处理（同时兼容 "//" 与 "http://" 两种旧写法） */
    static String fixUrl(String u) {
        return ImgLoader.https(u);
    }
}
