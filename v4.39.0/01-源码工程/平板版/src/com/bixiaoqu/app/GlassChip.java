package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 薄玻璃片材质：控制条上的胶囊按钮、圆形按钮。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 【v4.28.0 —— 删掉多余的光影与结构线】
 *
 *   ▸ 旧版一个胶囊按钮上叠了 7 道 STROKE（每道都内缩不同半径）：
 *       边缘高光 / 左上热点 / 接触暗线 / 内表面二次反射 / 色散 / 顶棱 / 底缘反光
 *     每一道都是「一圈线」。七圈套在一起，按钮看起来就是一个描边很讲究的白框 ——
 *     用户截图里那些"多余的结构线"就是它们。
 *
 *   ▸ 真玻璃片只有两样东西：
 *       ① 一片极淡的乳白雾（把底下的画面「化开」一点，让文字立得住）；
 *       ② 一条极细的折射亮线，且**只有一条**。
 *     圆钮再额外保留一点"光从左上来"的方向暗示 —— 用**同一条线**的
 *     径向亮度差表达，而不是再叠第二道、第三道线。
 *
 *   ▸ 这一版因此从 7 道降到 1 道：
 *       主体雾 0x0A→0x04（极透）
 *       单道描边：左上 0x38（22%）径向衰减到右下 0x14（8%）
 *       按下提亮
 *
 *   ▸ 顺带的内存收益：每实例 Shader 8 个 → 2 个，Paint 7 个 → 2 个。
 *     一屏通常有 6 个胶囊/圆钮，省下 36 个 native Shader 对象。
 *
 * API22 起可用，没有 RenderScript、没有 RenderEffect、没有额外线程。
 */
public class GlassChip extends Drawable {

    // 【v4.28.0】两个 Paint 就够：填色（主体雾）/ 描边（唯一一道折射线）
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF box = new RectF();
    private final RectF inset = new RectF();
    private final Path clipPath = new Path();
    private final float den;

    /** 圆角（px）；circle=true 时忽略 */
    private final float radiusPx;
    private final boolean circle;
    /** 浅色主题：玻璃发灰、发暗（白雾换黑雾、白边换暗边） */
    private final boolean light;

    private boolean pressed;
    private int alpha = 255;

    // 着色器按尺寸缓存：静止时零开销，按下态也不重建
    private int shW, shH;
    private Shader shFog, shEdge;

    public GlassChip(Context c, float radiusDp, boolean circle, boolean light) {
        den = c.getResources().getDisplayMetrics().density;
        radiusPx = radiusDp * den;
        this.circle = circle;
        this.light = light;
        pEdge.setStyle(Paint.Style.STROKE);
    }

    // ------------------------------------------------------------ 工厂

    /** 胶囊形玻璃片（按钮） */
    public static GlassChip pill(Context c, float radiusDp) {
        return new GlassChip(c, radiusDp, false, false);
    }

    /** 浅色主题下的胶囊 */
    public static GlassChip pillLight(Context c, float radiusDp) {
        return new GlassChip(c, radiusDp, false, true);
    }

    /** 正圆玻璃片（播放 / 全屏 / 更多） */
    public static GlassChip round(Context c) {
        return new GlassChip(c, 0, true, false);
    }

    /**
     * 玻璃圆钮图标：图标本体缩到 76%，四周留白由背景的柔光补足，
     * 视觉上比「图标塞满一个深色圆饼」轻得多。
     */
    public static ImageView icon(Context c, String name, int sizeDp) {
        ImageView v = new ImageView(c);
        float d = c.getResources().getDisplayMetrics().density;
        int px = (int) (sizeDp * d + 0.5f);
        v.setBackground(round(c));
        v.setScaleType(ImageView.ScaleType.CENTER);
        int pad = (int) (px * 0.22f);
        v.setPadding(pad, pad, pad, pad);
        int r = Ic.id(c, name);
        if (r != 0) v.setImageResource(r);
        v.setColorFilter(0xFFFFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }

    // ------------------------------------------------------------ 状态

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] s) {
        boolean p = false;
        for (int i = 0; i < s.length; i++) {
            if (s[i] == android.R.attr.state_pressed) {
                p = true;
                break;
            }
        }
        if (p != pressed) {
            pressed = p;
            invalidateSelf();
        }
        return true;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        shW = 0;   // 尺寸变了，着色器缓存作废
        super.onBoundsChange(b);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void draw(Canvas cv) {
        final Rect b = getBounds();
        final int w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;

        if (circle) {
            final float s = Math.min(w, h);
            box.set(b.left + (w - s) / 2f, b.top + (h - s) / 2f,
                    b.left + (w + s) / 2f, b.top + (h + s) / 2f);
        } else {
            box.set(b);
        }
        final float r = circle ? box.width() / 2f : Math.min(radiusPx, box.width() / 2f);

        if (w != shW || h != shH) {
            shW = w;
            shH = h;
            if (light) {
                // 浅色主题：一层极淡的灰雾 + 一道暗边（暗边也走径向，
                // 深色主题的"光从左上"在浅色下变成"影在左上"）
                shFog = new LinearGradient(0, box.top, 0, box.bottom,
                        0x0E000000, 0x06000000, Shader.TileMode.CLAMP);
                shEdge = new RadialGradient(box.left + box.width() * 0.26f,
                        box.top - box.height() * 0.18f, box.width() * 0.95f,
                        0x2E000000, 0x0C000000, Shader.TileMode.CLAMP);
            } else {
                // ① 身体：极淡的乳白雾，只够把底下的画面「化开」一点
                shFog = new LinearGradient(0, box.top, 0, box.bottom,
                        0x0AFFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP);
                // ② 唯一的一道边缘折射：左上 22% → 右下 8%。
                //    圆形没有长边，用径向衰减让亮线自然绕着左上走、
                //    到右下彻底消失 —— 一条线就能表达"光从上来"，不必再叠热点。
                shEdge = new RadialGradient(box.left + box.width() * 0.26f,
                        box.top - box.height() * 0.18f, box.width() * 0.95f,
                        0x38FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP);
            }
        }

        // ---- 主体：极淡的雾（被圆角裁掉）
        clipPath.reset();
        clipPath.addRoundRect(box, r, r, Path.Direction.CW);
        final int sc = cv.save();
        cv.clipPath(clipPath);
        pFill.setAlpha(alpha);
        pFill.setShader(shFog);
        cv.drawRect(box, pFill);
        pFill.setShader(null);
        cv.restoreToCount(sc);

        // ---- 唯一的一道边缘折射线
        final float in = Math.max(1f, den * 0.5f);
        inset.set(box.left + in, box.top + in, box.right - in, box.bottom - in);
        pEdge.setAlpha(alpha);
        pEdge.setStrokeWidth(Math.max(1f, den * 0.6f));
        pEdge.setShader(shEdge);
        cv.drawRoundRect(inset, Math.max(0f, r - in), Math.max(0f, r - in), pEdge);
        pEdge.setShader(null);

        // ---- 按下：整片玻璃吸一口光（不是变实色，是变亮）
        if (pressed) {
            pFill.setShader(null);
            pFill.setColor(light ? 0x12000000 : 0x18FFFFFF);
            cv.drawRoundRect(box, r, r, pFill);
        }
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }

    /**
     * 玻璃片的颜色来自边缘光学，不接受外部整体染色，
     * 否则会把通透的玻璃压成一块实色 —— 那正是要去掉的塑料感。
     */
    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        // no-op
    }

    @Override
    public void setAlpha(int a) {
        if (alpha != a) {
            alpha = a;
            invalidateSelf();
        }
    }
}
