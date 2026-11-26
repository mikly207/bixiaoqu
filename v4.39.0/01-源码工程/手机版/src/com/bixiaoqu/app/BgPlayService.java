package com.bixiaoqu.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 【v4.32.0】后台播放保活服务。
 *
 * 为什么需要它：
 *   「后台播放」真正的难点不是让 MediaPlayer 继续跑（它本来就不会因为 Activity
 *   不可见而停），而是**进程别被回收**。App 退到后台几分钟、或者锁屏放一会儿，
 *   系统就会按 LRU 把普通后台进程清掉，声音随之消失 —— 用户感知就是"后台播放
 *   没生效"。挂一个 mediaPlayback 类型的前台服务，进程优先级从"可随时回收"
 *   升到"用户可见"，这是唯一可靠的做法。
 *
 * 它不持有播放器：
 *   播放器仍在 PlayActivity 里（Surface 也在那边），服务只做两件事 ——
 *   顶一条常驻通知（点击回播放页、点「停止」暂停播放），以及把进程留住。
 *   服务与 Activity 同进程，所以「停止」直接走静态回调，不用广播、不用跨进程。
 *
 * 兼容性：
 *   需在 API 22 的 bootclasspath 下编译，而 NotificationChannel / 双参 Builder /
 *   FLAG_IMMUTABLE 都是 API 26/23 才有的符号，这里一律走反射或字面量常量。
 */
public class BgPlayService extends Service {

    /** 「通知栏点停止」→ 回到 Activity 主线程执行暂停（同进程静态回调，零 IPC） */
    public interface BgCmd {
        void onBgStop();
    }

    static BgCmd cmdSink;

    static final String CH_ID = "bxq_bg_play";
    static final int NOTI_ID = 9001;
    static final String ACT_STOP = "com.bixiaoqu.app.BG_PLAY_STOP";

    /** FLAG_IMMUTABLE / FLAG_UPDATE_CURRENT（API 23 常量，避免编译期依赖） */
    static final int PI_IMMUTABLE = 0x04000000;
    static final int PI_UPDATE = 0x08000000;

    public static void start(Context c, String title, String bvid) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, BgPlayService.class);
            i.putExtra("title", title);
            i.putExtra("bvid", bvid);
            if (Build.VERSION.SDK_INT >= 26) {
                // startForegroundService 是 API 26 方法，android-22 的 bootclasspath 里没有，
                // 走反射调用（运行时存在即可用）
                Context.class.getMethod("startForegroundService", Intent.class)
                        .invoke(c, i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignore) {
            // Android 12+ 后台启动受限时这里会抛；播放本身不受影响（只是活得短一点）
        }
    }

    public static void stop(Context c) {
        if (c == null) return;
        try { c.stopService(new Intent(c, BgPlayService.class)); } catch (Throwable ignore) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 「停止」是从通知栏发来的同一个 Service Intent
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            final BgCmd sink = cmdSink;
            if (sink != null) {
                // onStartCommand 在主线程，但为免阻塞这里仍旧 post 一下
                new android.os.Handler(getMainLooper()).post(new Runnable() {
                    @Override public void run() { sink.onBgStop(); }
                });
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent == null ? "" : intent.getStringExtra("title");
        String bvid = intent == null ? "" : intent.getStringExtra("bvid");
        try {
            startForeground(NOTI_ID, build(title, bvid));
        } catch (Throwable e) {
            // 通知建不起来（极端机型 / 渠道被禁）时绝不能留一个"没有前台通知的服务" ——
            // 那会在几秒后被系统判成 FGS 超时直接崩进程。宁可不保活，也要保住播放。
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** 从最近任务里划掉 App：声音必须跟着停，否则就成了关不掉的后台播放 */
    @Override public void onTaskRemoved(Intent root) {
        final BgCmd sink = cmdSink;
        if (sink != null) {
            new android.os.Handler(getMainLooper()).post(new Runnable() {
                @Override public void run() { sink.onBgStop(); }
            });
        }
        stopSelf();
        super.onTaskRemoved(root);
    }

    @Override public void onDestroy() {
        try { stopForeground(true); } catch (Throwable ignore) { }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 通知

    Notification build(String title, String bvid) {
        ensureChannel();
        Notification.Builder b;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Object o = Class.forName("android.app.Notification$Builder")
                        .getConstructor(Context.class, String.class)
                        .newInstance(this, CH_ID);
                b = (Notification.Builder) o;
            } else {
                b = new Notification.Builder(this);
            }
        } catch (Throwable e) {
            b = new Notification.Builder(this);   // 兜底：26 以下路径
        }

        Intent open = new Intent(this, PlayActivity.class);
        open.putExtra("bvid", bvid);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        Intent stop = new Intent(this, BgPlayService.class);
        stop.setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        String t = (title == null || title.trim().isEmpty()) ? "哔小趣" : title.trim();
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t)
                .setContentText("正在后台播放 · 点击回到播放页")
                .setContentIntent(po)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", ps);
        return b.build();
    }

    /** API 26+ 必须有渠道，否则前台通知无效（startForeground 会抛） */
    void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Object nm = getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Class<?> chCls = Class.forName("android.app.NotificationChannel");
            Object ch = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CH_ID, "后台播放", 2);   // IMPORTANCE_LOW
            try { chCls.getMethod("setShowBadge", boolean.class).invoke(ch, false); }
            catch (Throwable ignore) { }
            try { chCls.getMethod("setSound", android.net.Uri.class,
                    android.media.AudioAttributes.class).invoke(ch, null, null); }
            catch (Throwable ignore) { }
            nm.getClass().getMethod("createNotificationChannel", chCls).invoke(nm, ch);
        } catch (Throwable ignore) { }
    }
}
