package com.bixiaoqu.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

/**
 * 扫码登录：展示官方网页端二维码，确认后保存登录 Cookie。
 * 凭据双通道解析（Set-Cookie + 跳转 URL 参数），任一通道有效即可。
 */
public class LoginActivity extends Activity {

    ImageView qrView;
    TextView status;
    Button refreshBtn;
    Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean polling = false;
    String currentKey = "";
    // 【v4.13.6】二维码代次序号：每次刷新自增，旧轮询线程的回调一律丢弃，
    // 避免「点刷新后旧 key 的过期/扫码结果」污染新二维码。
    volatile int qrSeq = 0;
    // 自动刷新次数上限：过期/超时自动换新码，但最多 3 次，避免无限循环刷屏。
    int autoRefreshCount = 0;
    // 【v4.15.10】生成中标记：驱动进度提示，避免界面看着像卡死
    volatile boolean generating = false;
    static final int MAX_AUTO_REFRESH = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.applyTheme(this);
        buildUi();
        refreshQr();
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("扫码登录 B 站");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("用 B 站 App 扫码确认。登录后可看高清、缓存 320k。\n" +
                "凭据保存在本机 App 私有目录，不会上传。");
        sub.setTextColor(MainActivity.C_SUB);
        sub.setTextSize(12);
        sub.setPadding(0, (int) (pad * 0.6), 0, 0);
        root.addView(sub);

