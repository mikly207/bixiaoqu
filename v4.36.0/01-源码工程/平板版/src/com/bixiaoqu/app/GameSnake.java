package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Random;

/** 贪吃蛇：18 列网格，四周穿墙不死，撞自己才结束 */
public class GameSnake extends GameView {

    static final int COLS = 18;

    int rows, cell, ox, oy;
    final ArrayList<Point> body = new ArrayList<>();
    int dirX = 1, dirY = 0;
    int foodX = 5, foodY = 5;
    float acc;
    int interval = 150;
    float pulse;

    final Random rnd = new Random();

    // 手势
    float downX, downY;
    boolean dragged;

    public GameSnake(Context c) {
        super(c);
        gameId = "snake";
        gameTitle = "贪吃蛇";
    }

    @Override protected void reset() {
        int availH = H - dp(58);
        cell = Math.min((int) (W * 0.94 / COLS), dp(26));
        rows = Math.max(14, Math.min(30, (int) (availH * 0.98 / cell)));
        ox = (W - COLS * cell) / 2;
        oy = (H - rows * cell) / 2 - dp(4);

        body.clear();
        int cx = COLS / 2, cy = rows / 2;
        for (int i = 0; i < 4; i++) body.add(new Point(cx - i, cy));
        dirX = 1; dirY = 0;
        acc = 0;
        interval = 150;
        score = 0;
        pulse = 0;
        spawnFood();
    }

    void spawnFood() {
        for (int k = 0; k < 400; k++) {
            int x = rnd.nextInt(COLS), y = rnd.nextInt(rows);
            boolean hit = false;
            for (Point p : body) if (p.x == x && p.y == y) { hit = true; break; }
            if (!hit) { foodX = x; foodY = y; return; }
        }
    }

    @Override protected void step(int dt) {
        pulse += dt;
        interval = Math.max(78, 155 - score / 10 * 6);
        acc += dt;
        while (acc >= interval) {
            acc -= interval;
            advance();
            if (over) return;
        }
    }

    void advance() {
        Point head = body.get(0);
        int nx = head.x + dirX, ny = head.y + dirY;
        // 穿墙：从另一侧出来，手机上更好操作，不会因为贴边一下手滑就死
        if (nx < 0) nx = COLS - 1;
        if (nx >= COLS) nx = 0;
        if (ny < 0) ny = rows - 1;
        if (ny >= rows) ny = 0;

        // 撞自己：尾巴这一帧会让位，所以最后一节不算
        for (int i = 0; i < body.size() - 1; i++) {
            Point p = body.get(i);
            if (p.x == nx && p.y == ny) {
                gameOver();
                return;
            }
        }

        body.add(0, new Point(nx, ny));
        if (nx == foodX && ny == foodY) {
            score += 10;
            spawnFood();
        } else {
            body.remove(body.size() - 1);
        }
    }

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF14161B);

        float bl = ox - dp(6), bt = oy - dp(6);
        float br = ox + COLS * cell + dp(6), bb = oy + rows * cell + dp(6);
        box(c, bl, bt, br, bb, 0xFF1B1E24, dp(14));

        // 网格
        pLine.setStrokeWidth(dp(0.6f));
        pLine.setColor(0x14FFFFFF);
        for (int x = 1; x < COLS; x++) {
            float px = ox + x * cell;
            c.drawLine(px, oy, px, oy + rows * cell, pLine);
        }
        for (int y = 1; y < rows; y++) {
            float py = oy + y * cell;
            c.drawLine(ox, py, ox + COLS * cell, py, pLine);
        }

        // 食物：呼吸光点
        float fx = ox + foodX * cell + cell / 2f;
        float fy = oy + foodY * cell + cell / 2f;
        float rr = cell * 0.30f + (float) Math.sin(pulse / 260.0) * cell * 0.045f;
        pFill.setColor(0x33FF5A6E);
        c.drawCircle(fx, fy, rr * 2.2f, pFill);
        pFill.setColor(0xFFFF5A6E);
        c.drawCircle(fx, fy, rr, pFill);
        pFill.setColor(0x66FFFFFF);
        c.drawCircle(fx - rr * 0.3f, fy - rr * 0.35f, rr * 0.32f, pFill);

        // 蛇身：从尾到头由深到亮
        int n = body.size();
        for (int i = n - 1; i >= 0; i--) {
            Point p = body.get(i);
            float t = n <= 1 ? 1f : (float) (n - 1 - i) / (n - 1);
            int col = lerpColor(0xFF2E7D32, 0xFF7BE07B, t);
            float l = ox + p.x * cell + cell * 0.07f;
            float tp = oy + p.y * cell + cell * 0.07f;
            float r = ox + p.x * cell + cell * 0.93f;
            float b = oy + p.y * cell + cell * 0.93f;
            box(c, l, tp, r, b, col, cell * 0.34f);
        }

        // 眼睛
        if (n > 0) {
            Point head = body.get(0);
            float hx = ox + head.x * cell + cell / 2f;
            float hy = oy + head.y * cell + cell / 2f;
            float eo = cell * 0.20f;
            float exx = dirY != 0 ? eo : 0, eyy = dirX != 0 ? eo : 0;
            float px = dirX * cell * 0.13f, py = dirY * cell * 0.13f;
            pFill.setColor(0xFF0F2A12);
            c.drawCircle(hx + exx + px, hy + eyy + py, cell * 0.085f, pFill);
            c.drawCircle(hx - exx + px, hy - eyy + py, cell * 0.085f, pFill);
        }

        tip(c, "滑动屏幕转向 · 四周穿墙不死");
    }

    @Override protected boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragged = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragged) break;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) < dp(22) && Math.abs(dy) < dp(22)) break;
                dragged = true;
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 0 && dirX != -1) { dirX = 1; dirY = 0; }
                    else if (dx < 0 && dirX != 1) { dirX = -1; dirY = 0; }
                } else {
                    if (dy > 0 && dirY != -1) { dirX = 0; dirY = 1; }
                    else if (dy < 0 && dirY != 1) { dirX = 0; dirY = -1; }
                }
                break;
            case MotionEvent.ACTION_UP:
                // 轻点：等同「按当前朝向前进一格」的手感不需要，这里只做无效点击的兜底
                dragged = false;
                break;
            default:
                break;
        }
        return true;
    }
}
