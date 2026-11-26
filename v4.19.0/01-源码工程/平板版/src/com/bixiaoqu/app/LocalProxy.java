package com.bixiaoqu.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 HTTP 转发代理：VideoView/MediaPlayer 无法设置 Referer 头，
 * 而 B 站 CDN 校验 Referer，故在本机 127.0.0.1 起一个代理，
 * 把请求转发到 B 站 CDN 并补上 UA/Referer/Cookie 头（含 Range 断点续传）。
 *
 * 设计原则（v4.13.8 精简）：竞速线程池用 cached，每个竞速任务只做「建连+读响应头」
 * 后立即归还线程；真正的数据传输交给独立的 proxy-conn 线程，绝不占用竞速线程池。
 * 这样无论切换多少视频、旧连接是否释放，新视频的线路竞速都能立刻拿到线程，
 * 不会卡在「加载中」。断流续传保留，作为高码率流中途断网的兜底。
 */
public class LocalProxy implements Runnable {

    ServerSocket ss;
    volatile boolean running = true;
    final ExecutorService pool;
    /** 连接读写线程池：视频轨 + 音轨 + 播放器预读，正常并发不超过 6，留余量到 8 */
    final java.util.concurrent.ThreadPoolExecutor ioPool;

    // ============ v4.13.13 线路亲和 + 速度记忆（官方客户端式选路）============
    // 一个 DASH 视频会发几十个 Range 分片请求。旧版每片都全量竞速 4 条 CDN +
    // 错峰 sleep + 读响应头，握手开销被重复几十次 —— 这正是"加载慢"的主因。
    // 现在：同一视频首次竞速后记住胜出的 CDN host，后续分片单连直连、零竞速。
    // key = 主 URL 的 scheme://host/path（不含会过期的鉴权 query）；value = 胜出 host。
    // 只存 host，用时从本次候选里取该 host 的最新 URL，保证鉴权参数新鲜。
    static final java.util.concurrent.ConcurrentHashMap<String, String> LINE_AFFINITY =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    // 每个 CDN host 的实测吞吐(bps)平滑值：新视频首次竞速时快的线路优先开跑（少错峰）。
    static final java.util.concurrent.ConcurrentHashMap<String, Long> HOST_SCORE =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * 【v4.18.0 代际取消】每次「换片 / 换清晰度」都开启新一代。
     *
     * 旧版最大的隐性浪费：上一路视频的音轨、视频轨分片下载在切换后仍在跑，
     * 它们占着 socket 带宽和读线程，新视频只能排队 —— 表现就是
     * 「点了推荐要转好几秒」「切清晰度越切越慢」「还会有两路声音叠在一起」。
     * 现在每个播放地址都带一个代际号 g，新一代开启时立刻 disconnect 掉
     * 上一代登记在册的所有连接，旧任务读到 EOF/异常后自然退出。
     */
    volatile int epoch = 1;

