package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 液态玻璃悬浮底栏（v4.14.4 · 三格）。
 *
 * 苹果 Liquid Glass 观感的四个要素：
 *   1) 通透：抓取底栏背后的真实内容，本地高斯模糊后作为半透明背景；
 *   2) 材质：在模糊层上叠一层带渐变的半透明染色，既透出背后又保证文字可读；
 *   3) 边缘：整圈「受光高光」描边（上亮下暗）+ 顶部棱高光 + 极淡彩色色散；
 *   4) 悬浮：三层柔和外投影 + 更大的离底间距。
 *
 * 兼容：全部为 API1 / API22 可用的 Canvas/Paint/Bitmap 基础绘制，模糊用纯 Java
 *       分离式 box blur（不依赖 RenderScript / RenderEffect），无额外权限。
 */
public class LiquidNavBar extends FrameLayout {

    public interface Callback {
        void onTouchDown(float x, float y);
        void onTouchMove(float x, float y);
        void onTouchUp();
        void onHover(int tabIndex);
        void onCommit(int tabIndex);
    }

    private Callback callback;
    /** 槽位 → 真实 tab 索引。底栏三格：{0,1,4} */
    private int[] slots = {0, 1, 4};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final RectF t2 = new RectF();
    private final Path clip = new Path();

    /** 背后内容视图（MainActivity 的 content），用于抓取模糊背景 */
    private View backdropSource;
    /** 模糊后的背景位图 */
    private Bitmap backdrop;

    private boolean dragging;

    public LiquidNavBar(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);   // ViewGroup 默认可能跳过 onDraw，这里必须强制绘制材质
        edge.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void setSlots(int[] s) {
        if (s != null && s.length > 0) slots = s;
    }

    /** 设置需要被模糊的背后内容视图 */
    public void setBackdropSource(View v) {
        this.backdropSource = v;
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /**
     * 抓取背后 content 在底栏所在区域的内容，缩小、模糊后缓存。
     * 低频调用（布局完成 / 切 tab / 回到前台），不做每帧刷新，避免抢播放带宽与算力。
     */
    public void captureBackdrop() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || backdropSource == null) return;
        try {
            // 缩小后再模糊：像素更少、模糊更柔和，开销极低
            int bw = Math.min(w, 480);
            int bh = Math.max(1, Math.round(h * (bw / (float) w)));
            Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);

            // 以页面背景色铺底（content 本身无背景，避免透明处抓成黑块）
            c.drawColor(MainActivity.C_BG);

            int[] navLoc = new int[2], srcLoc = new int[2];
            getLocationOnScreen(navLoc);
            backdropSource.getLocationOnScreen(srcLoc);
            float dx = navLoc[0] - srcLoc[0];
            float dy = navLoc[1] - srcLoc[1];
            float sx = bw / (float) w, sy = bh / (float) h;
            c.scale(sx, sy);
            c.translate(-dx, -dy);
            backdropSource.draw(c);

            int[] px = new int[bw * bh];
            small.getPixels(px, 0, bw, 0, 0, bw, bh);
            GlassBlur.blurInPlace(px, bw, bh, 6);   // 【v4.23.0】共用模糊内核
            small.setPixels(px, 0, bw, 0, 0, bw, bh);

