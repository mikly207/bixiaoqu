package com.bixiaoqu.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 全屏液态透镜覆盖层（参照酷安 Liquid Glass）。
 *
 * 与 v3.0 的区别：
 *  - 本层 MATCH_PARENT 覆盖整个屏幕，而不是局限在底栏那一小块，
 *    因此彩色光晕可以「扩散到底栏胶囊外面」。
 *  - 放大镜真实放大底栏背后的画面，叠加玻璃球面反光与彩色色散环。
 */
public class LiquidLensLayer extends View {

    private Bitmap behind;
    private final Paint magnify = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chroma = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cx = 0f, cy = 0f, r = 0f, maxR = 0f;
    private float bandTop = -1f, bandBottom = -1f;   // 只允许绘制的纵向条带（底栏范围）
    private ValueAnimator anim;

    public LiquidLensLayer(Context c) {
        super(c);
        chroma.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
        maxR = dp(34);   // 透镜半径收敛到只覆盖底栏附近
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 抓取整屏快照作为放大镜采样源（须在透镜出现前调用） */
    void captureBehind(View root) {
        r = 0f;
        try {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(b));
            behind = b;
        } catch (Throwable ignore) {
            behind = null;
        }
    }

    /** 限定透镜绘制条带（底栏上下各溢出一点点），坐标为本层坐标系 */
    void setBand(float top, float bottom) {
        this.bandTop = top;
        this.bandBottom = bottom;
    }

    void start(float x, float y) {
        cx = x; cy = y;
        animateTo(maxR);
    }

    void move(float x, float y) {
        cx = x; cy = y;
        invalidate();
    }

    void end() {
        animateTo(0f);
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (r <= 1f) return;
        // 限制在底栏条带内：上下超出该条带的部分不绘制，凸出量约 3~4mm
        boolean banded = bandTop >= 0 && bandBottom > bandTop;
        int sc = banded ? cv.save() : -1;
        if (banded) cv.clipRect(0, bandTop, getWidth(), bandBottom);
        drawHalo(cv);        // 贴近底栏的柔和光晕
        drawMagnifier(cv);   // 放大镜
        drawRing(cv);        // 彩色色散环 + 边缘反光
        if (sc >= 0) cv.restoreToCount(sc);
    }

    /** 向外扩散的柔和光晕：收敛到底栏附近（上下溢出约 5mm），不再四处扩散 */
    private void drawHalo(Canvas cv) {
        float outer = r * 1.42f;
        // 白色柔光基底
        halo.setShader(new RadialGradient(cx, cy, outer,
                new int[]{0x00000000, 0x22FFFFFF, 0x00000000},
                new float[]{Math.max(0f, r / outer - 0.10f), r / outer, 1f},
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, outer, halo);
        // 三层彩色扩散环，紧贴圆缘、不向外远扩
        int[] cc = {0x2EFF7EB6, 0x2AA78BFA, 0x2667E8F9};
        for (int k = 0; k < cc.length; k++) {
            float rr = outer * (1f - k * 0.07f);
            halo.setShader(new RadialGradient(cx, cy, rr,
                    new int[]{0x00000000, cc[k], 0x00000000},
                    new float[]{Math.max(0f, r / rr - 0.05f),
                            Math.min(0.94f, r / rr + 0.08f), 1f},
                    Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, rr, halo);
        }
    }

    /** 放大镜：裁剪圆形，用放大后的快照填充，再叠一层玻璃球面反光 */
    private void drawMagnifier(Canvas cv) {
        cv.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        cv.clipPath(clip);

        if (behind != null && behind.getWidth() > 0) {
            BitmapShader bs = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float zoom = 1.5f;
            m.setScale(zoom, zoom);
            float px = clamp(cx * zoom - cx, 0, behind.getWidth() * (zoom - 1f));
            float py = clamp(cy * zoom - cy, 0, behind.getHeight() * (zoom - 1f));
            m.postTranslate(-px, -py);
            bs.setLocalMatrix(m);
            magnify.setShader(bs);
            cv.drawRect(cx - r, cy - r, cx + r, cy + r, magnify);
            magnify.setShader(null);
        } else {
            magnify.setColor(Color.parseColor("#33555555"));
            cv.drawCircle(cx, cy, r, magnify);
        }

        gloss.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 0.12f,
                Color.parseColor("#66FFFFFF"), Color.parseColor("#00FFFFFF"),
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, gloss);
        gloss.setShader(null);
        cv.restore();
    }

    /** 彩色色散环 + 边缘白色反光 */
    private void drawRing(Canvas cv) {
        int[] cols = {
                Color.parseColor("#99FF7EB6"), Color.parseColor("#99A78BFA"),
                Color.parseColor("#9967E8F9"), Color.parseColor("#99FFD166"),
                Color.parseColor("#99FF7EB6")
        };
        chroma.setStrokeWidth(dp(3));
        chroma.setShader(new SweepGradient(cx, cy, cols, null));
        cv.drawCircle(cx, cy, r - dp(1), chroma);
        chroma.setShader(null);

        rim.setStrokeWidth(dp(1.2f));
        rim.setColor(Color.parseColor("#BBFFFFFF"));
        cv.drawCircle(cx, cy, r - dp(3), rim);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(r, target);
        anim.setDuration(target > 0 ? 260 : 200);
        anim.setInterpolator(new DecelerateInterpolator(2.2f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                r = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }
}
