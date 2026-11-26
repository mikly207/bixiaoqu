package com.bixiaoqu.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 轻量封面图加载器（不引入任何图片库，保持 APK 体积）：
 * · 内存 LRU + 磁盘缓存（cacheDir/img）
 * · 后台线程池下载，二次采样解码到目标尺寸附近
 * · 带 B 站图床要求的 Referer，避免 403
 *
 * ────────────────────────────────────────────────────────────────────────
 * 【v4.31.0 三个模块（首页 / 动态 / 搜索）的封面提速】
 *
 *   ① 连接不再主动断开。
 *      旧版每张封面下载完都 `disconnect()`，等于把刚握好手的 TLS 连接直接扔掉；
 *      下一张同域名的封面又要重新 DNS + TCP + TLS（移动网络下 80~250ms）。
 *      一屏 12 张封面 = 白扔 12 次握手。现在读完就地 close 输入流、连接退回池子，
 *      同域名后续封面直接复用 —— 这是本轮最大的一笔提速。
 *
 *   ② 下载与磁盘写入解耦。
 *      旧版「下载 → 写临时文件 → rename → 再从文件解码」全在同一条线程，
 *      闪存抖动的那几毫秒正好卡住后面排队的封面。现在：内存里直接解码，
 *      落盘交给单线程低优先级队列，网络线程读完立刻去取下一张。
 *
 *   ③ 可见与预取彻底分流 + 并发放宽。
 *      可见 6 条、预取 3 条：手机双列一屏 8~10 张，4 条会让第 5 张开始排队。
 *
 *   ④ 磁盘封面缓存加容量上限（120MB）+ 后台 LRU 清理。
 *      旧版只增不减，用久了单是封面就能吃掉几百 MB。
 */
public class ImgLoader {

