package com.bixiaoqu.app;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/**
 * 崩溃黑匣子：任何未捕获异常都会把完整堆栈写入
 * Android/data/com.bixiaoqu.app/files/crash.txt
 * 下次启动可在「我的」页点「查看崩溃日志」直接查看。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler prev;
    private final File dir;

    /** 当前日志目录（供 note 使用） */
    private static volatile File curDir;

    private CrashHandler(Thread.UncaughtExceptionHandler prev, File dir) {
        this.prev = prev;
        this.dir = dir;
    }

    public static void install(File externalDir, File internalDir) {
        File dir = externalDir != null ? externalDir : internalDir;
        curDir = dir;
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(prev, dir));
    }

    /** 启动心跳：记录到 boot.txt，用于区分「没启动」与「启动后崩溃」 */
    public static void note(String tag, String msg) {
        File dir = curDir;
        if (dir == null) return;
        try {
            PrintWriter pw = new PrintWriter(
                    new FileWriter(new File(dir, "boot.txt"), true));
            pw.println("[" + tag + "] " + new Date() + " " + msg);
            pw.close();
        } catch (Throwable ignore) { }
        try { android.util.Log.i("BiliXQ", tag + ": " + msg); } catch (Throwable ignore) { }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            if (dir != null) {
                PrintWriter pw = new PrintWriter(
                        new FileWriter(new File(dir, "crash.txt"), true));
                pw.println("=== " + new Date() + " thread=" + t.getName());
                pw.println(android.util.Log.getStackTraceString(e));
                pw.println();
                pw.close();
            }
        } catch (Exception ignore) { }

        boolean isMain;
        try {
            isMain = android.os.Looper.getMainLooper().getThread() == t;
        } catch (Throwable x) {
            isMain = true;
        }
        if (isMain) {
            // 主线程：交给系统处理（进程退出），但日志已落盘
            if (prev != null) prev.uncaughtException(t, e);
        } else {
            // 后台线程：只记录，不杀进程，避免「下载/搜索出错 → 整个 App 闪退」
            android.util.Log.e("BiliXQ", "后台线程异常已捕获（进程继续）", e);
        }
    }

    public static String readCrashLog(File externalDir, File internalDir) {
        File dir = externalDir != null ? externalDir : internalDir;
        if (dir == null) return "（目录不可用）";
        File f = new File(dir, "crash.txt");
        if (!f.exists()) return "（暂无崩溃记录）";
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 120) {
                sb.append(line).append('\n');
                lines++;
            }
            br.close();
            return sb.length() == 0 ? "（暂无崩溃记录）" : sb.toString();
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }
}
