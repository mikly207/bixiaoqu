package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Random;

/**
 * 保卫萝卜（简化版）：怪物沿折线路径走向萝卜，你在路边点空地建炮塔拦截。
 * 击杀得金币，金币再建塔，萝卜血量归零就结束。
 */
public class GameCarrot extends GameView {

    static class Monster {
        float d;          // 沿路径的里程
        float hp, maxHp;
        float speed;
        boolean dead;
    }

    static class Tower {
        float x, y;
        float cd;
        float aim;
    }

    static class Bullet {
        float x, y, vx, vy;
        Monster target;
        float dmg;
    }

    final ArrayList<Monster> monsters = new ArrayList<>();
    final ArrayList<Tower> towers = new ArrayList<>();
    final ArrayList<Bullet> bullets = new ArrayList<>();
    final Random rnd = new Random();

    final ArrayList<float[]> wp = new ArrayList<>();
    final ArrayList<Float> segLen = new ArrayList<>();
    float totalLen;

    float carrotX, carrotY;
    // 【v4.15.1 无限硬币】游戏金币设为无限：初始即高额，建塔不再扣钱。
    // 注意：这是「保卫萝卜」小游戏的本地金币，与 B 站账号硬币（服务器数据）无关。
    int coin = 99999999;
    int carrotHp = 100;
    int wave = 1;
    float spawnT, waveT;
    int waveLeft;
    String hint = "";
    float hintT;
    float pulse;

    static final float TOWER_RANGE = 108;   // dp
    static final int TOWER_COST = 50;

    public GameCarrot(Context c) {
        super(c);
        gameId = "carrot";
        gameTitle = "保卫萝卜";
    }

    @Override protected void reset() {
        buildPath();
        monsters.clear();
        towers.clear();
        bullets.clear();
        coin = 99999999;   // 【v4.15.1 无限硬币】重开也保持无限
        carrotHp = 100;
        wave = 1;
        spawnT = 1200;
        waveT = 0;
        waveLeft = 4;
        hint = "";
        hintT = 0;
        pulse = 0;
        score = 0;
    }

    void buildPath() {
        wp.clear();
        segLen.clear();
        float top = dp(58);
        float bottom = H - dp(16);
        float h = bottom - top;

        float[][] p = {
                {W * 0.99f, top + h * 0.10f},
                {W * 0.12f, top + h * 0.10f},
                {W * 0.12f, top + h * 0.36f},
                {W * 0.86f, top + h * 0.36f},
                {W * 0.86f, top + h * 0.62f},
                {W * 0.16f, top + h * 0.62f},
                {W * 0.16f, top + h * 0.86f},
                {W * 0.56f, top + h * 0.86f},
        };
        for (float[] q : p) wp.add(new float[]{q[0], q[1]});

        totalLen = 0;
        for (int i = 0; i < wp.size() - 1; i++) {
            float[] a = wp.get(i), b = wp.get(i + 1);
            float l = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            segLen.add(l);
            totalLen += l;
        }
        carrotX = wp.get(wp.size() - 1)[0] + dp(14);
        carrotY = wp.get(wp.size() - 1)[1];
    }

