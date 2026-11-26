package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Random;

/** 俄罗斯方块：10×20，底部四个虚拟键（左 / 旋转 / 右 / 下落） */
public class GameTetris extends GameView {

    static final int COLS = 10, ROWS = 20;

    static final int[][] BASE = {
            {0,0, 1,0, 2,0, 3,0},                 // I
            {0,0, 1,0, 0,1, 1,1},                 // O
            {0,0, 1,0, 2,0, 1,1},                 // T
            {1,0, 2,0, 0,1, 1,1},                 // S
            {0,0, 1,0, 1,1, 2,1},                 // Z
            {0,0, 0,1, 1,1, 2,1},                 // J
            {2,0, 0,1, 1,1, 2,1},                 // L
    };
    static final int[] COLORS = {
            0, 0xFF4FC3F7, 0xFFFFD54F, 0xFFBA68C8, 0xFF81C784,
            0xFFE57373, 0xFF64B5F6, 0xFFFFB74D
    };

    int[][] grid = new int[ROWS][COLS];
    int cell, ox, oy;
    int[] piece = new int[8];
    int pieceColor;
    int px, py;
    float dropAcc;
    int dropMs = 620;
    int lines;
    final Random rnd = new Random();

    // 按钮
    float[] bx = new float[4];
    float btnY, btnR, btnGap;

    // 手势
    float downX, downY, lastMoveX;
    int holdDir;

    public GameTetris(Context c) {
        super(c);
        gameId = "tetris";
        gameTitle = "俄罗斯方块";
    }

    @Override protected void reset() {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) grid[y][x] = 0;
        int availH = H - dp(58) - dp(92);
        cell = Math.min((W - dp(32)) / COLS, availH / ROWS);
        ox = (W - COLS * cell) / 2;
        oy = dp(54) + (availH - ROWS * cell) / 2;

        btnR = dp(26);
        btnGap = Math.min(dp(76), (W - 4 * btnR - dp(16)) / 3f);
        btnY = H - dp(44);
        for (int i = 0; i < 4; i++) bx[i] = W / 2f + (i - 1.5f) * btnGap;

