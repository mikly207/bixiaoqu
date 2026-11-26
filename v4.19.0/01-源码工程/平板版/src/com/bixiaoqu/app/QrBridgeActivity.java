package com.bixiaoqu.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【v4.15.13】浏览器通道出码：完全绕开 App 自己的 HttpURLConnection。
 *
 * 背景：用户手机上「App 直连 passport.bilibili.com」与「App 请求云端接口」都长时间无响应，
 * 但同一台手机的浏览器能正常打开网页 —— 说明网络本身没断，是 App 的 HTTP 栈
 * （老系统 TLS、IPv6 黑洞、系统代理）这一层出了问题。
 *
 * 做法：用一个隐藏 WebView 去请求云端二维码接口（浏览器网络栈），
 * 把返回的 JSON 文本读回来，再在原生层渲染二维码。
 * 码是同一套接口签发的，扫码与轮询走同一条链路，登录成功后的凭据整串取回。
 */
public class QrBridgeActivity extends Activity {

    private static final int TIMEOUT_MS = 12000;

    WebView wv;
    ImageView qrView;
    TextView status;
    Button refreshBtn;
    Handler ui = new Handler(Looper.getMainLooper());

    String phase = "gen";      // gen / poll
    String qrcodeKey = "";
    int genTry = 0;
    int pollTicks = 0;
    boolean done = false;
    Runnable timeoutTask;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);
        int pad = 24;

        TextView title = new TextView(this);
        title.setText("扫码登录 B 站（浏览器通道）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(pad, 40, pad, 10);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("用 B 站 App 扫码确认。凭据保存在本机，不会上传。");
        tip.setTextColor(0xFF9AA0A6);
        tip.setTextSize(12);
        tip.setPadding(pad, 0, pad, 12);
        root.addView(tip);

        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        qp.setMargins(pad, 0, pad, 0);
        root.addView(qrView, qp);

        status = new TextView(this);
        status.setTextColor(MainActivity.C_ACC);
        status.setTextSize(13);
        status.setPadding(pad, 14, pad, 14);
        status.setGravity(android.view.Gravity.CENTER);
        status.setText("正在获取二维码…");
        root.addView(status);

        refreshBtn = new Button(this);
        refreshBtn.setText("刷新二维码");
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setBackgroundColor(0xFF2B2B2B);
        refreshBtn.setAllCaps(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(pad, 0, pad, 8);
        root.addView(refreshBtn, rp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gen(); }
        });

        Button web = new Button(this);
        web.setText("改用官方网页登录");
        web.setTextColor(MainActivity.C_ACC);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setAllCaps(false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.setMargins(pad, 0, pad, 30);
        root.addView(web, wp);
        web.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(QrBridgeActivity.this, WebViewLoginActivity.class));
                finish();
            }
        });

        // 隐藏 WebView：只用来发请求，不参与显示
        wv = new WebView(this);
        wv.setLayoutParams(new LinearLayout.LayoutParams(1, 1));
        wv.setAlpha(0f);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
            @Override
            public void onPageFinished(WebView view, String url) {
                readBody();
            }
        });
        root.addView(wv);

        setContentView(root);
        gen();
    }

    String genUrl() {
        return BiliApi.QR_PROXY + "?action=qr_gen&sharecode=" + BiliApi.QR_PROXY_SC;
    }

    String pollUrl() {
        return BiliApi.QR_PROXY + "?action=qr_poll&sharecode=" + BiliApi.QR_PROXY_SC
                + "&key=" + qrcodeKey;
    }

    /** 开始要一张新码 */
    void gen() {
        if (done) return;
        phase = "gen";
        qrcodeKey = "";
        pollTicks = 0;
        status.setText("正在获取二维码…");
        refreshBtn.setEnabled(false);
        armTimeout();
        wv.loadUrl(genUrl());
    }

    /** 把当前页面的文本（接口返回的 JSON）读回来 */
    void readBody() {
        wv.evaluateJavascript("(document.body?document.body.innerText:'')", new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                cancelTimeout();
                String txt = unwrap(value);
                if (txt == null || txt.trim().isEmpty()) {
                    fail(phase.equals("gen") ? "接口无响应" : "轮询无返回");
                    return;
                }
                try {
                    JSONObject r = new JSONObject(txt);
                    if ("gen".equals(phase)) onGen(r);
                    else onPoll(r);
                } catch (Throwable e) {
                    fail("返回内容异常：" + txt.substring(0, Math.min(60, txt.length())));
                }
            }
        });
    }

    void onGen(JSONObject r) {
        JSONObject d = r.optJSONObject("data");
        String url = d == null ? "" : d.optString("url", "");
        String key = d == null ? "" : d.optString("qrcode_key", "");
        if (r.optInt("code", -1) != 0 || url.isEmpty() || key.isEmpty()) {
            if (++genTry < 2) { wv.loadUrl(genUrl()); armTimeout(); return; }
            fail("云端未返回二维码（" + r.optString("message", "code=" + r.optInt("code", -1)) + "）");
            return;
        }
        qrcodeKey = key;
        Bitmap bmp = LoginActivity.makeQr(url, 640);
        if (bmp == null) { fail("二维码渲染失败"); return; }
        qrView.setImageBitmap(bmp);
        status.setText("请用 B 站 App 扫码，3 分钟内有效");
        refreshBtn.setEnabled(true);
        phase = "poll";
        pollTicks = 0;
        ui.postDelayed(pollRunnable, 1500);
    }

    Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (done || !"poll".equals(phase)) return;
            if (pollTicks++ > 220) { fail("二维码已过期，请点刷新"); return; }
            ui.post(new Runnable() { @Override public void run() {
                armTimeout();
                wv.loadUrl(pollUrl());
            } });
        }
    };

    void onPoll(JSONObject r) {
        JSONObject data = r.optJSONObject("data");
        int code = data == null ? -1 : data.optInt("code", -1);
        if (code == 0) {
            onConfirmed(data);
            return;
        }
        if (code == 86038) { fail("二维码已过期，请点刷新"); return; }
        if (code == 86090) status.setText("已扫码，请在手机上点「确认登录」");
        else if (code == 86101) { /* 未扫码，继续等 */ }
        else if (code == 86100) status.setText("等待确认…");
        ui.postDelayed(pollRunnable, 1500);
    }

    void onConfirmed(JSONObject data) {
        if (done) return;
        done = true;
        JSONObject creds = data.optJSONObject("creds");
        String raw = creds == null ? "" : creds.optString("raw", "");
        if (!raw.isEmpty()) BiliApi.addCookie(raw);
        String url = data.optString("url", "");
        String sessdata = first(creds == null ? "" : creds.optString("SESSDATA"), param(url, "SESSDATA"));
        String jct = first(creds == null ? "" : creds.optString("bili_jct"), param(url, "bili_jct"));
        String uid = first(creds == null ? "" : creds.optString("DedeUserID"), param(url, "DedeUserID"));
        if (sessdata.isEmpty()) {
            status.setText("已确认，但未取到凭据，请点刷新重试");
            done = false;
            return;
        }
        BiliApi.setLoginCookie(sessdata, jct, uid);
        String full = BiliApi.exportLoginCookie();
        SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
        sp.edit().putString("SESSDATA", sessdata)
                .putString("bili_jct", jct)
                .putString("DedeUserID", uid)
                .putString("full_cookie", full.isEmpty() ? raw : full)
                .putLong("login_time", System.currentTimeMillis())
                .apply();
        status.setText("已确认，正在校验登录态…");
        // nav 校验要联网，必须离开主线程（Android 3.0+ 主线程禁止网络请求）
        new Thread(new Runnable() { @Override public void run() {
            final String fName = verifyName();
            runOnUiThread(new Runnable() { @Override public void run() {
                if (isFinishing()) return;
                Toast.makeText(QrBridgeActivity.this,
                        "登录成功 ✓" + (fName.isEmpty() ? "" : " " + fName), Toast.LENGTH_SHORT).show();
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (!isFinishing()) finish();
                } }, 400);
            } });
        } }, "bxq-qr-verify").start();
    }

    /** 用 nav 接口核实登录态（api 域，App 平时能通） */
    String verifyName() {
        for (int i = 0; i < 3; i++) {
            try {
                JSONObject nav = BiliApi.navInfo();
                String n = nav.optString("uname", "");
                if (!n.isEmpty()) return n;
            } catch (Throwable ignore) { }
            try { Thread.sleep(1000); } catch (InterruptedException ignore) { }
        }
        return "";
    }

    void armTimeout() {
        cancelTimeout();
        timeoutTask = new Runnable() { @Override public void run() {
            fail("通道无响应（" + (TIMEOUT_MS / 1000) + "s 超时）");
        } };
        ui.postDelayed(timeoutTask, TIMEOUT_MS);
    }

    void cancelTimeout() {
        if (timeoutTask != null) { ui.removeCallbacks(timeoutTask); timeoutTask = null; }
    }

    void fail(String msg) {
        cancelTimeout();
        done = false;
        phase = "gen";
        status.setText("浏览器通道也失败：" + msg + "\n请点「改用官方网页登录」");
        refreshBtn.setEnabled(true);
    }

    /** evaluateJavascript 回传的是 JSON 编码字符串，先还原成原文 */
    static String unwrap(String v) {
        if (v == null) return null;
        if (v.equals("null")) return null;
        try {
            JSONArray a = new JSONArray("[" + v + "]");
            return a.getString(0);
        } catch (Throwable ignore) {
            return v;
        }
    }

    static String first(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    static String param(String url, String key) {
        if (url == null || url.isEmpty()) return "";
        int i = url.indexOf(key + "=");
        if (i < 0) return "";
        int s = i + key.length() + 1;
        int e = url.indexOf('&', s);
        return e < 0 ? url.substring(s) : url.substring(s, e);
    }

    @Override
    public void onBackPressed() { finish(); }

    @Override
    protected void onDestroy() {
        done = true;
        cancelTimeout();
        ui.removeCallbacksAndMessages(null);
        if (wv != null) {
            try { wv.stopLoading(); wv.destroy(); } catch (Throwable ignore) { }
            wv = null;
        }
        super.onDestroy();
    }
}
