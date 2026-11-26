package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * 五子棋：15×15，人机对战。
 * 电脑走子用「先看自己能不能五连 → 再看对手有没有五连 → 否则启发式评分」三段式，
 * 不搜索整棵博弈树，但足够挡住普通玩家。
 */
public class GameGomoku extends GameView {

    static final int N = 15;
    static final int EMPTY = 0, BLACK = 1, WHITE = 2;

    int[][] b = new int[N][N];
    int cell, ox, oy;
    int turn = BLACK;          // 1 = 玩家（黑），2 = 电脑（白）
    boolean thinking;
    float thinkT;
    int lastR = -1, lastC = -1;
    String status = "";

    public GameGomoku(Context c) {
        super(c);
        gameId = "gomoku";
        gameTitle = "五子棋";
    }

    @Override protected boolean showHud() { return false; }

    @Override protected void reset() {
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) b[r][c] = EMPTY;
        cell = Math.min(W - dp(30), H - dp(120)) / N;
        ox = (W - cell * N) / 2;
        oy = (H - cell * N) / 2 + dp(6);
        turn = BLACK;
        thinking = false;
        thinkT = 0;
        lastR = lastC = -1;
        status = "你执黑先行";
        score = 0;
        win = 0;
    }

    boolean in(int r, int c) { return r >= 0 && r < N && c >= 0 && c < N; }

    @Override protected void step(int dt) {
        if (thinking) {
            thinkT += dt;
            if (thinkT > 260) {
                thinking = false;
                aiMove();
            }
        }
    }

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF14161B);

        float l = ox - dp(6), t = oy - dp(6);
        float r = ox + cell * N + dp(6), btm = oy + cell * N + dp(6);
        box(c, l, t, r, btm, 0xFF3A3126, dp(12));
        frame(c, l, t, r, btm, 0x22FFFFFF, dp(12), dp(1));

        // 棋盘线
        pLine.setStrokeWidth(dp(1));
        pLine.setColor(0x66D9C7A3);
        for (int i = 0; i < N; i++) {
            float p = ox + i * cell + cell / 2f;
            c.drawLine(p, oy + cell / 2f, p, oy + cell / 2f + (N - 1) * cell, pLine);
            c.drawLine(ox + cell / 2f, p, ox + cell / 2f + (N - 1) * cell, p, pLine);
        }
        // 天元与星位
        pFill.setColor(0xAA3A3126);
        int[][] stars = {{3,3},{3,11},{11,3},{11,11},{7,7}};
        for (int[] s : stars) {
            c.drawCircle(ox + s[1] * cell + cell / 2f, oy + s[0] * cell + cell / 2f,
                    cell * 0.13f, pFill);
        }

        // 棋子
        for (int rr = 0; rr < N; rr++) {
            for (int cc = 0; cc < N; cc++) {
                if (b[rr][cc] == EMPTY) continue;
                float cx = ox + cc * cell + cell / 2f;
                float cy = oy + rr * cell + cell / 2f;
                float rad = cell * 0.42f;
                pFill.setColor(0x66000000);
                c.drawCircle(cx + dp(1.2f), cy + dp(1.6f), rad, pFill);
                if (b[rr][cc] == BLACK) {
                    pFill.setColor(0xFF1B1D22);
                    c.drawCircle(cx, cy, rad, pFill);
                    pFill.setColor(0x33FFFFFF);
                    c.drawCircle(cx - rad * 0.3f, cy - rad * 0.35f, rad * 0.3f, pFill);
                } else {
                    pFill.setColor(0xFFF2F4F8);
                    c.drawCircle(cx, cy, rad, pFill);
                    pFill.setColor(0x22000000);
                    c.drawCircle(cx + rad * 0.25f, cy + rad * 0.3f, rad * 0.55f, pFill);
                }
            }
        }
        if (lastR >= 0) {
            float cx = ox + lastC * cell + cell / 2f;
            float cy = oy + lastR * cell + cell / 2f;
            pFill.setColor(0xFFFB7299);
            c.drawCircle(cx, cy, cell * 0.11f, pFill);
        }

        label(c, status, W / 2f, oy + cell * N + dp(30), 12,
                thinking ? 0xFFFB7299 : 0xFF9AA0AB, Paint.Align.CENTER);
    }

    @Override protected String overTitle() {
        return win == BLACK ? "你赢了！" : (win == WHITE ? "电脑赢了" : "平局");
    }

    int win = 0;

    @Override protected boolean touch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        if (thinking || turn != BLACK) return true;

        int cc = (int) ((e.getX() - ox - cell / 2f) / cell + 0.5f);
        int rr = (int) ((e.getY() - oy - cell / 2f) / cell + 0.5f);
        if (!in(rr, cc) || b[rr][cc] != EMPTY) return true;

        place(rr, cc, BLACK);
        if (win != 0 || over) return true;
        turn = WHITE;
        thinking = true;
        thinkT = 0;
        status = "电脑思考中…";
        return true;
    }

    void place(int r, int c, int who) {
        b[r][c] = who;
        lastR = r; lastC = c;
        if (checkWin(r, c, who)) {
            win = who;
            score = who == BLACK ? 100 : 0;
            status = who == BLACK ? "五连！你赢了" : "五连，电脑赢了";
            gameOver();
            return;
        }
        boolean full = true;
        for (int rr = 0; rr < N && full; rr++)
            for (int cc = 0; cc < N; cc++) if (b[rr][cc] == EMPTY) { full = false; break; }
        if (full) {
            win = 0;
            status = "棋盘满了，平局";
            gameOver();
        }
    }

    boolean checkWin(int r, int c, int who) {
        int[][] dirs = {{0,1},{1,0},{1,1},{1,-1}};
        for (int[] d : dirs) {
            int cnt = 1;
            for (int s = 1; s <= 4; s++) {
                int rr = r + d[0]*s, cc = c + d[1]*s;
                if (in(rr,cc) && b[rr][cc] == who) cnt++; else break;
            }
            for (int s = 1; s <= 4; s++) {
                int rr = r - d[0]*s, cc = c - d[1]*s;
                if (in(rr,cc) && b[rr][cc] == who) cnt++; else break;
            }
            if (cnt >= 5) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 电脑

    void aiMove() {
        if (over) return;

        // 1) 自己能五连 → 直接赢
        int[] p = findPoint(WHITE);
        if (p == null) p = findPoint(BLACK);     // 2) 对手能五连 → 必须堵

        if (p == null) {
            // 3) 启发式：自己的收益 + 1.1 倍对手的收益
            int bestV = -1;
            for (int r = 0; r < N; r++) {
                for (int c = 0; c < N; c++) {
                    if (b[r][c] != EMPTY) continue;
                    if (!nearStone(r, c, 2)) continue;
                    int v = (int) (scoreAt(r, c, WHITE) * 1.15f + scoreAt(r, c, BLACK));
                    if (v > bestV) { bestV = v; p = new int[]{r, c}; }
                }
            }
        }
        if (p == null) {
            int mid = N / 2;
            for (int d = 0; d < N; d++) {
                int r = mid + (d % 2 == 0 ? d / 2 : -d / 2);
                if (in(r, mid) && b[r][mid] == EMPTY) { p = new int[]{r, mid}; break; }
            }
        }
        if (p == null) { p = new int[]{0, 0}; }

        place(p[0], p[1], WHITE);
        if (win == 0 && !over) {
            turn = BLACK;
            status = "轮到你落子";
        }
    }

    /** 找能立刻成五的点 */
    int[] findPoint(int who) {
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (b[r][c] != EMPTY) continue;
                b[r][c] = who;
                boolean w = checkWin(r, c, who);
                b[r][c] = EMPTY;
                if (w) return new int[]{r, c};
            }
        }
        return null;
    }

    boolean nearStone(int r, int c, int rad) {
        for (int dr = -rad; dr <= rad; dr++) {
            for (int dc = -rad; dc <= rad; dc++) {
                int rr = r + dr, cc = c + dc;
                if (in(rr, cc) && b[rr][cc] != EMPTY) return true;
            }
        }
        return false;
    }

    int scoreAt(int r, int c, int who) {
        int[][] dirs = {{0,1},{1,0},{1,1},{1,-1}};
        int total = 0;
        for (int[] d : dirs) total += lineScore(r, c, d[0], d[1], who);
        return total;
    }

    int lineScore(int r, int c, int dr, int dc, int who) {
        int cnt = 1, open = 0;
        for (int s = -1; s <= 1; s += 2) {
            int rr = r + dr * s, cc = c + dc * s;
            while (in(rr, cc) && b[rr][cc] == who) {
                cnt++;
                rr += dr * s;
                cc += dc * s;
            }
            if (in(rr, cc) && b[rr][cc] == EMPTY) open++;
        }
        if (cnt >= 5) return 1000000;
        if (cnt == 4) return open == 2 ? 100000 : (open == 1 ? 12000 : 0);
        if (cnt == 3) return open == 2 ? 8000 : (open == 1 ? 900 : 0);
        if (cnt == 2) return open == 2 ? 600 : (open == 1 ? 120 : 0);
        if (cnt == 1) return open == 2 ? 40 : 8;
        return 0;
    }
}
