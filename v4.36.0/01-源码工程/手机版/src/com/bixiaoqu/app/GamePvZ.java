package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Random;

/**
 * 植物大战僵尸（简化版）：5 行 9 列草坪。
 * 阳光 → 种向日葵产阳光、种豌豆射手打僵尸、种坚果挡路；
 * 僵尸走到最左边就算防线失守。
 */
public class GamePvZ extends GameView {

    static final int ROWS = 5, COLS = 9;
    static final int SUNFLOWER = 0, PEASHOOTER = 1, WALLNUT = 2;
    static final int[] COST = {50, 100, 50};
    static final String[] PNAME = {"向日葵", "豌豆射手", "坚果墙"};

    static class Plant {
        int row, col, type;
        float hp = 100;
        float shootT;
        float anim;
    }

    static class Zombie {
        int row;
        float x;
        float hp;
        float eatT;
        float walk;
        boolean eating;
    }

    static class Pea {
        int row;
        float x, y;
    }

    static class Sun {
        float x, y, vy, tx, ty;
        float life;
        boolean landed;
    }

    final ArrayList<Plant> plants = new ArrayList<>();
    final ArrayList<Zombie> zombies = new ArrayList<>();
    final ArrayList<Pea> peas = new ArrayList<>();
    final ArrayList<Sun> suns = new ArrayList<>();
    final Random rnd = new Random();

    float gridL, gridT, cellW, cellH;
    int sunPoint = 75;
    int selected = -1;
    float skySunT;
    float zombieT;
    float waveT;
    int wave = 1;
    float cardY, cardH;

    public GamePvZ(Context c) {
        super(c);
        gameId = "pvz";
        gameTitle = "植物大战僵尸";
    }

    @Override protected void reset() {
        gridL = dp(4);
        cardY = dp(6);
        cardH = dp(42);
        gridT = cardY + cardH + dp(8);
        cellW = (W - gridL * 2) / COLS;
        cellH = (H - gridT - dp(10)) / ROWS;

        plants.clear();
        zombies.clear();
        peas.clear();
        suns.clear();
        sunPoint = 75;
        selected = -1;
        skySunT = 2000;
        zombieT = 5000;
        waveT = 0;
        wave = 1;
        score = 0;
    }

    float colX(int c) { return gridL + (c + 0.5f) * cellW; }
    float rowY(int r) { return gridT + (r + 0.5f) * cellH; }

