package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Random;

/**
 * 滑雪大冒险（简化版）：俯视雪坡，手指拖动控制小人，躲树躲石头、吃金币。
 * 越滑越快，撞一次就结束。
 */
public class GameSki extends GameView {

    static class Obj {
        float x, y;
        int type;     // 0 = 松树  1 = 石头  2 = 金币
        boolean done;
        Obj(float x, float y, int t) { this.x = x; this.y = y; this.type = t; }
    }

    final ArrayList<Obj> list = new ArrayList<>();
    final Random rnd = new Random();

    float px, targetX, py;
    float speed = 260f;
    float spawnAcc;
    float dist;
    boolean dragging;
    float snowT;
    float crashT;
    final float[] flakes = new float[60];

    public GameSki(Context c) {
        super(c);
        gameId = "ski";
        gameTitle = "滑雪大冒险";
    }

    @Override protected void reset() {
        px = W / 2f;
        targetX = px;
        py = H * 0.74f;
        speed = 260f;
        spawnAcc = 0;
        dist = 0;
        score = 0;
        dragging = false;
        snowT = 0;
        crashT = 0;
        list.clear();
        for (int i = 0; i < flakes.length; i += 2) {
            flakes[i] = rnd.nextFloat() * W;
            flakes[i + 1] = rnd.nextFloat() * H;
        }
    }

    @Override protected void step(int dt) {
        float s = dt / 1000f;
        speed = Math.min(760f, 260f + dist * 0.35f);
        dist += speed * s;
        score = (int) (dist / 10);
        snowT += s;

        if (crashT > 0) crashT -= dt;

        // 横向平滑跟随
        px += (targetX - px) * Math.min(1f, s * 12f);
        px = Math.max(dp(26), Math.min(W - dp(26), px));

        spawnAcc += speed * s;
        float gap = Math.max(dp(105), dp(150) - dist * 0.006f);
        while (spawnAcc > gap) {
            spawnAcc -= gap;
            spawn();
        }

        for (int i = list.size() - 1; i >= 0; i--) {
            Obj o = list.get(i);
            o.y += speed * s;
            if (o.y > H + dp(60)) { list.remove(i); continue; }

            float dx = o.x - px, dy = o.y - py;
            if (o.type == 2) {
                if (!o.done && dx * dx + dy * dy < dp(26) * dp(26)) {
                    o.done = true;
                    score += 10;
                    list.remove(i);
                }
            } else {
                float rr = o.type == 0 ? dp(17) : dp(14);
                if (dx * dx + dy * dy < (rr + dp(10)) * (rr + dp(10))) {
                    crashT = 260;
                    gameOver();
                    return;
                }
            }
        }
    }

    void spawn() {
        int type = rnd.nextInt(100) < 24 ? 2 : (rnd.nextInt(100) < 72 ? 0 : 1);
        float x = dp(30) + rnd.nextFloat() * (W - dp(60));
        // 金币偶尔排成一小串，吃起来更爽
        if (type == 2) {
            for (int i = 0; i < 3; i++) {
                list.add(new Obj(x, -dp(40) - i * dp(30), 2));
            }
            return;
        }
        // 同一横行别把路堵死
        for (Obj o : list) {
            if (Math.abs(o.y - (-dp(30))) < dp(40) && Math.abs(o.x - x) < dp(70)) {
                x = x + (x < W / 2f ? dp(80) : -dp(80));
                x = Math.max(dp(30), Math.min(W - dp(30), x));
            }
        }
        list.add(new Obj(x, -dp(40), type));
    }

