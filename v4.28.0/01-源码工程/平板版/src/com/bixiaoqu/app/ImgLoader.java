package com.bixiaoqu.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

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

/**
 * 轻量封面图加载器（不引入任何图片库，保持 APK 体积）：
 * · 内存 LRU + 磁盘缓存（cacheDir/img）
 * · 后台线程池下载，二次采样解码到目标尺寸附近
 * · 带 B 站图床要求的 Referer，避免 403
 *
 * 【v4.17.0 加载提速】
 * 旧版只有 1 个 4 线程池，可见封面与「下一条预取」抢同一条队列，
 * 结果预取任务把可见封面的线程占满 → 用户看到的封面迟迟不出。
 * 现在拆成两级：
 * · pool    —— 只服务「当前可见」的封面，线程优先级 MAX_PRIORITY，4 条并发；
 * · prePool —— 只服务「预取」，线程优先级 MIN_PRIORITY，2 条并发，绝不挤占可见项。
 * 另外加了 in-flight 去重：同一张图被多个复用行同时请求时只下载一次，
 * 其余调用方挂上回调等同一份结果，避免列表快速滑动时对同一 URL 重复发十几条 HTTP。
 */
public class ImgLoader {

    /**
     * 封面内存缓存。
     *
     * 【v4.9 16MB → v4.14.8 24MB → v4.15.3 32MB → v4.17.0 48MB】
     * 竖屏滑动要同时缓存「下一条 + 再下一条 + 上一条」的封面，
     * 一张全宽竖屏封面解码后约 4~5MB，缓存太小会导致预取的封面互相逐出，
     * 滑动时照样要等网络（等于白预取）。
     *
     * 【v4.28.0 固定 48MB → 按堆上限自适应】
     *   48MB 是按 256MB 堆的中端机定的。低端机（heap 128MB）上，
     *   48MB 图片缓存 + 播放器缓冲 + DEX/字符串很容易把堆顶到 80%，
     *   GC 就会在滑动时频繁触发 —— 表现是「掉帧 + 卡顿」，比缓存小更糟。
     *   现在按 maxMemory/5 取，上限仍是 48MB：
     *     heap 128MB → 25MB（约 5 张封面，足够覆盖相邻三条）
     *     heap 192MB → 38MB
     *     heap 256MB+ → 48MB（不退步）
     *   sizeOf 用 getByteCount，LruCache 按真实字节淘汰，不会误判。
     */
    static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(memCapBytes()) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b == null ? 0 : b.getByteCount();
        }
    };

    /** 堆上限的 1/5，封顶 48MB、保底 16MB（低于 16MB 相邻三条就装不下） */
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

    /**
     * 【v4.12 → v4.17.0】可见封面专用线程池。
     * 线程优先级拉到 MAX_PRIORITY：封面出图快慢直接决定「卡不卡」的体感，
     * 应当优先于后台预取与其它后台任务。
     */
    static final ExecutorService pool = Executors.newFixedThreadPool(4,
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bxq-img");
                    t.setPriority(Thread.MAX_PRIORITY);
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * 【v4.17.0】预取专用线程池：低优先级 + 只有 2 条并发。
     * 预取只是「提前把下一批封面备好」，把 CPU 与网络让给当前可见内容才是对的取舍。
     */
    static final ExecutorService prePool = Executors.newFixedThreadPool(2,
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bxq-img-pre");
                    t.setPriority(Thread.MIN_PRIORITY);
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 【v4.17.0】正在下载中的 URL → 等待该结果的回调列表（key 存在即表示「在飞」） */
    static final ConcurrentHashMap<String, List<Runnable>> inflight = new ConcurrentHashMap<>();
    static final Object FLOCK = new Object();

    static final Handler ui = new Handler(Looper.getMainLooper());
    static File dir;

    /** 【v4.17.0】封面占位底色（跟随主题，柔和不刺眼）；由页面在应用主题时设置 */
    static volatile int placeholderColor = 0xFF262A31;

    public static void init(File cacheDir) {
        try {
            dir = new File(cacheDir, "img");
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignore) { }
    }

    /** 【v4.17.0】设置封面占位色（深/浅主题各一份柔和灰蓝） */
    public static void setPlaceholderColor(int c) {
        placeholderColor = c;
    }

    public static int placeholderColor() {
        return placeholderColor;
    }

    /** 绑定到 ImageView；离开视野后被复用也能通过 tag 校验避免错图 */
    public static void load(ImageView iv, String rawUrl, final int w, final int h) {
        if (iv == null) return;
        // 【v4.16.0】按控件实际像素挑分辨率：以前固定 @320w，平板横屏一列 500px 起步，
        // 320 宽的图被拉伸 2 倍，糊成一片。这里按目标宽自动换 @Nw_Nh。
        final String url = fit(https(rawUrl), w, h);
        if (url.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        // 【v4.14.8】复用行时若当前显示的已是同一张图，直接跳过，避免重复 setImageBitmap
        // 触发的无谓重绘（列表快速滑动时这是主要的掉帧来源之一）。
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
        // 还没到货：先清图（占位底色由适配器在创建控件时设好，避免每次绑定都触发重绘）
        iv.setImageBitmap(null);

        final Runnable cb = new Runnable() {
            @Override public void run() {
                if (!url.equals(iv.getTag())) return;
                Bitmap x = mem.get(url);
                if (x != null && !isSameBitmap(iv, x)) iv.setImageBitmap(x);
            }
        };
        boolean start;
        synchronized (FLOCK) {
            List<Runnable> lst = inflight.get(url);
            start = (lst == null);
            if (lst == null) {
                lst = new ArrayList<Runnable>();
                inflight.put(url, lst);
            }
            lst.add(cb);
        }
        if (!start) return;   // 已有同 URL 在下载，等它的结果即可，不再重复发 HTTP
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = fetch(url, w, h);
                if (bm != null) mem.put(url, bm);
                finish(url);
            }
        });
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
     * 【v4.9 预取】只把图片下载并写入内存 LRU，不绑定任何 ImageView。
     *
     * 【为什么需要单独一个方法】fetch() 原来只落磁盘，不写 mem；
     * 而滑到下一条时要立刻显示封面，如果 mem 里没有就得重新走网络 → 卡一下。
     * 提前预取进 mem，滑动时 ImgLoader.load() 会直接命中内存，零等待。
     *
     * 【v4.17.0】改走低优先级 prePool，并参与 in-flight 去重，
     * 预取绝不会挤占可见封面的线程与带宽。
     */
    public static void prefetch(String rawUrl, final int w, final int h) {
        final String url = fit(https(rawUrl), w, h);
        if (url.isEmpty()) return;
        if (mem.get(url) != null) return;   // 已在内存，不必重复
        boolean start;
        synchronized (FLOCK) {
            List<Runnable> lst = inflight.get(url);
            start = (lst == null);
            if (lst == null) {
                lst = new ArrayList<Runnable>();
                inflight.put(url, lst);
            }
        }
        if (!start) return;   // 已经在下载了（可见项已经发起），不重复
        prePool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = fetch(url, w, h);
                if (bm != null) mem.put(url, bm);
                // 【v4.17.0 关键】预取也必须派发等待者回调：
                // 若此时有可见项刚好请求同一张图（预取先发起、可见项后挂上），
                // 不派发的话那张图就永远停在占位色上。
                finish(url);
            }
        });
    }

    /** 本地视频缩略图。【v4.11.0】API 29 的 loadThumbnail 降到全版本走 MediaMetadataRetriever */
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
     * 【v4.16.0 → v4.17.0】按显示像素重写图床尺寸参数。
     *
     * VideoItem.thumb() 一律拼 @480w_270h，手机双列（约 500px）就已经偏糊，
     * 平板横屏四列时更明显。规则：
     *  · 目标像素 × 1.15 = 想要宽度（轻微超采样，缩放时不发虚）；
     *  · 声明宽度已经够 → 原样使用，不再另下一份（省流量、少一次请求）；
     *  · 声明宽度不够 → 向上取到想要宽度（上限 1080，避免在平板上白下 4K 图）。
     */
    static String fit(String url, int w, int h) {
        if (url == null || url.isEmpty() || w <= 0 || h <= 0) return url;
        try {
            java.util.regex.Matcher m = PIC_SIZE.matcher(url);
            if (!m.find()) return url;
            int cw = Integer.parseInt(m.group(1));
            int want = (int) (w * 1.15f);
            if (cw >= want) return url;                    // 已经够清晰，别多下一份
            int need = Math.min(1080, Math.max(want, 160));
            if (need <= cw) return url;
            int nh = Math.max(1, (int) ((long) need * h / w));
            return url.substring(0, m.start()) + "@" + need + "w_" + nh + "h_1c." + m.group(3);
        } catch (Throwable e) {
            return url;
        }
    }

    /**
     * 【v4.16.1】头像 / 圆形小图的统一尺寸处理。
     * 以前 10 处页面各写一遍「没有 @ 就补 @Nw_Nh_1c.jpg」，改一处漏一处，
     * 这里收敛成一个方法：补 https + 补尺寸后缀（已带尺寸的直接用）。
     */
    public static String face(String url, int px) {
        String u = https(url);
        if (u.isEmpty() || u.contains("@")) return u;
        int p = px <= 0 ? 96 : px;
        return u + "@" + p + "w_" + p + "h_1c.jpg";
    }

    /**
     * 【v4.10.2】把封面地址统一成 https。
     * B 站老接口（space/arc/search、动态 feed）返回的 pic 常是 http://i0.hdslb.com/...，
     * 而 targetSdk 34 默认禁止明文 HTTP（nsc 只放行 127.0.0.1），
     * 结果「我的」投稿与动态页的封面全部加载失败、只剩灰块。
     * 图床本身支持 https，这里直接升级协议，一处修复所有页面。
     */
    public static String https(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http://")) return "https://" + u.substring("http://".length());
        return u;
    }

    /**
     * 【v4.17.0】下载 + 解码。超时收紧（5s/9s）：封面等太久不如先显示占位、
     * 下一帧再补上；把时间留给真正能出图的请求。
     */
    static Bitmap fetch(String url, int w, int h) {
        if (dir == null) return null;
        try {
            File f = new File(dir, md5(url) + ".img");
            if (f.exists() && f.length() > 0) {
                Bitmap b = decode(f, w, h);
                if (b != null) return b;
                f.delete();
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", BiliApi.UA);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            File tmp = new File(dir, md5(url) + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            c.disconnect();
            if (!tmp.renameTo(f)) { tmp.delete(); return null; }
            return decode(f, w, h);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 【v4.17.0】采样解码。
     * 规则：解出来的图最多比目标大 1.6 倍，再大就整体降采样。
     * 旧版要求宽高同时满足 h，结果 1080 宽的源解成 1080 给 372px 的小图用，
     * 白占 3 倍内存、上传 GPU 也更慢；现在按长边判断，尺寸最贴合目标。
     */
    static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            long limW = (long) w * 16L / 10L;
            long limH = (long) h * 16L / 10L;
            while (o.outWidth / sample > limW || o.outHeight / sample > limH) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565; // 封面不需要 alpha，省一半内存
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable e) {
            return null;
        }
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

    /** 清空内存缓存（清磁盘时一起做） */
    public static void clearMem() {
        try { mem.evictAll(); } catch (Throwable ignore) { }
        try { synchronized (FLOCK) { inflight.clear(); } } catch (Throwable ignore) { }
    }
}
