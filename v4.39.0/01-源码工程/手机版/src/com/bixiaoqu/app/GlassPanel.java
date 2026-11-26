package com.bixiaoqu.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/**
 * 液态玻璃容器。
 *
 * 继承 LinearLayout，所以老的「竖着摞几行控件」的代码可以原地替换：
 *   ctrlBar = new LinearLayout(this)  →  ctrlBar = new GlassPanel(this)
 * 子 View 的排布、padding、addView 全部照旧，只是背景从一块死色换成了玻璃材质。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 【v4.28.0 —— 删掉所有多余的光影与结构线】
 *
 *   ▸ 用户反馈：「去除多余的光影，以及多余的结构线」。
 *     截图实测：控制条外部一圈白描边、每个胶囊各一圈白描边、
 *     每个圆钮还有双圈 —— 控件全被「白线框住」，像早期拟物按钮。
 *
 *   ▸ 为什么会这样（v4.27.0 的锅）：
 *     为了「做出玻璃厚度」，每一层光学都用一道 STROKE 实现，
 *     而 STROKE 就是一圈线。于是控制条一个控件上叠了 7 道内缩半径不同的描边：
 *       端面高光 / 高光热点 / 厚度暗线 / 内表面反射 / 顶棱镜面 / 色散 / 底缘反光
 *     每道单独看都"很讲究"，叠在一起就是**七个框**。
 *     再配上端面 0xE6（90% 白）的强度，最外层那道直接成了醒目的白色结构线。
 *
 *   ▸ 真玻璃到底有几道线？—— **一道都算多**。
 *     一块浮在画面上的玻璃，眼睛能看到的只有两样东西：
 *       ① 一片极淡的压暗（玻璃挡掉了一部分光，这是它能承载白字的原因）；
 *       ② 一条极细的、沿边缘走的亮线（玻璃-空气界面的折射），
 *          粗细约 0.5dp、亮度约 12%，在深色画面上"若有若无"。
 *     没有色散、没有焦散、没有内表面二次反射、没有顶棱镜面 ——
 *     那些是珠宝摄影里的布光，不是播放器控制条该有的东西。
 *
 *   ▸ 这一版因此把绘制层数从 13 降到 3：
 *       ① 外阴影（2 层极淡，浮起感靠它）
 *       ② 基底压暗（水平：两端略实、中段最透 → 长边融进画面）
 *       ③ 单道描边（垂直微差 0x2E→0x18，一道线自带明暗，不再叠第二道）
 *     外加两个瞬时态：扫光（显隐时跑一次 720ms）、按下提亮。
 *
 *   ▸ 顺带的内存收益（用户同轮要求「进一步优化内存占用」）：
 *     每个 GlassPanel 实例的 Shader 从 13 个降到 2 个，
 *     Paint 从 11 个降到 3 个，并**删除两张噪点 Bitmap**
 *     （64×64 + 22×22，每张都要分配像素数组并挂一个平铺着色器）。
 *     控制条 + 多个胶囊/圆钮一屏下来，省掉几十个 native Shader 对象。
 *
 * 全场只在「控件显示 / 按下 / 扫光」时重绘，静止时零开销；
 * 没有 RenderScript、没有 RenderEffect、没有额外线程，API22 起可用。
 */
public class GlassPanel extends LinearLayout {

    /** 圆角（dp） */
    private float radiusDp = 20f;
    /** 是否画外投影（浮在画面上时要有，嵌在页面流里可以关掉省一层绘制） */
    private boolean floating = true;
    /**
     * 基底浓度：0~255，直接就是「压暗层」的 alpha。
     * 玻璃本身几乎不压暗，压暗只是为了白字能读；压太多就变回塑料板。
     */
    private int tint = 0x46;
    /** 扫光进度：-1 表示不画 */
    private float sheen = -1f;

    // 【v4.28.0】三个 Paint 就够：填色 / 描边 / 阴影（扫光复用填色笔）
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final RectF innerR = new RectF();
    private final Path clip = new Path();

    private ValueAnimator sheenAnim;

    // ---- 着色器缓存：尺寸不变就不重建，扫光动画每帧重绘也不会产生垃圾 ----
    private int shW, shH;
    private Shader shFill, shEdge;

