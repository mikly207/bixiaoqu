package com.bixiaoqu.app;

/**
 * 【v4.34.0】网络线程调度优先级。
 *
 * 为什么要单独做一个类：
 *
 * Android 的 ART **不理会 Java 层的 Thread.setPriority()**。
 * Java 规范里的 1~10 在 Android 上只是一个被记录下来的数字，调度器完全不看它；
 * 真正决定线程先后的是 Linux 的 nice 值 + cgroup 分组，
 * 只能通过 {@link android.os.Process#setThreadPriority(int)} 来改。
 *
 * 本项目原来在 16 个地方写了 `t.setPriority(Thread.NORM_PRIORITY + 3)` 之类，
 * 注释还写着「优先级保持 NORM+2 让起播取址优先拿到 CPU」——
 * **那些全是无效代码**。所谓「网络线程调度调不动」，根子就在这里：
 * 视频分段、封面下载、落盘、日志在调度器眼里完全平级，
 * 谁的代码碰巧先占住 CPU 谁就赢。
 *
 * 现在改成真正生效的进程级优先级，并坚持一个原则：**用相对关系表达诉求**，
 * 而不是把某个线程抬到离谱的高（那会反过来把 UI 线程挤掉，低端机更卡）。
 *
 *   视频数据（分段 / 转发 / 取址）→ FOREGROUND (-4)：比普通线程先跑
 *   封面（可见项）              → DEFAULT   (0) ：保持现状，不因调度改造变慢
 *   封面预取 / 落盘 / 预热      → BACKGROUND (10)：明确给视频让路
 *   日志 / 清理                 → LOWEST    (19)：永远不参与抢
 */
public class NetPrio {

    /** 播放主链路：视频分段下载、代理转发、线路竞速、播放地址取址 */
    public static final int VIDEO = android.os.Process.THREAD_PRIORITY_FOREGROUND;   // -4

    /** 封面等图片（可见项）：与现状持平，不因为这次改造而变慢 */
    public static final int IMAGE = android.os.Process.THREAD_PRIORITY_DEFAULT;      // 0

    /** 封面预取、落盘、首片预热、后台清理：明确让位给播放 */
    public static final int BACKGROUND = android.os.Process.THREAD_PRIORITY_BACKGROUND;  // 10

    /** 日志、心跳这类纯后台杂活 */
    public static final int IDLE = android.os.Process.THREAD_PRIORITY_LOWEST;        // 19

    private NetPrio() { }

    /**
     * 设置**当前线程**的 Android 优先级。
     * 必须在 Runnable 体里调用（ThreadFactory 里调用时线程还没跑起来，
     * setThreadPriority 作用于调用者自己，不是新建的线程）。
     */
    public static void apply(int prio) {
        try {
            android.os.Process.setThreadPriority(prio);
        } catch (Throwable ignore) {
            // 个别 ROM 禁止调整优先级时静默降级：功能不受影响，只是调度不优化
        }
    }

    /**
     * 包一层：让线程体一进来就带上指定优先级。
     *
     * 线程池会复用线程，每次 run 都会重新 apply 一次，
     * 所以不会出现「一个线程先跑高优先级任务、后来跑低优先级任务还留着高优先级」的问题。
     */
    public static Runnable wrap(final Runnable r, final int prio) {
        if (r == null) return null;
        return new Runnable() {
            @Override public void run() {
                apply(prio);
                r.run();
            }
        };
    }
}
