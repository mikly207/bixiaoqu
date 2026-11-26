package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Random;

/**
 * 彩蛋：跳一跳小游戏（精美版 v4.4 · 三档难度 · 整体调容易）。
 * 玩法：按住蓄力，松手起跳；落到下一块得分，踩中心连击翻倍，落空结束。
 * 难度：简单 / 中等 / 困难 —— 分别调整方块间距、平台宽度、边缘容错、蓄力速度与连击判定。
 * 视觉：梦幻天空渐变 + 星星云朵 + 渐变立体方块 + 闭眼小电视（哔小趣精华）
 *       蓄力下蹲 + 跳跃翻滚 + 落地粒子迸溅 + 踩中飘分 + 连击提示。
 * 记分：左上角圆角记分卡（大分数 + 本档最高 + 连击），全程可见。
 *       右上角难度标签，点击随时切换难度。
 */
public class JumpActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        JumpView game = new JumpView(this);
        root.addView(game, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    // ================================================================ 难度配置
    /** 三档难度参数 */
    static class DiffCfg {
        String name;        // 简单 / 中等 / 困难
        String desc;        // 说明文案
        float gapMin, gapMax;   // 方块间距（单位）
        float wMin, wMax;       // 方块宽度（单位）
        float firstW;           // 起始平台宽度
        float tol;              // 边缘容错系数（>1 更宽容，<1 边缘算失误）
        float distMin, distMax; // 蓄力满量程对应的跳跃距离
        int chargeMs;           // 蓄力满量程所需毫秒（越短越难控时）
        float centerRatio;      // 中心连击判定阈值
        int accent;             // 主题色
        int stars;              // 难度星级
    }

    static final int DIFF_EASY = 0, DIFF_NORMAL = 1, DIFF_HARD = 2;

    static DiffCfg[] cfgs() {
        // 整体调容易（v4.4）：平台更宽、间距更小、边缘容错更高、蓄力更慢更好控。
        // 约束：distMax 必须 >= 最远中心距 - 半块容差，否则会出现满蓄力也跳不到的死局。
        DiffCfg e = new DiffCfg();
        e.name = "简单";
        e.desc = "超宽平台 · 近距离 · 轻松上手";
        e.gapMin = 0.8f; e.gapMax = 1.5f;
        e.wMin = 1.70f; e.wMax = 2.60f;
        e.firstW = 2.60f;
        e.tol = 1.45f;
        e.distMin = 0.5f; e.distMax = 3.3f;
        e.chargeMs = 1250;
        e.centerRatio = 0.42f;
        e.accent = 0xFF5FD3A6;
        e.stars = 1;

        DiffCfg n = new DiffCfg();
        n.name = "中等";
        n.desc = "宽平台 · 适中间距 · 好控制";
        n.gapMin = 1.2f; n.gapMax = 2.1f;
        n.wMin = 1.35f; n.wMax = 2.15f;
        n.firstW = 2.10f;
        n.tol = 1.20f;
        n.distMin = 0.5f; n.distMax = 3.8f;
        n.chargeMs = 1000;
        n.centerRatio = 0.32f;
        n.accent = 0xFFFB7299;
        n.stars = 2;

        DiffCfg h = new DiffCfg();
        h.name = "困难";
        h.desc = "略窄平台 · 较大间距 · 略有挑战";
        h.gapMin = 1.7f; h.gapMax = 2.9f;
        h.wMin = 1.00f; h.wMax = 1.70f;
        h.firstW = 1.60f;
        h.tol = 1.00f;
        h.distMin = 0.5f; h.distMax = 4.4f;
        h.chargeMs = 820;
        h.centerRatio = 0.24f;
        h.accent = 0xFFFF6B6B;
        h.stars = 3;

        return new DiffCfg[]{e, n, h};
    }

    // ================================================================ 游戏视图
    static class JumpView extends View {

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                step();
                invalidate();
                h.postDelayed(this, 16);
            }
        };
        final Random rnd = new Random();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** 方块 */
        static class Block {
            float x;
            float w;
            int kind;
            boolean dark;
            boolean bonus;
            float squash = 0f;
            Block(float x, float w, int kind, boolean dark, boolean bonus) {
                this.x = x; this.w = w; this.kind = kind; this.dark = dark; this.bonus = bonus;
            }
        }

        /** 飘字 */
        static class FloatText {
            float x, y, vy;
            String text;
            int color;
            long born;
            FloatText(float x, float y, String text, int color) {
                this.x = x; this.y = y; this.text = text; this.color = color;
                this.vy = 1.1f; this.born = System.currentTimeMillis();
            }
        }

        /** 落地粒子 */
        static class Particle {
            float x, y, vx, vy;
            int color;
            long born;
            float r;
            Particle(float x, float y, float vx, float vy, int color, float r) {
                this.x = x; this.y = y; this.vx = vx; this.vy = vy;
                this.color = color; this.r = r; this.born = System.currentTimeMillis();
            }
        }

        /** 云朵装饰 */
        static class Cloud {
            float x, y, scale, alpha;
            Cloud(float x, float y, float scale, float alpha) {
                this.x = x; this.y = y; this.scale = scale; this.alpha = alpha;
            }
        }

        final ArrayList<Block> blocks = new ArrayList<Block>();
        final ArrayList<FloatText> floats = new ArrayList<FloatText>();
        final ArrayList<Particle> particles = new ArrayList<Particle>();
        final ArrayList<Cloud> clouds = new ArrayList<Cloud>();
        int cur = 0;
        int score = 0, best = 0, combo = 0;
        float px;
        boolean charging = false;
        long chargeStart;
        boolean jumping = false;
        float jumpFrom, jumpTo, jumpT;
        long jumpStart;
        static final long JUMP_MS = 420;
        boolean over = false;
        int W = 0, H = 0;
        float unit;
        float baseY;
        float playerSquash = 0f;   // 落地压扁 0..1
        long lastLandMs = 0;

        // ---- 难度 ----
        DiffCfg[] cfg = cfgs();
        int diff = 1;              // 当前难度索引
        boolean menuOpen = true;   // 难度选择面板是否展开
        int menuTouchIndex = -1;   // 面板按压高亮

        // 方块主题色：顶面 / 侧面
        final int[] topA = {0xFFF2F3F5, 0xFF8FD3FF, 0xFFF6C66B, 0xFF9BE7A4, 0xFFF29B9B};
        final int[] sideA = {0xFFD6DAE0, 0xFF57A9DE, 0xFFDCA946, 0xFF6CCB7C, 0xFFD97575};
        final int[] particleColors = {0xFFFB7299, 0xFFFFD76B, 0xFF8FD3FF, 0xFFFFFFFF};

        // 哔小趣粉色系
        static final int PINK = 0xFFFB7299;
        static final int GOLD = 0xFFFFD76B;

        int dp(float v) {
            return (int) (getResources().getDisplayMetrics().density * v);
        }

        JumpView(Context c) {
            super(c);
            diff = Prefs.jumpDifficulty(c);
            if (diff < 0 || diff > 2) diff = 1;
            best = Prefs.jumpBestAt(c, diff);
            reset();
            setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    handleTouch(e);
                    return true;
                }
            });
            h.post(tick);
        }

        DiffCfg cf() { return cfg[diff]; }

        void reset() {
            blocks.clear();
            floats.clear();
            particles.clear();
            clouds.clear();
            // 云朵装饰：几朵不同大小/透明度
            clouds.add(new Cloud(0.22f, 0.14f, 1.0f, 0.10f));
            clouds.add(new Cloud(0.72f, 0.22f, 1.3f, 0.08f));
            clouds.add(new Cloud(0.45f, 0.30f, 0.8f, 0.06f));
            clouds.add(new Cloud(0.88f, 0.10f, 0.9f, 0.09f));
            blocks.add(new Block(0, cf().firstW, 0, false, false));
            for (int i = 1; i < 12; i++) spawn();
            cur = 0;
            px = blocks.get(0).x;
            score = 0; combo = 0;
            playerSquash = 0f;
            charging = jumping = over = false;
        }

        void spawn() {
            DiffCfg c = cf();
            Block last = blocks.get(blocks.size() - 1);
            float gap = c.gapMin + rnd.nextFloat() * (c.gapMax - c.gapMin);
            float w = c.wMin + rnd.nextFloat() * (c.wMax - c.wMin);
            boolean bonus = rnd.nextFloat() < 0.28f;
            blocks.add(new Block(last.x + last.w / 2 + gap + w / 2, w,
                    rnd.nextInt(5), !last.dark, bonus));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            W = w; H = h;
            unit = Math.min(w / 5.2f, 118f);
            baseY = h * 0.66f;
        }

        // ---- 输入 ----
        void handleTouch(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN
                    && e.getAction() != MotionEvent.ACTION_UP
                    && e.getAction() != MotionEvent.ACTION_CANCEL) return;

            // 难度选择面板：点击卡片即切换难度并开始
            if (menuOpen) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    for (int i = 0; i < 3; i++) {
                        if (menuCardRect(i).contains(e.getX(), e.getY())) {
                            menuTouchIndex = i;
                            invalidate();
                            return;
                        }
                    }
                    menuTouchIndex = -1;
                } else if (e.getAction() == MotionEvent.ACTION_UP) {
                    int hit = -1;
                    for (int i = 0; i < 3; i++) {
                        if (menuCardRect(i).contains(e.getX(), e.getY())) { hit = i; break; }
                    }
                    menuTouchIndex = -1;
                    if (hit >= 0) {
                        setDiff(hit);
                        menuOpen = false;
                        invalidate();
                    }
                }
                return;
            }

            if (over) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    if (overBtnDiff().contains(e.getX(), e.getY())) {
                        menuOpen = true;
                        invalidate();
                        return;
                    }
                    if (overBtnRestart().contains(e.getX(), e.getY())) {
                        reset();
                        invalidate();
                        return;
                    }
                    // 其他位置点击 = 再来一局
                    reset();
                }
                return;
            }

            // 右上角难度标签 → 打开难度选择
            if (e.getAction() == MotionEvent.ACTION_DOWN && diffTagRect().contains(e.getX(), e.getY())) {
                menuOpen = true;
                invalidate();
                return;
            }

            if (jumping) return;
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                charging = true;
                chargeStart = System.currentTimeMillis();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (charging) {
                    charging = false;
                    long held = System.currentTimeMillis() - chargeStart;
                    float f = chargeFactor(held);
                    float dist = cf().distMin + f * (cf().distMax - cf().distMin);
                    jumpFrom = px;
                    jumpTo = px + dist;
                    jumpT = 0f;
                    jumpStart = System.currentTimeMillis();
                    jumping = true;
                }
            }
        }

        /** 蓄力量 0..1 */
        float chargeFactor(long held) {
            float f = (held - 100f) / (float) cf().chargeMs;
            return Math.max(0f, Math.min(1f, f));
        }

        void setDiff(int d) {
            diff = d;
            Prefs.setJumpDifficulty(getContext(), d);
            best = Prefs.jumpBestAt(getContext(), d);
            reset();
        }

        // ---- 逻辑 ----
        void step() {
            if (jumping) {
                long elapsed = System.currentTimeMillis() - jumpStart;
                jumpT = Math.min(1f, (float) elapsed / (float) JUMP_MS);
                if (jumpT >= 1f) { jumpT = 1f; land(); }
            }
            for (Block b : blocks) if (b.squash > 0f) b.squash = Math.max(0f, b.squash - 0.045f);
            if (playerSquash > 0f) playerSquash = Math.max(0f, playerSquash - 0.06f);
            // 飘字上浮
            for (int i = floats.size() - 1; i >= 0; i--) {
                FloatText ft = floats.get(i);
                ft.y -= ft.vy * unit;
                if (System.currentTimeMillis() - ft.born > 900) floats.remove(i);
            }
            // 粒子运动（重力）
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle pt = particles.get(i);
                pt.x += pt.vx;
                pt.y += pt.vy;
                pt.vy += 0.35f;
                if (System.currentTimeMillis() - pt.born > 600) particles.remove(i);
            }
        }

        void land() {
            jumping = false;
            px = jumpTo;
            Block target = blocks.get(cur + 1);
            float half = target.w / 2f * cf().tol;
            float d = Math.abs(px - target.x);
            if (d <= half) {
                boolean center = d <= Math.min(cf().centerRatio, half * 0.35f);
                cur++;
                target.squash = 1f;
                playerSquash = 1f;
                lastLandMs = System.currentTimeMillis();
                int gain = center ? 2 : 1;
                if (center) combo++; else combo = 0;
                if (center && target.bonus) gain += 2;
                score += gain;
                // 落地粒子迸溅
                float sx = (target.x - camX()) * unit;
                burst(sx, baseY - unit * 0.2f, center ? 12 : 7);
                // 飘分
                floats.add(new FloatText(sx, baseY - unit * 0.6f,
                        "+" + gain, center ? PINK : 0xFFFFFFFF));
                if (combo >= 2) {
                    floats.add(new FloatText(sx, baseY - unit * 1.15f,
                            "连击 x" + combo, GOLD));
                }
                if (score > best) {
                    best = score;
                    Prefs.setJumpBestAt(getContext(), diff, score);
                    Prefs.setJumpBest(getContext(), score);
                }
                while (cur > 4) { blocks.remove(0); cur--; }
                while (blocks.size() < cur + 8) spawn();
            } else {
                over = true;
                combo = 0;
            }
        }

        void burst(float x, float y, int n) {
            for (int i = 0; i < n; i++) {
                float ang = (float) (Math.PI * 0.15f + rnd.nextFloat() * Math.PI * 0.7f);
                float sp = unit * 0.04f + rnd.nextFloat() * unit * 0.05f;
                particles.add(new Particle(x, y,
                        (float) Math.cos(ang) * sp * (rnd.nextBoolean() ? 1 : -1),
                        (float) -Math.sin(ang) * sp,
                        particleColors[rnd.nextInt(particleColors.length)],
                        dp(2) + rnd.nextFloat() * dp(2.5f)));
            }
        }

        float camX() {
            float anchor = blocks.get(Math.min(cur, blocks.size() - 1)).x;
            return anchor - (W * 0.28f) / unit;
        }

        // ---- 布局辅助 ----
        RectF diffTagRect() {
            float w = dp(62), hh = dp(26);
            return new RectF(W - w - dp(14), dp(14), W - dp(14), dp(14) + hh);
        }

        RectF menuCardRect(int i) {
            float cw = Math.min(W - dp(44), dp(330));
            float ch = dp(76);
            float gap = dp(11);
            float top = H * 0.285f;
            float l = (W - cw) / 2f;
            float t = top + i * (ch + gap);
            return new RectF(l, t, l + cw, t + ch);
        }

        RectF overPanelRect() {
            float cw = Math.min(W * 0.82f, dp(320));
            float ch = dp(268);
            float l = (W - cw) / 2f, t = H * 0.5f - ch / 2f;
            return new RectF(l, t, l + cw, t + ch);
        }

        RectF overBtnRestart() {
            RectF r = overPanelRect();
            float bw = (r.width() - dp(40) - dp(10)) / 2f;
            float bh = dp(38);
            float by = r.bottom - dp(58);
            return new RectF(r.left + dp(20), by, r.left + dp(20) + bw, by + bh);
        }

        RectF overBtnDiff() {
            RectF r = overPanelRect();
            float bw = (r.width() - dp(40) - dp(10)) / 2f;
            float bh = dp(38);
            float by = r.bottom - dp(58);
            return new RectF(r.right - dp(20) - bw, by, r.right - dp(20), by + bh);
        }

        // ---- 绘制 ----
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (W == 0) return;

            // ① 梦幻天空渐变（深空蓝 → 蓝紫 → 底部微粉）
            LinearGradient bg = new LinearGradient(0, 0, 0, H,
                    new int[]{0xFF1A2340, 0xFF2A2F55, 0xFF4A3A5E},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            p.setShader(bg);
            c.drawRect(0, 0, W, H, p);
            p.setShader(null);

            // ② 星星（上半部，固定伪随机）
            drawStars(c);

            // ③ 云朵
            drawClouds(c);

            float camX = camX();

            // ④ 地面
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0E1420);
            c.drawRect(0, baseY + unit * 0.55f, W, H, p);

            // ⑤ 方块（远到近）
            for (int i = Math.max(0, cur - 4); i < blocks.size(); i++) {
                drawBlock(c, blocks.get(i), camX, i == cur);
            }

            // ⑥ 落地粒子（在玩家之下）
            drawParticles(c);

            // ⑦ 玩家：闭眼小电视
            drawPlayer(c, camX);

            // ⑧ 飘字
            for (FloatText ft : floats) {
                long age = System.currentTimeMillis() - ft.born;
                int a = (int) (255 * Math.max(0f, 1f - age / 900f));
                p.setColor(ft.color); p.setAlpha(a);
                p.setTextSize(unit * 0.3f); p.setFakeBoldText(true);
                p.setTextAlign(Paint.Align.CENTER);
                // 描边提升可读性
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3));
                p.setColor(0x55000000);
                c.drawText(ft.text, ft.x, ft.y, p);
                p.setStyle(Paint.Style.FILL); p.setColor(ft.color); p.setAlpha(a);
                c.drawText(ft.text, ft.x, ft.y, p);
                p.setAlpha(255); p.setFakeBoldText(false);
            }

            // ⑨ 蓄力指示（角色脚下进度环）
            if (charging) drawCharge(c);

            // ⑩ 左上角记分卡
            drawScoreCard(c);

            // ⑪ 右上角难度标签
            drawDiffTag(c);

            // ⑫ 结束面板
            if (over) drawOver(c);

            // ⑬ 难度选择面板（最上层）
            if (menuOpen) drawMenu(c);
        }

        void drawStars(Canvas c) {
            p.setColor(0x66FFFFFF);
            for (int i = 0; i < 40; i++) {
                float sx = ((i * 137) % W);
                float sy = ((i * 97) % (int) (baseY * 0.7f));
                float r = dp(0.8f + (i % 3) * 0.6f);
                c.drawCircle(sx, sy, r, p);
            }
        }

        void drawClouds(Canvas c) {
            for (Cloud cl : clouds) {
                float x = cl.x * W, y = cl.y * H;
                float s = unit * cl.scale * 0.5f;
                p.setColor(0xFFFFFF);
                p.setAlpha((int) (cl.alpha * 255));
                // 一朵云 = 三个椭圆组合
                c.drawOval(new RectF(x - s, y - s * 0.5f, x + s, y + s * 0.3f), p);
                c.drawOval(new RectF(x - s * 0.6f, y - s * 0.8f, x + s * 0.2f, y + s * 0.2f), p);
                c.drawOval(new RectF(x + s * 0.1f, y - s * 0.7f, x + s * 0.7f, y + s * 0.25f), p);
            }
            p.setAlpha(255);
        }

        void drawParticles(Canvas c) {
            for (Particle pt : particles) {
                long age = System.currentTimeMillis() - pt.born;
                int a = (int) (255 * Math.max(0f, 1f - age / 600f));
                p.setColor(pt.color); p.setAlpha(a);
                c.drawCircle(pt.x, pt.y, pt.r, p);
            }
            p.setAlpha(255);
        }

        void drawBlock(Canvas c, Block b, float camX, boolean isCur) {
            float left = (b.x - camX) * unit - b.w * unit / 2f;
            float right = left + b.w * unit;
            float top = baseY - b.squash * unit * 0.14f;
            float depth = unit * 0.46f;
            int tone = b.kind % topA.length;

            // 地面投影
            p.setColor(0x33000000);
            c.drawOval(new RectF(left + unit * 0.1f, baseY + unit * 0.42f,
                    right + unit * 0.1f, baseY + unit * 0.62f), p);

            // 侧面（立体感，渐变）
            LinearGradient sideGrad = new LinearGradient(left, top, left, top + depth,
                    b.dark ? 0xFF3A4250 : sideA[tone],
                    b.dark ? 0xFF2A3140 : (sideA[tone] & 0x00FFFFFF) | 0xBB000000,
                    Shader.TileMode.CLAMP);
            p.setShader(sideGrad);
            c.drawRect(left, top, right, top + depth, p);
            p.setShader(null);
            p.setColor(0x22000000);
            c.drawRect(right - unit * 0.12f, top, right, top + depth, p);

            // 顶面（渐变，带高光）
            int topColor = b.dark ? 0xFF4A5668 : topA[tone];
            LinearGradient topGrad = new LinearGradient(left, top - depth * 0.5f, right, top + depth * 0.25f,
                    topColor, (topColor & 0x00FFFFFF) | 0xDD000000, Shader.TileMode.CLAMP);
            p.setShader(topGrad);
            c.drawRoundRect(new RectF(left, top - depth * 0.5f, right, top + depth * 0.25f),
                    unit * 0.06f, unit * 0.06f, p);
            p.setShader(null);

            // 建筑图案
            drawBuilding(c, b, left, right, top - depth * 0.12f, b.dark);

            // 中心加分点（呼吸发光）
            if (b.bonus) {
                float cx = (left + right) / 2f;
                float pulse = 1f + 0.15f * (float) Math.sin(System.currentTimeMillis() / 220.0);
                p.setColor(0x44FFD76B);
                c.drawCircle(cx, top - depth * 0.05f, unit * 0.13f * pulse, p);
                p.setColor(GOLD);
                c.drawCircle(cx, top - depth * 0.05f, unit * 0.075f * pulse, p);
            }
        }

        /** 画方块上的简化建筑图案 */
        void drawBuilding(Canvas c, Block b, float left, float right, float y, boolean dark) {
            float cx = (left + right) / 2f;
            float s = Math.min(right - left, unit * 1.2f) * 0.34f;
            p.setColor(dark ? 0xFFD8DEE8 : 0xFF59647A);
            switch (b.kind % 5) {
                case 0:
                    c.drawRect(cx - s * 0.55f, y - s * 0.9f, cx + s * 0.55f, y, p);
                    p.setColor(dark ? PINK : 0xFFE5648D);
                    Path roof = new Path();
                    roof.moveTo(cx - s * 0.75f, y - s * 0.85f);
                    roof.lineTo(cx, y - s * 1.55f);
                    roof.lineTo(cx + s * 0.75f, y - s * 0.85f);
                    roof.close();
                    c.drawPath(roof, p);
                    break;
                case 1:
                    p.setColor(dark ? 0xFFE5648D : 0xFFC24E72);
                    c.drawRoundRect(new RectF(cx - s * 0.38f, y - s * 1.3f,
                            cx + s * 0.38f, y), s * 0.38f, s * 0.38f, p);
                    break;
                case 2:
                    p.setColor(dark ? 0xFF5B6678 : 0xFF2E3644);
                    c.drawCircle(cx, y - s * 0.6f, s * 0.62f, p);
                    p.setColor(dark ? PINK : GOLD);
                    c.drawCircle(cx, y - s * 0.6f, s * 0.2f, p);
                    break;
                case 3:
                    p.setColor(dark ? 0xFF9AA7B8 : 0xFF8B96A8);
                    c.drawRect(cx - s * 0.12f, y - s * 0.7f, cx + s * 0.12f, y, p);
                    p.setColor(dark ? 0xFF6FCF97 : 0xFF57B57A);
                    c.drawCircle(cx, y - s * 0.95f, s * 0.5f, p);
                    break;
                default:
                    p.setColor(dark ? 0xFF8FC7F0 : 0xFF6FA9D6);
                    c.drawOval(new RectF(cx - s * 0.7f, y - s * 0.25f,
                            cx + s * 0.7f, y + s * 0.1f), p);
                    c.drawRect(cx - s * 0.08f, y - s * 1.05f, cx + s * 0.08f, y - s * 0.2f, p);
                    c.drawCircle(cx, y - s * 1.15f, s * 0.16f, p);
                    break;
            }
        }

        /** 闭眼小电视角色（哔小趣精华）：浅蓝白渐变方块 + 天线 + 闭眼笑脸 + 小脚 */
        void drawPlayer(Canvas c, float camX) {
            float x = (px - camX) * unit;
            float groundY = baseY - unit * 0.2f;
            float squash = 0f;
            if (charging) {
                float f = chargeFactor(System.currentTimeMillis() - chargeStart);
                squash = f * 0.45f;
            }
            float jumpY = 0f;
            float rotate = 0f;
            if (jumping) {
                float t = jumpT;
                float ease = 0.5f - 0.5f * (float) Math.cos(t * Math.PI);
                px = jumpFrom + (jumpTo - jumpFrom) * ease;
                jumpY = -(float) Math.sin(t * Math.PI) * unit * 1.75f;
                x = (px - camX) * unit;
                // 跳跃时前滚翻 360°，更生动
                rotate = t * 360f;
            }
            // 落地压扁 + 蓄力下蹲 叠加
            float sq = Math.max(squash, playerSquash * 0.5f);
            float bodyW = unit * 0.62f * (1f + sq * 0.5f);
            float bodyH = unit * 0.62f * (1f - sq);
            float cy = groundY + jumpY;
            float bodyTop = cy - bodyH;

            // 影子（跳跃时变小变淡）
            float sh = jumping ? 0.55f : 1f;
            p.setColor(0x44000000);
            c.drawOval(new RectF(x - bodyW * 0.8f * sh, groundY - unit * 0.13f,
                    x + bodyW * 0.8f * sh, groundY + unit * 0.06f), p);

            // 翻滚：围绕中心旋转绘制
            c.save();
            if (rotate != 0f) c.rotate(rotate, x, cy - bodyH / 2f);

            float left = x - bodyW / 2f;
            float rCorner = bodyW * 0.22f;

            // 身体：浅蓝白渐变（呼应应用 Logo）
            LinearGradient bodyGrad = new LinearGradient(left, bodyTop, left + bodyW, cy,
                    0xFFEAF4FF, 0xFFB8D8F0, Shader.TileMode.CLAMP);
            p.setShader(bodyGrad);
            c.drawRoundRect(new RectF(left, bodyTop, left + bodyW, cy), rCorner, rCorner, p);
            p.setShader(null);
            // 描边
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f)); p.setColor(0xAA7AA8C8);
            c.drawRoundRect(new RectF(left, bodyTop, left + bodyW, cy), rCorner, rCorner, p);
            p.setStyle(Paint.Style.FILL);

            // 天线（两条斜线）
            p.setColor(0xFF4A6E8C);
            p.setStrokeWidth(dp(2)); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(x - bodyW * 0.12f, bodyTop + dp(2), x - bodyW * 0.28f, bodyTop - dp(9), p);
            c.drawLine(x + bodyW * 0.12f, bodyTop + dp(2), x + bodyW * 0.28f, bodyTop - dp(9), p);

            // 闭眼笑脸（^ ^）：两条向上的弧线
            p.setColor(0xFF3A4E60); p.setStrokeWidth(dp(2.5f));
            float eyeY = bodyTop + bodyH * 0.42f;
            float eyeDx = bodyW * 0.20f;
            c.drawArc(new RectF(x - eyeDx - dp(6), eyeY - dp(4), x - eyeDx + dp(6), eyeY + dp(6)),
                    180f, 180f, false, p);
            c.drawArc(new RectF(x + eyeDx - dp(6), eyeY - dp(4), x + eyeDx + dp(6), eyeY + dp(6)),
                    180f, 180f, false, p);
            // 嘴（微笑）
            p.setStrokeWidth(dp(2));
            c.drawArc(new RectF(x - dp(7), bodyTop + bodyH * 0.55f, x + dp(7), bodyTop + bodyH * 0.75f),
                    0f, 180f, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);

            // 小脚（蓄力/站立时露出）
            if (!jumping) {
                p.setColor(0xFF7AA8C8);
                c.drawRoundRect(new RectF(left + bodyW * 0.15f, cy - dp(2),
                        left + bodyW * 0.30f, cy + dp(5)), dp(2), dp(2), p);
                c.drawRoundRect(new RectF(left + bodyW * 0.70f, cy - dp(2),
                        left + bodyW * 0.85f, cy + dp(5)), dp(2), dp(2), p);
            }
            c.restore();
        }

        /** 蓄力：角色脚下的蓄力环 + 力度百分比 */
        void drawCharge(Canvas c) {
            float f = chargeFactor(System.currentTimeMillis() - chargeStart);
            float x = (px - camX()) * unit;
            float cy = baseY + unit * 0.35f, r = dp(20);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5)); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x33FFFFFF);
            c.drawCircle(x, cy, r, p);
            p.setColor(f > 0.85f ? 0xFFFF6B6B : PINK);
            c.drawArc(new RectF(x - r, cy - r, x + r, cy + r), -90f, 360f * f, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(11));
            c.drawText((int) (f * 100) + "%", x, cy + dp(4), p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        /** 左上角记分卡片：半透明圆角底 + 大分数 + 本档最高 + 连击标签 */
        void drawScoreCard(Canvas c) {
            float pad = dp(14);
            float cardW = dp(128), cardH = dp(76);
            float l = pad, t = pad;
            // 半透明玻璃卡片
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(l, t, l + cardW, t + cardH), dp(16), dp(16), p);
            p.setColor(0x14FFFFFF);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1));
            c.drawRoundRect(new RectF(l, t, l + cardW, t + cardH), dp(16), dp(16), p);
            p.setStyle(Paint.Style.FILL);

            // 小标题
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xCCFFFFFF); p.setTextSize(dp(11));
            c.drawText("得分", l + dp(14), t + dp(20), p);
            // 大分数（粉色加粗）
            p.setColor(PINK); p.setTextSize(dp(32)); p.setFakeBoldText(true);
            c.drawText(String.valueOf(score), l + dp(14), t + dp(52), p);
            p.setFakeBoldText(false);
            // 本档最高分
            p.setColor(0x99FFFFFF); p.setTextSize(dp(11));
            c.drawText(cf().name + "最高 " + Math.max(best, score), l + dp(14), t + dp(68), p);

            // 连击标签（卡片右侧突出）
            if (combo >= 2) {
                float bx = l + cardW - dp(6), by = t + dp(14);
                p.setColor(PINK);
                c.drawRoundRect(new RectF(bx, by, bx + dp(64), by + dp(24)), dp(12), dp(12), p);
                p.setColor(Color.WHITE); p.setTextSize(dp(12)); p.setFakeBoldText(true);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("x" + combo, bx + dp(32), by + dp(16), p);
                p.setFakeBoldText(false);
            }
        }

        /** 右上角难度标签：点击切换难度 */
        void drawDiffTag(Canvas c) {
            RectF r = diffTagRect();
            DiffCfg d = cf();
            p.setColor(0x40000000);
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1));
            p.setColor(d.accent);
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p);
            p.setStyle(Paint.Style.FILL);
            // 难度色点
            p.setColor(d.accent);
            c.drawCircle(r.left + dp(13), r.centerY(), dp(3.5f), p);
            // 文字
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Color.WHITE); p.setTextSize(dp(12)); p.setFakeBoldText(true);
            c.drawText(d.name, r.left + dp(22), r.centerY() + dp(4.5f), p);
            p.setFakeBoldText(false);
            // 切换箭头
            p.setColor(0x99FFFFFF); p.setTextSize(dp(11));
            c.drawText("切换", r.right - dp(26), r.centerY() + dp(4f), p);
        }

        void drawOver(Canvas c) {
            p.setColor(0xBB0E1520);
            c.drawRect(0, 0, W, H, p);
            RectF r = overPanelRect();
            // 玻璃卡片
            p.setColor(0xF02A3444);
            c.drawRoundRect(r, dp(20), dp(20), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(0x33FFFFFF);
            c.drawRoundRect(r, dp(20), dp(20), p);
            p.setStyle(Paint.Style.FILL);

            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(PINK); p.setTextSize(dp(20)); p.setFakeBoldText(true);
            c.drawText("哔小趣 · 跳一跳", W / 2f, r.top + dp(38), p);
            // 难度标签
            p.setTextSize(dp(12)); p.setFakeBoldText(false);
            p.setColor(cf().accent);
            c.drawText("难度 · " + cf().name + "  ★".replace("★", starsStr(cf().stars)),
                    W / 2f, r.top + dp(58), p);
            // 大分数
            p.setColor(Color.WHITE); p.setTextSize(dp(54)); p.setFakeBoldText(true);
            c.drawText(String.valueOf(score), W / 2f, r.top + dp(118), p);
            p.setTextSize(dp(13)); p.setFakeBoldText(false); p.setColor(0xFFB8C2D0);
            c.drawText("本局得分 · 本档最高 " + best, W / 2f, r.top + dp(144), p);
            // 新纪录徽章
            if (score > 0 && score >= best) {
                p.setColor(GOLD); p.setTextSize(dp(14)); p.setFakeBoldText(true);
                c.drawText("新纪录！", W / 2f, r.top + dp(168), p);
                p.setFakeBoldText(false);
            }

            // 两个按钮：再来一局 / 换难度
            RectF b1 = overBtnRestart(), b2 = overBtnDiff();
            p.setColor(PINK);
            c.drawRoundRect(b1, dp(19), dp(19), p);
            p.setColor(0x22FFFFFF);
            c.drawRoundRect(b2, dp(19), dp(19), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1));
            p.setColor(0x66FFFFFF);
            c.drawRoundRect(b2, dp(19), dp(19), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.WHITE); p.setTextSize(dp(15)); p.setFakeBoldText(true);
            c.drawText("再来一局", b1.centerX(), b1.centerY() + dp(5), p);
            p.setColor(0xFFDDE4EE); p.setFakeBoldText(false);
            c.drawText("换难度", b2.centerX(), b2.centerY() + dp(5), p);
        }

        String starsStr(int n) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append('★');
            return sb.toString();
        }

        // ---- 难度选择面板 ----
        void drawMenu(Canvas c) {
            // 遮罩
            p.setColor(0xE60C111C);
            c.drawRect(0, 0, W, H, p);

            // 标题
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.WHITE); p.setTextSize(dp(26)); p.setFakeBoldText(true);
            c.drawText("哔小趣 · 跳一跳", W / 2f, H * 0.175f, p);
            p.setFakeBoldText(false);
            p.setColor(0xFFB8C2D0); p.setTextSize(dp(13));
            c.drawText("选择难度开始游戏", W / 2f, H * 0.175f + dp(24), p);

            for (int i = 0; i < 3; i++) {
                DiffCfg d = cfg[i];
                RectF r = menuCardRect(i);
                boolean pressed = (menuTouchIndex == i);
                boolean cur = (i == diff);

                // 卡片底
                p.setColor(pressed ? 0xFF34405A : 0xE6283448);
                c.drawRoundRect(r, dp(18), dp(18), p);
                // 左侧难度色条
                p.setColor(d.accent);
                c.drawRoundRect(new RectF(r.left, r.top, r.left + dp(6), r.bottom), dp(3), dp(3), p);
                // 描边
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(cur ? dp(2) : dp(1));
                p.setColor(cur ? d.accent : 0x33FFFFFF);
                c.drawRoundRect(r, dp(18), dp(18), p);
                p.setStyle(Paint.Style.FILL);

                float tx = r.left + dp(20);
                // 难度名
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(Color.WHITE); p.setTextSize(dp(19)); p.setFakeBoldText(true);
                c.drawText(d.name, tx, r.top + dp(28), p);
                // 星级
                float sx = tx + p.measureText(d.name) + dp(10);
                p.setColor(d.accent); p.setTextSize(dp(12));
                c.drawText(starsStr(d.stars), sx, r.top + dp(27), p);
                p.setFakeBoldText(false);
                // 说明
                p.setColor(0xFFB8C2D0); p.setTextSize(dp(12));
                c.drawText(d.desc, tx, r.top + dp(48), p);
                // 本档最佳
                int b = Prefs.jumpBestAt(getContext(), i);
                p.setColor(0x99FFFFFF); p.setTextSize(dp(11));
                c.drawText("最佳 " + b, tx, r.top + dp(65), p);
                // 右侧当前选中标记
                if (cur) {
                    p.setColor(d.accent);
                    p.setTextSize(dp(12)); p.setFakeBoldText(true);
                    p.setTextAlign(Paint.Align.RIGHT);
                    c.drawText("当前", r.right - dp(16), r.centerY() + dp(4), p);
                    p.setFakeBoldText(false);
                }
            }

            // 底部提示
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0x88FFFFFF); p.setTextSize(dp(12));
            c.drawText("游戏中点击右上角难度标签可随时切换", W / 2f, H * 0.86f, p);
            p.setColor(0x66FFFFFF); p.setTextSize(dp(11));
            c.drawText("按住蓄力 · 松手起跳 · 踩中心连击翻倍", W / 2f, H * 0.86f + dp(20), p);
        }
    }
}
