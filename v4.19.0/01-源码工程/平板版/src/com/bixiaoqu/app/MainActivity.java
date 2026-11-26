package com.bixiaoqu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 哔小趣 · B 站轻量客户端
 * 底部三栏：推荐 / 我的 / 关注；顶部搜索；无广告、清爽界面。
 * 推荐页：封面图列表 + 下拉刷新（手写，不依赖 androidx SwipeRefreshLayout）
 */
public class MainActivity extends Activity {

    static final String VERSION = BxqApp.VERSION;

    // 【v4.10.0】改为可变静态字段：所有页面在 onCreate 里调 applyTheme() 重算，
    // 切主题后 recreate 就能整体换肤（原来是 final 常量，切了也不变）。
    static int C_BG = Color.parseColor("#16181d");
    static int C_CARD = Color.parseColor("#1f2229");
    static int C_FG = Color.parseColor("#e8eaf0");
    static int C_SUB = Color.parseColor("#9aa0ab");
    static int C_ACC = Color.parseColor("#fb7299");
    /** 当前是否为深色（浅色下底栏、卡片、分隔线要用另一套颜色） */
    static boolean DARK = true;
    /** 本页创建时的主题指纹 */
    int appliedStamp = -1;
    /** 因切主题而重建时，记住原来停在哪一页 */
    static int restoreTab = -1;

    // ===== 「我的」空间页控件（v4.10.0）=====
    android.widget.ImageView mineBanner;
    TextView mineSign, mineExp, mineVideoCount;
    View mineExpBarFill;
    LinearLayout mineVideoBox;
    String mineCoinsText = "—";
    /** 主题指纹，用于 onResume 检测变化 → recreate */
    static int themeStamp = 1;

