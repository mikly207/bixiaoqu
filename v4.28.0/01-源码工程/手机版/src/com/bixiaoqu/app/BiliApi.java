package com.bixiaoqu.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * B 站接口封装：Cookie 管理、短链解析、view API、WBI 签名的 playurl API。
 * 全部走公开网页端接口，不涉及登录态破解；提供 SESSDATA 可解锁高码率音源。
 */
public final class BiliApi {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36";
    static final String REFERER = "https://www.bilibili.com/";

    /** 进程内 Cookie（自动收集 Set-Cookie；可用 addCookie 注入 SESSDATA） */
    static final Map<String, String> COOKIES =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>());
    static volatile String mixinKey = null;

    // ==================== 【v4.15.13】TLS 1.2 强制启用 ====================
    // 根因：Android 4.x/5.x 的系统 HttpsURLConnection 默认只协商 TLS 1.0/1.1，
    // 而 passport.bilibili.com 与云端接口用的 CDN 只接受 TLS 1.2 → 握手阶段直接挂起
    // （不返回、不报错，只能干等超时）。这正好解释「api.bilibili.com 正常、passport 卡死」。
    // 这里显式给每个 HTTPS 连接挂上 TLS1.2 socket 工厂，老设备也能握手成功。
    private static volatile SSLSocketFactory TLS_FACTORY = null;

    static {
        // 【v4.15.13】老机型/部分移动网络下 IPv6 是黑洞：请求先走 AAAA 地址，
        // 连不通也不立刻失败，于是整条链路卡在握手前。强制优先 IPv4。
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Throwable ignore) { }
    }

    static SSLSocketFactory tlsFactory() {
        SSLSocketFactory f = TLS_FACTORY;
        if (f != null) return f;
        synchronized (BiliApi.class) {
            if (TLS_FACTORY != null) return TLS_FACTORY;
            try {
                SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                ctx.init(null, null, null);
                TLS_FACTORY = ctx.getSocketFactory();
            } catch (Throwable t) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, null, null);
                    TLS_FACTORY = ctx.getSocketFactory();
                } catch (Throwable ignore) { }
            }
            return TLS_FACTORY;
        }
    }

    // ==================== 【v4.13.5】带宽预估与播放缓冲调参（从 v4.14 合回） ====================
    // 本工程用系统 MediaPlayer + 本地转发代理，不是 ExoPlayer。用等价手段落地同样的目标：
    //  · 初始带宽预估 10Mbps：挑流时按「预估带宽 × 系数」约束码率，首播不过冲到链路扛不住的
    //    超高码率轨（横屏高清视频更容易因选到超高码率而卡加载），播放中由代理实测回填。
    //  · TCP_NODELAY + 首块立刻下发 + 256KB 转发缓冲，等效于尽快灌满起播缓冲再稳态续传。
    //  · 分片并发闸门：LocalProxy 把同时转发的分片连接限制在 2 个，避免 CDN 带 token 直链
    //    因请求堆积过期（这是「横屏视频有概率下载不出来」的主要诱因之一）。
    static volatile long estBandwidthBps = 10L * 1000 * 1000;   // 初始预估 10Mbps

    // ==================== 【v4.20.0】起播窗口（让路机制） ====================
    // 实测竖屏视频「点开→出画面」比横屏慢一截，根因不是解码，而是竖屏路径会额外触发
    // 一大批「锦上添花」的请求：related + 3 页 popular + 6 条 playurl 预取 +
    // like/coin/follow 三个互动状态 + 整包弹幕 XML。它们和视频首帧抢同一条链路，
    // 弱网下首帧就被顶到 3 秒开外 —— 用户直觉说的「线路满溢」就是这个。
    //
    // 约定：从 play() 开始到首帧出画后 1.2 秒内，这些请求一律排队；
    // 只有「视频流 / 音轨」两条连接可以占带宽。
    public static volatile long startupGuardUntil = 0L;

    /** 是否处于起播保护窗口内 */
    public static boolean startupBusy() {
        return System.currentTimeMillis() < startupGuardUntil;
    }

    /** 进入起播窗口：caps 为最长保护时间（兜底，防首帧永不回调时把预取永久冻住） */
    public static void beginStartup(long caps) {
        long until = System.currentTimeMillis() + caps;
        if (until > startupGuardUntil) startupGuardUntil = until;
    }

    /** 首帧出画：把窗口收敛到「现在 + hold」，窗口结束后预取自然恢复 */
    public static void endStartup(long hold) {
        long until = System.currentTimeMillis() + hold;
        if (until < startupGuardUntil) startupGuardUntil = until;
    }

    /**
     * 【v4.20.0】异步预热设备指纹。
     * 冷启动后第一次 playurl 会先在 initFinger() 里同步打首页拿 buvid3/buvid4，
     * 这一步常占 0.8~2 秒 —— 表现就是「App 里点第一个视频特别慢，之后就正常」。
     * 首页/列表一启动就在后台把它做掉，第一次点播与后续点播一样快。
     */
    public static void warmFinger() {
        if (fingerReady) return;
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try { initFinger(); } catch (Throwable ignore) { }
        } }, "bxq-finger");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * 【v4.20.0】统一解析接口里的画面宽高比（含 rotate=90/270 的竖屏拍摄）。
     * 列表页把 aspect 带进播放页，播放页就能「第一帧之前」摆好竖屏沉浸式布局，
     * 不必等 view 接口回来再改容器尺寸 —— 那一次改尺寸正是竖屏独有的额外卡顿。
     */
    public static float aspectOf(JSONObject o) {
        if (o == null) return 0f;
        try {
            JSONObject d = o.optJSONObject("dimension");
            if (d == null) d = o.optJSONObject("dim");
            int w = 0, h = 0, rot = 0;
            if (d != null) {
                w = d.optInt("width", 0); h = d.optInt("height", 0);
                rot = d.optInt("rotate", 0);
            }
            if (w <= 0 || h <= 0) {
                w = o.optInt("width", 0); h = o.optInt("height", 0);
                rot = o.optInt("rotate", 0);
            }
            if (w > 0 && h > 0) {
                if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }
                return w / (float) h;
            }
        } catch (Throwable ignore) { }
        return 0f;
    }

    /** 代理转发完一段后回填实测带宽（平滑），供下次挑流使用。bytes/时长(ms) → bps */
    static void noteThroughput(long bytes, long ms) {
        if (bytes <= 0 || ms <= 0) return;
        long bps = bytes * 8000L / ms;              // 字节→比特
        if (bps < 200_000L) return;                 // 过小的请求（索引/短分片）不参与估算
        long old = estBandwidthBps;
        // 指数平滑：0.35 新 + 0.65 旧，抑制单分片抖动
        long next = (long) (old * 0.65 + bps * 0.35);
        // 限幅 0.5Mbps ~ 100Mbps，避免异常值把估算带飞
        if (next < 500_000L) next = 500_000L;
        if (next > 100L * 1000 * 1000) next = 100L * 1000 * 1000;
        estBandwidthBps = next;
    }

    static final Pattern P_BV = Pattern.compile("(BV[0-9A-Za-z]{8,12})");
    static final Pattern P_AV = Pattern.compile("(?:^|[^A-Za-z0-9])av(\\d{1,15})(?:[^0-9]|$)");

    static {
        // 不预置伪造 buvid3：伪造格式会被 B 站 WAF 直接 412
        COOKIES.put("b_nut", String.valueOf(System.currentTimeMillis() / 1000));
    }

    /** 获取 B 站真实设备指纹：首页 Set-Cookie → finger/spi → 合规格式兜底 */
    static volatile boolean fingerReady = false;

    static void initFinger() {
        if (fingerReady) return;
        synchronized (BiliApi.class) {
            if (fingerReady) return;
            try {
                // ① 关键：先访问首页，让 B 站下发 WAF 认可的 buvid3/buvid4/b_nut/_uuid
                primeCookies();
                // ② 再用 finger 接口补齐
                if (!COOKIES.containsKey("buvid3") || !COOKIES.containsKey("buvid4")) {
                    try {
                        JSONObject r = getJson("https://api.bilibili.com/x/frontend/finger/spi");
                        if (r.optInt("code", -1) == 0) {
                            JSONObject d = r.getJSONObject("data");
                            String b3 = d.optString("b_3", "");
                            String b4 = d.optString("b_4", "");
                            if (!b3.isEmpty()) COOKIES.put("buvid3", b3);
                            if (!b4.isEmpty()) COOKIES.put("buvid4", b4);
                        }
                    } catch (Throwable ignore) { }
                }
                // ③ 仍缺失才用合规格式伪造（32位hex + 5位数字 + infoc）
                if (!COOKIES.containsKey("buvid3")) COOKIES.put("buvid3", fakeBuvid3());
                if (!COOKIES.containsKey("buvid4")) COOKIES.put("buvid4", fakeBuvid4());
                if (!COOKIES.containsKey("b_nut"))
                    COOKIES.put("b_nut", String.valueOf(System.currentTimeMillis() / 1000));
                // 【v4.15.9 扫码修复】补设备 ID _uuid：B 站登录风控会比对它，
                // 生成二维码、轮询、后续接口必须保持同一台「设备」，否则会出现
                // 「认得出账号但不认可会话」（nav 返回 isLogin=false）。
                if (!COOKIES.containsKey("_uuid")) COOKIES.put("_uuid", fakeUuid());
            } catch (Throwable ignore) { }
            fingerReady = true;
        }
    }

    /** 访问 B 站首页，收集它下发的全部 Cookie（这是通过 WAF 的关键） */
    static void primeCookies() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://www.bilibili.com/").openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = c.getResponseCode();
            collectCookies(c);
            // 必须把流读完，否则连接不能复用且部分 cookie 可能丢失
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            while (in.read(buf) > 0) { }
            in.close();
        } catch (Throwable ignore) {
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /** 从响应头收集 Set-Cookie */
    static void collectCookies(HttpURLConnection c) {
        try {
            java.util.List<String> list = c.getHeaderFields().get("Set-Cookie");
            if (list == null) {
                list = c.getHeaderFields().get("set-cookie");
            }
            if (list == null) {
                // 某些实现 key 大小写不定，全表扫一遍
                for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                        list = e.getValue();
                        break;
                    }
                }
            }
            if (list == null) return;
            for (String v : list) {
                if (v == null) continue;
                int semi = v.indexOf(';');
                String pair = semi > 0 ? v.substring(0, semi) : v;
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String val = pair.substring(eq + 1).trim();
                    if (!k.isEmpty() && !val.isEmpty()) COOKIES.put(k, val);
                }
            }
        } catch (Throwable ignore) { }
    }

    /** 合规格式的 buvid3 兜底：32位大写hex + 5位数字 + infoc */
    static String fakeBuvid3() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(42);
        for (int i = 0; i < 32; i++) sb.append("0123456789ABCDEF".charAt(r.nextInt(16)));
        sb.append(10000 + r.nextInt(90000));
        sb.append("infoc");
        return sb.toString();
    }

    static String fakeBuvid4() {
        return UUID.randomUUID().toString().toUpperCase() + "-"
                + (System.currentTimeMillis() / 1000);
    }

    /** B 站设备 ID：与网页端同构的 _uuid 格式（8-4-4-4-12 + 时间尾数 + infoc） */
    static String fakeUuid() {
        java.util.Random r = new java.util.Random();
        StringBuilder h = new StringBuilder(12);
        for (int i = 0; i < 12; i++) h.append("0123456789abcdef".charAt(r.nextInt(16)));
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4"
                + (100 + r.nextInt(900)) + "-a" + (100 + r.nextInt(900)) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + (System.currentTimeMillis() % 100000) + "infoc";
    }

    /** 写入登录 Cookie（扫码成功后调用） */
    public static void setLoginCookie(String sessdata, String biliJct, String dedeUserId) {
        COOKIES.put("SESSDATA", sessdata);
        COOKIES.put("bili_jct", biliJct);
        COOKIES.put("DedeUserID", dedeUserId);
    }

    /** 【v4.15.9】登录态相关的全部字段：只存 SESSDATA 三件套会丢掉 B 站校验会话必需的项 */
    static final java.util.Set<String> LOGIN_KEYS = new java.util.HashSet<String>(
            java.util.Arrays.asList("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"));

    /** 导出完整登录 Cookie 串（含 DedeUserID__ckMd5 / sid），用于持久化与跨启动恢复 */
    public static String exportLoginCookie() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (!LOGIN_KEYS.contains(e.getKey())) continue;
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 清除登录态 */
    public static void clearLoginCookie() {
        for (String k : LOGIN_KEYS) COOKIES.remove(k);
    }

    /** 已登录用户 ID（未登录返回 null） */
    public static String loggedInUid() {
        return COOKIES.get("DedeUserID");
    }

    /** 供设置页注入登录 Cookie（Netscape txt 或 k=v 字符串） */
    public static void addCookie(String raw) {
        if (raw == null) return;
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length >= 7) { // Netscape 格式
                COOKIES.put(parts[5], parts[6]);
            } else {
                // 【v4.15.9】支持 "k1=v1; k2=v2" 整串 Cookie（扫码登录持久化用），
                // 旧实现只按第一个 '=' 切，会把后面所有字段塞进 value。
                for (String seg : line.split(";")) {
                    int eq = seg.indexOf('=');
                    if (eq <= 0) continue;
                    String k = seg.substring(0, eq).trim();
                    String v = seg.substring(eq + 1).trim();
                    if (!k.isEmpty() && !v.isEmpty()) COOKIES.put(k, v);
                }
            }
        }
    }

    // ------------------------------------------------------------ HTTP

    public static String httpGet(String url) throws IOException {
        return httpGet0(url, 0, null);
    }

    /** 指定 Referer 的请求（关注/收藏等接口会校验来源页） */
    public static String httpGet(String url, String referer) throws IOException {
        return httpGet0(url, 0, referer);
    }

    static String httpGet0(String url, int retry) throws IOException {
        return httpGet0(url, retry, null);
    }

    static String httpGet0(String url, int retry, String referer) throws IOException {
        return httpGet0(url, retry, referer, 0, 0);
    }

    /** 【v4.15.10】可指定超时的版本：扫码这类「失败要快速反馈」的请求用小超时 */
    static String httpGet0(String url, int retry, String referer, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // 【v4.15.13】老系统默认不协商 TLS1.2，passport / 云端 CDN 只在 1.2 上握手 →
        // 表现为连接挂起直到超时。显式挂 TLS1.2 工厂，这是「直连永远不出码」的底层根因。
        if (c instanceof HttpsURLConnection) {
            SSLSocketFactory f = tlsFactory();
            if (f != null) {
                try { ((HttpsURLConnection) c).setSSLSocketFactory(f); } catch (Throwable ignore) { }
            }
        }
        c.setConnectTimeout(connectMs > 0 ? connectMs : 15000);
        c.setReadTimeout(readMs > 0 ? readMs : 30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer != null ? referer : REFERER);
        // 【v4.15.9 扫码修复】Origin 必须与目标域一致。此前一律写死 www.bilibili.com，
        // 访问 passport 登录域时来源对不上，会被风控直接拦下（表现为二维码生成失败）。
        String origin = "https://www.bilibili.com";
        try {
            String host = new URL(url).getHost();
            if (host != null && host.startsWith("passport.")) origin = "https://" + host;
        } catch (Throwable ignore) { }
        c.setRequestProperty("Origin", origin);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        // 【v4.12 带宽优化】JSON 接口响应普遍压缩 5~10 倍，显式声明 gzip 省流量也提速
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("Sec-Fetch-Dest", "empty");
        c.setRequestProperty("Sec-Fetch-Mode", "cors");
        c.setRequestProperty("Sec-Fetch-Site", "same-site");

        StringBuilder ck = new StringBuilder();
        synchronized (COOKIES) {
            for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                if (ck.length() > 0) ck.append("; ");
                ck.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());

        int code = c.getResponseCode();
        // 关键：Set-Cookie 必须在连接建立（getResponseCode）之后才读得到
        collectCookies(c);
        if (code == 301 || code == 302 || code == 303 || code == 307) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new IOException("重定向缺少 Location: HTTP " + code);
            return httpGet0(new URL(new URL(url), loc.replace(" ", "%20")).toString(), retry, referer);
        }
        if (code == 412) {
            c.disconnect();
            // 风控：重新获取设备指纹后重试一次（多数情况可恢复）
            if (retry < 1) {
                fingerReady = false;
                primeCookies();
                return httpGet0(url, retry + 1, referer);
            }
            throw new IOException("B站风控(412)：" + (loggedInUid() != null
                    ? "账号被临时限流，请稍后重试"
                    : "未登录被限流，点右上角「登录」后再试，或稍后重试"));
        }
        if (code != 200) {
            throw new IOException("HTTP " + code + "：" + url);
        }

        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String enc = c.getContentEncoding();
        c.disconnect();
        byte[] raw = bos.toByteArray();
        if (enc != null) {
            String e = enc.toLowerCase();
            if (e.contains("gzip")) raw = gunzip(raw);
            else if (e.contains("deflate")) raw = inflate(raw);
        }
        return new String(raw, "UTF-8");
    }

    /** 解 gzip（部分接口无视 Accept-Encoding 也会压缩） */
    static byte[] gunzip(byte[] raw) throws IOException {
        try {
            java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(raw);
            GZIPInputStream gin = new GZIPInputStream(bin);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 3);
            byte[] buf = new byte[8192];
            int n;
            while ((n = gin.read(buf)) > 0) out.write(buf, 0, n);
            gin.close();
            return out.toByteArray();
        } catch (IOException e) {
            return raw;
        }
    }

    /** 解 deflate：先按 zlib 头解，失败再按 raw deflate 解 */
    static byte[] inflate(byte[] raw) {
        try {
            return inflate0(raw, false);
        } catch (Throwable e) {
            try {
                return inflate0(raw, true);
            } catch (Throwable e2) {
                return raw;
            }
        }
    }

    static byte[] inflate0(byte[] raw, boolean nowrap) throws IOException {
        Inflater inf = new Inflater(nowrap);
        inf.setInput(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 3);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    /** POST 表单（点赞 / 投币 / 收藏 / 关注 / 发弹幕等），返回 JSON。带一次风控 412 自愈重试。 */
    static JSONObject postForm(String url, String body, String referer) throws Exception {
        return postForm(url, body, referer, 0);
    }

    static JSONObject postForm(String url, String body, String referer, int retry) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer != null ? referer : REFERER);
        // 【v4.15.9 扫码修复】Origin 必须与目标域一致。此前一律写死 www.bilibili.com，
        // 访问 passport 登录域时来源对不上，会被风控直接拦下（表现为二维码生成失败）。
        String origin = "https://www.bilibili.com";
        try {
            String host = new URL(url).getHost();
            if (host != null && host.startsWith("passport.")) origin = "https://" + host;
        } catch (Throwable ignore) { }
        c.setRequestProperty("Origin", origin);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        c.setRequestProperty("Sec-Fetch-Site", "same-site");
        c.setRequestProperty("Sec-Fetch-Mode", "cors");
        c.setRequestProperty("Sec-Fetch-Dest", "empty");

        StringBuilder ck = new StringBuilder();
        synchronized (COOKIES) {
            for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                if (ck.length() > 0) ck.append("; ");
                ck.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());

        byte[] data = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();

        int code = c.getResponseCode();
        collectCookies(c);
        if (code == 412) {
            // 写操作撞风控：与 GET 一样重取设备指纹后再试一次（互动场景最易触发）
            c.disconnect();
            if (retry < 1) {
                fingerReady = false;
                primeCookies();
                return postForm(url, body, referer, retry + 1);
            }
            throw new IOException("B站风控拦截(412)，请稍后再试");
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String enc = c.getContentEncoding();
        c.disconnect();
        byte[] raw = bos.toByteArray();
        if (enc != null) {
            String e = enc.toLowerCase();
            if (e.contains("gzip")) raw = gunzip(raw);
            else if (e.contains("deflate")) raw = inflate(raw);
        }
        return new JSONObject(new String(raw, "UTF-8"));
    }

    static JSONObject getJson(String url) throws Exception {
        return getJson(url, null);
    }

    static JSONObject getJson(String url, String referer) throws Exception {
        return getJson(url, referer, 0, 0);
    }

    /** 【v4.15.10】带超时的 getJson（扫码链路用短超时，失败能立刻反馈） */
    static JSONObject getJson(String url, String referer, int connectMs, int readMs) throws Exception {
        String body = httpGet0(url, 0, referer, connectMs, readMs);
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("接口返回非 JSON（可能被风控）：" + body.substring(0, Math.min(120, body.length())));
        }
    }

    // ------------------------------------------------------------ 解析输入

    /** 从任意输入提取 BV 号；b23.tv 短链自动跟随重定向。无法解析返回 null。 */
    public static String extractBvid(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) return null;
        text = text.trim();
        if (text.contains("b23.tv") || text.contains("bili2233.cn")) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(text).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestMethod("HEAD");
                c.getResponseCode(); // 触发重定向
                String finalUrl = c.getURL().toString();
                c.disconnect();
                text = finalUrl;
            } catch (Exception e) {
                return null;
            }
        }
        Matcher m = P_BV.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 提取 av 号（BV 优先）。 */
    public static String extractAvid(String text) {
        if (text == null) return null;
        Matcher m = P_AV.matcher(text.trim());
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------ API

    /** 视频信息：标题、aid、分P 列表。 */
    public static JSONObject view(String bvid, String aid) throws Exception {
        String q = bvid != null ? "bvid=" + bvid : "aid=" + aid;
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/view?" + q);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取视频信息失败：" + r.optString("message", "未知错误"));
        }
        return r.getJSONObject("data");
    }

    static String baseName(String url) {
        int slash = url.lastIndexOf('/');
        String s = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** WBI mixin key（懒加载，进程内缓存） */
    static String navMixinKey() throws Exception {
        if (mixinKey != null) return mixinKey;
        JSONObject nav = getJson("https://api.bilibili.com/x/web-interface/nav");
        JSONObject w = nav.getJSONObject("data").getJSONObject("wbi_img");
        mixinKey = Wbi.mixinKey(baseName(w.getString("img_url")), baseName(w.getString("sub_url")));
        return mixinKey;
    }

    // ------------------------------------------------------------ 播放地址预取缓存（v4.9）

    /**
     * 【v4.9 提速】playurl 结果的内存缓存。
     * 竖屏上下滑切视频时，若等到「松手切换」才去请求 playurl，光网络往返就要 300~800ms，
     * 手感必然是「卡一下」。这里把相邻视频的 playurl 提前在后台线程算好存进来，
     * 切换瞬间直接命中 → 零网络等待，这是「滑动不卡顿」最关键的一环。
     *
     * · LRU + 30 分钟 TTL：B 站 CDN 直链有效期通常 2 小时，30 分钟内复用是安全的。
     * · 容量 16 条：竖屏队列一次预取前后各 2 条足够，再多只是占内存。
     */
    static final java.util.LinkedHashMap<String, Object[]> URL_CACHE =
            new java.util.LinkedHashMap<String, Object[]>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                    // 【v4.14.0】16→32：竖屏快速连滑时，一次预取 ±3 共 6 个 key，
                    // 滑几次就会把即将要看的相邻项挤出 16 槽 LRU，导致预取白做、切换现场取址卡住。
                    return size() > 32;
                }
            };
    static final long URL_CACHE_TTL = 30 * 60 * 1000L;

    /** 缓存键：bvid + cid + 清晰度档位。同一视频不同清晰度是不同地址，不能混用。 */
    static String urlCacheKey(String bvid, long cid, int qn) {
        if (bvid == null || bvid.isEmpty() || cid <= 0) return null;
        return bvid + "|" + cid + "|" + qn;
    }

    /** 写入缓存（已校验过 hasMedia 的结果才允许写入） */
    static void putUrlCache(String key, JSONObject data) {
        if (key == null || data == null) return;
        synchronized (URL_CACHE) {
            URL_CACHE.put(key, new Object[]{data, System.currentTimeMillis()});
        }
    }

    /** 读取缓存；过期或不存在返回 null */
    static JSONObject takeUrlCache(String key) {
        if (key == null) return null;
        synchronized (URL_CACHE) {
            Object[] v = URL_CACHE.get(key);
            if (v == null) return null;
            if (System.currentTimeMillis() - (Long) v[1] > URL_CACHE_TTL) {
                URL_CACHE.remove(key);
                return null;
            }
            return (JSONObject) v[0];
        }
    }


    /**
     * 正在预取中的 key。
     * 【为什么要这个】光靠 URL_CACHE 做去重不够 —— 预取是异步的，
     * 同一个视频在预取还没回来之前可能被多次请求（用户快速连滑），
     * 那就会起多个线程重复打同一个接口，白白消耗带宽并加剧 B 站风控。
     * 用这个集合做「在途去重」。
     */
    static final java.util.HashSet<String> URL_PREFETCHING = new java.util.HashSet<String>();

    /** 【v4.13.5】预取并发闸门：同时最多 2 个 playurl 预取在飞，避免一次滑动并发打多个接口堆积/触发风控。 */
    /**
     * 【v4.23.0】并发 2 → 3。
     * 首页/播放页一次要给 4 条视频预热播放地址，2 个许可意味着第 3、4 条要排队，
     * 后两条往往在用户已经点下去时才拿到地址 —— 等于白预热。
     * playurl 只是几百字节的 JSON，3 条并发对带宽的影响可以忽略，但能让 4 条基本同时就绪。
     */
    static final java.util.concurrent.Semaphore PREFETCH_GATE = new java.util.concurrent.Semaphore(3);

    /**
     * 【v4.15.3】预取线程池：旧版每次预取都 new Thread()（含双括号匿名子类），
     * 快速连滑时频繁创建/销毁线程有开销。改为固定 2 线程的池复用，
     * 与 PREFETCH_GATE 并发上限一致，优先级保持 NORM+2 让起播取址优先拿到 CPU。
     */
    static final java.util.concurrent.ExecutorService PREFETCH_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2,
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "bxq-prefetch");
                            t.setPriority(Thread.NORM_PRIORITY + 2);
                            return t;
                        }
                    });

    /**
     * 【v4.22.0】首片预热专用单线程池。
     * 不能复用 PREFETCH_POOL（只有 2 线程，被预热占住就会拖慢真正的取址预取）。
     * 优先级压到 NORM-1：预热天然可以慢一点，绝不能和起播抢 CPU。
     */
    static final java.util.concurrent.ExecutorService WARM_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "bxq-warm");
                            t.setPriority(Thread.NORM_PRIORITY - 1);
                            return t;
                        }
                    });

    /**
     * 后台预取播放地址（不阻塞调用方）。
     * 预取失败静默忽略 —— 真到切换时仍会走正常链路再试一次。
     */
    public static void prefetchPlayurl(final String bvid, final long cid, final int qn) {
        prefetchPlayurl(bvid, cid, qn, false);
    }

    /**
     * 【v4.22.0】预取播放地址，可选「顺带预热首片」。
     * warm=true 时在拿到地址后再拉 96KB 首片，把 DNS/TCP/TLS/回源提前做掉。
     * 只对最可能被立刻点开的那 1~2 条开启，避免流量浪费。
     *
     * 【v4.27.0】targetH：用户的目标分辨率（0 = 不指定）。
     *   预热必须和正式播放走同一条轨道，否则预热的 URL/CDN 与实际播放不一致，
     *   提前握好的手全部作废。详见 warmFirstChunkOf 的说明。
     */
    public static void prefetchPlayurl(final String bvid, final long cid, final int qn,
                                       final boolean warm) {
        prefetchPlayurl(bvid, cid, qn, warm, 0);
    }

    /**
     * 【v4.28.0】已经做过「首片预热」的 key。
     *
     * 修的是一个真实缺陷：旧版只要 URL_CACHE 命中就直接 return，
     * 但地址缓存的首个写入者往往是 warm=false 的滑动预取 ——
     * 于是这条视频的地址在缓存里躺着，首片却从没预热过；
     * 之后即便有人用 warm=true 再来一次，也会被「已缓存」挡回去。
     * 结果：滑动预取列表里的条目，起播时永远要现付 DNS/TCP/TLS/回源 那 300~900ms。
     * 现在缓存命中时补一次预热，只做一次（本集合去重），不重复消耗带宽。
     */
    static final java.util.HashSet<String> URL_WARMED = new java.util.HashSet<String>();

    /** 标记「该 key 的首片预热已发起」；返回 true 表示这次是首次（调用方应真的去预热） */
    static boolean markWarmIfNew(String key) {
        if (key == null) return false;
        synchronized (URL_WARMED) {
            if (URL_WARMED.size() > 512) URL_WARMED.clear();
            return URL_WARMED.add(key);
        }
    }

    public static void prefetchPlayurl(final String bvid, final long cid, final int qn,
                                       final boolean warm, final int targetH) {
        if (bvid == null || bvid.isEmpty() || cid <= 0) return;
        final String key = urlCacheKey(bvid, cid, qn);
        if (key == null) return;
        synchronized (URL_CACHE) {
            if (URL_CACHE.containsKey(key)) {
                // 【v4.28.0】地址已在缓存 —— 若这次要求预热且从没预热过，补上。
                // 用 takeUrlCache 走同一套 TTL 校验，过期就不补（马上会重新取址）。
                if (warm && markWarmIfNew(key)) {
                    final JSONObject d = takeUrlCache(key);
                    if (d != null) {
                        WARM_POOL.execute(new Runnable() { @Override public void run() {
                            try {
                                warmFirstChunkOf(d, targetH);
                                warmAudioChunkOf(d);
                            } catch (Throwable ignore) { }
                        } });
                    }
                }
                return;   // 地址本身不用重复请求
            }
        }
        // 在途去重：同一个 key 只允许一个预取线程
        synchronized (URL_PREFETCHING) {
            if (!URL_PREFETCHING.add(key)) return;
        }
        // 【v4.15.3】改用线程池复用，去掉每次 new Thread + 双括号匿名子类的开销。
        PREFETCH_POOL.execute(new Runnable() { @Override public void run() {
            boolean got = false;
            try {
                try { PREFETCH_GATE.acquire(); got = true; } catch (InterruptedException ie) { return; }
                JSONObject d = playurlFetch(bvid, null, cid, qn);
                if (d != null && hasMedia(d)) {
                    putUrlCache(key, d);
                    if (warm) {
                        markWarmIfNew(key);   // 【v4.28.0】登记，避免缓存命中路径重复预热
                        // 【v4.27.0】按用户目标档位预热视频轨（与正式播放同轨）
                        warmFirstChunkOf(d, targetH);
                        // 【v4.27.0】DASH 音视频分离，音频轨是另一条独立流，
                        // 正式播放时它在画面首帧之后才 prepare，此前毫无预热 →
                        // 声音会迟到半秒。现在一并预热，音画同步更准。
                        warmAudioChunkOf(d);
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                if (got) PREFETCH_GATE.release();
                synchronized (URL_PREFETCHING) { URL_PREFETCHING.remove(key); }
            }
        } });
    }

    /**
     * 播放地址（DASH/durl）。
     * 端点链：x/player/wbi/playurl（文档化接口，主链）
     *      → x/player/playurl（旧接口，无 WBI）
     *      → platform=html5（合一流 mp4 兜底）。
     * 未登录自动携带 try_look=1（游客试看，可拿 64-132kbps DASH 音轨）。
     */
    /**
     * 【v4.18.0】请求「最高可用」档位。
     * 一次拿回 4K/2K/1080/720 全部轨道后，切清晰度只要在本地重选，无需再请求网络。
     * 所有预取入口也必须用这个值，否则 URL_CACHE 的 key 对不上，预取等于白做。
     */
    public static final int QN_ALL = 0;

    public static JSONObject playurl(String bvid, String aid, long cid) throws Exception {
        return playurl(bvid, aid, cid, QN_ALL);
    }

    /**
     * 指定清晰度档位（qn）请求播放地址。qn=0 走「最高可用」。
     * fnval=4048 一次性打开 DASH / 4K / 8K / HDR / 杜比 / AV1，
     * 这样 dash.video 里会把 4K、2K、1080P60 一起返回，切换清晰度不必重复请求。
     */
    public static JSONObject playurl(String bvid, String aid, long cid, int qn) throws Exception {
        // 【v4.9 提速】命中预取缓存 → 直接返回，不再走网络往返。
        // 竖屏上下滑切视频时，提前把下一条的 playurl 算好存进来，
        // 切换瞬间即可开始拉流 —— 这是「滑动不卡顿」最关键的一环。
        String ck = urlCacheKey(bvid, cid, qn);
        if (ck != null) {
            JSONObject cached = takeUrlCache(ck);
            if (cached != null) return cached;
            // 【v4.14.0】缓存未命中但预取正在途：短暂等待预取结果，而不是自己再发
            // 一个重复请求。竖屏快速连滑时预取线程可能还在飞（首条要等指纹/签名），
            // 旧版会另起请求 → 两次往返串行叠加 → 切换卡住。等待窗口用满即转为自取。
            boolean inFlight;
            synchronized (URL_PREFETCHING) { inFlight = URL_PREFETCHING.contains(ck); }
            if (inFlight) {
                // 【v4.19.0】1200ms → 300ms：预取正常 200~400ms 就回来，
                // 等这么久仍然没有就该自己发请求了 —— 继续干等只会让「点开→起播」更慢。
                long waitUntil = System.currentTimeMillis() + 300L;
                while (System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(40); } catch (InterruptedException ignore) { break; }
                    JSONObject ready = takeUrlCache(ck);
                    if (ready != null) return ready;
                }
            }
        }
        JSONObject d = playurlFetch(bvid, aid, cid, qn);
        if (d != null && ck != null) putUrlCache(ck, d);
        return d;
    }

    /**
     * 真正走网络的 playurl（不走缓存）。
     * 单独拆出来是为了让「预取」和「正式取」共用同一段降级链逻辑，
     * 避免预取拿到的东西和正式请求口径不一致。
     */
    static JSONObject playurlFetch(String bvid, String aid, long cid, int qn) throws Exception {
        initFinger();
        boolean logged = loggedInUid() != null;
        String lastErr = "未知错误";
        int wantQn = qn > 0 ? qn : 120;

        // ---- 链1：wbi/playurl（文档化接口） ----
        Map<String, String> p = new LinkedHashMap<>();
        p.put("bvid", bvid);
        p.put("cid", String.valueOf(cid));
        p.put("qn", String.valueOf(wantQn));
        p.put("fnval", "4048"); // DASH|8K|HDR|杜比|4K|AV1
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("platform", "pc");
        if (!logged) p.put("try_look", "1");
        if (aid != null && !aid.isEmpty()) p.put("aid", aid);
        try {
            String query = Wbi.sign(p, navMixinKey());
            JSONObject r = getJson("https://api.bilibili.com/x/player/wbi/playurl?" + query);
            if (r.optInt("code", -1) != 0) {
                lastErr = "wbi/playurl: " + r.optString("message", "?") + " (code=" + r.optInt("code") + ")";
            } else {
                JSONObject d = r.getJSONObject("data");
                if (hasMedia(d)) return d;
                lastErr = "wbi/playurl: dash/durl 均为空";
            }
        } catch (Exception e) {
            lastErr = "wbi/playurl: " + e.getMessage();
        }

        // ---- 链2：旧接口 x/player/playurl（无 WBI） ----
        StringBuilder q2 = new StringBuilder();
        q2.append("bvid=").append(bvid).append("&cid=").append(cid)
          .append("&qn=").append(wantQn).append("&fnval=4048&fnver=0&fourk=1&platform=pc");
        if (!logged) q2.append("&try_look=1");
        if (aid != null && !aid.isEmpty()) q2.append("&aid=").append(aid);
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/player/playurl?" + q2);
            if (r.optInt("code", -1) != 0) {
                lastErr = "旧接口: " + r.optString("message", "?") + " (code=" + r.optInt("code") + ")";
            } else {
                JSONObject d = r.getJSONObject("data");
                if (hasMedia(d)) return d;
                lastErr = "旧接口: dash/durl 均为空";
            }
        } catch (Exception e) {
            lastErr = "旧接口: " + e.getMessage();
        }

        // ---- 链2.5【v4.13.3 破解】PGC/番剧专用接口 ----
        // 番剧、独家正版、大会员内容用普通 x/player/playurl 会返回 -404「该视频不存在或已失效」，
        // 但它们其实能播 —— 走 PGC 专用接口即可（响应字段是 result 而非 data）。
        // 无条件尝试（普通链已失败到这里），成功且有 dash/durl 就返回。
        try {
            JSONObject r = getJson("https://api.bilibili.com/pgc/player/web/playurl?bvid=" + bvid
                    + "&cid=" + cid + "&qn=" + wantQn
                    + "&fnval=4048&fnver=0&fourk=1", "https://www.bilibili.com/bangumi/play/ss" + bvid);
            if (r.optInt("code", -1) == 0) {
                JSONObject d = r.optJSONObject("result");
                if (d != null && hasMedia(d)) return d;
            }
        } catch (Exception ignore) { }

        // ---- 链3：html5 合一流兜底 ----
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                    + "&cid=" + cid + "&qn=16&platform=html5&high_quality=1");
            if (r.optInt("code", -1) == 0) {
                JSONObject d = r.getJSONObject("data");
                if (hasMedia(d)) return d;
            }
        } catch (Exception ignore) { }

        throw new Exception("获取播放信息失败：" + lastErr
                + "。若多次出现，可能是 B 站风控、视频需付费/大会员/地区限制，请稍后重试或确认视频可正常在线播放。");
    }

    static boolean hasMedia(JSONObject d) {
        if (d.has("dash") && !d.isNull("dash")) {
            JSONObject dash = d.optJSONObject("dash");
            if (dash != null && dash.optJSONArray("audio") != null
                    && dash.optJSONArray("audio").length() > 0) return true;
        }
        if (d.has("durl") && !d.isNull("durl")) {
            JSONArray du = d.optJSONArray("durl");
            if (du != null && du.length() > 0 && !du.optJSONObject(0).optString("url", "").isEmpty())
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 搜索 / 播放器

    /**
     * 关键字搜索视频（WBI 签名搜索接口）。返回 video 类型条目数组，
     * 每条含 bvid / title / author / duration / play。
     */
    public static JSONArray searchVideos(String keyword, int page) throws Exception {
        initFinger();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("search_type", "video");
        p.put("keyword", keyword);
        p.put("page", String.valueOf(page));
        p.put("order", "totalrank");
        String q = Wbi.sign(p, navMixinKey());
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?" + q);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("搜索失败：" + r.optString("message", "未知错误")
                    + " (code=" + r.optInt("code") + ")");
        }
        return r.getJSONObject("data").optJSONArray("result");
    }

    /** 去掉搜索结果标题里的关键词高亮标签与 HTML 实体 */
    public static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replace("<em class=\"keyword\">", "")
                .replace("</em>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'");
    }

    /**
     * 【v4.7】把 playurl 的业务错误码翻译成人话。
     * B站对「cid 不属于该 bvid」「视频失效」等情况只回一句 "啥都木有"，对用户毫无意义。
     */
    static String playErrText(int code, String raw) {
        switch (code) {
            case -404: return "该视频不存在或已失效";
            case -403: return "该视频已下架或无播放权限";
            case -10403: return "该视频需要大会员账号";
            case 87007: return "这是充电专属视频，需先充电";
            case 87008: return "这是付费课程视频";
            case -101: return "请先登录后再播放";
            case -412: return "请求被风控拦截，请稍后重试";
            default:
                return (raw == null || raw.isEmpty()) ? ("错误码 " + code) : raw;
        }
    }

    /**
     * 取 mp4 合一流直链（html5 模式，给 VideoView 播放用）。
     * 登录时自动带高画质；未登录为 360/480p。
     */
    public static String playDurlMp4(String bvid, long cid) throws Exception {
        initFinger();
        StringBuilder q = new StringBuilder();
        q.append("bvid=").append(bvid).append("&cid=").append(cid)
         .append("&qn=64&fnval=0&fnver=0&platform=html5&high_quality=1");
        JSONObject r = getJson("https://api.bilibili.com/x/player/playurl?" + q);
        if (r.optInt("code", -1) != 0) {
            throw new IOException(playErrText(r.optInt("code"), r.optString("message", ""))
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.getJSONObject("data");
        JSONArray du = d.optJSONArray("durl");
        if (du == null || du.length() == 0) {
            throw new IOException("未返回播放地址（durl 为空，可能需要登录或视频受限）");
        }
        return du.getJSONObject(0).getString("url");
    }

    /**
     * 取第一个分P信息（含 cid）。
     * 用 pagelist 而不是 view：pagelist 的风控等级更低，未登录/新 IP 下成功率明显更高。
     */
    public static JSONObject firstPage(String bvid) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/player/pagelist?bvid=" + bvid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取分P失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONArray arr = r.getJSONArray("data");
        if (arr.length() == 0) throw new IOException("该视频没有可播放的分P");
        return arr.getJSONObject(0);
    }

    /** 全部分P（选集用）。字段：cid / page / part / duration */
    public static JSONArray pages(String bvid) throws Exception {
        // 【v4.20.0】分P列表加缓存：热门 / 推荐这类接口不一定带 cid，
        // 点开时旧版必须串行等一次 pagelist 往返（300~800ms）才开始取址。
        // 现在列表页点击瞬间就预取（见 prefetchPages），播放页直接命中。
        if (bvid != null && !bvid.isEmpty()) {
            JSONArray c = PAGES_CACHE.get(bvid);
            if (c != null) return c;
        }
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/player/pagelist?bvid=" + bvid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取分P失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONArray arr = r.optJSONArray("data");
        if (arr == null || arr.length() == 0) throw new IOException("该视频没有可播放的分P");
        try {
            if (PAGES_CACHE.size() > 64) PAGES_CACHE.clear();
            PAGES_CACHE.put(bvid, arr);
        } catch (Throwable ignore) { }
        return arr;
    }

    /** 【v4.20.0】分P列表缓存（bvid → pagelist 的 data 数组） */
    static final java.util.concurrent.ConcurrentHashMap<String, JSONArray> PAGES_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, JSONArray>();
    static final java.util.concurrent.ConcurrentHashMap<String, Boolean> PAGES_FLYING =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    /**
     * 【v4.20.0】点击瞬间预取 pagelist。
     * 只对「列表没带 cid」的条目调用；预热与 Activity 启动并行，点开即命中。
     * 失败静默，正式播放会自己再取一次。
     */
    public static void prefetchPages(final String bvid) {
        if (bvid == null || !bvid.startsWith("BV")) return;
        if (PAGES_CACHE.containsKey(bvid)) return;
        if (PAGES_FLYING.putIfAbsent(bvid, Boolean.TRUE) != null) return;
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try {
                JSONArray arr = pages(bvid);
                // 【v4.20.0】拿到 cid 立刻把 playurl 也预热上：
                // 热门/关注这类列表没带 cid，旧版要「pagelist → playurl」两次串行，
                // 现在点击瞬间这两步一起排队，Activity 起来时地址往往已经就绪。
                if (arr != null && arr.length() > 0) {
                    long cid = arr.getJSONObject(0).optLong("cid", 0);
                    if (cid > 0) prefetchPlayurl(bvid, cid, QN_ALL);
                }
            } catch (Throwable ignore) { }
            finally { PAGES_FLYING.remove(bvid); }
        } }, "bxq-pages");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
    }

    /**
     * 【v4.0 提速 → v4.17.0 六线路】取最多 6 条候选 CDN 直链，供本地代理并行竞速。
     * v3.9 曾为凑线路串行请求 3 次 playurl，实测 backup_url 多为 0，
     * 3 次请求纯属白等（网络差时每次几百 ms~2s）——v4.0 改为只请求 1 次：
     * ① 主地址 ② 同响应 backup_url ③ 域名替换补到 6 条，全程 1 次网络往返。
     */
    public static String[] playDurlMp4Multi(String bvid, long cid, boolean low) throws Exception {
        initFinger();
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<String>();
        int qn = low ? 16 : 64;
        int hq = low ? 0 : 1;
        String primary = null;
        StringBuilder q = new StringBuilder();
        q.append("bvid=").append(bvid).append("&cid=").append(cid)
         .append("&qn=").append(qn).append("&fnval=0&fnver=0&platform=html5&high_quality=")
         .append(hq);
        JSONObject r = getJson("https://api.bilibili.com/x/player/playurl?" + q);
        if (r.optInt("code", -1) != 0) {
            throw new IOException(playErrText(r.optInt("code"), r.optString("message", "")));
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray du = d == null ? null : d.optJSONArray("durl");
        if (du == null || du.length() == 0) throw new IOException("未返回可播放地址（可能需要登录）");
        JSONObject d0 = du.getJSONObject(0);
        String u = d0.optString("url", "");
        if (u != null && !u.isEmpty()) { primary = u; urls.add(u); }
        JSONArray bu = d0.optJSONArray("backup_url");
        if (bu != null) {
            for (int k = 0; k < bu.length() && urls.size() < 6; k++) {
                String b = bu.optString(k, "");
                if (b != null && !b.isEmpty()) urls.add(b);
            }
        }
        // 域名替换补足：换已知备用 CDN 节点，路径与鉴权参数原样保留（本地操作，零网络开销）
        if (primary != null && urls.size() < 6) {
            for (String alt : mirrorUrls(primary)) {
                if (urls.size() >= 6) break;
                urls.add(alt);
            }
        }
        if (urls.isEmpty()) throw new IOException("未返回任何播放线路（可能需要登录或视频受限）");
        return urls.toArray(new String[urls.size()]);
    }


    // ------------------------------------------------------------ 哔小趣：推荐 / 关注

    /**
     * 热门推荐视频列表（无需登录）。字段：bvid/title/owner.name/duration(秒)
     * 【v4.16.1】ps 可选：首页一次要 50 条，榜单接口实测支持 ps=50。
     */
    public static JSONArray popular(int page) throws Exception {
        return popularPage(page, 20);
    }

    /** 同 popular，但可指定每页条数（ps 最大 50）。 */
    public static JSONArray popularPage(int page, int ps) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/popular?ps=" + ps
                + "&pn=" + page);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取推荐失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        return r.getJSONObject("data").optJSONArray("list");
    }

    /**
     * 首页推荐流（WBI 签名）。fresh_idx 每换一次，返回的一批内容就不同，
     * 这是「下拉刷新永远是同一批」的解药：旧版固定 popular pn=1，榜单在几分钟内是死的。
     */
    public static JSONArray recommendFeed(int freshIdx) throws Exception {
        initFinger();
        Map<String, String> p = new LinkedHashMap<>();
        // 【v4.16.1】实测 ps 上限 30（ps=50 直接返回 -400），一次多拿 10 条
        p.put("ps", "30");
        p.put("fresh_type", "4");
        p.put("fresh_idx", String.valueOf(Math.max(1, freshIdx)));
        p.put("fresh_idx_1h", String.valueOf(Math.max(1, freshIdx / 2 + 1)));
        p.put("feed_version", "V8");
        p.put("homepage_ver", "1");
        p.put("brush", String.valueOf(freshIdx % 4));
        p.put("y_num", "4");
        p.put("fresh_sub", "1");
        p.put("last_yiqi_state", "0");
        p.put("web_location", "1430650");
        String q = Wbi.sign(p, navMixinKey());
        JSONObject r = getJson(
                "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd?" + q);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("推荐流: " + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        return r.getJSONObject("data").optJSONArray("item");
    }

    /** 分区排行榜（0=全站）。字段与 popular 一致，可当推荐备源 */
    public static JSONArray ranking(int rid) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/ranking/v2?rid="
                + rid + "&type=all");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("排行榜: " + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        return r.getJSONObject("data").optJSONArray("list");
    }

    /**
     * 【v4.13.2 新番修复】番剧「新番」频道专用。
     * 根因：ranking/v2 不支持 rid=13（番剧），返回 code=-400，导致新番页全空。
     * 番剧是 PGC 内容，不在普通投稿排行榜体系里，改走 PGC 番剧索引接口：
     *   1) pgc/season/index/result 拿番剧列表（season_id / 标题 / 封面 / 追番数）
     *   2) 每个 season 用 pgc/view/web/season 取首集 bvid + cid（现有播放器只认 bvid）
     * 返回结构与 ranking 一致（bvid/aid/title/pic/owner/stat/duration/cid），
     * 这样 MainActivity.fillFromJson 可原样复用，点击照样进 PlayActivity。
     */
    public static JSONArray bangumiNew() throws Exception {
        initFinger();
        final String indexUrl = "https://api.bilibili.com/pgc/season/index/result"
                + "?season_version=-1&spoken_language_type=-1&area=-1&is_finish=-1"
                + "&copyright=-1&season_status=-1&season_month=-1&year=-1&style_id=-1"
                + "&order=3&st=1&sort=0&page=1&season_type=1&pagesize=20&type=1";
        JSONObject r = getJson(indexUrl, "https://www.bilibili.com/anime/index/");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("新番索引: " + r.optString("message", "?"));
        }
        JSONArray seasons = r.optJSONObject("data") != null
                ? r.getJSONObject("data").optJSONArray("list") : null;
        if (seasons == null || seasons.length() == 0) return new JSONArray();

        final JSONArray out = new JSONArray();
        final Object lock = new Object();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(seasons.length());
        for (int i = 0; i < seasons.length(); i++) {
            final JSONObject s = seasons.optJSONObject(i);
            if (s == null) { remaining.decrementAndGet(); continue; }
            final long sid = s.optLong("season_id", 0);
            pool.execute(new Runnable() { @Override public void run() {
                try {
                    if (sid <= 0) return;
                    JSONObject v = getJson(
                            "https://api.bilibili.com/pgc/view/web/season?season_id=" + sid,
                            "https://www.bilibili.com/bangumi/play/ss" + sid);
                    if (v.optInt("code", -1) != 0) return;
                    JSONObject res = v.optJSONObject("result");
                    if (res == null) return;
                    JSONArray eps = res.optJSONArray("episodes");
                    if (eps == null || eps.length() == 0) return;
                    // 取第一个可播的正片集（从前往后找有 bvid 的）
                    JSONObject ep = null;
                    for (int j = 0; j < eps.length(); j++) {
                        JSONObject e = eps.optJSONObject(j);
                        if (e != null && !e.optString("bvid", "").isEmpty()) { ep = e; break; }
                    }
                    if (ep == null) return;
                    // 组一条与 ranking 兼容的记录
                    JSONObject item = new JSONObject();
                    item.put("bvid", ep.optString("bvid", ""));
                    item.put("aid", ep.optLong("aid", 0));
                    item.put("cid", ep.optLong("cid", 0));
                    String title = s.optString("title", "");
                    if (title.isEmpty()) title = res.optString("title", "");
                    item.put("title", cleanTitle(title));
                    String cover = s.optString("cover", "");
                    if (cover.isEmpty()) cover = ep.optString("cover", "");
                    item.put("pic", cover);
                    JSONObject owner = new JSONObject();
                    owner.put("name", s.optString("index_show", "番剧"));  // 更新至第N话
                    item.put("owner", owner);
                    JSONObject stat = new JSONObject();
                    stat.put("view", s.optLong("order", 0));               // 追番数
                    item.put("stat", stat);
                    long durMs = ep.optLong("duration", 0);
                    item.put("duration", durMs > 0 ? durMs / 1000 : 0);
                    synchronized (lock) { out.put(item); }
                } catch (Throwable ignore) {
                } finally { remaining.decrementAndGet(); }
            } });
        }
        // 等所有并发任务完成（最多 12 秒，避免个别慢接口拖死整页）
        long deadline = System.currentTimeMillis() + 12000L;
        while (remaining.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(40); } catch (InterruptedException e) { break; }
        }
        pool.shutdownNow();
        return out;
    }

    /** 推荐可用分区（随机挑一个当备源，保证换一批时品类也变） */
    static final int[] REC_RIDS = {0, 1, 4, 36, 119, 129, 155, 160, 181, 188, 211, 217, 223, 234, 249};

    /**
     * 混合推荐：首页推荐流 + 热门随机页 + 随机分区排行榜，去重后打乱。
     * seed 每刷新一次自增，三个来源同时变化 → 每次下拉都是新内容。
     * 任一来源失败不影响其余，全失败才抛错。
     * 【v4.16.1】want = 期望条数（首页默认 50）。
     */
    public static JSONArray recommendMix(int seed) throws Exception {
        return recommendMix(seed, 50);
    }

    public static JSONArray recommendMix(int seed, int want) throws Exception {
        initFinger();
        Random rnd = new Random(seed * 7919L + System.currentTimeMillis() / 60000L);
        LinkedHashMap<String, JSONObject> uniq = new LinkedHashMap<>();
        String lastErr = null;
        int ok = 0;

        // 1) 首页推荐流（主源，最「千人千面」）：两次不同 fresh_idx，各 20 条
        try {
            JSONArray a = recommendFeed(seed % 12 + 1 + rnd.nextInt(6));
            if (a != null) { addAll(uniq, a); ok++; }
        } catch (Exception e) { lastErr = e.getMessage(); }
        if (uniq.size() < 26) {
            try {
                JSONArray a = recommendFeed(seed % 12 + 7 + rnd.nextInt(6));
                if (a != null) { addAll(uniq, a); ok++; }
            } catch (Exception e) { lastErr = e.getMessage(); }
        }

        // 2) 热门随机页（一页 50 条，内容差异明显）
        if (uniq.size() < 34) {
            try {
                JSONArray a = popularPage(1 + rnd.nextInt(4), 50);
                if (a != null) { addAll(uniq, a); ok++; }
            } catch (Exception e) { lastErr = e.getMessage(); }
        }

        // 3) 随机分区排行榜（补品类）
        if (uniq.size() < 44) {
            try {
                int rid = REC_RIDS[rnd.nextInt(REC_RIDS.length)];
                JSONArray a = ranking(rid);
                if (a != null) { addAll(uniq, a); ok++; }
            } catch (Exception e) { lastErr = e.getMessage(); }
        }

        if (ok == 0) throw new IOException("获取推荐失败：" + (lastErr == null ? "空" : lastErr));

        ArrayList<JSONObject> all = new ArrayList<>(uniq.values());
        Collections.shuffle(all, rnd);
        JSONArray out = new JSONArray();
        int limit = Math.min(Math.max(1, want), all.size());
        for (int i = 0; i < limit; i++) out.put(all.get(i));
        return out;
    }

    /**
     * 【v4.16.1】首页推荐翻页：滚动到底时继续取下一批。
     * 用热门随机页 + 分区榜拼一批，与首页内容基本不重复。
     */
    public static JSONArray recommendMore(int page) throws Exception {
        initFinger();
        Random rnd = new Random(page * 104729L + System.currentTimeMillis() / 30000L);
        LinkedHashMap<String, JSONObject> uniq = new LinkedHashMap<>();
        // 热门随机页（实测 pn 1~6 每页 50 条，足够翻好几屏）
        try {
            JSONArray a = popularPage(1 + rnd.nextInt(6), 50);
            if (a != null) addAll(uniq, a);
        } catch (Throwable ignore) { }
        // 首页推荐流（换一批 fresh_idx，内容与首页不同）
        try {
            JSONArray a = recommendFeed(1 + page * 3 + rnd.nextInt(3));
            if (a != null) addAll(uniq, a);
        } catch (Throwable ignore) { }
        // 分区榜补品类
        try {
            int rid = REC_RIDS[rnd.nextInt(REC_RIDS.length)];
            JSONArray a = ranking(rid);
            if (a != null) addAll(uniq, a);
        } catch (Throwable ignore) { }
        if (uniq.isEmpty()) throw new IOException("没有更多内容了");
        ArrayList<JSONObject> all = new ArrayList<>(uniq.values());
        Collections.shuffle(all, rnd);
        JSONArray out = new JSONArray();
        int limit = Math.min(30, all.size());
        for (int i = 0; i < limit; i++) out.put(all.get(i));
        return out;
    }

    /** 合并去重：只收有 bvid 的稿件 */
    static void addAll(LinkedHashMap<String, JSONObject> uniq, JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String bv = o.optString("bvid", "");
            if (bv.isEmpty()) continue;
            if (!uniq.containsKey(bv)) uniq.put(bv, o);
        }
    }

    /**
     * 【v4.16.1】B 站热搜榜（无需登录）。
     * 返回 data.trending.list，每项：keyword / show_name / heat_score / icon。
     * 搜索页右侧栏与「热搜发现」都走这里。
     */
    public static JSONArray hotSearch(int limit) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/search/square?limit="
                + Math.max(1, Math.min(50, limit)), "https://www.bilibili.com/");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("热搜获取失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        JSONObject tr = d != null ? d.optJSONObject("trending") : null;
        JSONArray list = tr != null ? tr.optJSONArray("list") : null;
        if (list == null) throw new IOException("热搜数据为空");
        // 【v4.17.0】服务端不一定严格按 limit 截断，这里本地再裁一刀，
        // 保证调用方拿到的条数与传入的 limit 完全一致。
        int n = Math.max(1, Math.min(50, limit));
        if (list.length() <= n) return list;
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) out.put(o);
        }
        return out;
    }

    // ------------------------------------------------------------ 【v4.22.0】推荐列表缓存

    /**
     * 相关推荐内存缓存：LRU 48 条，5 分钟。
     *
     * 「相关推荐慢一拍」的根因有两个，这是第二个：
     * 旧版每次进入播放页 / 换片都要重新请求一次 related，而且还要排队等首帧（见 guardStartup）。
     * 缓存之后：同一个视频二次进入、以及「点推荐 → 返回 → 再点回去」都瞬间铺满，
     * 网络请求只发生在真的没见过的 bvid 上。
     */
    static final int REC_TTL = 5 * 60 * 1000;

    static final java.util.LinkedHashMap<String, Object[]> REC_CACHE =
            new java.util.LinkedHashMap<String, Object[]>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> e) {
                    return size() > 48;
                }
            };

    /** 命中缓存直接返回，未命中返回 null；不发起任何网络请求。 */
    public static JSONArray relatedCached(String bvid) {        if (bvid == null || bvid.isEmpty()) return null;
        synchronized (REC_CACHE) {
            Object[] v = REC_CACHE.get(bvid);
            if (v == null) return null;
            if (System.currentTimeMillis() - (Long) v[1] > REC_TTL) {
                REC_CACHE.remove(bvid);
                return null;
            }
            return (JSONArray) v[0];
        }
    }

    static void putRecCache(String bvid, JSONArray arr) {
        if (bvid == null || bvid.isEmpty() || arr == null || arr.length() == 0) return;
        synchronized (REC_CACHE) {
            REC_CACHE.put(bvid, new Object[]{ arr, System.currentTimeMillis() });
        }
    }

    /** 相关推荐（播放页下方）。字段：bvid/title/pic/owner.name/duration/stat.view */
    public static JSONArray related(String bvid) throws Exception {
        JSONArray hit = relatedCached(bvid);
        if (hit != null) return hit;
        initFinger();
        JSONObject r = getJson(
                "https://api.bilibili.com/x/web-interface/archive/related?bvid=" + bvid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取相关推荐失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONArray arr = r.optJSONArray("data");
        putRecCache(bvid, arr);
        return arr;
    }

    /**
     * 首片预热：拉一小段文件头，读满立刻断开。
     *
     * 目的不是把数据下回来，而是把「DNS 解析 → TCP 握手 → TLS → CDN 回源」这
     * 300~900ms 的固定开销提前付掉。用户真点下去时，MediaPlayer 的第一个 Range
     * 请求直接命中已建立的边缘连接，起播感知快很多。
     *
     * 【v4.28.0】96KB → 192KB。
     *   1080P DASH 码率约 2Mbps ≈ 250KB/s，96KB 只够 0.4 秒 ——
     *   moov/moof 索引刚读完就断了，CDN 边缘那段缓存没被真正「捂热」。
     *   192KB 约合 0.8 秒，把第一个关键帧连同其后的数据一起压进边缘节点，
     *   正式播放的第一次 Range 基本能整段命中边缘缓存。
     *   代价 192KB/条，仍只对「列表第一条 + 竖屏下一条」使用。
     */
    public static void warmFirstChunk(final String url) {
        if (url == null || url.isEmpty()) return;
        WARM_POOL.execute(new Runnable() { @Override public void run() {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Referer", REFERER);
                c.setRequestProperty("Range", "bytes=0-196607");   // 192KB
                StringBuilder ck = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : COOKIES.entrySet()) {
                    if (ck.length() > 0) ck.append("; ");
                    ck.append(e.getKey()).append('=').append(e.getValue());
                }
                if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());
                int code = c.getResponseCode();
                if (code != 200 && code != 206) return;
                java.io.InputStream in = c.getInputStream();
                byte[] buf = new byte[32 * 1024];
                int left = 192 * 1024;
                while (left > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n <= 0) break;
                    left -= n;
                }
                try { in.close(); } catch (Throwable ignore) { }
            } catch (Throwable ignore) {
            } finally {
                if (c != null) { try { c.disconnect(); } catch (Throwable ignore) { } }
            }
        } });
    }

    /**
     * 从 playurl 结果里取首选视频轨地址做首片预热（静默失败）。
     *
     * 【v4.27.0】新增 targetH 参数：预热必须和「用户实际会播的那条轨」一致。
     * 旧版固定取 dash.video[0]（码率最高那条，通常是 4K），
     * 但用户设置的画质往往只有 1080P —— 正式播放用的是另一条 URL、
     * 另一个 CDN host，预先握好的手、暖好的 TLS 连接全都对不上，
     * 等于白预热（还白占了带宽）。
     * 现在按目标分辨率用 pickVideo 选轨，和正式播放走完全同一条判断逻辑。
     * targetH <= 0 时退回旧行为（取最高码率）。
     */
    public static void warmFirstChunkOf(final JSONObject playData) {
        warmFirstChunkOf(playData, 0);
    }

    public static void warmFirstChunkOf(final JSONObject playData, final int targetH) {
        try {
            String u = "";
            if (targetH > 0) {
                // 与正式播放同一条选轨逻辑（pickVideo 内部已做编解码可播性/硬解/带宽筛选）
                try {
                    String[] vs = pickVideo(playData, targetH);
                    if (vs != null && vs.length > 0) u = vs[0];
                } catch (Throwable ignore) { }
            }
            if (u.isEmpty()) {
                JSONObject dash = playData.optJSONObject("dash");
                JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
                if (vs == null || vs.length() == 0) return;
                // dash.video 按码率降序返回，取最高那条就是用户默认会播的那条
                u = vs.getJSONObject(0).optString("baseUrl", "");
            }
            if (u.isEmpty()) return;
            warmFirstChunk(u);
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.27.0】预热**音频轨**的首片。
     *
     * 为什么必须单独预热：DASH 是音视频分离的两条独立 HTTP 流，
     * 正式播放时音频是在画面首帧之后才 prepare 的（startAudioDeferred），
     * 而这条流此前完全没有预热 → 要重新 DNS、重新 TCP+TLS 握手、
     * 再竞速一次 CDN 线路。用户会听到「画面出来了但声音迟到半秒」。
     * 现在提前把这 96KB 拉下来，音频 prepare 几乎零等待，音画同步也就更准。
     */
    public static void warmAudioChunkOf(final JSONObject playData) {
        try {
            JSONObject dash = playData.optJSONObject("dash");
            JSONArray as = dash != null ? dash.optJSONArray("audio") : null;
            if (as == null || as.length() == 0) return;
            // 取码率最高的那条音轨（和 pickAudio 的取舍一致）
            int best = 0;
            long bestBw = -1L;
            for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                if (a == null) continue;
                long bw = a.optLong("bandwidth", 0);
                if (bw > bestBw) { bestBw = bw; best = i; }
            }
            String u = as.getJSONObject(best).optString("baseUrl", "");
            if (u.isEmpty()) return;
            warmFirstChunk(u);
        } catch (Throwable ignore) { }
    }

    /**
     * 我的关注列表（需登录）。字段：mid/uname/face/sign
     * 关键：vmid 必须传，且新版必须 WBI 签名，否则一律 -400/-352（这就是旧版关注空白的根因）。
     * 签名链失败时再尝试无签名旧接口。
     */
    public static JSONArray followings() throws Exception {
        initFinger();
        final String uid = loggedInUid();
        if (uid == null || uid.isEmpty()) throw new IOException("未登录");

        // 主链：WBI 签名
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("vmid", uid);
            p.put("pn", "1");
            p.put("ps", "50");
            p.put("order", "desc");
            p.put("order_type", "attention");
            String q = Wbi.sign(p, navMixinKey());
            JSONObject r = getJson("https://api.bilibili.com/x/relation/followings?" + q,
                    "https://space.bilibili.com/" + uid + "/fans/follow");
            if (r.optInt("code", -1) == 0) {
                JSONArray list = r.getJSONObject("data").optJSONArray("list");
                if (list != null) return list;
            }
        } catch (Throwable ignore) { }

        // 兜底：不带签名（少数风控宽松场景仍可用）
        JSONObject r2 = getJson("https://api.bilibili.com/x/relation/followings?vmid=" + uid
                + "&pn=1&ps=50&order=desc&order_type=attention",
                "https://space.bilibili.com/" + uid + "/fans/follow");
        if (r2.optInt("code", -1) != 0) {
            throw new IOException("获取关注列表失败：" + r2.optString("message", "?")
                    + " (code=" + r2.optInt("code") + ")");
        }
        return r2.getJSONObject("data").optJSONArray("list");
    }

    // ------------------------------------------------------------ 个人主页

    /** 当前登录用户信息（nav）。未登录抛 IOException("未登录") */
    public static JSONObject navInfo() throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/nav");
        int code = r.optInt("code", -1);
        JSONObject d = r.optJSONObject("data");
        if (code != 0 || d == null || !d.optBoolean("isLogin", false)) {
            throw new IOException("未登录");
        }
        return d;
    }

    /** 关注数 / 粉丝数（无需登录） */
    public static JSONObject relationStat(String mid) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/relation/stat?vmid=" + mid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取关注数据失败：" + r.optString("message", "?"));
        }
        return r.getJSONObject("data");
    }


    /** 收藏夹列表（需登录，看自己的）。字段：id / title / media_count */
    public static JSONArray favFolders(String mid) throws Exception {
        initFinger();
        JSONObject r = getJson(
                "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=" + mid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取收藏夹失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONArray arr = r.optJSONObject("data") != null
                ? r.getJSONObject("data").optJSONArray("list") : null;
        return arr == null ? new JSONArray() : arr;
    }

    /** 收藏夹内容（字段：bvid/title/cover/duration/upper.name） */
    public static JSONArray favItems(long mediaId) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/v3/fav/resource/list?media_id="
                + mediaId + "&pn=1&ps=30&order=mtime&type=0&platform=web");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取收藏内容失败：" + r.optString("message", "?"));
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray arr = d != null ? d.optJSONArray("medias") : null;
        return arr == null ? new JSONArray() : arr;
    }

    /** 稍后再看（需登录）。字段：bvid/title/pic/duration/owner */
    public static JSONArray toView() throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/v2/history/toview");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取稍后再看失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray arr = d != null ? d.optJSONArray("list") : null;
        return arr == null ? new JSONArray() : arr;
    }

    /** passport 登录接口的来源页：passport 域会校验 Referer，用首页 referer 易被判跨站 */
    static final String REFERER_PASSPORT = "https://passport.bilibili.com/login";

    /**
     * 【v4.15.6】二维码登录域名：主域 passport.bilibili.com，备用域 passport.biligame.com。
     * 两者 CDN 线路完全不同（IP 段不重叠）。部分网络环境下主域 DNS 解析异常或被拦截，
     * 导致二维码永远生成不出来（表现为一直停在「正在生成二维码…」）；备用域可完整兜底。
     * 生成成功后记住可用线路，轮询优先走同一线路。
     */
    static volatile String QR_HOST = "passport.bilibili.com";

    /** 【v4.15.10】云端兜底通道：与网页版共用同一套「已验证可用」的二维码接口。
     *  部分网络下 App 直连 passport 域长时间无响应（表现一直停在「正在生成二维码…」），
     *  此时改由服务端生成，App 只负责展示与轮询，绕开本机到 passport 的链路问题。 */
    static final String QR_PROXY = "https://a6126877489cec9b2.app.workbuddy.host/api";
    static final String QR_PROXY_SC = "KCUG7PBQSO";
    static volatile boolean qrViaProxy = false;

    /** 本机到 passport 域是否已被判定不通：记住后下次直接走云端，避免每次干等 */
    static volatile boolean qrDirectBlocked = false;

    /** 走云端生成二维码，成功返回 data，失败返回 null。
     *  【v4.15.12】超时收紧到 6s/8s：云端实测 0.5s 出码，8 秒还不回就是本机到云端也不通，立刻放弃转直连。 */
    static JSONObject tryProxyGen() {
        try {
            JSONObject r = getJson(QR_PROXY + "?action=qr_gen&sharecode=" + QR_PROXY_SC,
                    null, 4000, 5000);
            JSONObject d = r.optJSONObject("data");
            if (r.optInt("code", -1) == 0 && d != null && !d.optString("qrcode_key", "").isEmpty())
                return d;
        } catch (Throwable ignore) { }
        return null;
    }

    /** 最近一次生成的三路线路诊断（登录页直接显示，一眼看出哪条通、哪条挂） */
    public static volatile String lastQrDiag = "";

    static void addDiag(java.util.concurrent.atomic.AtomicReference<String> ref, String s) {
        for (; ; ) {
            String old = ref.get();
            String nv = old.isEmpty() ? s : old + " · " + s;
            if (ref.compareAndSet(old, nv)) return;
        }
    }

    /** 二维码登录：生成（返回 data 节点）。
     *  【v4.15.13】三路并行竞速：云端 / passport 主域 / passport 备域同时发请求，
     *  谁先成功用谁（9 秒总窗口）。旧版是串行排队，只要有一条挂起，
     *  用户就要等它超时完才轮到下一条 —— 这就是「一直停在正在获取二维码」的来源。 */
    public static JSONObject qrcodeGenerate() throws Exception {
        qrViaProxy = false;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);
        final java.util.concurrent.atomic.AtomicReference<JSONObject> win =
                new java.util.concurrent.atomic.AtomicReference<JSONObject>();
        final java.util.concurrent.atomic.AtomicReference<String> winSrc =
                new java.util.concurrent.atomic.AtomicReference<String>("");
        final java.util.concurrent.atomic.AtomicReference<String> diag =
                new java.util.concurrent.atomic.AtomicReference<String>("");
        final String[] hosts = {"passport.bilibili.com", "passport.biligame.com"};

        // 线路 1：云端生成（服务端到 B 站 0.07s）
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    JSONObject d = tryProxyGen();
                    long ms = System.currentTimeMillis() - t0;
                    if (d != null) {
                        if (win.compareAndSet(null, d)) winSrc.set("proxy");
                        addDiag(diag, "云端✓" + ms + "ms");
                    } else addDiag(diag, "云端✗" + ms + "ms");
                } catch (Throwable e) {
                    addDiag(diag, "云端✗" + (System.currentTimeMillis() - t0) + "ms");
                } finally { latch.countDown(); }
            }
        }, "bxq-qr-proxy").start();

        // 线路 2/3：直连双域（两个 CDN 线路不同）
        for (final String h : hosts) {
            new Thread(new Runnable() {
                @Override public void run() {
                    long t0 = System.currentTimeMillis();
                    String tag = h.replace("passport.", "").replace(".bilibili.com", "");
                    try {
                        JSONObject r = getJson("https://" + h + "/x/passport-login/web/qrcode/generate",
                                REFERER_PASSPORT, 4000, 5000);
                        long ms = System.currentTimeMillis() - t0;
                        JSONObject d = r.optJSONObject("data");
                        if (r.optInt("code", -1) == 0 && d != null
                                && !d.optString("qrcode_key", "").isEmpty()) {
                            if (win.compareAndSet(null, d)) { winSrc.set(h); QR_HOST = h; }
                            addDiag(diag, tag + "✓" + ms + "ms");
                        } else {
                            addDiag(diag, tag + "✗" + ms + "ms");
                        }
                    } catch (Throwable e) {
                        addDiag(diag, tag + "✗" + (System.currentTimeMillis() - t0) + "ms");
                    } finally { latch.countDown(); }
                }
            }, "bxq-qr-" + h).start();
        }

        try { latch.await(6, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignore) { }
        lastQrDiag = diag.get();
        JSONObject got = win.get();
        if (got != null) {
            qrViaProxy = "proxy".equals(winSrc.get());
            qrDirectBlocked = !qrViaProxy;
            return got;
        }
        throw new IOException("三路通道均无响应：" + (lastQrDiag.isEmpty() ? "未知" : lastQrDiag));
    }

    /** 二维码登录：轮询。code=0 已确认；86038 过期；86090 已扫码待确认；86101 未扫描 */
    public static JSONObject qrcodePoll(String qrcodeKey) throws Exception {
        // 走云端生成的码，必须走云端轮询（会话与服务端绑定）
        if (qrViaProxy) {
            return getJson(QR_PROXY + "?action=qr_poll&key="
                    + java.net.URLEncoder.encode(qrcodeKey, "UTF-8")
                    + "&sharecode=" + QR_PROXY_SC, null, 10000, 20000);
        }
        Exception last = null;
        String[] hosts = "passport.biligame.com".equals(QR_HOST)
                ? new String[]{"passport.biligame.com", "passport.bilibili.com"}
                : new String[]{"passport.bilibili.com", "passport.biligame.com"};
        for (String h : hosts) {
            try {
                JSONObject r = getJson("https://" + h
                        + "/x/passport-login/web/qrcode/poll?qrcode_key="
                        + java.net.URLEncoder.encode(qrcodeKey, "UTF-8"),
                        REFERER_PASSPORT, 8000, 12000);
                QR_HOST = h;
                return r;
            } catch (Exception e) {
                last = e;
            }
        }
        throw (last != null) ? last : new IOException("轮询失败：所有线路均不可达");
    }

    // ------------------------------------------------------------ 扫码登录


    /**
     * 统一取最佳音频源 URL：
     *  1) DASH 音轨（登录后可得 320k/Hi-Res）：取码率最高的一条
     *  2) 回退 durl 合一流（mp4，未登录常见）：取第一段
     * 返回 [0]=url，[1]="dash" 或 "durl"
     */
    public static String[] bestAudioSource(JSONObject playData) throws Exception {
        // DASH 优先
        if (playData.has("dash") && !playData.isNull("dash")) {
            JSONObject dash = playData.getJSONObject("dash");
            if (dash.has("audio") && !dash.isNull("audio")
                    && dash.getJSONArray("audio").length() > 0) {
                JSONArray audios = dash.getJSONArray("audio");
                int best = 0; long bestBw = -1;
                for (int i = 0; i < audios.length(); i++) {
                    long bw = audios.getJSONObject(i).optLong("bandwidth", 0);
                    if (bw > bestBw) { bestBw = bw; best = i; }
                }
                return new String[]{audios.getJSONObject(best).getString("baseUrl"), "dash"};
            }
        }
        // durl 合一流（mp4/flv，音视频合一条流）
        if (playData.has("durl") && !playData.isNull("durl")) {
            JSONArray durl = playData.getJSONArray("durl");
            if (durl.length() > 0) {
                String u = durl.getJSONObject(0).optString("url");
                if (!u.isEmpty()) return new String[]{u, "durl"};
            }
        }
        throw new Exception("接口未返回任何音频数据（dash 与 durl 均为空）");
    }

    /** 取 DASH 视频源（最高可用，最高 1080p）。无 DASH 视频时抛异常 */
    public static String bestVideoSource(JSONObject playData) throws Exception {
        if (playData.has("dash") && !playData.isNull("dash")) {
            JSONObject dash = playData.getJSONObject("dash");
            if (dash.has("video") && !dash.isNull("video")
                    && dash.getJSONArray("video").length() > 0) {
                JSONArray vs = dash.getJSONArray("video");
                int best = -1;
                long bestBw = -1;
                for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.getJSONObject(i);
                    // 【v4.12 画质解锁】去掉 id<=80 的上限：4K(120)/HDR(125)/杜比(126) 等
                    // 会员/高码率轨道只要服务端下发了就直接选带宽最高的一条
                    long bw = v.optLong("bandwidth", 0);
                    if (bw > bestBw) { bestBw = bw; best = i; }
                }
                if (best >= 0) {
                    JSONObject v = vs.getJSONObject(best);
                    String u = v.optString("baseUrl", "");
                    if (u.isEmpty() && v.optJSONArray("backupUrl") != null
                            && v.getJSONArray("backupUrl").length() > 0) {
                        u = v.getJSONArray("backupUrl").getString(0);
                    }
                    if (!u.isEmpty()) return u;
                }
            }
        }
        throw new IOException("无 DASH 视频流（将回退合一流）");
    }


    // ------------------------------------------------------------ 清晰度选流

    /**
     * 按目标高度挑 DASH 视频流。
     * 返回 {url, height, qn, label}；targetH<=0 表示「最高可用」。
     * 编码优先 AVC（硬解兼容性最好），其次 HEVC，最后 AV1 —— AV1 在多数手机上只能软解。
     */
    /** 编码 mime 支持缓存（按 mime 只查一次系统解码器列表） */
    static final java.util.HashMap<String, Boolean> CODEC_SUPPORT = new java.util.HashMap<String, Boolean>();
    /** 【v4.17.0】编码 mime → 本机是否有硬件解码器 */
    static final java.util.HashMap<String, Boolean> HW_CODEC = new java.util.HashMap<String, Boolean>();

    /**
     * 【v4.5 关键修复】本机系统 MediaPlayer 是否能解这种编码。
     * B 站 fnval=4048 会返回 AV1(av01)/HEVC(hvc) 轨道，而不少机型（尤其非旗舰）系统解码器
     * 不支持 AV1 —— 选中后 VideoView/MediaPlayer 一直准备不出来，永远卡在「正在加载喵~」。
     * 这里按 mime 查系统解码器白名单，把本机解不了的轨道直接排除；H.264(avc) 全机型通吃。
     * 查询本身异常时一律放行（宁可让上层看门狗回退，也不误杀能播的轨道）。
     */
    static boolean codecPlayable(String codecs) {
        if (codecs == null || codecs.isEmpty()) return true;
        String mime;
        if (codecs.startsWith("avc")) mime = "video/avc";
        else if (codecs.startsWith("hvc") || codecs.startsWith("hev")) mime = "video/hevc"; // B站 HEVC 常见 hev1.*
        else if (codecs.startsWith("av01")) mime = "video/av01";
        else if (codecs.startsWith("vp09")) mime = "video/x-vnd.on2.vp9";
        else if (codecs.startsWith("mp4v")) mime = "video/mp4v-es";
        else return true; // 未知编码放行，交给播放器/看门狗兜底
        synchronized (CODEC_SUPPORT) {
            Boolean cached = CODEC_SUPPORT.get(mime);
            if (cached != null) return cached;
        }
        boolean ok = true;
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
            ok = false;
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                if (info == null || info.isEncoder()) continue;
                String[] types = info.getSupportedTypes();
                if (types == null) continue;
                for (String t : types) {
                    if (t != null && t.equalsIgnoreCase(mime)) { ok = true; break; }
                }
                if (ok) break;
            }
        } catch (Throwable e) {
            ok = true; // 查询失败 → 放行
        }
        synchronized (CODEC_SUPPORT) { CODEC_SUPPORT.put(mime, ok); }
        return ok;
    }

    /**
     * 【v4.17.0 GPU 优先级】某编码在本机是否有「硬件解码器」。
     *
     * 背景：codecPlayable() 用 MediaCodecList.ALL_CODECS 查，得到的结果里
     * 包含了 Google/OEM 的**软件**解码器（OMX.google.*、c2.android.* 等）。
     * 于是「本机能解 HEVC」经常只是「能软解」——真播起来 CPU 飙高、掉帧、
     * 首帧从 2 秒拖到 8 秒，GPU 完全闲着。
     * 这里把软件解码器剔除，只认真正的硬件解码器：
     *  · API 29+ 直接用 MediaCodecInfo.isHardwareAccelerated()；
     *  · 低版本按解码器名字判断（软件解码器都有固定的厂商前缀）。
     * 用途：同分辨率有多条编码轨道时，优先挑硬解那条。
     */
    static boolean hasHwDecoder(String codecs) {
        if (codecs == null || codecs.isEmpty()) return false;
        String mime;
        if (codecs.startsWith("avc")) mime = "video/avc";
        else if (codecs.startsWith("hvc") || codecs.startsWith("hev")) mime = "video/hevc";
        else if (codecs.startsWith("av01")) mime = "video/av01";
        else if (codecs.startsWith("vp09")) mime = "video/x-vnd.on2.vp9";
        else if (codecs.startsWith("mp4v")) mime = "video/mp4v-es";
        else return false;
        synchronized (HW_CODEC) {
            Boolean cached = HW_CODEC.get(mime);
            if (cached != null) return cached;
        }
        boolean hw = false;
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                if (info == null || info.isEncoder()) continue;
                String[] types = info.getSupportedTypes();
                if (types == null) continue;
                boolean hit = false;
                for (String t : types) {
                    if (t != null && t.equalsIgnoreCase(mime)) { hit = true; break; }
                }
                if (!hit) continue;
                if (isHardwareCodec(info)) { hw = true; break; }
            }
        } catch (Throwable e) {
            hw = false;   // 查不到就当没有硬解，交给上层按兼容性排序
        }
        synchronized (HW_CODEC) { HW_CODEC.put(mime, hw); }
        return hw;
    }

    /** 解码器是否硬件加速 */
    static boolean isHardwareCodec(android.media.MediaCodecInfo info) {
        // API 29+ 有官方判定接口，但本项目用 API22 的 android.jar 编译，
        // 直接引用会编译不过，这里走反射（运行时存在才用）。
        try {
            java.lang.reflect.Method m = info.getClass().getMethod("isHardwareAccelerated");
            Object r = m.invoke(info);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignore) { }
        try {
            String name = info.getName();
            if (name == null) return false;
            String n = name.toLowerCase();
            // 软件解码器的固定前缀：Google 软解、Android 平台软解、通用软解
            if (n.startsWith("omx.google.") || n.startsWith("omx.software.")
                    || n.startsWith("c2.android.") || n.startsWith("c2.software.")) {
                return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 【v4.9】解码能力清单

    /**
     * 解析某个视频的「解码能力清单」：该视频在 DASH 里提供了哪些编码轨、
     * 各自多高、以及本机能不能解。
     *
     * 【为什么需要这个】B 站同一部视频会同时提供 AVC / HEVC / AV1 多条轨道，
     * 不同机型能解的编码不同（很多中低端机没有 AV1 硬解）。
     * 用户看到的「4K 变 1080P」「一直卡加载」往往不是网络问题，而是
     * 选了本机解不了的编码。把这份清单显式展示出来，
     * 用户就能明白「这台机器最高能到哪一档」，而不是以为软件有问题。
     *
     * 返回 JSONArray，每项：
     *   {codec:"AVC", height:1080, playable:true, label:"1080P · H.264"}
     */
    public static JSONArray codecAbility(JSONObject playData) {
        JSONArray out = new JSONArray();
        if (playData == null) return out;
        try {
            JSONObject dash = playData.optJSONObject("dash");
            JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
            if (vs == null || vs.length() == 0) return out;
            // 按 (编码, 高度) 去重：同一条轨会有多个 segment，取最高的一个即可
            java.util.LinkedHashMap<String, JSONObject> uniq =
                    new java.util.LinkedHashMap<String, JSONObject>();
            for (int i = 0; i < vs.length(); i++) {
                JSONObject v = vs.optJSONObject(i);
                if (v == null) continue;
                int hh = v.optInt("height", 0);
                int ww = v.optInt("width", 0);
                // 【v4.12】存短边：竖屏 1080x1920 归一成 1080，档位名才不会错标成 2K
                int side = (ww > 0 && hh > 0) ? Math.min(ww, hh) : hh;
                if (side <= 0) side = v.optInt("id", 0);
                String codecs = v.optString("codecs", "");
                String fam = codecFamily(codecs);
                String key = fam + "|" + side;
                if (!uniq.containsKey(key)) {
                    JSONObject o = new JSONObject();
                    o.put("codec", fam);
                    o.put("height", side);
                    o.put("playable", codecPlayable(codecs));
                    uniq.put(key, o);
                }
            }
            // 按高度降序排列，同高度 AVC 优先
            ArrayList<JSONObject> list = new ArrayList<JSONObject>(uniq.values());
            Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int ha = a.optInt("height", 0), hb = b.optInt("height", 0);
                    if (ha != hb) return hb - ha;
                    String ca = a.optString("codec", ""), cb = b.optString("codec", "");
                    return codecRankOf(ca) - codecRankOf(cb);
                }
            });
            for (JSONObject o : list) {
                o.put("label", qualityLabel(o.optInt("height", 0)) + " · " + codecFullName(o.optString("codec", "")));
                out.put(o);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** 编码族简称：AVC / HEVC / AV1 / VP9 / 未知 */
    static String codecFamily(String codecs) {
        if (codecs == null || codecs.isEmpty()) return "未知";
        if (codecs.startsWith("avc")) return "AVC";
        if (codecs.startsWith("hvc") || codecs.startsWith("hev")) return "HEVC";
        if (codecs.startsWith("av01")) return "AV1";
        if (codecs.startsWith("vp09") || codecs.startsWith("vp9")) return "VP9";
        if (codecs.startsWith("mp4v")) return "MPEG4";
        return "未知";
    }

    /** 编码族的中文全称 */
    static String codecFullName(String fam) {
        if ("AVC".equals(fam)) return "H.264";
        if ("HEVC".equals(fam)) return "H.265";
        if ("AV1".equals(fam)) return "AV1";
        if ("VP9".equals(fam)) return "VP9";
        if ("MPEG4".equals(fam)) return "MPEG-4";
        return fam;
    }

    /** 同高度时的编码优先级：AVC > HEVC > AV1（兼容性由高到低） */
    static int codecRankOf(String fam) {
        if ("AVC".equals(fam)) return 0;
        if ("HEVC".equals(fam)) return 1;
        if ("AV1".equals(fam)) return 2;
        return 3;
    }


    /**
     * 【v4.20.0】从 playurl 的 DASH 轨道里读画面宽高比。
     *
     * 列表页可能没带 aspect（关注流 / 搜索 / 动态 / 深链进入），旧版只能等 view 接口
     * 才知道是竖屏 —— 那意味着「首帧已经出画」之后才重排整页，竖屏视频因此明显慢半拍。
     * playurl 在 setVideoPath 之前就返回，轨道宽高此刻已经在手里，用它判定最快。
     */
    public static float videoAspectOf(JSONObject playData) {
        try {
            JSONObject dash = playData.optJSONObject("dash");
            JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
            if (vs != null) {
                for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.optJSONObject(i);
                    if (v == null) continue;
                    int w = v.optInt("width", 0);
                    int h = v.optInt("height", 0);
                    if (w > 0 && h > 0) return w / (float) h;
                }
            }
        } catch (Throwable ignore) { }
        return 0f;
    }

    public static String[] pickVideo(JSONObject playData, int targetH) throws Exception {
        JSONObject dash = playData.optJSONObject("dash");
        JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
        if (vs == null || vs.length() == 0) throw new IOException("无 DASH 视频流");

        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        int bestSide = 0;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v == null) continue;
            int h = v.optInt("height", 0);
            int w = v.optInt("width", 0);
            // 【v4.12 竖屏修复】竖屏视频（如 1080x1920）的 height 是长边，
            // 直接拿 height 和屏幕高度比会把它误判成「2K 超出目标档」→ 被压到 720P，
            // 甚至选到本机解不动的轨道卡在加载。改用短边 min(w,h) 归一，
            // 竖屏 1080x1920 与横屏 1920x1080 等价，都是「1080P」档。
            int side = (w > 0 && h > 0) ? Math.min(w, h) : h;
            if (side <= 0) side = v.optInt("id", 0);   // 兜底：没有宽高字段时用 qn 粗判
            String codecs = v.optString("codecs", "");
            // 【v4.5】跳过本机解码器不支持的编码（AV1/HEVC 在部分机型上会导致永久卡加载）
            if (!codecPlayable(codecs)) continue;
            boolean hevc = codecs.startsWith("hvc") || codecs.startsWith("hev");
            // 【v4.17.0 GPU 优先级】同分辨率下优先挑「有硬件解码器」的那条轨道。
            // 家族权重 AVC(3) > HEVC(2) > AV1(1)；有硬解再 +2。
            // 结果：硬解 AVC(5) > 硬解 HEVC(4) > 软解 AVC(3) = 硬解 AV1(3) > 软解 HEVC(2)。
            // 分辨率权重远大于此项（side*100），所以只影响同档位内的取舍，
            // 不会把用户选的 1080P 降成 720P。
            int codecRank = codecs.startsWith("avc") ? 3 : hevc ? 2 : 1;
            boolean hw = hasHwDecoder(codecs);
            if (hw) codecRank += 2;
            // 【v4.18.0】软解 1080P 以上直接重罚：4K/2K 走软解必然掉帧、甚至首帧永远出不来
            // （用户反馈的「有声音没画面」多属此类）。罚分跨过一个完整档位，
            // 宁可给硬解的 1080P，也不给软解的 4K。只有全片都没有可硬解档位时才会退回它。
            int softPenalty = (!hw && side > 1080) ? 300000 : 0;
            // 【v4.13.5】带宽感知 Tie-break：同档画质内优先选码率不超过预估带宽 1.4 倍的轨道。
            // 权重远小于档位差，不会把用户选的 4K 降级成 720P，仅避免「同是 1080P 却选了
            // 链路扛不住的超高码率轨」导致起播卡顿/下载缓慢（横屏视频更常见）。
            long bw = v.optLong("bandwidth", 0);
            long budget = estBandwidthBps * 14L / 10L;
            int bwRank = (bw > 0 && bw <= budget) ? 2 : ((bw > 0 && bw <= budget * 2) ? 1 : 0);

            int score;
            if (targetH <= 0) {
                score = side * 100 + bwRank * 10 + codecRank;              // 最高画质优先
            } else if (side <= targetH) {
                score = 1000000 + side * 100 + bwRank * 10 + codecRank;    // 不超过目标档，取最高
            } else {
                score = side * 100 + bwRank * 10 + codecRank - 10000000;   // 超出目标档，尽量取最接近的
            }
            score -= softPenalty;
            if (score > bestScore) { bestScore = score; best = i; bestSide = side; }
        }
        if (best < 0) throw new IOException("本机不支持该视频的高清编码，已切换兼容播放");

        JSONObject v = vs.getJSONObject(best);
        String u = v.optString("baseUrl", "");
        if (u.isEmpty() && v.optJSONArray("backupUrl") != null
                && v.getJSONArray("backupUrl").length() > 0) {
            u = v.getJSONArray("backupUrl").getString(0);
        }
        if (u.isEmpty()) throw new IOException("视频流地址为空");
        int h = v.optInt("height", v.optInt("id", 0));
        // 【v4.12】档位名按短边标注：竖屏 1080x1920 显示「1080P」而不是「2K」
        return new String[]{u, String.valueOf(h), String.valueOf(v.optInt("id", 0)),
                qualityLabel(bestSide)};
    }

    /**
     * 【v4.17.0 线路扩充】把一条 upos 直链换算成同路径的备用 CDN 域名。
     *
     * B 站 CDN 的备用节点（mirrorali / mirrorhw / mirrorcos / mirrorbd）与主节点
     * 路径、鉴权参数完全一致，只是调度机房不同。这里在本地做一次字符串替换，
     * 零网络开销就能多出 4 条候选线路 —— 代理层并行竞速，哪条快用哪条，
     * 主节点被风控 403 或握手慢时不再只能干等。
     */
    static java.util.List<String> mirrorUrls(String url) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (url == null || url.isEmpty()) return out;
        try {
            String h = new java.net.URL(url).getHost();
            if (h == null || h.isEmpty()) return out;
            if (!h.endsWith("bilivideo.com") && !h.endsWith("hdslb.com")) return out;
            String[] alts = {
                    "upos-sz-mirrorali.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com",
                    "upos-sz-mirrorcos.bilivideo.com", "upos-sz-mirrorbd.bilivideo.com"
            };
            int idx = url.indexOf(h);
            if (idx < 0) return out;
            for (String a : alts) {
                if (a.equals(h)) continue;
                out.add(url.substring(0, idx) + a + url.substring(idx + h.length()));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /**
     * 【v3.9 四线路 → v4.17.0 六线路】按 pickVideo 选中的地址，找出该视频流的主地址+所有 backupUrl。
     *
     * 【v4.17.0】旧版最多收 4 条，且只收「与 chosenUrl 完全同一条轨道」的地址；
     * 很多稿件的 backupUrl 只有 1~2 条，线路池太浅，一旦主节点被风控就频繁卡住。
     * 现在按三级扩充：
     * ① 同轨道的 baseUrl + backupUrl；
     * ② 同高度、同编码家族的其他轨道（不同码率版本，也是同一画质的合法来源）；
     * ③ 主地址换算出的备用 CDN 域名（零开销，必定可用）。
     */
    public static String[] videoLines(JSONObject playData, String chosenUrl, int targetH) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        if (chosenUrl != null && !chosenUrl.isEmpty()) out.add(chosenUrl);
        try {
            JSONObject dash = playData.optJSONObject("dash");
            JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
            int chosenH = 0;
            String chosenFam = "";
            if (vs != null) {
                // ① 精确匹配 chosenUrl 的那条轨道
                for (int i = 0; i < vs.length() && out.size() < 6; i++) {
                    JSONObject v = vs.optJSONObject(i);
                    if (v == null) continue;
                    String base = v.optString("baseUrl", "");
                    JSONArray bu = v.optJSONArray("backupUrl");
                    boolean match = (!base.isEmpty() && base.equals(chosenUrl))
                            || (bu != null && matchesAny(bu, chosenUrl));
                    if (!match) continue;
                    chosenH = v.optInt("height", 0);
                    chosenFam = codecFamily(v.optString("codecs", ""));
                    if (!base.isEmpty()) out.add(base);
                    if (bu != null) {
                        for (int k = 0; k < bu.length() && out.size() < 6; k++) {
                            String b = bu.optString(k, "");
                            if (!b.isEmpty()) out.add(b);
                        }
                    }
                }
                // ② 同高度 + 同编码家族的其他轨道（不同码率版本）
                if (chosenH > 0 && out.size() < 6) {
                    for (int i = 0; i < vs.length() && out.size() < 6; i++) {
                        JSONObject v = vs.optJSONObject(i);
                        if (v == null) continue;
                        if (v.optInt("height", 0) != chosenH) continue;
                        String fam = codecFamily(v.optString("codecs", ""));
                        if (!chosenFam.isEmpty() && !chosenFam.equals(fam)) continue;
                        if (!codecPlayable(v.optString("codecs", ""))) continue;
                        String base = v.optString("baseUrl", "");
                        if (!base.isEmpty()) out.add(base);
                        JSONArray bu = v.optJSONArray("backupUrl");
                        if (bu != null) {
                            for (int k = 0; k < bu.length() && out.size() < 6; k++) {
                                String b = bu.optString(k, "");
                                if (!b.isEmpty()) out.add(b);
                            }
                        }
                    }
                }
            }
            // ③ 备用 CDN 域名补足（零网络开销）
            if (out.size() < 6 && chosenUrl != null) {
                for (String m : mirrorUrls(chosenUrl)) {
                    if (out.size() >= 6) break;
                    out.add(m);
                }
            }
        } catch (Throwable ignore) { }
        if (out.isEmpty() && chosenUrl != null) return new String[]{ chosenUrl };
        return out.toArray(new String[out.size()]);
    }

    /**
     * 【v3.9 四线路 → v4.17.0 六线路】按 pickAudio 选中的地址，收集该音轨的主地址+backupUrl。
     */
    public static String[] audioLines(JSONObject playData, String chosenUrl) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        if (chosenUrl != null && !chosenUrl.isEmpty()) out.add(chosenUrl);
        try {
            JSONObject dash = playData.optJSONObject("dash");
            JSONArray as = dash != null ? dash.optJSONArray("audio") : null;
            if (as != null) {
                for (int i = 0; i < as.length() && out.size() < 6; i++) {
                    JSONObject a = as.optJSONObject(i);
                    if (a == null) continue;
                    String base = a.optString("baseUrl", "");
                    JSONArray bu = a.optJSONArray("backupUrl");
                    boolean match = (!base.isEmpty() && base.equals(chosenUrl))
                            || (bu != null && matchesAny(bu, chosenUrl));
                    if (!match) continue;
                    if (!base.isEmpty()) out.add(base);
                    if (bu != null) {
                        for (int k = 0; k < bu.length() && out.size() < 6; k++) {
                            String b = bu.optString(k, "");
                            if (!b.isEmpty()) out.add(b);
                        }
                    }
                }
            }
            if (out.size() < 6 && chosenUrl != null) {
                for (String m : mirrorUrls(chosenUrl)) {
                    if (out.size() >= 6) break;
                    out.add(m);
                }
            }
        } catch (Throwable ignore) { }
        if (out.isEmpty() && chosenUrl != null) return new String[]{ chosenUrl };
        return out.toArray(new String[out.size()]);
    }

    static boolean matchesAny(JSONArray arr, String url) {
        for (int i = 0; i < arr.length(); i++) {
            if (url.equals(arr.optString(i, ""))) return true;
        }
        return false;
    }

    /** 高度 → 界面标签 */
    public static String qualityLabel(int h) {
        if (h >= 2000) return "4K";
        if (h >= 1400) return "2K";
        if (h >= 1000) return "1080P";
        if (h >= 700) return "720P";
        if (h >= 400) return "480P";
        if (h > 0) return h + "P";
        return "标清";
    }

    /** 挑音频：优先 AAC（mp4a，封 MP4/硬解都稳），同编码取最高码率 */
    public static String[] pickAudio(JSONObject playData) throws Exception {
        JSONObject dash = playData.optJSONObject("dash");
        JSONArray as = dash != null ? dash.optJSONArray("audio") : null;
        if (as == null || as.length() == 0) throw new IOException("无 DASH 音轨");
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            String codecs = a.optString("codecs", "");
            boolean aac = codecs.startsWith("mp4a");
            int score = (aac ? 100000000 : 0) + a.optInt("bandwidth", 0);
            if (score > bestScore) { bestScore = score; best = a; }
        }
        if (best == null) throw new IOException("无可用音轨");
        String u = best.optString("baseUrl", "");
        if (u.isEmpty() && best.optJSONArray("backupUrl") != null
                && best.getJSONArray("backupUrl").length() > 0) {
            u = best.getJSONArray("backupUrl").getString(0);
        }
        if (u.isEmpty()) throw new IOException("音轨地址为空");
        return new String[]{u, String.valueOf(best.optInt("bandwidth", 0))};
    }

    /** 该稿件实际可选的最高画质高度（用于「自动」时告诉用户拿到了什么） */
    /** 轨道里的最高「档位」（短边口径：竖屏按宽度算）。用于 4K/2K 有无的判断。 */
    public static int maxVideoHeight(JSONObject playData) {
        JSONObject dash = playData.optJSONObject("dash");
        JSONArray vs = dash != null ? dash.optJSONArray("video") : null;
        int max = 0;
        if (vs != null) {
            for (int i = 0; i < vs.length(); i++) {
                JSONObject v = vs.optJSONObject(i);
                if (v == null) continue;
                int hh = v.optInt("height", 0), ww = v.optInt("width", 0);
                int side = (ww > 0 && hh > 0) ? Math.min(ww, hh) : hh;
                max = Math.max(max, side);
            }
        }
        return max;
    }

    /**
     * 取适合封进 MP4 的音轨：优先 AAC（codecs 含 mp4a），码率最高者。
     * FLAC / E-AC3（Hi-Res、杜比）无法写入 MP4，会让封装失败，故排除；
     * 无 AAC 时退回普通最佳音轨。
     */
    public static String bestMp4Audio(JSONObject playData) throws Exception {
        if (playData.has("dash") && !playData.isNull("dash")) {
            JSONObject dash = playData.getJSONObject("dash");
            JSONArray audios = dash.optJSONArray("audio");
            if (audios != null && audios.length() > 0) {
                String best = null;
                long bestBw = -1;
                for (int i = 0; i < audios.length(); i++) {
                    JSONObject a = audios.getJSONObject(i);
                    String codecs = a.optString("codecs", "");
                    String mime = a.optString("mimeType", "");
                    boolean aac = codecs.startsWith("mp4a") || mime.contains("mp4a");
                    if (!aac) continue;
                    long bw = a.optLong("bandwidth", 0);
                    if (bw > bestBw) {
                        bestBw = bw;
                        best = a.optString("baseUrl", "");
                        if (best.isEmpty() && a.optJSONArray("backupUrl") != null
                                && a.getJSONArray("backupUrl").length() > 0) {
                            best = a.getJSONArray("backupUrl").getString(0);
                        }
                    }
                }
                if (best != null && !best.isEmpty()) return best;
            }
        }
        String[] fb = bestAudioSource(playData);
        return fb[0];
    }

    // ------------------------------------------------------------ 动态 / 关注粉丝 / 用户空间

    /** 粉丝列表（需登录，看自己）。字段：mid/uname/face/sign */
    public static JSONArray followers(String mid, int pn) throws Exception {
        initFinger();
        if (mid == null || mid.isEmpty()) throw new IOException("未登录");
        String ref = "https://space.bilibili.com/" + mid + "/fans/follow";
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("vmid", mid);
            p.put("pn", String.valueOf(pn));
            p.put("ps", "50");
            p.put("order", "desc");
            p.put("order_type", "attention");
            String q = Wbi.sign(p, navMixinKey());
            JSONObject r = getJson("https://api.bilibili.com/x/relation/followers?" + q, ref);
            if (r.optInt("code", -1) == 0) {
                JSONArray list = r.optJSONObject("data") != null
                        ? r.getJSONObject("data").optJSONArray("list") : null;
                if (list != null) return list;
            }
        } catch (Throwable ignore) { }

        JSONObject r2 = getJson("https://api.bilibili.com/x/relation/followers?vmid=" + mid
                + "&pn=" + pn + "&ps=50&order=desc&order_type=attention", ref);
        if (r2.optInt("code", -1) != 0) {
            throw new IOException("获取粉丝列表失败：" + r2.optString("message", "?")
                    + " (code=" + r2.optInt("code") + ")");
        }
        JSONArray list = r2.optJSONObject("data") != null
                ? r2.getJSONObject("data").optJSONArray("list") : null;
        return list == null ? new JSONArray() : list;
    }

    /** 用户空间投稿视频（需 WBI 签名；字段：bvid/title/pic/play/length/created） */
    public static JSONArray userVideos(String mid, int pn) throws Exception {
        initFinger();
        String ref = "https://space.bilibili.com/" + mid + "/video";
        final String dmList = "[]";
        final String dmStr = "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ";
        final String dmCover = "QU5HTEUgKE5WSURJQSwgTlZJRElBIEdlRm9yY2UgR1RYIDEwNjAgNkdCIERpcmVjdDNEMTEgdnNfNV8wIHBzXzVfMCwgRDNEMTEpR29vZ2xlIEluYy4gKE5WSURJQS)";

        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("mid", mid);
            p.put("ps", "30");
            p.put("pn", String.valueOf(pn));
            p.put("order", "pubdate");
            p.put("platform", "web");
            p.put("web_location", "1550101");
            p.put("dm_img_list", dmList);
            p.put("dm_img_str", dmStr);
            p.put("dm_cover_img_str", dmCover);
            String q = Wbi.sign(p, navMixinKey());
            JSONObject r = getJson("https://api.bilibili.com/x/space/wbi/arc/search?" + q, ref);
            JSONArray v = vlistOf(r);
            if (v != null) return v;
        } catch (Throwable ignore) { }

        JSONObject r2 = getJson("https://api.bilibili.com/x/space/arc/search?mid=" + mid
                + "&ps=30&pn=" + pn + "&order=pubdate&platform=web&web_location=1550101", ref);
        JSONArray v2 = vlistOf(r2);
        if (v2 == null) {
            throw new IOException("获取投稿失败：" + (r2.optString("message", "").isEmpty()
                    ? "可能被风控，请稍后重试" : r2.optString("message"))
                    + " (code=" + r2.optInt("code") + ")");
        }
        return v2;
    }

    static JSONArray vlistOf(JSONObject r) {
        try {
            if (r == null || r.optInt("code", -1) != 0) return null;
            JSONObject d = r.optJSONObject("data");
            JSONObject list = d != null ? d.optJSONObject("list") : null;
            return list != null ? list.optJSONArray("vlist") : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 用户动态（第一页或翻页）。
     * 返回 data 节点：{items:[...], offset, has_more}
     */
    public static JSONObject dynamicFeed(String mid, String offset) throws Exception {
        initFinger();
        String ref = "https://space.bilibili.com/" + mid + "/dynamic";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("host_mid", mid);
        p.put("offset", offset == null ? "" : offset);
        p.put("timezone_offset", "-480");
        p.put("platform", "web");
        p.put("web_location", "333.1387");
        p.put("features", "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,"
                + "decorationCard,onlyfansAssetsV2,forwardListHidden,ugcDelete,onlyfansQaCard");
        String q = Wbi.sign(p, navMixinKey());
        JSONObject r = getJson(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?" + q, ref);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取动态失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        if (d == null) throw new IOException("动态为空");
        return d;
    }

    /**
     * 【v4.10.3】关注动态流（对应 B 站「关注」页）。
     * type = 0 全部 / 1 视频；offset 为空表示第一页。
     * 返回 data：{items:[...], offset, has_more}
     */
    public static JSONObject followDynamic(int type, String offset) throws Exception {
        initFinger();
        String ref = "https://t.bilibili.com/";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", type == 1 ? "video" : "all");
        p.put("page", "1");
        p.put("offset", offset == null ? "" : offset);
        p.put("update_baseline", "0");
        p.put("web_location", "333.999");
        p.put("features", "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,"
                + "decorationCard,onlyfansAssetsV2,forwardListHidden,ugcDelete");
        String q = Wbi.sign(p, navMixinKey());
        JSONObject r = getJson(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?" + q, ref);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取关注动态失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        if (d == null) throw new IOException("关注动态为空");
        return d;
    }

    /** 用户名片（头像/昵称/签名/粉丝数），无需登录 */
    public static JSONObject userCard(String mid) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/card?mid=" + mid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取用户资料失败：" + r.optString("message", "?"));
        }
        return r.getJSONObject("data");
    }

    // ------------------------------------------------------------ 弹幕

    /** 弹幕 XML（deflate 压缩，httpGet 已自动解压）。失败自动换备用域名 */
    public static String danmakuXml(long cid) throws IOException {
        initFinger();
        try {
            return httpGet("https://api.bilibili.com/x/v1/dm/list.so?oid=" + cid,
                    "https://www.bilibili.com/video/");
        } catch (IOException e) {
            return httpGet("https://comment.bilibili.com/" + cid + ".xml",
                    "https://www.bilibili.com/video/");
        }
    }

    /** 发送弹幕（需登录）。progress 单位毫秒 */
    public static void danmakuSend(String bvid, long cid, String msg, long progressMs)
            throws Exception {
        String csrf = COOKIES.get("bili_jct");
        if (csrf == null || csrf.isEmpty()) throw new IOException("请先登录后再发弹幕");
        if (msg == null || msg.trim().isEmpty()) throw new IOException("弹幕内容不能为空");
        String body = "type=1&oid=" + cid
                + "&msg=" + URLEncoder.encode(msg.trim(), "UTF-8")
                + "&bvid=" + bvid
                + "&progress=" + Math.max(0, progressMs)
                + "&mode=1&fontsize=25&color=16777215&pool=0&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/v2/dm/post", body,
                "https://www.bilibili.com/video/" + bvid);
        if (r.optInt("code", -1) != 0) {
            throw new IOException(r.optString("message", "发送失败")
                    + " (code=" + r.optInt("code") + ")");
        }
    }

    // ------------------------------------------------------------ 私信

    static final String IM_REF = "https://message.bilibili.com/";
    static String imDevId;

    static String devId() {
        if (imDevId == null) {
            // Web 端要求带上稳定的 dev_id，缺失会被 -400
            String uid = loggedInUid();
            imDevId = java.util.UUID.nameUUIDFromBytes(
                    ("bxq-" + (uid == null ? "guest" : uid)).getBytes()).toString().toUpperCase();
        }
        return imDevId;
    }

    static void requireLogin() throws IOException {
        String uid = loggedInUid();
        if (uid == null || uid.isEmpty()) throw new IOException("请先登录 B 站账号");
    }

    /**
     * 私信会话列表（需登录）。
     * 返回 data.session_list，元素含 talker_id / unread_count / last_msg{...} / top_ts。
     */
    public static JSONArray sessions() throws Exception {
        requireLogin();
        initFinger();
        String url = "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions"
                + "?session_type=1&group_id=0&build=0&mobi_app=web&size=50&end_seqno=";
        JSONObject r = getJson(url, IM_REF);
        int code = r.optInt("code", -1);
        if (code != 0) {
            throw new IOException("获取会话失败：" + r.optString("message", "?")
                    + " (code=" + code + ")");
        }
        JSONArray list = r.optJSONObject("data") != null
                ? r.getJSONObject("data").optJSONArray("session_list") : null;
        return list == null ? new JSONArray() : list;
    }

    /** 批量取用户名片（昵称/头像）。返回 map: uid -> {name, face} */
    public static JSONObject userCards(String uids) throws Exception {
        JSONObject map = new JSONObject();
        if (uids == null || uids.isEmpty()) return map;
        try {
            JSONObject r = getJson("https://api.vc.bilibili.com/account/v1/user/cards?uids="
                    + uids, IM_REF);
            if (r.optInt("code", -1) != 0) return map;
            JSONObject d = r.optJSONObject("data");
            if (d == null) return map;
            java.util.Iterator<String> it = d.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject u = d.optJSONObject(k);
                if (u == null) continue;
                JSONObject o = new JSONObject();
                o.put("name", u.optString("name", "用户" + k));
                o.put("face", u.optString("face", ""));
                map.put(k, o);
            }
        } catch (Throwable ignore) { }
        return map;
    }

    /**
     * 【v4.9.6】单个用户名片（公开接口，不需要 WBI / 登录）。
     * 私信的批量名片接口经常被风控（code!=0 或整个 data 为空），
     * 表现就是消息列表里 50 个会话全是灰头像、名字全变成「用户 xxxx」。
     * 这里用 web-interface/card 逐个回补，作为批量接口的兜底。
     *
     * @return {name, face}；失败返回 null（不要抛，调用方在循环里逐个补）
     */
    public static JSONObject card(long mid) {
        if (mid <= 0) return null;
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/web-interface/card?mid="
                    + mid + "&photo=false", "https://www.bilibili.com/");
            if (r.optInt("code", -1) != 0) return null;
            JSONObject d = r.optJSONObject("data");
            JSONObject c = d == null ? null : d.optJSONObject("card");
            if (c == null) return null;
            JSONObject o = new JSONObject();
            o.put("name", c.optString("name", ""));
            o.put("face", c.optString("face", ""));
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 拉取与某人的聊天记录（需登录）。
     * endSeqno>0 时向前翻页（取更早的消息），0 表示取最新一批。
     * 返回 data.messages（已按时间升序排好）。
     */
    public static JSONArray sessionMsgs(long talkerId, long endSeqno, int size) throws Exception {
        requireLogin();
        initFinger();
        String url = "https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs"
                + "?talker_id=" + talkerId + "&session_type=1&size=" + size
                + "&begin_seqno=&end_seqno=" + (endSeqno > 0 ? endSeqno : "");
        JSONObject r = getJson(url, IM_REF);
        int code = r.optInt("code", -1);
        if (code != 0) {
            throw new IOException("获取消息失败：" + r.optString("message", "?")
                    + " (code=" + code + ")");
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray msgs = d != null ? d.optJSONArray("messages") : null;
        if (msgs == null) return new JSONArray();
        // 接口返回顺序不固定，统一按 timestamp 升序
        List<JSONObject> tmp = new ArrayList<>();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null) tmp.add(m);
        }
        Collections.sort(tmp, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long ta = a.optLong("timestamp", 0), tb = b.optLong("timestamp", 0);
                return ta < tb ? -1 : (ta > tb ? 1 : 0);
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject m : tmp) out.put(m);
        return out;
    }

    /** 发送私信（需登录，msg_type=1 纯文本） */
    public static void sendMsg(long talkerId, String text) throws Exception {
        requireLogin();
        String csrf = COOKIES.get("bili_jct");
        if (csrf == null || csrf.isEmpty()) throw new IOException("登录态缺少 bili_jct，请重新登录");
        final String uid = loggedInUid();
        if (text == null || text.trim().isEmpty()) throw new IOException("消息不能为空");
        String content = new JSONObject().put("content", text.trim()).toString();
        String body = "msg%5Bsender_uid%5D=" + uid
                + "&msg%5Breceiver_id%5D=" + talkerId
                + "&msg%5Breceiver_type%5D=1"
                + "&msg%5Bmsg_type%5D=1"
                + "&msg%5Bdev_id%5D=" + URLEncoder.encode(devId(), "UTF-8")
                + "&msg%5Btimestamp%5D=" + (System.currentTimeMillis() / 1000L)
                + "&msg%5Bcontent%5D=" + URLEncoder.encode(content, "UTF-8")
                + "&csrf=" + csrf + "&csrf_token=" + csrf;
        JSONObject r = postForm("https://api.vc.bilibili.com/web_im/v1/web_im/send_msg",
                body, IM_REF);
        if (r.optInt("code", -1) != 0) {
            throw new IOException("发送失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
    }

    /** 标记单个会话已读：x/v2/msg/feed/read（session_type=1 私聊） */
    public static void markSessionRead(long talkerId) throws Exception {
        String csrf = csrfOrThrow("标记已读");
        String body = "talker_id=" + talkerId
                + "&session_type=1"
                + "&csrf=" + csrf + "&csrf_token=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/v2/msg/feed/read", body, IM_REF);
        // code 0 成功；部分账号该接口返回 -404/-403，视为无需处理
        if (r.optInt("code", -1) != 0
                && r.optInt("code") != -404 && r.optInt("code") != -403) {
            throw new IOException("标记已读失败：" + r.optString("message", "?"));
        }
    }

    /** 会话里最后一条消息的文字预览（处理文本/图片/视频分享等类型） */
    public static String previewOf(JSONObject lastMsg) {
        if (lastMsg == null) return "";
        int type = lastMsg.optInt("msg_type", 1);
        String raw = lastMsg.optString("content", "");
        try {
            if (type == 1) return new JSONObject(raw).optString("content", "");
            if (type == 2) return "[图片]";
            if (type == 5) return "[已撤回]";
            if (type == 7) {
                JSONObject c = new JSONObject(raw);
                String t = c.optString("title", "");
                return t.isEmpty() ? "[视频分享]" : "[视频] " + t;
            }
            if (type == 10) {
                JSONObject c = new JSONObject(raw);
                return c.optString("content", "[系统消息]");
            }
            if (type == 6) return "[表情]";
        } catch (Throwable ignore) { }
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }

    // ------------------------------------------------------------ 视频互动（点赞 / 投币 / 收藏 / 关注 / 评论）

    /** 登录态失效：-101 未登录 / -400 -403 请求被拒 / -412 风控。UI 层据此引导重新登录。 */
    public static class LoginExpired extends IOException {
        public LoginExpired(String msg) { super(msg); }
    }

    /** 写操作统一取 bili_jct，缺失说明登录态不完整 */
    static String csrfOrThrow(String action) throws IOException {
        String csrf = COOKIES.get("bili_jct");
        if (csrf == null || csrf.isEmpty()) throw new LoginExpired("登录态已失效，请到「我的」页重新登录");
        return csrf;
    }

    /**
     * 统一校验写操作业务码：0 成功；登录失效码抛 LoginExpired；其余抛可读错误。
     * 这样互动失败不会被当成成功，且登录过期能被上层识别并清理本地失效态。
     */
    static void checkWrite(JSONObject r, String action) throws IOException {
        int code = r.optInt("code", -1);
        if (code == 0) return;
        String msg = r.optString("message", "");
        if (code == -101 || code == -400 || code == -403 || code == -411) {
            throw new LoginExpired("登录已过期，请到「我的」页重新登录");
        }
        throw new IOException(action + "失败：" + (msg.isEmpty() ? "未知错误" : msg) + " (code=" + code + ")");
    }

    static String videoRef(String bvid) {
        return "https://www.bilibili.com/video/" + bvid;
    }

    /**
     * 视频详情（用 view 接口）：data 含 aid / cid / title / desc / pubdate / owner{mid,name,face}
     * / stat{view,danmaku,reply,favorite,coin,share,like} / pages[]。
     */
    public static JSONObject archiveMeta(String bvid) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid,
                videoRef(bvid));
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取视频信息失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        return r.getJSONObject("data");
    }

    /** 是否已点赞：-1 未登录 / 0 否 / 1 是（失败按未点赞处理，不打断播放） */
    public static int likeState(String bvid) {
        if (loggedInUid() == null) return -1;
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/web-interface/archive/has/like?bvid="
                    + bvid, videoRef(bvid));
            if (r.optInt("code", -1) == 0) return r.optInt("data", 0);
        } catch (Throwable ignore) { }
        return 0;
    }

    /** 已投币枚数：-1 未登录 / 0 未投 / 1~2 */
    public static int coinState(String bvid) {
        if (loggedInUid() == null) return -1;
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/web-interface/archive/coins?bvid="
                    + bvid, videoRef(bvid));
            if (r.optInt("code", -1) == 0) {
                JSONObject d = r.optJSONObject("data");
                if (d != null) return d.optInt("multiply", 0);
            }
        } catch (Throwable ignore) { }
        return 0;
    }

    /** 是否已关注：-1 未登录 / 0 未关注 / 1 已关注 / 2 互关 */
    public static int followState(long mid) {
        if (loggedInUid() == null || mid <= 0) return -1;
        try {
            JSONObject r = getJson("https://api.bilibili.com/x/relation?fid=" + mid,
                    "https://space.bilibili.com/" + mid);
            if (r.optInt("code", -1) == 0) {
                JSONObject d = r.optJSONObject("data");
                int attr = d == null ? 0 : d.optInt("attribute", 0);
                if (attr == 0) return 0;
                if (attr == 6) return 2;
                return attr == 2 ? 1 : 0;
            }
        } catch (Throwable ignore) { }
        return 0;
    }

    /** 点赞 / 取消点赞 */
    public static void setLike(String bvid, boolean on) throws Exception {
        String csrf = csrfOrThrow(on ? "点赞" : "取消点赞");
        String body = "bvid=" + bvid + "&like=" + (on ? 1 : 2) + "&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/web-interface/archive/like",
                body, videoRef(bvid));
        checkWrite(r, on ? "点赞" : "取消点赞");
    }

    /** 投币（n = 1 或 2），alsoLike 为 true 时同时点赞 */
    public static void addCoin(String bvid, int n, boolean alsoLike) throws Exception {
        String csrf = csrfOrThrow("投币");
        if (n < 1) n = 1;
        if (n > 2) n = 2;
        String body = "bvid=" + bvid + "&multiply=" + n
                + "&select_like=" + (alsoLike ? 1 : 0) + "&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/web-interface/coin/add",
                body, videoRef(bvid));
        checkWrite(r, "投币");
    }

    /** 收藏 / 取消收藏：addIds / delIds 为逗号分隔的收藏夹 id */
    public static void dealFav(long aid, String addIds, String delIds) throws Exception {
        String csrf = csrfOrThrow("收藏");
        String body = "rid=" + aid + "&type=2"
                + "&add_media_ids=" + (addIds == null ? "" : addIds)
                + "&del_media_ids=" + (delIds == null ? "" : delIds)
                + "&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/v3/fav/resource/deal",
                body, "https://www.bilibili.com/");
        checkWrite(r, "收藏");
    }

    /** 关注 / 取关 */
    public static void modifyFollow(long mid, boolean on) throws Exception {
        String csrf = csrfOrThrow(on ? "关注" : "取消关注");
        String body = "fid=" + mid + "&act=" + (on ? 1 : 2) + "&re_src=14&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/relation/modify",
                body, "https://space.bilibili.com/" + mid);
        checkWrite(r, on ? "关注" : "取消关注");
    }

    /** 视频评论列表（第 1 页 20 条，按热度）。元素含 member{uname,avatar} / content.message / like / ctime */
    public static JSONArray replies(long aid, int pn) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/v2/reply?type=1&oid=" + aid
                + "&sort=2&ps=20&pn=" + pn, "https://www.bilibili.com/");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取评论失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray arr = d == null ? null : d.optJSONArray("replies");
        return arr == null ? new JSONArray() : arr;
    }

    /**
     * 【v4.10.6】楼中楼：拉某条主评论下的二级回复。
     * root = 主评论 rpid；pn 从 1 开始。
     */
    public static JSONArray replyReplies(long aid, long root, int pn) throws Exception {
        initFinger();
        JSONObject r = getJson("https://api.bilibili.com/x/v2/reply/reply?type=1&oid=" + aid
                + "&root=" + root + "&ps=20&pn=" + pn, "https://www.bilibili.com/");
        if (r.optInt("code", -1) != 0) {
            throw new IOException("获取回复失败：" + r.optString("message", "?")
                    + " (code=" + r.optInt("code") + ")");
        }
        JSONObject d = r.optJSONObject("data");
        JSONArray arr = d == null ? null : d.optJSONArray("replies");
        return arr == null ? new JSONArray() : arr;
    }

    /** 发表评论 */
    public static void sendReply(long aid, String text) throws Exception {
        sendReply(aid, text, 0, 0);
    }

    /** 【v4.10.6】发表评论；root/parent 均 > 0 时是楼中楼里的回复 */
    public static void sendReply(long aid, String text, long root, long parent) throws Exception {
        String csrf = csrfOrThrow("发表评论");
        if (text == null || text.trim().isEmpty()) throw new IOException("评论内容不能为空");
        String body = "oid=" + aid + "&type=1&plat=1&message="
                + URLEncoder.encode(text.trim(), "UTF-8")
                + (root > 0 ? "&root=" + root + "&parent=" + (parent > 0 ? parent : root) : "")
                + "&csrf=" + csrf;
        JSONObject r = postForm("https://api.bilibili.com/x/v2/reply/add",
                body, "https://www.bilibili.com/");
        int code = r.optInt("code", -1);
        if (code != 0) {
            throw new IOException("发表失败：" + r.optString("message", "?") + " (code=" + code + ")");
        }
    }

    private BiliApi() {}
}
