package com.bixiaoqu.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 深色模式设置页（v4.10.0）。
 *
 * 两项：
 *   1) 深色跟随系统 —— 打开后完全交给系统的深浅色，App 内两个开关都不再强制；
 *   2) 深色模式     —— 手动指定深/浅。点它会自动关闭「跟随系统」。
 *
 * 改完直接 recreate() 整页重算配色，返回上一页时那边 onResume 也会检测到
 * 主题指纹变化并重建，所以不会有「设置页变了、主页没变」的错配。
 */
public class ThemeActivity extends Activity {

    Toggle sysToggle, darkToggle;

    /** 一次性同步两个开关的显示，避免互相触发监听 */
    boolean syncing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        appliedStamp = MainActivity.themeStamp;
        try {
            setContentView(build());
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("深色模式页加载失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(30), dp(60), dp(30), dp(30));
            setContentView(tv);
        }
    }

    int appliedStamp = -1;

    /** 从别的页面回来（比如系统深浅色变了）也要跟上 */
    @Override protected void onResume() {
        super.onResume();
        if (appliedStamp != MainActivity.themeStampOf(this)) {
            recreate();
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // ---- 顶栏：返回 + 居中标题 ----
        FrameLayout top = new FrameLayout(this);
        top.setPadding(dp(6), dp(8), dp(6), dp(8));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back, new FrameLayout.LayoutParams(dp(46), dp(44)));

        TextView title = new TextView(this);
        title.setText("深色模式");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER));

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int mode = Prefs.themeMode(this);
        final boolean follow = mode == Prefs.THEME_SYSTEM;
        final boolean darkNow = (MainActivity.themeStampOf(this) % 10) == 1;

        // ---- 第 1 行：深色跟随系统 ----
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setBackgroundColor(MainActivity.C_CARD);
        row1.setPadding(dp(16), dp(14), dp(16), dp(14));
        row1.setClickable(true);

        LinearLayout col1 = new LinearLayout(this);
        col1.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText("深色跟随系统");
        t1.setTextColor(MainActivity.C_FG);
        t1.setTextSize(16);
        col1.addView(t1);

        TextView s1 = new TextView(this);
        s1.setText("开启后，将跟随系统打开或关闭深色模式");
        s1.setTextColor(MainActivity.C_SUB);
        s1.setTextSize(12);
        s1.setPadding(0, dp(4), 0, 0);
        col1.addView(s1);

        row1.addView(col1, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sysToggle = new Toggle(this);
        sysToggle.setChecked(follow);
        LinearLayout.LayoutParams tp1 = new LinearLayout.LayoutParams(dp(46), dp(27));
        tp1.leftMargin = dp(12);
        row1.addView(sysToggle, tp1);

        row1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncing) return;
                boolean next = !sysToggle.isChecked();
                if (next) {
                    Prefs.setThemeMode(ThemeActivity.this, Prefs.THEME_SYSTEM);
                } else {
                    Prefs.setThemeMode(ThemeActivity.this,
                            MainActivity.DARK ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
                }
                applyAndRebuild();
            }
        });
        root.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 分割线 ----
        View line = new View(this);
        line.setBackgroundColor(MainActivity.DARK ? 0xFF23262C : 0xFFE6E8EC);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ---- 第 2 行：深色模式 ----
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setBackgroundColor(MainActivity.C_CARD);
        row2.setPadding(dp(16), dp(16), dp(16), dp(16));
        row2.setClickable(true);

        TextView t2 = new TextView(this);
        t2.setText("深色模式");
        t2.setTextColor(MainActivity.C_FG);
        t2.setTextSize(16);
        row2.addView(t2, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        darkToggle = new Toggle(this);
        darkToggle.setChecked(darkNow);
        row2.addView(darkToggle, new LinearLayout.LayoutParams(dp(46), dp(27)));

        row2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncing) return;
                boolean next = !darkToggle.isChecked();
                // 手动拨这个开关 = 不再跟随系统
                Prefs.setThemeMode(ThemeActivity.this,
                        next ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
                applyAndRebuild();
            }
        });
        root.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 说明 ----
        TextView hint = new TextView(this);
        hint.setText("浅色模式下全应用（首页、我的、留声机、游戏厅、设置）都会切换为白底深字，"
                + "视频播放页仍保持黑底，避免影响观感。");
        hint.setTextColor(MainActivity.C_SUB);
        hint.setTextSize(12);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** 写入后整页重建：配色、开关位置、背景全部按新主题重算 */
    void applyAndRebuild() {
        syncing = true;
        MainActivity.applyTheme(this);
        recreate();
    }

    /**
     * 自绘开关：iOS 风格胶囊 + 白色圆点，开启为 B 站粉。
     * 用 300ms 的 ValueAnimator 做位移插值，比直接跳变自然。
     */
    static class Toggle extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean checked;
        private float anim = 0f;
        private ValueAnimator va;

        Toggle(android.content.Context c) {
            super(c);
            dot.setColor(Color.WHITE);
        }

        boolean isChecked() { return checked; }

        void setChecked(boolean v) {
            checked = v;
            anim = v ? 1f : 0f;
            invalidate();
        }

        void animateTo(boolean v) {
            if (va != null) va.cancel();
            va = ValueAnimator.ofFloat(anim, v ? 1f : 0f);
            va.setDuration(190);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    anim = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            va.start();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float rad = h / 2f;
            r.set(0, 0, w, h);

            boolean dark = MainActivity.DARK;
            int off = dark ? 0xFF3A3F48 : 0xFFD5D8DE;
            int on = MainActivity.C_ACC;
            // 简单线性混色，避免额外依赖
            int c = blend(off, on, anim);
            bg.setColor(c);
            cv.drawRoundRect(r, rad, rad, bg);

            float pad = h * 0.09f;
            float d = h - pad * 2;
            float x = pad + (w - pad * 2 - d) * anim;
            cv.drawCircle(x + d / 2f, h / 2f, d / 2f, dot);
        }

        static int blend(int a, int b, float t) {
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg2 = (b >> 8) & 0xFF, bb = b & 0xFF;
            int rr = (int) (ar + (br - ar) * t);
            int gg = (int) (ag + (bg2 - ag) * t);
            int bl = (int) (ab + (bb - ab) * t);
            return 0xFF000000 | (rr << 16) | (gg << 8) | bl;
        }
    }
}
