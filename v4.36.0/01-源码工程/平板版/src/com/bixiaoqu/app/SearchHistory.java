package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;

/** 本地搜索历史（最多 12 条，去重置顶，不上传） */
public final class SearchHistory {

    static final String PREF = "search_hist";
    static final String KEY = "list";
    static final int MAX = 12;

    public static void add(Context c, String kw) {
        if (c == null || kw == null) return;
        kw = kw.trim();
        if (kw.isEmpty() || kw.length() > 60) return;
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray out = new JSONArray();
            out.put(kw);
            for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                String s = old.optString(i, "");
                if (s.isEmpty() || s.equals(kw)) continue;
                out.put(s);
            }
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Throwable ignore) { }
    }

    public static ArrayList<String> list(Context c) {
        ArrayList<String> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static void clear(Context c) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignore) { }
    }

    private SearchHistory() { }
}
