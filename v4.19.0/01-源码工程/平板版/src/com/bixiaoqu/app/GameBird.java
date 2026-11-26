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
 * 愤怒的小鸟（简化版）：拖弹弓 → 抛射小鸟 → 撞塌木塔、砸死绿猪。
 * 3 只鸟打完算一关，清光猪就进下一关（塔重新搭，分数保留）。
 */
public class GameBird extends GameView {

    static class Block {
        float x, y, w, h;
        int hp;
        Block(float x, float y, float w, float h, int hp) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.hp = hp;
        }
    }

    static class Pig {
        float x, y, r;
        boolean dead;
        Pig(float x, float y, float r) { this.x = x; this.y = y; this.r = r; }
    }

    final ArrayList<Block> blocks = new ArrayList<>();
    final ArrayList<Pig> pigs = new ArrayList<>();
    final Random rnd = new Random();

    float groundY, slingX, slingY;
    float bx, by, vx, vy;
    boolean flying, aiming, resting;
    float dragX, dragY;
    float restT;
    int birdsLeft = 3;
    int level = 1;
    float flyT;

    static final float G = 1500f;     // 重力 px/s²
    static final float K = 4.6f;      // 拖拽距离 → 初速度

    public GameBird(Context c) {
        super(c);
        gameId = "bird";
        gameTitle = "愤怒的小鸟";
    }

    @Override protected void reset() {
        groundY = H * 0.84f;
        slingX = W * 0.15f;
        slingY = groundY - dp(96);
        birdsLeft = 3;
        level = 1;
        score = 0;
        buildLevel();
        resetBird();
    }

    void resetBird() {
        bx = slingX;
        by = slingY;
        vx = vy = 0;
        flying = false;
        aiming = false;
        resting = false;
        restT = 0;
    }

    void buildLevel() {
        blocks.clear();
        pigs.clear();

        float x0 = W * 0.56f + (level - 1) % 3 * dp(6);
        float towerW = Math.min(W * 0.30f, dp(142));
        float colW = dp(15), plankH = dp(13);
        float colH = dp(88);

        // 底层两柱 + 顶板
        blocks.add(new Block(x0, groundY - colH, colW, colH, 2));
        blocks.add(new Block(x0 + towerW, groundY - colH, colW, colH, 2));
        blocks.add(new Block(x0 - dp(8), groundY - colH - plankH, towerW + colW + dp(16), plankH, 2));

        // 二层两柱 + 顶板（层数随关卡增加，最多三层）
        int layers = Math.min(3, 1 + level / 2);
        float baseY = groundY - colH - plankH;
        for (int i = 0; i < layers - 1; i++) {
            float hh = colH * 0.72f;
            float xa = x0 + towerW * 0.18f;
            float xb = x0 + towerW * 0.72f;
            blocks.add(new Block(xa, baseY - hh, colW, hh, 2));
            blocks.add(new Block(xb, baseY - hh, colW, hh, 2));
            blocks.add(new Block(xa - dp(8), baseY - hh - plankH,
                    (xb - xa) + colW + dp(16), plankH, 2));
            baseY = baseY - hh - plankH;
        }

        // 猪：一只在地面中间，其余在顶层板上
        pigs.add(new Pig(x0 + towerW / 2f, groundY - dp(16), dp(15)));
        pigs.add(new Pig(x0 + towerW * 0.45f, baseY - dp(15), dp(15)));
        if (level >= 2) pigs.add(new Pig(x0 + towerW * 0.80f, baseY - dp(15), dp(14)));
        if (level >= 3) pigs.add(new Pig(x0 + towerW / 2f, baseY - dp(46), dp(13)));
    }

    @Override protected void step(int dt) {
        float s = dt / 1000f;

        if (flying) {
            flyT += s;
            vy += G * s;
            bx += vx * s;
            by += vy * s;

            // 地面
            if (by > groundY - dp(9)) {
                by = groundY - dp(9);
                if (Math.abs(vy) > dp(60)) {
                    vy = -vy * 0.32f;
                    vx *= 0.72f;
                } else {
                    vy = 0;
                    vx *= 0.86f;
                    if (Math.abs(vx) < dp(24)) { vx = 0; resting = true; }
                }
            }

            collide();

            // 出界 / 停下
            if (bx > W + dp(80) || bx < -dp(80) || by > H + dp(100)) {
                nextBird();
            } else if (resting) {
                restT += dt;
                if (restT > 520) nextBird();
            } else if (flyT > 9f) {
                nextBird();
            }
        }
    }

    void collide() {
        float br = dp(9);
        // 木块
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            float nx = Math.max(b.x, Math.min(bx, b.x + b.w));
            float ny = Math.max(b.y, Math.min(by, b.y + b.h));
            float dx = bx - nx, dy = by - ny;
            if (dx * dx + dy * dy > br * br) continue;

            float sp = (float) Math.hypot(vx, vy);
            if (sp > dp(160)) {
                b.hp--;
                if (b.hp <= 0) {
                    blocks.remove(i);
                    score += 20;
                }
                // 弹开
                vx *= -0.25f;
                vy *= 0.35f;
            } else {
                vx *= 0.6f;
                vy *= 0.3f;
            }
            break;
        }

        // 猪
        for (Pig p : pigs) {
            if (p.dead) continue;
            float dx = bx - p.x, dy = by - p.y;
            float rr = p.r + br;
            if (dx * dx + dy * dy < rr * rr) {
                p.dead = true;
                score += 100;
                vx *= 0.5f;
                vy *= 0.5f;
                checkClear();
                return;
            }
        }
    }

    void checkClear() {
        for (Pig p : pigs) if (!p.dead) return;
        // 全清：剩下的鸟折算分数，进下一关
        score += 200 + birdsLeft * 60;
        level++;
        birdsLeft = 3;
        buildLevel();
        resetBird();
    }

    void nextBird() {
        restT = 0;
        flying = false;
        aiming = false;
        resting = false;
        birdsLeft--;
        boolean alive = false;
        for (Pig p : pigs) if (!p.dead) { alive = true; break; }
        if (!alive) { checkClear(); return; }
        if (birdsLeft <= 0) {
            gameOver();
            return;
        }
        resetBird();
    }

    @Override protected void paint(Canvas c) {
        // 天空
        pFill.setShader(new LinearGradient(0, 0, 0, groundY,
                0xFF1E2B3A, 0xFF3B4A5E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, groundY, pFill);
        pFill.setShader(null);

        // 远山
        pFill.setColor(0xFF2A3746);
        c.drawCircle(W * 0.78f, groundY, W * 0.30f, pFill);
        c.drawCircle(W * 0.30f, groundY + dp(20), W * 0.24f, pFill);

        // 地面
        pFill.setColor(0xFF3E5B34);
        c.drawRect(0, groundY, W, H, pFill);
        pFill.setColor(0xFF4C6E3F);
        c.drawRect(0, groundY, W, groundY + dp(8), pFill);

        // 弹弓
        pLine.setStrokeWidth(dp(7));
        pLine.setColor(0xFF6D4C41);
        c.drawLine(slingX - dp(14), groundY, slingX, slingY + dp(14), pLine);
        c.drawLine(slingX + dp(14), groundY, slingX, slingY + dp(14), pLine);
        pLine.setStrokeWidth(dp(4));
        pLine.setColor(0xFF8D6E63);
        c.drawLine(slingX - dp(14), groundY, slingX, slingY + dp(14), pLine);

        // 木块
        for (Block b : blocks) {
            box(c, b.x, b.y, b.x + b.w, b.y + b.h, 0xFF8D6E63, dp(3));
            frame(c, b.x, b.y, b.x + b.w, b.y + b.h, 0x55000000, dp(3), dp(1));
            pLine.setColor(0x33000000);
            pLine.setStrokeWidth(dp(1));
            c.drawLine(b.x + dp(3), b.y + b.h * 0.5f, b.x + b.w - dp(3), b.y + b.h * 0.5f, pLine);
        }

        // 猪
        for (Pig p : pigs) {
            if (p.dead) continue;
            pFill.setColor(0x22000000);
            c.drawCircle(p.x, p.y + dp(2), p.r, pFill);
            pFill.setColor(0xFF7CB342);
            c.drawCircle(p.x, p.y, p.r, pFill);
            pFill.setColor(0xFF8BC34A);
            c.drawCircle(p.x - p.r * 0.2f, p.y - p.r * 0.25f, p.r * 0.72f, pFill);
            // 鼻子
            pFill.setColor(0xFF689F38);
            tmp.set(p.x - p.r * 0.32f, p.y + p.r * 0.05f, p.x + p.r * 0.32f, p.y + p.r * 0.42f);
            c.drawRoundRect(tmp, p.r * 0.2f, p.r * 0.2f, pFill);
            // 眼睛
            pFill.setColor(0xFFFFFFFF);
            c.drawCircle(p.x - p.r * 0.38f, p.y - p.r * 0.32f, p.r * 0.22f, pFill);
            c.drawCircle(p.x + p.r * 0.34f, p.y - p.r * 0.32f, p.r * 0.22f, pFill);
            pFill.setColor(0xFF1B1D22);
            c.drawCircle(p.x - p.r * 0.34f, p.y - p.r * 0.32f, p.r * 0.10f, pFill);
            c.drawCircle(p.x + p.r * 0.38f, p.y - p.r * 0.32f, p.r * 0.10f, pFill);
        }

        // 皮筋
        if (!flying) {
            pLine.setStrokeWidth(dp(4));
            pLine.setColor(0xFF5D4037);
            c.drawLine(slingX - dp(14), slingY + dp(14), bx, by, pLine);
            c.drawLine(slingX + dp(14), slingY + dp(14), bx, by, pLine);
        }

        // 拖拽时的预测轨迹
        if (aiming) {
            float pvx = (slingX - dragX) * K;
            float pvy = (slingY - dragY) * K;
            pFill.setColor(0x66FFFFFF);
            for (int i = 1; i <= 16; i++) {
                float t = i * 0.075f;
                float px = bx + pvx * t;
                float py = by + pvy * t + 0.5f * G * t * t;
                if (py > groundY) break;
                c.drawCircle(px, py, dp(2.0f) * (1f - i / 22f), pFill);
            }
        }

        // 小鸟
        {
            float r = dp(10);
            pFill.setColor(0x33000000);
            c.drawCircle(bx, by + dp(2), r, pFill);
            pFill.setColor(0xFFE53935);
            c.drawCircle(bx, by, r, pFill);
            pFill.setColor(0xFFF5D6D6);
            c.drawCircle(bx, by + r * 0.35f, r * 0.62f, pFill);
            // 眼睛
            pFill.setColor(0xFFFFFFFF);
            c.drawCircle(bx + r * 0.32f, by - r * 0.25f, r * 0.32f, pFill);
            pFill.setColor(0xFF1B1D22);
            c.drawCircle(bx + r * 0.42f, by - r * 0.25f, r * 0.15f, pFill);
            // 嘴
            pFill.setColor(0xFFFFB300);
            tmp.set(bx + r * 0.6f, by, bx + r * 1.6f, by + r * 0.55f);
            c.drawRoundRect(tmp, r * 0.2f, r * 0.2f, pFill);
        }

        label(c, "第 " + level + " 关 · 小鸟 " + birdsLeft, dp(58), dp(32), 11,
                0xE6FFFFFF, Paint.Align.LEFT);
        if (!flying) tip(c, "按住小鸟往后拖，松手发射");
    }

    @Override protected boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (flying) return true;
                float dx = x - bx, dy = y - by;
                if (dx * dx + dy * dy < dp(46) * dp(46)) {
                    aiming = true;
                    dragX = bx;
                    dragY = by;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!aiming) break;
                dragX = x;
                dragY = y;
                float ddx = dragX - slingX, ddy = dragY - slingY;
                float d = (float) Math.hypot(ddx, ddy);
                float max = dp(78);
                if (d > max) {
                    dragX = slingX + ddx / d * max;
                    dragY = slingY + ddy / d * max;
                }
                bx = dragX;
                by = dragY;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!aiming) break;
                aiming = false;
                float px = dragX - slingX, py = dragY - slingY;
                if (Math.hypot(px, py) < dp(8)) {
                    bx = slingX;
                    by = slingY;
                    break;
                }
                vx = -px * K;
                vy = -py * K;
                flying = true;
                flyT = 0;
                restT = 0;
                resting = false;
                break;
            default:
                break;
        }
        return true;
    }
}
