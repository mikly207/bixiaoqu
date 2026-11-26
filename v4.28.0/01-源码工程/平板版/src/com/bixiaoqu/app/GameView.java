package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 【v4.9.7】游戏厅所有小游戏的公共底座。
 *
 * 它负责四件与具体玩法无关的事，让每个游戏类只关心「规则 + 画面」：
 *   1. 帧循环（16ms 一跳，掉帧时把 dt 夹到 60ms，避免穿墙）；
 *   2. 分数 HUD 与最高分持久化；
 *   3. 结束遮罩：点屏幕任意处重开；
 *   4. 尺寸变化时只初始化一次（reset 需要 W/H）。
 *
 * 子类实现 reset / step / paint（必要的话再实现 touch）。
 * 所有绘制都用 Canvas 现画，不引入任何图片资源。
 */
public abstract class GameView extends View {

    /** 用于最高分存储的键，也用于大厅里的统计 */
    public String gameId = "";
    public String gameTitle = "";

    protected int W, H;
    protected float dens;
    protected int score;
    protected int best;
    protected boolean over;
    protected boolean paused;
    protected boolean inited;
    /** 底部引导条的剩余显示时间（开局几秒后自动消失，免得挡住按钮） */
    protected float tipT;

    protected final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF tmp = new RectF();

    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastT;
    private boolean looping;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!looping) return;
            long now = SystemClock.uptimeMillis();
            int dt = (int) (now - lastT);
            lastT = now;
            if (dt > 60) dt = 60;      // 掉帧保护：一下跳太久会穿模
            if (dt < 0) dt = 0;
            if (tipT > 0) tipT -= dt;
            if (!paused && !over) {
                try { step(dt); } catch (Throwable ignore) { }
            }
            invalidate();
            h.postDelayed(this, 16);
        }
    };

    public GameView(Context c) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setKeepScreenOn(true);
        pTxt.setTypeface(Typeface.DEFAULT_BOLD);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        looping = true;
        lastT = SystemClock.uptimeMillis();
        h.postDelayed(tick, 16);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        looping = false;
        h.removeCallbacksAndMessages(null);
    }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        W = w; H = hh;
        if (W > 0 && H > 0) {
            if (!inited) {
                inited = true;
                best = loadBest();
                tipT = 4500;
                try { reset(); } catch (Throwable ignore) { }
            } else if (ow > 0 && oh > 0 && (ow != w || oh != hh)) {
                // 尺寸变了（旋转/分屏）：重新摆一遍，否则牌桌会跑到屏幕外
                tipT = 4500;
                try { reset(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 宿主 Activity onPause 时调用：停逻辑但保留画面 */
    public void onHostPause() { paused = true; }

    /** 宿主 Activity onResume 时调用 */
    public void onHostResume() {
        paused = false;
        lastT = SystemClock.uptimeMillis();
    }

    // ---------------------------------------------------------------- 最高分

    protected int loadBest() {
        try {
            SharedPreferences sp = getContext().getSharedPreferences("bxq_game", 0);
            return sp.getInt("best_" + gameId, 0);
        } catch (Throwable e) { return 0; }
    }

    /** 结算：破纪录才写盘（每帧调用也不怕） */
    protected void commitScore() {
        if (score <= best) return;
        best = score;
        try {
            SharedPreferences sp = getContext().getSharedPreferences("bxq_game", 0);
            sp.edit().putInt("best_" + gameId, best).apply();
        } catch (Throwable ignore) { }
    }

    // ---------------------------------------------------------------- 子类接口

    /** 开局/重开：清空所有状态。此时 W/H 已就绪。 */
    protected abstract void reset();

    /** 推进一帧，dt 为毫秒（0~60） */
    protected abstract void step(int dt);

    /** 画游戏本体（HUD 与结束层由基类补上） */
    protected abstract void paint(Canvas c);

    /** 未结束时的手势；返回 true 表示已消费 */
    protected boolean touch(MotionEvent e) { return true; }

    /** 是否显示右上角的分数 HUD（棋牌类不需要） */
    protected boolean showHud() { return true; }

    /** 结束时的标题，默认「游戏结束」 */
    protected String overTitle() { return "游戏结束"; }

    // ---------------------------------------------------------------- 绘制流程

    @Override protected final void onDraw(Canvas c) {
        if (W <= 0 || H <= 0) return;
        try { paint(c); } catch (Throwable ignore) { }
        if (showHud()) drawHud(c);
        if (over) drawOver(c);
    }

    private void drawHud(Canvas c) {
        String s = "分数 " + score;
        pTxt.setTextSize(12 * dens);
        pTxt.setTextAlign(Paint.Align.LEFT);
        float tw = pTxt.measureText(s);
        float w = tw + dp(22), hh = dp(26);
        float right = W - dp(10), top = dp(10);
        box(c, right - w, top, right, top + hh, 0x77000000, dp(13));
        label(c, s, right - dp(11), top + dp(18), 12, 0xFFFFFFFF, Paint.Align.RIGHT);

        if (best > 0) {
            String b = "最高 " + best;
            label(c, b, right - dp(11), top + hh + dp(14), 10, 0x889AA0AB, Paint.Align.RIGHT);
        }
    }

    private void drawOver(Canvas c) {
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(0xD9000000);
        c.drawRect(0, 0, W, H, pFill);

        float cy = H * 0.42f;
        label(c, overTitle(), W / 2f, cy, 24, 0xFFFFFFFF, Paint.Align.CENTER);
        label(c, "本局 " + score + " 分", W / 2f, cy + dp(34), 15, 0xFFFB7299, Paint.Align.CENTER);
        label(c, "历史最高 " + Math.max(best, score), W / 2f, cy + dp(58), 12, 0xFF9AA0AB,
                Paint.Align.CENTER);

        float bw = dp(150), bh = dp(42);
        float bl = W / 2f - bw / 2f, bt = cy + dp(84);
        box(c, bl, bt, bl + bw, bt + bh, 0xFFFB7299, dp(21));
        label(c, "再来一局", W / 2f, bt + bh / 2f + dp(5), 14, 0xFF16181D, Paint.Align.CENTER);
        label(c, "点屏幕任意处重开", W / 2f, bt + bh + dp(24), 11, 0x889AA0AB,
                Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------- 绘制工具

    protected int dp(float v) { return (int) (v * dens + 0.5f); }

    protected void box(Canvas c, float l, float t, float r, float b, int color, float rad) {
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(color);
        tmp.set(l, t, r, b);
        c.drawRoundRect(tmp, rad, rad, pFill);
    }

    protected void frame(Canvas c, float l, float t, float r, float b, int color, float rad,
                         float stroke) {
        pLine.setColor(color);
        pLine.setStrokeWidth(stroke);
        tmp.set(l, t, r, b);
        c.drawRoundRect(tmp, rad, rad, pLine);
    }

    protected void label(Canvas c, String s, float x, float y, float sizeSp, int color) {
        label(c, s, x, y, sizeSp, color, Paint.Align.LEFT);
    }

    protected void label(Canvas c, String s, float x, float y, float sizeSp, int color,
                         Paint.Align al) {
        pTxt.setColor(color);
        pTxt.setTextSize(sizeSp * dens);
        pTxt.setTextAlign(al);
        c.drawText(s, x, y, pTxt);
    }

    /** 居中的半透明提示条，用于「滑动转向」「点击落子」这类引导（4.5 秒后自动消失） */
    protected void tip(Canvas c, String s) {
        if (tipT <= 0) return;
        pTxt.setTextSize(11 * dens);
        pTxt.setTextAlign(Paint.Align.LEFT);
        float tw = pTxt.measureText(s);
        float w = tw + dp(24), hh = dp(26);
        float l = (W - w) / 2f, t = H - dp(46);
        box(c, l, t, l + w, t + hh, 0x66000000, dp(13));
        label(c, s, W / 2f, t + dp(17.5f), 11, 0xCCFFFFFF, Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------- 触摸

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!inited) return true;
        if (over) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                performClick();
                over = false;
                score = 0;
                tipT = 3500;
                try { reset(); } catch (Throwable ignore) { }
            }
            return true;
        }
        if (paused) return true;
        try { return touch(e); } catch (Throwable t) { return true; }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 结束本局（子类在自己的死亡条件里调用） */
    protected void gameOver() {
        if (over) return;
        over = true;
        commitScore();
    }

    /** 通用：画一个圆形虚拟按键，返回是否被这一下点中 */
    protected boolean hitCircle(MotionEvent e, float cx, float cy, float r) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        float dx = e.getX() - cx, dy = e.getY() - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** 通用：画底部圆形按键 */
    protected void pad(Canvas c, float cx, float cy, float r, String symbol, boolean on) {
        box(c, cx - r, cy - r, cx + r, cy + r, on ? 0x55FB7299 : 0x33FFFFFF, r);
        frame(c, cx - r, cy - r, cx + r, cy + r, 0x55FFFFFF, r, dp(1));
        label(c, symbol, cx, cy + dp(6), 16, on ? 0xFFFB7299 : 0xE6FFFFFF, Paint.Align.CENTER);
    }

    protected static int lerpColor(int a, int b, float t) {
        int aa = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        int rr = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int gg = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bb = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(aa, rr, gg, bb);
    }
}
