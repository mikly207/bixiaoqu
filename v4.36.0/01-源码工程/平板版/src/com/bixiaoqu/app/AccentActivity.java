package com.bixiaoqu.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 【v4.33.0】主题颜色选择页（仅非 iOS 版入口）。
 *
 * 上半部分：8 个推荐色（红橙黄绿青蓝紫粉）+ 实时预览（色点 / 关注胶囊 / 进度条），
 * 下半部分：自定义调色 —— 色相条 + 饱和度 + 明度，全部实时生效。
 *
 * 之所以不做「确定 / 取消」：调色这个东西必须边调边看，弹二次确认反而打断手感。
 * 每一次改动直接落 Prefs，返回后各页面靠 themeStamp 指纹自动重建。
 */
public class AccentActivity extends Activity {

    /** 当前 HSV（h: 0~360，s/v: 0~100） */
    float h = 340f, s = 62f, v = 98f;

    int appliedStamp = -1;

    View previewDot, sampleBar;
    TextView sampleBtn;
    TextView previewHex, previewNote;
    HueBar hueBar;
    SeekBar satBar, valBar;
    TextView satVal, valVal;
    View[] swatchRings = new View[Theme.PRESETS.length];

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        appliedStamp = MainActivity.themeStampOf(this);
        int c = Prefs.accent(this);
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        h = hsv[0]; s = hsv[1] * 100f; v = hsv[2] * 100f;
        try {
            setContentView(build());
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("主题色页面加载失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(24), dp(60), dp(24), dp(24));
            setContentView(tv);
        }
        syncAll();
    }

    @Override protected void onResume() {
        super.onResume();
        if (appliedStamp != MainActivity.themeStampOf(this)) recreate();
    }

    int dp(float x) {
        return (int) (getResources().getDisplayMetrics().density * x);
    }

