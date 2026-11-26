package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 【v4.14.1】小说吧内置浏览器：打开正版小说平台网址，并套用小说吧的护眼模式。
 * 纯书签导航 + WebView，不破解、不抓取、不内置任何盗版内容。
 */
public class NovelWebActivity extends Activity {

    WebView wv;
    TextView eyeBtn;
    String pageUrl, pageTitle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pageUrl = getIntent().getStringExtra("url");
        pageTitle = getIntent().getStringExtra("title");
        if (pageTitle == null || pageTitle.isEmpty()) pageTitle = "小说阅读";
        try {
            setContentView(build());
            if (pageUrl == null || pageUrl.isEmpty()) {
                Toast.makeText(this, "无效的网址", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("浏览器打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(20), dp(60), dp(20), dp(20));
            setContentView(tv);
        }
    }

    int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v); }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    int[] eyeColors() {
        int mode = Prefs.novelEyeMode(this);
        switch (mode) {
            case 1: return new int[]{0xFFF5E6C8, 0xFF4A3B22};
            case 2: return new int[]{0xFFC7EDCC, 0xFF23331F};
            case 3: return new int[]{0xFF121418, 0xFFC8CCD4};
            default:
                boolean dark = MainActivity.DARK;
                return dark ? new int[]{0xFF16181D, 0xFFE8EAF0}
                        : new int[]{0xFFF7F7F8, 0xFF18191C};
        }
    }

    TextView txt(String s, int color, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    String modeName() {
        switch (Prefs.novelEyeMode(this)) {
            case 1: return "米黄";
            case 2: return "豆绿";
            case 3: return "夜间";
            default: return "普通";
        }
    }

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(eyeColors()[0]);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(6), dp(8), dp(6));
        top.setBackgroundColor(eyeColors()[0]);
        TextView back = txt("←", eyeColors()[1], 20, true);
        back.setPadding(dp(10), dp(6), dp(10), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = txt(pageTitle, eyeColors()[1], 15, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        eyeBtn = txt("护眼·" + modeName(), eyeColors()[1], 12, false);
        eyeBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        eyeBtn.setBackground(round(eyeColors()[0] == 0xFF121418 ? 0xFF2A2F38 : 0xFFEDEFF2, 12));
        eyeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int m = (Prefs.novelEyeMode(NovelWebActivity.this) + 1) % 4;
                Prefs.setNovelEyeMode(NovelWebActivity.this, m);
                eyeBtn.setText("护眼·" + modeName());
                root.setBackgroundColor(eyeColors()[0]);
                top.setBackgroundColor(eyeColors()[0]);
                applyEye();
            }
        });
        top.addView(eyeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView refresh = txt("刷新", eyeColors()[1], 12, false);
        refresh.setPadding(dp(8), dp(6), dp(8), dp(6));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (wv != null) wv.reload(); }
        });
        top.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView openOuter = txt("浏览器", eyeColors()[1], 12, false);
        openOuter.setPadding(dp(8), dp(6), dp(4), dp(6));
        openOuter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)));
                } catch (Throwable e) {
                    Toast.makeText(NovelWebActivity.this, "没有可用浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        top.addView(openOuter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        // WebView
        wv = new WebView(this);
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setUserAgentString(NovelUtil.UA);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
            @Override public void onPageFinished(WebView view, String url) {
                applyEye();
            }
        });
        wv.loadUrl(pageUrl);
        root.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** 按护眼模式给网页注入样式；普通模式移除注入，保持网页原样。 */
    void applyEye() {
        if (wv == null) return;
        int mode = Prefs.novelEyeMode(this);
        if (mode == 0) {
            wv.evaluateJavascript(
                    "(function(){var e=document.getElementById('bxq-eye');"
                            + "if(e&&e.parentNode)e.parentNode.removeChild(e);})()", null);
            return;
        }
        int[] c = eyeColors();
        String bg = String.format("#%06X", 0xFFFFFF & c[0]);
        String fg = String.format("#%06X", 0xFFFFFF & c[1]);
        String link = mode == 3 ? "#8AB4F8" : "#1E6FBB";
        String css = "html,body{background-color:" + bg + "!important;color:" + fg
                + "!important;}a{color:" + link + "!important;}";
        String js = "(function(){var e=document.getElementById('bxq-eye');"
                + "if(!e){e=document.createElement('style');e.id='bxq-eye';"
                + "document.head.appendChild(e);}e.innerHTML='" + css + "';})()";
        wv.evaluateJavascript(js, null);
    }

    @Override public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