    @Override protected void step(int dt) {
        float s = dt / 1000f;

        // 天降阳光
        skySunT -= dt;
        if (skySunT <= 0) {
            skySunT = 6500 + rnd.nextInt(2500);
            Sun sn = new Sun();
            sn.x = dp(30) + rnd.nextFloat() * (W - dp(60));
            sn.y = gridT - dp(20);
            sn.tx = sn.x;
            sn.ty = gridT + rnd.nextFloat() * (ROWS * cellH - dp(20));
            sn.vy = dp(60);
            sn.life = 9000;
            suns.add(sn);
        }

        for (int i = suns.size() - 1; i >= 0; i--) {
            Sun sn = suns.get(i);
            sn.life -= dt;
            if (!sn.landed) {
                if (sn.y < sn.ty) sn.y += sn.vy * s;
                else sn.landed = true;
            }
            if (sn.life <= 0) suns.remove(i);
        }

        // 植物
        for (Plant p : plants) {
            p.anim += s;
            if (p.type == SUNFLOWER) {
                p.shootT += dt;
                if (p.shootT > 8000) {
                    p.shootT = 0;
                    Sun sn = new Sun();
                    sn.x = colX(p.col);
                    sn.y = rowY(p.row) - dp(6);
                    sn.tx = sn.x;
                    sn.ty = sn.y + dp(4);
                    sn.landed = true;
                    sn.life = 7000;
                    suns.add(sn);
                }
            } else if (p.type == PEASHOOTER) {
                p.shootT += dt;
                // 本行有僵尸才开火，省得满屏豆子
                boolean has = false;
                for (Zombie z : zombies) {
                    if (z.row == p.row && z.x > colX(p.col) - cellW * 0.4f && z.x < W + dp(40)) {
                        has = true;
                        break;
                    }
                }
                if (has && p.shootT > 1450) {
                    p.shootT = 0;
                    Pea pea = new Pea();
                    pea.row = p.row;
                    pea.x = colX(p.col) + cellW * 0.34f;
                    pea.y = rowY(p.row) - cellH * 0.12f;
                    peas.add(pea);
                }
            }
        }

        // 豌豆
        for (int i = peas.size() - 1; i >= 0; i--) {
            Pea pea = peas.get(i);
            pea.x += dp(360) * s;
            if (pea.x > W + dp(20)) { peas.remove(i); continue; }
            for (Zombie z : zombies) {
                if (z.row != pea.row) continue;
                if (Math.abs(z.x - pea.x) < dp(16)) {
                    z.hp -= 20;
                    peas.remove(i);
                    break;
                }
            }
        }

        // 僵尸
        zombieT -= dt;
        if (zombieT <= 0) {
            zombieT = Math.max(1800, 5200 - wave * 340);
            Zombie z = new Zombie();
            z.row = rnd.nextInt(ROWS);
            z.x = W + dp(20);
            z.hp = 100 + (wave - 1) * 22;
            z.walk = 0;
            zombies.add(z);
        }

        for (int i = zombies.size() - 1; i >= 0; i--) {
            Zombie z = zombies.get(i);
            if (z.hp <= 0) {
                zombies.remove(i);
                score += 12;
                continue;
            }
            z.walk += s * 4f;
            // 找本行最靠右的可啃植物
            Plant target = null;
            float bestX = -1;
            for (Plant p : plants) {
                if (p.row != z.row) continue;
                float cxp = colX(p.col);
                if (cxp < z.x + dp(6) && cxp > z.x - cellW * 0.62f && cxp > bestX) {
                    bestX = cxp;
                    target = p;
                }
            }
            if (target != null) {
                z.eating = true;
                z.eatT += dt;
                if (z.eatT > 700) {
                    z.eatT = 0;
                    target.hp -= 22;
                    if (target.hp <= 0) plants.remove(target);
                }
            } else {
                z.eating = false;
                z.x -= dp(22) * s;
            }
            if (z.x < gridL - dp(6)) {
                gameOver();
                return;
            }
        }

        // 波次
        waveT += dt;
        if (waveT > 22000) {
            waveT = 0;
            wave++;
        }
    }

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF171A1F);

        // 草坪
        for (int r = 0; r < ROWS; r++) {
            for (int cc = 0; cc < COLS; cc++) {
                boolean dark = ((r + cc) & 1) == 0;
                pFill.setColor(dark ? 0xFF2E4A2A : 0xFF355431);
                c.drawRect(gridL + cc * cellW, gridT + r * cellH,
                        gridL + (cc + 1) * cellW, gridT + (r + 1) * cellH, pFill);
            }
        }

        // 选中格高亮
        if (selected >= 0) {
            pLine.setColor(0x66FB7299);
            pLine.setStrokeWidth(dp(1.4f));
            for (int r = 0; r < ROWS; r++) {
                for (int cc = 0; cc < COLS; cc++) {
                    if (plantAt(r, cc) != null) continue;
                    c.drawRect(gridL + cc * cellW + dp(2), gridT + r * cellH + dp(2),
                            gridL + (cc + 1) * cellW - dp(2), gridT + (r + 1) * cellH - dp(2),
                            pLine);
                }
            }
        }

        // 植物
        for (Plant p : plants) drawPlant(c, p);

        // 豌豆
        for (Pea pea : peas) {
            pFill.setColor(0xFF9CCC65);
            c.drawCircle(pea.x, pea.y, dp(5), pFill);
            pFill.setColor(0xFFC5E1A5);
            c.drawCircle(pea.x - dp(1.4f), pea.y - dp(1.4f), dp(2), pFill);
        }

        // 僵尸
        for (Zombie z : zombies) drawZombie(c, z);

        // 阳光
        for (Sun sn : suns) {
            float a = sn.life < 1800 ? (sn.life / 1800f) : 1f;
            pFill.setColor(((int) (70 * a) << 24) | 0xFFD54F);
            c.drawCircle(sn.x, sn.y, dp(20), pFill);
            pFill.setColor(((int) (255 * a) << 24) | 0xFFD54F);
            c.drawCircle(sn.x, sn.y, dp(11), pFill);
            pFill.setColor(((int) (230 * a) << 24) | 0xFFF59D);
            c.drawCircle(sn.x - dp(3), sn.y - dp(3), dp(4), pFill);
        }

        drawCardBar(c);
        tip(c, "点卡片选植物，再点草坪种下 · 点阳光收集");
    }

    void drawPlant(Canvas c, Plant p) {
        float cx = colX(p.col), cy = rowY(p.row);
        float bob = (float) Math.sin(p.anim * 2.2) * dp(1.6f);
        if (p.type == SUNFLOWER) {
            pFill.setColor(0xFF6D4C41);
            c.drawRect(cx - dp(2.5f), cy, cx + dp(2.5f), cy + cellH * 0.22f, pFill);
            pFill.setColor(0xFFFDD835);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4 + p.anim * 0.6;
                c.drawCircle(cx + (float) Math.cos(a) * dp(11), cy + bob + (float) Math.sin(a) * dp(11),
                        dp(5), pFill);
            }
            pFill.setColor(0xFFFFEE58);
            c.drawCircle(cx, cy + bob, dp(9), pFill);
            pFill.setColor(0xFF5D4037);
            c.drawCircle(cx - dp(3), cy + bob, dp(1.6f), pFill);
            c.drawCircle(cx + dp(3), cy + bob, dp(1.6f), pFill);
        } else if (p.type == PEASHOOTER) {
            pFill.setColor(0xFF2E7D32);
            c.drawRect(cx - dp(2.5f), cy + dp(2), cx + dp(2.5f), cy + cellH * 0.24f, pFill);
            pFill.setColor(0xFF66BB6A);
            c.drawCircle(cx, cy + bob, dp(12), pFill);
            pFill.setColor(0xFF81C784);
            c.drawCircle(cx - dp(3), cy + bob - dp(3), dp(5), pFill);
            // 炮管
            box(c, cx + dp(6), cy + bob - dp(4), cx + dp(18), cy + bob + dp(4),
                    0xFF4CAF50, dp(4));
            pFill.setColor(0xFF1B5E20);
            c.drawCircle(cx - dp(4), cy + bob - dp(2), dp(1.8f), pFill);
        } else {
            pFill.setColor(0xFF8D6E63);
            c.drawCircle(cx, cy + bob, dp(14), pFill);
            pFill.setColor(0xFFA1887F);
            c.drawCircle(cx - dp(4), cy + bob - dp(4), dp(7), pFill);
            pFill.setColor(0xFF4E342E);
            c.drawCircle(cx - dp(4), cy + bob - dp(1), dp(1.8f), pFill);
            c.drawCircle(cx + dp(4), cy + bob - dp(1), dp(1.8f), pFill);
            // 裂纹表示受损
            if (p.hp < 70) {
                pLine.setColor(0x66000000);
                pLine.setStrokeWidth(dp(1.4f));
                c.drawLine(cx - dp(6), cy + bob - dp(6), cx + dp(2), cy + bob + dp(4), pLine);
            }
        }
    }

    void drawZombie(Canvas c, Zombie z) {
        float cx = z.x, cy = rowY(z.row);
        float sway = (float) Math.sin(z.walk) * dp(2);
        // 影子
        pFill.setColor(0x33000000);
        c.drawOval(cx - dp(14), cy + cellH * 0.30f, cx + dp(14), cy + cellH * 0.40f, pFill);
        // 腿
        pLine.setStrokeWidth(dp(5));
        pLine.setColor(0xFF4E5D3A);
        c.drawLine(cx - dp(4), cy + dp(8), cx - dp(7), cy + cellH * 0.32f + sway, pLine);
        c.drawLine(cx + dp(4), cy + dp(8), cx + dp(7), cy + cellH * 0.32f - sway, pLine);
        // 身体
        box(c, cx - dp(10), cy - dp(14), cx + dp(10), cy + dp(10), 0xFF6B7F52, dp(6));
        // 手臂前伸
        pLine.setStrokeWidth(dp(4.5f));
        pLine.setColor(0xFF7D9163);
        c.drawLine(cx - dp(8), cy - dp(8), cx - dp(20), cy - dp(2) + sway, pLine);
        c.drawLine(cx + dp(8), cy - dp(8), cx + dp(20), cy - dp(4) - sway, pLine);
        // 头
        pFill.setColor(0xFF9BB37A);
        c.drawCircle(cx, cy - dp(24), dp(10), pFill);
        pFill.setColor(0xFF2B3320);
        c.drawCircle(cx - dp(3.6f), cy - dp(26), dp(1.9f), pFill);
        c.drawCircle(cx + dp(3.6f), cy - dp(26), dp(1.9f), pFill);
        // 嘴
        pLine.setColor(0xFF2B3320);
        pLine.setStrokeWidth(dp(1.4f));
        c.drawLine(cx - dp(4), cy - dp(19), cx + dp(4), cy - dp(19), pLine);
        // 血条
        float hw = dp(16);
        box(c, cx - hw, cy - dp(40), cx + hw, cy - dp(37), 0x66000000, dp(1.5f));
        float t = Math.max(0, Math.min(1, z.hp / (100f + (wave - 1) * 22)));
        box(c, cx - hw, cy - dp(40), cx - hw + hw * 2 * t, cy - dp(37), 0xFFE53935, dp(1.5f));
    }

    void drawCardBar(Canvas c) {
        // 阳光计数
        box(c, dp(54), cardY, dp(142), cardY + cardH, 0xFF23262D, dp(10));
        pFill.setColor(0xFFFFD54F);
        c.drawCircle(dp(68), cardY + cardH / 2f, dp(9), pFill);
        label(c, "" + sunPoint, dp(82), cardY + cardH / 2f + dp(5), 15, 0xFFFFE082);
        label(c, "阳光", dp(82), cardY + cardH / 2f + dp(18), 8, 0xFF9AA0AB);

        float cw = dp(52), ch = cardH;
        float x = dp(148);
        for (int i = 0; i < 3; i++) {
            boolean on = selected == i;
            boolean afford = sunPoint >= COST[i];
            box(c, x, cardY, x + cw, cardY + ch,
                    on ? 0x33FB7299 : 0xFF23262D, dp(9));
            if (on) frame(c, x, cardY, x + cw, cardY + ch, 0xFFFB7299, dp(9), dp(1.6f));

            float mx = x + cw / 2f, my = cardY + ch * 0.40f;
            if (i == 0) {
                pFill.setColor(0xFFFFD54F);
                c.drawCircle(mx, my, dp(8), pFill);
            } else if (i == 1) {
                pFill.setColor(0xFF66BB6A);
                c.drawCircle(mx, my, dp(8), pFill);
                pFill.setColor(0xFF1B5E20);
                c.drawCircle(mx - dp(2.5f), my - dp(1), dp(1.4f), pFill);
            } else {
                pFill.setColor(0xFF8D6E63);
                c.drawCircle(mx, my, dp(8), pFill);
            }
            label(c, "" + COST[i], mx, cardY + ch - dp(6), 10,
                    afford ? 0xFFFFE082 : 0xFFE57373, Paint.Align.CENTER);
            x += cw + dp(5);
        }

        // 波次放在阳光框下方，避开右上角的分数 HUD
        label(c, "第 " + wave + " 波 · 僵尸 " + zombies.size(), dp(54), cardY + cardH + dp(18),
                10, 0xFF8A909C, Paint.Align.LEFT);
    }

    Plant plantAt(int r, int cc) {
        for (Plant p : plants) if (p.row == r && p.col == cc) return p;
        return null;
    }

    @Override protected boolean touch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY();

        // 先看阳光
        for (int i = suns.size() - 1; i >= 0; i--) {
            Sun sn = suns.get(i);
            if (Math.hypot(x - sn.x, y - sn.y) < dp(26)) {
                suns.remove(i);
                sunPoint += 25;
                return true;
            }
        }

        // 卡片
        float cw = dp(52);
        float cx0 = dp(148);
        if (y >= cardY && y <= cardY + cardH) {
            for (int i = 0; i < 3; i++) {
                float l = cx0 + i * (cw + dp(5));
                if (x >= l && x <= l + cw) {
                    selected = (selected == i) ? -1 : i;
                    return true;
                }
            }
            return true;
        }

        // 草坪
        if (y < gridT || x < gridL || x > gridL + COLS * cellW) return true;
        int cc = (int) ((x - gridL) / cellW);
        int r = (int) ((y - gridT) / cellH);
        if (cc < 0 || cc >= COLS || r < 0 || r >= ROWS) return true;

        if (selected < 0) return true;
        if (plantAt(r, cc) != null) return true;
        if (sunPoint < COST[selected]) {
            selected = -1;
            return true;
        }
        sunPoint -= COST[selected];
        Plant p = new Plant();
        p.row = r;
        p.col = cc;
        p.type = selected;
        p.shootT = 0;
        plants.add(p);
        selected = -1;
        return true;
    }
}
