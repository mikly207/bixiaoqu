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
 * 神庙逃亡（简化版）：三条跑道伪 3D 跑酷。
 * 左右滑换道、上滑跳、下滑滑铲；躲开石墙与横梁，捡金币，跑得越远分越高。
 */
public class GameTemple extends GameView {

    static class Obs {
        int lane;      // 0 / 1 / 2
        int type;      // 0 = 矮墙(跳)  1 = 横梁(滑铲)  2 = 金币
        float z;       // 1 最远 → 0 到达玩家
        boolean done;
        Obs(int lane, int type, float z) { this.lane = lane; this.type = type; this.z = z; }
    }

    final ArrayList<Obs> list = new ArrayList<>();
    final Random rnd = new Random();

    float horizonY, playerY, laneW;
    int lane = 1;
    float laneX;              // 当前横向位置（平滑跟随）
    float jumpY, jumpV;
    boolean sliding;
    float slideT;
    float speed = 0.52f;      // z / 秒
    float spawnAcc;
    float roadPhase;
    float dist;
    float shakeT;

    float downX, downY;

    public GameTemple(Context c) {
        super(c);
        gameId = "temple";
        gameTitle = "神庙逃亡";
    }

    @Override protected void reset() {
        horizonY = H * 0.32f;
        playerY = H * 0.84f;
        laneW = W * 0.27f;
        lane = 1;
        laneX = laneOffset(1);
        jumpY = 0; jumpV = 0;
        sliding = false; slideT = 0;
        speed = 0.52f;
        spawnAcc = 0;
        roadPhase = 0;
        dist = 0;
        shakeT = 0;
        list.clear();
        score = 0;
        // 开局先放一段空白，别一上来就撞
        for (int i = 0; i < 3; i++) spawnRow(1.0f + i * 0.30f);
    }

    float laneOffset(int l) { return (l - 1) * laneW; }

    @Override protected void step(int dt) {
        float s = dt / 1000f;
        speed = Math.min(1.15f, 0.52f + dist * 0.0016f);
        roadPhase = (roadPhase + speed * s) % 1f;
        dist += speed * s * 10;
        score = (int) dist;

        // 跳跃
        if (jumpY > 0 || jumpV < 0) {
            jumpY += jumpV * s;
            jumpV += 3000f * s;
            if (jumpY <= 0) { jumpY = 0; jumpV = 0; }
        }
        // 滑铲
        if (sliding) {
            slideT -= dt;
            if (slideT <= 0) sliding = false;
        }
        // 横向平滑
        float target = laneOffset(lane);
        laneX += (target - laneX) * Math.min(1f, s * 14f);

        // 生成
        // 按时间出，不按距离：速度快了也不会瞬间铺满一屏
        spawnAcc += s;
        float gapT = Math.max(0.62f, 0.98f - dist * 0.0004f);
        if (spawnAcc > gapT) {
            spawnAcc = 0;
            spawnRow(1.02f);
        }

        // 推进 & 碰撞
        for (int i = list.size() - 1; i >= 0; i--) {
            Obs o = list.get(i);
            o.z -= speed * s;
            if (o.z < -0.12f) {
                if (o.type == 2 && !o.done && o.z < 0.02f) score += 30;
                list.remove(i);
                continue;
            }
            if (o.done) continue;
            if (o.z > 0.06f || o.z < -0.02f) continue;
            if (o.lane != lane) continue;

            if (o.type == 2) {
                if (jumpY < dp(60)) {
                    o.done = true;
                    score += 30;
                }
            } else if (o.type == 0) {
                if (jumpY < dp(34)) {
                    shakeT = 300;
                    gameOver();
                    return;
                }
            } else {
                if (!sliding) {
                    shakeT = 300;
                    gameOver();
                    return;
                }
            }
        }
        if (shakeT > 0) shakeT -= dt;
    }

    void spawnRow(float z) {
        // 至少留一条空道，避免出现必死组合
        int blocked = rnd.nextInt(3) == 0 ? 2 : 1;
        boolean[] used = new boolean[3];
        for (int i = 0; i < blocked; i++) {
            int l;
            int guard = 0;
            do { l = rnd.nextInt(3); guard++; } while (used[l] && guard < 20);
            used[l] = true;
            list.add(new Obs(l, rnd.nextInt(3) == 0 ? 1 : 0, z + rnd.nextFloat() * 0.05f));
        }
        if (rnd.nextInt(100) < 45) {
            int l;
            int guard = 0;
            do { l = rnd.nextInt(3); guard++; } while (used[l] && guard < 20);
            list.add(new Obs(l, 2, z + 0.10f));
        }
    }