    // ------------------------------------------------------------ 布局

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { finish(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText("主题颜色");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(6);
        top.addView(title, tp);
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(28));
        sv.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(section("预览"));
        col.addView(previewCard());
        col.addView(section("推荐颜色"));
        col.addView(swatchCard());
        col.addView(section("自定义"));
        col.addView(customCard());

        TextView note = new TextView(this);
        note.setText("选择后立即生效：底栏、频道栏、按钮、进度条、播放器控件都会换成这个颜色。");
        note.setTextColor(MainActivity.C_SUB);
        note.setTextSize(11.5f);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(6), dp(14), dp(6), 0);
        col.addView(note);
        return root;
    }

    TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(MainActivity.C_SUB);
        v.setTextSize(12);
        v.setLetterSpacing(0.06f);
        v.setPadding(dp(6), dp(16), dp(6), dp(8));
        return v;
    }

    LinearLayout cardBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(MainActivity.C_CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), MainActivity.DARK ? 0x14FFFFFF : 0x0D000000);
        l.setBackground(g);
        return l;
    }

    /** 预览：大色点 + 名称 + 一个跟随色的胶囊按钮和一条进度条 */
    View previewCard() {
        LinearLayout card = cardBox();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        previewDot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Theme.acc());
        previewDot.setBackground(dotBg);
        row.addView(previewDot, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(14);

        TextView name = new TextView(this);
        name.setText("主题颜色");
        name.setTextColor(MainActivity.C_FG);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);

        previewHex = new TextView(this);
        previewHex.setTextSize(12);
        previewHex.setPadding(0, dp(3), 0, 0);
        texts.addView(previewHex);

        row.addView(texts, tlp);

        sampleBtn = new TextView(this);
        sampleBtn.setText("关注");
        sampleBtn.setTextColor(Color.WHITE);
        sampleBtn.setTextSize(13);
        sampleBtn.setGravity(Gravity.CENTER);
        sampleBtn.setPadding(dp(16), dp(7), dp(16), dp(7));
        row.addView(sampleBtn);

        card.addView(row);

        sampleBar = new View(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        blp.topMargin = dp(16);
        card.addView(sampleBar, blp);

        previewNote = new TextView(this);
        previewNote.setText("进度条 / 选中态");
        previewNote.setTextSize(10.5f);
        previewNote.setPadding(0, dp(7), 0, 0);
        card.addView(previewNote);
        return card;
    }

    /** 8 个推荐色：4 列 × 2 行，选中态是「外圈 + 对勾」 */
    View swatchCard() {
        LinearLayout card = cardBox();
        card.setPadding(dp(12), dp(14), dp(12), dp(14));

        for (int rowI = 0; rowI < 2; rowI++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 4; i++) {
                final int idx = rowI * 4 + i;
                row.addView(swatch(idx), new LinearLayout.LayoutParams(
                        0, dp(66), 1f));
            }
            card.addView(row);
        }
        return card;
    }

    View swatch(final int idx) {
        final int color = Theme.PRESETS[idx];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);

        // 外圈（选中时显形）
        final LinearLayout ring = new LinearLayout(this);
        ring.setGravity(Gravity.CENTER);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setColor(Color.TRANSPARENT);
        rg.setStroke(dp(2), MainActivity.C_FG);
        ring.setBackground(rg);
        swatchRings[idx] = ring;

        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(color);
        dot.setBackground(dg);
        ring.addView(dot, new LinearLayout.LayoutParams(dp(38), dp(38)));
        box.addView(ring, new LinearLayout.LayoutParams(dp(50), dp(50)));

        TextView nm = new TextView(this);
        nm.setText(Theme.NAMES[idx]);
        nm.setTextColor(MainActivity.C_SUB);
        nm.setTextSize(11);
        nm.setGravity(Gravity.CENTER);
        nm.setPadding(0, dp(4), 0, 0);
        box.addView(nm);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                Theme.set(AccentActivity.this, color);
                float[] hsv = new float[3];
                Color.colorToHSV(color, hsv);
                h = hsv[0]; s = hsv[1] * 100f; v = hsv[2] * 100f;
                syncAll();
                Bounce.pressIn(x);
                Bounce.release(x);
            }
        });
        return box;
    }

    View customCard() {
        LinearLayout card = cardBox();
        card.setPadding(dp(18), dp(16), dp(18), dp(18));

        hueBar = new HueBar(this);
        hueBar.setHue(h);
        hueBar.setOnHue(new HueBar.OnHue() {
            @Override public void onHue(float hue) {
                h = hue;
                commitCustom();
            }
        });
        card.addView(hueBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        LinearLayout sRow = sliderRow("饱和度");
        satBar = (SeekBar) sRow.getTag();
        // 【v4.34.0】防御性取子视图：sliderRow 是自己拼的，结构不会变，
        // 但一旦以后调整了行内子 View 顺序，这里就会 ClassCastException 崩掉整个取色页。
        satVal = childText(sRow, 2);
        satBar.setMax(100);
        satBar.setProgress((int) s);
        card.addView(sRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout vRow = sliderRow("明度");
        valBar = (SeekBar) vRow.getTag();
        valVal = childText(vRow, 2);
        valBar.setMax(100);
        valBar.setProgress((int) v);
        card.addView(vRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        SeekBar.OnSeekBarChangeListener lis = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                s = satBar.getProgress();
                v = valBar.getProgress();
                commitCustom();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        };
        satBar.setOnSeekBarChangeListener(lis);
        valBar.setOnSeekBarChangeListener(lis);
        return card;
    }

    /** 【v4.34.0】安全地取第 idx 个子 View 且要求它是 TextView，否则返回 null */
    static TextView childText(ViewGroup g, int idx) {
        if (g == null) return null;
        try {
            if (idx < 0 || idx >= g.getChildCount()) return null;
            View v = g.getChildAt(idx);
            return (v instanceof TextView) ? (TextView) v : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    LinearLayout sliderRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(MainActivity.C_FG);
        l.setTextSize(13);
        row.addView(l, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar sb = new SeekBar(this);
        row.addView(sb, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setTextColor(MainActivity.C_SUB);
        val.setTextSize(11.5f);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(dp(46),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setTag(sb);
        return row;
    }

    // ------------------------------------------------------------ 逻辑

    int currentColor() {
        float sv = Math.max(0.0001f, s / 100f);
        float vv = Math.max(0.0001f, v / 100f);
        return Color.HSVToColor(new float[]{h % 360f, sv, vv});
    }

    void commitCustom() {
        int c = currentColor();
        Theme.set(this, c);
        updatePreview(c);
        if (hueBar != null) hueBar.setHue(h);
        satVal.setText((int) s + "%");
        valVal.setText((int) v + "%");
        if (satBar != null) tint(satBar);
    }

    void syncAll() {
        int c = Theme.acc();
        updatePreview(c);
        if (hueBar != null) hueBar.setHue(h);
        if (satBar != null) {
            satBar.setProgress((int) s);
            valBar.setProgress((int) v);
            tint(satBar);
        }
        satVal.setText((int) s + "%");
        valVal.setText((int) v + "%");
        int pi = Theme.presetIndex(c);
        for (int i = 0; i < swatchRings.length; i++) {
            GradientDrawable g = (GradientDrawable) swatchRings[i].getBackground();
            g.setStroke(dp(2), i == pi ? MainActivity.C_FG : Color.TRANSPARENT);
        }
    }

    void tint(SeekBar sb) {
        try {
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.acc()));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.acc()));
        } catch (Throwable ignore) { }
    }

    void updatePreview(int c) {
        GradientDrawable dotBg = (GradientDrawable) previewDot.getBackground();
        dotBg.setColor(c);

        GradientDrawable btn = new GradientDrawable();
        btn.setCornerRadius(dp(18));
        btn.setColor(c);
        sampleBtn.setBackground(btn);

        GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(dp(2));
        bar.setColor(c);
        sampleBar.setBackground(bar);

        String hex = String.format("#%06X", c & 0xFFFFFF);
        previewHex.setText(hex + "  ·  " + Theme.nameOf(this));
        previewHex.setTextColor(c);
        previewNote.setTextColor(MainActivity.C_SUB);
    }

    // ------------------------------------------------------------ 色相条

    /**
     * 色相条：彩虹渐变 + 可拖动指示器。
     * 用 LinearGradient 的 7 个停靠点近似色相环（红→黄→绿→青→蓝→品红→红），
     * 比自绘色相环省一层遮罩，触控命中也更直观。
     */
    static class HueBar extends View {
        interface OnHue { void onHue(float hue); }

        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        LinearGradient lg;
        float hue = 340f;
        OnHue cb;

        HueBar(android.content.Context c) {
            super(c);
            indicator.setStyle(Paint.Style.STROKE);
            indicator.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2.4f);
            indicator.setColor(Color.WHITE);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            indicator.setShadowLayer(4f, 0f, 1f, 0x66000000);
        }

        void setOnHue(OnHue l) { cb = l; }

        void setHue(float h) {
            hue = h;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
            super.onSizeChanged(w, hh, ow, oh);
            int pad = (int) (4 * getResources().getDisplayMetrics().density);
            r.set(pad, pad, w - pad, hh - pad);
            lg = new LinearGradient(r.left, 0, r.right, 0, new int[]{
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
            }, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas c) {
            if (lg == null) return;
            p.setShader(lg);
            float rad = r.height() / 2f;
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);

            float x = r.left + r.width() * (hue % 360f) / 360f;
            float cx = Math.min(r.right, Math.max(r.left, x));
            c.drawCircle(cx, r.centerY(), rad * 0.92f, indicator);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN
                    || e.getAction() == MotionEvent.ACTION_MOVE) {
                float pad = 4 * getResources().getDisplayMetrics().density;
                float left = pad, right = getWidth() - pad;
                float t = (e.getX() - left) / Math.max(1f, right - left);
                t = Math.max(0f, Math.min(1f, t));
                hue = t * 359.9f;
                invalidate();
                if (cb != null) cb.onHue(hue);
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