    float[] ptAt(float d) {
        if (d <= 0) return wp.get(0);
        for (int i = 0; i < segLen.size(); i++) {
            float l = segLen.get(i);
            if (d <= l) {
                float[] a = wp.get(i), b = wp.get(i + 1);
                float t = l <= 0 ? 0 : d / l;
                return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            d -= l;
        }
        return wp.get(wp.size() - 1);
    }

    @Override protected void step(int dt) {
        float s = dt / 1000f;
        pulse += s;
        if (hintT > 0) hintT -= dt;

        // 出怪
        spawnT -= dt;
        if (spawnT <= 0 && waveLeft > 0) {
            spawnT = Math.max(700, 1500 - wave * 60);
            waveLeft--;
            Monster m = new Monster();
            m.d = 0;
            m.maxHp = 60 + wave * 20;
            m.hp = m.maxHp;
            m.speed = dp(42 + wave * 2);
            monsters.add(m);
        }
        waveT += dt;
        if (waveT > 14000) {
            waveT = 0;
            wave++;
            waveLeft += 3 + wave;
            spawnT = 600;
        }

        // 怪物前进
        for (int i = monsters.size() - 1; i >= 0; i--) {
            Monster m = monsters.get(i);
            if (m.dead) { monsters.remove(i); continue; }
            m.d += m.speed * s;
            if (m.d >= totalLen) {
                monsters.remove(i);
                carrotHp -= 12;
                score = Math.max(0, score - 5);
                if (carrotHp <= 0) {
                    carrotHp = 0;
                    gameOver();
                    return;
                }
            }
        }

        // 炮塔
        for (Tower t : towers) {
            t.cd -= dt;
            Monster best = null;
            float bestD = -1;
            for (Monster m : monsters) {
                float[] p = ptAt(m.d);
                float dist = (float) Math.hypot(p[0] - t.x, p[1] - t.y);
                if (dist > dp(TOWER_RANGE)) continue;
                if (m.d > bestD) { bestD = m.d; best = m; }
            }
            if (best != null) {
                float[] p = ptAt(best.d);
                t.aim = (float) Math.atan2(p[1] - t.y, p[0] - t.x);
                if (t.cd <= 0) {
                    t.cd = 720;
                    Bullet b = new Bullet();
                    b.x = t.x + (float) Math.cos(t.aim) * dp(12);
                    b.y = t.y + (float) Math.sin(t.aim) * dp(12);
                    b.target = best;
                    b.dmg = 26;
                    bullets.add(b);
                }
            }
        }

        // 子弹
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            if (b.target == null || b.target.dead) {
                // 目标没了，就近改打
                Monster alt = null;
                float bd = dp(90);
                for (Monster m : monsters) {
                    float[] p = ptAt(m.d);
                    float dd = (float) Math.hypot(p[0] - b.x, p[1] - b.y);
                    if (dd < bd) { bd = dd; alt = m; }
                }
                if (alt == null) { bullets.remove(i); continue; }
                b.target = alt;
            }
            float[] tp = ptAt(b.target.d);
            float dx = tp[0] - b.x, dy = tp[1] - b.y;
            float d = (float) Math.hypot(dx, dy);
            float sp = dp(520) * s;
            if (d <= sp || d < dp(10)) {
                b.target.hp -= b.dmg;
                if (b.target.hp <= 0 && !b.target.dead) {
                    b.target.dead = true;
                    coin += 12;
                    score += 15;
                }
                bullets.remove(i);
            } else {
                b.x += dx / d * sp;
                b.y += dy / d * sp;
            }
        }
    }

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF1B2A1B);

        // 草地纹理
        pFill.setColor(0xFF23361F);
        for (int i = 0; i < 40; i++) {
            float x = (i * 97 % 100) / 100f * W;
            float y = ((i * 53) % 100) / 100f * H;
            c.drawCircle(x, y, dp(18), pFill);
        }

        // 路径
        Path path = new Path();
        path.moveTo(wp.get(0)[0], wp.get(0)[1]);
        for (int i = 1; i < wp.size(); i++) path.lineTo(wp.get(i)[0], wp.get(i)[1]);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeWidth(dp(30));
        pLine.setColor(0xFF6B5636);
        c.drawPath(path, pLine);
        pLine.setStrokeWidth(dp(22));
        pLine.setColor(0xFF8A7047);
        c.drawPath(path, pLine);
        pLine.setStrokeWidth(dp(2));
        pLine.setColor(0x33FFFFFF);
        for (float d = 0; d < totalLen; d += dp(26)) {
            float[] p = ptAt(d);
            float[] q = ptAt(Math.min(totalLen, d + dp(10)));
            c.drawLine(p[0], p[1], q[0], q[1], pLine);
        }
        pLine.setStyle(Paint.Style.STROKE);

        // 萝卜
        drawCarrot(c);

        // 炮塔
        for (Tower t : towers) {
            pFill.setColor(0x33000000);
            c.drawCircle(t.x, t.y + dp(3), dp(14), pFill);
            pFill.setColor(0xFF37474F);
            c.drawCircle(t.x, t.y, dp(13), pFill);
            pFill.setColor(0xFF455A64);
            c.drawCircle(t.x, t.y, dp(9), pFill);
            // 炮管
            pLine.setStrokeWidth(dp(6));
            pLine.setColor(0xFF64B5F6);
            c.drawLine(t.x, t.y, t.x + (float) Math.cos(t.aim) * dp(14),
                    t.y + (float) Math.sin(t.aim) * dp(14), pLine);
            pFill.setColor(0xFF90CAF9);
            c.drawCircle(t.x, t.y, dp(4), pFill);
        }

        // 怪物
        for (Monster m : monsters) {
            float[] p = ptAt(m.d);
            float r = dp(11);
            pFill.setColor(0x44000000);
            c.drawCircle(p[0], p[1] + dp(3), r, pFill);
            pFill.setColor(0xFF8E24AA);
            c.drawCircle(p[0], p[1], r, pFill);
            pFill.setColor(0xFFAB47BC);
            c.drawCircle(p[0] - r * 0.3f, p[1] - r * 0.3f, r * 0.6f, pFill);
            pFill.setColor(0xFFFFFFFF);
            c.drawCircle(p[0] - r * 0.35f, p[1] - r * 0.1f, r * 0.25f, pFill);
            c.drawCircle(p[0] + r * 0.35f, p[1] - r * 0.1f, r * 0.25f, pFill);
            pFill.setColor(0xFF1B1D22);
            c.drawCircle(p[0] - r * 0.35f, p[1] - r * 0.1f, r * 0.12f, pFill);
            c.drawCircle(p[0] + r * 0.35f, p[1] - r * 0.1f, r * 0.12f, pFill);
            // 血条
            float t = Math.max(0, m.hp / m.maxHp);
            box(c, p[0] - dp(13), p[1] - dp(19), p[0] + dp(13), p[1] - dp(16),
                    0x88000000, dp(1.5f));
            box(c, p[0] - dp(13), p[1] - dp(19), p[0] - dp(13) + dp(26) * t, p[1] - dp(16),
                    0xFFE53935, dp(1.5f));
        }

        // 子弹
        pFill.setColor(0xFFFFEB3B);
        for (Bullet b : bullets) {
            pFill.setColor(0x55FFEB3B);
            c.drawCircle(b.x, b.y, dp(7), pFill);
            pFill.setColor(0xFFFFF176);
            c.drawCircle(b.x, b.y, dp(3.4f), pFill);
        }

        drawHud(c);

        if (hintT > 0) {
            float a = Math.min(1f, hintT / 400f);
            pTxt.setTextSize(12 * dens);
            pTxt.setTextAlign(Paint.Align.LEFT);
            float tw = pTxt.measureText(hint);
            float bw = tw + dp(28);
            float l = (W - bw) / 2f, t = H * 0.46f;
            box(c, l, t, l + bw, t + dp(34), ((int) (200 * a) << 24) | 0x000000,
                    dp(17));
            label(c, hint, W / 2f, t + dp(22), 12, ((int) (255 * a) << 24) | 0xFFFFFF,
                    Paint.Align.CENTER);
        } else {
            tip(c, "点空地建炮塔（" + TOWER_COST + " 金币）· 守住萝卜");
        }
    }

    void drawCarrot(Canvas c) {
        float x = carrotX, y = carrotY;
        float bob = (float) Math.sin(pulse * 2) * dp(1.5f);
        // 叶子
        pFill.setColor(0xFF43A047);
        for (int i = -1; i <= 1; i++) {
            float lx = x + i * dp(7);
            tmp.set(lx - dp(4), y - dp(34) + bob + Math.abs(i) * dp(4),
                    lx + dp(4), y - dp(16) + bob);
            c.drawRoundRect(tmp, dp(4), dp(4), pFill);
        }
        // 身体
        Path p = new Path();
        p.moveTo(x - dp(13), y - dp(18) + bob);
        p.lineTo(x + dp(13), y - dp(18) + bob);
        p.lineTo(x + dp(2), y + dp(12) + bob);
        p.close();
        pFill.setColor(0xFFF4511E);
        c.drawPath(p, pFill);
        pFill.setColor(0xFFFB8C00);
        tmp.set(x - dp(9), y - dp(14) + bob, x - dp(3), y - dp(2) + bob);
        c.drawRoundRect(tmp, dp(3), dp(3), pFill);
        // 眼睛
        pFill.setColor(0xFFFFFFFF);
        c.drawCircle(x - dp(5), y - dp(9) + bob, dp(3.4f), pFill);
        c.drawCircle(x + dp(5), y - dp(9) + bob, dp(3.4f), pFill);
        pFill.setColor(0xFF1B1D22);
        c.drawCircle(x - dp(4.4f), y - dp(9) + bob, dp(1.6f), pFill);
        c.drawCircle(x + dp(5.6f), y - dp(9) + bob, dp(1.6f), pFill);
        // 血条
        float t = carrotHp / 100f;
        box(c, x - dp(20), y + dp(18), x + dp(20), y + dp(23), 0x88000000, dp(2.5f));
        box(c, x - dp(20), y + dp(18), x - dp(20) + dp(40) * t, y + dp(23),
                t > 0.4f ? 0xFF66BB6A : 0xFFE53935, dp(2.5f));
        label(c, "萝卜", x, y + dp(36), 9, 0x99FFFFFF, Paint.Align.CENTER);
    }

    void drawHud(Canvas c) {
        box(c, dp(54), dp(8), dp(196), dp(48), 0xCC000000, dp(12));
        pFill.setColor(0xFFFFD54F);
        c.drawCircle(dp(72), dp(28), dp(9), pFill);
        label(c, "" + coin, dp(88), dp(34), 15, 0xFFFFE082);
        label(c, "金币", dp(88), dp(44), 8, 0xFF9AA0AB);

        label(c, "第 " + wave + " 波 · 剩余 " + (waveLeft + monsters.size()) + " 只",
                dp(54), dp(64), 10, 0xFF8A909C, Paint.Align.LEFT);
    }

    @Override protected boolean touch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY();
        if (y < dp(54)) return true;   // HUD 区域

        // 不能建在路径上
        if (distToPath(x, y) < dp(22)) {
            hint = "这里是小怪走的路，换个空地";
            hintT = 1400;
            return true;
        }
        // 不能太近重叠
        for (Tower t : towers) {
            if (Math.hypot(t.x - x, t.y - y) < dp(30)) {
                hint = "离已有炮塔太近了";
                hintT = 1200;
                return true;
            }
        }
        // 【v4.15.1 无限硬币】建塔不扣金币，无需攒钱
        coin -= 0;
        Tower t = new Tower();
        t.x = x;
        t.y = y;
        t.cd = 0;
        towers.add(t);
        return true;
    }

    float distToPath(float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < wp.size() - 1; i++) {
            float[] a = wp.get(i), b = wp.get(i + 1);
            float dx = b[0] - a[0], dy = b[1] - a[1];
            float len2 = dx * dx + dy * dy;
            float t = len2 <= 0 ? 0 : ((x - a[0]) * dx + (y - a[1]) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            float px = a[0] + dx * t, py = a[1] + dy * t;
            best = Math.min(best, (float) Math.hypot(x - px, y - py));
        }
        return best;
    }
}