    /**
     * 封面内存缓存：按堆自适应，heap/5，封顶 48MB、保底 16MB。
     * sizeOf 用 getByteCount，LruCache 按真实字节淘汰。
     */
    static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(memCapBytes()) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b == null ? 0 : b.getByteCount();
        }
    };

    private static int memCapBytes() {
        try {
            long heap = Runtime.getRuntime().maxMemory();
            long cap = heap / 5;
            if (cap > 48L * 1024 * 1024) cap = 48L * 1024 * 1024;
            if (cap < 16L * 1024 * 1024) cap = 16L * 1024 * 1024;
            return (int) cap;
        } catch (Throwable t) {
            return 32 * 1024 * 1024;
        }
    }

    /** 可见封面专用线程池（6 条，NORM+2） */
    static final ExecutorService pool = Executors.newFixedThreadPool(6, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(NetPrio.wrap(r, NetPrio.IMAGE), "bxq-img");
            t.setDaemon(true);
            // 【v4.34.0】可见封面维持 DEFAULT(0)：与调度改造前一致，不让用户感到变慢，
            // 但仍排在视频(FOREGROUND -4)之后、预取(BACKGROUND 10)之前。
            return t;
        }
    });

    /** 预取专用线程池（3 条，低优先级，绝不挤占可见项） */
    static final ExecutorService prePool = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(NetPrio.wrap(r, NetPrio.BACKGROUND), "bxq-img-pre");
            t.setDaemon(true);
            // 【v4.34.0】封面预取明确让位给播放（原来写的 MIN_PRIORITY 在 ART 上无效）
            return t;
        }
    });

    /** 落盘线程（单条，低优先级）：写文件 / rename / 容量清理都在这里 */
    static final ExecutorService diskPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(NetPrio.wrap(r, NetPrio.BACKGROUND), "bxq-img-disk");
            t.setDaemon(true);
            // 【v4.34.0】图片落盘让位给视频
            return t;
        }
    });

    /** 正在下载中的 URL → 等待该结果的回调列表（key 存在即表示「在飞」） */
    static final ConcurrentHashMap<String, List<Runnable>> inflight = new ConcurrentHashMap<>();
    static final Object FLOCK = new Object();
    static final Runnable NOOP = new Runnable() { @Override public void run() { } };

    /** 每个下载线程复用一块 32KB 读缓冲，避免每张图 new 一次 */
    private static final ThreadLocal<byte[]> TLBUF = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[32 * 1024]; }
    };

    static final Handler ui = new Handler(Looper.getMainLooper());
    static File dir;

    /** 磁盘封面缓存上限（超过就按最久未使用清到 80%） */
    static final long DISK_CAP = 120L * 1024 * 1024;
    /** 单张封面最大接收字节（超过直接放弃，绝不把内存撑爆） */
    static final int MAX_BYTES = 12 * 1024 * 1024;

    static volatile int placeholderColor = 0xFF262A31;

    public static void init(final File cacheDir) {
        try {
            dir = new File(cacheDir, "img");
            if (!dir.exists()) dir.mkdirs();
            // 连接池放大到 16：6 条可见 + 3 条预取 + 页面其它图片请求，
            // 默认 5 条会让超出的连接被池子直接关掉、退化成每次重新握手。
            try { System.setProperty("http.maxConnections", "16"); } catch (Throwable ignore) { }
            try { System.setProperty("http.keepAlive", "true"); } catch (Throwable ignore) { }
            // 后台清一次超容量的旧封面
            diskPool.execute(new Runnable() { @Override public void run() { sweepDisk(); } });
        } catch (Throwable ignore) { }
    }

    public static void setPlaceholderColor(int c) {
        placeholderColor = c;
    }

    public static int placeholderColor() {
        return placeholderColor;
    }

    /** 绑定到 ImageView；离开视野后被复用也能通过 tag 校验避免错图 */
    public static void load(ImageView iv, String rawUrl, final int w, final int h) {
        if (iv == null) return;
        final String url = fit(https(rawUrl), w, h);
        if (url.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        // 复用行时若当前显示的已是同一张图，直接跳过，避免重复 setImageBitmap 触发重绘
        if (url.equals(iv.getTag()) && iv.getDrawable() != null) {
            Bitmap cur = mem.get(url);
            if (cur != null && isSameBitmap(iv, cur)) {
                return;
            }
        }
        iv.setTag(url);
        Bitmap b = mem.get(url);
        if (b != null) {
            if (!isSameBitmap(iv, b)) iv.setImageBitmap(b);
            return;
        }
        // 还没到货：先清图（占位底色由适配器在创建控件时设好）
        iv.setImageBitmap(null);

        final Runnable cb = new Runnable() {
            @Override public void run() {
                if (!url.equals(iv.getTag())) return;
                Bitmap x = mem.get(url);
                if (x != null && !isSameBitmap(iv, x)) iv.setImageBitmap(x);
            }
        };
        if (!enqueue(url, cb)) return;   // 已有同 URL 在飞，挂上回调即可
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = fetch(url, w, h);
                if (bm != null) mem.put(url, bm);
                finish(url);
            }
        });
    }

    /**
     * 登记等待者。返回 true 表示「这次由你发起下载」，false 表示「已经有人在下了」。
     */
    static boolean enqueue(String url, Runnable cb) {
        synchronized (FLOCK) {
            List<Runnable> lst = inflight.get(url);
            if (lst != null) {
                lst.add(cb);
                return false;
            }
            lst = new ArrayList<Runnable>(2);
            lst.add(cb);
            inflight.put(url, lst);
            return true;
        }
    }

    /**
     * 一次下载结束：把等待该结果的回调全部派发（不管这次下载是由
     * 「可见项 load」发起还是由「预取 prefetch」发起，都必须派发，
     * 否则先预取、后可见的组合会让 ImageView 永远等不到图）。
     */
    static void finish(String url) {
        List<Runnable> lst;
        synchronized (FLOCK) { lst = inflight.remove(url); }
        if (lst == null) return;
        for (int i = 0; i < lst.size(); i++) {
            final Runnable r = lst.get(i);
            ui.post(r);
        }
    }

    /**
     * 只把图片下载并写进内存 LRU，不绑定任何 ImageView。
     * 走低优先级 prePool，并参与 in-flight 去重，绝不挤占可见封面的线程与带宽。
     */
    public static void prefetch(String rawUrl, final int w, final int h) {
        final String url = fit(https(rawUrl), w, h);
        if (url.isEmpty()) return;
        if (mem.get(url) != null) return;   // 已在内存，不必重复
        if (!enqueue(url, NOOP)) return;    // 已经在下载了（可见项已经发起），不重复
        prePool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = fetch(url, w, h);
                if (bm != null) mem.put(url, bm);
                finish(url);   // 预取也必须派发等待者：否则先预取、后可见的那张会一直停在占位色
            }
        });
    }

    /**
     * 【v4.31.0】按顺序批量预取（动态流 / 搜索列表用）。
     * 顺序很重要：prePool 是 FIFO 的，先提交的先生效 —— 正好就是「离屏幕近的先来」。
     */
    public static void prefetchList(List<String> urls, int w, int h) {
        if (urls == null) return;
        for (int i = 0; i < urls.size(); i++) prefetch(urls.get(i), w, h);
    }

    /** 本地视频缩略图。API 29 的 loadThumbnail 降到全版本走 MediaMetadataRetriever */
    public static void loadUri(final ImageView iv, final android.net.Uri uri,
                               final int w, final int h) {
        if (iv == null || uri == null) return;
        final String key = "uri:" + uri;
        iv.setTag(key);
        Bitmap b = mem.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = Compat.videoThumb(iv.getContext(), uri, w, h);
                if (bm == null) return;
                mem.put(key, bm);
                final Bitmap fb = bm;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }

    /** 判断 ImageView 当前显示的是否就是这张 Bitmap（避免重复 setImageBitmap 触发重绘） */
    static boolean isSameBitmap(ImageView iv, Bitmap b) {
        try {
            return iv.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                    && ((android.graphics.drawable.BitmapDrawable) iv.getDrawable()).getBitmap() == b;
        } catch (Throwable e) {
            return false;
        }
    }

    /** B 站图床的尺寸后缀：...@320w_200h_1c.jpg */
    static final java.util.regex.Pattern PIC_SIZE =
            java.util.regex.Pattern.compile("@(\\d+)w_(\\d+)h_1c\\.(jpg|png|webp)$");

    /**
     * 按显示像素重写图床尺寸参数：
     *  · 目标像素 × 1.15 = 想要宽度（轻微超采样，缩放时不发虚）；
     *  · 声明宽度已经够 → 原样使用，不再另下一份（省流量、少一次请求）；
     *  · 声明宽度不够 → 向上取到想要宽度（上限 1080）。
     */
    static String fit(String url, int w, int h) {
        if (url == null || url.isEmpty() || w <= 0 || h <= 0) return url;
        try {
            java.util.regex.Matcher m = PIC_SIZE.matcher(url);
            if (!m.find()) return url;
            int cw = Integer.parseInt(m.group(1));
            int want = (int) (w * 1.15f);
            if (cw >= want) return url;
            int need = Math.min(1080, Math.max(want, 160));
            if (need <= cw) return url;
            int nh = Math.max(1, (int) ((long) need * h / w));
            return url.substring(0, m.start()) + "@" + need + "w_" + nh + "h_1c." + m.group(3);
        } catch (Throwable e) {
            return url;
        }
    }

    /** 头像 / 圆形小图的统一尺寸处理 */
    public static String face(String url, int px) {
        String u = https(url);
        if (u.isEmpty() || u.contains("@")) return u;
        int p = px <= 0 ? 96 : px;
        return u + "@" + p + "w_" + p + "h_1c.jpg";
    }

    /** 把封面地址统一成 https（targetSdk 34 默认禁止明文 HTTP） */
    public static String https(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http://")) return "https://" + u.substring("http://".length());
        return u;
    }

    /**
     * 取图：磁盘命中 → 解码；否则下载 → 内存解码 → 异步落盘。
     * 关键点是「不在同一条线程里既等网络又等闪存」。
     */
    static Bitmap fetch(String url, int w, int h) {
        if (dir == null) return null;
        File f = new File(dir, md5(url) + ".img");
        if (f.exists() && f.length() > 0) {
            Bitmap b = decode(f, w, h);
            if (b != null) {
                try { f.setLastModified(System.currentTimeMillis()); } catch (Throwable ignore) { }
                return b;
            }
            f.delete();
        }
        byte[] data = download(url);
        if (data == null) return null;
        Bitmap b = decodeBytes(data, w, h);
        if (b != null) saveAsync(f, data);
        return b;
    }

    /**
     * 下载到内存。成功时不 disconnect —— 读完关闭输入流，连接退回池子复用。
     * 这是三个模块封面变快的核心：同域名后续封面省掉一次 TLS 握手。
     */
    static byte[] download(String url) {
        HttpURLConnection c = null;
        boolean ok = false;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", BiliApi.UA);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            c.setRequestProperty("Connection", "keep-alive");
            int code = c.getResponseCode();
            if (code != 200) return null;
            int len = c.getContentLength();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    len > 0 && len < MAX_BYTES ? len : 64 * 1024);
            InputStream in = c.getInputStream();
            byte[] buf = TLBUF.get();
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return null;    // 异常大图：放弃，别把堆撑爆
                bos.write(buf, 0, n);
            }
            in.close();
            ok = true;
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            // 失败才断开；成功时保持连接给池子复用
            if (!ok && c != null) {
                try { c.disconnect(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 异步落盘：写 tmp → rename → 更新修改时间（LRU 依据） */
    static void saveAsync(final File dst, final byte[] data) {
        try {
            diskPool.execute(new Runnable() {
                @Override public void run() {
                    File tmp = new File(dst.getAbsolutePath() + ".tmp");
                    FileOutputStream out = null;
                    try {
                        out = new FileOutputStream(tmp);
                        out.write(data);
                        out.flush();
                        out.close();
                        out = null;
                        if (dst.exists()) dst.delete();
                        if (!tmp.renameTo(dst)) tmp.delete();
                    } catch (Throwable t) {
                        try { if (out != null) out.close(); } catch (Throwable ignore) { }
                        tmp.delete();
                    }
                }
            });
        } catch (Throwable ignore) { }
    }

    /** 采样解码：字节数组版（不再经过磁盘中转） */
    static Bitmap decodeBytes(byte[] data, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sampleOf(o.outWidth, o.outHeight, w, h);
            o2.inPreferredConfig = Bitmap.Config.RGB_565;   // 封面不需要 alpha，省一半内存
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 采样解码。规则：解出来的图最多比目标大 1.6 倍，再大就整体降采样。
     * 按长边判断，尺寸最贴合目标 —— 1080 宽的源不会为 372px 的小图白占 3 倍内存。
     */
    static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sampleOf(o.outWidth, o.outHeight, w, h);
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable e) {
            return null;
        }
    }

    static int sampleOf(int ow, int oh, int w, int h) {
        int sample = 1;
        long limW = (long) Math.max(1, w) * 16L / 10L;
        long limH = (long) Math.max(1, h) * 16L / 10L;
        while (ow / sample > limW || oh / sample > limH) sample *= 2;
        return sample;
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 清空磁盘封面缓存（设置里可调用） */
    public static void clearDisk() {
        try {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) f.delete();
        } catch (Throwable ignore) { }
    }

    /** 磁盘缓存占用（字节） */
    public static long diskSize() {
        long n = 0;
        try {
            if (dir == null) return 0;
            File[] fs = dir.listFiles();
            if (fs == null) return 0;
            for (File f : fs) n += f.length();
        } catch (Throwable ignore) { }
        return n;
    }

    /** 【v4.31.0】超过 120MB 就按最久未使用清到 80%（后台线程调用） */
    static void sweepDisk() {
        try {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= DISK_CAP) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            long target = DISK_CAP * 8 / 10;
            for (File f : fs) {
                if (total <= target) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignore) { }
    }

    /** 清空内存缓存（清磁盘时一起做） */
    public static void clearMem() {
        try { mem.evictAll(); } catch (Throwable ignore) { }
        try { synchronized (FLOCK) { inflight.clear(); } } catch (Throwable ignore) { }
    }
}
