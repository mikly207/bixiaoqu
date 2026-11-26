package com.bixiaoqu.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App 内置浏览器登录：加载 B 站官方登录页。
 * 用户可在官方页面里用任意方式登录：手机号+短信验证码、密码、极验滑块、二维码等，
 * 人机验证由 B 站官方页面自行完成。登录成功后从系统 Cookie 读取 SESSDATA/bili_jct。
 */
public class WebViewLoginActivity extends Activity {

    WebView wv;
    Handler ui = new Handler(Looper.getMainLooper());
    TextView tip;
    boolean done = false;
    int tries = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        tip = new TextView(this);
        tip.setText("正在打开 B 站官方登录页…\n支持：手机号+验证码 / 密码 / 扫码 / 滑块验证");
        tip.setTextColor(MainActivity.C_ACC);
        tip.setTextSize(12);
        tip.setPadding(24, 16, 24, 16);
        root.addView(tip);

        wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        // 【v4.15.7 修复】原来用 BiliApi.UA（Windows 桌面 Chrome），手机上会加载
        // 桌面版登录页，二维码/验证码组件在移动端 WebView 里经常异常甚至不渲染。
        // 改用移动端 UA，让官方页面按其移动端逻辑渲染，登录组件才正常工作。
        wv.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        // 接受第三方 Cookie（passport 跳转 bilibili.com 需要）
        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 拦截 bilibili:// 等跳转（防止尝试唤起 B 站 App 导致白屏），http/https 正常加载
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                tip.setText("在官方页面完成登录（验证码/密码/扫码均可）。" +
                        "登录成功会自动确认；若页面已登录但无反应，点「我已登录完成」。");
                scheduleCheck();
            }
        });
        wv.loadUrl("https://passport.bilibili.com/login");
        root.addView(wv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button ok = new Button(this);
        ok.setText("我已登录完成（手动确认）");
        ok.setTextColor(Color.WHITE);
        ok.setBackgroundColor(MainActivity.C_ACC);
        ok.setAllCaps(false);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        op.setMargins(24, 12, 24, 24);
        root.addView(ok, op);
        ok.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                tries = 0;
                checkCookie();
            }
        });

        setContentView(root);

        // 立即检查一次（可能已有历史登录 Cookie）
        ui.postDelayed(new Runnable() { @Override public void run() { checkCookie(); } }, 800);
    }

    void scheduleCheck() {
        ui.postDelayed(new Runnable() { @Override public void run() { checkCookie(); } }, 1000);
    }

    void checkCookie() {
        if (done) return;
        tries++;
        if (tries > 600) return; // 最多等 10 分钟
        try {
            // 【v4.15.7 修复】登录态 Cookie 可能落在 www / passport / account 任一域，
            // 原来只读 www 域，若落在其它域就永远判定为「未登录」。改为遍历聚合。
            String ck = "";
            String[] domains = {"https://www.bilibili.com/", "https://passport.bilibili.com/",
                    "https://account.bilibili.com/", "https://.bilibili.com/"};
            for (String d : domains) {
                try {
                    String one = CookieManager.getInstance().getCookie(d);
                    if (one != null && one.length() > ck.length()) ck = one;
                } catch (Throwable ignore) { }
            }
            if (ck != null && ck.contains("SESSDATA=")) {
                String sessdata = cookieVal(ck, "SESSDATA");
                String jct = cookieVal(ck, "bili_jct");
                String uid = cookieVal(ck, "DedeUserID");
                if (!sessdata.isEmpty()) {
                    done = true;
                    // 【v4.15.13】官方页面拿到的就是浏览器真 Cookie，含 DedeUserID__ckMd5 / sid，
                    // 整串存下来，重启后不会再出现「认得出账号、不认会话」。
                    BiliApi.addCookie(ck);
                    String full = BiliApi.exportLoginCookie();
                    SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
                    sp.edit().putString("SESSDATA", sessdata)
                            .putString("bili_jct", jct)
                            .putString("DedeUserID", uid)
                            .putString("full_cookie", full.isEmpty() ? ck : full)
                            .putLong("login_time", System.currentTimeMillis())
                            .apply();
                    BiliApi.setLoginCookie(sessdata, jct, uid);
                    Toast.makeText(this, "登录成功 ✓（验证码/密码/扫码均可）", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
            }
        } catch (Exception ignore) { }
        scheduleCheck();
    }

    static String cookieVal(String cookieHeader, String key) {
        for (String pair : cookieHeader.split(";")) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else finish();
    }

    @Override
    protected void onDestroy() {
        done = true;
        ui.removeCallbacksAndMessages(null);
        if (wv != null) {
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }
}
