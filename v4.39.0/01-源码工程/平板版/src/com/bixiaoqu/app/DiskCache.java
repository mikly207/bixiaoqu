package com.bixiaoqu.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 【v4.22.0】播放分片磁盘缓存。
 *
 * 目标很朴素：**看过的视频，第二次点开不要再从零下载**。
 *
 * 为什么以「整个 m4s」为单位而不是按固定块切：
 * 系统 MediaPlayer 拉 DASH 流时，对同一个 m4s 基本是 `Range: bytes=0-` 一次到底
 * （它需要先读 moov/moof 索引才知道怎么解），所以按「URL + 起始偏移」整段落盘，
 * 命中率最高、实现也最简单 —— 不需要把多个块拼起来，不存在拼花屏的风险。
 *
 * 安全边界（宁可不缓存，也不能让播放出错）：
 *  · 只缓存 start==0 且读到 EOF 的完整分片；
 *  · 单文件 > 24MB 直接放弃（长视频一个 m4s 能上百 MB，缓存它只会撑爆磁盘）；
 *  · 总容量 300MB，超了按最久未使用删；
 *  · 写完先落 .tmp 再 rename，读到半截文件的情况不会发生；
 *  · 命中时校验长度，长度不符就当没命中（走网络）。
 */
public class DiskCache {

    static final long MAX_TOTAL = 400L * 1024 * 1024;
    static final long MAX_FILE = 48L * 1024 * 1024;
    /** 剩余空间不足这个值就不再写，避免把用户存储塞满 */
    static final long MIN_FREE = 80L * 1024 * 1024;

    static File dir;
    static volatile long total = -1L;

    /**
     * 【v4.23.0】初始化改为「同步建目录 + 后台统计」。
     *
     * 旧版在 Application.onCreate 里同步 listFiles + 逐个 length() + 淘汰扫描：
     * 缓存攒到几百 MB（几千个分片文件）后，这一步要走几十到上百毫秒的主线程 I/O，
     * 冷启动时正好卡在第一帧前面 —— 用户看到的就是「点开 App 白一下」。
     * 现在目录建好就返回，容量统计和 LRU 淘汰交给后台线程，谁也不等它。
     */
    public static synchronized void init(final File cacheDir) {
        try {
            if (dir != null) return;
            dir = new File(cacheDir, "vcache");
            if (!dir.exists()) dir.mkdirs();
            total = 0;
            Thread t = new Thread(NetPrio.wrap(new Runnable() { @Override public void run() {
                try {
                    File[] fs = dir == null ? null : dir.listFiles();
                    long sum = 0L;
                    if (fs != null) for (File f : fs) sum += f.length();
                    total = sum;
                    sweep();
                } catch (Throwable ignore) { }
            } }, NetPrio.BACKGROUND), "bxq-cache-scan");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) { }
    }

    public static boolean ready() {
        return dir != null && dir.isDirectory();
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 缓存 key：只取 URL 的 path。
     *
     * B 站每个分片 URL 都带一次性鉴权（deadline/upsig/trid…），而且每次重新取址
     * host 也可能被调度到另一个镜像机房 —— 用整条 URL 做 key，缓存永远命中不了。
     * path 里已经包含 cid 与清晰度编号（如 /upgcxcode/xx/xx/12345/12345-1-30080.m4s），
     * 既稳定又能区分不同视频与不同档位，正是我们要的。
     */
    public static String keyOfUrl(String url) {
        if (url == null) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            return (p == null || p.isEmpty()) ? url : p;
        } catch (Throwable t) {
            return url;
        }
    }

    static File fileFor(String url, long start, long end) {
        if (!ready() || url == null || url.isEmpty()) return null;
        // key 里带上「清晰度/URL 本身」，B 站每条流的鉴权参数不同 → 天然按档位分开缓存。
        // 这里统一再过一遍 keyOfUrl：只取 path，于是「整条 URL」和「已归一化的 path」
        // 两个入口算出来的文件名完全一致，调用方怎么传都不会错过缓存。
        return new File(dir, md5(keyOfUrl(url) + "|" + start + "|" + end) + ".vdat");
    }

    /** 请求是否值得查缓存：只认「从头开始」的整段请求 */
    public static boolean cacheable(long start, long end) {
        return ready() && start == 0L && (end < 0L || end > 0L);
    }

