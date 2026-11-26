package com.bixiaoqu.app;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 【v4.30.0】并行拉流的端到端测试（真跑一个 HTTP 服务端 + 真跑 LocalProxy）。
 *
 * 为什么非要做这一层：并行下载最怕的不是"慢"，是**拼错**。
 * 段边界差一个字节、某段回了 200 整包、失败后没续传干净 —— 任何一条都会
 * 让播放器收到错位数据（花屏/卡死），而且只在特定网速下偶发，真机上极难复现。
 *
 * 所以这里用真实 socket 把整条链路跑一遍：
 *   客户端 → LocalProxy(127.0.0.1) → 本地 HTTP 服务端
 * 然后**逐字节比对**整段内容，同时数服务端被请求了几次、Range 是什么，
 * 以此证明「确实并行了 / 该不并行时确实只开了一条」。
 */
public class ProxyParallelTest {

    static byte[] DATA;
    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    /** 支持 Range 的假 CDN；requests 里记录每次拿到的 Range 头 */
    static class Cdn implements HttpHandler {
        final boolean rangeOk;
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());

        Cdn(boolean rangeOk) { this.rangeOk = rangeOk; }

        @Override public void handle(HttpExchange ex) throws java.io.IOException {
            String r = ex.getRequestHeaders().getFirst("Range");
            requests.add(r == null ? "none" : r);
            long from = 0, to = DATA.length - 1L;
            boolean partial = false;
            if (rangeOk && r != null && r.startsWith("bytes=")) {
                String spec = r.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash == 0) {                              // bytes=-500
                    long n = Long.parseLong(spec.substring(1).trim());
                    from = DATA.length - n;
                    partial = true;
                } else if (dash > 0) {
                    from = Long.parseLong(spec.substring(0, dash).trim());
                    String t = spec.substring(dash + 1).trim();
                    to = t.isEmpty() ? DATA.length - 1L : Long.parseLong(t);
                    partial = true;
                }
            }
            if (from < 0) from = 0;
            if (to > DATA.length - 1L) to = DATA.length - 1L;
            int len = (int) (to - from + 1L);
            if (!partial) { from = 0; len = DATA.length; }

            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "video/mp4");
            h.set("Accept-Ranges", "bytes");
            if (partial) h.set("Content-Range", "bytes " + from + "-" + to + "/" + DATA.length);
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            OutputStream os = ex.getResponseBody();
            os.write(DATA, (int) from, len);
            os.close();
        }
    }

    /** 第二段第一次必挂（503），之后正常 —— 用来验证「段失败 → 单连接续传」这条回退路 */
    static class Flaky extends Cdn {
        volatile boolean failed = false;
        Flaky() { super(true); }

        @Override public void handle(HttpExchange ex) throws java.io.IOException {
            String r = ex.getRequestHeaders().getFirst("Range");
            boolean second = false;
            if (r != null) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("bytes=(\\d+)-").matcher(r);
                if (m.find() && Long.parseLong(m.group(1)) > 0L) second = true;
            }
            if (second && !failed) {
                failed = true;
                requests.add(r);
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            super.handle(ex);
        }
    }

    /** 通过代理取一段，返回原始响应体（不含响应头） */
    static byte[] fetch(int port, String[] lineUrls, String range) throws Exception {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(port).append("/p?g=1");
        sb.append("&u=").append(URLEncoder.encode(lineUrls[0], "UTF-8"));
        for (int i = 1; i < lineUrls.length; i++) {
            sb.append("&u").append(i + 1).append("=")
              .append(URLEncoder.encode(lineUrls[i], "UTF-8"));
        }
        String url = sb.toString();
        String path = url.substring(url.indexOf('/', 8));   // /p?g=1&u=...

        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(60000);
        OutputStream out = s.getOutputStream();
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: 127.0.0.1\r\n");
        if (range != null) req.append("Range: ").append(range).append("\r\n");
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        InputStream in = s.getInputStream();
        // 精确匹配 CRLFCRLF 结束响应头，绝不多吞 body 的第一个字节
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int match = 0;
        while (match < 4) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            if ((match == 0 || match == 2) && b == '\r') match++;
            else if ((match == 1 || match == 3) && b == '\n') match++;
            else match = (b == '\r') ? 1 : 0;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) body.write(b, 0, n);
        s.close();
        return body.toByteArray();
    }

    /**
     * 数「从非 0 起点开始的有限区间」请求 —— 只有并行段的第 2/3 段长这样：
     * 段 0 沿用客户端的 bytes=X-，而客户端自己的区间起点是 0 或会被这个条件排除。
     */
    static int segRequests(List<String> reqs) {
        int n = 0;
        synchronized (reqs) {
            for (String r : reqs) {
                if (r == null) continue;
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(r);
                if (m.find() && Long.parseLong(m.group(1)) > 0L) n++;
            }
        }
        return n;
    }

    static boolean same(byte[] a, int off, byte[] b) {
        if (b.length != a.length - off) {
            System.out.println("     长度不符：期望 " + (a.length - off) + " 实得 " + b.length);
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            if (a[off + i] != b[i]) {
                System.out.println("     第 " + i + " 字节起不一致（绝对偏移 " + (off + i) + "）");
                return false;
            }
        }
        return true;
    }

    public static void main(String[] a) throws Exception {
        DATA = new byte[12 * 1024 * 1024 + 777];      // 故意不是整 MB，考验余数归末段
        new Random(20261126).nextBytes(DATA);

        CrashHandler.install(null, new File("/tmp/bxq-proxy-log"));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        Cdn cdn = new Cdn(true);
        Cdn cdn200 = new Cdn(false);
        Flaky flaky = new Flaky();
        srv.createContext("/v.m4s", cdn);
        srv.createContext("/norange.m4s", cdn200);
        srv.createContext("/flaky.m4s", flaky);
        srv.start();
        int sp = srv.getAddress().getPort();

        LocalProxy proxy = new LocalProxy();
        proxy.start();
        int port = proxy.port();

        String base = "http://127.0.0.1:" + sp + "/v.m4s?e=1";
        String nr = "http://127.0.0.1:" + sp + "/norange.m4s?e=1";

        // ---- 1) 12MB 整段 + 3 条线路 → 应该并行，且内容逐字节一致
        System.out.println("=== 12MB 整段（3 线路）===");
        cdn.requests.clear();
        byte[] got = fetch(port, new String[]{base, base + "&b=2", base + "&b=3"}, "bytes=0-");
        ok("内容逐字节一致", same(DATA, 0, got));
        ok("确实拆了段（有限区间请求 " + segRequests(cdn.requests) + " 条）",
                segRequests(cdn.requests) >= 2);
        boolean allPart = true;
        for (String r : cdn.requests) if (r == null || !r.startsWith("bytes=")) allPart = false;
        ok("每条都是 Range 请求（没有整包下载）", allPart);

        // ---- 2) 只有 1 条线路 → 不许并行
        System.out.println("=== 12MB 整段（单线路）===");
        cdn.requests.clear();
        got = fetch(port, new String[]{base}, "bytes=0-");
        ok("内容逐字节一致", same(DATA, 0, got));
        ok("单线路只开一条连接（" + cdn.requests.size() + " 次）", cdn.requests.size() == 1);

        // ---- 3) 非 0 起点（拖动进度条）→ 段边界必须从 start 算起
        System.out.println("=== 从 1,000,000 起 ===");
        cdn.requests.clear();
        got = fetch(port, new String[]{base, base + "&b=2", base + "&b=3"}, "bytes=1000000-");
        ok("从断点起的整段内容一致", same(DATA, 1000000, got));
        ok("断点请求也拆了段（" + segRequests(cdn.requests) + " 条）",
                segRequests(cdn.requests) >= 2);

        // ---- 4) 小于 4MB 阈值 → 不并行（握手开销不划算）
        System.out.println("=== 4MB 以下不拆 ===");
        cdn.requests.clear();
        got = fetch(port, new String[]{base, base + "&b=2", base + "&b=3"}, "bytes=0-2097151");
        ok("2MB 内容一致", got.length == 2097152 && same(java.util.Arrays.copyOf(DATA, got.length), 0, got));
        ok("2MB 不拆段（有限区间请求 " + segRequests(cdn.requests) + " 条）",
                segRequests(cdn.requests) == 0);

        // ---- 5) 上游不支持 Range（回 200 整包）→ 必须退回单连接，内容仍要正确
        System.out.println("=== 上游不支持 Range ===");
        cdn200.requests.clear();
        got = fetch(port, new String[]{nr, nr + "&b=2", nr + "&b=3"}, "bytes=0-");
        ok("不支持 Range 时内容一致", same(DATA, 0, got));
        ok("不支持 Range 时不硬拆（有限区间请求 " + segRequests(cdn200.requests) + " 条）",
                segRequests(cdn200.requests) == 0);

        // ---- 5b) 某一段必挂 → 必须自动退回单连接续传，且内容仍然逐字节正确
        System.out.println("=== 某段第一次返回 503 ===");
        String fk = "http://127.0.0.1:" + sp + "/flaky.m4s?e=1";
        got = fetch(port, new String[]{fk, fk + "&b=2", fk + "&b=3"}, "bytes=0-");
        ok("段失败后自动续传，内容仍逐字节一致", same(DATA, 0, got));
        ok("确实发生过失败（回退路径被走到）", flaky.failed);

        // ---- 6) 后缀 Range（bytes=-500）→ 不缓存、不并行，取末 500 字节
        System.out.println("=== bytes=-500 ===");
        cdn.requests.clear();
        got = fetch(port, new String[]{base, base + "&b=2", base + "&b=3"}, "bytes=-500");
        ok("末 500 字节正确", same(DATA, DATA.length - 500, got));

        proxy.stop();
        srv.stop(0);

        System.out.println("--------------------------------------");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("结果：" + (fail == 0 ? "ALL PASS" : "FAIL"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
