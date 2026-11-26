package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 */
public class DanmakuView extends View {

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
        invalidate();
    }

    public void clear() {
        data.clear();
        active.clear();
        index = 0;
        posMs = -1;
        drawnPos = -1;
        invalidate();
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

    /** 由播放器每 ~80ms 调用一次 */
    public void setPosition(long ms) {
        if (ms < 0) return;
        if (drawnPos < 0) drawnPos = ms;
        if (posMs >= 0 && ms < posMs - 1500) {
            // 回退/拖动：清屏并重定位游标
            active.clear();
            index = 0;
            while (index < data.size() && data.get(index).time < ms) index++;
        }
        posMs = ms;
        spawnDue();
        invalidate();
    }

    void spawnDue() {
        if (index >= data.size()) return;
        long now = posMs;
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

        // 播放中才继续刷新；暂停时 pos 不变，不再重绘，省电
        if (now != drawnPos) {
            drawnPos = now;
            postInvalidateDelayed(33);
        }
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