        score = 0;
        lines = 0;
        dropAcc = 0;
        dropMs = 620;
        newPiece();
    }

    void newPiece() {
        int t = rnd.nextInt(7);
        System.arraycopy(BASE[t], 0, piece, 0, 8);
        pieceColor = t + 1;
        int minX = 9, maxX = -9, minY = 9, maxY = -9;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, piece[i * 2]);
            maxX = Math.max(maxX, piece[i * 2]);
            minY = Math.min(minY, piece[i * 2 + 1]);
            maxY = Math.max(maxY, piece[i * 2 + 1]);
        }
        px = (COLS - (maxX - minX + 1)) / 2 - minX;
        py = -minY;
        if (collide(px, py, piece)) {
            gameOver();
        }
    }

    boolean collide(int nx, int ny, int[] p) {
        for (int i = 0; i < 4; i++) {
            int x = nx + p[i * 2], y = ny + p[i * 2 + 1];
            if (x < 0 || x >= COLS || y >= ROWS) return true;
            if (y >= 0 && grid[y][x] != 0) return true;
        }
        return false;
    }

    void rotate() {
        int[] np = new int[8];
        int minX = 9, minY = 9;
        for (int i = 0; i < 4; i++) {
            int x = -piece[i * 2 + 1], y = piece[i * 2];   // 顺时针
            np[i * 2] = x;
            np[i * 2 + 1] = y;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
        }
        for (int i = 0; i < 4; i++) {
            np[i * 2] -= minX;
            np[i * 2 + 1] -= minY;
        }
        // 尝试原地 / 左右各一格（踢墙）
        int[] kicks = {0, -1, 1, -2, 2};
        for (int k : kicks) {
            if (!collide(px + k, py, np)) {
                piece = np;
                px += k;
                return;
            }
        }
    }

    void move(int d) {
        if (!collide(px + d, py, piece)) px += d;
    }

    void softDrop() {
        if (!collide(px, py + 1, piece)) {
            py++;
            dropAcc = 0;
        } else {
            lock();
        }
    }

    void lock() {
        for (int i = 0; i < 4; i++) {
            int x = px + piece[i * 2], y = py + piece[i * 2 + 1];
            if (y >= 0 && y < ROWS && x >= 0 && x < COLS) grid[y][x] = pieceColor;
        }
        clearLines();
        if (over) return;
        newPiece();
    }

    void clearLines() {
        int cleared = 0;
        for (int y = ROWS - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) if (grid[y][x] == 0) { full = false; break; }
            if (!full) continue;
            cleared++;
            for (int yy = y; yy > 0; yy--)
                for (int x = 0; x < COLS; x++) grid[yy][x] = grid[yy - 1][x];
            for (int x = 0; x < COLS; x++) grid[0][x] = 0;
            y++;   // 同一行要重新检查
        }
        if (cleared > 0) {
            lines += cleared;
            score += new int[]{0, 100, 260, 450, 700}[Math.min(4, cleared)];
            dropMs = Math.max(110, 620 - lines * 14);
        }
    }

    @Override protected void step(int dt) {
        dropAcc += dt;
        while (dropAcc >= dropMs) {
            dropAcc -= dropMs;
            if (!collide(px, py + 1, piece)) py++;
            else { lock(); break; }
            if (over) return;
        }
    }

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF14161B);

        float bl = ox - dp(5), bt = oy - dp(5);
        float br = ox + COLS * cell + dp(5), bb = oy + ROWS * cell + dp(5);
        box(c, bl, bt, br, bb, 0xFF1B1E24, dp(12));

        pLine.setStrokeWidth(dp(0.6f));
        pLine.setColor(0x10FFFFFF);
        for (int x = 1; x < COLS; x++) {
            float pxx = ox + x * cell;
            c.drawLine(pxx, oy, pxx, oy + ROWS * cell, pLine);
        }
        for (int y = 1; y < ROWS; y++) {
            float pyy = oy + y * cell;
            c.drawLine(ox, pyy, ox + COLS * cell, pyy, pLine);
        }

        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int v = grid[y][x];
                if (v == 0) continue;
                drawCell(c, x, y, COLORS[v], false);
            }
        }
        for (int i = 0; i < 4; i++) {
            int x = px + piece[i * 2], y = py + piece[i * 2 + 1];
            if (y < 0) continue;
            drawCell(c, x, y, COLORS[pieceColor], true);
        }

        // 按键
        pad(c, bx[0], btnY, btnR, "←", false);
        pad(c, bx[1], btnY, btnR, "↻", false);
        pad(c, bx[2], btnY, btnR, "→", false);
        pad(c, bx[3], btnY, btnR, "↓", false);

        label(c, "已消 " + lines + " 行", W / 2f, btnY - btnR - dp(12), 11, 0xFF7C828E,
                Paint.Align.CENTER);
    }

    void drawCell(Canvas c, int gx, int gy, int color, boolean active) {
        float l = ox + gx * cell + cell * 0.06f;
        float t = oy + gy * cell + cell * 0.06f;
        float r = ox + gx * cell + cell * 0.94f;
        float b = oy + gy * cell + cell * 0.94f;
        if (active) {
            pFill.setColor(color & 0x33FFFFFF);
            pFill.setStyle(Paint.Style.FILL);
            tmp.set(l - dp(2), t - dp(2), r + dp(2), b + dp(2));
            c.drawRoundRect(tmp, cell * 0.3f, cell * 0.3f, pFill);
        }
        box(c, l, t, r, b, color, cell * 0.22f);
        // 顶部高光
        pFill.setColor(0x33FFFFFF);
        tmp.set(l + cell * 0.1f, t + cell * 0.08f, r - cell * 0.1f, t + cell * 0.3f);
        c.drawRoundRect(tmp, cell * 0.1f, cell * 0.1f, pFill);
    }

    @Override protected boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; lastMoveX = x;
                for (int i = 0; i < 4; i++) {
                    if (hitCircle(e, bx[i], btnY, btnR * 1.35f)) {
                        if (i == 0) move(-1);
                        else if (i == 1) rotate();
                        else if (i == 2) move(1);
                        else softDrop();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastMoveX;
                if (Math.abs(dx) > cell * 0.85f) {
                    move(dx > 0 ? 1 : -1);
                    lastMoveX = x;
                }
                break;
            case MotionEvent.ACTION_UP:
                // 棋盘上短促下划 = 直接落底
                if (y - downY > dp(60) && Math.abs(x - downX) < dp(40)
                        && y < btnY - btnR) {
                    while (!collide(px, py + 1, piece)) py++;
                    lock();
                }
                break;
            default:
                break;
        }
        return true;
    }
}
