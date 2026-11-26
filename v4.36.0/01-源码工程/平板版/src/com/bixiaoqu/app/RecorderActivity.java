package com.bixiaoqu.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 【v4.14.0】录音棚：录音 + 播放 + 重命名 + 删除 + 在线转文字。
 *
 * API22 兼容：MediaRecorder(MPEG_4/AAC) 全版本可用；运行时权限走 Compat 反射；
 * 录音保存到应用私有目录 getExternalFilesDir，无需写存储权限。
 */
public class RecorderActivity extends Activity {

    static final int REQ_REC = 71;

    final Handler ui = new Handler(Looper.getMainLooper());

    MediaRecorder recorder;
    MediaPlayer player;
    boolean recording = false;
    File recordDir;
    File currentFile;
    long recordStartMs = 0;

    final ArrayList<File> files = new ArrayList<>();
    RecAdapter adapter;
    ListView list;
    TextView status, timer, hint;
    TextView recBtn;
    File playingFile;

    final SimpleDateFormat fmtName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
    final SimpleDateFormat fmtShow = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try {
            recordDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "录音");
            if (!recordDir.isDirectory()) recordDir.mkdirs();
            setContentView(build());
            refresh();
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("录音棚打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(20), dp(60), dp(20), dp(20));
            setContentView(tv);
        }
    }

    int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v); }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
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
        top.setPadding(dp(8), dp(10), dp(12), dp(6));
        ImageView back = Ic.view(this, "back", MainActivity.C_FG, 22);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(txt("录音棚", MainActivity.C_FG, 18, true));
        titleCol.addView(txt("录音 · 播放 · 转文字", MainActivity.C_SUB, 10.5f, false));
        top.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView setBtn = txt("转文字设置", MainActivity.C_ACC, 12, false);
        setBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        setBtn.setBackground(round(0x1A3FA9E8, 14));
        setBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAsrSettings(); }
        });
        top.addView(setBtn);
        root.addView(top);

        // 录音控制卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundColor(MainActivity.C_CARD);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams cdp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cdp.setMargins(dp(16), dp(8), dp(16), dp(8));
        root.addView(card, cdp);

        status = txt("准备就绪", MainActivity.C_SUB, 12, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status);

        timer = txt("00:00", MainActivity.C_ACC, 30, true);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, dp(6), 0, dp(6));
        card.addView(timer);

        recBtn = txt("● 开始录音", Color.WHITE, 15, true);
        recBtn.setGravity(Gravity.CENTER);
        recBtn.setPadding(dp(28), dp(12), dp(28), dp(12));
        GradientDrawable rbg = round(Theme.c(0xFFFB7299), 24);
        recBtn.setBackground(rbg);
        card.addView(recBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecord(); }
        });

        hint = txt("提示：转文字需先在右上角填入百度语音密钥（有免费额度）",
                MainActivity.C_SUB, 10.5f, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        // 列表标题
        TextView secT = txt("我的录音", MainActivity.C_FG, 13, true);
        secT.setPadding(dp(18), dp(10), dp(18), dp(4));
        root.addView(secT);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new RecAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                if (i >= 0 && i < files.size()) togglePlay(files.get(i));
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                if (i >= 0 && i < files.size()) showItemMenu(files.get(i));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    TextView txt(String s, int color, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ------------------------------------------------------------ 录音

    void toggleRecord() {
        if (recording) stopRecord(); else startRecord();
    }

    void startRecord() {
        if (!Compat.hasPerm(this, Manifest.permission.RECORD_AUDIO)) {
            Compat.requestPerm(this, Manifest.permission.RECORD_AUDIO, REQ_REC);
            Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            releaseRecorder();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(64000);
            currentFile = new File(recordDir, "录音_" + fmtName.format(new Date()) + ".m4a");
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordStartMs = System.currentTimeMillis();
            recBtn.setText("■ 停止录音");
            ((GradientDrawable) recBtn.getBackground()).setColor(0xFF666C78);
            status.setText("正在录音…（再点一次停止）");
            ui.post(tickRecord);
        } catch (Throwable e) {
            recording = false;
            status.setText("录音启动失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            Toast.makeText(this, "无法启动录音，可能被其他应用占用麦克风", Toast.LENGTH_SHORT).show();
        }
    }

    void stopRecord() {
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Throwable ignore) { }
        releaseRecorder();
        recording = false;
        ui.removeCallbacks(tickRecord);
        recBtn.setText("● 开始录音");
        try { ((GradientDrawable) recBtn.getBackground()).setColor(Theme.c(0xFFFB7299)); } catch (Throwable ignore) { }
        long dur = System.currentTimeMillis() - recordStartMs;
        if (currentFile != null && currentFile.isFile() && currentFile.length() > 0) {
            status.setText("已保存：" + currentFile.getName() + "（" + dur / 1000 + " 秒）");
            Toast.makeText(this, "录音已保存", Toast.LENGTH_SHORT).show();
            refresh();
        } else {
            status.setText("录音太短，未保存");
            if (currentFile != null) currentFile.delete();
        }
        timer.setText("00:00");
    }

    final Runnable tickRecord = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            int s = (int) ((System.currentTimeMillis() - recordStartMs) / 1000);
            timer.setText(String.format("%02d:%02d", s / 60, s % 60));
            ui.postDelayed(this, 500);
        }
    };

    void releaseRecorder() {
        try { if (recorder != null) recorder.release(); } catch (Throwable ignore) { }
        recorder = null;
    }

    public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        if (req == REQ_REC) {
            if (res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                startRecord();
            } else {
                Toast.makeText(this, "没有麦克风权限，无法录音", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------ 播放

    void togglePlay(File f) {
        if (playingFile != null && playingFile.equals(f) && player != null && player.isPlaying()) {
            player.pause();
            status.setText("已暂停");
            return;
        }
        try {
            releasePlayer();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            playingFile = f;
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    m.start();
                    status.setText("正在播放：" + playingFile.getName());
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { status.setText("播放完毕"); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int w, int e) {
                    status.setText("播放失败（格式不支持）");
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Throwable e) {
            status.setText("播放失败：" + e.getMessage());
        }
    }

    void releasePlayer() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Throwable ignore) { }
        player = null;
    }

    // ------------------------------------------------------------ 列表

    void refresh() {
        File[] arr = recordDir.listFiles();
        files.clear();
        if (arr != null) {
            for (File f : arr) {
                if (f.isFile() && f.getName().endsWith(".m4a")) files.add(f);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        adapter.notifyDataSetChanged();
        if (files.isEmpty()) status.setText("还没有录音，点上面按钮开始");
    }

    class RecAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout row = new LinearLayout(RecorderActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(11), dp(14), dp(11));
            GradientDrawable bg = round(MainActivity.C_CARD, 12);
            bg.setStroke(1, DARK_LINE());
            row.setBackground(bg);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(7);

            ImageView icon = Ic.view(RecorderActivity.this, "g_record", MainActivity.C_ACC, 20);
            row.addView(icon);

            LinearLayout col = new LinearLayout(RecorderActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = txt(files.get(i).getName(), MainActivity.C_FG, 13, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            long kb = files.get(i).length() / 1024;
            TextView sub = txt(fmtShow.format(new Date(files.get(i).lastModified()))
                    + " · " + kb + " KB · 长按更多", MainActivity.C_SUB, 10.5f, false);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    int DARK_LINE() { return MainActivity.DARK ? 0xFF2A2F38 : 0xFFE3E7EC; }

    // ------------------------------------------------------------ 菜单 / 转文字 / 设置

    void showItemMenu(final File f) {
        String[] items = {"播放", "转文字", "重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) togglePlay(f);
                        else if (which == 1) transcribe(f);
                        else if (which == 2) renameFile(f);
                        else if (which == 3) deleteFile(f);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void deleteFile(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除录音")
                .setMessage("确定删除「" + f.getName() + "」？此操作不可恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (playingFile != null && playingFile.equals(f)) releasePlayer();
                        f.delete();
                        refresh();
                        Toast.makeText(RecorderActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void renameFile(final File f) {
        final EditText et = new EditText(this);
        et.setText(f.getName());
        et.setSelection(f.getName().lastIndexOf('.'));
        et.setTextColor(MainActivity.C_FG);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty()) return;
                        if (!n.endsWith(".m4a")) n += ".m4a";
                        n = n.replace("/", "_").replace("\\", "_");
                        File dst = new File(recordDir, n);
                        if (dst.exists()) { Toast.makeText(RecorderActivity.this, "名字已存在", Toast.LENGTH_SHORT).show(); return; }
                        if (f.renameTo(dst)) { refresh(); Toast.makeText(RecorderActivity.this, "已重命名", Toast.LENGTH_SHORT).show(); }
                        else Toast.makeText(RecorderActivity.this, "重命名失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void showAsrSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView intro = txt("在线转文字使用百度语音开放平台（有免费额度）。\n"
                + "请到 ai.baidu.com 创建应用，拿到 API Key 和 Secret Key 后填入下方。"
                + "\n\n密钥仅保存在本机，App 不上传、不内置任何凭证。", MainActivity.C_SUB, 12, false);
        box.addView(intro);
        final EditText ak = new EditText(this);
        ak.setHint("API Key");
        ak.setText(Prefs.asrApiKey(this));
        ak.setTextColor(MainActivity.C_FG);
        final EditText sk = new EditText(this);
        sk.setHint("Secret Key");
        sk.setText(Prefs.asrSecretKey(this));
        sk.setTextColor(MainActivity.C_FG);
        box.addView(ak);
        box.addView(sk);
        new AlertDialog.Builder(this)
                .setTitle("转文字设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Prefs.setAsrKeys(RecorderActivity.this,
                                ak.getText().toString(), sk.getText().toString());
                        boolean ok = Prefs.hasAsrKeys(RecorderActivity.this);
                        hint.setText(ok ? "已配置密钥，长按录音条目即可「转文字」"
                                : "未配置密钥，转文字不可用");
                        Toast.makeText(RecorderActivity.this, ok ? "已保存密钥" : "已清空密钥", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void transcribe(final File f) {
        if (!Prefs.hasAsrKeys(this)) {
            Toast.makeText(this, "请先点右上角「转文字设置」填写密钥", Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("正在转文字，请稍候…");
        new Thread(new Runnable() {
            @Override public void run() {
                final AsrUtil.Result r = AsrUtil.recognize(f,
                        Prefs.asrApiKey(RecorderActivity.this),
                        Prefs.asrSecretKey(RecorderActivity.this));
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (r.error != null) {
                            status.setText("转文字失败");
                            new AlertDialog.Builder(RecorderActivity.this)
                                    .setTitle("转文字失败")
                                    .setMessage(r.error)
                                    .setPositiveButton("知道了", null)
                                    .show();
                        } else {
                            status.setText("转文字完成");
                            showResult(f, r.text);
                        }
                    }
                });
            }
        }).start();
    }

    void showResult(final File f, final String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));
        final EditText et = new EditText(this);
        et.setText(text);
        et.setTextColor(MainActivity.C_FG);
        et.setGravity(Gravity.TOP);
        et.setMinLines(6);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        TextView tip = txt("可直接编辑修改，或保存到同名 .txt 文件", MainActivity.C_SUB, 11, false);
        box.addView(tip);
        new AlertDialog.Builder(this)
                .setTitle("识别结果")
                .setView(box)
                .setPositiveButton("保存 TXT", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            File txt = new File(recordDir, stripExt(f.getName()) + ".txt");
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(txt);
                            fos.write(et.getText().toString().getBytes("UTF-8"));
                            fos.close();
                            Toast.makeText(RecorderActivity.this, "已保存：" + txt.getName(), Toast.LENGTH_SHORT).show();
                        } catch (Throwable e) {
                            Toast.makeText(RecorderActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setText(et.getText().toString());
                            Toast.makeText(RecorderActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignore) { }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    static String stripExt(String n) {
        int p = n.lastIndexOf('.');
        return p > 0 ? n.substring(0, p) : n;
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (recording) {
            try { if (recorder != null) recorder.stop(); } catch (Throwable ignore) { }
        }
        releaseRecorder();
        releasePlayer();
        super.onDestroy();
    }
}