    /** 当前代所有在途连接（open 时登记、关闭时移除），用于切换瞬间统一掐断 */
    static final java.util.Set<HttpURLConnection> ACTIVE =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());

    /** 开启新一代并掐断上一代的全部连接，返回新代际号 */
    public int newEpoch() {
        int g = ++epoch;
        try {
            for (HttpURLConnection c : ACTIVE) {
                try { c.disconnect(); } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        ACTIVE.clear();
        return g;
    }

    public LocalProxy() throws Exception {
        ss = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        // cached 线程池：竞速任务极短暂（建连+读响应头），用完立即归还/复用，
        // 不会像 fixed(8) 那样被「预热读/长阻塞」占满导致新任务排队饿死。
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "proxy-race");
                t.setDaemon(true);
                // 【v4.17.0】竞速线程给到 NORM+1：选路是起播链路上的第一环，
                // 它的快慢直接决定用户看到「加载中」还是直接出画面。
                t.setPriority(Thread.NORM_PRIORITY + 1);
                return t;
            }
        });
        // 【v4.18.0】8 条连接线程足够覆盖「视频轨 + 音轨 + 预读」，
        // 队列 48 吸收突发重连；再多的请求说明旧播放没被取消，直接拒绝。
        ioPool = new java.util.concurrent.ThreadPoolExecutor(4, 8, 20L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(48), new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "proxy-io");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY + 1);
                        return t;
                    }
                });
    }

    public int port() {
        return ss.getLocalPort();
    }

    /** 生成给 VideoView 的播放地址（单线路，兼容旧调用） */
    public String playUrl(String targetUrl) throws Exception {
        return playUrlMulti(new String[]{ targetUrl });
    }

    /**
     * 【多线路】生成播放地址，u=主线路，u2~u6=备用线路。
     * 代理会并行竞速所有线路，谁快用谁。
     */
    public String playUrlMulti(String[] urls) throws Exception {
        return playUrlMulti(urls, epoch);
    }

    /**
     * 带代际号的播放地址。gen 会随每一代请求带给代理，
     * 代理据此判断「这条请求是不是已经过期的旧播放」——过期的请求直接 502，
     * 不再参与竞速，也不会占带宽。
     */
    public String playUrlMulti(String[] urls, int gen) throws Exception {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(port())
                .append("/p?g=").append(gen).append("&u=");
        sb.append(URLEncoder.encode(urls[0], "UTF-8"));
        for (int i = 1; i < urls.length && i <= 6; i++) {
            sb.append("&u").append(i + 1).append("=").append(URLEncoder.encode(urls[i], "UTF-8"));
        }
        return sb.toString();
    }

    /**
     * 【v4.13.14 边看边加载】预热下一条视频的线路：只建连 + 读响应头 + 立即断开，
     * 用 1KB 极小 Range，**不读数据体、不缓存任何视频数据**，绝不抢当前视频带宽。
     * 把胜出的 CDN host 写入 LINE_AFFINITY —— 用户滑到该视频时，
     * handle() 命中亲和快路径，跳过「4 线路全量竞速 + 错峰等待」，首帧更快出画。
     * 只探测历史最快的 2 条，第一条成功即止；失败静默（正式播放回退全量竞速）。
     * 必须预先保证 urls[0] 与正式播放时的主线路一致（同为 pickVideo 结果），
     * 这样 affinityKey(host+path) 才能匹配命中。
     */
    public void warmup(String[] urls) {
        if (urls == null || urls.length == 0) return;
        final String affKey = affinityKey(urls[0]);
        if (LINE_AFFINITY.containsKey(affKey)) return;   // 已预热过，不重复探测
        final String[] ordered = rankUrls(urls);
        for (int i = 0; i < ordered.length && i < 2; i++) {
            String u = ordered[i];
            HttpURLConnection c = null;
            try {
                if (i > 0) Thread.sleep(120L);   // 错峰，降低同资源瞬时并发被风控 403 的概率
                c = open(u, "bytes=0-1023");      // 极小 Range：握手 + 读头即可，不拉数据体
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                // 探测成功：记录该 host 为这个流的亲和线路，正式播放直接复用
                if (LINE_AFFINITY.size() > 256) LINE_AFFINITY.clear();
                LINE_AFFINITY.put(affKey, hostOf(u));
                CrashHandler.note("proxy", "线路预热 " + hostOf(u));
                return;
            } catch (Exception e) {
                if (c != null) { try { c.disconnect(); } catch (Exception ignore) { } }
            }
        }
    }

    public void start() {
        new Thread(this, "bili-proxy").start();
    }

    public void stop() {
        running = false;
        try { ss.close(); } catch (Exception ignore) { }
        try { pool.shutdownNow(); } catch (Exception ignore) { }
        try { ioPool.shutdownNow(); } catch (Exception ignore) { }
        // 【v4.13.13】退出时清理线路记忆，避免跨会话残留
        LINE_AFFINITY.clear();
        HOST_SCORE.clear();
    }

    @Override
    public void run() {
        while (running) {
            try {
                final Socket s = ss.accept();
                // 【v4.18.0 线程受控】旧版每个连接都 new Thread()：
                // 播放器一次会开视频/音频多条连接，快速换片时旧连接还没退出，
                // 线程数与 socket 数会短暂翻几倍。改成固定池 + 有界队列，
                // 超出的连接直接回 503（播放器会重试），不再无限制地吃内存和带宽。
                try {
                    ioPool.execute(new Runnable() {
                        @Override public void run() {
                            // 禁 Nagle：首块立刻下发，缩短起播缓冲等待
                            try { s.setTcpNoDelay(true); } catch (Exception ignore) { }
                            try { handle(s); }
                            catch (Exception ignore) { }
                            finally {
                                try { s.close(); } catch (Exception ignore) { }
                            }
                        }
                    });
                } catch (Throwable reject) {
                    try { s.close(); } catch (Exception ignore) { }
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    /** 解析请求行里的所有候选 URL 与 Range 头 */
    static class Req {
        String[] urls;
        String range;
        long start;   // 【v4.13.5】Range 起始绝对偏移，用于断流续传时计算 bytes=start+已读-
        int gen;      // 【v4.18.0】发起这条请求时的播放代际
    }

    Req parseRequest(Socket s) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String line = br.readLine();
        if (line == null || !line.startsWith("GET ")) return null;
        String range = null;
        String rl;
        while ((rl = br.readLine()) != null && !rl.isEmpty()) {
            if (rl.regionMatches(true, 0, "Range:", 0, 6)) {
                range = rl.substring(6).trim();
            }
        }
        java.util.List<String> list = new java.util.ArrayList<String>();
        String main = paramFrom(line, "u=");
        if (main != null && !main.isEmpty()) list.add(main);
        // 【v4.17.0】备用线路从 3 条扩到 5 条（u2~u6），与 BiliApi 侧六线路一致
        for (int i = 2; i <= 6; i++) {
            String alt = paramFrom(line, "u" + i + "=");
            if (alt != null && !alt.isEmpty()) list.add(alt);
        }
        if (list.isEmpty()) return null;
        Req r = new Req();
        r.urls = list.toArray(new String[list.size()]);
        r.range = range;
        r.gen = 0;
        String gs = paramFrom(line, "g=");
        if (gs != null) {
            try { r.gen = Integer.parseInt(gs.trim()); } catch (Exception ignore) { r.gen = 0; }
        }
        // 解析 Range 起点：bytes=12345-  → 12345；bytes=-500 / 无 → 0
        r.start = 0;
        if (range != null) {
            String rv = range.trim();
            if (rv.regionMatches(true, 0, "bytes=", 0, 6)) {
                String spec = rv.substring(6);
                int dash = spec.indexOf('-');
                if (dash > 0) {
                    try { r.start = Long.parseLong(spec.substring(0, dash).trim()); }
                    catch (Exception ignore) { r.start = 0; }
                }
            }
        }
        return r;
    }

    /** 从请求行里取某个查询参数的值（u2= 不会误匹配到 u= ） */
    String paramFrom(String line, String key) {
        int idx = -1;
        int searchFrom = 0;
        while (true) {
            int p = line.indexOf(key, searchFrom);
            if (p < 0) break;
            boolean boundary = (p == 0) || (line.charAt(p - 1) == '?') || (line.charAt(p - 1) == '&');
            if (boundary) { idx = p; break; }
            searchFrom = p + 1;
        }
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = line.indexOf(' ', start);
        String raw = end > 0 ? line.substring(start, end) : line.substring(start);
        try { return URLDecoder.decode(raw, "UTF-8"); }
        catch (Exception e) { return raw; }
    }

    /** 一次线路竞速的结果 */
    static class Trial {
        HttpURLConnection conn;   // 成功拿到响应头的连接；非胜出者会被关闭并置 null
        String url;
        String err;
    }

    void handle(Socket s) throws Exception {
        Req req = parseRequest(s);
        if (req == null || req.urls == null || req.urls.length == 0) return;
        // 【v4.18.0】上一代播放遗留的请求：直接关掉，不竞速、不下载
        if (req.gen > 0 && req.gen != epoch) {
            try { s.close(); } catch (Exception ignore) { }
            return;
        }

        final String range = req.range;

        // ===== v4.13.13 快路径①：线路亲和 —— 同视频后续分片直接用已验证的最快线路 =====
        // 官方客户端做法：一次选路、全片复用。避免每个分片重复「4 线路建连+错峰+读头」，
        // 这是竖屏滑动/起播慢的主因。只缓存 host，URL 用本次传入的最新鉴权地址。
        String affKey = affinityKey(req.urls[0]);
        String affHost = LINE_AFFINITY.get(affKey);
        if (affHost != null) {
            for (String u : req.urls) {
                if (u != null && affHost.equals(hostOf(u))) {
                    HttpURLConnection hit = null;
                    try {
                        hit = open(u, range);
                        int hc = hit.getResponseCode();
                        if (hc >= 200 && hc < 300) {
                            // 命中，直接进传输段（下方复用同一套响应头/续传逻辑）
                            serveWinner(s, hit, u, req);
                            return;
                        }
                        // 亲和线路失效（风控/鉴权过期）→ 清掉，回落全量竞速
                        LINE_AFFINITY.remove(affKey);
                    } catch (Exception e) {
                        LINE_AFFINITY.remove(affKey);
                    } finally {
                        if (hit != null) { try { hit.disconnect(); } catch (Exception ignore) { } }
                    }
                    break;
                }
            }
        }

        // ===== 全量竞速（仅首片 / 亲和失效时）=====
        // v4.13.13：按 host 历史吞吐排序，历史快的线路排前面 → trialIndex 小、错峰少、优先胜出。
        final String[] ordered = rankUrls(req.urls);
        final BlockingQueue<Trial> results = new LinkedBlockingQueue<Trial>();
        final AtomicBoolean claimed = new AtomicBoolean(false); // 保证只有一个胜出连接

        // 所有线路错峰开跑（降低同资源瞬时并发被 CDN 风控 403 的概率）
        for (int idx = 0; idx < ordered.length; idx++) {
            final int trialIndex = idx;
            final String target = ordered[idx];
            pool.execute(new Runnable() {
                @Override public void run() {
                    Trial t = new Trial();
                    t.url = target;
                    HttpURLConnection trial = null;
                    try {
                        if (req.gen > 0 && req.gen != epoch) throw new Exception("stale gen");
                        // 【v4.13.9 错峰竞速】多条线路同时打同一资源会被 B 站 CDN 风控
                        // 返回 403 拦截页（实测 8 条并发时多数线路 403）。按序号错峰几十毫秒，
                        // 首条立刻发出，其余依次稍后，显著降低"同资源瞬时并发"被判风控的概率。
                        // 【v4.17.0】25ms → 16ms：线路从 4 条扩到 6 条后仍要保证
                        // 全部线路在 ~80ms 内跑完，竞速总时长不因线路变多而变长。
                        if (trialIndex > 0) Thread.sleep(trialIndex * 16L);
                        trial = open(target, range);
                        int code = trial.getResponseCode();   // 连接阶段失败会抛异常
                        // 【v4.13.9 致命 bug 修复】只接受 2xx！
                        // 旧版 `code >= 500` 漏判 4xx：CDN 风控返回的 403/412 页面被当成
                        // "胜出成功"原样转发给 MediaPlayer，播放器收到 HTML 而非视频 →
                        // 永远卡在「加载中喵~」。403/412/404/301 等一律判该线路失败，
                        // 继续等其它真正在吐视频数据的线路胜出。
                        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                        // 拿到响应头即完成竞速，立刻归还线程。
                        // 关键：绝不在竞速线程里读数据体（read 会阻塞到 readTimeout，
                        // 把整个线程池占满 → 新视频竞速任务拿不到线程 → 卡「加载中」）。
                        // 真正的数据读取在下方独立的 proxy-conn / handle 线程中进行。
                        t.conn = trial;
                    } catch (Exception e) {
                        t.err = e.getMessage();
                        if (trial != null) {
                            try { trial.disconnect(); } catch (Exception ignore) { }
                            ACTIVE.remove(trial);
                        }
                        t.conn = null;
                    }
                    // 只有一个连接能成为胜者；迟到的成功者自行关闭，避免泄漏
                    if (t.conn != null && !claimed.compareAndSet(false, true)) {
                        try { t.conn.disconnect(); } catch (Exception ignore) { }
                        ACTIVE.remove(t.conn);
                        t.conn = null;
                    }
                    results.offer(t);
                }
            });
        }

        // 收结果：拿到第一个成功连接即用；全部失败则快速返回 502
        HttpURLConnection c = null;
        String usedUrl = null;
        int remaining = ordered.length;
        long deadline = System.currentTimeMillis() + 8000L;
        while (remaining > 0) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            Trial t = results.poll(left, TimeUnit.MILLISECONDS);
            if (t == null) break;
            remaining--;
            if (t.conn != null) {
                c = t.conn;
                usedUrl = t.url;
                CrashHandler.note("proxy", "竞速胜出 " + hostOf(usedUrl));
                break;
            } else if (t.err != null) {
                CrashHandler.note("proxy", "线路失败 " + hostOf(t.url) + "：" + t.err);
            }
        }

        if (c == null) {
            OutputStream out = s.getOutputStream();
            String body = "all lines failed";
            out.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body).getBytes("UTF-8"));
            out.flush();
            return;
        }

        // 【v4.13.13】记住胜出线路 → 同视频后续分片走亲和快路径，不再全量竞速
        if (LINE_AFFINITY.size() > 256) LINE_AFFINITY.clear();   // 容量保护：滑动太多视频后重置
        LINE_AFFINITY.put(affKey, hostOf(usedUrl));
        serveWinner(s, c, usedUrl, req);
    }

    /**
     * 【v4.13.13】把胜出连接的数据转发给播放器（含响应头 + 断流续传 + 带宽反馈）。
     * 亲和快路径与全量竞速共用此方法，避免逻辑重复。
     */
    private void serveWinner(Socket s, HttpURLConnection c, String usedUrl, Req req) throws Exception {
        try {
            long len = c.getContentLength();
            String cr = c.getHeaderField("Content-Range");
            String ct = c.getContentType();

            OutputStream out = s.getOutputStream();
            StringBuilder h = new StringBuilder();
            // 【v4.13.9】胜出连接已确认是 2xx，统一按 200/206 回复，
            // 杜绝旧版把异常状态码（如 403 OK）当成正常响应转发给播放器。
            int okCode = c.getResponseCode();
            if (okCode < 200 || okCode >= 300) okCode = 200;   // 兜底防御
            h.append("HTTP/1.1 ").append(okCode).append(' ')
             .append(okCode == 206 ? "Partial Content" : "OK").append("\r\n");
            if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
            if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
            if (ct != null) h.append("Content-Type: ").append(ct).append("\r\n");
            h.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            out.flush();

            // 【v4.13.5】传输（含断流自动续传）：
            // 竞速胜出的线路若在中途断流，旧版 read() 直接结束，客户端收到的字节数少于
            // 响应头声明的 Content-Length，MediaPlayer 就判定「下载失败/加载不出来」——
            // 横屏高码率流更容易踩到。现在断流时改用备用线路按绝对 Range 续上，
            // 只发一次响应头，续传数据直接续写，对客户端完全透明。
            byte[] buf = new byte[256 * 1024];   // v4.12：64KB→256KB，高码率单次 read 更满
            long bytes = 0;
            long t0 = System.currentTimeMillis();
            HttpURLConnection cur = c;
            InputStream in = cur.getResponseCode() >= 400 ? cur.getErrorStream() : cur.getInputStream();

            // 已用过的线路不再重复续传（主线路 + 每条备用最多一次）
            java.util.HashSet<String> used = new java.util.HashSet<String>();
            if (usedUrl != null) used.add(usedUrl);
            int maxResume = req.urls.length;   // 最多尝试 = 线路总数，保证有限次、不死循环

            try {
                // 【v4.18.0】每块都检查代际：换片/换清晰度后这条旧流立刻停止转发，
                // 不会继续把带宽占住让新视频排队。
                while (running && (req.gen <= 0 || req.gen == epoch)) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (Exception e) {
                        n = -1;   // 视作断流，交给续传逻辑
                    }
                    if (n > 0) {
                        out.write(buf, 0, n);
                        bytes += n;
                        continue;
                    }

                    // read 结束：判断是否传满。len<0（未知长度）无法判断，按 EOF 正常结束。
                    boolean incomplete = (len > 0 && bytes < len);
                    boolean canResume = incomplete && maxResume > 0;
                    String backup = null;
                    if (canResume) {
                        // 选一条没用过的备用线路
                        for (String u : req.urls) {
                            if (u != null && !used.contains(u)) { backup = u; break; }
                        }
                    }
                    if (backup == null) break;   // 传满或无备用可用 → 结束

                    // 用备用线路从断点续传。绝对偏移 = Range 起点 + 已读字节。
                    HttpURLConnection resume = null;
                    try {
                        long from = req.start + bytes;
                        resume = open(backup, "bytes=" + from + "-");
                        int rc = resume.getResponseCode();
                        if (rc != 200 && rc != 206) throw new Exception("HTTP " + rc);
                        InputStream rin = rc >= 400 ? resume.getErrorStream() : resume.getInputStream();
                        // 关闭旧连接，切换到续传连接（响应头只写过一次，不再重复写）
                        try { if (in != null) in.close(); } catch (Exception ignore) { }
                        try { cur.disconnect(); } catch (Exception ignore) { }
                        cur = resume;
                        in = rin;
                        used.add(backup);
                        maxResume--;
                        CrashHandler.note("proxy", "断流续传 " + hostOf(backup)
                                + " 从 " + from + " 继续（已传 " + bytes + "/" + len + ")");
                        continue;
                    } catch (Exception e) {
                        if (resume != null) { try { resume.disconnect(); } catch (Exception ignore) { } }
                        used.add(backup);
                        maxResume--;
                        CrashHandler.note("proxy", "续传失败 " + hostOf(backup) + "：" + e.getMessage());
                        // 继续尝试下一条备用线路
                    }
                }
                out.flush();   // 首块/末块立刻推出，不滞留在缓冲
                long elapsed = System.currentTimeMillis() - t0;
                BiliApi.noteThroughput(bytes, elapsed);
                // 【v4.13.13】按 host 回填实测吞吐，供后续视频选路时"快线路优先"
                noteHostScore(hostOf(usedUrl), bytes, elapsed);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignore) { }
                try { cur.disconnect(); } catch (Exception ignore) { }
                try { out.close(); } catch (Exception ignore) { }
            }
        } finally {
            // cur 可能已切换为续传连接，主连接 c 在这里兜底关闭（已关闭的重复 disconnect 无害）
            try { c.disconnect(); } catch (Exception ignore) { }
            ACTIVE.remove(c);
        }
    }

    /** 打开一条线路：缩短超时 + 完整请求头 */
    HttpURLConnection open(String target, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        // 【v4.17.0】5s → 4.5s：起播阶段等太久不如立刻换下一条线路，
        // 并行竞速取最快，慢线路只是被淘汰，不影响总时长。
        c.setConnectTimeout(4500);
        // 【v4.17.0】12s → 10s：某条线路建连成功却中途「挂住不吐数据」时，
        // 旧版要干等 12s 才超时，画面就卡在加载中。收紧后能更快判定该线路失效，
        // 触发竞速的其它线路 / 断流续传接管。10s 仍能覆盖正常分片下载间隔。
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", BiliApi.UA);
        c.setRequestProperty("Referer", BiliApi.REFERER);
        StringBuilder ck = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : BiliApi.COOKIES.entrySet()) {
            if (ck.length() > 0) ck.append("; ");
            ck.append(e.getKey()).append('=').append(e.getValue());
        }
        if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());
        if (range != null) c.setRequestProperty("Range", range);
        // 【v4.18.0】登记在途连接，换代时统一掐断
        try { ACTIVE.add(c); } catch (Throwable ignore) { }
        return c;
    }

    /**
     * 【v4.13.13】线路亲和 key：用 host + path 标识"同一路视频流"。
     * 丢弃 query（expires/oi/trid 等鉴权参数每次取址都变），保证同一视频的
     * 多个分片命中同一 key；不同清晰度/音轨因 path 不同而天然隔离。
     */
    static String affinityKey(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            if (p == null || p.isEmpty()) p = "/";
            return u.getHost() + p;
        } catch (Exception e) {
            int q = url.indexOf('?');
            return q > 0 ? url.substring(0, q) : url;
        }
    }

    /**
     * 【v4.13.13】按 host 历史吞吐给候选线路降序排序：历史快的排前面。
     * 竞速时 trialIndex 小 → 错峰 sleep 少 → 优先开跑、优先胜出。
     * 无历史记录的线路保持原有相对顺序（稳定排序），首次播放行为与旧版一致。
     */
    String[] rankUrls(String[] urls) {
        if (urls.length <= 1) return urls;
        final String[] out = urls.clone();
        java.util.Arrays.sort(out, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                Long sa = HOST_SCORE.get(hostOf(a));
                Long sb = HOST_SCORE.get(hostOf(b));
                long va = sa == null ? -1L : sa;
                long vb = sb == null ? -1L : sb;
                if (va == vb) return 0;
                return va > vb ? -1 : 1;   // 吞吐高的排前面
            }
        });
        return out;
    }

    /** 【v4.13.13】传输完成后按实测吞吐回填该 host 评分（指数平滑，抑制抖动） */
    static void noteHostScore(String host, long bytes, long ms) {
        if (host == null || "unknown".equals(host) || bytes <= 0 || ms <= 0) return;
        long bps = bytes * 8000L / ms;
        if (bps < 100_000L) return;   // 过小的分片不参与，避免短请求噪声
        Long old = HOST_SCORE.get(host);
        long next = old == null ? bps : (long) (old * 0.6 + bps * 0.4);
        HOST_SCORE.put(host, next);
    }

    String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return "unknown"; }
    }
}
