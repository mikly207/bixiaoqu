package com.bixiaoqu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：外观（单/双列、开屏动画）、播放（默认解码、弹幕）、存储、账号、关于。
 * 纯代码布局 + 自绘六边形图标，不引入任何图片资源。
 */
public class SettingsActivity extends Activity {

    static final int C_LINE = Color.parseColor("#2c3038");

    TextView layoutValue, cacheValue, decoderValue, accountValue, dpiValue;
    /** 【v4.35.0】播放调度档位显示 */
    TextView scheduleValue;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyTheme(this);
        appliedStamp = MainActivity.themeStamp;
        try {
            setContentView(build());
        } catch (Throwable e) {
            TextView tv = new TextView(this);
            tv.setText("设置页加载失败：\n" + android.util.Log.getStackTraceString(e));
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(30), dp(60), dp(30), dp(30));
            setContentView(tv);
        }
    }

    int appliedStamp = -1;

    @Override protected void onResume() {
        super.onResume();
        if (appliedStamp != MainActivity.themeStampOf(this)) {
            recreate();
            return;
        }
        refreshValues();
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        // ---- 顶栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(14), dp(8));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MainActivity.C_FG);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(MainActivity.C_FG);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(6);
        top.addView(title, tp);

        TextView ver = new TextView(this);
        ver.setText("哔小趣 " + BxqApp.VERSION + " · Dynamo");
        ver.setTextColor(Color.parseColor("#6f7581"));
        ver.setTextSize(11);
        top.addView(ver);
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(14), dp(24));
        sv.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 外观 ----
        col.addView(section("外观"));
        LinearLayout look = card();
        // 【v4.10.0】深色 / 浅色模式入口
        themeValue = addRow(look, HexIcon.SPARK, "深色模式", "", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, ThemeActivity.class));
            }
        });
        addDivider(look);
        // 【v4.33.0】全局主题色（iOS 皮肤下入口隐藏 —— 那版固定系统粉）
        if (!UiSkin.ios(this)) {
            accentValue = addAccentRow(look);
            addDivider(look);
        }
        layoutValue = addRow(look, HexIcon.LAYOUT, "推荐页布局", "", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseLayout(); }
        });
        addDivider(look);
        // 【v4.14.2】界面缩放 / DPI：手表、小屏设备可调小，一屏显示更多
        dpiValue = addRow(look, HexIcon.SCREEN, "界面缩放 / DPI", "", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseDpi(); }
        });
        addDivider(look);
        addSwitchRow(look, HexIcon.SPARK, "开屏动画",
                Prefs.splash(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                        Prefs.setSplash(SettingsActivity.this, c);
                    }
                });
        col.addView(look);

        // ---- 播放 ----
        col.addView(section("播放"));
        LinearLayout play = card();
        decoderValue = addRow(play, HexIcon.PLAY, "默认解码方式", "", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseDecoder(); }
        });
        addDivider(play);
        // 【v4.35.0】播放调度：一键切换「超流畅 / 超清晰 / 平衡 / 节能」
        scheduleValue = addRow(play, HexIcon.SPARK, "播放调度", "", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSchedule(); }
        });
        addDivider(play);
        addSwitchRow(play, HexIcon.DANMAKU, "默认开启弹幕",
                Prefs.danmaku(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                        Prefs.setDanmaku(SettingsActivity.this, c);
                    }
                });
        addDivider(play);
        addSwitchRow(play, HexIcon.SCREEN, "竖屏视频横屏优化",
                Prefs.portraitOptimize(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                        Prefs.setPortraitOptimize(SettingsActivity.this, c);
                    }
                });
        col.addView(play);

        TextView playTip = new TextView(this);
        playTip.setText("播放调度：一键决定清晰度上限、并行分段、预取与连接池。"
                + "超流畅适合老设备或网络差，超清晰适合好设备配快网，平衡兼顾流畅与清晰，节能只求不卡。\n"
                + "竖屏优化：竖屏视频自动铺满整屏，画面按原比例放大填满、不留黑边；点右上角「退出铺满」可还原详情页。");
        playTip.setTextColor(Color.parseColor("#6f7581"));
        playTip.setTextSize(11);
        playTip.setPadding(dp(8), dp(8), dp(8), 0);
        col.addView(playTip);

        // ---- 存储 ----
        col.addView(section("存储"));
        LinearLayout store = card();
        cacheValue = addRow(store, HexIcon.BROOM, "清除封面缓存", "", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearCache(); }
        });
        addDivider(store);
        addRow(store, HexIcon.PLAY, "清除播放记录", "", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearPos(); }
        });
        addDivider(store);
        addRow(store, HexIcon.INFO, "清除搜索历史", "", new View.OnClickListener() {
            @Override public void onClick(View v) {
                SearchHistory.clear(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "已清除搜索历史", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(store);

        // ---- 账号 ----
        col.addView(section("账号"));
        LinearLayout acc = card();
        accountValue = addRow(acc, HexIcon.USER, "哔哩哔哩账号", "", new View.OnClickListener() {
            @Override public void onClick(View v) { loginOrOut(); }
        });
        col.addView(acc);

        // ---- 品牌卡（与上方账号卡留出间距，避免圆角重叠）----
        LinearLayout brand = card();
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setPadding(0, dp(22), 0, dp(18));
        ImageView logo = new ImageView(this);
        int iconId = getResources().getIdentifier("logo", "drawable", getPackageName());
        if (iconId != 0) logo.setImageResource(iconId);
        else logo.setImageResource(android.R.drawable.sym_def_app_icon);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));
        // 彩蛋：连续点击 logo 7 次进入跳一跳小游戏
        attachEgg(logo);
        TextView brandName = new TextView(this);
        brandName.setText("哔小趣");
        brandName.setTextColor(MainActivity.C_FG);
        brandName.setTextSize(17);
        brandName.setTypeface(Typeface.DEFAULT_BOLD);
        brandName.setGravity(Gravity.CENTER);
        brandName.setPadding(0, dp(10), 0, 0);
        brand.addView(brandName);
        TextView brandSub = new TextView(this);
        brandSub.setText("软件作者 Dynamo · " + BxqApp.VERSION);
        brandSub.setTextColor(MainActivity.C_SUB);
        brandSub.setTextSize(11);
        brandSub.setGravity(Gravity.CENTER);
        brandSub.setPadding(0, dp(5), 0, 0);
        brand.addView(brandSub);
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbp.topMargin = dp(14);
        col.addView(brand, bbp);

        col.addView(section("关于"));
        LinearLayout about = card();
        addRow(about, HexIcon.INFO, "版本信息", "v" + BxqApp.VERSION.replace("v", ""),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showAbout(); }
                });
        addDivider(about);
        addRow(about, HexIcon.BUG, "崩溃日志", "", new View.OnClickListener() {
            @Override public void onClick(View v) { showCrashLog(); }
        });
        col.addView(about);

        TextView foot = new TextView(this);
        foot.setText("哔小趣 · 非官方 B 站客户端\n仅用于个人学习与日常观看，内容版权归哔哩哔哩及原作者所有");
        foot.setTextColor(Color.parseColor("#565c67"));
        foot.setTextSize(10);
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(0, dp(26), 0, 0);
        col.addView(foot);

        return root;
    }

    TextView themeValue;
    /** 【v4.33.0】主题颜色行右侧的色点 */
    View accentDot;
    TextView accentValue;

    void refreshValues() {
        if (themeValue != null) {
            int m = Prefs.themeMode(this);
            themeValue.setText(m == Prefs.THEME_SYSTEM ? "跟随系统"
                    : (m == Prefs.THEME_LIGHT ? "浅色" : "深色"));
        }
        if (accentValue != null) {
            accentValue.setText(Theme.nameOf(this));
            accentValue.setTextColor(Theme.acc());
        }
        if (accentDot != null) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Theme.acc());
            accentDot.setBackground(g);
        }
        if (layoutValue != null) {
            layoutValue.setText(Prefs.twoColumn(this) ? "双列" : "单列");
        }
        if (dpiValue != null) {
            dpiValue.setText(DensityUtil.label(this));
        }
        if (decoderValue != null) {
            decoderValue.setText(Prefs.decoderName(Prefs.decoder(this)));
        }
        if (scheduleValue != null) {
            int mode = Prefs.scheduleMode(this);
            scheduleValue.setText(ScheduleProfile.nameOf(mode));
            scheduleValue.setTextColor(mode == ScheduleProfile.MODE_CUSTOM
                    ? MainActivity.C_ACC : MainActivity.C_SUB);
        }
        if (accountValue != null) {
            String uid = BiliApi.loggedInUid();
            accountValue.setText(uid == null ? "未登录" : "UID " + uid);
        }
        if (cacheValue != null) {
            cacheValue.setText(sizeText(ImgLoader.diskSize()));
        }
    }

    static String sizeText(long n) {
        if (n <= 0) return "0 MB";
        if (n < 1024 * 1024) return String.format("%.0f KB", n / 1024.0);
        return String.format("%.1f MB", n / 1048576.0);
    }

    // ------------------------------------------------------------ 行构建

    TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(MainActivity.C_SUB);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(8), dp(20), dp(8), dp(8));
        return v;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        // 【v4.14.5】液态玻璃卡片：半透明渐变 + 细高光描边，通透有质感
        l.setBackground(GlassUtil.card(MainActivity.DARK, 14, this));
        return l;
    }

    void addDivider(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(54);
        card.addView(line, lp);
    }

    /** 行：图标 + 标题 + 右侧值 + 箭头；返回值 TextView 便于更新 */
    /**
     * 【v4.33.0】「主题颜色」行：右侧不是一个普通的值，而是一枚跟随当前色的圆点 + 颜色名。
     * 单独写一份而不是改 addRow 签名，是为了不动其它 30 多行的既有调用。
     */
    TextView addAccentRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(true);
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) Bounce.pressIn(v);
                else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) Bounce.release(v);
                return false;
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, AccentActivity.class));
            }
        });

        row.addView(new HexIcon(this, HexIcon.SPARK), new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView tv = new TextView(this);
        tv.setText("主题颜色");
        tv.setTextColor(MainActivity.C_FG);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        row.addView(tv, tp);

        accentDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Theme.acc());
        accentDot.setBackground(dot);
        LinearLayout.LayoutParams dpx = new LinearLayout.LayoutParams(dp(16), dp(16));
        dpx.rightMargin = dp(8);
        row.addView(accentDot, dpx);

        TextView val = new TextView(this);
        val.setText(Theme.nameOf(this));
        val.setTextColor(Theme.acc());
        val.setTextSize(13);
        val.setSingleLine(true);
        row.addView(val);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.parseColor("#6f7581"));
        arrow.setTextSize(22);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = dp(8);
        row.addView(arrow, ap);

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return val;
    }

    TextView addRow(LinearLayout card, int icon, String title, String value,
                    View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(true);
        // 【v4.14.7】设置行按压 Q 弹反馈
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) Bounce.pressIn(v);
                else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) Bounce.release(v);
                return false;
            }
        });
        row.setOnClickListener(click);

        row.addView(new HexIcon(this, icon), new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(MainActivity.C_FG);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        row.addView(tv, tp);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(MainActivity.C_SUB);
        val.setTextSize(13);
        val.setSingleLine(true);
        row.addView(val);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.parseColor("#6f7581"));
        arrow.setTextSize(22);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = dp(8);
        row.addView(arrow, ap);

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return val;
    }

    void addSwitchRow(LinearLayout card, int icon, String title, boolean checked,
                      CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));

        row.addView(new HexIcon(this, icon), new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(MainActivity.C_FG);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        row.addView(tv, tp);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        // 【v4.11.0】Switch 着色 API 24+；低版本保持系统原生样式（反射调用以兼容 API22 编译）
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                java.lang.reflect.Method m = sw.getClass().getMethod("setThumbTintList", ColorStateList.class);
                m.invoke(sw, new ColorStateList(
                        new int[][]{ {android.R.attr.state_checked}, {} },
                        new int[]{MainActivity.C_ACC, Color.parseColor("#a8adb7")}));
                sw.getClass().getMethod("setTrackTintList", ColorStateList.class)
                  .invoke(sw, new ColorStateList(
                        new int[][]{ {android.R.attr.state_checked}, {} },
                        new int[]{Color.parseColor("#66fb7299"), Color.parseColor("#3a3f49")}));
            } catch (Throwable ignore) { }
        }
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    GradientDrawable round(int color, float radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radDp * getResources().getDisplayMetrics().density);
        return g;
    }

    // ------------------------------------------------------------ 交互

    void chooseLayout() {
        final String[] items = {"单列（大封面，信息更全）", "双列（瀑布流，一屏更多）"};
        int cur = Prefs.twoColumn(this) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("推荐页布局")
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.setTwoColumn(SettingsActivity.this, which == 1);
                        refreshValues();
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 【v4.14.2】界面缩放：3 个推荐档 + 自定义，选完重启让全部页面按新密度重建 */
    void chooseDpi() {
        final String[] items = {
                DensityUtil.PRESET_LABELS[0],
                DensityUtil.PRESET_LABELS[1],
                DensityUtil.PRESET_LABELS[2],
                "自定义…（50%~200%）"
        };
        int cur = -1;
        int pct = DensityUtil.percent(this);
        for (int i = 0; i < DensityUtil.PRESETS.length; i++) {
            if (DensityUtil.PRESETS[i] == pct) { cur = i; break; }
        }
        // 【v4.14.3 修复】API22 的 AlertController 中 setMessage 与列表互斥：
        // 一旦 setMessage，单选项列表就不会被挂载，导致只弹说明文字、看不到选项。
        // 去掉 setMessage，把「选后重启生效」并入标题，列表即可正常显示。
        new AlertDialog.Builder(this)
                .setTitle("界面缩放 / DPI（选后自动重启生效）")
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which < DensityUtil.PRESETS.length) {
                            applyDpi(DensityUtil.PRESETS[which]);
                        } else {
                            inputDpi();
                        }
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    void inputDpi() {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(DensityUtil.percent(this)));
        et.setSelection(et.getText().length());
        et.setTextColor(MainActivity.C_FG);
        et.setHint("50~200，例如 90 表示缩小到 90%");
        et.setPadding(dp(16), dp(14), dp(16), dp(14));
        new AlertDialog.Builder(this)
                .setTitle("自定义界面缩放")
                .setMessage("输入百分比：小于 100 变小（一屏更多），大于 100 变大。范围 50~200。")
                .setView(et)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int v;
                        try {
                            v = Integer.parseInt(et.getText().toString().trim());
                        } catch (Throwable e) {
                            Toast.makeText(SettingsActivity.this, "请输入 50~200 的数字",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyDpi(v);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    void applyDpi(int pct) {
        pct = Math.max(50, Math.min(200, pct));
        if (pct == DensityUtil.percent(this)) return;
        Prefs.setUiScale(this, pct);
        DensityUtil.apply(this);
        // 重启到主页：让所有页面按新密度重新构建，避免运行中视图尺寸错乱
        Toast.makeText(this, "已设为 " + pct + "%，正在重启…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(350); } catch (Throwable ignore) { }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent i = new Intent(SettingsActivity.this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(i);
                            finish();
                        } catch (Throwable ignore) { }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 【v4.35.0】播放调度

    /**
     * 播放调度档位选择。
     *
     * 之前「流畅度」散落成一堆隐式规则，用户只能靠反复改画质去试；
     * 现在收成四档 + 一档自定义，一屏就能选明白。
     */
    void chooseSchedule() {
        final int cur = Prefs.scheduleMode(this);
        final String[] items = new String[ScheduleProfile.MODE_COUNT];
        for (int i = 0; i < items.length; i++) {
            items[i] = ScheduleProfile.NAMES[i] + "  ·  " + ScheduleProfile.HINTS[i];
        }
        new AlertDialog.Builder(this)
                .setTitle("播放调度（选后立即生效）")
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Prefs.setScheduleMode(SettingsActivity.this, which);
                        refreshValues();
                        if (which == ScheduleProfile.MODE_CUSTOM) {
                            chooseCustomSchedule();
                            return;
                        }
                        applySchedule();
                        Toast.makeText(SettingsActivity.this,
                                "已切换到「" + ScheduleProfile.nameOf(which) + "」",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 应用调度档位带来的全局参数（连接池大小等） */
    void applySchedule() {
        try {
            ScheduleProfile.Plan p = Prefs.schedulePlan(this);
            if (p != null) {
                BiliApi.applyConnPool(p.connPool);
                BiliApi.setPrefetchEnabled(p.prefetch);
            }
        } catch (Throwable ignore) { }
    }

    /** 自定义调度的临时状态（子对话框直接改它，确定时一次性落盘） */
    static class CustomState {
        int height, segs;
        boolean prefetch, danmaku, downgrade;
    }

    CustomState cs;

    void chooseCustomSchedule() {
        cs = new CustomState();
        cs.height = Prefs.schedHeight(this);
        cs.segs = Prefs.schedSegs(this);
        cs.prefetch = Prefs.schedPrefetch(this);
        cs.danmaku = Prefs.schedDanmaku(this);
        cs.downgrade = Prefs.schedDowngrade(this);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(2), dp(20), 0);

        final LinearLayout[] rows = new LinearLayout[5];
        rows[0] = customRow(box, "清晰度上限", capName(cs.height));
        rows[1] = customRow(box, "并行分段数", segName(cs.segs));
        rows[2] = customRow(box, "预取下一条", onOff(cs.prefetch));
        rows[3] = customRow(box, "弹幕", onOff(cs.danmaku));
        rows[4] = customRow(box, "卡顿自动降档", onOff(cs.downgrade));
        // customRow 的子 View 顺序固定：[0]=标题 [1]=当前值
        final TextView vH = (TextView) rows[0].getChildAt(1);
        final TextView vS = (TextView) rows[1].getChildAt(1);
        final TextView vP = (TextView) rows[2].getChildAt(1);
        final TextView vD = (TextView) rows[3].getChildAt(1);
        final TextView vG = (TextView) rows[4].getChildAt(1);
        for (int i = 0; i < rows.length; i++) {
            final int idx = i;
            rows[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickCustom(idx, vH, vS, vP, vD, vG); }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle("自定义播放调度")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (cs == null) return;
                        Prefs.setSchedHeight(SettingsActivity.this, cs.height);
                        Prefs.setSchedSegs(SettingsActivity.this, cs.segs);
                        Prefs.setSchedPrefetch(SettingsActivity.this, cs.prefetch);
                        Prefs.setSchedDanmaku(SettingsActivity.this, cs.danmaku);
                        Prefs.setSchedDowngrade(SettingsActivity.this, cs.downgrade);
                        applySchedule();
                        refreshValues();
                        Toast.makeText(SettingsActivity.this, "自定义调度已保存",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 自定义面板的一行：标题 + 当前值；返回这一行本身 */
    LinearLayout customRow(LinearLayout box, String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        row.setClickable(true);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(MainActivity.C_FG);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(MainActivity.C_ACC);
        v.setTextSize(14);
        row.addView(v);
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 自定义面板里点某一行 → 弹出子选择 */
    void pickCustom(int idx, TextView vH, TextView vS, TextView vP, TextView vD, TextView vG) {
        if (cs == null) return;
        if (idx == 0) {
            final String[] names = {"不限", "1080P", "720P", "480P", "360P"};
            final int[] hs = {0, 1080, 720, 480, 360};
            new AlertDialog.Builder(this)
                    .setTitle("清晰度上限")
                    .setSingleChoiceItems(names, capIndex(cs.height),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    d.dismiss();
                                    cs.height = hs[w];
                                    vH.setText(capName(cs.height));
                                }
                            })
                    .setNegativeButton("取消", null).show();
        } else if (idx == 1) {
            final String[] names = {"自动（按设备）", "1 段", "2 段", "3 段"};
            final int[] ss = {0, 1, 2, 3};
            new AlertDialog.Builder(this)
                    .setTitle("并行分段数")
                    .setSingleChoiceItems(names, segIndex(cs.segs),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    d.dismiss();
                                    cs.segs = ss[w];
                                    vS.setText(segName(cs.segs));
                                }
                            })
                    .setNegativeButton("取消", null).show();
        } else if (idx == 2) {
            new AlertDialog.Builder(this)
                    .setTitle("预取下一条")
                    .setSingleChoiceItems(new String[]{"开", "关"}, cs.prefetch ? 0 : 1,
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    d.dismiss();
                                    cs.prefetch = w == 0;
                                    vP.setText(onOff(cs.prefetch));
                                }
                            })
                    .setNegativeButton("取消", null).show();
        } else if (idx == 3) {
            new AlertDialog.Builder(this)
                    .setTitle("弹幕")
                    .setSingleChoiceItems(new String[]{"开", "关"}, cs.danmaku ? 0 : 1,
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    d.dismiss();
                                    cs.danmaku = w == 0;
                                    vD.setText(onOff(cs.danmaku));
                                }
                            })
                    .setNegativeButton("取消", null).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("卡顿自动降档")
                    .setSingleChoiceItems(new String[]{"开", "关"}, cs.downgrade ? 0 : 1,
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    d.dismiss();
                                    cs.downgrade = w == 0;
                                    vG.setText(onOff(cs.downgrade));
                                }
                            })
                    .setNegativeButton("取消", null).show();
        }
    }

    static int capIndex(int h) {
        if (h <= 0) return 0;
        if (h >= 1080) return 1;
        if (h >= 720) return 2;
        if (h >= 480) return 3;
        return 4;
    }

    static String capName(int h) {
        if (h <= 0) return "不限";
        if (h >= 1080) return "1080P";
        if (h >= 720) return "720P";
        if (h >= 480) return "480P";
        return "360P";
    }

    static int segIndex(int s) {
        if (s <= 0) return 0;
        if (s >= 3) return 3;
        return s;
    }

    static String segName(int s) {
        if (s <= 0) return "自动（按设备）";
        return s + " 段";
    }

    static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    void chooseDecoder() {
        final String[] items = {
                Prefs.decoderName(0), Prefs.decoderName(1),
                Prefs.decoderName(2), Prefs.decoderName(3)
        };
        new AlertDialog.Builder(this)
                .setTitle("默认解码方式")
                .setSingleChoiceItems(items, Prefs.decoder(this),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                Prefs.setDecoder(SettingsActivity.this, which);
                                refreshValues();
                                d.dismiss();
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    void confirmClearCache() {
        final long size = ImgLoader.diskSize();
        new AlertDialog.Builder(this)
                .setTitle("清除封面缓存")
                .setMessage("当前占用 " + sizeText(size) + "，清除后不影响已下载的视频。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ImgLoader.clearDisk();
                        ImgLoader.clearMem();
                        refreshValues();
                        Toast.makeText(SettingsActivity.this, "已清除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    void confirmClearPos() {
        new AlertDialog.Builder(this)
                .setTitle("清除播放记录")
                .setMessage("清除后所有视频将从开头播放，不影响收藏和历史列表。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(PlayPos.PREF, MODE_PRIVATE).edit().clear().apply();
                        Toast.makeText(SettingsActivity.this, "已清除播放记录", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    void loginOrOut() {
        final String uid = BiliApi.loggedInUid();
        if (uid != null) {
            new AlertDialog.Builder(this)
                    .setTitle("已登录 UID " + uid)
                    .setPositiveButton("退出登录", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            BiliApi.clearLoginCookie();
                            getSharedPreferences("bili_login", MODE_PRIVATE)
                                    .edit().clear().apply();
                            refreshValues();
                            Toast.makeText(SettingsActivity.this, "已退出", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("登录哔哩哔哩")
                .setMessage("验证码登录：手机号 + 短信验证码\n二维码登录：用 B 站 App 扫码\n\n登录后可看关注、收藏、粉丝并发送弹幕")
                .setPositiveButton("验证码登录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(SettingsActivity.this, WebViewLoginActivity.class));
                    }
                })
                .setNeutralButton("二维码登录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // 【v4.15.14】直接走官方扫码页套用，出码最稳
                        startActivity(new Intent(SettingsActivity.this, WebQrLoginActivity.class));
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 彩蛋：连续点击 logo 7 次进入跳一跳（2 秒内不点则重新计数） */
    long eggLast = 0;
    int eggCount = 0;
    void attachEgg(final ImageView logo) {
        logo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - eggLast > 2000) eggCount = 0;
                eggLast = now;
                eggCount++;
                // 轻微缩放反馈
                logo.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                logo.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                            }
                        }).start();
                if (eggCount == 7) {
                    eggCount = 0;
                    try {
                        Toast.makeText(SettingsActivity.this, "彩蛋 · 跳一跳", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(SettingsActivity.this, JumpActivity.class));
                    } catch (Throwable e) {
                        CrashHandler.note("egg", "跳一跳启动失败：" + e);
                    }
                }
            }
        });
    }

    void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("哔小趣 " + BxqApp.VERSION)
                .setMessage("B 站轻量客户端 · 无广告\n\n"
                        + "· 推荐单列 / 双列自由切换\n"
                        + "· 开屏动画可关\n"
                        + "· 动态 / 关注 / 粉丝可点开查看\n"
                        + "· 弹幕开关与发送\n"
                        + "· 竖屏视频横屏优化\n"
                        + "· 缓存直接存 MP4，保存在 电影/BiliAudio\n\n"
                        + "本软件为个人学习作品，与哔哩哔哩官方无关。")
                .setPositiveButton("知道了", null).show();
    }

    void showCrashLog() {
        String log = CrashHandler.readCrashLog(getExternalFilesDir(null), getFilesDir());
        new AlertDialog.Builder(this)
                .setTitle("崩溃日志")
                .setMessage(log)
                .setPositiveButton("知道了", null).show();
    }

    // ------------------------------------------------------------ 自绘六边形图标

    static class HexIcon extends View {

        static final int SETTINGS = 0, LAYOUT = 1, PLAY = 2, SPARK = 3,
                BROOM = 4, USER = 5, INFO = 6, BUG = 7, DANMAKU = 8, SCREEN = 9;

        final int type;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path hex = new Path();

        HexIcon(android.content.Context c, int type) {
            super(c);
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - dp(1.5f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.7f));
            p.setColor(MainActivity.C_ACC);
            hex.reset();
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 3 * i - Math.PI / 2;
                float x = (float) (cx + r * Math.cos(a));
                float y = (float) (cy + r * Math.sin(a));
                if (i == 0) hex.moveTo(x, y); else hex.lineTo(x, y);
            }
            hex.close();
            cv.drawPath(hex, p);

            p.setStyle(Paint.Style.FILL);
            float u = r * 0.42f;
            switch (type) {
                case LAYOUT:
                    cv.drawRect(cx - u, cy - u, cx - u * 0.15f, cy + u, p);
                    cv.drawRect(cx + u * 0.15f, cy - u, cx + u, cy + u, p);
                    break;
                case PLAY: {
                    Path t = new Path();
                    t.moveTo(cx - u * 0.55f, cy - u);
                    t.lineTo(cx + u * 0.9f, cy);
                    t.lineTo(cx - u * 0.55f, cy + u);
                    t.close();
                    cv.drawPath(t, p);
                    break;
                }
                case SPARK: {
                    Path t = new Path();
                    t.moveTo(cx, cy - u * 1.15f);
                    t.lineTo(cx + u * 0.3f, cy - u * 0.3f);
                    t.lineTo(cx + u * 1.1f, cy);
                    t.lineTo(cx + u * 0.3f, cy + u * 0.3f);
                    t.lineTo(cx, cy + u * 1.15f);
                    t.lineTo(cx - u * 0.3f, cy + u * 0.3f);
                    t.lineTo(cx - u * 1.1f, cy);
                    t.lineTo(cx - u * 0.3f, cy - u * 0.3f);
                    t.close();
                    cv.drawPath(t, p);
                    break;
                }
                case BROOM:
                    p.setStrokeWidth(dp(2));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(cx - u, cy - u, cx + u * 0.6f, cy + u * 0.6f, p);
                    cv.drawLine(cx - u * 0.5f, cy + u * 0.2f, cx + u, cy + u, p);
                    cv.drawLine(cx - u, cy - u, cx - u, cy - u, p);
                    break;
                case USER:
                    cv.drawCircle(cx, cy - u * 0.45f, u * 0.45f, p);
                    cv.drawArc(cx - u, cy + u * 0.1f, cx + u, cy + u * 1.5f,
                            180, 180, true, p);
                    break;
                case INFO:
                    cv.drawCircle(cx, cy - u * 0.7f, u * 0.16f, p);
                    p.setStrokeWidth(dp(2));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(cx, cy - u * 0.2f, cx, cy + u * 0.9f, p);
                    break;
                case BUG:
                    cv.drawCircle(cx, cy, u * 0.55f, p);
                    p.setStrokeWidth(dp(1.6f));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(cx - u, cy - u * 0.9f, cx - u * 0.55f, cy - u * 0.45f, p);
                    cv.drawLine(cx + u, cy - u * 0.9f, cx + u * 0.55f, cy - u * 0.45f, p);
                    cv.drawLine(cx - u, cy + u * 0.9f, cx - u * 0.55f, cy + u * 0.45f, p);
                    cv.drawLine(cx + u, cy + u * 0.9f, cx + u * 0.55f, cy + u * 0.45f, p);
                    break;
                case DANMAKU:
                    p.setStrokeWidth(dp(1.8f));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawRoundRect(cx - u, cy - u * 0.8f, cx + u * 0.4f, cy + u * 0.2f,
                            u * 0.3f, u * 0.3f, p);
                    cv.drawLine(cx - u * 0.1f, cy + u * 0.2f, cx - u * 0.35f, cy + u * 0.7f, p);
                    cv.drawRoundRect(cx - u * 0.15f, cy - u * 0.1f, cx + u * 1.1f, cy + u * 0.9f,
                            u * 0.3f, u * 0.3f, p);
                    break;
                case SCREEN:
                    p.setStrokeWidth(dp(1.8f));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawRoundRect(cx - u, cy - u, cx + u, cy + u, u * 0.25f, u * 0.25f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRect(cx - u * 0.55f, cy - u * 0.3f, cx + u * 0.55f, cy + u * 0.55f, p);
                    break;
                default:
                    p.setStrokeWidth(dp(2.1f));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(cx, cy, u * 0.85f, p);
                    cv.drawCircle(cx, cy, u * 0.3f, p);
                    break;
            }
        }

        float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }
}