    /** 命中返回文件，未命中返回 null。length 是本次请求期望的字节数（-1 表示到结尾） */
    public static File lookup(String url, long start, long end, long expectLen) {
        File f = fileFor(url, start, end);
        if (f == null || !f.isFile()) return null;
        long len = f.length();
        if (len <= 0) return null;
        if (expectLen > 0 && len < expectLen) return null;   // 短了就是没写完，当没命中
        if (expectLen > 0 && end > 0 && len != (end - start + 1)) return null;
        // touch：更新访问时间，LRU 删除时不会先删它
        try { f.setLastModified(System.currentTimeMillis()); } catch (Throwable ignore) { }
        return f;
    }

    /** 把缓存文件按请求的 Range 片段写给客户端 */
    public static boolean serve(File f, long start, long end, OutputStream out) {
        FileInputStream in = null;
        try {
            long total = f.length();
            long from = Math.max(0L, start);
            long to = (end > 0 && end < total - 1) ? end : total - 1;
            long count = to - from + 1;
            if (count <= 0) return false;
            StringBuilder h = new StringBuilder();
            if (from == 0L && to == total - 1) {
                h.append("HTTP/1.1 200 OK\r\n");
            } else {
                h.append("HTTP/1.1 206 Partial Content\r\n");
                h.append("Content-Range: bytes ").append(from).append('-').append(to)
                 .append('/').append(total).append("\r\n");
            }
            h.append("Content-Length: ").append(count).append("\r\n");
            h.append("Content-Type: video/mp4\r\n");
            h.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            in = new FileInputStream(f);
            long skip = from;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) break;
                skip -= s;
            }
            byte[] buf = new byte[128 * 1024];
            long left = count;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
            return left == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    // ------------------------------------------------------------ 写入

    /** 边转发边落盘。写完调 commit()，中途失败调 abort()。 */
    public static class Writer {
        final File tmp;
        final File dst;
        FileOutputStream os;
        long n;
        boolean dead;

        Writer(File dst) {
            this.dst = dst;
            this.tmp = new File(dst.getAbsolutePath() + ".tmp");
        }

        public void write(byte[] b, int off, int len) {
            if (dead || os == null) return;
            if (n + len > MAX_FILE) { abort(); return; }   // 太大：放弃缓存，不影响播放
            try { os.write(b, off, len); n += len; }
            catch (Throwable t) { abort(); }
        }

        public void commit() {
            if (dead || os == null) return;
            try {
                os.flush();
                os.close();
                os = null;
                if (n <= 0) { tmp.delete(); return; }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) { tmp.delete(); return; }
                addTotal(n);
                sweep();
            } catch (Throwable t) {
                abort();
            }
        }

        public void abort() {
            dead = true;
            try { if (os != null) os.close(); } catch (Throwable ignore) { }
            os = null;
            try { tmp.delete(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 开始写一条分片。只接受 start==0 的请求；调用方负责判断响应是否 200/206 完整。
     * 返回 null 表示这条不缓存（磁盘紧张 / 已有缓存 / 不可缓存）。
     */
    public static Writer begin(String url, long start, long end) {
        if (!cacheable(start, end)) return null;
        File dst = fileFor(url, start, end);
        if (dst == null) return null;
        if (dst.isFile() && dst.length() > 0) return null;
        try {
            if (freeSpace() < MIN_FREE) return null;
            Writer w = new Writer(dst);
            w.os = new FileOutputStream(w.tmp);
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    static long freeSpace() {
        try { return dir.getUsableSpace(); } catch (Throwable t) { return Long.MAX_VALUE; }
    }

    static synchronized void addTotal(long n) {
        if (total < 0) total = 0;
        total += n;
    }

    /** 超出容量按最久未使用删 */
    public static synchronized void sweep() {
        if (!ready()) return;
        try {
            if (total < 0) total = 0;
            if (total <= MAX_TOTAL) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : fs) {
                if (total <= MAX_TOTAL * 8 / 10) break;
                long len = f.length();
                if (f.delete()) total -= len;
                if (total < 0) total = 0;
            }
        } catch (Throwable ignore) { }
    }

    public static long size() {
        return total < 0 ? 0 : total;
    }

    public static void clear() {
        if (!ready()) return;
        try {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            total = 0;
        } catch (Throwable ignore) { }
    }
}
