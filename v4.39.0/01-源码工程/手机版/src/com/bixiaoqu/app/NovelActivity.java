package com.bixiaoqu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 【v4.14.1】小说吧：内置正版书城（网址导航）+ 书源聚合阅读 + 护眼模式。
 *
 * 合规：App 内置的是起点/番茄/七猫等正版小说平台的官方网址入口（书签导航），
 * 不内置任何盗版内容、不破解登录、不抓取受版权保护的内容；书源聚合仅提供通用规则框架，
 * 书源由用户自行添加，来源合法性由用户自负。
 */
public class NovelActivity extends Activity {

    final Handler ui = new Handler(Looper.getMainLooper());

    // 护眼配色：0 普通 / 1 米黄 / 2 豆沙绿 / 3 夜间（配色见 eyeColors()）

    LinearLayout root;
    LinearLayout content;   // 主体区（各 tab 内容挂这里）
    TextView tabStore, tabShelf, tabSearch, tabSource, tabEye;
    int curTab = 4;

    ArrayList<NovelUtil.Source> sources = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            sources = NovelUtil.loadSources(this);
            // 【v4.14.5】首次进入（用户尚未添加任何书源）时，导入 5 个正版平台内置书源
            if (sources.isEmpty()) {
                sources = NovelUtil.builtinSources();
                NovelUtil.saveSources(this, sources);
            }
            setContentView(build());
            switchTab(4);   // 默认进「书城」（内置正版网址）
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("小说吧打开失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(20), dp(60), dp(20), dp(20));
            setContentView(tv);
        }
    }

    int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v); }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    // 护眼配色：返回 {背景, 正文, 次要, 强调底}
    int[] eyeColors() {
        int mode = Prefs.novelEyeMode(this);
        switch (mode) {
            case 1: return new int[]{0xFFF5E6C8, 0xFF4A3B22, 0xFF8A7550, 0xFFE8D3A0}; // 米黄
            case 2: return new int[]{0xFFC7EDCC, 0xFF23331F, 0xFF5C7256, 0xFFA9D3AE}; // 豆沙绿
            case 3: return new int[]{0xFF121418, 0xFFC8CCD4, 0xFF7A808C, 0xFF2A2F38}; // 夜间
            default:
                boolean dark = MainActivity.DARK;
                return dark ? new int[]{0xFF16181D, 0xFFE8EAF0, 0xFF9AA0AB, 0xFF242830}
                        : new int[]{0xFFF7F7F8, 0xFF18191C, 0xFF61666D, 0xFFEDEFF2};
        }
    }

    int bgColor() { return eyeColors()[0]; }
    int fgColor() { return eyeColors()[1]; }
    int subColor() { return eyeColors()[2]; }

    View build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor());

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(12), dp(6));
        android.widget.ImageView back = Ic.view(this, "back", fgColor(), 22);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(txt("小说吧", fgColor(), 18, true));
        titleCol.addView(txt("正版书城 · 护眼阅读", subColor(), 10.5f, false));
        top.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        // tab 行
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(6), dp(4), dp(6), dp(6));
        tabStore = tabView("书城", 4);
        tabShelf = tabView("书架", 0);
        tabSearch = tabView("搜索", 1);
        tabSource = tabView("书源", 2);
        tabEye = tabView("护眼", 3);
        tabs.addView(tabStore, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabShelf, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabSearch, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabSource, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabEye, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    TextView tabView(String name, final int idx) {
        TextView t = txt(name, idx == 4 ? Theme.c(0xFFFB7299) : subColor(), 13.5f, idx == 4);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(idx); }
        });
        return t;
    }

    void switchTab(int idx) {
        curTab = idx;
        TextView[] tabs = {tabStore, tabShelf, tabSearch, tabSource, tabEye};
        int[] tabIdx = {4, 0, 1, 2, 3};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(tabIdx[i] == idx ? Theme.c(0xFFFB7299) : subColor());
            tabs[i].setTypeface(tabIdx[i] == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        root.setBackgroundColor(bgColor());
        if (idx == 4) buildStore();
        else if (idx == 0) buildShelf();
        else if (idx == 1) buildSearch();
        else if (idx == 2) buildSources();
        else buildEye();
    }

    TextView txt(String s, int color, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ============================================================ 书城（内置正版网址导航）

    /** 内置的正版小说平台网址入口（书签导航，不含任何盗版内容/登录破解） */
    static final String[][] BUILTIN_SITES = {
            {"起点中文网", "https://www.qidian.com/", "阅文旗下正版小说平台"},
            {"番茄免费小说", "https://fanqienovel.com/", "免费正版小说"},
            {"七猫免费小说", "https://www.qimao.com/", "免费正版小说"},
            {"晋江文学城", "https://www.jjwxc.net/", "正版言情/原创"},
            {"纵横中文网", "https://www.zongheng.com/", "正版玄幻/都市"},
            {"17K小说网", "https://www.17k.com/", "正版原创小说"},
            {"书旗小说", "https://www.shuqi.com/", "阿里正版阅读"},
            {"QQ阅读", "https://book.qq.com/", "阅文正版阅读"},
            {"掌阅iReader", "https://www.ireader.com/", "正版电子书阅读"},
            {"小米小说", "https://reader.mi.com/", "正版阅读"},
    };

    void buildStore() {
        TextView hint = txt("内置正版书城 · 点按即可在 App 内阅读（自动套用护眼模式）",
                subColor(), 11.5f, false);
        hint.setPadding(dp(14), dp(8), dp(14), dp(4));
        content.addView(hint);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(8));
        for (int i = 0; i < BUILTIN_SITES.length; i++) {
            final String name = BUILTIN_SITES[i][0];
            final String url = BUILTIN_SITES[i][1];
            final String desc = BUILTIN_SITES[i][2];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(11), dp(14), dp(11));
            row.setBackground(round(eyeColors()[3], 12));
            row.addView(txt(name, fgColor(), 14.5f, true));
            row.addView(txt(desc + "  ·  " + url, subColor(), 11, false));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent it = new Intent(NovelActivity.this, NovelWebActivity.class);
                        it.putExtra("url", url);
                        it.putExtra("title", name);
                        startActivity(it);
                    } catch (Throwable e) {
                        Toast.makeText(NovelActivity.this, "打开失败：" + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(7);
            box.addView(row, rp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = txt("说明：以上为正版平台的官方网址入口，是否免费/需登录以各网站为准。" +
                "App 不提供盗版内容，也不破解任何登录限制。", subColor(), 10.5f, false);
        note.setPadding(dp(14), dp(4), dp(14), dp(10));
        content.addView(note);
    }

    // ============================================================ 书架

    void buildShelf() {
        final ArrayList<NovelUtil.Book> shelf = NovelUtil.loadShelf(this);
        if (shelf.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            TextView empty = txt("书架还是空的\n去「搜索」找本书加入书架吧", subColor(), 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(6), 1f);
            box.addView(empty);
            content.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return;
        }
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final ArrayList<NovelUtil.Book> data = shelf;
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return data.size(); }
            @Override public Object getItem(int i) { return data.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup p) {
                NovelUtil.Book bk = data.get(i);
                LinearLayout row = new LinearLayout(NovelActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable g = round(eyeColors()[3], 12);
                row.setBackground(g);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(7);
                TextView name = txt(bk.name, fgColor(), 14, true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name);
                int prog = NovelUtil.loadProgress(NovelActivity.this, bk.key());
                TextView sub = txt((bk.author.isEmpty() ? "佚名" : bk.author)
                        + " · " + bk.sourceName + " · 读到第 " + (prog + 1) + " 章", subColor(), 11, false);
                sub.setPadding(0, dp(3), 0, 0);
                row.addView(sub);
                return row;
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                openBook(data.get(i));
            }
        });
        lv.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                final NovelUtil.Book bk = data.get(i);
                new AlertDialog.Builder(NovelActivity.this)
                        .setTitle(bk.name)
                        .setItems(new String[]{"移出书架"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                NovelUtil.removeShelf(NovelActivity.this, bk);
                                switchTab(0);
                                Toast.makeText(NovelActivity.this, "已移出书架", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            }
        });
        content.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ============================================================ 搜索

    void buildSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText et = new EditText(this);
        et.setHint("输入书名");
        et.setTextColor(fgColor());
        et.setSingleLine(true);
        row.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = txt("搜索", Color.WHITE, 13, true);
        go.setPadding(dp(16), dp(9), dp(16), dp(9));
        go.setBackground(round(Theme.c(0xFFFB7299), 18));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String key = et.getText().toString().trim();
                if (key.isEmpty()) { Toast.makeText(NovelActivity.this, "请输入书名", Toast.LENGTH_SHORT).show(); return; }
                if (sources.isEmpty()) {
                    Toast.makeText(NovelActivity.this, "还没有书源，请先到「书源」添加", Toast.LENGTH_LONG).show();
                    switchTab(2);
                    return;
                }
                doSearch(key);
            }
        });
        row.addView(go, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row);

        TextView tip = txt("搜索会依次访问你添加的书源（无需登录）；结果仅为公开网页内容索引。",
                subColor(), 10.5f, false);
        tip.setPadding(0, dp(8), 0, dp(4));
        box.addView(tip);
        content.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 结果区
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(results, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        results.addView(txt("输入书名开始搜索", subColor(), 12, false));
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        searchResults = results;
    }

    LinearLayout searchResults;

    void doSearch(final String key) {
        searchResults.removeAllViews();
        searchResults.addView(txt("搜索中…", subColor(), 12, false));
        final ArrayList<NovelUtil.Book> out = new ArrayList<>();
        new Thread(new Runnable() {
            @Override public void run() {
                for (final NovelUtil.Source s : sources) {
                    if (!s.usable()) continue;
                    try {
                        String url = NovelUtil.fillKey(s.searchUrl, key);
                        String html = NovelUtil.httpGet(url, s.charset);
                        ArrayList<String> items = NovelUtil.matchAll(html, s.searchItem);
                        for (String item : items) {
                            NovelUtil.Book bk = new NovelUtil.Book();
                            bk.sourceName = s.name;
                            bk.name = cleanOne(NovelUtil.matchOne(item, s.searchName));
                            bk.author = cleanOne(NovelUtil.matchOne(item, s.searchAuthor));
                            String rel = NovelUtil.matchOne(item, s.searchLink);
                            bk.url = NovelUtil.absUrl(s.url, rel);
                            if (!bk.name.isEmpty() && !bk.url.isEmpty()) out.add(bk);
                        }
                    } catch (Throwable ignore) { }
                }
                ui.post(new Runnable() {
                    @Override public void run() { renderSearchResults(out, key); }
                });
            }
        }).start();
    }

    void renderSearchResults(final ArrayList<NovelUtil.Book> list, String key) {
        searchResults.removeAllViews();
        if (list.isEmpty()) {
            searchResults.addView(txt("没有找到结果，可换个关键词或检查书源规则", subColor(), 12, false));
            return;
        }
        searchResults.addView(txt("找到 " + list.size() + " 条结果（点击加入书架并阅读）", subColor(), 11, false));
        for (final NovelUtil.Book bk : list) {
            TextView t = txt("《" + bk.name + "》  " + (bk.author.isEmpty() ? "" : bk.author)
                    + "\n来源：" + bk.sourceName, fgColor(), 13.5f, false);
            t.setPadding(dp(10), dp(11), dp(10), dp(11));
            t.setBackground(round(eyeColors()[3], 10));
            t.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    NovelUtil.addShelf(NovelActivity.this, bk);
                    openBook(bk);
                }
            });
            searchResults.addView(t, lp);
        }
    }

    // ============================================================ 书源管理

    void buildSources() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(6));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = txt("+ 添加书源", Color.WHITE, 12.5f, true);
        add.setPadding(dp(14), dp(8), dp(14), dp(8));
        add.setBackground(round(Theme.c(0xFFFB7299), 16));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editSource(null); }
        });
        TextView guide = txt("规则说明", Theme.c(0xFFFB7299), 12.5f, false);
        guide.setPadding(dp(14), dp(8), dp(14), dp(8));
        guide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGuide(); }
        });
        // 【v4.14.5】一键恢复 5 个内置正版书源
        TextView restore = txt("恢复内置", Theme.c(0xFFFB7299), 12.5f, false);
        restore.setPadding(dp(14), dp(8), dp(2), dp(8));
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ArrayList<NovelUtil.Source> builtin = NovelUtil.builtinSources();
                int added = 0;
                for (NovelUtil.Source bs : builtin) {
                    boolean exists = false;
                    for (NovelUtil.Source s : sources) if (s.name.equals(bs.name)) { exists = true; break; }
                    if (!exists) { sources.add(bs); added++; }
                }
                NovelUtil.saveSources(NovelActivity.this, sources);
                Toast.makeText(NovelActivity.this,
                        added > 0 ? "已恢复 " + added + " 个内置书源" : "内置书源已存在",
                        Toast.LENGTH_SHORT).show();
                switchTab(2);
            }
        });
        btns.addView(add);
        btns.addView(guide);
        btns.addView(restore);
        box.addView(btns);
        box.addView(txt("共 " + sources.size() + " 个书源（已内置 5 个正版平台公开检索源，可编辑/删除）",
                subColor(), 10.5f, false));
        content.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return sources.size(); }
            @Override public Object getItem(int i) { return sources.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup p) {
                NovelUtil.Source s = sources.get(i);
                LinearLayout row = new LinearLayout(NovelActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(11), dp(14), dp(11));
                row.setBackground(round(eyeColors()[3], 10));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(6);
                TextView name = txt(s.name + (s.usable() ? "" : "（规则不完整）"),
                        fgColor(), 13.5f, true);
                row.addView(name);
                TextView sub = txt(s.url + (s.charset.isEmpty() ? "" : " · " + s.charset),
                        subColor(), 10.5f, false);
                sub.setPadding(0, dp(3), 0, 0);
                row.addView(sub);
                return row;
            }
        });
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                editSource(sources.get(i));
            }
        });
        lv.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int i, long id) {
                final NovelUtil.Source s = sources.get(i);
                new AlertDialog.Builder(NovelActivity.this)
                        .setTitle(s.name)
                        .setItems(new String[]{"删除书源"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                sources.remove(s);
                                NovelUtil.saveSources(NovelActivity.this, sources);
                                switchTab(2);
                            }
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            }
        });
        content.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void editSource(final NovelUtil.Source existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView intro = txt("粘贴书源 JSON（示例：点「规则说明」查看字段）。\n"
                + "已内置 5 个正版平台公开检索源，自定义书源合法性由你自行判断。", subColor(), 11.5f, false);
        box.addView(intro);
        final EditText et = new EditText(this);
        et.setText(existing == null ? NovelUtil.sampleSourceJson() : existing.toJson().toString());
        et.setTextColor(fgColor());
        et.setGravity(Gravity.TOP);
        et.setMinLines(8);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加书源" : "编辑书源")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            org.json.JSONObject o = new org.json.JSONObject(et.getText().toString());
                            NovelUtil.Source s = NovelUtil.Source.fromJson(o);
                            if (s.name.isEmpty()) { Toast.makeText(NovelActivity.this, "请填写 name", Toast.LENGTH_SHORT).show(); return; }
                            if (existing == null) sources.add(s);
                            else {
                                int idx = sources.indexOf(existing);
                                if (idx >= 0) sources.set(idx, s);
                            }
                            NovelUtil.saveSources(NovelActivity.this, sources);
                            switchTab(2);
                            Toast.makeText(NovelActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                        } catch (Throwable e) {
                            Toast.makeText(NovelActivity.this, "JSON 格式错误", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void showGuide() {
        String guide = "书源 JSON 字段说明（正则用捕获组提取）：\n\n"
                + "name 书源名；url 站点根地址；charset 编码(默认UTF-8)；\n"
                + "searchUrl 搜索地址，用 {{key}} 代表关键词；\n"
                + "searchItem 每条搜索结果的正则；\n"
                + "searchName / searchAuthor / searchLink 从每条结果里提取 书名/作者/链接；\n"
                + "tocItem 目录每条的正则；tocName / tocLink 提取 章节名/链接；\n"
                + "content 正文的正则。\n\n"
                + "提示：{{key}} 会自动做 URL 编码；相对链接会按 url 补全；\n"
                + "正文会自动去除 HTML 标签并分段。请按实际网页结构调整正则。\n\n"
                + "合规：仅添加你有权访问的公开来源，App 不提供任何盗版内容。";
        new AlertDialog.Builder(this)
                .setTitle("书源规则说明")
                .setMessage(guide)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ============================================================ 护眼设置

    void buildEye() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(18));

        box.addView(txt("护眼模式", fgColor(), 16, true));
        TextView sub = txt("选择适合长时间阅读的背景色", subColor(), 11.5f, false);
        sub.setPadding(0, dp(3), 0, dp(12));
        box.addView(sub);

        final String[] modes = {"普通（跟随主题）", "米黄（护眼）", "豆沙绿（护眼）", "夜间"};
        for (int i = 0; i < modes.length; i++) {
            final int mi = i;
            TextView t = txt(modes[i], i == Prefs.novelEyeMode(this) ? Color.WHITE : fgColor(), 13.5f, false);
            t.setPadding(dp(16), dp(12), dp(16), dp(12));
            int[] c = eyeColorsOf(i);
            GradientDrawable g = round(c[0], 10);
            g.setStroke(1, c[1]);
            t.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setNovelEyeMode(NovelActivity.this, mi);
                    refreshRootBg();
                    switchTab(3);
                }
            });
            box.addView(t, lp);
        }

        box.addView(txt("正文字号", fgColor(), 15, true));
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(0, dp(10), 0, dp(16));
        TextView minus = txt("A−", fgColor(), 16, true);
        minus.setPadding(dp(16), dp(8), dp(16), dp(8));
        minus.setBackground(round(eyeColors()[3], 10));
        final TextView sizeLabel = txt(Prefs.novelTextSize(this) + " sp", Theme.c(0xFFFB7299), 14, true);
        sizeLabel.setPadding(dp(16), 0, dp(16), 0);
        TextView plus = txt("A＋", fgColor(), 16, true);
        plus.setPadding(dp(16), dp(8), dp(16), dp(8));
        plus.setBackground(round(eyeColors()[3], 10));
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setNovelTextSize(NovelActivity.this, Prefs.novelTextSize(NovelActivity.this) - 1);
                sizeLabel.setText(Prefs.novelTextSize(NovelActivity.this) + " sp");
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setNovelTextSize(NovelActivity.this, Prefs.novelTextSize(NovelActivity.this) + 1);
                sizeLabel.setText(Prefs.novelTextSize(NovelActivity.this) + " sp");
            }
        });
        sizeRow.addView(minus);
        sizeRow.addView(sizeLabel);
        sizeRow.addView(plus);
        box.addView(sizeRow);

        box.addView(txt("字号在阅读页也会生效。护眼设置自动保存。", subColor(), 11, false));
        sv.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    int[] eyeColorsOf(int mode) {
        switch (mode) {
            case 1: return new int[]{0xFFF5E6C8, 0xFF4A3B22};
            case 2: return new int[]{0xFFC7EDCC, 0xFF23331F};
            case 3: return new int[]{0xFF121418, 0xFFC8CCD4};
            default: return new int[]{0xFFEDEFF2, 0xFF9AA0AB};
        }
    }

    void refreshRootBg() { if (root != null) root.setBackgroundColor(bgColor()); }

    // ============================================================ 目录 / 阅读

    void openBook(final NovelUtil.Book bk) {
        NovelUtil.Source src = null;
        for (NovelUtil.Source s : sources) if (s.name.equals(bk.sourceName)) { src = s; break; }
        if (src == null || src.tocItem.isEmpty()) {
            Toast.makeText(this, "该书源未配置目录规则，无法阅读", Toast.LENGTH_LONG).show();
            return;
        }
        final NovelUtil.Source source = src;
        Toast.makeText(this, "正在获取目录…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<NovelUtil.Chapter> chapters = new ArrayList<>();
                try {
                    String html = NovelUtil.httpGet(bk.url, source.charset);
                    ArrayList<String> items = NovelUtil.matchAll(html, source.tocItem);
                    for (String item : items) {
                        NovelUtil.Chapter ch = new NovelUtil.Chapter();
                        ch.name = cleanOne(NovelUtil.matchOne(item, source.tocName));
                        ch.url = NovelUtil.absUrl(bk.url, NovelUtil.matchOne(item, source.tocLink));
                        if (!ch.name.isEmpty() && !ch.url.isEmpty()) chapters.add(ch);
                    }
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(NovelActivity.this, "获取目录失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override public void run() { showCatalog(bk, source, chapters); }
                });
            }
        }).start();
    }

    void showCatalog(final NovelUtil.Book bk, final NovelUtil.Source source,
                     final ArrayList<NovelUtil.Chapter> chapters) {
        if (chapters.isEmpty()) {
            Toast.makeText(this, "未解析到章节，请检查目录规则", Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("《" + bk.name + "》 共 " + chapters.size() + " 章");
        String[] names = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) names[i] = (i + 1) + ". " + chapters.get(i).name;
        b.setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                NovelUtil.saveProgress(NovelActivity.this, bk.key(), which);
                openChapter(bk, source, chapters, which);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    void openChapter(final NovelUtil.Book bk, final NovelUtil.Source source,
                     final ArrayList<NovelUtil.Chapter> chapters, final int index) {
        final LinearLayout reading = new LinearLayout(this);
        reading.setOrientation(LinearLayout.VERTICAL);
        reading.setBackgroundColor(bgColor());
        final TextView title = txt(chapters.get(index).name, fgColor(), 17, true);
        title.setPadding(dp(18), dp(16), dp(18), dp(8));
        reading.addView(title);
        final TextView body = txt("正在加载正文…", fgColor(), Prefs.novelTextSize(this), false);
        body.setLineSpacing(dp(6), 1.35f);
        body.setTextColor(fgColor());
        body.setPadding(dp(18), dp(6), dp(18), dp(18));
        final ScrollView sv = new ScrollView(this);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reading.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部翻页
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(12), dp(8), dp(12), dp(10));
        TextView prev = txt("← 上一章", fgColor(), 13, false);
        prev.setPadding(dp(14), dp(9), dp(14), dp(9));
        prev.setBackground(round(eyeColors()[3], 14));
        TextView next = txt("下一章 →", fgColor(), 13, false);
        next.setPadding(dp(14), dp(9), dp(14), dp(9));
        next.setBackground(round(eyeColors()[3], 14));
        TextView back = txt("目录", Theme.c(0xFFFB7299), 13, true);
        back.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nav.addView(prev, np);
        nav.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nav.addView(next, np);
        reading.addView(nav);
        setContentView(reading);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (index > 0) {
                    NovelUtil.saveProgress(NovelActivity.this, bk.key(), index - 1);
                    openChapter(bk, source, chapters, index - 1);
                } else Toast.makeText(NovelActivity.this, "已经是第一章", Toast.LENGTH_SHORT).show();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (index < chapters.size() - 1) {
                    NovelUtil.saveProgress(NovelActivity.this, bk.key(), index + 1);
                    openChapter(bk, source, chapters, index + 1);
                } else Toast.makeText(NovelActivity.this, "已经是最后一章", Toast.LENGTH_SHORT).show();
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCatalog(bk, source, chapters); }
        });

        // 加载正文
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] res = new String[1];
                try {
                    String html = NovelUtil.httpGet(chapters.get(index).url, source.charset);
                    String raw = source.content.isEmpty() ? html : NovelUtil.matchOne(html, source.content);
                    if (raw.isEmpty()) raw = html;
                    res[0] = NovelUtil.cleanHtml(raw);
                    if (res[0].isEmpty()) res[0] = "未能解析出正文，请检查书源的 content 规则。";
                } catch (final Throwable e) {
                    res[0] = "加载正文失败：" + e.getMessage();
                }
                ui.post(new Runnable() {
                    @Override public void run() { body.setText(res[0]); }
                });
            }
        }).start();
    }

    @Override public void onBackPressed() {
        // 阅读页按返回回到书架视图
        if (content != null && content.getParent() == null) {
            setContentView(build());
            switchTab(4);   // 阅读页返回书城首页
        } else {
            super.onBackPressed();
        }
    }

    static String cleanOne(String s) {
        if (s == null) return "";
        return NovelUtil.cleanHtml(s).replace("\n", " ").trim();
    }
}
