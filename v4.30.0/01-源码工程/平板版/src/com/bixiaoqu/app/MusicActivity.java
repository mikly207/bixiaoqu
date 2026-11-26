package com.bixiaoqu.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 【v4.9.6】留声机：底栏「消息」位替换而来。
 *
 * 三件事：
 *   1. 扫描并离线播放本机音乐（MediaStore，完全不走网络）；
 *   2. 唱片式播放器（圆形专辑图 + 旋转动画 + 进度条 + 上/下一首）；
 *   3. 一键唤起 QQ 音乐（已安装则直接打开 App，否则开网页版）。
 *
 * 【为什么 QQ 音乐只能「唤起」】
 * QQ 音乐没有对第三方开放的音乐接口，曲库也受版权保护，
 * 任何绕过登录/加密签名的抓取都属于违规。所以这里做的是
 * 「把你送到 QQ 音乐去听」，而不是把它的曲库搬进来。
 */
public class MusicActivity extends Activity {


    static final int REQ_AUDIO = 51;

    static class Track {
        long id;
        long albumId;
        String title = "";
        String artist = "";
        long durMs;
        Uri uri;
        Uri art;
    }

    final ArrayList<Track> tracks = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());

    MediaPlayer mp;
    int curIndex = -1;
    boolean preparing = false;
    boolean playWhenReady = false;

    LinearLayout playerCard;
    ImageView disc;
    TextView songTitle, songArtist, timeNow, timeAll, emptyTip;
    boolean seekTouching = false;
    SeekBar seek;
    ImageView playBtn;
    ListView list;
    Adapter adapter;

    android.animation.ObjectAnimator spin;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try {
            setContentView(build());
            ensurePermission();
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("留声机打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(20), dp(60), dp(20), dp(20));
            setContentView(tv);
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    // ------------------------------------------------------------ UI

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // ---- 顶栏 ----
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
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = dp(4);
        top.addView(titleCol, tcp);

        TextView t = new TextView(this);
        t.setText("留声机");
        t.setTextColor(MainActivity.C_FG);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(t);

        TextView st = new TextView(this);
        st.setText("本地音乐 · 离线也能听");
        st.setTextColor(MainActivity.C_SUB);
        st.setTextSize(10.5f);
        st.setPadding(0, dp(2), 0, 0);
        titleCol.addView(st);

        // 【v4.14.0】录音棚入口
        TextView rec = new TextView(this);
        rec.setText("录音棚");
        rec.setTextColor(MainActivity.C_ACC);
        rec.setTextSize(12);
        rec.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0x1A3FA9E8);
        rbg.setCornerRadius(dp(14));
        rec.setBackground(rbg);
        rec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(MusicActivity.this, RecorderActivity.class)); }
                catch (Throwable e) { Toast.makeText(MusicActivity.this, "打开录音棚失败", Toast.LENGTH_SHORT).show(); }
            }
        });
        top.addView(rec);

        TextView qq = new TextView(this);
        qq.setText("QQ音乐");
        qq.setTextColor(MainActivity.C_ACC);
        qq.setTextSize(12);
        qq.setPadding(dp(12), dp(7), dp(4), dp(7));
        GradientDrawable qbg = new GradientDrawable();
        qbg.setColor(0x1AFB7299);
        qbg.setCornerRadius(dp(14));
        qq.setBackground(qbg);
        qq.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openQQMusic(); }
        });
        top.addView(qq);
        root.addView(top);

        // ---- 播放器卡片 ----
        playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setGravity(Gravity.CENTER_HORIZONTAL);
        playerCard.setPadding(dp(60), dp(6), dp(60), dp(2));

        // 唱片：圆形容器 + 旋转
        FrameLayout discWrap = new FrameLayout(this);
        int ds = dp(126);
        LinearLayout.LayoutParams dwp = new LinearLayout.LayoutParams(ds, ds);
        playerCard.addView(discWrap, dwp);

        // 外圈黑胶
        View vinyl = new View(this);
        GradientDrawable vb = new GradientDrawable();
        vb.setShape(GradientDrawable.OVAL);
        vb.setColor(0xFF0E1014);
        vb.setStroke(dp(1), 0xFF2A2E36);
        vinyl.setBackground(vb);
        discWrap.addView(vinyl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        disc = new ImageView(this);
        disc.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable dph = new GradientDrawable();
        dph.setShape(GradientDrawable.OVAL);
        dph.setColor(0xFF1E2229);
        disc.setBackground(dph);
        disc.setClipToOutline(true);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ds - dp(34), ds - dp(34), Gravity.CENTER);
        discWrap.addView(disc, dlp);

        // 中心轴
        View axis = new View(this);
        GradientDrawable ab = new GradientDrawable();
        ab.setShape(GradientDrawable.OVAL);
        ab.setColor(MainActivity.C_ACC);
        axis.setBackground(ab);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER);
        discWrap.addView(axis, alp);

        spin = android.animation.ObjectAnimator.ofFloat(disc, "rotation", 0f, 360f);
        spin.setDuration(9000);
        spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        spin.setInterpolator(new android.view.animation.LinearInterpolator());

        songTitle = new TextView(this);
        songTitle.setText("还没有在播放");
        songTitle.setTextColor(MainActivity.C_FG);
        songTitle.setTextSize(14.5f);
        songTitle.setTypeface(Typeface.DEFAULT_BOLD);
        songTitle.setSingleLine(true);
        songTitle.setEllipsize(TextUtils.TruncateAt.END);
        songTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(12);
        playerCard.addView(songTitle, stp);

        songArtist = new TextView(this);
        songArtist.setText("从下面挑一首开始");
        songArtist.setTextColor(MainActivity.C_SUB);
        songArtist.setTextSize(11);
        songArtist.setSingleLine(true);
        songArtist.setGravity(Gravity.CENTER);
        playerCard.addView(songArtist);

        // 进度
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(6);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgress(0);
        seek.setSplitTrack(false);
        seek.setPadding(0, 0, 0, 0);
        seekRow.addView(seek, new LinearLayout.LayoutParams(
                0, dp(22), 1f));

        timeNow = new TextView(this);
        timeNow.setText("00:00");
        timeNow.setTextColor(MainActivity.C_SUB);
        timeNow.setTextSize(10);
        timeNow.setPadding(dp(6), 0, 0, 0);
        seekRow.addView(timeNow);

        timeAll = new TextView(this);
        timeAll.setText("/ 00:00");
        timeAll.setTextColor(MainActivity.C_SUB);
        timeAll.setTextSize(10);
        timeAll.setPadding(dp(3), 0, 0, 0);
        seekRow.addView(timeAll);
        playerCard.addView(seekRow, srp);

        // 控制
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(0, dp(2), 0, 0);

        ImageView prev = new ImageView(this);
        prev.setImageDrawable(prevNextIcon(true));
        prev.setPadding(dp(10), dp(10), dp(10), dp(10));
        ctrl.addView(prev, new LinearLayout.LayoutParams(dp(46), dp(46)));
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(-1); }
        });

        playBtn = new ImageView(this);
        playBtn.setImageDrawable(playPauseIcon(false));
        playBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(0xFFFB7299);
        playBtn.setBackground(pb);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(52), dp(52));
        pbp.leftMargin = dp(20);
        pbp.rightMargin = dp(20);
        ctrl.addView(playBtn, pbp);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });

        ImageView next = new ImageView(this);
        next.setImageDrawable(prevNextIcon(false));
        next.setPadding(dp(10), dp(10), dp(10), dp(10));
        ctrl.addView(next, new LinearLayout.LayoutParams(dp(46), dp(46)));
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(1); }
        });

        playerCard.addView(ctrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(playerCard);

        // ---- 分隔 + 小标题 ----
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        sec.setPadding(dp(16), dp(8), dp(16), dp(4));

        TextView secT = new TextView(this);
        secT.setText("本机音乐");
        secT.setTextColor(MainActivity.C_FG);
        secT.setTextSize(13);
        secT.setTypeface(Typeface.DEFAULT_BOLD);
        sec.addView(secT);

        emptyTip = new TextView(this);
        emptyTip.setText("");
        emptyTip.setTextColor(MainActivity.C_SUB);
        emptyTip.setTextSize(10.5f);
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etp.leftMargin = dp(8);
        sec.addView(emptyTip, etp);

        TextView rescan = new TextView(this);
        rescan.setText("重新扫描");
        rescan.setTextColor(MainActivity.C_SUB);
        rescan.setTextSize(10.5f);
        rescan.setPadding(dp(8), dp(4), dp(4), dp(4));
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ensurePermission(); }
        });
        sec.addView(rescan);
        root.addView(sec);

        // ---- 列表 ----
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(12), 0, dp(12), dp(12));
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                if (i >= 0 && i < tracks.size()) playAt(i);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean dragging = false;
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mp != null) {
                    try {
                        timeNow.setText(mmss((int) ((long) progress * mp.getDuration() / 1000)));
                    } catch (Throwable ignore) { }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (preparing) return;
                if (mp != null) {
                    try { mp.seekTo((int) ((long) sb.getProgress() * mp.getDuration() / 1000)); }
                    catch (Throwable ignore) { }
                }
            }
        });

        return root;
    }

    android.graphics.drawable.Drawable playPauseIcon(boolean playing) {
        // 直接用系统媒体图标（播放三角 / 暂停双竖条），再染成白色
        android.graphics.drawable.Drawable d =
                getResources().getDrawable(playing ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play, getTheme());
        try { d.mutate().setTint(Color.WHITE); } catch (Throwable ignore) { }
        return d;
    }

    /** 上/下一首：用系统媒体图标镜像 + 着色（避免额外资源） */
    android.graphics.drawable.Drawable prevNextIcon(boolean prev) {
        android.graphics.drawable.Drawable d = getResources().getDrawable(
                prev ? android.R.drawable.ic_media_previous : android.R.drawable.ic_media_next,
                getTheme());
        try { d.mutate().setTint(MainActivity.C_FG); } catch (Throwable ignore) { }
        return d;
    }

    // ------------------------------------------------------------ 权限 / 扫描

    void ensurePermission() {
        // 【v4.11.0】READ_MEDIA_AUDIO 常量是 API33 才有的符号，低版本编译不可见，改用字符串；
        // 权限检查/请求统一走 Compat（API<23 安装时已授予）
        String perm = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Compat.hasPerm(this, perm)) {
            scan();
            return;
        }
        Compat.requestPerm(this, perm, REQ_AUDIO);
    }

    // 【v4.11.0】onRequestPermissionsResult 是 API 23+ 才有的 Activity 方法：
    // 去掉 @Override/super（低版本基类没有此方法，定义同名方法即可正常收到回调）
    public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        if (req == REQ_AUDIO) {
            if (res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                scan();
            } else {
                emptyTip.setText("没有读取音频的权限");
                tipDialog();
            }
        }
    }

    void tipDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("需要读取音频权限")
                .setMessage("留声机要扫描本机音乐，必须拿到「音乐和音频」读取权限。\n\n"
                        + "如果系统不再弹授权框，请到 设置 → 应用 → 哔小趣 → 权限 里手动打开。")
                .setPositiveButton("知道了", null)
                .show();
    }

    void scan() {
        emptyTip.setText("扫描中…");
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<Track> got = new ArrayList<>();
            Cursor c = null;
            try {
                Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] proj = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.ALBUM_ID
                };
                String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0 AND "
                        + MediaStore.Audio.Media.DURATION + ">30000";
                c = getContentResolver().query(base, proj, sel, null,
                        MediaStore.Audio.Media.TITLE + " ASC");
                if (c != null) {
                    while (c.moveToNext() && got.size() < 2000) {
                        Track t = new Track();
                        t.id = c.getLong(0);
                        t.title = c.getString(1);
                        t.artist = c.getString(2);
                        t.durMs = c.getLong(3);
                        t.albumId = c.getLong(4);
                        t.uri = ContentUris.withAppendedId(base, t.id);
                        if (t.albumId > 0) {
                            t.art = ContentUris.withAppendedId(
                                    Uri.parse("content://media/external/audio/albumart"), t.albumId);
                        }
                        if (t.title == null || t.title.trim().isEmpty()) t.title = "未知曲目";
                        if (t.artist == null || t.artist.trim().isEmpty()
                                || "<unknown>".equalsIgnoreCase(t.artist)) t.artist = "未知艺术家";
                        got.add(t);
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignore) { }
            }
            ui.post(new Runnable() { @Override public void run() {
                tracks.clear();
                tracks.addAll(got);
                adapter.notifyDataSetChanged();
                emptyTip.setText(tracks.isEmpty()
                        ? "没找到音乐（先用文件管理器放几首进来）"
                        : "共 " + tracks.size() + " 首");
            } });
        } }).start();
    }

    // ------------------------------------------------------------ 播放

    void playAt(int idx) {
        if (idx < 0 || idx >= tracks.size()) return;
        curIndex = idx;
        Track t = tracks.get(idx);
        songTitle.setText(t.title);
        songArtist.setText(t.artist);
        seek.setProgress(0);
        timeNow.setText("00:00");
        loadDisc(t);

        preparing = true;
        playWhenReady = true;
        releasePlayer();
        try {
            mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            }
            mp.setDataSource(this, t.uri);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    preparing = false;
                    if (mp != m) return;
                    timeAll.setText(mmss(m.getDuration()));
                    playBtn.setImageDrawable(playPauseIcon(true));
                    if (playWhenReady) {
                        m.start();
                        startSpin();
                    }
                    ui.post(ticker);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { step(1); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    preparing = false;
                    songArtist.setText("这首打不开（格式不支持或文件已移动）");
                    playBtn.setImageDrawable(playPauseIcon(false));
                    stopSpin();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable e) {
            preparing = false;
            songArtist.setText("播放失败：" + e.getMessage());
        }
    }

    void loadDisc(Track t) {
        if (t.art != null) {
            try { disc.setImageURI(t.art); } catch (Throwable ignore) { }
        } else {
            disc.setImageDrawable(null);
            GradientDrawable dph = new GradientDrawable();
            dph.setShape(GradientDrawable.OVAL);
            dph.setColor(0xFF1E2229);
            disc.setBackground(dph);
        }
    }

    void toggle() {
        if (mp == null) {
            if (!tracks.isEmpty()) playAt(curIndex >= 0 ? curIndex : 0);
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                playWhenReady = false;
                playBtn.setImageDrawable(playPauseIcon(false));
                stopSpin();
            } else {
                mp.start();
                playWhenReady = true;
                playBtn.setImageDrawable(playPauseIcon(true));
                startSpin();
                ui.post(ticker);
            }
        } catch (Throwable ignore) { }
    }

    void step(int d) {
        if (tracks.isEmpty()) return;
        int n = tracks.size();
        int idx = curIndex < 0 ? 0 : (curIndex + d + n) % n;
        playAt(idx);
    }

    void startSpin() {
        try {
            if (spin != null && !spin.isStarted()) spin.start();
            else if (spin != null && spin.isPaused()) spin.resume();
        } catch (Throwable ignore) { }
    }

    void stopSpin() {
        try { if (spin != null) spin.pause(); } catch (Throwable ignore) { }
    }

    final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mp == null) return;
            try {
                if (mp.isPlaying()) {
                    int dur = mp.getDuration();
                    int pos = mp.getCurrentPosition();
                    if (dur > 0) seek.setProgress((int) ((long) pos * 1000 / dur));
                    timeNow.setText(mmss(pos));
                }
            } catch (Throwable ignore) { }
            ui.postDelayed(this, 500);
        }
    };

    void releasePlayer() {
        ui.removeCallbacks(ticker);
        try {
            if (mp != null) { mp.stop(); mp.release(); }
        } catch (Throwable ignore) { }
        mp = null;
        stopSpin();
    }

    static String mmss(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Throwable ignore) { }
        mp = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ QQ 音乐

    /**
     * 唤起 QQ 音乐。
     * 优先直接拉起 App（qqmusic:// 或包名），失败则打开网页版搜索页。
     * 说明见类注释：曲库有版权，客户端不做任何爬取/破解。
     */
    void openQQMusic() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.qqmusic");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Throwable ignore) { }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("qqmusic://"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignore) { }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://y.qq.com/"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "没有找到 QQ 音乐，也没法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ 列表

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int i) { return tracks.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Track t = tracks.get(pos);
            boolean playing = pos == curIndex;

            LinearLayout row = new LinearLayout(MusicActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(playing ? 0x1AFB7299 : 0x00000000);
            bg.setCornerRadius(dp(12));
            row.setBackground(bg);

            TextView no = new TextView(MusicActivity.this);
            no.setText(String.valueOf(pos + 1));
            no.setTextColor(playing ? MainActivity.C_ACC : MainActivity.C_SUB);
            no.setTextSize(11);
            no.setGravity(Gravity.CENTER);
            no.setWidth(dp(26));
            row.addView(no);

            LinearLayout col = new LinearLayout(MusicActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = dp(8);
            row.addView(col, cp);

            TextView title = new TextView(MusicActivity.this);
            title.setText(t.title);
            title.setTextColor(playing ? MainActivity.C_ACC : MainActivity.C_FG);
            title.setTextSize(13);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title);

            TextView sub = new TextView(MusicActivity.this);
            sub.setText(t.artist);
            sub.setTextColor(MainActivity.C_SUB);
            sub.setTextSize(10.5f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub);

            TextView dur = new TextView(MusicActivity.this);
            dur.setText(mmss((int) t.durMs));
            dur.setTextColor(MainActivity.C_SUB);
            dur.setTextSize(10);
            dur.setPadding(dp(8), 0, dp(2), 0);
            row.addView(dur);

            return row;
        }
    }
}