    @Override protected void paint(Canvas c) {
        float ox = crashT > 0 ? (float) (Math.random() - 0.5) * dp(8) : 0;
        c.save();
        c.translate(ox, 0);

        // 雪坡
        pFill.setShader(new LinearGradient(0, 0, 0, H,
                0xFFE8F1F8, 0xFFC7DAEA, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, pFill);
        pFill.setShader(null);

        // 滑雪痕迹（两条随速度滚动的浅色斜线）
        pLine.setStrokeWidth(dp(14));
        pLine.setColor(0x33A8C4DC);
        float trail = (float) ((dist * 0.6) % dp(120));
        for (float y = -dp(120) + trail; y < H; y += dp(120)) {
            c.drawLine(px - dp(9), y, px - dp(9), y + dp(60), pLine);
            c.drawLine(px + dp(9), y, px + dp(9), y + dp(60), pLine);
        }

        // 飘雪
        pFill.setColor(0x66FFFFFF);
        for (int i = 0; i < flakes.length; i += 2) {
            flakes[i + 1] += (speed * 0.35f) * 0.016f;
            if (flakes[i + 1] > H) { flakes[i + 1] = -dp(10); flakes[i] = rnd.nextFloat() * W; }
            c.drawCircle(flakes[i], flakes[i + 1], dp(1.6f), pFill);
        }

        // 物体
        for (Obj o : list) {
            if (o.type == 2) {
                pFill.setColor(0x55FFC107);
                c.drawCircle(o.x, o.y, dp(13), pFill);
                pFill.setColor(0xFFFFC107);
                c.drawCircle(o.x, o.y, dp(8), pFill);
                pFill.setColor(0xFFFFF8E1);
                c.drawCircle(o.x - dp(2.5f), o.y - dp(2.5f), dp(2.8f), pFill);
            } else if (o.type == 0) {
                // 松树：影子 + 三层三角 + 树干
                pFill.setColor(0x22000000);
                c.drawOval(o.x - dp(14), o.y - dp(4), o.x + dp(16), o.y + dp(12), pFill);
                pFill.setColor(0xFF6D4C41);
                c.drawRect(o.x - dp(3), o.y - dp(6), o.x + dp(3), o.y + dp(6), pFill);
                int green = 0xFF2E7D32;
                for (int i = 0; i < 3; i++) {
                    float w = dp(17) - i * dp(4);
                    float top = o.y - dp(44) + i * dp(13);
                    Path p = new Path();
                    p.moveTo(o.x, top);
                    p.lineTo(o.x + w, top + dp(22));
                    p.lineTo(o.x - w, top + dp(22));
                    p.close();
                    pFill.setColor(i == 0 ? green : (i == 1 ? 0xFF388E3C : 0xFF43A047));
                    c.drawPath(p, pFill);
                }
            } else {
                pFill.setColor(0x22000000);
                c.drawOval(o.x - dp(12), o.y - dp(4), o.x + dp(14), o.y + dp(10), pFill);
                pFill.setColor(0xFF8D99A6);
                c.drawCircle(o.x, o.y, dp(12), pFill);
                pFill.setColor(0xFFB0BCC8);
                c.drawCircle(o.x - dp(3), o.y - dp(3), dp(6), pFill);
            }
        }

        // 小人
        float ph = crashT > 0 ? dp(20) : dp(40);
        pFill.setColor(0x33000000);
        c.drawOval(px - dp(16), py + dp(4), px + dp(16), py + dp(14), pFill);
        // 雪板
        box(c, px - dp(13), py - dp(2), px + dp(13), py + dp(5), 0xFFFB7299, dp(3));
        // 身体
        box(c, px - dp(9), py - ph, px + dp(9), py, 0xFF1E88E5, dp(7));
        // 头 + 帽子
        pFill.setColor(0xFFF5CBA7);
        c.drawCircle(px, py - ph - dp(2), dp(7.5f), pFill);
        pFill.setColor(0xFFE53935);
        tmp.set(px - dp(8.5f), py - ph - dp(12), px + dp(8.5f), py - ph - dp(3));
        c.drawRoundRect(tmp, dp(3), dp(3), pFill);
        // 雪杖
        pLine.setStrokeWidth(dp(2));
        pLine.setColor(0xFF546E7A);
        c.drawLine(px - dp(10), py - dp(4), px - dp(20), py + dp(10), pLine);
        c.drawLine(px + dp(10), py - dp(4), px + dp(20), py + dp(10), pLine);

        c.restore();

        label(c, "距离 " + (int) dist + " m", dp(58), dp(32), 12, 0xFF33414F, Paint.Align.LEFT);
        tip(c, "拖动手指左右闪避 · 吃金币");
    }

    @Override protected boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                targetX = e.getX();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) targetX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }
}
