package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/**
 * 斗地主（简化版，玩家固定当地主）。
 *
 * 牌型支持：单张 / 对子 / 三张 / 三带一 / 三带二 / 顺子 / 连对 / 炸弹 / 王炸。
 * 两个电脑农民各自为战（不做配合），AI 会「先用最小的能压过的牌」，压不过就不出。
 */
public class GameDoudizhu extends GameView {

    static class Card {
        int rank;   // 3..15(2) 16 小王 17 大王
        int suit;   // 0 ♠ 1 ♥ 2 ♦ 3 ♣ ，王为 -1
        Card(int r, int s) { rank = r; suit = s; }
    }

    static class Combo {
        int type;   // 1 单 2 对 3 三 4 三带一 5 三带二 6 顺子 7 连对 8 炸弹 9 王炸
        int main;   // 主牌点数
        int len;    // 顺子张数 / 连对对数
    }

    final ArrayList<Card> myHand = new ArrayList<>();
    final ArrayList<Card> aiR = new ArrayList<>();
    final ArrayList<Card> aiL = new ArrayList<>();
    final Random rnd = new Random();

    // 回合：0 我，1 右家，2 左家
    int turn = 0;
    Combo lastCombo;
    int lastOwner = -1;
    ArrayList<Card> lastCards = new ArrayList<>();
    int passCount;

    boolean[] picked = new boolean[20];
    float aiWait;
    String msg = "";
    float msgT;
    boolean gameEnd;
    boolean playerWin;

    // 牌布局
    float cardW, cardH, step, startX, handY;
    float btnPlayL, btnPlayR, btnPassL, btnPassR, btnY, btnH;

    public GameDoudizhu(Context c) {
        super(c);
        gameId = "doudizhu";
        gameTitle = "斗地主";
    }

    @Override protected boolean showHud() { return false; }

    @Override protected void reset() {
        myHand.clear();
        aiR.clear();
        aiL.clear();
        ArrayList<Card> deck = new ArrayList<>();
        for (int r = 3; r <= 15; r++) for (int s = 0; s < 4; s++) deck.add(new Card(r, s));
        deck.add(new Card(16, -1));
        deck.add(new Card(17, -1));
        Collections.shuffle(deck, rnd);

        for (int i = 0; i < 17; i++) {
            myHand.add(deck.remove(0));
            aiR.add(deck.remove(0));
            aiL.add(deck.remove(0));
        }
        // 底牌 3 张归地主（玩家）
        while (!deck.isEmpty()) myHand.add(deck.remove(0));

        sort(myHand);
        sort(aiR);
        sort(aiL);

        picked = new boolean[20];
        turn = 0;
        lastCombo = null;
        lastOwner = -1;
        lastCards.clear();
        passCount = 0;
        aiWait = 0;
        msg = "你是地主，先出牌";
        msgT = 2000;
        gameEnd = false;
        playerWin = false;
        score = 0;
        layoutCards();
    }

    void layoutCards() {
        cardW = Math.min(dp(48), W * 0.145f);
        cardH = cardW * 1.42f;
        int n = myHand.size();
        step = n <= 1 ? cardW : Math.min(cardW * 0.66f, (W - dp(12) - cardW) / (n - 1));
        startX = (W - (cardW + step * (n - 1))) / 2f;
        handY = H - cardH - dp(12);
        btnH = dp(34);
        btnY = handY - btnH - dp(12);
        btnPlayR = W - dp(12);
        btnPlayL = btnPlayR - dp(84);
        btnPassR = btnPlayL - dp(10);
        btnPassL = btnPassR - dp(72);
    }

