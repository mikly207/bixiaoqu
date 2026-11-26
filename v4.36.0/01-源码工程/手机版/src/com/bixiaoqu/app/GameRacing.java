package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Random;

/**
 * 极速赛车（简化版）：四车道纵向追逐，手指拖动变道，躲开前车。
 * 速度随距离提升，撞车即结束。
 */
public class GameRacing extends GameView {

    static class Car {
        float x, y;
        float sp;      // 相对速度 px/s
        int color;
        Car(float x, float y, float sp, int color) {
            this.x = x; this.y = y; this.sp = sp; this.color = color;
        }
    }

    static final int[] CAR_COLORS = {
            0xFF42A5F5, 0xFFEF5350, 0xFF66BB6A, 0xFFFFCA28, 0xFFAB47BC, 0xFF26C6DA
    };

    final ArrayList<Car> cars = new ArrayList<>();
    final Random rnd = new Random();

    float roadL, roadR, laneW, carW, carH;
    float px, targetX, py;
    float speed = 520f;
    float dist;
    float spawnAcc;
    boolean dragging;
    float crashT;
    float dashPhase;

    public GameRacing(Context c) {
        super(c);
        gameId = "racing";
        gameTitle = "极速赛车";
    }

    @Override protected void reset() {
        roadL = W * 0.08f;
        roadR = W * 0.92f;
        laneW = (roadR - roadL) / 4f;
        carW = Math.min(dp(48), laneW * 0.66f);
        carH = carW * 1.6f;
        px = (roadL + roadR) / 2f;
        targetX = px;
        py = H * 0.78f;
        speed = 520f;
        dist = 0;
        score = 0;
        spawnAcc = 0;
        dragging = false;
        crashT = 0;
        dashPhase = 0;
        cars.clear();
    }

    @Override protected void step(int dt) {
        float s = dt / 1000f;
        speed = Math.min(1150f, 520f + dist * 0.22f);
        dist += speed * s;
        score = (int) (dist / 8);
        dashPhase = (dashPhase + speed * s) % dp(90);
        if (crashT > 0) crashT -= dt;

        px += (targetX - px) * Math.min(1f, s * 14f);
        px = Math.max(roadL + carW / 2f, Math.min(roadR - carW / 2f, px));

        spawnAcc += speed * s;
        float gap = Math.max(dp(240), dp(420) - dist * 0.02f);
        while (spawnAcc > gap) {
            spawnAcc -= gap;
            spawnCar();
        }

        for (int i = cars.size() - 1; i >= 0; i--) {
            Car c = cars.get(i);
            c.y += (c.sp) * s;
            if (c.y > H + dp(80)) { cars.remove(i); continue; }

            if (Math.abs(c.x - px) < (carW * 0.82f) && Math.abs(c.y - py) < carH * 0.82f) {
                crashT = 300;
                gameOver();
                return;
            }
        }
    }

    void spawnCar() {
        int lane = rnd.nextInt(4);
        float x = roadL + laneW * (lane + 0.5f);
        // 别和玩家正上方的新车重叠太近
        for (Car c : cars) {
            if (Math.abs(c.y - (-dp(80))) < dp(140) && Math.abs(c.x - x) < carW * 0.6f) return;
        }
        // 相对速度：比自己慢很多 → 会从后方追上来（这里表现为快速下移）
        float sp = speed * (0.35f + rnd.nextFloat() * 0.45f);
        int col = CAR_COLORS[rnd.nextInt(CAR_COLORS.length)];
        cars.add(new Car(x, -dp(80), sp, col));
    }

    @Override protected void paint(Canvas c) {
        float ox = crashT > 0 ? (float) (Math.random() - 0.5) * dp(9) : 0;
        c.save();
        c.translate(ox, 0);

        // 草地
        pFill.setShader(new LinearGradient(0, 0, 0, H,
                0xFF20301F, 0xFF2C4229, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, pFill);
        pFill.setShader(null);

        // 路面
        pFill.setColor(0xFF2B2E33);
        c.drawRect(roadL, 0, roadR, H, pFill);
        // 路面渐变（远处更暗）
        pFill.setShader(new LinearGradient(0, 0, 0, H,
                0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(roadL, 0, roadR, H, pFill);
        pFill.setShader(null);

        // 边线
        pFill.setColor(0xFFE8EAF0);
        c.drawRect(roadL, 0, roadL + dp(4), H, pFill);
        c.drawRect(roadR - dp(4), 0, roadR, H, pFill);

        // 车道虚线
        pFill.setColor(0x88FFFFFF);
        for (int i = 1; i < 4; i++) {
            float x = roadL + laneW * i;
            for (float y = -dp(90) + dashPhase; y < H; y += dp(90)) {
                c.drawRect(x - dp(2), y, x + dp(2), y + dp(46), pFill);
            }
        }

        // 路边护栏
        pFill.setColor(0xFF546E7A);
        for (float y = -dp(60) + (dashPhase * 1.4f) % dp(60); y < H; y += dp(60)) {
            c.drawRect(roadL - dp(10), y, roadL - dp(4), y + dp(26), pFill);
            c.drawRect(roadR + dp(4), y, roadR + dp(10), y + dp(26), pFill);
        }

        // 敌车
        for (Car cc : cars) drawCar(c, cc.x, cc.y, cc.color, false);

        // 玩家车
        drawCar(c, px, py, 0xFFFB7299, true);

        c.restore();

        label(c, "时速 " + (int) (speed / 10) + " km/h", dp(58), dp(32), 12, 0xE6FFFFFF,
                Paint.Align.LEFT);
        tidySpeedBar(c);
        tip(c, "拖动手指变道 · 躲开前车");
    }

    void tidySpeedBar(Canvas c) {
        float x0 = dp(58), w = W - dp(70), h = dp(4), y = dp(42);
        box(c, x0, y, x0 + w, y + h, 0x33FFFFFF, h / 2);
        float t = (speed - 520f) / (1150f - 520f);
        t = Math.max(0, Math.min(1, t));
        box(c, x0, y, x0 + w * t, y + h, 0xFFFB7299, h / 2);
    }

    void drawCar(Canvas c, float x, float y, int color, boolean hero) {
        float l = x - carW / 2f, r = x + carW / 2f;
        float t = y - carH / 2f, b = y + carH / 2f;
        // 影子
        pFill.setColor(0x44000000);
        tmp.set(l + dp(3), t + dp(6), r + dp(3), b + dp(6));
        c.drawRoundRect(tmp, dp(9), dp(9), pFill);
        // 车身
        box(c, l, t, r, b, color, dp(9));
        // 车顶
        box(c, l + carW * 0.13f, t + carH * 0.16f, r - carW * 0.13f, b - carH * 0.30f,
                0x33000000, dp(6));
        // 挡风
        box(c, l + carW * 0.16f, t + carH * 0.22f, r - carW * 0.16f, t + carH * 0.42f,
                0x6600E5FF, dp(4));
        // 车灯
        pFill.setColor(hero ? 0xFFFFF9C4 : 0xFFFF6E40);
        c.drawCircle(l + carW * 0.22f, b - dp(4), dp(3.4f), pFill);
        c.drawCircle(r - carW * 0.22f, b - dp(4), dp(3.4f), pFill);
        if (hero) {
            pFill.setColor(0x33FFF9C4);
            tmp.set(l - dp(2), b - dp(2), r + dp(2), b + dp(26));
            c.drawRoundRect(tmp, dp(8), dp(8), pFill);
        }
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
