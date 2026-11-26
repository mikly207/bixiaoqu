package com.bixiaoqu.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/**
 * 【v4.15.17】官方页面出图 + 原生居中显示。
 *
 * 上一版（v4.15.16）把官网的二维码克隆进页面内遮罩放大，但这台设备上
 * position:fixed + 百分比 的定位基准与可视区不一致，码跑到了右下角还被裁掉。
 *
 * 本版不再在网页里排版：网页只负责「跑官方轮询、把二维码图片拿出来」，
 * 界面全部由原生控件画，位置用屏幕像素精确计算，必然居中。
 *
 * 另外查清了一个陈年 Bug：dex 里缺 com.google.zxing.qrcode.encoder.Encoder
 * 与 ReedSolomonEncoder，QRCodeWriter.encode() 一调用就抛 NoClassDefFoundError
 * （属于 Error，catch(Exception) 抓不到），所以之前「本地重画二维码」必然失败。
 * 本版彻底不走 ZXing：直接用官网页面里那张图的 base64 数据。
 */
public class WebQrLoginActivity extends Activity {

    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    static final String LOGIN_URL = "https://passport.bilibili.com/login";

    /** 取官网页面里二维码图片的 base64（变化时才回传整串，未变化返回 same） */
    static final String JS_SRC =
            "(function(){try{"
                    + "var sels=['.login-scan__qrcode img','[class*=qrcode] img',"
                    + "'[class*=scan] img','img'];"
                    + "var el=null;"
                    + "for(var s=0;s<sels.length&&!el;s++){"
                    + "var list=document.querySelectorAll(sels[s]);"
                    + "for(var i=0;i<list.length;i++){"
                    + "var e=list[i];"
                    + "var w=e.clientWidth||e.naturalWidth||0;"
                    + "var h=e.clientHeight||e.naturalHeight||0;"
                    + "if(!w||!h)continue;"
                    + "if(w<90||h<90)continue;"
                    + "if(Math.abs(w-h)>Math.max(30,w*0.35))continue;"
                    + "el=e;break;}}"
                    + "if(!el){var cs=document.querySelectorAll('canvas');"
                    + "for(var j=0;j<cs.length;j++){var c=cs[j];"
                    + "if(c.width>=90&&c.height>=90&&Math.abs(c.width-c.height)<30){el=c;break;}}}"
                    + "if(!el)return 'none';"
                    + "var s=(el.tagName==='CANVAS')?el.toDataURL('image/png'):(el.currentSrc||el.src||'');"
                    + "if(!s||s.length<200)return 'none';"
                    + "var sig=''+s.length+s.slice(0,20)+s.slice(-20);"
                    + "if(sig===window.__bxqSig)return 'same';"
                    + "window.__bxqSig=sig;"
                    + "return s;"
                    + "}catch(e){return 'err:'+e;}})()";

    LinearLayout root;
    TextView tip;
    ImageView qrView;
    LinearLayout card;
    WebView wv;
    Handler ui = new Handler(Looper.getMainLooper());