    static void sort(ArrayList<Card> h) {
        Collections.sort(h, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) {
                if (a.rank != b.rank) return a.rank - b.rank;
                return a.suit - b.suit;
            }
        });
    }

    // ------------------------------------------------------------ 牌型

    static Combo combo(int t, int main, int len) {
        Combo c = new Combo();
        c.type = t;
        c.main = main;
        c.len = len;
        return c;
    }

    static Combo analyze(ArrayList<Card> cs) {
        int n = cs.size();
        if (n == 0) return null;
        int[] cnt = new int[18];
        for (Card c : cs) cnt[c.rank]++;

        if (n == 2 && cnt[16] == 1 && cnt[17] == 1) return combo(9, 17, 0);
        if (n == 1) return combo(1, cs.get(0).rank, 0);

        int triple = -1, pairs = 0, singles = 0, distinct = 0;
        int maxR = 0, minR = 99;
        boolean allSame = true;
        int first = cs.get(0).rank;
        for (int r = 3; r <= 17; r++) {
            if (cnt[r] == 0) continue;
            distinct++;
            maxR = Math.max(maxR, r);
            minR = Math.min(minR, r);
            if (r != first) allSame = false;
            if (cnt[r] == 3) triple = r;
            if (cnt[r] == 2) pairs++;
            if (cnt[r] == 1) singles++;
            if (cnt[r] == 4) {
                if (n == 4) return combo(8, r, 0);
            }
        }

        if (n == 2 && allSame) return combo(2, first, 0);
        if (n == 3 && allSame) return combo(3, first, 0);
        if (n == 4 && allSame) return combo(8, first, 0);
        if (n == 4 && triple > 0 && singles == 1) return combo(4, triple, 0);
        if (n == 5 && triple > 0 && pairs == 1) return combo(5, triple, 0);

        // 顺子：5 张以上、全单张、连续、不含 2 与王
        if (n >= 5 && singles == n && distinct == n && maxR <= 14 && maxR - minR == n - 1) {
            return combo(6, maxR, n);
        }
        // 连对：3 对以上、全对子、连续、不含 2 与王
        if (n >= 6 && n % 2 == 0 && pairs == n / 2 && distinct == n / 2
                && maxR <= 14 && maxR - minR == distinct - 1) {
            return combo(7, maxR, distinct);
        }
        return null;
    }

    static boolean beats(Combo a, Combo b) {
        if (a == null) return false;
        if (b == null) return true;
        if (a.type == 9) return true;
        if (b.type == 9) return false;
        if (a.type == 8 && b.type != 8) return true;
        if (a.type != b.type) return false;
        if (a.len != b.len) return false;
        return a.main > b.main;
    }

    // ------------------------------------------------------------ 流程

    @Override protected void step(int dt) {
        if (msgT > 0) msgT -= dt;
        if (gameEnd || over) return;

        if (turn != 0) {
            aiWait += dt;
            if (aiWait > 760) {
                aiWait = 0;
                doAi();
            }
        }
    }

    void doAi() {
        ArrayList<Card> hand = turn == 1 ? aiR : aiL;
        ArrayList<Card> pick = aiFind(hand, lastCombo);
        if (pick == null) {
            passCount++;
            showMsg((turn == 1 ? "右家" : "左家") + " 不出");
            nextTurn();
            return;
        }
        for (Card c : pick) hand.remove(c);
        lastCards = new ArrayList<>(pick);
        lastCombo = analyze(pick);
        lastOwner = turn;
        passCount = 0;
        showMsg((turn == 1 ? "右家" : "左家") + " 出了 " + name(lastCombo));
        if (hand.isEmpty()) {
            finish(false);
            return;
        }
        nextTurn();
    }

    void nextTurn() {
        if (passCount >= 2) {
            passCount = 0;
            lastCombo = null;
            lastCards.clear();
            lastOwner = -1;
        }
        turn = (turn + 1) % 3;
        if (turn == 0) {
            // 轮到玩家：自由出牌时给个提示
            if (lastCombo == null) showMsg("你可以自由出牌");
        }
    }

    void finish(boolean win) {
        gameEnd = true;
        playerWin = win;
        score = win ? 100 : 0;
        gameOver();
    }

    @Override protected String overTitle() {
        return playerWin ? "地主获胜！" : "农民获胜";
    }

    void showMsg(String s) {
        msg = s;
        msgT = 1800;
    }

    // ------------------------------------------------------------ AI 找牌

    ArrayList<Card> aiFind(ArrayList<Card> hand, Combo target) {
        int[] cnt = new int[18];
        for (Card c : hand) cnt[c.rank]++;

        if (target == null) {
            // 首出：最小的单张（除非只剩一手能走完）
            if (hand.size() == 1) return new ArrayList<>(hand);
            Combo all = analyze(hand);
            if (all != null && all.type != 1) return new ArrayList<>(hand);
            for (int r = 3; r <= 17; r++) {
                if (cnt[r] > 0) return take(hand, r, 1);
            }
            return null;
        }

        switch (target.type) {
            case 1:
                for (int r = 3; r <= 17; r++) {
                    if (cnt[r] > 0 && r > target.main) return take(hand, r, 1);
                }
                break;
            case 2:
                for (int r = 3; r <= 17; r++) {
                    if (cnt[r] >= 2 && r > target.main) return take(hand, r, 2);
                }
                break;
            case 3:
                for (int r = 3; r <= 17; r++) {
                    if (cnt[r] >= 3 && r > target.main) return take(hand, r, 3);
                }
                break;
            case 4: {
                for (int r = 3; r <= 17; r++) {
                    if (cnt[r] >= 3 && r > target.main) {
                        ArrayList<Card> out = take(hand, r, 3);
                        Card extra = smallestSingle(hand, out);
                        if (extra != null) { out.add(extra); return out; }
                    }
                }
                break;
            }
            case 5: {
                for (int r = 3; r <= 17; r++) {
                    if (cnt[r] >= 3 && r > target.main) {
                        for (int p = 3; p <= 17; p++) {
                            if (p == r || cnt[p] < 2) continue;
                            ArrayList<Card> out = take(hand, r, 3);
                            out.addAll(take(hand, p, 2));
                            return out;
                        }
                    }
                }
                break;
            }
            case 6: {
                int len = target.len;
                for (int s = 3; s + len - 1 <= 14; s++) {
                    if (s + len - 1 <= target.main) continue;
                    boolean ok = true;
                    for (int r = s; r < s + len; r++) if (cnt[r] < 1) { ok = false; break; }
                    if (!ok) continue;
                    ArrayList<Card> out = new ArrayList<>();
                    for (int r = s; r < s + len; r++) out.addAll(take(hand, r, 1));
                    return out;
                }
                break;
            }
            case 7: {
                int pairs = target.len;
                for (int s = 3; s + pairs - 1 <= 14; s++) {
                    if (s + pairs - 1 <= target.main) continue;
                    boolean ok = true;
                    for (int r = s; r < s + pairs; r++) if (cnt[r] < 2) { ok = false; break; }
                    if (!ok) continue;
                    ArrayList<Card> out = new ArrayList<>();
                    for (int r = s; r < s + pairs; r++) out.addAll(take(hand, r, 2));
                    return out;
                }
                break;
            }
            default:
                break;
        }

        // 炸弹 / 王炸兜底
        // 手牌快走完时才舍得用王炸/炸弹压，否则一上来就炸太浪费
        if (hand.size() <= 6 && cnt[16] == 1 && cnt[17] == 1) {
            ArrayList<Card> out = new ArrayList<>();
            out.addAll(take(hand, 16, 1));
            out.addAll(take(hand, 17, 1));
            return out;
        }
        if (target.type != 8 && hand.size() <= 8) {
            for (int r = 3; r <= 15; r++) {
                if (cnt[r] == 4) return take(hand, r, 4);
            }
        }
        return null;
    }

    ArrayList<Card> take(ArrayList<Card> hand, int rank, int num) {
        ArrayList<Card> out = new ArrayList<>();
        for (Card c : hand) {
            if (c.rank == rank && out.size() < num) out.add(c);
        }
        return out;
    }

    Card smallestSingle(ArrayList<Card> hand, ArrayList<Card> exclude) {
        Card best = null;
        for (Card c : hand) {
            if (exclude.contains(c)) continue;
            if (best == null || c.rank < best.rank) best = c;
        }
        return best;
    }

    static String name(Combo c) {
        if (c == null) return "";
        switch (c.type) {
            case 1: return "单张";
            case 2: return "对子";
            case 3: return "三张";
            case 4: return "三带一";
            case 5: return "三带二";
            case 6: return "顺子";
            case 7: return "连对";
            case 8: return "炸弹";
            case 9: return "王炸";
            default: return "";
        }
    }

    // ------------------------------------------------------------ 绘制

    @Override protected void paint(Canvas c) {
        c.drawColor(0xFF17251C);

        // 桌面
        pFill.setColor(0xFF1D3226);
        c.drawCircle(W / 2f, H * 0.44f, W * 0.46f, pFill);

        drawAi(c, 1, W - dp(52), dp(66), aiR.size());
        drawAi(c, 2, dp(52), dp(66), aiL.size());

        // 我的身份
        box(c, W / 2f - dp(30), dp(24), W / 2f + dp(30), dp(48), 0x55FB7299, dp(12));
        label(c, "地主", W / 2f, dp(41), 12, 0xFFFFD3E0, Paint.Align.CENTER);

        // 上一手牌
        if (!lastCards.isEmpty()) {
            float cw = dp(30), ch = cw * 1.4f;
            int n = lastCards.size();
            float st = Math.min(cw * 0.72f, (W * 0.6f - cw) / Math.max(1, n - 1));
            float sx = W / 2f - (cw + st * (n - 1)) / 2f;
            float sy = H * 0.30f;
            String who = lastOwner == 0 ? "你" : (lastOwner == 1 ? "右家" : "左家");
            label(c, who + "：" + name(lastCombo), W / 2f, sy - dp(10), 11, 0xFF9AA0AB,
                    Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                drawCard(c, sx + i * st, sy, cw, ch, lastCards.get(i), false, true);
            }
        }

        // 状态文字
        if (msgT > 0) {
            label(c, msg, W / 2f, H * 0.52f, 12, 0xCCFFFFFF, Paint.Align.CENTER);
        }

        // 我的手牌
        for (int i = 0; i < myHand.size(); i++) {
            float x = startX + i * step;
            float y = handY - (picked[i] ? dp(16) : 0);
            drawCard(c, x, y, cardW, cardH, myHand.get(i), picked[i], false);
        }

        // 按钮
        boolean canAct = turn == 0 && !gameEnd;
        if (canAct) {
            boolean mustPlay = lastCombo == null;
            box(c, btnPlayL, btnY, btnPlayR, btnY + btnH,
                    mustPlay ? 0xFFFB7299 : 0xFFFB7299, btnH / 2);
            label(c, "出牌", (btnPlayL + btnPlayR) / 2f, btnY + btnH / 2f + dp(5), 13,
                    0xFF1A1020, Paint.Align.CENTER);
            if (!mustPlay) {
                box(c, btnPassL, btnY, btnPassR, btnY + btnH, 0x33FFFFFF, btnH / 2);
                label(c, "不出", (btnPassL + btnPassR) / 2f, btnY + btnH / 2f + dp(5), 13,
                        0xFFE8EAF0, Paint.Align.CENTER);
            }
        } else if (!gameEnd) {
            label(c, turn == 1 ? "右家出牌中…" : "左家出牌中…",
                    W / 2f, handY - dp(30), 12, 0xFF9AA0AB, Paint.Align.CENTER);
        }
    }

    void drawAi(Canvas c, int who, float x, float y, int count) {
        pFill.setColor(0xFF2E4033);
        c.drawCircle(x, y, dp(20), pFill);
        pFill.setColor(0xFF3E5443);
        c.drawCircle(x, y, dp(16), pFill);
        label(c, who == 1 ? "右" : "左", x, y + dp(5), 13, 0xFFB9E3C4, Paint.Align.CENTER);
        label(c, "农民 · " + count + " 张", x, y + dp(34), 10,
                turn == who ? 0xFFFB7299 : 0xFF8A909C, Paint.Align.CENTER);
        if (turn == who) {
            frame(c, x - dp(22), y - dp(22), x + dp(22), y + dp(22), 0x88FB7299, dp(22), dp(1.6f));
        }
    }

    void drawCard(Canvas c, float x, float y, float w, float h, Card cd,
                  boolean sel, boolean small) {
        box(c, x, y, x + w, y + h, 0xFFF7F8FA, dp(5));
        frame(c, x, y, x + w, y + h, sel ? 0xFFFB7299 : 0x33000000, dp(5), dp(1));
        int col = (cd.rank == 17 || cd.suit == 1 || cd.suit == 2) ? 0xFFE53935 : 0xFF23262D;
        String r = rankName(cd.rank);
        float ts = small ? 8.5f : (w < dp(36) ? 10f : 12.5f);
        if (cd.rank >= 16) {
            label(c, cd.rank == 17 ? "大" : "小", x + w * 0.16f, y + h * 0.30f, ts, col);
            label(c, cd.rank == 17 ? "王" : "王", x + w * 0.16f, y + h * 0.58f, ts, col);
        } else {
            label(c, r, x + w * 0.12f, y + h * 0.30f, ts, col);
            label(c, suitName(cd.suit), x + w * 0.12f, y + h * 0.56f, ts * 0.9f, col);
        }
        if (!small && w > dp(34)) {
            label(c, r, x + w * 0.5f, y + h * 0.78f, ts * 1.1f, col & 0x44FFFFFF, Paint.Align.CENTER);
        }
    }

    static String rankName(int r) {
        switch (r) {
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            case 15: return "2";
            case 16: return "小";
            case 17: return "大";
            default: return "" + r;
        }
    }

    static String suitName(int s) {
        switch (s) {
            case 0: return "♠";
            case 1: return "♥";
            case 2: return "♦";
            case 3: return "♣";
            default: return "";
        }
    }

    // ------------------------------------------------------------ 触摸

    @Override protected boolean touch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        if (gameEnd) return true;
        float x = e.getX(), y = e.getY();

        if (turn == 0) {
            if (y >= btnY - dp(8) && y <= btnY + btnH + dp(8)) {
                if (x >= btnPlayL && x <= btnPlayR) {
                    tryPlay();
                    return true;
                }
                if (lastCombo != null && x >= btnPassL && x <= btnPassR) {
                    passCount++;
                    showMsg("你选择不出");
                    picked = new boolean[20];
                    nextTurn();
                    return true;
                }
                return true;
            }
        }

        // 点手牌
        if (y < handY - dp(20)) return true;
        for (int i = myHand.size() - 1; i >= 0; i--) {
            float l = startX + i * step;
            if (x >= l && x <= l + cardW) {
                picked[i] = !picked[i];
                return true;
            }
        }
        return true;
    }

    void tryPlay() {
        ArrayList<Card> sel = new ArrayList<>();
        for (int i = 0; i < myHand.size(); i++) if (picked[i]) sel.add(myHand.get(i));
        if (sel.isEmpty()) {
            showMsg("先点要出的牌");
            return;
        }
        sort(sel);
        Combo c = analyze(sel);
        if (c == null) {
            showMsg("这不是合法的牌型");
            return;
        }
        if (!beats(c, lastCombo)) {
            showMsg("压不过上家的 " + name(lastCombo));
            return;
        }
        for (Card cd : sel) myHand.remove(cd);
        lastCards = sel;
        lastCombo = c;
        lastOwner = 0;
        passCount = 0;
        picked = new boolean[20];
        layoutCards();
        showMsg("你出了 " + name(c));
        if (myHand.isEmpty()) {
            finish(true);
            return;
        }
        nextTurn();
    }
}
