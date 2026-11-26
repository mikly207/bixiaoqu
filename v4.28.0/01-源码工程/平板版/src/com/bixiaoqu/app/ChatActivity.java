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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 私信聊天窗口：拉取与某个 UP 主/用户的会话记录，可发送文本。
 * 接口：svr_sync/fetch_session_msgs 拉记录，web_im/send_msg 发送（都要登录 + csrf）。
 */
public class ChatActivity extends Activity {


    final ArrayList<Msg> rows = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    long talkerId;
    String peerName = "", peerFace = "";
    ListView list;
    Adapter adapter;
    EditText input;
    TextView tip;

    static class Msg {
        boolean mine;
        String text = "";
        long ts;
        boolean failed;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try {
            Intent in = getIntent();
            talkerId = in != null ? in.getLongExtra("talkerId", 0) : 0;
            peerName = in != null ? in.getStringExtra("name") : "";
            peerFace = in != null ? in.getStringExtra("face") : "";
            if (peerName == null || peerName.isEmpty()) peerName = "用户 " + talkerId;
            setContentView(build());
            load();
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("聊天页加载失败：\n" + android.util.Log.getStackTraceString(e));
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

        // 顶栏：返回 + 头像 + 昵称
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(14), dp(8));
        top.setBackgroundColor(0xFF1A1D23);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setBackground(round(0xFF2A2E37, dp(16)));
        av.setClipToOutline(true);
        av.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
        if (peerFace != null && !peerFace.isEmpty()) {
            ImgLoader.load(av, MsgActivity.fixUrl(peerFace), dp(32), dp(32));
        }
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(32), dp(32));
        avp.leftMargin = dp(2);
        avp.rightMargin = dp(9);
        top.addView(av, avp);

        TextView name = new TextView(this);
        name.setText(peerName);
        name.setTextColor(MainActivity.C_FG);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        tip = new TextView(this);
        tip.setText("加载中…");
        tip.setTextColor(MainActivity.C_SUB);
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(6), 0, dp(6));
        root.addView(tip);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        list.setPadding(dp(12), dp(6), dp(12), dp(10));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部输入区
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(0xFF1A1D23);

        input = new EditText(this);
        input.setHint("发条私信…");
        input.setHintTextColor(0x66FFFFFF);
        input.setTextColor(MainActivity.C_FG);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackground(round(0xFF262A32, dp(18)));
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        bar.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = new Button(this);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(13);
        send.setAllCaps(false);
        send.setPadding(dp(16), 0, dp(16), 0);
        send.setBackground(round(MainActivity.C_ACC, dp(18)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        sp.leftMargin = dp(8);
        bar.addView(send, sp);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });
        root.addView(bar);
        return root;
    }

    void load() {
        tip.setText("加载中…");
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<Msg> got = new ArrayList<>();
            final String[] err = {null};
            try {
                JSONArray arr = BiliApi.sessionMsgs(talkerId, 0, 50);
                String me = BiliApi.loggedInUid();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Msg m = new Msg();
                    m.mine = String.valueOf(o.optLong("sender_uid", 0)).equals(me);
                    m.ts = o.optLong("timestamp", 0) * 1000L;
                    m.text = BiliApi.previewOf(o);
                    if (m.text == null || m.text.isEmpty()) continue;
                    got.add(m);
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                rows.clear();
                rows.addAll(got);
                adapter.notifyDataSetChanged();
                scrollBottom();
                tip.setText(err[0] != null ? "加载失败：" + err[0]
                        : (rows.isEmpty() ? "还没有聊过，打个招呼吧" : ""));
            } });
        } }).start();
    }

    void send() {
        final String txt = input.getText().toString().trim();
        if (txt.isEmpty()) return;
        input.setText("");
        final Msg m = new Msg();
        m.mine = true;
        m.text = txt;
        m.ts = System.currentTimeMillis();
        rows.add(m);
        adapter.notifyDataSetChanged();
        scrollBottom();

        new Thread(new Runnable() { @Override public void run() {
            try {
                BiliApi.sendMsg(talkerId, txt);
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    m.failed = true;
                    adapter.notifyDataSetChanged();
                    Toast.makeText(ChatActivity.this, "发送失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                } });
            }
        } }).start();
    }

    void scrollBottom() {
        try {
            if (adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1);
        } catch (Throwable ignore) { }
    }

    static String hm(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup parent) {
            Msg m = rows.get(i);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int maxW = (int) (screenW * 0.72f);

            LinearLayout wrap = new LinearLayout(ChatActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(4), 0, dp(4));
            wrap.setGravity(m.mine ? Gravity.END : Gravity.START);

            LinearLayout line = new LinearLayout(ChatActivity.this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.BOTTOM);

            if (!m.mine) {
                ImageView av = new ImageView(ChatActivity.this);
                av.setScaleType(ImageView.ScaleType.CENTER_CROP);
                av.setBackground(round(0xFF2A2E37, dp(14)));
                av.setClipToOutline(true);
                av.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
                    }
                });
                if (peerFace != null && !peerFace.isEmpty()) {
                    ImgLoader.load(av, MsgActivity.fixUrl(peerFace), dp(30), dp(30));
                }
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(30), dp(30));
                ap.rightMargin = dp(8);
                line.addView(av, ap);
            }

            TextView bubble = new TextView(ChatActivity.this);
            bubble.setText(m.text);
            bubble.setTextSize(14);
            bubble.setTextColor(m.mine ? Color.WHITE : MainActivity.C_FG);
            bubble.setLineSpacing(dp(2), 1f);
            bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
            bubble.setMaxWidth(maxW);
            bubble.setBackground(round(m.mine ? MainActivity.C_ACC : 0xFF262A32, dp(14)));
            line.addView(bubble);

            wrap.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView meta = new TextView(ChatActivity.this);
            meta.setText(hm(m.ts) + (m.failed ? " · 发送失败" : ""));
            meta.setTextColor(m.failed ? 0xFFE05B5B : 0x668A93A3);
            meta.setTextSize(9);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = dp(3);
            mp.leftMargin = dp(4);
            mp.rightMargin = dp(4);
            mp.gravity = m.mine ? Gravity.END : Gravity.START;
            wrap.addView(meta, mp);
            return wrap;
        }
    }
}