            if (backdrop != null && !backdrop.isRecycled()) backdrop.recycle();
            backdrop = small;
        } catch (Throwable ignore) {
            // 抓取失败时保留上一次的模糊背景，或退回实色，绝不影响交互
        }
        invalidate();
    }

    /** 兼容旧调用 */
    public void refreshBackdrop() {
        captureBackdrop();
    }

    @Override
    protected void onDraw(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float r = h / 2f;
        final boolean dark = MainActivity.DARK;
        rect.set(0, 0, w, h);

        // ---------- 1) 外投影：三层递减，制造明显悬浮感 ----------
        int shadowLayers = 3;
        for (int i = shadowLayers; i >= 1; i--) {
            float off = dp(i * 2.2f);
            shadow.setColor(dark
                    ? (i == 1 ? 0x4D000000 : (i == 2 ? 0x2A000000 : 0x16000000))
                    : (i == 1 ? 0x38000000 : (i == 2 ? 0x1F000000 : 0x12000000)));
            tmp.set(-off * 0.42f, -off * 0.22f, w + off * 0.42f, h + off * 1.05f);
            cv.drawRoundRect(tmp, r + off * 0.45f, r + off * 0.45f, shadow);
        }

        // ---------- 2) 通透主体：模糊背景 + 半透明染色 ----------
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        final int sc = cv.save();
        cv.clipPath(clip);

        if (backdrop != null && !backdrop.isRecycled()) {
            bmpPaint.setAlpha(255);
            cv.drawBitmap(backdrop, null, rect, bmpPaint);
        } else {
            fill.setShader(null);
            fill.setColor(dark ? 0xF216181D : 0xF4FFFFFF);
            cv.drawRoundRect(rect, r, r, fill);
        }

        // 染色层：v4.14.5 整体再降一档不透明度，让背后内容透得更多、更"通透"
        if (dark) {
            gloss.setShader(new LinearGradient(0, 0, 0, h,
                    0x3D12141A, 0x660B0C10, Shader.TileMode.CLAMP));
        } else {
            gloss.setShader(new LinearGradient(0, 0, 0, h,
                    0x7AFFFFFF, 0xA6ECEDF3, Shader.TileMode.CLAMP));
        }
        cv.drawRect(rect, gloss);
        gloss.setShader(null);

        // 顶部受光
        gloss.setShader(new LinearGradient(0, 0, 0, dp(10),
                dark ? 0x24FFFFFF : 0x9FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(rect, gloss);
        gloss.setShader(null);

        // 底部内暗边（玻璃厚度感）
        gloss.setShader(new LinearGradient(0, h * 0.66f, 0, h,
                0x00000000, dark ? 0x2E000000 : 0x14000000, Shader.TileMode.CLAMP));
        cv.drawRect(rect, gloss);
        gloss.setShader(null);

        // 左右端侧光，让弧面立起来
        int sideA = dark ? 0x2FFFFFFF : 0x66FFFFFF;
        gloss.setShader(new LinearGradient(0, 0, w * 0.18f, 0,
                sideA, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w * 0.18f, h, gloss);
        gloss.setShader(new LinearGradient(w, 0, w - w * 0.18f, 0,
                sideA, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(w - w * 0.18f, 0, w, h, gloss);
        gloss.setShader(null);

        cv.restoreToCount(sc);

        // ---------- 3) 边缘高光：v4.14.5 合并为单圈细的半透明描边 ----------
        // 原来叠了 edge(1.6) + 色散 rim(1.0) + 顶棱 rim(1.1) 三圈，视觉上像套了两层边框。
        // 现在只留一圈 1dp、上亮下暗且整体半透明的高光，通透干净、不再有双边框。
        edge.setStrokeWidth(dp(1f));
        edge.setShader(new LinearGradient(0, 0, 0, h,
                dark ? 0x66FFFFFF : 0x7AFFFFFF,
                dark ? 0x14FFFFFF : 0x26FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRoundRect(rect, r, r, edge);
        edge.setShader(null);

        // ---------- 4) 按下提亮 ----------
        if (dragging) {
            gloss.setShader(null);
            gloss.setColor(dark ? 0x1AFFFFFF : 0x26FFFFFF);
            cv.drawRoundRect(rect, r, r, gloss);
        }
    }

    private int tabAt(float x) {
        int n = slots.length;
        int idx = (int) (x / (getWidth() / (float) n));
        idx = idx < 0 ? 0 : (idx >= n ? n - 1 : idx);
        return slots[idx];
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                invalidate();
                if (callback != null) {
                    callback.onTouchDown(getLeft() + x, getTop() + y);
                    callback.onHover(tabAt(x));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (callback != null) {
                    callback.onTouchMove(getLeft() + x, getTop() + y);
                    callback.onHover(tabAt(x));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && callback != null) {
                    boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                    callback.onTouchUp();
                    if (up) callback.onCommit(tabAt(x));
                }
                dragging = false;
                invalidate();
                break;
        }
        return true;
    }
}
