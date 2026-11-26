package com.bixiaoqu.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 【v4.13】分区页：参考 B 站客户端「分区」界面。
 * 由首页频道栏右侧的「•••」进入。
 *  - 顶部：分区标题 + 全部分区网格（4 列，彩色图标 + 名称）
 *  - 点分区 → 该分区排行榜视频列表，点视频进播放页
 * 全部纯 Java 动态建 UI，兼容 minSdk 22。
 */
public class ZoneActivity extends Activity {

    // 分区：名称 / rid（B 站分区 tid）/ 图标字 / 主题色
    static final String[] Z_NAMES = {
            "番剧", "国创", "纪录片", "电影",
            "电视剧", "综艺", "影视", "娱乐",
            "音乐", "舞蹈", "动画", "绘画",
            "鬼畜", "游戏", "资讯", "知识",
            "人工智能", "科技数码", "汽车", "时尚美妆",
            "家装房产", "户外潮流", "健身", "体育运动",
            "手工", "美食", "小剧场", "旅游出行"
    };
    static final int[] Z_RIDS = {
            1, 4, 3, 181,
            11, 13, 181, 5,
            3, 129, 1, 188,
            119, 4, 202, 36,
            188, 188, 223, 155,
            160, 167, 234, 234,
            160, 211, 181, 176
    };
    // 图标用彩色字块模拟（无图片资源依赖，低版本也能显示）
    static final String[] Z_ICONS = {
            "番", "国", "纪", "影",
            "剧", "综", "视", "娱",
            "音", "舞", "动", "画",
            "鬼", "游", "讯", "知",
            "AI", "科", "车", "妆",
            "家", "户", "健", "体",
            "手", "食", "剧", "旅"
    };
    static final int[] Z_COLORS = {
            0xFFF5A623, 0xFFF2708A, 0xFF4FC3A1, 0xFFFF6B6B,
            0xFFFFA94D, 0xFFFFC93C, 0xFF7C9CFF, 0xFFFF8FB1,
            0xFF45C2E4, 0xFFFF7EB8, 0xFF7B8CFF, 0xFFF5B041,
            0xFFA66BFF, 0xFF5AC8FA, 0xFF4DC3FF, 0xFFFFD166,
            0xFF5AC8E8, 0xFF5B9DFF, 0xFFFF8A65, 0xFFFF6FA8,
            0xFFFFA26B, 0xFF4DB6AC, 0xFFFF7A59, 0xFF66BB6A,
            0xFFBA68C8, 0xFFFFB74D, 0xFF9575CD, 0xFF26A69A
    };