    /** 主题指纹：mode*10 + 是否深色。变了就说明要重建界面。 */
    static int themeStampOf(android.content.Context c) {
        int mode = Prefs.themeMode(c);
        boolean dark;
        if (mode == Prefs.THEME_LIGHT) {
            dark = false;
        } else if (mode == Prefs.THEME_DARK) {
            dark = true;
        } else {
            int ui = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return mode * 10 + (dark ? 1 : 0);
    }

    /** 按设置算出这一屏的配色。任何 Activity 创建 UI 之前必须调用一次。 */
    static void applyTheme(android.content.Context c) {
        int st = themeStampOf(c);
        boolean dark = (st % 10) == 1;
        DARK = dark;
        C_ACC = Color.parseColor("#fb7299");
        if (dark) {
            // 【v4.17.0】深色：底色更沉一点、卡片略提亮一档，
            // 两者对比更小 → 整体更"软"，不再是一块块生硬的深灰。
            C_BG = Color.parseColor("#15171c");
            C_CARD = Color.parseColor("#1e2128");
            C_FG = Color.parseColor("#e8eaf0");
            C_SUB = Color.parseColor("#9aa0ab");
        } else {
            C_BG = Color.parseColor("#f4f5f7");
            C_CARD = Color.parseColor("#ffffff");
            C_FG = Color.parseColor("#18191c");
            C_SUB = Color.parseColor("#61666d");
        }
        // 【v4.17.0】封面占位色跟随主题：柔和的灰蓝，代替原来刺眼的 #2a2e36
        ImgLoader.setPlaceholderColor(dark ? Color.parseColor("#242833") : Color.parseColor("#e9ebf0"));
        themeStamp = st;
    }

    LinearLayout content;
    LiquidNavBar nav;                    // 液态玻璃悬浮底栏
    LiquidLensLayer lensLayer;           // 全屏液态透镜（光晕可溢出胶囊）
    // 底部三栏：首页 / 动态 / 我的
    TextView[] tabs = new TextView[5];
    final String[] tabNames = {"首页", "动态", "", "", "我的"};
    ImageView[] tabIcons = new ImageView[5];
    /** 底栏每个 tab 的胶囊容器（选中时点亮）；下标 2/3 已无入口 */
    final LinearLayout[] navPills = new LinearLayout[5];
    /** 全局顶栏：动态页会把它藏起来，用页面自带的「关注」标题 */
    LinearLayout topBarView;
    /** 关注动态流（底栏第二格） */
    DynFeedView dynFeed;
    int currentTab = 0;

    // 顶部频道栏（推荐/热门/动画/影视/新番，已去掉直播）
    final String[] channels = {"推荐", "热门", "动画", "影视", "新番"};
    final int[] channelRid = {0, -2, 1, 181, 13};
    LinearLayout channelBar;
    TextView[] channelViews = new TextView[5];
    int currentChannel = 0;   // 默认选中「推荐」

    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<VideoItem> rows = new ArrayList<>();

    TextView mineView;
    android.widget.AbsListView listView;
    /** 【v4.17.0】首页推荐列表的适配器：滚动预取封面时要用它的真实卡片尺寸 */
    FeedAdapter feedAdapter;
    TextView refreshTip;
    // 【v4.13.4】首页「推荐」列表视图缓存：点到动态/我的再切回来时，直接复用原视图，
    // 不再 rows.clear() + newList() + loadChannel()，避免视频整批刷新、滚动位置丢失。
    View homeRootCache;                 // 缓存的首页列表容器（PullFrame）
    TextView homeRefreshTipCache;       // 缓存视图里的下拉刷新提示
    boolean homeCacheValid = false;     // 首页是否已加载过（有缓存可复用）
    int homeScrollPos = 0, homeScrollTop = 0;  // 离开首页前的滚动位置
    Button loginTop;
    ImageView avatarTop;              // 首页顶栏头像
    boolean appliedTwoCol = false;

    // 下拉刷新状态
    boolean pullArmed = false;
    boolean refreshing = false;
    float pullStartY = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.applyTheme(this);
        appliedStamp = themeStamp;
        try {
            loadLoginCookies();
            lastOrientation = getResources().getConfiguration().orientation;
            buildUi();
            switchTab(0);
            handleShare(getIntent());
            CrashHandler.note("boot", "MainActivity.onCreate OK");
        } catch (Throwable e) {
            // 兜底：启动任何一步出错都不闪退，直接把错误显示在屏幕上
            CrashHandler.note("fatal", android.util.Log.getStackTraceString(e));
            showErrorScreen(e);
        }
    }    /** 启动失败时显示错误页（不依赖主题/资源，只用最原始的 View） */
    void showErrorScreen(Throwable e) {
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView tv = new TextView(this);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(28, 28, 28, 28);
            tv.setText("启动出错，但不闪退。\n把以下内容截图发我即可定位：\n\n"
                    + android.util.Log.getStackTraceString(e)
                    + "\n\n设备：" + android.os.Build.MODEL
                    + "  SDK：" + android.os.Build.VERSION.SDK_INT);
            sv.addView(tv);
            sv.setBackgroundColor(Color.BLACK);
            setContentView(sv);
        } catch (Throwable ignore) { }
    }

    int lastOrientation = -1;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // 【v4.16.0】横竖屏切换整页重建：列数、顶栏宽度分配、底栏最大宽度、
        // 封面请求尺寸全都跟屏宽绑死，只 switchTab 会留下「横屏了还是两列」的错位。
        boolean rotated = lastOrientation != -1
                && lastOrientation != cfg.orientation;
        lastOrientation = cfg.orientation;
        invalidateHomeCache();
        if (!rotated) {
            // 分屏 / 多窗口拖动只改变窗口尺寸，没换方向：重建太重，重排列表就够
            try { if (currentTab == 0) switchTab(0); } catch (Throwable ignore) { }
            return;
        }
        restoreTab = currentTab;
        try {
            ui.post(new Runnable() { @Override public void run() {
                if (!isFinishing()) recreate();
            } });
        } catch (Throwable ignore) { }
    }

    /** 【v4.16.0】横屏 / 宽屏（平板）判定：宽明显大于高就算 */
    boolean isWide() {
        return isWideScreen(this);
    }

    /**
     * 【v4.16.1】宽屏判定收敛成静态工具，搜索页 / 动态页共用一份，
     * 避免每个页面各写一遍阈值不同的判断。
     */
    static boolean isWideScreen(android.content.Context c) {
        try {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            return dm.widthPixels > dm.heightPixels * 1.12f
                    || dm.widthPixels / dm.density >= 600f;
        } catch (Throwable e) { return false; }
    }

    /**
     * 【v4.16.0】网格列数按屏宽算：
     * 360dp 手机竖屏 → 2 列；411dp → 2 列；780dp 横屏 → 4 列；
     * 800dp 平板 → 4 列；1000dp 以上 → 5 列。单列模式仍然尊重用户设置。
     */
    int calcCols() {
        if (!Prefs.twoColumn(this)) return 1;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float wdp = dm.widthPixels / dm.density;
            int c = (int) Math.floor(wdp / 200f);
            if (c < 2) c = 2;
            if (c > 4) c = 4;   // 上限 4 列：与 B 站 HD 版横屏一致，卡片不至于挤成小方块
            return c;
        } catch (Throwable e) { return 2; }
    }

    /** 【v4.13.4】作废首页视图缓存（旋转/双列设置变化时调用） */
    void invalidateHomeCache() {
        homeRootCache = null;
        homeRefreshTipCache = null;
        homeCacheValid = false;
        homeScrollPos = 0;
        homeScrollTop = 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页改完主题回来：整页重建一次，配色与底栏材质全部换新
        if (appliedStamp != themeStampOf(this)) {
            restoreTab = currentTab;
            recreate();
            return;
        }
        refreshLoginTop();
        loadTopAvatar();
        if (currentTab == 0 && Prefs.twoColumn(this) != appliedTwoCol) {
            // 【v4.13.4】双列开关变了，缓存视图列数不符，作废后重建
            invalidateHomeCache();
            switchTab(0);
            return;
        }
        if (currentTab == 4) refreshMine();
        else if (currentTab == 1 && dynFeed != null) {
            try { dynFeed.refreshOnShow(); } catch (Throwable ignore) { }
        }
        // 【v4.14.4】回到前台内容可能已变，重抓一次液态玻璃背景
        ui.postDelayed(new Runnable() { @Override public void run() { refreshNavBackdrop(); } }, 360);
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    /** 列表里是否有真实条目（全是占位提示时视为需要重载） */
    boolean hasRealRow() {
        for (VideoItem it : rows) if (!it.isPlaceholder()) return true;
        return false;
    }

    /** 刷新顶栏登录态显示 */
    void refreshLoginTop() {
        if (loginTop == null) return;
        String uid = BiliApi.loggedInUid();
        loginTop.setText(uid != null ? "UID " + uid : "登录");
        loginTop.setTextColor(uid != null ? C_ACC : Color.WHITE);
    }

    /** 顶栏头像：异步加载当前登录用户的真实头像，失败显示默认图标 */
    void loadTopAvatar() {
        if (avatarTop == null) return;
        // 默认占位：粉色小人
        avatarTop.setImageDrawable(Ic.drawable(this, "g_avatar", C_ACC));
        if (BiliApi.loggedInUid() == null) return;
        new Thread(new Runnable() { @Override public void run() {
            final String[] face = {null};
            try {
                JSONObject nav = BiliApi.navInfo();
                String f = nav.optString("face", "");
                if (!f.isEmpty()) {
                    f = ImgLoader.https(f);
                    f = ImgLoader.face(f, 80);
                    face[0] = f;
                }
            } catch (Throwable ignore) { }
            final String url = face[0];
            if (url != null) {
                ui.post(new Runnable() { @Override public void run() {
                    if (avatarTop != null) ImgLoader.load(avatarTop, url, dp(36), dp(36));
                } });
            }
        } }).start();
    }

    void loadLoginCookies() {
        SharedPreferences sp = getSharedPreferences("bili_login", MODE_PRIVATE);
        String sess = sp.getString("SESSDATA", "");
        if (!sess.isEmpty()) {
            BiliApi.setLoginCookie(sess, sp.getString("bili_jct", ""),
                    sp.getString("DedeUserID", ""));
        }
        // 【v4.15.9】恢复完整登录 Cookie（含 DedeUserID__ckMd5 / sid），
        // 只恢复三件套会导致重启后会话不被 B 站认可。
        String full = sp.getString("full_cookie", "");
        if (!full.isEmpty()) BiliApi.addCookie(full);
    }

    void handleShare(Intent it) {
        if (it == null || !Intent.ACTION_SEND.equals(it.getAction())) return;
        CharSequence t = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t == null) return;
        String bvid = null;
        try { bvid = BiliApi.extractBvid(t.toString()); } catch (Exception ignore) { }
        if (bvid == null) {
            String av = BiliApi.extractAvid(t.toString());
            if (av != null) bvid = "av" + av;
        }
        if (bvid != null) {
            Intent i = new Intent(this, PlayActivity.class);
            i.putExtra("bvid", bvid);
            startActivity(i);
        }
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        int pad = dp(12);

        // ============ 顶栏：头像 + 搜索框 + 游戏 + 信封 ============
        boolean wide = isWide();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // 横屏时左右留更多边，顶栏不贴屏幕边缘（对齐平板 HD 版观感）
        top.setPadding(wide ? dp(28) : pad, wide ? dp(14) : pad,
                wide ? dp(28) : pad, wide ? dp(8) : (int) (pad * 0.5));

        // 顶栏头像（圆形，加载登录用户真实头像；点击进入个人空间）
        avatarTop = new ImageView(this);
        avatarTop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarTop.setClickable(true);
        // 【v4.17.0】占位底色改柔和灰蓝，和整体配色一致
        avatarTop.setBackgroundColor(DARK ? Color.parseColor("#262A33")
                : Color.parseColor("#E4E7EC"));
        avatarTop.setClipToOutline(true);
        final float avatarRad = 18 * getResources().getDisplayMetrics().density;
        avatarTop.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), avatarRad);
            }
        });
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(36), dp(36));
        avp.rightMargin = dp(10);
        top.addView(avatarTop, avp);
        avatarTop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 【v4.14.7】点击头像 Q 弹一下再直接进「我的」页
                Bounce.pop(v, 1.22f);
                switchTab(4);
            }
        });
        loadTopAvatar();
        if (wide) {
            // 横屏：搜索框不铺满，约占屏宽 45%，右侧留白更接近平板 HD 版
            View spacerL = new View(this);
            top.addView(spacerL, new LinearLayout.LayoutParams(0, 1, 0.35f));
        }
        avatarTop.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;
            }
        });

        // 搜索胶囊
        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        // 【v4.17.0】复用 GlassUtil 的玻璃材质：与搜索页输入框、标签胶囊同一套语言，
        // 顶栏这一处不再单独写一份描边/渐变（少一处重复样式）。
        android.graphics.drawable.GradientDrawable sg = GlassUtil.card(DARK, 19, this);
        search.setBackground(sg);
        search.setPadding(dp(12), 0, dp(10), 0);
        ImageView sIcon = Ic.view(this, "g_search", C_SUB, 15);
        search.addView(sIcon);
        TextView sHint = new TextView(this);
        sHint.setText("搜索你感兴趣的内容");
        sHint.setTextColor(0x99AAB0BC);
        sHint.setTextSize(12.5f);
        sHint.setSingleLine(true);
        sHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shp.leftMargin = dp(8);
        search.addView(sHint, shp);
        top.addView(search, new LinearLayout.LayoutParams(
                0, dp(38), 1f));
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });

        if (wide) {
            View spacerR = new View(this);
            top.addView(spacerR, new LinearLayout.LayoutParams(0, 1, 0.85f));
        }

        // 信封（私信）
        ImageView mail = Ic.glass(this, "g_mail", C_FG, DARK ? 0x14FFFFFF : 0x0F000000, 34, 17);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(34), dp(34));
        mp.leftMargin = dp(7);
        top.addView(mail, mp);
        mail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MsgActivity.class));
            }
        });

        topBarView = top;
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ============ 顶部频道栏 ============
        channelBar = new LinearLayout(this);
        channelBar.setOrientation(LinearLayout.HORIZONTAL);
        channelBar.setGravity(Gravity.CENTER_VERTICAL);
        channelBar.setPadding(dp(8), dp(4), dp(8), dp(6));
        final android.widget.HorizontalScrollView channelScroll = new android.widget.HorizontalScrollView(this);
        channelScroll.setHorizontalScrollBarEnabled(false);
        for (int i = 0; i < channels.length; i++) {
            final int ci = i;
            TextView ch = new TextView(this);
            ch.setText(channels[i]);
            ch.setTextSize(14.5f);
            ch.setGravity(Gravity.CENTER);
            ch.setPadding(dp(11), dp(7), dp(11), dp(9));
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchChannel(ci); }
            });
            channelViews[i] = ch;
            channelBar.addView(ch, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // 频道栏右侧更多按钮 → 【v4.13】分区页（参考 B 站客户端「分区」）
        ImageView more = Ic.view(this, "more", C_SUB, 18);
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ZoneActivity.class));
            }
        });
        LinearLayout.LayoutParams moreP = new LinearLayout.LayoutParams(dp(30), dp(34));
        more.setPadding(dp(6), 0, 0, 0);
        channelBar.addView(more, moreP);
        channelScroll.addView(channelBar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (wide) {
            // 横屏/平板：标签整行居中（对齐 HD 版「直播 推荐 热门 …」的排布）
            // 必须给 channelBar 一个至少等于屏宽的最小宽，否则 WRAP_CONTENT 下
            // CENTER_HORIZONTAL 没有可分配空间，看起来还是左对齐。
            channelScroll.setFillViewport(true);
            channelBar.setMinimumWidth(getResources().getDisplayMetrics().widthPixels);
            channelBar.setGravity(Gravity.CENTER_HORIZONTAL);
            channelBar.setPadding(dp(8), dp(6), dp(8), dp(8));
        }
        root.addView(channelScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshChannelStyle();

        // ============ 内容区 ============
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ============ 底部胶囊 · 玻璃光影（首页 / 动态 / 我的）============
        // 【v4.10.6】整条压矮到 52dp（约 0.8cm）：原来 66dp 视觉偏厚，
        // 现在图标 20 / 文字 9.5sp / 胶囊 46dp，仍保持三格与玻璃光影。
        // 【v4.10.4】按参考图重做，三格，去掉加号与消息：
        //   · 底栏本体是同色系的深灰玻璃，不是近黑，避免糊成一块死黑；
        //   · 选中项套一枚明显亮一档的圆角胶囊，图标与文字同时转粉；
        //   · 未选中项用中灰，保持安静；
        //   · 玻璃层次（顶部受光、上棱高光、外描边、外投影）仍由 LiquidNavBar 画。
        LiquidNavBar nav = new LiquidNavBar(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(3), dp(6), dp(3));
        final int[] order = {0, 1, 4};          // 首页 / 动态 / 我的
        for (int slot = 0; slot < order.length; slot++) {
            final int idx = order[slot];
            FrameLayout cellWrap = new FrameLayout(this);

            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER_HORIZONTAL);
            pill.setPadding(dp(2), dp(3), dp(2), dp(4));
            navPills[idx] = pill;

            LinearLayout iconRow = new LinearLayout(this);
            iconRow.setOrientation(LinearLayout.HORIZONTAL);
            iconRow.setGravity(Gravity.CENTER);
            pill.addView(iconRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

            int initColor = (idx == currentTab) ? C_ACC : navOffColor();
            ImageView ic = Ic.view(this, tabIconName(idx), initColor, 20);
            tabIcons[idx] = ic;
            iconRow.addView(ic);

            TextView lb = new TextView(this);
            lb.setText(tabNames[idx]);
            lb.setTextColor(initColor);
            lb.setTextSize(9.5f);
            lb.setGravity(Gravity.CENTER);
            lb.setIncludeFontPadding(false);
            tabs[idx] = lb;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            pill.addView(lb, lp);

            // 选中态：圆角胶囊铺满整格（左右各留 4dp 缝），高度占满内区
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER);
            plp.leftMargin = dp(4);
            plp.rightMargin = dp(4);
            cellWrap.addView(pill, plp);
            if (idx == currentTab) pill.setBackground(navPillBg());

            cellWrap.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 【v4.14.7】底栏点击 Q 弹反馈：放大更明显、回弹更 Q、带余振
                    Bounce.pop(tabIcons[idx], 1.42f);
                    switchTab(idx);
                }
            });
            bar.addView(cellWrap, new LinearLayout.LayoutParams(
                    0, dp(46), 1f));
        }
        nav.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        nav.setSlots(order);
        // 【v4.14.4】让底栏持有内容视图引用，抓取背后画面做液态玻璃模糊
        nav.setBackdropSource(content);
        this.nav = nav;
        // 内容区底部留出底栏高度，避免被悬浮胶囊遮挡
        content.setPadding(0, 0, 0, dp(74));

        refreshLoginTop();
        FrameLayout wrap = new FrameLayout(this);
        // 玻璃胶囊的投影与外描边会溢出自身边界一点点，父容器不能裁
        wrap.setClipChildren(false);
        wrap.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 【v4.14.4】悬浮胶囊：左右 16dp、底部 18dp，离底更高、悬空更明显，三项等分
        FrameLayout.LayoutParams navP = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        // 【v4.16.0】横屏/平板：底栏最多 560dp 宽并居中，否则整条横跨 1000dp 会散架
        int navSide = dp(16);
        if (wide) {
            int wpx = getResources().getDisplayMetrics().widthPixels;
            navSide = Math.max(dp(16), (wpx - dp(560)) / 2);
        }
        navP.leftMargin = navSide;
        navP.rightMargin = navSide;
        navP.bottomMargin = wide ? dp(12) : dp(18);
        wrap.addView(nav, navP);

        // 液态透镜层：覆盖全屏但只在「底栏条带」内绘制（上下溢出约 3~4mm），
        // 不吃触摸，事件穿透给底栏。
        lensLayer = new LiquidLensLayer(this);
        lensLayer.setClickable(false);
        wrap.addView(lensLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底栏滑动交互：按住出透镜 + 实时预览高亮，松手才真正切换页面
        nav.setCallback(new LiquidNavBar.Callback() {
            @Override public void onTouchDown(float x, float y) {
                // 透镜只允许出现在底栏所在条带，上下各溢出约 3mm（<5mm）
                lensLayer.setBand(nav.getTop() - dp(20), nav.getBottom() + dp(20));
                lensLayer.captureBehind(wrap);
                lensLayer.start(x, y);
            }
            @Override public void onTouchMove(float x, float y) {
                lensLayer.move(x, y);
            }
            @Override public void onTouchUp() {
                lensLayer.end();
                clearPreview();
            }
            @Override public void onHover(int tabIndex) {
                previewTab(tabIndex);
            }
            @Override public void onCommit(int tabIndex) {
                commitTab(tabIndex);
            }
        });

        setContentView(wrap);
        if (restoreTab > 0) {
            int t = restoreTab;
            restoreTab = -1;
            switchTab(t);
        }
        if (Prefs.splash(this)) showSplash(wrap);
        // 【v4.14.4】等首屏布局与内容完成后再抓一次背景，生成液态玻璃材质
        ui.postDelayed(new Runnable() { @Override public void run() { refreshNavBackdrop(); } }, 420);
    }

    /** 【v4.14.4】刷新底栏液态玻璃的模糊背景（延后一帧，等内容真正绘制完成） */
    void refreshNavBackdrop() {
        if (nav == null || content == null) return;
        ui.post(new Runnable() { @Override public void run() {
            try { nav.captureBackdrop(); } catch (Throwable ignore) { }
        }});
    }

    /** 动态卡片里的跳转 */
    final DynFeedView.Listener dynListener = new DynFeedView.Listener() {
        @Override public void onOpenVideo(String bvid, String title) {
            Intent it = new Intent(MainActivity.this, PlayActivity.class);
            it.putExtra("bvid", bvid);
            it.putExtra("ptitle", title);
            startActivity(it);
        }
        @Override public void onOpenUp(String mid, String name) {
            Intent it = new Intent(MainActivity.this, UserActivity.class);
            it.putExtra("mid", mid);
            it.putExtra("name", name);
            startActivity(it);
        }
    };

    /** 选中胶囊：参考图实测 #353841，顶亮底暗一点点，做出玻璃块的手感 */
    android.graphics.drawable.GradientDrawable navPillBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(23));
        if (DARK) {
            g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
            // 【v4.14.4】改为半透明，底下液态玻璃能透上来，不再是一块死实色
            g.setColors(new int[]{0x66434852, 0x4D2C2F38});
            g.setStroke(dp(1), 0x33FFFFFF);
        } else {
            g.setColor(0x99FCE7EE);
            g.setStroke(dp(1), 0x2AFB7299);
        }
        return g;
    }

    /** 未选中项的中灰 */
    int navOffColor() {
        return DARK ? 0xFF9B9FA8 : 0xFF6E747D;
    }

    String tabIconName(int i) {
        if (i == 0) return "g_home";
        if (i == 1) return "g_dynamic";
        return "g_mine";
    }

    /** 统一刷新底栏：active 项点亮胶囊，其余用 off 色 */
    void styleNav(int active, int off) {
        for (int i = 0; i < 5; i++) {
            if (navPills[i] == null && tabs[i] == null) continue;   // 加号/消息位已移除
            boolean on = (i == active);
            int color = on ? C_ACC : off;
            if (tabs[i] != null) tabs[i].setTextColor(color);
            if (tabIcons[i] != null) Ic.set(tabIcons[i], tabIconName(i), color);
            if (navPills[i] != null) {
                navPills[i].setBackground(on ? navPillBg() : null);
            }
        }
    }

    /** 滑动预览：手指悬停的 tab 变粉，其余变淡（不改页面） */
    void previewTab(int idx) {
        styleNav(idx, navOffColor());
    }

    /** 取消预览：按当前 tab 恢复常规配色 */
    void clearPreview() {
        styleNav(currentTab, navOffColor());
    }

    /** 松手提交：点到别的 tab 才真正切页；重复点当前 tab 回顶部 */
    void commitTab(int idx) {
        if (idx == currentTab) { clearPreview(); scrollContentTop(); return; }
        switchTab(idx);
    }

    /** 回到当前列表顶部（仅首页列表有效） */
    void scrollContentTop() {
        try {
            if (listView != null) listView.smoothScrollToPosition(0);
        } catch (Throwable ignore) { }
    }

    /**
     * 开屏动画：整屏插画（drawable/splash）+ 底部品牌与作者署名。
     * 总时长 2.5 秒：完整可见 2360ms，随后 140ms 淡出。
     */
    void showSplash(final FrameLayout host) {
        try {
            final FrameLayout layer = new FrameLayout(this);
            layer.setBackgroundColor(Color.BLACK);
            layer.setClickable(true);   // 开屏期间吞掉点击，避免误触底下的按钮

            // ---- 背景插画 ----
            final ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int rid = getResources().getIdentifier("splash", "drawable", getPackageName());
            if (rid != 0) art.setImageResource(rid);
            art.setPivotX(0.5f);
            art.setPivotY(0.42f);
            layer.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // ---- 底部压暗，保证文字可读 ----
            View scrim = new View(this);
            scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xF2000000, 0xB3000000, 0x00000000}));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM);
            layer.addView(scrim, sp);

            // ---- 文案：名称 + 作者 ----
            final LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView name = new TextView(this);
            name.setText("哔小趣");
            name.setTextColor(Color.WHITE);
            name.setTextSize(30);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setLetterSpacing(0.1f);
            name.setShadowLayer(dp(8), 0, dp(2), 0x99000000);
            name.setGravity(Gravity.CENTER);
            box.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView author = new TextView(this);
            author.setText("软件作者 Dynamo");
            author.setTextColor(0xE6FFFFFF);
            author.setTextSize(13);
            author.setLetterSpacing(0.06f);
            author.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
            author.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ap.topMargin = dp(10);
            box.addView(author, ap);

            // 细分割线 + 标语
            View line = new View(this);
            line.setBackgroundColor(0x66FB7299);
            LinearLayout.LayoutParams lpLine = new LinearLayout.LayoutParams(dp(34), dp(2));
            lpLine.topMargin = dp(14);
            box.addView(line, lpLine);

            TextView slogan = new TextView(this);
            slogan.setText("轻量 · 无广告 · 高清");
            slogan.setTextColor(0xB3FFFFFF);
            slogan.setTextSize(11);
            slogan.setLetterSpacing(0.12f);
            slogan.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sg = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sg.topMargin = dp(12);
            box.addView(slogan, sg);

            FrameLayout.LayoutParams lpBox = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lpBox.bottomMargin = dp(56);
            layer.addView(box, lpBox);

            // ---- 底部粉色加载线 ----
            final View bar = new View(this);
            bar.setBackgroundColor(0xFFFB7299);
            bar.setPivotX(0f);
            bar.setScaleX(0.02f);
            int barW = getResources().getDisplayMetrics().widthPixels;
            layer.addView(bar, new FrameLayout.LayoutParams(
                    barW, dp(2), Gravity.BOTTOM));

            host.addView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 入场：图片缓慢推近，文字淡入上浮，加载线走满全程（整体 2.5 秒）
            art.setScaleX(1f);
            art.setScaleY(1f);
            art.animate().scaleX(1.06f).scaleY(1.06f).setDuration(2360)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

            box.setAlpha(0f);
            box.setTranslationY(dp(14));
            box.animate().alpha(1f).translationY(0f).setDuration(560)
                    .setStartDelay(80)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

            bar.animate().scaleX(1f).setDuration(2360)
                    .setInterpolator(new android.view.animation.LinearInterpolator())
                    .start();

            layer.postDelayed(new Runnable() {
                @Override public void run() {
                    layer.animate().alpha(0f).setDuration(140)
                            .withEndAction(new Runnable() {
                                @Override public void run() {
                                    try { host.removeView(layer); } catch (Throwable ignore) { }
                                }
                            }).start();
                }
            }, 2360);
        } catch (Throwable e) {
            CrashHandler.note("splash", "开屏动画失败：" + e);
        }
    }

    /**
     * 下拉刷新容器：dispatchTouchEvent 在最外层，子 View（双列卡片）消费了事件也照样能观察到。
     * 只观察、不消费，列表滚动与卡片点击完全不受影响。
     */
    static class PullFrame extends FrameLayout {
        interface Watcher { void onTouch(MotionEvent ev); }
        Watcher watcher;

        PullFrame(Context c) { super(c); }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            if (watcher != null) {
                try { watcher.onTouch(ev); } catch (Throwable ignore) { }
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    /** 品牌标：粉色渐变圆角方块 + 白色播放键 */
    static class LogoView extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path tri = new android.graphics.Path();
        final android.graphics.RectF rect = new android.graphics.RectF();

        LogoView(Context c) { super(c); }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            float pad = w * 0.04f;
            rect.set(pad, pad, w - pad, h - pad);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h,
                    new int[]{Color.parseColor("#ff9dbb"), Color.parseColor("#fb7299"),
                            Color.parseColor("#e85c86")},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRoundRect(rect, w * 0.28f, w * 0.28f, p);
            p.setShader(null);

            p.setColor(Color.WHITE);
            tri.reset();
            tri.moveTo(w * 0.40f, h * 0.31f);
            tri.lineTo(w * 0.40f, h * 0.69f);
            tri.lineTo(w * 0.72f, h * 0.50f);
            tri.close();
            cv.drawPath(tri, p);
        }
    }

    void switchTab(int idx) {
        // 【v4.13.4】切走首页前记录滚动位置，切回来时原样恢复（配合视图复用）
        if (currentTab == 0 && idx != 0 && listView != null) {
            try {
                homeScrollPos = listView.getFirstVisiblePosition();
                View first = listView.getChildAt(0);
                homeScrollTop = first == null ? 0
                        : first.getTop() - listView.getPaddingTop();
            } catch (Throwable ignore) { }
        }
        currentTab = idx;
        // 底部高亮（胶囊 + 图标文字）
        styleNav(idx, navOffColor());
        // 动态页用自带的「关注」标题栏，隐藏全局搜索栏；其余页恢复
        if (topBarView != null) topBarView.setVisibility(idx == 1 ? View.GONE : View.VISIBLE);
        // 频道栏只在首页显示
        if (channelBar != null) {
            channelBar.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        }
        content.removeAllViews();
        refreshTip = null;
        refreshing = false;
        if (idx == 0) {
            appliedTwoCol = Prefs.twoColumn(this);
            // 【v4.13.4】推荐列表视图复用：只要缓存有效就不重新请求、不清空数据，
            // 直接把原来的列表视图挂回去并恢复滚动位置。切走再点回来不再刷新视频。
            if (homeCacheValid && homeRootCache != null) {
                content.addView(homeRootCache, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                listView = homeRootCache instanceof PullFrame
                        ? findListView(homeRootCache) : listView;
                refreshTip = homeRefreshTipCache;
                final int pos = homeScrollPos, top = homeScrollTop;
                ui.post(new Runnable() { @Override public void run() {
                    try { if (listView != null) listView.setSelectionFromTop(pos, top); }
                    catch (Throwable ignore) { }
                }});
            } else {
                rows.clear();
                homeRootCache = newList(appliedTwoCol, calcCols());
                homeRefreshTipCache = refreshTip;
                homeCacheValid = true;
                homeScrollPos = 0;
                homeScrollTop = 0;
                content.addView(homeRootCache, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                loadChannel();
            }
        } else if (idx == 1) {
            // 关注页：标题 / 全部·视频 / 最常访问 / 动态卡
            // 【v4.10.5】整段兜底：万一某台机型上构建失败，也只会在页面里显示错误，
            // 不再把整个 App 顶掉（之前点「动态」直接闪退就是这里没护栏）。
            try {
                if (dynFeed == null) dynFeed = new DynFeedView(this, dynListener);
                content.addView(dynFeed, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                dynFeed.refreshOnShow();   // 【v4.13.3】重新挂载后按当前分段拉新鲜数据，避免串入其它栏目的视频
            } catch (Throwable e) {
                CrashHandler.note("switchTab/dyn",
                        android.util.Log.getStackTraceString(e));
                TextView t = new TextView(this);
                t.setText("动态页打开失败\n" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : "：" + e.getMessage()));
                t.setTextColor(C_SUB);
                t.setTextSize(12);
                t.setGravity(Gravity.CENTER);
                t.setLineSpacing(dp(6), 1f);
                t.setPadding(dp(24), dp(80), dp(24), dp(24));
                content.addView(t, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else if (idx == 3) {
            // 消息：直接进私信列表，回来后高亮仍在首页
            try {
                startActivity(new Intent(this, MsgActivity.class));
            } catch (Throwable e) {
                Toast.makeText(this, "打开消息失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            switchTab(0);
        } else if (idx == 4) {
            buildMine();
        }
        // 【v4.13.7】板块切换 Q 弹入场：内容从下方微微带回弹地滑入并放大，
        // 用 OvershootInterpolator 制造「Q 弹有活力」的手感（API11+，API22 可用）。
        bounceInContent();
        // 【v4.14.4】新板块内容 asynchronously 铺好后，重抓液态玻璃背景
        ui.postDelayed(new Runnable() { @Override public void run() { refreshNavBackdrop(); } }, 480);
    }

    /** 【v4.14.7】板块内容弹性入场动画：回弹更明显、更 Q 弹有活力 */
    void bounceInContent() {
        if (content == null || content.getChildCount() == 0) return;
        try {
            View v = content.getChildAt(0);
            float h = content.getHeight() > 0 ? content.getHeight() : dp(400);
            Bounce.enterUp(v, h * 0.06f);
        } catch (Throwable ignore) { }
    }

    /** 频道样式：选中的粉色加粗 */
    void refreshChannelStyle() {
        for (int i = 0; i < channels.length; i++) {
            if (channelViews[i] == null) continue;
            boolean on = i == currentChannel;
            channelViews[i].setTextColor(on ? C_ACC : C_SUB);
            channelViews[i].setTextSize(on ? 15.5f : 14.5f);
            channelViews[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    void switchChannel(int ci) {
        if (ci == currentChannel) return;
        currentChannel = ci;
        refreshChannelStyle();
        // 【v4.14.7】频道切换 Q 弹：标签弹得更明显，回弹更 Q
        Bounce.pop(channelViews[ci], 1.3f);
        if (currentTab == 0) {
            loadChannel();
            bounceInContent();
        }
    }

    /** 按当前频道加载内容 */
    void loadChannel() {
        int rid = channelRid[currentChannel];
        if (rid == 0) { loadRecommend(false); return; }        // 推荐：混合流
        if (rid == -2) { loadPopular(); return; }              // 热门
        if (rid == -1) { loadLive(); return; }                 // 直播
        if (rid == 13) { loadBangumi(); return; }              // 【v4.13.2】新番：番剧是 PGC 内容，ranking/v2 不支持 rid=13
        loadRanking(rid);                                      // 分区榜单
    }

    /** 【v4.13.2 新番修复】番剧新番列表：PGC 索引 → 首集 bvid/cid → 复用播放链。 */
    void loadBangumi() {
        if (!refreshing) addLoading();
        final int ch = currentChannel;
        new Thread(new Runnable() { @Override public void run() {
            try {
                final JSONArray list = BiliApi.bangumiNew();
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) {
                        fillBangumi(list, "新番暂无内容");
                    }
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) {
                        rows.clear();
                        rows.add(new VideoItem("", "新番加载失败：" + e.getMessage(), "", null));
                        notifyData(); hideRefreshTip();
                    }
                } });
            }
        } }).start();
    }

    /** 解析番剧结果成 rows（带 cid，跳过 pages 往返），字段结构见 BiliApi.bangumiNew */
    void fillBangumi(final JSONArray list, final String emptyTip) {
        final ArrayList<VideoItem> got = new ArrayList<>();
        try {
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    String bv = o.optString("bvid", "");
                    if (bv.isEmpty()) continue;
                    String up = o.optJSONObject("owner") != null
                            ? o.getJSONObject("owner").optString("name", "") : "";
                    long play = o.optJSONObject("stat") != null
                            ? o.getJSONObject("stat").optLong("view", 0) : 0;
                    int dur = o.optInt("duration", 0);
                    StringBuilder sub = new StringBuilder();
                    if (!up.isEmpty()) sub.append(up);
                    if (play > 0) {
                        if (sub.length() > 0) sub.append("  ·  ");
                        sub.append(VideoItem.views(play)).append("追番");
                    }
                    if (dur > 0) {
                        if (sub.length() > 0) sub.append("  ·  ");
                        sub.append(VideoItem.dur(dur));
                    }
                    got.add(new VideoItem(bv, BiliApi.cleanTitle(o.optString("title", "")),
                            sub.toString(), o.optString("pic", ""),
                            o.optLong("cid", 0), 0f, dur));
                }
            }
        } catch (Throwable ignore) { }
        setResult(got, emptyTip);
        hideRefreshTip();
    }

    void showPublishMenu() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("发布内容")
                .setMessage("第三方客户端暂不支持投稿。\n你可以进入动态页查看关注的人的最新内容。")
                .setPositiveButton("去看看动态", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { switchTab(1); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 列表 + 下拉刷新（刷新提示浮在列表上方，不参与 ListView 布局，避免 header 动态高度问题） */
    View newList(boolean twoCol, int cols) {
        final PullFrame wrap = new PullFrame(this);

        final ListView lv = new ListView(this);
        listView = lv;
        lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lv.setDividerHeight(dp(twoCol ? 12 : 9));
        lv.setPadding(dp(10), dp(6), dp(10), dp(10));
        lv.setClipToPadding(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 【v4.17.0 滚动顺滑】加大复用缓存 + 开启滚动缓存：
        // 快速滑动时行 View 直接从缓存里取，不再重新创建 / 重新绑定，
        // 这是列表滑动掉帧的主要来源之一。
        try {
            lv.setScrollingCacheEnabled(true);
            lv.setAnimationCacheEnabled(false);
            lv.setSmoothScrollbarEnabled(true);
            lv.setDrawSelectorOnTop(false);
        } catch (Throwable ignore) { }
        feedAdapter = new FeedAdapter(this, rows, twoCol, cols, new FeedAdapter.Listener() {
            @Override public void onItem(VideoItem item) { openItem(item); }
        });
        lv.setAdapter(feedAdapter);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              View v, int i, long id) {
                if (twoCol) return;   // 双列由卡片自身处理，避免行号与数据下标错位
                if (i < 0 || i >= rows.size()) return;
                openItem(rows.get(i));
            }
        });
        // 【v4.16.1】滚到底自动续页：首页首批 50 条，看完还能继续往下翻
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) { }
            @Override public void onScroll(android.widget.AbsListView v, int first,
                                           int visible, int total) {
                if (total > 0 && first + visible >= total - 3) loadMoreRecommend();
                // 【v4.17.0】顺手把即将露出的封面预取掉（低优先级，不抢当前可见项）：
                // 滑到那一屏时直接命中内存，零等待出图。
                prefetchCovers(first + visible, 8);
            }
        });

        refreshTip = new TextView(this);
        refreshTip.setText("下拉刷新");
        refreshTip.setTextColor(C_SUB);
        refreshTip.setTextSize(12);
        refreshTip.setGravity(Gravity.CENTER);
        refreshTip.setPadding(0, dp(8), 0, dp(8));

        wrap.addView(lv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(refreshTip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP));
        refreshTip.setTranslationY(-dp(40));
        refreshTip.setAlpha(0f);

        // 关键：手势不能挂在 ListView 上。双列卡片自己是 clickable，会消费 ACTION_DOWN，
        // ListView 的 OnTouchListener 拿不到按下点，pullStartY 永远是旧值 → 双列下拉刷新失效。
        // 改到外层容器的 dispatchTouchEvent 观察，所有事件都能拿到，且不消费、不影响滚动与点击。
        wrap.watcher = new PullFrame.Watcher() {
            @Override public void onTouch(MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pullStartY = ev.getY();
                        pullArmed = atTop();
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        if (!pullArmed || refreshing || refreshTip == null) break;
                        // 双列卡片会吃掉 DOWN，拖动后列表可能已在滚动：一旦离开顶部就判定为滚动
                        if (!atTop()) { pullArmed = false; hideRefreshTip(); break; }
                        float dy = ev.getY() - pullStartY;
                        if (dy > 0) {
                            float max = dp(110);
                            float d = Math.min(dy, max);
                            refreshTip.animate().cancel();
                            refreshTip.setTranslationY(-dp(40) + d);
                            refreshTip.setAlpha(Math.min(1f, d / (max * 0.45f)));
                            refreshTip.setText(d > max * 0.55f ? "松开刷新" : "下拉刷新");
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean trigger = pullArmed && !refreshing
                                && refreshTip != null && refreshTip.getAlpha() > 0.9f
                                && refreshTip.getTranslationY() > -dp(25);
                        pullArmed = false;
                        if (trigger) doRefresh();
                        else if (!refreshing) hideRefreshTip();
                        break;
                    }
                }
            }
        };

        return wrap;
    }

    /** 【v4.13.4】从缓存的 PullFrame 里找回 ListView，避免复用后引用丢失 */
    android.widget.AbsListView findListView(View root) {
        try {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof android.widget.AbsListView) return (android.widget.AbsListView) c;
                if (c instanceof android.view.ViewGroup) {
                    android.widget.AbsListView inner = findListView(c);
                    if (inner != null) return inner;
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }

    boolean atTop() {
        if (listView == null) return false;
        if (listView.getChildCount() == 0) return true;
        return listView.getFirstVisiblePosition() == 0
                && listView.getChildAt(0).getTop() >= listView.getPaddingTop() - 2;
    }

    void doRefresh() {
        if (refreshing) return;
        refreshing = true;
        if (refreshTip != null) {
            refreshTip.setText("正在刷新…");
            refreshTip.animate().translationY(dp(6)).alpha(1f).setDuration(160).start();
        }
        if (currentTab == 0) {
            if (channelRid[currentChannel] == 0) loadRecommend(true);
            else loadChannel();
        } else if (currentTab == 1 && dynFeed != null) {
            refreshing = false;
        } else refreshing = false;
    }

    void hideRefreshTip() {
        refreshing = false;
        if (refreshTip == null) return;
        refreshTip.animate().translationY(-dp(40)).alpha(0f).setDuration(200).start();
    }

    void addLoading() {
        rows.add(new VideoItem("", "加载中…", "", null));
        notifyData();
    }

    void setResult(ArrayList<VideoItem> got, String emptyTip) {
        rows.clear();
        rows.addAll(got);
        if (rows.isEmpty()) rows.add(new VideoItem("", emptyTip, "", null));
        // 【v4.16.1】记录已加载的稿件，供续页去重
        loadedBvids.clear();
        for (int i = 0; i < rows.size(); i++) {
            VideoItem it = rows.get(i);
            if (it != null && !it.isPlaceholder() && it.bvid != null
                    && !it.bvid.isEmpty()) loadedBvids.add(it.bvid);
        }
        notifyData();
        // 【v4.13.6 起播提速】列表刚铺好时，趁用户还在浏览，提前预取前几条的地址。
        // PREFETCH_GATE 已把并发限制在 2，不会一次性打爆接口/触发风控。
        prefetchTopRows();
    }

    // ------------------------------------------------------------ 首页续页

    /** 【v4.16.1】已加载稿件集合（续页去重） */
    final java.util.HashSet<String> loadedBvids = new java.util.HashSet<>();
    /** 推荐流页码：1 = 首批，2+ = 续页 */
    int recPage = 1;
    boolean recLoadingMore = false;

    /**
     * 滚动到底 → 续一批推荐。
     * 只在「推荐」频道生效；其它频道（分区/热门/新番）沿用各自的数据源。
     */
    void loadMoreRecommend() {
        if (recLoadingMore || currentTab != 0) return;
        if (channelRid == null || currentChannel >= channelRid.length
                || channelRid[currentChannel] != 0) return;
        if (rows.isEmpty()) return;
        recLoadingMore = true;
        final int page = ++recPage;
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                JSONArray list = BiliApi.recommendMore(page);
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null || o.optString("bvid", "").isEmpty()) continue;
                        got.add(parseRec(o));
                    }
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                recLoadingMore = false;
                if (currentTab != 0) return;
                // 去掉尾部可能残留的占位行
                while (!rows.isEmpty() && rows.get(rows.size() - 1).isPlaceholder()) {
                    rows.remove(rows.size() - 1);
                }
                int added = 0;
                for (int i = 0; i < got.size(); i++) {
                    VideoItem it = got.get(i);
                    if (it == null || it.bvid == null || it.bvid.isEmpty()) continue;
                    if (loadedBvids.contains(it.bvid)) continue;   // 去重
                    loadedBvids.add(it.bvid);
                    rows.add(it);
                    added++;
                }
                if (added > 0) {
                    notifyData();
                } else if (err[0] != null) {
                    Toast.makeText(MainActivity.this, "没有更多了", Toast.LENGTH_SHORT).show();
                }
                // 续页失败就把页码退回去，下次滚动还能再试
                if (added == 0 && err[0] != null) recPage = page - 1;
            } });
        } }).start();
    }

    void prefetchTopRows() {
        try {
            int n = 0;
            for (int i = 0; i < rows.size() && n < 3; i++) {
                VideoItem it = rows.get(i);
                if (it == null || it.isPlaceholder() || it.bvid.startsWith("up:")) continue;
                if (it.cid > 0) {
                    BiliApi.prefetchPlayurl(it.bvid, it.cid, BiliApi.QN_ALL);
                    n++;
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.17.0】滚动时预取即将露出的封面。
     * 走 ImgLoader 的低优先级预取池：提前把下一屏封面下好并解码进内存，
     * 滑到那一屏时直接命中 → 零等待出图；同时绝不占用可见封面的线程与带宽。
     */
    void prefetchCovers(int from, int count) {
        try {
            if (feedAdapter == null) return;
            int w = feedAdapter.prefetchWidth();
            int h = feedAdapter.prefetchHeight();
            if (w <= 0 || h <= 0) return;
            int end = Math.min(rows.size(), from + count);
            for (int i = Math.max(0, from); i < end; i++) {
                VideoItem it = rows.get(i);
                if (it == null || it.isPlaceholder()) continue;
                if (it.localUri != null && !it.localUri.isEmpty()) continue;
                String t = it.thumb();
                if (t != null) ImgLoader.prefetch(t, w, h);
            }
        } catch (Throwable ignore) { }
    }

    void notifyData() {
        try {
            if (listView != null && listView.getAdapter() != null) {
                ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
            }
        } catch (Throwable ignore) { }
    }

    /** 列表项统一入口：视频进播放页，UP 主进空间 */
    void openItem(VideoItem it) {
        if (it == null || it.isPlaceholder()) return;
        if (it.bvid.startsWith("up:")) {
            if (it.extra != null && !it.extra.isEmpty()) {
                Intent u = new Intent(this, UserActivity.class);
                u.putExtra("mid", it.extra);
                u.putExtra("name", it.title);
                u.putExtra("face", it.pic);
                startActivity(u);
            } else {
                Intent s = new Intent(this, SearchActivity.class);
                s.putExtra("keyword", it.bvid.substring(3));
                startActivity(s);
            }
            return;
        }
        // 【v4.13.6 起播提速】点击瞬间就后台预取该视频的 playurl，
        // 让「网络取址」与「播放页 Activity 启动/布局」并行跑。等 play() 真正要地址时
        // 往往已命中缓存 → 直接省掉一次 WBI 签名 + playurl 网络往返（5G 下常占 1~3 秒）。
        if (it.cid > 0) {
            BiliApi.prefetchPlayurl(it.bvid, it.cid, BiliApi.QN_ALL);
        }
        Intent intent = new Intent(this, PlayActivity.class);
        intent.putExtra("bvid", it.bvid);
        // 【v4.1 提速】把列表已有的元数据带给播放页，省掉 pages() 串行请求
        intent.putExtra("cid", it.cid);
        intent.putExtra("aspect", it.aspect);
        intent.putExtra("ptitle", it.title);
        intent.putExtra("pdur", it.durSec);
        startActivity(intent);
    }

    // ------------------------------------------------------------ 推荐

    void loadRecommend(final boolean isRefresh) {
        if (!isRefresh) addLoading();
        // 【v4.16.1】换一批 = 重置续页计数
        recPage = 1;
        // 刷新前的列表快照，用来判定「这一批是不是和刚才完全一样」
        final ArrayList<String> gotOld = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            VideoItem it = rows.get(i);
            if (it != null && !it.isPlaceholder() && it.bvid != null
                    && !it.bvid.isEmpty() && !it.bvid.startsWith("up:")) {
                gotOld.add(it.bvid);
            }
        }
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                // 每次刷新自增种子：首页推荐流 + 热门随机页 + 随机分区榜单混合后打乱，
                // 不再固定拉 popular 第 1 页（那批内容几分钟内都不会变，正是「怎么刷都一样」的原因）
                int seed = Prefs.bumpRecSeed(MainActivity.this);
                JSONArray list = BiliApi.recommendMix(seed, 50);
                // 极小概率抽到同一批：强制再换一次
                if (list == null || list.length() == 0
                        || sameBvids(list, gotOld)) {
                    list = BiliApi.recommendMix(seed + 131);
                }
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null || o.optString("bvid", "").isEmpty()) continue;
                        got.add(parseRec(o));
                    }
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                if (currentTab != 0) { hideRefreshTip(); return; }
                setResult(got, "没有内容（" + (err[0] == null ? "空" : err[0]) + "）");
                hideRefreshTip();
                if (isRefresh) {
                    Toast.makeText(MainActivity.this,
                            got.isEmpty() ? "刷新失败，请稍后重试" : "已换一批 · " + got.size() + " 条",
                            Toast.LENGTH_SHORT).show();
                }
            } });
        } }).start();
    }

    /** 新一批与旧列表重复度 ≥85% 视为「没换」（bvid 命中率） */
    static boolean sameBvids(JSONArray list, ArrayList<String> old) {
        if (list == null || old == null || old.isEmpty() || list.length() == 0) return false;
        int hit = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            if (old.contains(o.optString("bvid", ""))) hit++;
        }
        return hit >= Math.min(list.length(), old.size()) * 0.85f;
    }

    /**
     * 【v4.15.4】推荐流条目解析（loadRecommend 共用）：
     * 标题 / UP / 播放量 / 时长 / cid / 宽高比——cid 等列表接口已带，播放页可跳过 pages 请求。
     */
    static VideoItem parseRec(JSONObject o) {
        String bv = o.optString("bvid", "");
        JSONObject owner = o.optJSONObject("owner");
        String up = owner != null ? owner.optString("name", "") : "";
        int dur = o.optInt("duration", 0);
        JSONObject stat = o.optJSONObject("stat");
        long play = stat != null ? stat.optLong("view", 0) : 0;
        StringBuilder sub = new StringBuilder();
        if (!up.isEmpty()) sub.append(up);
        if (play > 0) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(VideoItem.views(play)).append("播放");
        }
        if (dur > 0) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(VideoItem.dur(dur));
        }
        long cid = o.optLong("cid", 0);
        long durSec = o.optLong("duration", 0);
        float aspect = 0f;
        JSONObject dim = o.optJSONObject("dimension");
        if (dim != null) {
            int dw = dim.optInt("width", 0), dh = dim.optInt("height", 0);
            int rot = dim.optInt("rotate", 0);
            if (rot == 90 || rot == 270) { int t = dw; dw = dh; dh = t; }
            if (dw > 0 && dh > 0) aspect = dw / (float) dh;
        }
        return new VideoItem(bv, BiliApi.cleanTitle(o.optString("title", "")),
                sub.toString(), o.optString("pic", ""), cid, aspect, durSec);
    }

    // ------------------------------------------------------------ 我的（个人主页）

    // 个人主页控件
    android.widget.ScrollView mineScroll;
    android.widget.ImageView mineAvatar;
    TextView mineName, mineLv, mineVip, mineCoins, mineUid, mineCacheLine;
    Button mineLoginBtn;
    final TextView[] mineStats = new TextView[3];
    final TextView[] mineStatLabels = new TextView[3];
    /** 【v4.10.1】分区 tab（主页/动态/投稿/收藏）与下划线 */
    final TextView[] mineTabs = new TextView[4];
    final View[] mineTabLines = new View[4];
    /** 投稿区标题行，点「投稿」tab 时滚到这里 */
    View mineVideoHead;

    /** 圆角背景 + 1dp 描边：浅色下让白卡片和浅灰背景分开 */
    static android.graphics.drawable.GradientDrawable roundBgLine(int color, float radDp,
                                                                  int lineColor, Activity a) {
        android.graphics.drawable.GradientDrawable g = roundBg(color, radDp, a);
        g.setStroke((int) a.getResources().getDisplayMetrics().density, lineColor);
        return g;
    }

    /** 圆角背景（纯代码，不引入 shape 资源） */
    static android.graphics.drawable.GradientDrawable roundBg(int color, float radDp, Activity a) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * a.getResources().getDisplayMetrics().density);
        return g;
    }

    /**
     * 【v4.9.8】「我的」页里的跳转行：设置 / 游戏厅 / 留声机 共用一套样式，
     * 保证三行的高度、圆角、箭头位置完全一致。
     */
    LinearLayout mineLinkRow(String icon, String title, String sub, int tint,
                             View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundBg(C_CARD, 14, this));
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setClickable(true);

        row.addView(Ic.view(this, icon, tint, 22),
                new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(15);
        col.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(sub);
            d.setTextColor(Color.parseColor("#6f7581"));
            d.setTextSize(11);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(3);
            col.addView(d, dlp);
        }

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(14);
        row.addView(col, clp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.parseColor("#6f7581"));
        arrow.setTextSize(22);
        row.addView(arrow);

        row.setOnClickListener(click);
        return row;
    }

    /**
     * 【v4.10.1 / v4.10.2】「我的」页排版：
     * 头图渐变 + 头像压线 → 昵称与编辑资料同一行 → 三等分数据卡 →
     * 等级 → 签名 → 常用功能宫格 + 设置分组卡 → 分区 tab → 投稿卡网格。
     * 关键点：编辑资料不再独占一整行，三栏数据从昵称列里挪出来铺满宽度，
     * 底部留出 98dp 空白，避免最后几行被悬浮底栏压住。
     */
    void buildMine() {
        mineScroll = new android.widget.ScrollView(this);
        mineScroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(98));
        mineScroll.addView(col, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==================== 头图：渐变 + 光晕 + 头像水印 ====================
        // 【v4.10.2】头图与头像行放进同一个 FrameLayout：
        // 头像不再用负 margin（负 margin 会让父 LinearLayout 高度算错，
        // 结果头像下半截被后面的数据卡盖掉），改成给 profile 一个 114dp 的 topMargin，
        // 头像正常摆在 profile 顶部，完整落在容器内，压线效果不变。
        android.widget.FrameLayout head = new android.widget.FrameLayout(this);
        head.setClipChildren(false);

        android.widget.FrameLayout banner = new android.widget.FrameLayout(this);
        banner.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{mixColor(C_ACC, C_BG, DARK ? 0.32f : 0.52f),
                        mixColor(C_ACC, C_BG, DARK ? 0.08f : 0.13f),
                        C_BG}));

        View deco = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(DARK ? 0x14FFFFFF : 0x22FFFFFF);
        deco.setBackground(dg);
        android.widget.FrameLayout.LayoutParams dlp =
                new android.widget.FrameLayout.LayoutParams(dp(200), dp(200));
        dlp.gravity = Gravity.TOP | Gravity.RIGHT;
        dlp.topMargin = dp(-86);
        dlp.rightMargin = dp(-56);
        banner.addView(deco, dlp);

        // 没有头图接口，把本人头像当右上角的水印，透明度压到很低，只做氛围
        mineBanner = new android.widget.ImageView(this);
        mineBanner.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        mineBanner.setAlpha(DARK ? 0.16f : 0.12f);
        mineBanner.setClipToOutline(true);
        mineBanner.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        android.widget.FrameLayout.LayoutParams blp =
                new android.widget.FrameLayout.LayoutParams(dp(178), dp(178));
        blp.gravity = Gravity.TOP | Gravity.RIGHT;
        blp.rightMargin = dp(-26);
        blp.topMargin = dp(-18);
        banner.addView(mineBanner, blp);

        // 下沿融进页面背景，避免头图与内容之间出现硬边
        View fade = new View(this);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{C_BG, C_BG & 0x00FFFFFF, C_BG & 0x00FFFFFF}));
        banner.addView(fade, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右上角：搜索 / 更多（半透明玻璃圆底，压在任何背景上都看得清）
        LinearLayout bannerTop = new LinearLayout(this);
        bannerTop.setOrientation(LinearLayout.HORIZONTAL);
        bannerTop.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bannerTop.setPadding(dp(12), dp(10), dp(12), 0);

        ImageView bSet = Ic.glass(this, "g_set",
                DARK ? 0xFFFFFFFF : 0xFF2E333C,
                DARK ? 0x2EFFFFFF : 0x4DFFFFFF, 34, 17);
        bSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        bannerTop.addView(bSet, new LinearLayout.LayoutParams(dp(34), dp(34)));

        View topGap = new View(this);
        bannerTop.addView(topGap, new LinearLayout.LayoutParams(dp(8), dp(1)));

        ImageView bMore = Ic.glass(this, "more",
                DARK ? 0xFFFFFFFF : 0xFF2E333C,
                DARK ? 0x2EFFFFFF : 0x4DFFFFFF, 34, 17);
        bMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMineMore(); }
        });
        bannerTop.addView(bMore, new LinearLayout.LayoutParams(dp(34), dp(34)));

        banner.addView(bannerTop, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        head.addView(banner, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(158)));

        // ==================== 头像 + 昵称 + 编辑资料（同一行）====================
        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.HORIZONTAL);
        profile.setGravity(Gravity.TOP);
        profile.setPadding(dp(16), 0, dp(16), 0);

        mineAvatar = new android.widget.ImageView(this);
        mineAvatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        mineAvatar.setBackgroundColor(ImgLoader.placeholderColor());
        mineAvatar.setClipToOutline(true);
        mineAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });

        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(3), C_BG);
        FrameLayout avatarWrap = new FrameLayout(this);
        avatarWrap.setBackground(ring);
        avatarWrap.setClipToOutline(true);
        avatarWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        avatarWrap.addView(mineAvatar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(88), dp(88));
        profile.addView(avatarWrap, avp);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(13);
        np.topMargin = dp(13);
        profile.addView(names, np);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        mineName = new TextView(this);
        mineName.setTextColor(C_FG);
        mineName.setTextSize(18.5f);
        mineName.setTypeface(Typeface.DEFAULT_BOLD);
        mineName.setSingleLine(true);
        mineName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(mineName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mineVip = new TextView(this);
        mineVip.setTextColor(Color.WHITE);
        mineVip.setTextSize(9.5f);
        mineVip.setBackground(roundBg(C_ACC, 7, this));
        mineVip.setPadding(dp(6), dp(2), dp(6), dp(2));
        mineVip.setVisibility(View.GONE);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.leftMargin = dp(6);
        nameRow.addView(mineVip, vp);
        names.addView(nameRow);

        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brp.topMargin = dp(6);
        names.addView(badgeRow, brp);
        badgeRow.addView(mineBadge("粉丝团"));
        badgeRow.addView(mineBadge("成就勋章"));

        // 头像高 88，容器给足 88，保证头像完整；topMargin 114 = 头图 158 - 44（压线一半）
        profile.setMinimumHeight(dp(88));
        android.widget.FrameLayout.LayoutParams plp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(114);
        head.addView(profile, plp);

        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==================== 三栏数据（整宽卡片，三等分）====================
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setBackground(roundBgLine(C_CARD, 14, DARK ? 0xFF2A2F38 : 0xFFE9ECF1, this));
        stats.setPadding(0, dp(12), 0, dp(12));

        String[] lab = {"粉丝", "关注", "获赞"};
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(DARK ? 0xFF2F343E : 0xFFEBEEF3);
                stats.addView(div, new LinearLayout.LayoutParams(dp(0.8f), dp(22)));
            }
            LinearLayout one = new LinearLayout(this);
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER_HORIZONTAL);

            mineStats[i] = new TextView(this);
            mineStats[i].setTextColor(C_FG);
            mineStats[i].setTextSize(17);
            mineStats[i].setTypeface(Typeface.DEFAULT_BOLD);
            mineStats[i].setGravity(Gravity.CENTER);
            mineStats[i].setText("—");
            one.addView(mineStats[i]);

            mineStatLabels[i] = new TextView(this);
            mineStatLabels[i].setTextColor(C_SUB);
            mineStatLabels[i].setTextSize(10.5f);
            mineStatLabels[i].setGravity(Gravity.CENTER);
            mineStatLabels[i].setText(lab[i]);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(3);
            one.addView(mineStatLabels[i], llp);

            final int si = i;
            one.setClickable(true);
            one.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openStat(si); }
            });
            stats.addView(one, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.leftMargin = dp(16);
        stp.rightMargin = dp(16);
        stp.topMargin = dp(14);
        col.addView(stats, stp);

        // ==================== 等级进度 ====================
        LinearLayout lvRow = new LinearLayout(this);
        lvRow.setOrientation(LinearLayout.HORIZONTAL);
        lvRow.setGravity(Gravity.CENTER_VERTICAL);
        lvRow.setPadding(dp(16), dp(14), dp(16), 0);

        mineLv = new TextView(this);
        mineLv.setTextColor(Color.WHITE);
        mineLv.setTextSize(9.5f);
        mineLv.setTypeface(Typeface.DEFAULT_BOLD);
        mineLv.setBackground(roundBg(0xFFF0773C, 4, this));
        mineLv.setPadding(dp(6), dp(2), dp(6), dp(2));
        mineLv.setText("LV·");   // 【v4.12】占位：等接口返回真实等级再覆盖，别误导成 LV6
        lvRow.addView(mineLv);

        FrameLayout barBox = new FrameLayout(this);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                0, dp(5), 1f);
        bbp.leftMargin = dp(10);
        lvRow.addView(barBox, bbp);

        View barBg = new View(this);
        barBg.setBackground(roundBg(DARK ? 0xFF2E333D : 0xFFE1E5EC, 2.5f, this));
        barBox.addView(barBg, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        mineExpBarFill = new View(this);
        mineExpBarFill.setBackground(roundBg(0xFFF0773C, 2.5f, this));
        barBox.addView(mineExpBarFill, new android.widget.FrameLayout.LayoutParams(dp(0), dp(5)));

        mineExp = new TextView(this);
        mineExp.setTextColor(C_SUB);
        mineExp.setTextSize(10);
        mineExp.setText("加载中");
        mineExp.setGravity(Gravity.END);
        mineExp.setSingleLine(true);
        mineExp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(72),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.leftMargin = dp(8);
        lvRow.addView(mineExp, ep);
        col.addView(lvRow);

        // ==================== 签名 + 编辑资料 ====================
        LinearLayout signRow = new LinearLayout(this);
        signRow.setOrientation(LinearLayout.HORIZONTAL);
        signRow.setPadding(dp(16), dp(12), dp(16), 0);

        mineSign = new TextView(this);
        mineSign.setTextColor(C_FG);
        mineSign.setTextSize(12.5f);
        mineSign.setLineSpacing(dp(3), 1f);
        mineSign.setMaxLines(3);
        mineSign.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mineSign.setText("这个人很神秘，什么都没有写");
        signRow.addView(mineSign, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「编辑资料」：放在签名右侧的小胶囊，不再和昵称抢宽度
        TextView editBtn = new TextView(this);
        editBtn.setText("编辑资料");
        editBtn.setTextColor(C_FG);
        editBtn.setTextSize(11.5f);
        editBtn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable eg =
                new android.graphics.drawable.GradientDrawable();
        eg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        eg.setCornerRadius(dp(14));
        eg.setColor(0x00000000);
        eg.setStroke(dp(1), DARK ? 0xFF41464F : 0xFFD2D6DE);
        editBtn.setBackground(eg);
        editBtn.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams dtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        dtp.leftMargin = dp(10);
        signRow.addView(editBtn, dtp);
        editBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String uid = BiliApi.loggedInUid();
                if (uid == null) { promptLoginOrOut(); return; }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://space.bilibili.com/" + uid)));
                } catch (Throwable e) {
                    Toast.makeText(MainActivity.this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });

        col.addView(signRow);

        mineUid = new TextView(this);
        mineUid.setTextColor(DARK ? 0xFF6F7581 : 0xFF8A9099);
        mineUid.setTextSize(11);
        mineUid.setPadding(dp(16), dp(7), dp(16), 0);
        mineUid.setText("");
        col.addView(mineUid);

        // ==================== 功能宫格 ====================
        LinearLayout fnHead = new LinearLayout(this);
        fnHead.setOrientation(LinearLayout.HORIZONTAL);
        fnHead.setGravity(Gravity.CENTER_VERTICAL);
        fnHead.setPadding(dp(16), dp(24), dp(16), dp(10));

        TextView fnTitle = new TextView(this);
        fnTitle.setText("常用功能");
        fnTitle.setTextColor(C_FG);
        fnTitle.setTextSize(14);
        fnTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fnHead.addView(fnTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(fnHead);

        LinearLayout entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.HORIZONTAL);
        entries.setBackground(roundBgLine(C_CARD, 14, DARK ? 0xFF2A2F38 : 0xFFE9ECF1, this));
        entries.setPadding(0, dp(16), 0, dp(14));
        final String[] entryNames = {"离线缓存", "历史记录", "我的收藏", "稍后再看", "消息"};
        final String[] entryIcons = {"m_cache", "m_history", "m_fav", "m_later", "m_msg"};
        final String[] entryModes = {"cache", "history", "fav", "toview", "msg"};
        for (int i = 0; i < entryNames.length; i++) {
            final String mode = entryModes[i];
            LinearLayout one = new LinearLayout(this);
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER);

            ImageView icon = Ic.view(this, entryIcons[i], C_ACC, 25);
            one.addView(icon);

            TextView label = new TextView(this);
            label.setText(entryNames[i]);
            label.setTextColor(C_SUB);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpp.topMargin = dp(7);
            one.addView(label, lpp);

            entries.addView(one, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            one.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (BiliApi.loggedInUid() == null && !"cache".equals(mode)
                            && !"history".equals(mode)) {
                        Toast.makeText(MainActivity.this, "请先登录 B 站账号", Toast.LENGTH_SHORT).show();
                        promptLoginOrOut();
                        return;
                    }
                    if ("msg".equals(mode)) {
                        startActivity(new Intent(MainActivity.this, MsgActivity.class));
                        return;
                    }
                    Intent it = new Intent(MainActivity.this, ShelfActivity.class);
                    it.putExtra("mode", mode);
                    startActivity(it);
                }
            });
        }
        LinearLayout.LayoutParams ep2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep2.leftMargin = dp(16);
        ep2.rightMargin = dp(16);
        col.addView(entries, ep2);

        // ==================== 设置 / 游戏厅 / 留声机（同一张卡片，三行）====================
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(roundBgLine(C_CARD, 14, DARK ? 0xFF2A2F38 : 0xFFE9ECF1, this));
        group.setPadding(0, dp(4), 0, dp(4));

        group.addView(mineGroupRow(new SettingsActivity.HexIcon(this,
                        SettingsActivity.HexIcon.SETTINGS), "设置", "外观 / 播放 / 缓存 / 账号",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                }));
        group.addView(mineGroupDivider());
        group.addView(mineGroupRow(Ic.view(this, "g_game", 0xFFFB7299, 21), "游戏厅",
                "十款经典小游戏 · 离线可玩", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(MainActivity.this, GameActivity.class));
                        } catch (Throwable e) {
                            Toast.makeText(MainActivity.this,
                                    "打开游戏厅失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }));
        group.addView(mineGroupDivider());
        group.addView(mineGroupRow(Ic.view(this, "g_record", 0xFF3FA9E8, 21), "留声机",
                "本地音乐 · 录音棚 · 唤起 QQ 音乐", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(MainActivity.this, MusicActivity.class));
                        } catch (Throwable e) {
                            Toast.makeText(MainActivity.this,
                                    "打开留声机失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }));
        // 【v4.14.0】小说吧：书源聚合阅读 + 护眼模式（留声机下方）
        group.addView(mineGroupDivider());
        group.addView(mineGroupRow(Ic.view(this, "g_set", 0xFF8BC34A, 21), "小说吧",
                "书源聚合阅读 · 护眼模式", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(MainActivity.this, NovelActivity.class));
                        } catch (Throwable e) {
                            Toast.makeText(MainActivity.this,
                                    "打开小说吧失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }));

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(16);
        glp.rightMargin = dp(16);
        glp.topMargin = dp(12);
        col.addView(group, glp);

        // ==================== 分区 tab ====================
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(dp(12), dp(16), dp(12), 0);
        String[] tabNames2 = {"主页", "动态", "投稿", "收藏"};
        for (int i = 0; i < tabNames2.length; i++) {
            final int ti = i;
            LinearLayout one = new LinearLayout(this);
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER_HORIZONTAL);
            one.setPadding(0, dp(8), 0, dp(7));

            TextView t = new TextView(this);
            t.setText(tabNames2[i]);
            t.setTextColor(i == 0 ? C_ACC : C_SUB);
            t.setTextSize(14.5f);
            if (i == 0) t.setTypeface(Typeface.DEFAULT_BOLD);
            one.addView(t);
            mineTabs[i] = t;

            View underline = new View(this);
            underline.setBackground(roundBg(C_ACC, 2, this));
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(20), dp(3));
            ulp.topMargin = dp(6);
            underline.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            one.addView(underline, ulp);
            mineTabLines[i] = underline;

            one.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (ti == 0) {
                        selectMineTab(0);
                        if (mineScroll != null) mineScroll.smoothScrollTo(0, 0);
                        return;
                    }
                    if (ti == 1) {
                        String u = BiliApi.loggedInUid();
                        if (u == null) { promptLoginOrOut(); return; }
                        Intent it = new Intent(MainActivity.this, DynamicsActivity.class);
                        it.putExtra("mid", u);
                        it.putExtra("name", mineName.getText().toString());
                        startActivity(it);
                        return;
                    }
                    if (ti == 3) {
                        if (BiliApi.loggedInUid() == null) { promptLoginOrOut(); return; }
                        Intent it = new Intent(MainActivity.this, ShelfActivity.class);
                        it.putExtra("mode", "fav");
                        startActivity(it);
                        return;
                    }
                    // 投稿：本页就有，滚过去即可
                    selectMineTab(2);
                    if (mineScroll != null && mineVideoHead != null) {
                        mineScroll.smoothScrollTo(0, Math.max(0, mineVideoHead.getTop() - dp(8)));
                    }
                }
            });
            tabsRow.addView(one, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(tabsRow);

        View tabLine = new View(this);
        tabLine.setBackgroundColor(DARK ? 0xFF23262C : 0xFFE6E8EC);
        col.addView(tabLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f)));

        // ==================== 视频数 + 更多 ====================
        LinearLayout vHead = new LinearLayout(this);
        vHead.setOrientation(LinearLayout.HORIZONTAL);
        vHead.setGravity(Gravity.CENTER_VERTICAL);
        vHead.setPadding(dp(16), dp(16), dp(16), dp(4));
        mineVideoHead = vHead;

        mineVideoCount = new TextView(this);
        mineVideoCount.setTextColor(C_FG);
        mineVideoCount.setTextSize(15);
        mineVideoCount.setTypeface(Typeface.DEFAULT_BOLD);
        mineVideoCount.setText("视频 —");
        vHead.addView(mineVideoCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView moreV = new TextView(this);
        moreV.setText("更多 ›");
        moreV.setTextColor(C_SUB);
        moreV.setTextSize(11.5f);
        moreV.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String uid = BiliApi.loggedInUid();
                if (uid == null) { promptLoginOrOut(); return; }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://space.bilibili.com/" + uid + "/video")));
                } catch (Throwable e) {
                    Toast.makeText(MainActivity.this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        vHead.addView(moreV);
        col.addView(vHead);

        // ==================== 投稿网格 ====================
        mineVideoBox = new LinearLayout(this);
        mineVideoBox.setOrientation(LinearLayout.VERTICAL);
        mineVideoBox.setPadding(dp(16), 0, dp(16), 0);
        col.addView(mineVideoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mineVideoBox.addView(mineEmptyTip("登录后显示你的投稿"));



        // ---- 缓存概览 ----
        mineCacheLine = new TextView(this);
        mineCacheLine.setTextColor(C_SUB);
        mineCacheLine.setTextSize(11.5f);
        mineCacheLine.setPadding(dp(16), dp(14), dp(16), 0);
        mineCacheLine.setText("离线缓存：0 个视频（MP4，保存在 电影/BiliAudio）");
        col.addView(mineCacheLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 登录 / 退出 ----
        Button loginBtn = new Button(this);
        loginBtn.setText(BiliApi.loggedInUid() != null ? "退出登录" : "登录 / 注册 B 站账号");
        loginBtn.setTextColor(Color.WHITE);
        loginBtn.setTextSize(14.5f);
        loginBtn.setAllCaps(false);
        loginBtn.setBackground(roundBg(BiliApi.loggedInUid() != null
                ? (DARK ? Color.parseColor("#2c3038") : Color.parseColor("#D9DDE5"))
                : C_ACC, 12, this));
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptLoginOrOut(); }
        });
        mineLoginBtn = loginBtn;
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lbp.topMargin = dp(18);
        lbp.leftMargin = dp(16);
        lbp.rightMargin = dp(16);
        col.addView(loginBtn, lbp);

        // ---- 崩溃日志 ----
        TextView diag = new TextView(this);
        diag.setText("崩溃日志");
        diag.setTextColor(DARK ? 0xFF5C626D : 0xFF9AA0A8);
        diag.setTextSize(11);
        diag.setGravity(Gravity.CENTER);
        diag.setPadding(0, dp(16), 0, dp(10));
        diag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String log = CrashHandler.readCrashLog(
                        getExternalFilesDir(null), getFilesDir());
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("崩溃日志")
                        .setMessage(log)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        col.addView(diag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(mineScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateMineLocal();
        refreshMine();
    }

    /** 两色混合，t 为 a 的占比（0~1） */
    static int mixColor(int a, int b, float t) {
        float u = 1f - t;
        int r = (int) (((a >> 16) & 0xFF) * t + ((b >> 16) & 0xFF) * u);
        int g = (int) (((a >> 8) & 0xFF) * t + ((b >> 8) & 0xFF) * u);
        int bl = (int) ((a & 0xFF) * t + (b & 0xFF) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 分组卡片里的一行（无独立背景，靠父卡片圆角） */
    LinearLayout mineGroupRow(View icon, String title, String sub,
                              View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setClickable(true);

        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(26), dp(26)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(14.5f);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(sub);
        d.setTextColor(C_SUB);
        d.setTextSize(11);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        texts.addView(d, dlp);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        row.addView(texts, tlp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(DARK ? 0xFF6F7581 : 0xFFA6ACB5);
        arrow.setTextSize(20);
        row.addView(arrow);

        row.setOnClickListener(click);
        return row;
    }

    /** 分组卡片里的分隔线：从文字左边缘开始，右侧留白 */
    View mineGroupDivider() {
        View line = new View(this);
        line.setBackgroundColor(DARK ? 0xFF2A2E36 : 0xFFEDF0F4);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(0.7f));
        p.leftMargin = dp(54);
        line.setLayoutParams(p);
        return line;
    }

    /** 分区 tab 高亮切换 */
    void selectMineTab(int idx) {
        for (int i = 0; i < mineTabs.length; i++) {
            TextView t = mineTabs[i];
            if (t == null) continue;
            boolean on = (i == idx);
            t.setTextColor(on ? C_ACC : C_SUB);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (mineTabLines[i] != null) {
                mineTabLines[i].setVisibility(on ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** 昵称下方的小徽章（粉丝团 / 成就勋章），描边胶囊样式 */
    TextView mineBadge(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_SUB);
        t.setTextSize(9.5f);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(7));
        g.setColor(0x00000000);
        g.setStroke(dp(1), DARK ? 0xFF3A3F48 : 0xFFD3D7DE);
        t.setBackground(g);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    /** 右上「更多」：把设置/消息/崩溃日志/关于收在这里 */
    void showMineMore() {
        final String[] items = {"设置", "消息", "崩溃日志", "刷新资料"};
        new AlertDialog.Builder(this)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        } else if (which == 1) {
                            startActivity(new Intent(MainActivity.this, MsgActivity.class));
                        } else if (which == 2) {
                            String log2 = CrashHandler.readCrashLog(
                                    getExternalFilesDir(null), getFilesDir());
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("崩溃日志").setMessage(log2)
                                    .setPositiveButton("知道了", null).show();
                        } else {
                            refreshMine();
                            Toast.makeText(MainActivity.this, "正在刷新…", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    /** 「详情」：账号资料弹窗 */
    void showMineDetail() {
        String uid = BiliApi.loggedInUid();
        if (uid == null) {
            promptLoginOrOut();
            return;
        }
        String info = "UID：" + uid
                + "\nB币：" + mineCoinsText
                + "\n签名：" + (mineSign != null ? mineSign.getText().toString() : "");
        new AlertDialog.Builder(this)
                .setTitle("账号详情")
                .setMessage(info)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 本地即可确定的部分：缓存数量 */
    void updateMineLocal() {
        if (mineCacheLine == null) return;
        int n = 0;
        try {
            n = getSharedPreferences("video_cache", MODE_PRIVATE)
                    .getStringSet("files", new java.util.HashSet<String>()).size();
        } catch (Throwable ignore) { }
        mineCacheLine.setText("离线缓存：" + n + " 个视频（MP4，保存在 电影/BiliAudio）");
    }

    /** 联网拉取用户资料；未登录则显示引导态 */
    void refreshMine() {
        if (mineName == null) return;
        updateMineLocal();
        final String uid = BiliApi.loggedInUid();
        if (mineLoginBtn != null) {
            mineLoginBtn.setText(uid != null ? "退出登录" : "登录 / 注册 B 站账号");
            mineLoginBtn.setBackground(roundBg(uid != null
                    ? (DARK ? Color.parseColor("#2c3038") : Color.parseColor("#D9DDE5"))
                    : C_ACC, 10, this));
            mineLoginBtn.setTextColor(uid != null && !DARK ? C_FG : Color.WHITE);
        }
        if (uid == null) {
            mineName.setText("未登录");
            mineVip.setVisibility(View.GONE);
            mineLv.setText("LV0");
            mineSign.setText("登录后同步头像、粉丝、投稿与收藏");
            mineUid.setText("");
            mineExp.setText("登录后可见");
            setExpBar(0f);
            for (int i = 0; i < 3; i++) mineStats[i].setText("—");
            mineAvatar.setImageBitmap(null);
            if (mineBanner != null) mineBanner.setImageBitmap(null);
            if (mineVideoCount != null) mineVideoCount.setText("视频 —");
            if (mineVideoBox != null) {
                mineVideoBox.removeAllViews();
                mineVideoBox.addView(mineEmptyTip("登录后显示你的投稿"));
            }
            return;
        }

        mineUid.setText("UID " + uid);
        new Thread(new Runnable() { @Override public void run() {
            final JSONObject[] card = {null};
            final JSONObject[] stat = {null};
            final JSONArray[] vids = {null};
            try { card[0] = BiliApi.userCard(uid); } catch (Throwable ignore) { }
            // 【v4.13 经验校准】userCard(别人主页接口) 并不返回 level_info，
            // 旧版从这里读等级经验就拿不到 → 显示 0/—。改用登录态 nav 接口的真实数据覆盖。
            try {
                JSONObject nav = BiliApi.navInfo();
                JSONObject nli = nav.optJSONObject("level_info");
                if (nli != null && card[0] != null) {
                    JSONObject cc = card[0].optJSONObject("card");
                    if (cc == null) cc = card[0];
                    cc.put("level_info", nli);
                }
            } catch (Throwable ignore) { }
            try { stat[0] = BiliApi.relationStat(uid); } catch (Throwable ignore) { }
            try { vids[0] = BiliApi.userVideos(uid, 1); } catch (Throwable ignore) { }

            ui.post(new Runnable() { @Override public void run() {
                if (currentTab != 4 || mineName == null) return;
                try {
                    String face = "";
                    if (card[0] != null) {
                        JSONObject c = card[0].optJSONObject("card");
                        if (c == null) c = card[0];
                        String nm = c.optString("name", "");
                        if (!nm.isEmpty()) mineName.setText(nm);
                        String sign = c.optString("sign", "");
                        mineSign.setText(sign.isEmpty() ? "这个人很神秘，什么都没有写" : sign);

                        face = c.optString("face", "");
                        if (!face.isEmpty()) {
                            face = ImgLoader.https(face);
                            String small = face.contains("@") ? face : face + "@160w_160h_1c.jpg";
                            String big = face.contains("@") ? face.split("@")[0] + "@480w_480h_1c.jpg" : face + "@480w_480h_1c.jpg";
                            ImgLoader.load(mineAvatar, small, dp(88), dp(88));
                            if (mineBanner != null) ImgLoader.load(mineBanner, big, dp(178), dp(178));
                        }

                        JSONObject li = c.optJSONObject("level_info");
                        int lv = li != null ? li.optInt("current_level", 0) : 0;
                        int cur = li != null ? li.optInt("current_exp", 0) : 0;
                        int next = li != null ? li.optInt("next_exp", 0) : 0;
                        int cmin = li != null ? li.optInt("current_min", 0) : 0;
                        mineLv.setText("LV" + Math.max(0, lv));
                        // 【v4.13 经验校准】官方口径：current_exp / next_exp 都是「累计经验」，
                        // 进度条要按当前等级区间 (current_exp - current_min)/(next_exp - current_min)，
                        // 与参考图「21752/28800 + 约六成进度条」一致；满级 next_exp=0 或 -1。
                        if (next > cmin && cur >= 0) {
                            mineExp.setText(cur + "/" + next);
                            float ratio = (cur - cmin) / (float) (next - cmin);
                            setExpBar(Math.max(0f, Math.min(1f, ratio)));
                        } else if (lv >= 6 || next <= 0) {
                            mineExp.setText("经验已满");
                            setExpBar(1f);
                        } else {
                            mineExp.setText("经验 " + cur);
                            setExpBar(0.3f);
                        }

                        JSONObject vip = c.optJSONObject("vip");
                        boolean isVip = vip != null && (vip.optInt("status", 0) == 1
                                || vip.optInt("type", 0) > 0);
                        if (isVip) {
                            mineVip.setText("年度大会员");
                            mineVip.setVisibility(View.VISIBLE);
                        } else {
                            mineVip.setVisibility(View.GONE);
                        }

                        int like = card[0].optInt("like_num", 0);
                        int fans = card[0].optInt("follower", c.optInt("fans", 0));
                        mineStats[0].setText(VideoItem.views(fans));
                        mineStats[2].setText(VideoItem.views(like));

                        double money = c.optDouble("money", 0);
                        if (money <= 0) money = 0;
                        mineCoinsText = String.format("%.1f", money);
                    } else {
                        mineName.setText("UID " + uid);
                        mineSign.setText("资料获取失败（可能被风控，点右上「更多 → 刷新资料」重试）");
                    }

                    if (stat[0] != null) {
                        mineStats[1].setText(VideoItem.views(stat[0].optInt("following", 0)));
                        if (stat[0].optInt("follower", 0) > 0) {
                            mineStats[0].setText(VideoItem.views(stat[0].optInt("follower", 0)));
                        }
                    }

                    // 投稿网格
                    if (mineVideoBox != null) {
                        mineVideoBox.removeAllViews();
                        JSONArray v = vids[0];
                        if (v == null || v.length() == 0) {
                            mineVideoBox.addView(mineEmptyTip("还没有投稿"));
                            if (mineVideoCount != null) mineVideoCount.setText("视频 0");
                        } else {
                            if (mineVideoCount != null) {
                                mineVideoCount.setText("视频 " + v.length()
                                        + (v.length() >= 30 ? "+" : ""));
                            }
                            buildMineVideos(v);
                        }
                    }
                } catch (Throwable ignore) { }
            } });
        } }).start();
    }

    /** 等级进度条：按比例设宽度（父容器宽度在 layout 之后才准，所以 post 一次） */
    void setExpBar(final float t) {
        if (mineExpBarFill == null) return;
        mineExpBarFill.post(new Runnable() {
            @Override public void run() {
                View parent = (View) mineExpBarFill.getParent();
                int w = parent != null ? parent.getWidth() : 0;
                if (w <= 0) return;
                ViewGroup.LayoutParams lp = mineExpBarFill.getLayoutParams();
                float p = Math.max(0f, Math.min(1f, t));
                lp.width = Math.max(dp(2), (int) (w * p));
                mineExpBarFill.setLayoutParams(lp);
            }
        });
    }

    TextView mineEmptyTip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_SUB);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(24), 0, dp(24));
        return t;
    }

    /** 投稿网格：两列卡片，最多 6 个；奇数个时补一个占位，避免最后一行被拉伸 */
    void buildMineVideos(JSONArray v) {
        int total = Math.min(6, v.length());
        int sw = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(10);
        int cardW = (sw - dp(16) * 2 - gap) / 2;

        LinearLayout row = null;
        int colIdx = 0;
        for (int i = 0; i < total; i++) {
            JSONObject o = v.optJSONObject(i);
            if (o == null) continue;
            if (colIdx % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(10);
                mineVideoBox.addView(row, rp);
            }
            View card = mineVideoCard(o, cardW);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cardW,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = (colIdx % 2 == 0) ? gap : 0;
            row.addView(card, cp);
            colIdx++;
        }
        if (row != null && colIdx % 2 == 1) {
            View filler = new View(this);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(cardW,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.leftMargin = gap;
            row.addView(filler, fp);
        }
    }

    View mineVideoCard(JSONObject o, int cardW) {
        final String bvid = o.optString("bvid", "");
        final String title = o.optString("title", "");
        String pic = o.optString("pic", "");
        pic = ImgLoader.https(pic);
        long play = o.optLong("play", 0);
        long danmaku = o.optLong("video_review", 0);
        String len = o.optString("length", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setBackground(roundBgLine(C_CARD, 10, DARK ? 0xFF2A2F38 : 0xFFE9ECF1, this));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v2, android.graphics.Outline out) {
                out.setRoundRect(0, 0, v2.getWidth(), v2.getHeight(), dp(10));
            }
        });

        FrameLayout thumb = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(ImgLoader.placeholderColor());
        thumb.addView(img, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!pic.isEmpty()) {
            ImgLoader.load(img, pic, cardW, cardW * 9 / 16);
        }

        // 时长久：右下角小黑胶囊，别做整条渐变，卡片才干净
        if (!len.isEmpty()) {
            TextView tl = new TextView(this);
            tl.setText(len);
            tl.setTextColor(0xFFFFFFFF);
            tl.setTextSize(9);
            android.graphics.drawable.GradientDrawable tg =
                    new android.graphics.drawable.GradientDrawable();
            tg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            tg.setCornerRadius(dp(3));
            tg.setColor(0x99000000);
            tl.setBackground(tg);
            tl.setPadding(dp(4), dp(1), dp(4), dp(1));
            android.widget.FrameLayout.LayoutParams tlp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            tlp.rightMargin = dp(5);
            tlp.bottomMargin = dp(5);
            thumb.addView(tl, tlp);
        }

        card.addView(thumb, new LinearLayout.LayoutParams(cardW, cardW * 9 / 16));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(12);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLineSpacing(dp(1.5f), 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        tp.rightMargin = dp(8);
        tp.topMargin = dp(7);
        card.addView(t, tp);

        TextView meta = new TextView(this);
        meta.setText("▶ " + VideoItem.views(play) + "  ·  弹幕 " + VideoItem.views(danmaku));
        meta.setTextColor(C_SUB);
        meta.setTextSize(9.5f);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = dp(8);
        mp.rightMargin = dp(8);
        mp.topMargin = dp(4);
        mp.bottomMargin = dp(9);
        card.addView(meta, mp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bvid.isEmpty()) return;
                Intent it = new Intent(MainActivity.this, PlayActivity.class);
                it.putExtra("bvid", bvid);
                it.putExtra("ptitle", title);
                startActivity(it);
            }
        });
        return card;
    }

    /** 三栏：0 粉丝 / 1 关注 / 2 获赞（获赞没有公开列表，点了给个提示） */
    void openStat(int idx) {
        String uid = BiliApi.loggedInUid();
        if (uid == null) {
            Toast.makeText(this, "请先登录 B 站账号", Toast.LENGTH_SHORT).show();
            promptLoginOrOut();
            return;
        }
        String name = mineName != null ? mineName.getText().toString() : "";
        if (idx == 2) {
            Toast.makeText(this, "获赞数来自作品累计，没有单独列表", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, RelationActivity.class);
        it.putExtra("mode", idx == 1 ? "following" : "followers");
        it.putExtra("mid", uid);
        it.putExtra("name", name);
        startActivity(it);
    }

    void promptLoginOrOut() {
        if (BiliApi.loggedInUid() != null) {
            new AlertDialog.Builder(this)
                    .setTitle("已登录 UID " + BiliApi.loggedInUid())
                    .setPositiveButton("退出登录", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            BiliApi.clearLoginCookie();
                            getSharedPreferences("bili_login", MODE_PRIVATE).edit().clear().apply();
                            refreshLoginTop();
                            refreshMine();
                            Toast.makeText(MainActivity.this, "已退出", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("登录哔哩哔哩")
                    .setMessage("两种方式任选：\n\n· 二维码登录：用 B 站手机 App 扫码确认\n· 验证码登录：手机号 + 短信验证码（B 站官方页面）\n\n登录后可解锁 1080p 与 320k 音质")
                    .setPositiveButton("二维码登录", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            // 【v4.15.14】改为直接加载 B 站官方扫码页（桌面 UA），
                            // 页面自带二维码，由官方页面自己轮询 —— 不再依赖 App 的 HTTP 请求
                            try {
                                if (!BxqApp.webViewOk)
                                    throw new IllegalStateException("本机 WebView 不可用");
                                startActivity(new Intent(MainActivity.this, WebQrLoginActivity.class));
                            } catch (Throwable e) {
                                Toast.makeText(MainActivity.this,
                                        "扫码页打开失败，改用短信登录", Toast.LENGTH_SHORT).show();
                                startActivity(new Intent(MainActivity.this, WebViewLoginActivity.class));
                            }
                        }
                    })
                    .setNeutralButton("验证码登录", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            startActivity(new Intent(MainActivity.this, WebViewLoginActivity.class));
                        }
                    })
                    .setNegativeButton("取消", null).show();
        }
    }

    // ------------------------------------------------------------ 顶部频道内容

    /** 把统一结构的视频 JSONArray 转成 rows（热门 / 分区榜 / 直播共用） */
    void fillFromJson(final JSONArray list, final String emptyTip, final int tabAtPost) {
        if (!refreshing) addLoading();
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) continue;
                        String bv = o.optString("bvid", "");
                        if (bv.isEmpty()) bv = "av" + o.optLong("aid", 0);
                        if (bv.equals("av0")) continue;
                        String up = o.optJSONObject("owner") != null
                                ? o.getJSONObject("owner").optString("name", "") : "";
                        long play = o.optJSONObject("stat") != null
                                ? o.getJSONObject("stat").optLong("view", 0) : 0;
                        int dur = o.optInt("duration", 0);
                        StringBuilder sub = new StringBuilder();
                        if (!up.isEmpty()) sub.append("UP · ").append(up);
                        if (play > 0) {
                            if (sub.length() > 0) sub.append("  ·  ");
                            sub.append(VideoItem.views(play)).append("播放");
                        }
                        if (dur > 0) {
                            if (sub.length() > 0) sub.append("  ·  ");
                            sub.append(VideoItem.dur(dur));
                        }
                        got.add(new VideoItem(bv, BiliApi.cleanTitle(o.optString("title", "")),
                                sub.toString(), o.optString("pic", "")));
                    }
                }
            } catch (final Throwable e) { err[0] = e.getMessage(); }
            ui.post(new Runnable() { @Override public void run() {
                if (currentTab != tabAtPost) { hideRefreshTip(); return; }
                setResult(got, emptyTip + (err[0] == null ? "" : "（" + err[0] + "）"));
                hideRefreshTip();
            } });
        } }).start();
    }

    void loadPopular() {
        if (!refreshing) addLoading();
        final int ch = currentChannel;
        new Thread(new Runnable() { @Override public void run() {
            try {
                final JSONArray list = BiliApi.popular(1 + (int) (Math.random() * 5));
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) fillFromJson(list, "热门为空", 0);
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) {
                        rows.clear();
                        rows.add(new VideoItem("", "热门加载失败：" + e.getMessage(), "", null));
                        notifyData(); hideRefreshTip();
                    }
                } });
            }
        } }).start();
    }

    void loadRanking(final int rid) {
        if (!refreshing) addLoading();
        final int ch = currentChannel;
        new Thread(new Runnable() { @Override public void run() {
            try {
                final JSONArray list = BiliApi.ranking(rid);
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) fillFromJson(list, "该分区暂无内容", 0);
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (currentTab == 0 && currentChannel == ch) {
                        rows.clear();
                        rows.add(new VideoItem("", "分区加载失败：" + e.getMessage(), "", null));
                        notifyData(); hideRefreshTip();
                    }
                } });
            }
        } }).start();
    }

    /** 直播：直播流接口风控较重，拉起系统浏览器进入 B 站直播页 */
    void loadLive() {
        try {
            Intent in = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://live.bilibili.com/"));
            startActivity(in);
        } catch (Throwable e) {
            Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show();
        }
        // 频道切回推荐，避免停留在空白页
        currentChannel = 0;
        refreshChannelStyle();
        loadRecommend(false);
    }

    /** 动态：关注 UP 主的最新投稿（复用关注列表数据源） */
    void loadDynamic() {
        if (BiliApi.loggedInUid() == null) {
            rows.clear();
            rows.add(new VideoItem("", "未登录——先到「我的」页登录，再查看动态", "", null));
            notifyData();
            hideRefreshTip();
            return;
        }
        loadFollow();
    }

    // ------------------------------------------------------------ 关注

    void loadFollow() {
        if (BiliApi.loggedInUid() == null) {
            rows.add(new VideoItem("", "未登录——先到「我的」页登录，再查看关注", "", null));
            notifyData();
            hideRefreshTip();
            return;
        }
        if (!refreshing) addLoading();
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            final String[] err = {null};
            try {
                JSONArray list = BiliApi.followings();
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.getJSONObject(i);
                        String uname = o.optString("uname", "");
                        if (uname.isEmpty()) continue;
                        String face = o.optString("face", "");
                        face = ImgLoader.face(face, 160);
                        String sign = o.optString("sign", "").trim();
                        VideoItem vi = new VideoItem("up:" + uname, uname,
                                sign.isEmpty() ? "点击查看 TA 的作品" : sign, face);
                        vi.extra = o.optString("mid", "");
                        got.add(vi);
                    }
                }
            } catch (final Throwable e) {
                err[0] = e.getMessage();
            }
            ui.post(new Runnable() { @Override public void run() {
                if (currentTab != 1) { hideRefreshTip(); return; }
                setResult(got, "关注列表为空（" + (err[0] == null ? "空" : err[0]) + "）");
                hideRefreshTip();
            } });
        } }).start();
    }
}
