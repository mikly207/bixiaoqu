package com.bixiaoqu.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 音频提取器：粘贴链接 → 下载音轨 → LAME 320k MP3 → 保存到 音乐/BiliAudio */
public class AudioToolActivity extends Activity {

    EditText input;
    TextView logView;
    ProgressBar bar;
    Button go;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.applyTheme(this);
        loadCookies();
        buildUi();
        Intent it = getIntent();
        if (it != null && Intent.ACTION_SEND.equals(it.getAction())) {
            CharSequence t = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                input.setText(t.toString());
                start();
            }
        }
    }

    void loadCookies() {
        android.content.SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
        String sess = sp.getString("SESSDATA", "");
        if (!sess.isEmpty()) {
            BiliApi.setLoginCookie(sess, sp.getString("bili_jct", ""),
                    sp.getString("DedeUserID", ""));
        }
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);
        int pad = (int) (getResources().getDisplayMetrics().density * 14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("音频提取器（MP3 320k）");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        input = new EditText(this);
        input.setHint("粘贴视频链接 / BV号 / av号，每行一个");
        input.setTextColor(MainActivity.C_FG);
        input.setHintTextColor(Color.parseColor("#565b64"));
        input.setBackgroundColor(MainActivity.C_CARD);
        input.setTextSize(13);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = (int) (pad * 0.6);
        root.addView(input, ip);

        go = new Button(this);
        go.setText("开始提取 MP3");
        go.setTextColor(Color.WHITE);
        go.setTextSize(15);
        go.setBackgroundColor(MainActivity.C_ACC);
        go.setAllCaps(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (pad * 2.6));
        gp.topMargin = (int) (pad * 0.7);
        root.addView(go, gp);
        go.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { start(); }
        });

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (pad * 0.4);
        root.addView(bar, bp);

        logView = new TextView(this);
        logView.setTextColor(MainActivity.C_FG);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setPadding(12, 12, 12, 12);
        logView.setBackgroundColor(Color.parseColor("#101216"));
        logView.setText("就绪。登录后自动 320k，未登录 64-132k。\n");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = (int) (pad * 0.6);
        root.addView(sv, lp);

        setContentView(root);
    }

    void log(String s) {
        ui.post(new Runnable() { @Override public void run() {
            logView.append(s + "\n");
        } });
    }

    void setProgress(double r) {
        ui.post(new Runnable() { @Override public void run() {
            bar.setProgress((int) Math.min(1000, r * 1000));
        } });
    }

    void start() {
        if (busy) return;
        String text = input.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "请先粘贴链接", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        go.setEnabled(false);
        go.setText("提取中…");
        bar.setProgress(0);
        exec.execute(new Runnable() { @Override public void run() {
            try {
                for (String line : text.split("\\r?\\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    try { processOne(line); }
                    catch (Throwable e) { log("✗ 失败：" + e.getMessage()); }
                }
                log("—— 全部完成 ——");
            } finally {
                ui.post(new Runnable() { @Override public void run() {
                    busy = false;
                    go.setEnabled(true);
                    go.setText("开始提取 MP3");
                } });
            }
        } });
    }

    void processOne(String line) throws Exception {
        log("解析：" + line);
        String bvid = BiliApi.extractBvid(line);
        String aid = null;
        if (bvid == null) {
            aid = BiliApi.extractAvid(line);
            if (aid == null) {
                log("✗ 无法识别（需要 BV 号或 av 号）");
                return;
            }
        }
        JSONObject v = BiliApi.view(bvid, aid);
        if (bvid == null) bvid = v.optString("bvid", null);
        String title = sanitize(v.getString("title"));
        long aidN = v.optLong("aid");
        JSONArray pages = v.getJSONArray("pages");
        log("《" + title + "》 共 " + pages.length() + " 个分P");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject pg = pages.getJSONObject(i);
            long cid = pg.getLong("cid");
            String name = pages.length() > 1 ? title + "_P" + pg.optInt("page", i + 1) : title;
            try {
                processPage(bvid, aidN > 0 ? String.valueOf(aidN) : null, cid, name);
            } catch (Throwable e) {
                log("  ✗ P" + pg.optInt("page", i + 1) + "：" + e.getMessage());
            }
        }
    }

    void processPage(String bvid, String aid, long cid, String name) throws Exception {
        log("  获取播放信息…");
        JSONObject data = BiliApi.playurl(bvid, aid, cid);
        String[] src = BiliApi.bestAudioSource(data);
        File in = new File(getCacheDir(), "t_src.bin");
        File out = new File(getCacheDir(), "t_out.mp3");
        log("  下载音轨…");
        download(src[0], in);
        log("  LAME 编码 320k…");
        AudioConverter.decodeToMp3(in.getAbsolutePath(), out.getAbsolutePath(), 320,
                new AudioConverter.Progress() {
                    @Override public void onProgress(double r) { setProgress(r); }
                });
        String display = sanitize(name) + ".mp3";
        saveToMusic(out, display);
        log("  ✓ 已保存 音乐/BiliAudio/" + display);
        in.delete();
        out.delete();
    }

    void download(String url, File dst) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", BiliApi.UA);
        c.setRequestProperty("Referer", BiliApi.REFERER);
        StringBuilder ck = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : BiliApi.COOKIES.entrySet()) {
            if (ck.length() > 0) ck.append("; ");
            ck.append(e.getKey()).append('=').append(e.getValue());
        }
        if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());
        int code = c.getResponseCode();
        if (code != 200 && code != 206) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        c.disconnect();
    }

    void saveToMusic(File src, String displayName) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // 【v4.11.0】低版本没有 relative_path/is_pending，直接 File 写公共 Music 目录
            Compat.savePublicLegacy(this, src, displayName,
                    Environment.DIRECTORY_MUSIC, "BiliAudio");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        Compat.putRelativePath(cv, Environment.DIRECTORY_MUSIC + "/BiliAudio");
        Compat.putPending(cv, 1);
        Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            File f = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), displayName);
            copy(src, f);
            return;
        }
        OutputStream os = getContentResolver().openOutputStream(uri);
        byte[] buf = new byte[32 * 1024];
        InputStream is = new FileInputStream(src);
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
        cv.clear();
        Compat.putPending(cv, 0);
        getContentResolver().update(uri, cv, null, null);
    }

    static void copy(File src, File dst) throws Exception {
        FileInputStream is = new FileInputStream(src);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                      .replaceAll("^[ ._-]+|[ ._-]+$", "");
        if (s.isEmpty()) s = "untitled";
        return s.substring(0, Math.min(80, s.length()));
    }
}