    final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    LinearLayout content;   // 页面主体（网格 / 列表之间切换）
    int appliedStamp = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        appliedStamp = MainActivity.themeStamp;
        try {
            setContentView(build());
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("分区页加载失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(24), dp(60), dp(24), dp(24));
            setContentView(tv);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (appliedStamp != MainActivity.themeStampOf(this)) recreate();
    }

    int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v + 0.5f); }

    /** 圆角/圆形背景 */
    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radDp));
        return g;
    }

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // 顶栏：返回 + 居中「分区」
        FrameLayout top = new FrameLayout(this);
        top.setPadding(dp(6), dp(10), dp(6), dp(10));
        ImageView back = Ic.view(this, "back", MainActivity.C_FG, 22);
        back.setPadding(dp(8), dp(4), dp(8), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.START | Gravity.CENTER_VERTICAL);
        top.addView(back, bp);
        TextView title = new TextView(this);
        title.setText("分区");
        title.setTextSize(18);
        title.setTextColor(MainActivity.C_FG);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        top.addView(title, tp);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 【v4.13.1 关键修复】垂直 LinearLayout 的 weight 只作用于高度：
        // 之前写 width=0 让 ScrollView 宽度变成 0，页面只剩标题、内容全不可见。
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showGrid();
        return root;
    }

    /** 分区网格页：快捷访问（可编辑）+ 全部分区 */
    void showGrid() {
        content.removeAllViews();

        // ---------- 快捷访问 ----------
        TextView quick = new TextView(this);
        quick.setText("快捷访问");
        quick.setTextSize(15);
        quick.setTextColor(MainActivity.C_FG);
        quick.setPadding(dp(16), dp(12), dp(16), dp(8));
        content.addView(quick);

        LinearLayout quickCard = new LinearLayout(this);
        quickCard.setOrientation(LinearLayout.VERTICAL);
        quickCard.setBackground(round(MainActivity.C_CARD, 12));
        quickCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qcp.leftMargin = dp(12); qcp.rightMargin = dp(12);
        content.addView(quickCard, qcp);

        int[] favs = parseFavs(Prefs.zoneFavorites(this));
        if (favs.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("＋  编辑");
            empty.setTextSize(14);
            empty.setTextColor(MainActivity.C_SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(18), dp(10), dp(18));
            empty.setClickable(true);
            empty.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { editFavorites(); }
            });
            quickCard.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout qr = new LinearLayout(this);
            qr.setOrientation(LinearLayout.HORIZONTAL);
            quickCard.addView(qr, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int k = 0; k < favs.length; k++) {
                final int idx = favs[k];
                if (idx < 0 || idx >= Z_NAMES.length) continue;
                qr.addView(smallZoneCell(idx), new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            TextView edit = new TextView(this);
            edit.setText("编辑");
            edit.setTextSize(12);
            edit.setTextColor(MainActivity.C_SUB);
            edit.setGravity(Gravity.CENTER);
            edit.setPadding(dp(4), dp(6), dp(4), dp(6));
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { editFavorites(); }
            });
            qr.addView(edit, new LinearLayout.LayoutParams(
                    dp(50), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---------- 全部分区 ----------
        TextView hint = new TextView(this);
        hint.setText("全部分区");
        hint.setTextSize(15);
        hint.setTextColor(MainActivity.C_FG);
        hint.setPadding(dp(16), dp(18), dp(16), dp(4));
        content.addView(hint);

        LinearLayout row = null;
        for (int i = 0; i < Z_NAMES.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(6), dp(8), dp(6), dp(8));
                content.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(zoneCell(i), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** 解析收藏索引字符串 */
    int[] parseFavs(String s) {
        if (s == null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        java.util.ArrayList<Integer> list = new java.util.ArrayList<Integer>();
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 0 && v < Z_NAMES.length) list.add(v);
            } catch (Throwable ignore) { }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    /** 弹出多选对话框编辑快捷访问 */
    void editFavorites() {
        final int[] favs = parseFavs(Prefs.zoneFavorites(this));
        final boolean[] checked = new boolean[Z_NAMES.length];
        for (int f : favs) checked[f] = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("编辑快捷访问")
                .setMultiChoiceItems(Z_NAMES, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(android.content.DialogInterface d,
                                                          int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("完成", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(i);
                            }
                        }
                        Prefs.setZoneFavorites(ZoneActivity.this, sb.toString());
                        showGrid();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 大图标分区格（全部分区用） */
    View zoneCell(final int idx) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClickable(true);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openZone(idx); }
        });
        TextView icon = new TextView(this);
        icon.setText(Z_ICONS[idx]);
        icon.setTextSize(20);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Z_COLORS[idx], 15));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView name = new TextView(this);
        name.setText(Z_NAMES[idx]);
        name.setTextSize(12.5f);
        name.setTextColor(MainActivity.C_FG);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(7), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** 小图标分区格（快捷访问用） */
    View smallZoneCell(final int idx) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClickable(true);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openZone(idx); }
        });
        TextView icon = new TextView(this);
        icon.setText(Z_ICONS[idx]);
        icon.setTextSize(16);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Z_COLORS[idx], 12));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = new TextView(this);
        name.setText(Z_NAMES[idx]);
        name.setTextSize(11);
        name.setTextColor(MainActivity.C_FG);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(5), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** 打开某分区的排行榜列表 */
    void openZone(final int idx) {
        content.removeAllViews();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(10));
        ImageView back = Ic.view(this, "back", MainActivity.C_FG, 20);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGrid(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = new TextView(this);
        t.setText(Z_NAMES[idx] + " · 排行榜");
        t.setTextSize(16);
        t.setTextColor(MainActivity.C_FG);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(MainActivity.C_SUB);
        loading.setPadding(dp(20), dp(30), dp(20), dp(20));
        content.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int rid = Z_RIDS[idx];
        new Thread(new Runnable() { @Override public void run() {
            final String[] err = {null};
            final JSONArray[] list = {null};
            try { list[0] = BiliApi.ranking(rid); }
            catch (Throwable e) { err[0] = e.getMessage(); }
            ui.post(new Runnable() { @Override public void run() {
                content.removeView(loading);
                if (err[0] != null || list[0] == null || list[0].length() == 0) {
                    TextView tv = new TextView(ZoneActivity.this);
                    tv.setText("该分区暂时加载失败：" + (err[0] == null ? "空列表" : err[0]));
                    tv.setTextColor(MainActivity.C_SUB);
                    tv.setPadding(dp(20), dp(30), dp(20), dp(20));
                    content.addView(tv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return;
                }
                for (int i = 0; i < list[0].length(); i++) {
                    JSONObject o = list[0].optJSONObject(i);
                    if (o == null) continue;
                    content.addView(videoRow(o));
                }
            } });
        } }, "bxq-zone").start();
    }

    /** 单条视频行：封面 + 标题 + UP/播放 */
    View videoRow(final JSONObject o) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String bv = o.optString("bvid", "");
                    if (bv.isEmpty()) bv = "av" + o.optLong("aid", 0);
                    Intent it = new Intent(ZoneActivity.this, PlayActivity.class);
                    it.putExtra("bvid", bv);
                    startActivity(it);
                } catch (Throwable ignore) { }
            }
        });

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(MainActivity.C_CARD, 6));
        String pic = o.optString("pic", "");
        if (!pic.isEmpty()) ImgLoader.load(cover, pic, dp(112), dp(64));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(112), dp(64));
        cp.rightMargin = dp(10);
        row.addView(cover, cp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(BiliApi.cleanTitle(o.optString("title", "")));
        title.setTextSize(13.5f);
        title.setTextColor(MainActivity.C_FG);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StringBuilder sub = new StringBuilder();
        JSONObject owner = o.optJSONObject("owner");
        if (owner != null) {
            String nm = owner.optString("name", "");
            if (!nm.isEmpty()) sub.append("UP · ").append(nm);
        }
        JSONObject stat = o.optJSONObject("stat");
        long play = stat != null ? stat.optLong("view", 0) : 0;
        if (play > 0) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(VideoItem.views(play)).append("播放");
        }
        TextView s = new TextView(this);
        s.setText(sub.toString());
        s.setTextSize(11);
        s.setTextColor(MainActivity.C_SUB);
        s.setPadding(0, dp(6), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
