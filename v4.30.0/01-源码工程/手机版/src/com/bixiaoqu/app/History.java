package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 本地观看历史（仅记录 bvid + 标题 + 时间，不上传任何数据） */
public final class History {

    static final String PREF = "history";
    static final String KEY = "list";
    static final int MAX = 60;

    public static void add(Context c, String bvid, String title) {
        add(c, bvid, title, null);
    }

    /** 【v4.13.16】带封面的写入：封面一并持久化，历史页才有缩略图 */
    public static void add(Context c, String bvid, String title, String pic) {
        if (c == null || bvid == null || bvid.isEmpty()) return;
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray out = new JSONArray();
            out.put(item(bvid, title, pic));
            for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (bvid.equals(o.optString("bvid", ""))) continue;  // 去重，最新置顶
                // 【v4.13.16】旧记录若没封面、本次拿到了，顺手补齐（不改动观看时间）
                if (pic != null && !pic.isEmpty() && o.optString("pic", "").isEmpty()) {
                    try { o.put("pic", pic); } catch (Throwable ignore) { }
                }
                out.put(o);
            }
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Throwable ignore) { }
    }

    public static ArrayList<VideoItem> list(Context c) {
        ArrayList<VideoItem> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String bv = o.optString("bvid", "");
                if (bv.isEmpty()) continue;
                // 【v4.13.16】把持久化的封面带出来，历史项才会显示缩略图
                out.add(new VideoItem(bv, o.optString("title", bv),
                        "观看于 " + time(o.optLong("t", 0)), o.optString("pic", "")));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static void clear(Context c) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignore) { }
    }

    static JSONObject item(String bvid, String title, String pic) {
        JSONObject o = new JSONObject();
        try {
            o.put("bvid", bvid);
            o.put("title", title == null ? bvid : title);
            o.put("pic", pic == null ? "" : pic);   // 【v4.13.16】封面地址
            o.put("t", System.currentTimeMillis());
        } catch (Throwable ignore) { }
        return o;
    }

    static String time(long t) {
        if (t <= 0) return "—";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.CHINA);
        return f.format(new java.util.Date(t));
    }

    private History() {}
}
