package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放进度记忆：记住每个视频看到的位置，下次打开自动续播。
 * 仅本地保存 bvid -> 毫秒位置 / 时长，不上传任何数据。
 */
public final class PlayPos {

    static final String PREF = "play_pos";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 保存进度：看完（>95%）或太靠前（<5s）就清掉，避免下次无意义续播 */
    public static void save(Context c, String bvid, int posMs, int durMs) {
        if (c == null || bvid == null || bvid.isEmpty()) return;
        try {
            if (durMs > 0 && posMs > durMs * 0.95) { clear(c, bvid); return; }
            if (posMs < 5000) return;
            sp(c).edit()
                    .putInt(bvid + "_p", posMs)
                    .putInt(bvid + "_d", durMs)
                    .putLong(bvid + "_t", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignore) { }
    }

    public static int pos(Context c, String bvid) {
        if (c == null || bvid == null) return 0;
        try { return sp(c).getInt(bvid + "_p", 0); } catch (Throwable t) { return 0; }
    }

    public static int dur(Context c, String bvid) {
        if (c == null || bvid == null) return 0;
        try { return sp(c).getInt(bvid + "_d", 0); } catch (Throwable t) { return 0; }
    }

    public static void clear(Context c, String bvid) {
        if (c == null || bvid == null) return;
        try {
            sp(c).edit().remove(bvid + "_p").remove(bvid + "_d").remove(bvid + "_t").apply();
        } catch (Throwable ignore) { }
    }

    /** 毫秒转 m:ss，供提示文案用 */
    public static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%d:%02d", s / 60, s % 60);
    }

    private PlayPos() { }
}
