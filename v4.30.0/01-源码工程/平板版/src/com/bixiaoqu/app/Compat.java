package com.bixiaoqu.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 【v4.11.0】低版本兼容层：minSdk 从 29 降到 22（Android 5.1）。
 * 所有「编译期需要高 API 符号」的调用统一收拢到这里，
 * 运行时按 Build.VERSION.SDK_INT 分流，低版本走降级实现。
 */
public final class Compat {

    private Compat() { }

    /** 运行时权限检查：PackageManager.checkPermission 全版本可用；API<23 安装时已授予必为 true。 */
    public static boolean hasPerm(Context ctx, String perm) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return ctx.getPackageManager()
                    .checkPermission(perm, ctx.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 运行时权限请求：requestPermissions 是 API 23+ 方法，走反射；API<23 直接忽略。 */
    public static void requestPerm(Activity act, String perm, int code) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            act.getClass()
               .getMethod("requestPermissions", String[].class, int.class)
               .invoke(act, new String[]{perm}, code);
        } catch (Throwable ignore) { }
    }

    /**
     * 播放倍速。PlaybackParams 是 API 23+ 的类，
     * 为让 API 22 也能编译通过，这里全部走反射；
     * API<23 上 MediaPlayer 不支持变速，直接静默跳过。
     */
    public static void applyRate(MediaPlayer mp, float r) {
        if (mp == null || Build.VERSION.SDK_INT < 23) return;
        try {
            Object pp = mp.getClass().getMethod("getPlaybackParams").invoke(mp);
            pp.getClass().getMethod("setSpeed", float.class).invoke(pp, r);
            mp.getClass().getMethod("setPlaybackParams", pp.getClass()).invoke(mp, pp);
        } catch (Throwable ignore) { }
    }

    /**
     * 把文件保存到公共媒体目录（API<29 时 MediaStore 没有
     * relative_path / is_pending，只能 File 直写）。
     * 需要 WRITE_EXTERNAL_STORAGE（API 19~28）；无权限时回退到应用私有目录。
     */
    public static void savePublicLegacy(Context ctx, File src, String displayName,
                                        String dirType, String subDir) throws Exception {
        File base = Environment.getExternalStoragePublicDirectory(dirType);
        File dstDir = new File(base, subDir);
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 23 || !base.exists()) {
            // API 23~28：需要运行时写权限；缺权限直接回退私有目录
            if (Build.VERSION.SDK_INT <= 28
                    && !hasPerm(ctx, "android.permission.WRITE_EXTERNAL_STORAGE")) {
                ok = false;
            } else {
                ok = dstDir.isDirectory() || dstDir.mkdirs();
            }
        } else {
            ok = dstDir.isDirectory() || dstDir.mkdirs();
        }
        File dst = ok ? new File(dstDir, displayName)
                : new File(ctx.getExternalFilesDir(dirType), displayName);
        copyFile(src, dst);
    }

    static void copyFile(File src, File dst) throws Exception {
        FileInputStream is = new FileInputStream(src);
        File p = dst.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
    }

    /** 兼容用 ContentValues 写入（避免引用 API29 常量符号，统一用字符串 key）。 */
    public static void putRelativePath(ContentValues cv, String v) {
        cv.put("relative_path", v);
    }

    public static void putPending(ContentValues cv, int v) {
        cv.put("is_pending", v);
    }

    /** 本地视频缩略图：API29 的 loadThumbnail 在低版本没有，统一走 MediaMetadataRetriever。 */
    public static android.graphics.Bitmap videoThumb(Context ctx, Uri uri, int w, int h) {
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            android.graphics.Bitmap f = mmr.getFrameAtTime();
            if (f == null) return null;
            int fw = f.getWidth(), fh = f.getHeight();
            if (fw <= 0 || fh <= 0) return f;
            float scale = Math.min((float) w / fw, (float) h / fh);
            if (scale >= 1f) return f;
            return android.graphics.Bitmap.createScaledBitmap(
                    f, Math.max(1, (int) (fw * scale)), Math.max(1, (int) (fh * scale)), true);
        } catch (Throwable t) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignore) { }
        }
    }
}
