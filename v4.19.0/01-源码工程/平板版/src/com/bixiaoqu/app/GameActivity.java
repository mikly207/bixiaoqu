package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 【v4.9.7】游戏厅：底栏中间新增的入口。
 *
 * 大厅是一个两列卡片网格，点任意卡片就在同一个 Activity 里换成对应的 GameView，
 * 按返回键回到大厅。所有游戏都是 Canvas 现画的，不依赖任何图片资源，
 * 所以整套 10 款游戏只增加了代码体积。
 */
public class GameActivity extends Activity {


    static final String[] NAMES = {
            "五子棋", "斗地主", "愤怒的小鸟", "神庙逃亡", "滑雪大冒险",
            "极速赛车", "植物大战僵尸", "保卫萝卜", "贪吃蛇", "俄罗斯方块"
    };
    static final String[] DESCS = {
            "15 路棋盘 · 人机对战", "你当地主 · 两个电脑农民", "拖弹弓 · 砸绿猪",
            "三跑道 · 换道跳跃滑铲", "下坡躲树 · 吃金币", "四车道 · 躲前车",
            "种植物 · 挡僵尸", "建炮塔 · 守萝卜", "穿墙不死 · 越吃越快", "经典消行 · 越叠越快"
    };
    static final String[] IDS = {
            "gomoku", "doudizhu", "bird", "temple", "ski",
            "racing", "pvz", "carrot", "snake", "tetris"
    };
    static final int[] TINTS = {
            0xFF26A69A, 0xFFEF6C00, 0xFFE53935, 0xFF8D6E63, 0xFF42A5F5,
            0xFFF9A825, 0xFF7CB342, 0xFFFB8C00, 0xFF66BB6A, 0xFF5C6BC0
    };

    FrameLayout stage;
    View hall;
    View backBtn;
    GameView current;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        try { setContentView(build()); }
        catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("游戏厅打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(20), dp(60), dp(20), dp(20));
            setContentView(tv);
        }
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MainActivity.C_BG);

        hall = buildHall();
        root.addView(hall, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(MainActivity.C_BG);
        stage.setVisibility(View.GONE);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 游戏内的返回键（浮在画面左上角）
        backBtn = buildBackBtn();
        backBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(40), dp(40));
        bp.leftMargin = dp(10);
        bp.topMargin = dp(8);
        root.addView(backBtn, bp);
        return root;
    }

    View buildBackBtn() {
        TextView tv = new TextView(this);
        tv.setText("←");
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(19);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(0x88000000, 20));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeGame(); }
        });
        return tv;
    }

    View buildHall() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(6));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(20);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), dp(6), dp(8), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText("游戏厅");
        t.setTextColor(MainActivity.C_FG);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        TextView st = new TextView(this);
        st.setText("十款小游戏 · 全部离线 · 不耗流量");
        st.setTextColor(MainActivity.C_SUB);
        st.setTextSize(10.5f);
        col.addView(st);
        top.addView(col);
        root.addView(top);

        // 卡片网格
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(8), dp(20));

        for (int i = 0; i < NAMES.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(card(i), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < NAMES.length) {
                row.addView(card(i + 1), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                View holder = new View(this);
                row.addView(holder, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(7);
            grid.addView(row, rp);
        }
        sc.addView(grid);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    View card(final int i) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(11));
        card.setBackground(round(MainActivity.C_CARD, 16));
        card.setClickable(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(NAMES[i].substring(0, 1));
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(16);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(TINTS[i], 19));
        head.addView(badge, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView nm = new TextView(this);
        nm.setText(NAMES[i]);
        nm.setTextColor(MainActivity.C_FG);
        nm.setTextSize(14);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(9);
        head.addView(nm, np);
        card.addView(head);

        TextView ds = new TextView(this);
        ds.setText(DESCS[i]);
        ds.setTextColor(MainActivity.C_SUB);
        ds.setTextSize(10.5f);
        ds.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams dsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dsp.topMargin = dp(8);
        card.addView(ds, dsp);

        int best = bestOf(IDS[i]);
        TextView bs = new TextView(this);
        bs.setText(best > 0 ? "最高 " + best + " 分" : "还没玩过");
        bs.setTextColor(best > 0 ? 0xFFFB7299 : 0xFF6F7581);
        bs.setTextSize(10);
        LinearLayout.LayoutParams bsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bsp.topMargin = dp(6);
        card.addView(bs, bsp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGame(i); }
        });

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(4);
        cp.rightMargin = dp(4);
        card.setLayoutParams(cp);
        return card;
    }

    int bestOf(String id) {
        try {
            SharedPreferences sp = getSharedPreferences("bxq_game", Context.MODE_PRIVATE);
            return sp.getInt("best_" + id, 0);
        } catch (Throwable e) { return 0; }
    }

    GameView create(int i) {
        switch (IDS[i]) {
            case "gomoku": return new GameGomoku(this);
            case "doudizhu": return new GameDoudizhu(this);
            case "bird": return new GameBird(this);
            case "temple": return new GameTemple(this);
            case "ski": return new GameSki(this);
            case "racing": return new GameRacing(this);
            case "pvz": return new GamePvZ(this);
            case "carrot": return new GameCarrot(this);
            case "snake": return new GameSnake(this);
            case "tetris": return new GameTetris(this);
            default: return new GameSnake(this);
        }
    }

    void openGame(int i) {
        try {
            current = create(i);
            stage.removeAllViews();
            stage.addView(current, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            stage.setVisibility(View.VISIBLE);
            hall.setVisibility(View.GONE);
            backBtn.setVisibility(View.VISIBLE);
            current.requestFocus();
        } catch (Throwable e) {
            android.widget.Toast.makeText(this, "启动游戏失败：" + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void closeGame() {
        if (current != null) {
            try { current.onHostPause(); } catch (Throwable ignore) { }
            current = null;
        }
        stage.removeAllViews();
        stage.setVisibility(View.GONE);
        backBtn.setVisibility(View.GONE);
        // 回大厅时重建，最高分才会即时刷新
        FrameLayout root = (FrameLayout) stage.getParent();
        int idx = root.indexOfChild(hall);
        root.removeView(hall);
        hall = buildHall();
        root.addView(hall, idx, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hall.setVisibility(View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (current != null) { closeGame(); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        if (current != null) current.onHostPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (current != null) current.onHostResume();
    }
}
