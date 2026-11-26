package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量弹幕层：按播放进度滚动绘制，不依赖任何第三方库。
 * · 数据来自 B 站 XML 弹幕（d 标签的 p 属性）
 * · 按播放位置驱动，暂停即静止，拖动进度条会重置
 * · 支持滚动弹幕 + 顶部/底部固定弹幕，自动分轨避免重叠
 *
 * 【v4.39.0 帧率修复】
 * 旧版位置完全依赖播放器每 80ms 轮询一次 setPosition()，onDraw 又只在
 * posMs 变化时才延迟重绘 —— 于是弹幕每秒只走 ~12 帧，
 * 而且在两次轮询之间完全静止，肉眼可见「一顿一顿」。
 * 新版改为 Choreographer 逐帧（跟随屏幕刷新率，通常 60fps）平滑推进：
 *   · setPosition() 只作为「锚点采样」：记录媒体位置 ms 与采样时刻 wall。
 *   · 每个 vsync 帧用锚点 + 速度 × 已流逝时间外推出当前位置，帧间平滑。
 *   · 速度由连续两次采样估算（天然兼容倍速：2x 时采样差值是 1x 的两倍）；
 *     采样位置不再前进（暂停/缓冲）时速度归零，弹幕随之静止。
 *   · 外推最多 300ms，防止轮询异常中断时弹幕「飞出去」。
 * 所有公开方法签名保持不变，PlayActivity 侧零改动。
 */
public class DanmakuView extends View implements Choreographer.FrameCallback {

    static class Item {
        long time;      // 出现时间（毫秒）
        int mode;       // 1 滚动 4 底部 5 顶部
        int color;      // RGB
        String text;
    }

    static final Pattern P_D = Pattern.compile(
            "<d p=\"([^\"]+)\"[^>]*>(.*?)</d>", Pattern.DOTALL);
    long dur = 8000;                    // 滚动时长（随「速度」变化，100% = 8 秒）
    static final long FIXED_DUR = 4000; // 固定弹幕停留

    final ArrayList<Item> data = new ArrayList<>();
    final ArrayList<Active> active = new ArrayList<>();
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    int index = 0;
    long posMs = -1, drawnPos = -1;
    int lanes = 0;
    float laneH;
    float textPx;
    int lastHeight = -1;

    // 【v4.8】用户自定义样式：forceColor=0 表示沿用弹幕自带颜色（B站原生彩色弹幕）
    int forceColor = 0;
    int alpha = 235;

    // 【v4.10.6】可调项：滚动速度、铺满高度占比、同屏上限
    int speedPct = 100;
    int areaPct = 100;
    // 【v4.15.7 精简弹幕】同屏上限 40 → 25：宁可少而清爽，也不堆满屏幕
    int maxSame = 25;

    // ---- 【v4.39.0】平滑时钟状态 ----
    long anchorMs = -1;          // 最近一次采样的媒体位置
    long anchorWallNs = 0;       // 采样发生的 wall 时刻（nanoTime）
    float playSpeed = 1f;        // 由相邻采样估算的播放速度（1x≈1.0，2x≈2.0，暂停→0）
    Choreographer choreo;
    boolean looping = false;
    static final long MAX_EXTRAP_NS = 300L * 1000L * 1000L;   // 最多外推 300ms

    /** 【v4.8】弹幕样式：颜色(0=跟随原色) / 透明度百分比 / 字号百分比 */
    public void setStyle(int color, int alphaPct, int sizePct) {
        this.forceColor = color & 0xFFFFFF;
        this.alpha = Math.max(10, Math.min(100, alphaPct)) * 255 / 100;

        float den = getResources().getDisplayMetrics().density;
        float np = 15 * den * (Math.max(60, Math.min(180, sizePct)) / 100f);
        if (Math.abs(np - textPx) > 0.5f) {
            textPx = np;
            laneH = textPx * 1.5f;
            fill.setTextSize(textPx);
            stroke.setTextSize(textPx);
            stroke.setStrokeWidth(Math.max(2f, 2.4f * den * (sizePct / 100f)));
            lastHeight = -1;   // 字号变了 → 轨道数必须重算
            active.clear();    // 已排布的旧轨道作废，避免重叠
        }
        invalidate();
    }