    boolean done = false;
    boolean probing = false;
    int probeCount = 0;
    int cookieTries = 0;
    int cardSize = 600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            build();
        } catch (Throwable e) {
            fallback(e);
        }
    }

    void build() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels;
        int H = dm.heightPixels;
        // 二维码卡片边长：屏宽 80% 与屏高 46% 取小，再兜底夹到合理区间
        cardSize = (int) Math.min(W * 0.78, H * 0.44);
        if (cardSize > 820) cardSize = 820;
        if (cardSize < 320) cardSize = Math.min((int) (W * 0.92), Math.max(320, (int) (H * 0.40)));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F0F12);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView head = new TextView(this);
        head.setText("扫码登录 B 站");
        head.setTextColor(Color.WHITE);
        head.setTextSize(17);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, 40, 0, 8);
        root.addView(head);

        tip = new TextView(this);
        tip.setText("正在获取二维码…");
        tip.setTextColor(Theme.c(0xFFFB7299));
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(24, 0, 24, 10);
        root.addView(tip);

        // 中间区域：白卡 + 二维码，FrameLayout 居中，尺寸写死像素，不受页面布局影响
        FrameLayout stage = new FrameLayout(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(stage, sp);

        // 官网这张图本身没有静区（140px 实体图），卡片白边按边长 7.5% 留，
        // 相当于二维码外的 4 个模块空白——扫码器靠这个定位。
        int pad = (int) (cardSize * 0.075);
        if (pad < 12) pad = 12;
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(pad, pad, pad, pad);

        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setBackgroundColor(Color.WHITE);
        card.addView(qrView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cardSize, cardSize);
        cp.gravity = Gravity.CENTER;
        stage.addView(card, cp);

        // 布局完成后再按「中间区域的实际尺寸」校正一次：
        // 分屏 / 悬浮窗 / 横屏时窗口可能只有一半高，靠这一步保证卡片永远完整居中，
        // 不会像上一版那样跑到右下角或被裁掉。
        final FrameLayout fstage = stage;
        stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int orr, int ob) {
            try {
                int sw = r - l, sh = b - t;
                if (sw <= 0 || sh <= 0) return;
                int s = (int) Math.min(Math.min(sw * 0.88, sh * 0.94), 820);
                if (s < 240) s = Math.min(sw, sh);
                cardSize = s;
                int pd = (int) (s * 0.075);
                if (pd < 12) pd = 12;
                card.setPadding(pd, pd, pd, pd);
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) card.getLayoutParams();
                lp.width = s;
                lp.height = s;
                lp.gravity = Gravity.CENTER;
                card.setLayoutParams(lp);
            } catch (Throwable ignore) { }
            }
        });

        TextView hint = new TextView(this);
        hint.setText("请用 B 站 App 扫码\n确认后自动登录，无需其它操作");
        hint.setTextColor(0xFFB9B9C0);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(6, 1f);
        hint.setPadding(24, 12, 24, 2);
        root.addView(hint);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        Button again = new Button(this);
        again.setText("换一张");
        again.setTextColor(Color.WHITE);
        again.setBackgroundColor(0xFF2B2B2B);
        again.setAllCaps(false);
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        bar.addView(again);

        Button page = new Button(this);
        page.setText("官方页面");
        page.setTextColor(Theme.c(0xFFFB7299));
        page.setBackgroundColor(Color.TRANSPARENT);
        page.setAllCaps(false);
        page.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(
                        WebQrLoginActivity.this, WebViewLoginActivity.class));
                finish();
            }
        });
        bar.addView(page);

        Button sms = new Button(this);
        sms.setText("短信登录");
        sms.setTextColor(Theme.c(0xFFFB7299));
        sms.setBackgroundColor(Color.TRANSPARENT);
        sms.setAllCaps(false);
        sms.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(
                        WebQrLoginActivity.this, WebViewLoginActivity.class));
                finish();
            }
        });
        bar.addView(sms);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 0, 0, 18);
        root.addView(bar, bp);

        // 搬运工 WebView：1px 挂在视图树里（不能 GONE，否则停渲染），只跑官方逻辑
        wv = new WebView(this);
        try {
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUserAgentString(DESKTOP_UA);
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
        } catch (Throwable ignore) { }

        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Throwable ignore) { }
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                try { probeCount = 0; startProbe(); } catch (Throwable ignore) { }
                try { checkCookie(); } catch (Throwable ignore) { }
            }
        });
        root.addView(wv, new LinearLayout.LayoutParams(1, 1));

        setContentView(root);
        wv.loadUrl(LOGIN_URL);

        ui.postDelayed(new Runnable() { @Override public void run() { checkCookie(); } }, 900);
        ui.postDelayed(new Runnable() { @Override public void run() { startProbe(); } }, 1000);
    }

    void startProbe() {
        if (probing) return;
        probing = true;
        probe();
    }

    /** 每 1.1 秒问一次页面：二维码图换了吗？换了就把 base64 拿回来 */
    void probe() {
        if (done || wv == null) return;
        if (probeCount++ > 500) return;
        try {
            wv.evaluateJavascript(JS_SRC, new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    String r = unwrap(v);
                    if (r == null) r = "";
                    if (r.startsWith("data:")) {
                        showQr(r);
                    } else if (r.startsWith("err")) {
                        if (tip != null && qrView.getDrawable() == null)
                            tip.setText("页面脚本异常，可点「官方页面」手动登录");
                    } else if (r.equals("none")) {
                        if (probeCount > 12 && qrView.getDrawable() == null && tip != null)
                            tip.setText("二维码还没出来，可点「换一张」或「官方页面」");
                    }
                }
            });
        } catch (Throwable ignore) { }
        ui.postDelayed(new Runnable() { @Override public void run() { probe(); } }, 1100);
    }

    /** base64 → Bitmap → 居中显示（后台解码，避免主线程卡顿） */
    void showQr(final String dataUri) {
        new Thread(new Runnable() { @Override public void run() {
            Bitmap bmp = null;
            try {
                int comma = dataUri.indexOf(',');
                String b64 = comma > 0 ? dataUri.substring(comma + 1) : dataUri;
                byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            } catch (Throwable ignore) { }
            final Bitmap f = bmp;
            ui.post(new Runnable() { @Override public void run() {
                if (isFinishing() || f == null) {
                    if (f == null && tip != null && qrView.getDrawable() == null)
                        tip.setText("二维码图片解析失败，可点「官方页面」");
                    return;
                }
                try {
                    BitmapDrawable bd = new BitmapDrawable(getResources(), f);
                    // 关掉平滑过滤：放大时用最近邻，边缘锐利，扫码更容易识别
                    bd.setFilterBitmap(false);
                    bd.setAntiAlias(false);
                    qrView.setImageDrawable(bd);
                    tip.setText("请用 B 站 App 扫码，3 分钟内有效");
                } catch (Throwable ignore) { }
            } });
        } }, "bxq-qr-decode").start();
    }

    void reload() {
        done = false;
        probeCount = 0;
        try { if (qrView != null) qrView.setImageDrawable(null); } catch (Throwable ignore) { }
        if (tip != null) tip.setText("正在刷新二维码…");
        try {
            if (wv != null) {
                wv.loadUrl("about:blank");
                wv.loadUrl(LOGIN_URL);
            }
        } catch (Throwable ignore) { }
        ui.postDelayed(new Runnable() { @Override public void run() { checkCookie(); } }, 900);
    }

    /** 每 1.2 秒查 Cookie，出现 SESSDATA 即整套取走 */
    void checkCookie() {
        if (done) return;
        if (cookieTries++ > 600) return;
        try {
            String best = "";
            String[] domains = {"https://www.bilibili.com/", "https://passport.bilibili.com/",
                    "https://account.bilibili.com/"};
            for (String d : domains) {
                try {
                    String one = CookieManager.getInstance().getCookie(d);
                    if (one != null && one.length() > best.length()) best = one;
                } catch (Throwable ignore) { }
            }
            if (best.contains("SESSDATA=")) {
                String sess = cookieVal(best, "SESSDATA");
                if (!sess.isEmpty()) { finishLogin(best, sess); return; }
            }
        } catch (Throwable ignore) { }
        ui.postDelayed(new Runnable() { @Override public void run() { checkCookie(); } }, 1200);
    }

    void finishLogin(final String cookieHeader, final String sessdata) {
        done = true;
        try {
            final String jct = cookieVal(cookieHeader, "bili_jct");
            final String uid = cookieVal(cookieHeader, "DedeUserID");
            BiliApi.addCookie(cookieHeader);
            BiliApi.setLoginCookie(sessdata, jct, uid);
            String full = BiliApi.exportLoginCookie();
            SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
            sp.edit().putString("SESSDATA", sessdata)
                    .putString("bili_jct", jct)
                    .putString("DedeUserID", uid)
                    .putString("full_cookie", full.isEmpty() ? cookieHeader : full)
                    .putLong("login_time", System.currentTimeMillis())
                    .apply();
            if (tip != null) tip.setText("登录成功，正在同步账号…");
        } catch (Throwable ignore) { }

        new Thread(new Runnable() { @Override public void run() {
            String name = "";
            for (int i = 0; i < 4; i++) {
                try {
                    org.json.JSONObject nav = BiliApi.navInfo();
                    name = nav.optString("uname", "");
                    if (!name.isEmpty()) break;
                } catch (Throwable ignore) { }
                try { Thread.sleep(900); } catch (InterruptedException ignore) { }
            }
            final String fName = name;
            ui.post(new Runnable() { @Override public void run() {
                if (isFinishing()) return;
                Toast.makeText(WebQrLoginActivity.this,
                        "登录成功 ✓" + (fName.isEmpty() ? "" : " " + fName), Toast.LENGTH_SHORT).show();
                finish();
            } });
        } }, "bxq-qr-verify").start();
    }

    /** 兜底界面：不闪退，把异常原因直接写在屏幕上 */
    void fallback(Throwable e) {
        String stack = android.util.Log.getStackTraceString(e);
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, "crash.txt"), true));
            pw.println("=== " + new Date() + " WebQrLoginActivity.build");
            pw.println(stack);
            pw.println();
            pw.close();
        } catch (Throwable ignore) { }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0F0F12);
        box.setPadding(28, 60, 28, 28);

        String brief = stack;
        String[] lines = stack.split("\n");
        if (lines.length > 7) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) sb.append(lines[i]).append('\n');
            brief = sb.toString();
        }
        TextView t = new TextView(this);
        t.setText("扫码页打开失败（已避免闪退）\n\n" + brief);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        box.addView(t);

        Button b = new Button(this);
        b.setText("用短信验证码登录");
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(
                        WebQrLoginActivity.this, WebViewLoginActivity.class));
                finish();
            }
        });
        box.addView(b);

        Button cp = new Button(this);
        cp.setText("复制错误详情");
        cp.setAllCaps(false);
        cp.setTag(stack);
        cp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "bxq-crash", String.valueOf(v.getTag())));
                    Toast.makeText(WebQrLoginActivity.this, "已复制，可直接粘贴发我",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) { }
            }
        });
        box.addView(cp);
        setContentView(box);
    }

    static String unwrap(String v) {
        if (v == null || v.equals("null")) return "";
        try { return new org.json.JSONArray("[" + v + "]").getString(0); }
        catch (Throwable ignore) { return v; }
    }

    static String cookieVal(String header, String key) {
        if (header == null) return "";
        for (String pair : header.split(";")) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return pair.substring(eq + 1);
        }
        return "";
    }

    @Override
    public void onBackPressed() { finish(); }

    @Override
    protected void onDestroy() {
        done = true;
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignore) { }
        if (wv != null) {
            try { wv.stopLoading(); wv.destroy(); } catch (Throwable ignore) { }
            wv = null;
        }
        super.onDestroy();
    }
}