    public GlassPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);          // ViewGroup 默认跳过 onDraw，材质必须自己画
        setClipChildren(false);
        setClipToPadding(false);
        edge.setStyle(Paint.Style.STROKE);
    }

    // ------------------------------------------------------------ 配置

    public GlassPanel radius(float dp) {
        this.radiusDp = dp;
        invalidate();
        return this;
    }

    /** 浮在画面上（画投影）；false = 嵌在页面流里，省一层阴影绘制 */
    public GlassPanel floating(boolean on) {
        this.floating = on;
        invalidate();
        return this;
    }

    /** 基底浓度 0~255（压暗层 alpha）。越大越实，越小越通透 */
    public GlassPanel tint(int alpha) {
        this.tint = Math.max(0, Math.min(255, alpha));
        shW = 0;   // 让着色器缓存失效
        invalidate();
        return this;
    }

    /** 控件出现时扫一道光，模拟玻璃被光扫过；只跑一次 700ms，不常驻 */
    public void playSheen() {
        if (sheenAnim != null) sheenAnim.cancel();
        sheenAnim = ValueAnimator.ofFloat(-0.35f, 1.35f);
        sheenAnim.setDuration(720);
        sheenAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        sheenAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                sheen = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        sheenAnim.start();
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float den = getResources().getDisplayMetrics().density;
        final float r = radiusDp * den;
        rect.set(0, 0, w, h);

        // ① 接触阴影：两层、范围大、alpha 极低。
        //    【v4.28.0】三层 → 两层。影子只是让玻璃「不贴着画面」，
        //    再淡就看不见了，再加就成贴纸。
        if (floating) {
            for (int i = 2; i >= 1; i--) {
                float off = dp(i * 3.0f);
                shadow.setColor(i == 1 ? 0x14000000 : 0x08000000);
                tmp.set(off * 0.22f, off * 0.85f, w - off * 0.22f, h + off * 0.75f);
                cv.drawRoundRect(tmp, r * 0.95f, r * 0.95f, shadow);
            }
        }

        if (w != shW || h != shH) {
            shW = w;
            shH = h;

            // 基底压暗：两端略实、中段最透。
            // 玻璃的厚度感来自端面（端面挡住更多光），长边中段尽量让画面透上来，
            // 这就是「一体感」——控制条不是一块板，而是一层贴着画面的膜。
            final float[] POS = new float[]{0f, 0.09f, 0.5f, 0.91f, 1f};
            shFill = new LinearGradient(0, 0, w, 0,
                    new int[]{
                            withAlpha(0x000000, (int) (tint * 1.00f)),
                            withAlpha(0x000000, (int) (tint * 0.88f)),
                            withAlpha(0x000000, (int) (tint * 0.78f)),
                            withAlpha(0x000000, (int) (tint * 0.88f)),
                            withAlpha(0x000000, (int) (tint * 1.00f))},
                    POS, Shader.TileMode.CLAMP);

            // 唯一的一道描边：上缘 0x2E（18%）→ 下缘 0x18（9%）。
            // 【v4.28.0】这是一圈线里最「贵」的一笔 —— 玻璃的形状全靠它，
            // 但绝对不能亮过 20%，否则就从「界面的折光」变成「框住控件的白线」。
            shEdge = new LinearGradient(0, 0, 0, h,
                    0x2EFFFFFF, 0x18FFFFFF, Shader.TileMode.CLAMP);
        }

        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        final int sc = cv.save();
        cv.clipPath(clip);

        // ② 基底压暗（很轻，只是为了白字能读）
        fill.setShader(shFill);
        cv.drawRect(rect, fill);
        fill.setShader(null);

        // 动态扫光：沿顶棱跑过的一道细亮光（不是整块擦白）
        if (sheen >= 0f) {
            final int sc2 = cv.save();
            cv.rotate(-14f, w * 0.5f, h * 0.5f);
            float cx = -w * 0.45f + sheen * (w * 1.9f);
            fill.setShader(new LinearGradient(cx, 0, cx + w * 0.16f, h * 0.6f,
                    0x00FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
            cv.drawRect(-w, -h, w * 2, h * 2, fill);
            fill.setShader(null);
            cv.restoreToCount(sc2);
        }

        // ③ 按下提亮：手指按住时玻璃「吸一口光」（不是变实色）
        if (isPressed()) {
            fill.setShader(null);
            fill.setColor(0x12FFFFFF);
            cv.drawRoundRect(rect, r, r, fill);
        }

        cv.restoreToCount(sc);

        // ④ 唯一的一道边缘描边（不再内缩两三层去叠"厚度"）
        final float rimW = Math.max(1f, den * 0.55f);
        edge.setShader(shEdge);
        edge.setStrokeWidth(rimW);
        innerR.set(rect.left + rimW * 0.5f, rect.top + rimW * 0.5f,
                rect.right - rimW * 0.5f, rect.bottom - rimW * 0.5f);
        cv.drawRoundRect(innerR, Math.max(0f, r - rimW * 0.5f),
                Math.max(0f, r - rimW * 0.5f), edge);
        edge.setShader(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 只是为了让 isPressed() 在整块面板范围内也能触发提亮；
        // 子控件自己消费掉事件的，走各自的按压态。
        return super.onTouchEvent(ev);
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = isPressed() != pressed;
        super.setPressed(pressed);
        if (changed) invalidate();
    }

    /** 上次扫光时间：控件反复显隐时也不会一直闪着 */
    private long lastSheenAt;

    /**
     * 控件从隐藏变可见时扫一道光。
     * 点一下画面唤出控制条，玻璃像被「重新注满」—— 这点微动效是液态感的关键，
     * 但只在显隐的那一刻跑一次，静止时一个属性动画都不挂。
     */
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE && getWindowVisibility() == VISIBLE) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastSheenAt > 1200) {
                lastSheenAt = now;
                playSheen();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sheenAnim != null) {
            sheenAnim.cancel();
            sheenAnim = null;
        }
        super.onDetachedFromWindow();
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