    /**
     * 【v4.10.6】弹幕调节：速度 / 显示区域 / 同屏密度。
     * · speedPct 50~200：越大跑得越快（时长 = 8000 × 100 / speedPct）
     * · areaPct  40~100：弹幕只铺画面顶部这块比例，其余留给画面
     * · density  10~80：同屏最多几条，超出的直接丢弃，宁可少不打架
     */
    public void setTuning(int speedPct, int areaPct, int density) {
        this.speedPct = Math.max(50, Math.min(200, speedPct));
        this.dur = (long) (8000L * 100f / this.speedPct);
        this.maxSame = Math.max(10, Math.min(80, density));
        int a = Math.max(40, Math.min(100, areaPct));
        if (a != this.areaPct) {
            this.areaPct = a;
            laneH = textPx * 1.5f;   // 之前可能被压缩过，先复原
            lastHeight = -1;         // 区域变了 → 轨道数重算
            active.clear();          // 已经在飞的旧弹幕作废，避免越界
        }
        invalidate();
    }


    static class Active {
        Item item;
        long start;
        int lane;
        float tw;
        boolean fixed;
    }

    public DanmakuView(Context c) {
        super(c);
        float den = c.getResources().getDisplayMetrics().density;
        textPx = 15 * den;
        laneH = textPx * 1.5f;

        fill.setTextSize(textPx);
        fill.setColor(Color.WHITE);
        stroke.setTextSize(textPx);
        stroke.setColor(Color.argb(190, 0, 0, 0));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, 2.4f * den));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // 【v4.15.7 帧率修复】不再强制软件渲染。软件层会让每帧文字全部走 CPU
        // 合成（高分屏上必然掉帧）；drawText 在硬件加速下完全支持且快数倍。
        // setLayerType(LAYER_TYPE_SOFTWARE) 已删除。
    }

    /** 解析 XML；异常文本直接忽略 */
    public static ArrayList<Item> parse(String xml) {
        ArrayList<Item> out = new ArrayList<>();
        if (xml == null) return out;
        try {
            Matcher m = P_D.matcher(xml);
            while (m.find()) {
                String[] p = m.group(1).split(",");
                if (p.length < 4) continue;
                Item it = new Item();
                it.time = (long) (Double.parseDouble(p[0]) * 1000);
                it.mode = (int) Double.parseDouble(p[1]);
                it.color = (int) Double.parseDouble(p[3]);
                it.text = unescape(m.group(2)).trim();
                if (it.text.isEmpty() || it.text.length() > 80) continue;
                if (it.time < 0) continue;
                out.add(it);
                if (out.size() >= 8000) break;
            }
        } catch (Throwable ignore) { }
        try {
            Collections.sort(out, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return a.time < b.time ? -1 : (a.time > b.time ? 1 : 0);
                }
            });
        } catch (Throwable ignore) { }
        return out;
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    public void setData(ArrayList<Item> list) {
        data.clear();
        if (list != null) data.addAll(list);
        active.clear();
        index = 0;
        posMs = -1;
        drawnPos = -1;
        resetClock();
        invalidate();
    }

    public void clear() {
        data.clear();
        active.clear();
        index = 0;
        posMs = -1;
        drawnPos = -1;
        resetClock();
        invalidate();
    }

    /** 重置平滑时钟（换视频 / 拖动后调用） */
    void resetClock() {
        anchorMs = -1;
        anchorWallNs = 0;
        playSpeed = 1f;
    }

    public int size() { return data.size(); }

    /** 发送成功后立即在本地显示（不等服务器下发） */
    public void addLocal(String text) {
        if (text == null || text.trim().isEmpty()) return;
        Item it = new Item();
        it.time = Math.max(0, posMs);
        it.mode = 1;
        it.color = 0xFFFFFF;
        it.text = text.trim();
        spawn(it, it.time);
        invalidate();
    }

    /**
     * 播放器每 ~80ms 调用一次：这里只做「锚点采样」，不再直接驱动绘制。
     * 真正的平滑位置由 Choreographer 每帧用锚点外推（见 doFrame）。
     */
    public void setPosition(long ms) {
        if (ms < 0) return;
        long wall = System.nanoTime();

        if (DanmakuClock.isSeekBack(ms, anchorMs)) {
            // 回退/拖动：清屏并重定位游标
            active.clear();
            index = 0;
            while (index < data.size() && data.get(index).time < ms) index++;
            playSpeed = 1f;
        } else if (DanmakuClock.isFrozen(ms, anchorMs)) {
            // 采样位置没动 → 暂停/缓冲，速度归零，弹幕静止
            playSpeed = 0f;
        } else if (anchorMs >= 0 && ms > anchorMs) {
            // 由相邻采样估算播放速度（纯逻辑，可单测）；天然兼容倍速播放
            playSpeed = DanmakuClock.estimateSpeed(playSpeed, ms - anchorMs, wall - anchorWallNs);
        }
        anchorMs = ms;
        anchorWallNs = wall;
        if (drawnPos < 0) drawnPos = ms;
        startLoop();
    }

    /** 当前平滑播放位置（每帧外推，帧间不再静止） */
    long smoothNowNs() {
        long elapsedNs = System.nanoTime() - anchorWallNs;
        return DanmakuClock.extrapolate(anchorMs, playSpeed,
                elapsedNs / 1_000_000L, MAX_EXTRAP_NS / 1_000_000L);
    }

    void spawnDue(long now) {
        if (index >= data.size()) return;
        while (index < data.size() && data.get(index).time <= now) {
            Item it = data.get(index++);
            if (now - it.time > 900) continue;  // 刚 seek 过来，旧弹幕不再补
            spawn(it, now);
        }
    }

    void spawn(Item it, long now) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (lanes <= 0) return;
        if (active.size() >= maxSame) return;   // 密度上限

        float tw = fill.measureText(it.text);
        boolean fixed = (it.mode == 4 || it.mode == 5);
        int lane = fixed ? fixedLane(it.mode == 4) : freeLane(tw, now);
        if (lane < 0) return;

        Active a = new Active();
        a.item = it;
        a.start = now;
        a.lane = lane;
        a.tw = tw;
        a.fixed = fixed;
        active.add(a);
    }

    /** 找一条空闲轨道；找不到则丢弃这条弹幕（宁可少，不重叠） */
    int freeLane(float tw, long now) {
        for (int i = 0; i < lanes; i++) {
            Active last = null;
            for (int j = active.size() - 1; j >= 0; j--) {
                Active a = active.get(j);
                if (!a.fixed && a.lane == i) { last = a; break; }
            }
            if (last == null) return i;
            long el = now - last.start;
            float x = getWidth() - el * 1f / dur * (getWidth() + last.tw);
            if (x + last.tw < getWidth() - dp(12)) return i;
        }
        return -1;
    }

    int fixedLane(boolean bottom) {
        for (int i = 0; i < lanes; i++) {
            int lane = bottom ? lanes - 1 - i : i;
            boolean busy = false;
            for (Active a : active) {
                if (a.fixed && a.lane == lane && posMs - a.start < FIXED_DUR) {
                    busy = true;
                    break;
                }
            }
            if (!busy) return lane;
        }
        return -1;
    }

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---- 【v4.39.0】逐帧驱动：跟随屏幕刷新率（通常 60fps），替代 80ms 步进 ----

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) startLoop(); else stopLoop();
    }

    void startLoop() {
        if (looping) return;
        if (getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        looping = true;
        if (choreo == null) choreo = Choreographer.getInstance();
        choreo.postFrameCallback(this);
    }

    void stopLoop() {
        looping = false;
        if (choreo != null) choreo.removeFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!looping) return;
        if (anchorMs >= 0) {
            long next = smoothNowNs();
            // 暂停/缓冲时位置不变且无新弹幕要入场：跳过重绘，省电
            boolean moved = next != posMs;
            posMs = next;
            int before = index;
            spawnDue(posMs);
            if (moved || index != before || active.isEmpty()) invalidate();
        }
        choreo.postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 弹幕只占用顶部 areaPct% 的高度，剩下留给画面
        int usable = (int) (h * areaPct / 100f);
        if (h != lastHeight) {
            lastHeight = h;
            // 保证最底一行的文字底边不超出可见区域；不再压缩行高（压缩会让字叠在一起）
            int fit = (int) ((usable - dp(8) - textPx) / laneH) + 1;
            lanes = Math.max(1, Math.min(10, fit));
        }
        if (posMs < 0) return;

        long now = posMs;
        for (int i = active.size() - 1; i >= 0; i--) {
            Active a = active.get(i);
            long el = now - a.start;
            if (a.fixed) {
                if (el > FIXED_DUR) { active.remove(i); continue; }
                float x = (w - a.tw) / 2f;
                float y = dp(8) + a.lane * laneH + textPx;
                drawOne(cv, a, x, y);
            } else {
                float x = w - el * 1f / dur * (w + a.tw);
                if (x + a.tw < -dp(6)) { active.remove(i); continue; }
                float y = dp(8) + a.lane * laneH + textPx;
                drawOne(cv, a, x, y);
            }
        }
        drawnPos = now;
        // 【v4.39.0】不再自行延迟重绘：逐帧由 doFrame 统一驱动。
    }

    void drawOne(Canvas cv, Active a, float x, float y) {
        // 【v4.8】用户选了强制颜色就统一用，否则用弹幕自带的 RGB（B站彩色弹幕）
        int c = forceColor != 0 ? forceColor : (a.item.color & 0xFFFFFF);
        if (c == 0) c = 0xFFFFFF;
        fill.setColor(Color.argb(alpha, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
        cv.drawText(a.item.text, x, y, stroke);
        cv.drawText(a.item.text, x, y, fill);
    }
}
