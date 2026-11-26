package com.bixiaoqu.app;

import com.bixiaoqu.app.DiskCache;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 【v4.22.0】磁盘分片缓存测试（纯 JVM）。
 *
 * 重点验证「宁可不用缓存，也不能返回错数据」这条底线：
 *  · 完整写入 → 命中，200/206 头与字节完全正确
 *  · 半截写入（abort / 断开）→ 不命中
 *  · 请求长度与文件不符 → 不命中
 *  · 超过单文件上限 → 放弃缓存，但不影响调用方
 *  · key 只认 URL path → 换 host / 换鉴权参数仍能命中，不同视频不串
 */
public class DiskCacheTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) pass++;
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    static void eq(String name, long got, long want) {
        if (got == want) pass++;
        else { fail++; System.out.println("  [FAIL] " + name + " 期望 " + want + " 实际 " + got); }
    }

    static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DiskCacheTest ===");
        File base = new File(System.getProperty("java.io.tmpdir"),
                "bxq-dc-" + System.currentTimeMillis());
        base.mkdirs();
        DiskCache.clear();
        DiskCache.init(base);

        String u1 = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/12345/12345-1-30080.m4s"
                + "?deadline=1700000000&upsig=aaa&trid=111";
        String u1b = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/2/12345/12345-1-30080.m4s"
                + "?deadline=1700009999&upsig=bbb&trid=222";
        String u2 = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/99999/99999-1-30080.m4s"
                + "?deadline=1700000000&upsig=ccc";

        // key 稳定性
        eq("换 host/鉴权仍是同一个 key",
                DiskCache.keyOfUrl(u1).equals(DiskCache.keyOfUrl(u1b)) ? 0 : 1, 0);
        ok("不同视频 key 不同", !DiskCache.keyOfUrl(u1).equals(DiskCache.keyOfUrl(u2)));

        // ---------- 完整写入 → 命中 ----------
        byte[] data = payload(100000);
        DiskCache.Writer w = DiskCache.begin(u1, 0, -1);
        ok("可开始写", w != null);
        w.write(data, 0, data.length);
        w.commit();

        File f = DiskCache.lookup(DiskCache.keyOfUrl(u1), 0, -1, -1);
        ok("完整写入后命中", f != null);
        eq("缓存长度正确", f == null ? -1 : f.length(), 100000);

        // 换 host 的同一个分片也应命中
        File f2 = DiskCache.lookup(DiskCache.keyOfUrl(u1b), 0, -1, -1);
        ok("换镜像 host 仍命中", f2 != null);

        // 不同视频不该命中
        ok("不同视频不命中", DiskCache.lookup(DiskCache.keyOfUrl(u2), 0, -1, -1) == null);

        // ---------- 200 全量 ----------
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean served = DiskCache.serve(f, 0, -1, out);
        ok("200 全量下发成功", served);
        String head = new String(out.toByteArray(), 0, Math.min(200, out.size()), "UTF-8");
        ok("200 响应行", head.startsWith("HTTP/1.1 200 OK"));
        ok("Content-Length 100000", head.contains("Content-Length: 100000"));
        byte[] body = out.toByteArray();
        int hdrEnd = indexOf(body, "\r\n\r\n".getBytes("UTF-8"));
        ok("body 长度正确", hdrEnd > 0 && body.length - hdrEnd - 4 == 100000);
        ok("body 内容正确", hdrEnd > 0 && body[hdrEnd + 4 + 99999] == data[99999]);

        // ---------- 206 片段 ----------
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        boolean served2 = DiskCache.serve(f, 0, 499, out2);
        ok("206 片段下发成功", served2);
        String head2 = new String(out2.toByteArray(), 0, Math.min(220, out2.size()), "UTF-8");
        ok("206 响应行", head2.startsWith("HTTP/1.1 206 Partial Content"));
        ok("Content-Range 正确", head2.contains("Content-Range: bytes 0-499/100000"));
        ok("片段 Content-Length 500", head2.contains("Content-Length: 500"));

        // ---------- 半截写入不命中 ----------
        String u3 = "https://x.bilivideo.com/upgcxcode/3/3/33333/33333-1-30080.m4s";
        DiskCache.Writer w3 = DiskCache.begin(u3, 0, -1);
        w3.write(data, 0, 500);
        w3.abort();
        ok("abort 后不命中", DiskCache.lookup(DiskCache.keyOfUrl(u3), 0, -1, -1) == null);

        // 写完但请求长度大于文件 → 不命中（防止返回半截给播放器）
        String u4 = "https://x.bilivideo.com/upgcxcode/4/4/44444/44444-1-30080.m4s";
        DiskCache.Writer w4 = DiskCache.begin(u4, 0, -1);
        w4.write(data, 0, 1000);
        w4.commit();
        ok("小文件 + 大请求长度 → 不命中",
                DiskCache.lookup(DiskCache.keyOfUrl(u4), 0, -1, 5000) == null);
        ok("小文件 + 未知长度 → 命中",
                DiskCache.lookup(DiskCache.keyOfUrl(u4), 0, -1, -1) != null);

        // ---------- 超单文件上限 ----------
        String u5 = "https://x.bilivideo.com/upgcxcode/5/5/55555/55555-1-30080.m4s";
        DiskCache.Writer w5 = DiskCache.begin(u5, 0, -1);
        ok("可开始写大文件", w5 != null);
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < 49; i++) w5.write(big, 0, big.length);   // 49MB > 48MB 上限
        w5.commit();
        ok("超过 48MB 不落盘", DiskCache.lookup(DiskCache.keyOfUrl(u5), 0, -1, -1) == null);

        // ---------- 不缓存「中段 Range」 ----------
        ok("start>0 不可缓存", !DiskCache.cacheable(1024, 2048));
        ok("start==0 可缓存", DiskCache.cacheable(0, -1));

        // ---------- 重复 begin 同一 key ----------
        ok("已有缓存时不重复写", DiskCache.begin(u1, 0, -1) == null);

        // ---------- 容量淘汰 ----------
        // 造 60 个 8MB 假文件（直接写目录，绕过 Writer），把 total 撑到 480MB 再 sweep
        File dir = new File(base, "vcache");
        byte[] chunk = new byte[1024 * 1024];
        for (int i = 0; i < 60; i++) {
            File x = new File(dir, "fake-" + i + ".vdat");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(x);
            for (int k = 0; k < 8; k++) fos.write(chunk);
            fos.close();
            x.setLastModified(System.currentTimeMillis() - (60 - i) * 60000L);
        }
        DiskCache.total = 60L * 8 * 1024 * 1024;   // 手工对齐统计
        DiskCache.sweep();
        long sz = DiskCache.size();
        ok("淘汰后总量下降", sz < 60L * 8 * 1024 * 1024);
        ok("淘汰到 80% 以下", sz <= DiskCache.MAX_TOTAL * 8 / 10 + 8 * 1024 * 1024);
        // 最旧的应该先被删
        ok("最旧文件已被删", !new File(dir, "fake-0.vdat").exists());

        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("结果：" + (fail == 0 ? "ALL PASS" : "FAIL"));
        // 清理
        DiskCache.clear();
        File[] fs = base.listFiles();
        if (fs != null) for (File x : fs) x.delete();
        base.delete();
        if (fail != 0) System.exit(1);
    }

    static int indexOf(byte[] a, byte[] b) {
        outer:
        for (int i = 0; i + b.length <= a.length; i++) {
            for (int j = 0; j < b.length; j++) if (a[i + j] != b[j]) continue outer;
            return i;
        }
        return -1;
    }
}
