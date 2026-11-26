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

    /**
     * 启动心跳：记录到 boot.txt，用于区分「没启动」与「启动后崩溃」。
     *
     * 【v4.23.0】改成「入队 + 单线程批量落盘」。
     * 旧版每次 note 都在调用线程上 open/write/close 一次文件：冷启动时 boot 日志、
     * 起播打点、布局打点加起来十几次主线程 I/O，每次 1~5ms，全压在首帧前面。
     * 现在主线程只做一个 offer()，真正的写盘在后台线程按 200ms 批量合并。
     * 崩溃时（uncaughtException）会先把队列刷干净再写 crash.txt，日志不会丢。
     */
    public static void note(String tag, String msg) {
        File dir = curDir;
        if (dir == null) return;
        try { android.util.Log.i("BiliXQ", tag + ": " + msg); } catch (Throwable ignore) { }
        try {
            ensureWriter();
            if (pending != null) {
                pending.offer("[" + tag + "] " + new Date() + " " + msg);
                return;
            }
        } catch (Throwable ignore) { }
        writeNow(dir, "[" + tag + "] " + new Date() + " " + msg);
    }

    static java.util.concurrent.LinkedBlockingQueue<String> pending;
    static volatile boolean writerStarted;

    private static synchronized void ensureWriter() {
        if (writerStarted) return;
        writerStarted = true;
        pending = new java.util.concurrent.LinkedBlockingQueue<String>(512);
        Thread t = new Thread(NetPrio.wrap(new Runnable() { @Override public void run() {
            StringBuilder sb = new StringBuilder();
            while (true) {
                try {
                    String first = pending.take();
                    sb.setLength(0);
                    sb.append(first).append('\n');
                    // 200ms 内陆续到的日志合并成一次写，批量落盘
                    Thread.sleep(200);
                    String more;
                    int n = 0;
                    while (n++ < 64 && (more = pending.poll()) != null) sb.append(more).append('\n');
                    flush(sb.toString());
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable ignore) { }
            }
        } }, NetPrio.IDLE), "bxq-log");
        t.setDaemon(true);
        t.start();
    }

    /** 把队列里还没落盘的日志一次性写掉（崩溃前调用） */
    static void flushPending() {
        try {
            if (pending == null) return;
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = pending.poll()) != null) sb.append(s).append('\n');
            if (sb.length() > 0) flush(sb.toString());
        } catch (Throwable ignore) { }
    }

    private static void flush(String text) {
        File dir = curDir;
        if (dir == null || text == null || text.isEmpty()) return;
        writeNow(dir, text);
    }

    private static void writeNow(File dir, String text) {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, "boot.txt"), true));
            pw.print(text);
            if (!text.endsWith("\n")) pw.println();
            pw.close();
        } catch (Throwable ignore) { }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        flushPending();   // 【v4.23.0】先把还在队列里的心跳日志落盘，再写崩溃栈
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
