package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 我的 → 四个入口的通用列表页：
 * cache   离线缓存（本地 MP4，来源 MediaStore 电影/BiliAudio）
 * history 历史记录（本地）
 * fav     我的收藏（先列文件夹，再进具体收藏夹）
 * toview  稍后再看（B 站）
 */
public class ShelfActivity extends Activity {

    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<VideoItem> rows = new ArrayList<>();
    ListView list;
    VideoAdapter adapter;
    TextView tip, titleTv;
    String mode = "cache";
    long favFolder = -1;      // >0 表示已进入某个收藏夹
    String favFolderName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.applyTheme(this);
        mode = getIntent() != null ? getIntent().getStringExtra("mode") : "cache";
        if (mode == null) mode = "cache";
        buildUi();
        load();
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    String titleOf() {
        switch (mode) {
            case "history": return "历史记录";
            case "fav": return favFolder > 0 ? "收藏夹 · " + favFolderName : "我的收藏";
            case "toview": return "稍后再看";
            default: return "离线缓存";
        }
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏：返回箭头 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(12), dp(8));

        Button back = new Button(this);
        back.setText("←");
        back.setTextSize(18);
        back.setTextColor(MainActivity.C_FG);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setAllCaps(false);
        back.setPadding(dp(14), 0, dp(14), 0);
        top.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 收藏夹里点返回先回到文件夹列表
                if ("fav".equals(mode) && favFolder > 0) {
                    favFolder = -1;
                    favFolderName = "";
                    titleTv.setText(titleOf());
                    load();
                    return;
                }
                finish();
            }
        });

        titleTv = new TextView(this);
        titleTv.setText(titleOf());
        titleTv.setTextColor(MainActivity.C_FG);
        titleTv.setTextSize(17);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = new Button(this);
        clear.setText(mode.equals("history") ? "清空" : "刷新");
        clear.setTextSize(12);
        clear.setTextColor(MainActivity.C_SUB);
        clear.setBackgroundColor(Color.TRANSPARENT);
        clear.setAllCaps(false);
        top.addView(clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mode.equals("history")) {
                    History.clear(ShelfActivity.this);
                }
                load();
            }
        });

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout wrap = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(9));
        list.setPadding(dp(10), dp(4), dp(10), dp(12));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new VideoAdapter(this, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              View v, int i, long id) {
                open(i);
            }
        });

        tip = new TextView(this);
        tip.setTextColor(MainActivity.C_SUB);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(10), 0, 0);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        wrap.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(tip, tp);

        root.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    void load() {
        tip.setText("加载中…");
        rows.clear();
        notifyData();
        titleTv.setText(titleOf());

        if (mode.equals("history")) {
            rows.addAll(History.list(this));
            finishLoad(rows.isEmpty() ? "还没有观看记录，先去推荐页看个视频吧" : null);
            return;
        }
        if (mode.equals("cache")) {
            loadCache();
            return;
        }

        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                String uid = BiliApi.loggedInUid();
                if (uid == null) throw new Exception("未登录，请先在「我的」页登录");
                if ("fav".equals(mode)) loadFav(got);
                else loadToView(got);
            } catch (Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                rows.clear();
                rows.addAll(got);
                finishLoad(got.isEmpty() ? (err[0] != null ? err[0] : emptyTip()) : null);
            } });
        } }).start();
    }

    String emptyTip() {
        if ("fav".equals(mode)) return "收藏夹是空的";
        if ("toview".equals(mode)) return "稍后再看是空的";
        return "这里是空的";
    }

    /** 收藏：先文件夹，后内容 */
    void loadFav(ArrayList<VideoItem> got) throws Exception {
        String uid = BiliApi.loggedInUid();
        if (favFolder <= 0) {
            JSONArray folders = BiliApi.favFolders(uid);
            long onlyId = -1;
            String onlyName = "";
            int count = 0;
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f == null) continue;
                if (f.optInt("media_count", 0) <= 0) continue;
                count++;
                onlyId = f.optLong("id", 0);
                onlyName = f.optString("title", "收藏夹");
            }
            if (count == 0) throw new Exception("收藏夹是空的");
            if (count == 1) {
                favFolder = onlyId;
                favFolderName = onlyName;
            } else {
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject f = folders.optJSONObject(i);
                    if (f == null) continue;
                    int n = f.optInt("media_count", 0);
                    if (n <= 0) continue;
                    long id = f.optLong("id", 0);
                    String t = f.optString("title", "收藏夹");
                    got.add(new VideoItem("fav:" + id + ":" + t, t,
                            n + " 个视频 · 点击进入", null));
                }
                return;
            }
        }
        JSONArray medias = BiliApi.favItems(favFolder);
        for (int i = 0; i < medias.length(); i++) {
            JSONObject o = medias.optJSONObject(i);
            if (o == null) continue;
            String bv = o.optString("bvid", "");
            if (bv.isEmpty()) continue;
            String up = o.optJSONObject("upper") != null
                    ? o.getJSONObject("upper").optString("name", "") : "";
            int dur = o.optInt("duration", 0);
            StringBuilder sub = new StringBuilder();
            if (!up.isEmpty()) sub.append(up);
            if (dur > 0) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append(VideoItem.dur(dur));
            }
            got.add(new VideoItem(bv, BiliApi.cleanTitle(o.optString("title", "")),
                    sub.toString(), o.optString("cover", "")));
        }
    }

    void loadToView(ArrayList<VideoItem> got) throws Exception {
        JSONArray arr = BiliApi.toView();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String bv = o.optString("bvid", "");
            if (bv.isEmpty()) continue;
            String up = o.optJSONObject("owner") != null
                    ? o.getJSONObject("owner").optString("name", "") : "";
            int dur = o.optInt("duration", 0);
            StringBuilder sub = new StringBuilder();
            if (!up.isEmpty()) sub.append(up);
            if (dur > 0) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append(VideoItem.dur(dur));
            }
            got.add(new VideoItem(bv, BiliApi.cleanTitle(o.optString("title", "")),
                    sub.toString(), o.optString("pic", "")));
        }
    }

    void finishLoad(String emptyTip) {
        if (rows.isEmpty() && emptyTip != null) {
            rows.add(new VideoItem("", emptyTip, "", null));
        }
        tip.setText("");
        titleTv.setText(titleOf());
        notifyData();
    }

    void loadCache() {
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            try {
                String[] proj = {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION
                };
                Cursor c = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                        // 【v4.11.0】低版本没有 relative_path 列，统一用 DATA 列做路径匹配
                        MediaStore.Video.Media.DATA + " LIKE ?",
                        new String[]{"%BiliAudio%"}, MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (c != null) {
                    int iId = c.getColumnIndex(MediaStore.Video.Media._ID);
                    int iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    while (c.moveToNext()) {
                        long id = c.getLong(iId);
                        String name = c.getString(iName);
                        int dur = c.getInt(iDur);
                        Uri uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        VideoItem it = new VideoItem("uri:" + uri.toString(), name,
                                (dur > 0 ? VideoItem.dur(dur / 1000) + "  ·  " : "")
                                        + "已缓存 MP4", null);
                        it.localUri = uri.toString();
                        got.add(it);
                    }
                    c.close();
                }
            } catch (Throwable e) {
                got.add(new VideoItem("", "读取缓存失败：" + e.getMessage(), "", null));
            }
            ui.post(new Runnable() { @Override public void run() {
                rows.clear();
                rows.addAll(got);
                finishLoad(got.isEmpty()
                        ? "还没有缓存视频。播放页点「缓存MP4」即可离线保存" : null);
            } });
        } }).start();
    }

    void open(int i) {
        if (i < 0 || i >= rows.size()) return;
        VideoItem it = rows.get(i);
        if (it.isPlaceholder()) return;
        if (it.bvid.startsWith("fav:")) {
            String rest = it.bvid.substring(4);
            int sep = rest.indexOf(':');
            if (sep > 0) {
                try { favFolder = Long.parseLong(rest.substring(0, sep)); } catch (Throwable ignore) { }
                favFolderName = rest.substring(sep + 1);
                load();
            }
            return;
        }
        if (it.bvid.startsWith("uri:")) {
            // 【v4.9.2】先确认有读取本地视频的权限，否则 MediaPlayer 打不开 content://
            // 【v4.11.0】权限 API 走 Compat（API<23 安装时已授予，直接放行）
            String perm = android.os.Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_VIDEO"
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            if (!Compat.hasPerm(this, perm)) {
                Compat.requestPerm(this, perm, 1001);
                Toast.makeText(this, "请允许读取视频，授权后再点一次即可播放",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // 用应用自己的播放器直接打开本地缓存。
            // 以前是 Intent.ACTION_VIEW 甩给外部播放器 —— 多数手机没设默认播放器，
            // 或者弹「无法播放」，用户感受就是「点了没反应」。
            try {
                Intent v = new Intent(this, PlayActivity.class);
                v.putExtra("localUri", it.bvid.substring(4));
                v.putExtra("ptitle", it.title);
                startActivity(v);
            } catch (Throwable e) {
                Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        Intent it2 = new Intent(this, PlayActivity.class);
        it2.putExtra("bvid", it.bvid);
        startActivity(it2);
    }

    void notifyData() {
        try {
            if (adapter != null) ((BaseAdapter) adapter).notifyDataSetChanged();
        } catch (Throwable ignore) { }
    }
}
