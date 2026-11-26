package com.bixiaoqu.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/**
 * 应用入口：在最早的 attachBaseContext 阶段安装崩溃黑匣子。
 * 目的：即使 Activity 创建失败（主题/资源/类加载异常），也能在
 * Android/data/com.bixiaoqu.app/files/crash.txt 留下完整堆栈。
 */
public class BxqApp extends Application {

    public static final String VERSION = "v4.23.0";

    /** WebView 是否可用（部分精简 ROM 移除了 Android System WebView，构造即抛异常） */
    public static volatile boolean webViewOk = true;

    @Override
    protected void attachBaseContext(Context base) {
        // 【v4.14.2】全局界面缩放：在任何界面创建前锁定系统密度基准并按设置应用倍率。
        // 放在 attachBaseContext 最前面，保证后续 getResources() 拿到的都是缩放后的 density。
        try { DensityUtil.apply(base); } catch (Throwable ignore) { }
        // 【v3.9 播放提速修复】强制走 IPv4 栈。
        // 现象：5G/WiFi 下 DNS 优先返回 IPv6(2409:8c20::/32 等)，而 B 站 CDN 的 IPv6
        // 线路在不少运营商网络里不通，Java 的 HttpURLConnection 没有 Happy Eyeballs
        // 快速回退机制，会一直等到 connectTimeout(15s) 才失败 —— 这就是「打开很慢、
        // 有概率打不开」的根因。设为 true 后 InetAddress 只返回 A 记录（IPv4），
        // 从源头绕开 IPv6，连接通常几百毫秒内建立。必须尽早设置，在网络栈初始化前生效。
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
            // 播放机频繁解析同一批 CDN 域名，缩短缓存失效带来的重复解析开销
            java.security.Security.setProperty("networkaddress.cache.ttl", "60");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "5");
        } catch (Throwable ignore) { }
        super.attachBaseContext(base);
        try {
            CrashHandler.install(base.getExternalFilesDir(null), base.getFilesDir());
            String abi = "unknown";
            try { abi = android.os.Build.SUPPORTED_ABIS[0]; } catch (Throwable ignore) { }
            CrashHandler.note("boot", "attachBaseContext OK  sdk="
                    + android.os.Build.VERSION.SDK_INT + "  abi=" + abi
                    + "  model=" + android.os.Build.MODEL
                    + "  ipv4Stack=" + System.getProperty("java.net.preferIPv4Stack"));
        } catch (Throwable ignore) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 【v4.15.15】预热一次 WebView：可用的设备上这步无感；
        // 不可用的设备提前把开关置 false，扫码页就不会被拉起来（避免一切闪退可能）。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                // 放到启动 3 秒后，不拖慢冷启动；失败只置开关，不影响其它功能
                try {
                    android.webkit.WebView probe = new android.webkit.WebView(BxqApp.this);
                    probe.destroy();
                    webViewOk = true;
                } catch (Throwable t) {
                    webViewOk = false;
                    try { CrashHandler.note("webview", "unavailable: " + t); } catch (Throwable ignore) { }
                }
            }
        }, 3000);
        // 【v4.14.2】每个 Activity 创建前再应用一次密度：
        // 部分机型会在 configuration 变化时重置 density，这里兜底，确保全局一致。
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                try { DensityUtil.apply(a); } catch (Throwable ignore) { }
            }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        // 设置变更后的重启也会走这里，统一再应用一次
        try { DensityUtil.apply(this); } catch (Throwable ignore) { }
        try { ImgLoader.init(getCacheDir()); } catch (Throwable ignore) { }
        // 【v4.22.0】播放分片磁盘缓存：看过的视频二次播放直接本地出流
        try { DiskCache.init(getCacheDir()); } catch (Throwable ignore) { }
        CrashHandler.note("boot", "Application.onCreate OK");
        // 【v4.13.6 起播提速】后台预热设备指纹 + WBI mixinKey。
        // 这两个是 playurl(WBI 签名) 的前置网络往返，如果不预热，首次点视频时
        // 必须串行等「首页 Cookie → finger/spi → nav」三步走完才能真正请求播放地址，
        // 在 5G 下这就是「点开要等好几秒」的一大来源。放到启动后台线程提前做完，
        // 用户点播放时直接命中进程内缓存。
        new Thread(new Runnable() { @Override public void run() {
            try { BiliApi.initFinger(); } catch (Throwable ignore) { }
            try { BiliApi.navMixinKey(); } catch (Throwable ignore) { }
        } }, "bxq-warmup") {{
            setPriority(Thread.NORM_PRIORITY - 1);   // 后台预热，不抢 UI/播放线程资源
        }}.start();
    }
}