    @Override protected void paint(Canvas c) {
        float ox = 0, oy = 0;
        if (shakeT > 0) {
            ox = (float) (Math.random() - 0.5) * dp(6);
            oy = (float) (Math.random() - 0.5) * dp(6);
        }
        c.save();
        c.translate(ox, oy);

        // 天空
        pFill.setShader(new LinearGradient(0, 0, 0, horizonY,
                0xFF2B1F2E, 0xFF6B4A46, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, horizonY, pFill);
        pFill.setShader(null);

        // 远处神庙剪影
        pFill.setColor(0xFF241A22);
        c.drawRect(W * 0.30f, horizonY - dp(54), W * 0.70f, horizonY, pFill);
        Path tp = new Path();
        tp.moveTo(W * 0.30f, horizonY - dp(54));
        tp.lineTo(W * 0.50f, horizonY - dp(84));
        tp.lineTo(W * 0.70f, horizonY - dp(54));
        tp.close();
        c.drawPath(tp, pFill);

        // 路面
        pFill.setColor(0xFF3B3230);
        Path road = new Path();
        road.moveTo(W / 2f - laneW * 1.5f * 0.16f, horizonY);
        road.lineTo(W / 2f + laneW * 1.5f * 0.16f, horizonY);
        road.lineTo(W / 2f + laneW * 1.5f, H);
        road.lineTo(W / 2f - laneW * 1.5f, H);
        road.close();
        c.drawPath(road, pFill);

        // 车道分隔线
        pLine.setStrokeWidth(dp(2));
        pLine.setColor(0x33FFFFFF);
        for (int i = -1; i <= 1; i += 2) {
            float off = i * laneW * 0.5f;
            c.drawLine(W / 2f + off * 0.16f, horizonY, W / 2f + off, H, pLine);
        }
        // 边缘线
        pLine.setColor(0x66FB7299);
        pLine.setStrokeWidth(dp(2.4f));
        for (int i = -1; i <= 1; i += 2) {
            float off = i * laneW * 1.5f;
            c.drawLine(W / 2f + off * 0.16f, horizonY, W / 2f + off, H, pLine);
        }

        // 横向速度线（路面上的斑纹）
        for (int i = 0; i < 9; i++) {
            float z = (i / 9f + roadPhase) % 1f;
            if (z < 0.001f) continue;
            float k = 1f / (1f + z * 5.2f);
            float y = horizonY + (playerY + dp(40) - horizonY) * k;
            pFill.setColor((int) (0x28 * (1f - z)) << 24 | 0xFFFFFF);
            float hw = laneW * 1.5f * k;
            c.drawRect(W / 2f - hw, y, W / 2f + hw, y + dp(2) * k * 2f, pFill);
        }

        // 物体：远 → 近
        for (int i = list.size() - 1; i >= 0; i--) {
            Obs o = list.get(i);
            if (o.z <= 0.005f || o.z > 1.05f) continue;
            float k = 1f / (1f + o.z * 5.2f);
            float x = W / 2f + laneOffset(o.lane) * k;
            float y = horizonY + (playerY + dp(40) - horizonY) * k;
            float bw = laneW * 0.62f * k;
            float bh = dp(38) * k * 2.2f;

            if (o.type == 2) {
                if (o.done) continue;
                float rr = dp(9) * k * 2.0f;
                pFill.setColor(0x66FFD54F);
                c.drawCircle(x, y - bh * 0.55f, rr * 1.5f, pFill);
                pFill.setColor(0xFFFFD54F);
                c.drawCircle(x, y - bh * 0.55f, rr, pFill);
                pFill.setColor(0xFFFFF59D);
                c.drawCircle(x - rr * 0.3f, y - bh * 0.55f - rr * 0.3f, rr * 0.35f, pFill);
            } else if (o.type == 0) {
                box(c, x - bw / 2, y - bh * 0.55f, x + bw / 2, y + bh * 0.1f,
                        0xFF6D5B4E, dp(2) * k);
                frame(c, x - bw / 2, y - bh * 0.55f, x + bw / 2, y + bh * 0.1f,
                        0x66FFFFFF, dp(2) * k, dp(1.2f));
            } else {
                // 横梁：从上方吊下来，必须滑铲
                float topY = y - bh * 1.9f;
                box(c, x - bw / 2, topY, x + bw / 2, y - bh * 0.55f,
                        0xFF8D6E63, dp(2) * k);
                pLine.setColor(0x88FFFFFF);
                pLine.setStrokeWidth(dp(1) * Math.max(0.4f, k));
                c.drawLine(x - bw / 2, (topY + y - bh * 0.55f) / 2,
                        x + bw / 2, (topY + y - bh * 0.55f) / 2, pLine);
            }
        }

        // 玩家
        float px = W / 2f + laneX;
        float py = playerY - jumpY;
        float ph = sliding ? dp(22) : dp(46);
        pFill.setColor(0x44000000);
        c.drawOval(px - dp(16), playerY - dp(4), px + dp(16), playerY + dp(6), pFill);
        // 身体
        box(c, px - dp(9), py - ph + dp(10), px + dp(9), py, 0xFF29B6F6, dp(6));
        // 头
        pFill.setColor(0xFFF5CBA7);
        c.drawCircle(px, py - ph + dp(4), dp(8), pFill);
        // 背包
        pFill.setColor(0xFFFB7299);
        tmp.set(px - dp(12), py - ph + dp(16), px - dp(4), py - ph + dp(30));
        c.drawRoundRect(tmp, dp(3), dp(3), pFill);

        c.restore();

        label(c, "距离 " + (int) dist + " m", dp(58), dp(32), 12, 0xE6FFFFFF, Paint.Align.LEFT);
        label(c, "速度 " + String.format("%.1f", speed / 0.52f) + "×", dp(58), dp(50), 10,
                0x99FFFFFF, Paint.Align.LEFT);
        tip(c, "左右滑换道 · 上滑跳 · 下滑滑铲");
    }

    @Override protected boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) < dp(20) && Math.abs(dy) < dp(20)) {
                    // 轻点：点左半屏左移、右半屏右移
                    if (e.getX() < W / 2f) { if (lane > 0) lane--; }
                    else { if (lane < 2) lane++; }
                    return true;
                }
                if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 0) { if (lane < 2) lane++; }
                    else { if (lane > 0) lane--; }
                } else if (dy < 0) {
                    if (jumpY <= 0) { jumpV = -1120f; jumpY = 0.1f; }
                } else {
                    sliding = true;
                    slideT = 620;
                }
                return true;
            default:
                return true;
        }
    }
}