        qrView = new ImageView(this);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                (int) (pad * 11), (int) (pad * 11));
        qp.topMargin = (int) (pad * 0.8);
        root.addView(qrView, qp);

        status = new TextView(this);
        status.setText("正在生成二维码…");
        status.setTextColor(MainActivity.C_ACC);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (pad * 0.8), 0, 0);
        root.addView(status);

        refreshBtn = new Button(this);
        refreshBtn.setText("刷新二维码");
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setBackgroundColor(MainActivity.C_CARD);
        refreshBtn.setAllCaps(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (pad * 0.8);
        root.addView(refreshBtn, rp);
        refreshBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                // 手动点刷新：重置自动刷新计数，给新二维码完整的自动重试机会
                autoRefreshCount = 0;
                refreshQr();
            }
        });

        // 【v4.15.7】兜底通道：WebView 网页登录。走系统 WebView 的网络栈
        // （自带 DNS/TLS/HTTP2），与 HttpURLConnection 完全不同——
        // 即使二维码两条线路都连不上 passport 域，网页登录往往仍能通。
        Button webBtn = new Button(this);
        webBtn.setText("网页方式登录（二维码打不开时用）");
        webBtn.setTextColor(MainActivity.C_ACC);
        webBtn.setBackgroundColor(MainActivity.C_BG);
        webBtn.setAllCaps(false);
        webBtn.setTextSize(12);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = (int) (pad * 0.4);
        root.addView(webBtn, wp);
        webBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                polling = false;
                qrSeq++;
                startActivity(new android.content.Intent(
                        LoginActivity.this, WebViewLoginActivity.class));
            }
        });

        setContentView(root);
    }

    void runUi(java.lang.Runnable r) {
        ui.post(new Runnable() { @Override public void run() { r.run(); } });
    }

    /** 【v4.15.13】从浏览器通道/网页登录返回时，本页可能还停在旧状态：
     *  已登录就直接关掉，没登录且没有可用码就重新要一张，避免用户回到一个死界面。 */
    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (BiliApi.loggedInUid() != null) { finish(); return; }
        } catch (Throwable ignore) { }
        if (!generating && (currentKey == null || currentKey.isEmpty())) refreshQr();
    }

    /** 生成期间的进度提示：每 2 秒刷新等待秒数，让用户知道没卡死 */
    void tickGen(final int seq, final int sec) {
        if (!generating || seq != qrSeq) return;
        runUi(new Runnable() { @Override public void run() {
            if (!generating || seq != qrSeq) return;
            status.setText(sec <= 0 ? "正在获取二维码…"
                    : ("正在获取二维码…（" + sec + "s）\n云端与直连连线同时尝试，先到先用"));
        } });
        ui.postDelayed(new Runnable() { @Override public void run() {
            tickGen(seq, sec + 2);
        } }, 2000);
    }

    void refreshQr() {
        // 【v4.13.6】每代刷新拿一个独立 seq；旧轮询线程发现 seq 变了会立刻退出，
        // 不会再拿旧 key 的结果覆盖新二维码。
        polling = false;
        final int seq = ++qrSeq;
        generating = true;
        runUi(new Runnable() { @Override public void run() {
            status.setText("正在获取二维码…");
            if (refreshBtn != null) refreshBtn.setEnabled(false);
        } });
        tickGen(seq, 0);
        new Thread(new Runnable() { @Override public void run() {
            // 生成失败重试：网络抖动/偶发风控时不要直接摆烂，退避重试 3 次
            final int MAX_TRY = 1;   // v4.15.13：内部已三路并行竞速，外层不再重复排队
            Exception lastErr = null;
            for (int attempt = 1; attempt <= MAX_TRY; attempt++) {
                if (seq != qrSeq) return;   // 已被新一代刷新取代
                try {
                    JSONObject data = BiliApi.qrcodeGenerate();
                    final String key = data.getString("qrcode_key");
                    String url = data.getString("url");
                    final Bitmap bmp = makeQr(url, 600);
                    if (bmp == null) throw new Exception("二维码编码失败");
                    currentKey = key;
                    runUi(new Runnable() { @Override public void run() {
                        if (seq != qrSeq) return;
                        generating = false;
                        qrView.setImageBitmap(bmp);
                        status.setText("请用 B 站 App 扫码，3 分钟内有效");
                        if (refreshBtn != null) refreshBtn.setEnabled(true);
                    } });
                    startPolling(seq);
                    return;
                } catch (Exception e) {
                    lastErr = e;
                    if (seq != qrSeq) return;
                    if (attempt < MAX_TRY) {
                        // 【v4.15.6】实时显示失败原因，不再默默重试让用户干等
                        final String em = e.getMessage() == null ? e.toString() : e.getMessage();
                        final int at = attempt;
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("第 " + at + " 次生成失败：" + em + "\n正在换线路重试…");
                        } });
                        try { Thread.sleep(600L * attempt); } catch (InterruptedException ignore) { return; }
                    }
                }
            }
            final String msg = lastErr == null ? "未知错误" : lastErr.getMessage();
            runUi(new Runnable() { @Override public void run() {
                if (seq != qrSeq) return;
                generating = false;
                qrView.setImageBitmap(null);
                // 【v4.15.13】失败不再静悄悄跳走：把三路线路诊断留在页面上
                // （云端✓/✗ 与耗时），既能自己判断根因，也让用户知道卡在哪。
                status.setText("二维码未生成（三路通道都超时）\n" + msg
                        + "\n正在切换浏览器通道…");
                if (refreshBtn != null) refreshBtn.setEnabled(true);
                // 【v4.15.13】HTTP 栈全挂时，改由隐藏 WebView 走浏览器网络栈请求接口出码
                // （同一台手机浏览器能打开网页，说明问题在 App 的 HTTP 层，不在网络）。
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    if (seq != qrSeq) return;   // 用户已点刷新，不打扰
                    polling = false;
                    qrSeq++;
                    startActivity(new android.content.Intent(
                            LoginActivity.this, QrBridgeActivity.class));
                } }, 2500);
            } });
        } }, "bxq-qrgen").start();
    }

    /** 过期/超时后自动换新二维码（有次数上限），无需用户手动点刷新 */
    void autoRefresh() {
        if (autoRefreshCount >= MAX_AUTO_REFRESH) {
            runUi(new Runnable() { @Override public void run() {
                status.setText("二维码已失效，请点「刷新二维码」");
                if (refreshBtn != null) refreshBtn.setEnabled(true);
            } });
            return;
        }
        autoRefreshCount++;
        refreshQr();
    }

    void startPolling(final int seq) {
        if (polling && seq == qrSeq) return;
        polling = true;
        new Thread(new Runnable() { @Override public void run() {
            long start = System.currentTimeMillis();
            int netFails = 0;             // 连续网络失败计数（区分于 B 站业务码）
            while (polling && seq == qrSeq && System.currentTimeMillis() - start < 180_000) {
                try {
                    Thread.sleep(1500);
                    if (!polling || seq != qrSeq) return;
                    JSONObject r = BiliApi.qrcodePoll(currentKey);
                    netFails = 0;         // 拿到响应即清零，业务码异常不算网络问题
                    JSONObject data = r.optJSONObject("data");
                    if (data == null) data = new JSONObject();
                    // 【v4.15.4 关键修复】B 站轮询接口的顶层 code 恒为 0（HTTP 层状态），
                    // 真正的二维码状态码在 data.code 里（86101 未扫 / 86090 已扫 /
                    // 86038 过期 / 0 确认成功）。旧代码读顶层 code → 永远等于 0 →
                    // 每次轮询都被当成「已确认」→ 取不到凭据 → 「登录返回缺少凭据」。
                    // 这就是扫码登录从来没成功过的根因。
                    int code = data.optInt("code", r.optInt("code", -1));
                    if (code == 0) {
                        // 【v4.15.9】凭据提取顺序：Set-Cookie 原值 > data.url 原样参数 > 解码参数。
                        // 旧版优先用 URL 解码值，SESSDATA 里的 %2C 被还原成逗号后 B 站可能不认可；
                        // 且只取三件套会丢掉 DedeUserID__ckMd5 / sid（校验会话必需）。
                        String url = data.optString("url", "");
                        // 【v4.15.10】云端通道：凭据在 data.creds 里（raw 为完整 Cookie 串）
                        JSONObject credsJ = data.optJSONObject("creds");
                        if (credsJ != null) {
                            String rawCk = credsJ.optString("raw", "");
                            if (!rawCk.isEmpty()) BiliApi.addCookie(rawCk);
                            String cs = credsJ.optString("SESSDATA", "");
                            if (!cs.isEmpty() && !BiliApi.COOKIES.containsKey("SESSDATA"))
                                BiliApi.COOKIES.put("SESSDATA", cs);
                        }
                        final String sessdata = firstNonEmpty(BiliApi.COOKIES.get("SESSDATA"),
                                firstNonEmpty(rawParam(url, "SESSDATA"), param(url, "SESSDATA")));
                        final String jct = firstNonEmpty(BiliApi.COOKIES.get("bili_jct"),
                                firstNonEmpty(rawParam(url, "bili_jct"), param(url, "bili_jct")));
                        final String uid = firstNonEmpty(BiliApi.COOKIES.get("DedeUserID"),
                                firstNonEmpty(rawParam(url, "DedeUserID"), param(url, "DedeUserID")));
                        if (sessdata.isEmpty()) {
                            final String dbg = "url=" + (url.isEmpty() ? "(无)" :
                                    url.substring(0, Math.min(80, url.length())));
                            runUi(new Runnable() { @Override public void run() {
                                if (seq != qrSeq) return;
                                status.setText("登录返回缺少凭据，请刷新重试\n" + dbg);
                                if (refreshBtn != null) refreshBtn.setEnabled(true);
                            } });
                            polling = false;
                            return;
                        }
                        BiliApi.setLoginCookie(sessdata, jct, uid);
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("已确认，正在校验登录态…");
                        } });
                        // 【v4.15.9】用 nav.data.isLogin 实测登录态，会话生效有延迟则重试
                        boolean ok = false;
                        String uname = "";
                        for (int i = 0; i < 4 && !ok; i++) {
                            if (seq != qrSeq) return;
                            try {
                                JSONObject nav = BiliApi.navInfo();
                                ok = true;
                                uname = nav.optString("uname", "");
                            } catch (Exception ve) {
                                try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
                            }
                        }
                        // 保存完整登录 Cookie（含 DedeUserID__ckMd5 / sid），重启后依然有效
                        final String full = BiliApi.exportLoginCookie();
                        SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
                        sp.edit().putString("SESSDATA", sessdata)
                                .putString("bili_jct", jct)
                                .putString("DedeUserID", uid)
                                .putString("full_cookie", full)
                                .putLong("login_time", System.currentTimeMillis())
                                .apply();
                        polling = false;
                        final boolean fOk = ok;
                        final String fName = uname;
                        runUi(new Runnable() { @Override public void run() {
                            if (fOk) {
                                Toast.makeText(LoginActivity.this,
                                        "登录成功 ✓" + (fName.isEmpty() ? "" : " " + fName),
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            } else {
                                status.setText("凭据已保存，但登录态未确认\n请返回后重试或点「刷新二维码」");
                                if (refreshBtn != null) refreshBtn.setEnabled(true);
                            }
                        } });
                        return;
                    } else if (code == 86038) {
                        // 【v4.13.6】二维码作废 → 自动换新码，不再逼用户手动点
                        polling = false;
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("二维码已失效，正在自动刷新…");
                        } });
                        autoRefresh();
                        return;
                    } else if (code == 86090) {
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("已扫码，请在手机上确认 ✓");
                        } });
                    } else if (code == 86101) {
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("等待扫码…");
                        } });
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception e) {
                    // 【v4.13.6】网络抖动：连续失败太多说明码可能已失效或网络异常，
                    // 主动换新二维码；偶发失败继续轮询。
                    netFails++;
                    if (netFails >= 12) {
                        polling = false;
                        runUi(new Runnable() { @Override public void run() {
                            if (seq != qrSeq) return;
                            status.setText("网络不稳，正在自动刷新二维码…");
                        } });
                        autoRefresh();
                        return;
                    }
                }
            }
            if (polling && seq == qrSeq) {
                polling = false;
                runUi(new Runnable() { @Override public void run() {
                    if (seq != qrSeq) return;
                    status.setText("二维码超时，正在自动刷新…");
                } });
                autoRefresh();
            }
        } }, "bxq-qrpoll").start();
    }

    static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    /** 【v4.15.9】取 URL 参数原值，不做 URL 解码（SESSDATA 的 %2C 必须保持编码态） */
    static String rawParam(String url, String key) {
        if (url == null) return "";
        for (String seg : url.split("&")) {
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            String k = seg.substring(0, eq);
            int ks = k.lastIndexOf('?');
            if (ks >= 0) k = k.substring(ks + 1);
            if (k.equals(key)) return seg.substring(eq + 1);
        }
        return "";
    }

    static String param(String url, String key) {
        for (String seg : url.split("&")) {
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            String k = seg.substring(0, eq);
            int ks = k.lastIndexOf('?');
            if (ks >= 0) k = k.substring(ks + 1);
            if (!k.equals(key)) continue;
            String v = seg.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return "";
    }

    @SuppressLint("HotSpot")
    static Bitmap makeQr(String content, int size) {
        if (content == null || content.isEmpty()) return null;
        // 【v4.13.6】显式指定 UTF-8 字符集 + 最低纠错档 L（二维码内容更长也能编码成功）
        // + margin=1。旧版用默认参数，遇到带长参数的登录 URL 有概率编码失败返回空白。
        java.util.Map<com.google.zxing.EncodeHintType, Object> hints =
                new java.util.EnumMap<com.google.zxing.EncodeHintType, Object>(
                        com.google.zxing.EncodeHintType.class);
        hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L);
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 3);   // [v4.15.14] 白边留足，扫码器更容易定位
        try {
            BitMatrix m = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int[] row = new int[size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    row[x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
                bmp.setPixels(row, 0, size, 0, y, size, 1);
            }
            return bmp;
        } catch (Throwable e) {
            // 【v4.15.17】改成 Throwable：本包 dex 缺 Encoder/ReedSolomonEncoder，
            // QRCodeWriter.encode() 抛的是 NoClassDefFoundError（Error），catch(Exception) 抓不住。
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        polling = false;
        qrSeq++;   // 作废在途的生成/轮询线程，避免回调写到已销毁的界面
        super.onDestroy();
    }
}
