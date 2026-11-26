package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * 通用液态玻璃材质（全 App 统一：设置页 / 搜索页 / 关注动态卡片 / 历史词条）。
 *
 * 【v4.15.3 去塑料感修正】
 * 旧版用 70%+ 不透明的单色渐变 + 偏亮描边，视觉上像一块实心塑料盒。
 * 真玻璃的关键是「通透」而非「染色」，本版改为：
 *   - 染色浓度大幅降低（深色卡片 alpha 从 ~0xB2 降到 ~0x3D），背后内容能透出来；
 *   - 用多色停靠点做「顶部柔和高光 → 通透主体 → 底部微暗」的体积感，
 *     而不是一整块均匀色；
 *   - 描边收细、降亮度，只在顶缘留一缕高光，避免"画了个框"的塑料感。
 *
 * 全部为 API1 可用的 GradientDrawable，API22~17 通用。
 */
public final class GlassUtil {

    /** 液态玻璃卡片背景（通透 + 顶部受光 + 细高光描边） */
    public static GradientDrawable card(boolean dark, float radDp, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        // 【v4.31.0】iOS 皮肤：卡片不再是"玻璃"，而是标准的系统分组卡片 ——
        // 一块实心 #1C1C1E / #FFFFFF、12dp 圆角、一道极淡的分隔描边。
        // iOS 的层次靠"底色差"，不靠渐变与高光，这也是它看起来更"干净"的原因。
        if (UiSkin.ios(c)) return UiSkin.group(c, dark);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radDp * d);
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        if (dark) {
            // 深色通透玻璃：主体只轻轻染色（约 24%），背后底色可透出。
            // 三段停靠：顶部一缕柔光 → 通透主体 → 底部略沉，模拟玻璃厚度与受光。
            g.setColors(new int[]{
                    0x14FFFFFF,  // 顶缘柔光（很淡）
                    0x3D2B303B,  // 主体：低浓度冷灰染色，通透
                    0x33212730   // 底部：略深，收住体积
            });
            g.setStroke(Math.max(1, (int) d), 0x14FFFFFF);   // 细、淡描边
        } else {
            // 浅色通透玻璃：主体接近透明，顶部高光 + 淡粉描边
            g.setColors(new int[]{
                    0x8CFFFFFF,
                    0x3DFFFFFF,
                    0x2BF2F4F8
            });
            g.setStroke(Math.max(1, (int) d), Theme.c(0x24FB7299));
        }
        return g;
    }

    /**
     * 【v4.32.0】播放器弹窗专用玻璃（「更多」「清晰度」「解码方式」等）。
     *
     * 弹窗压在视频画面上，而视频是高对比、满屏运动的底 —— card() 那套 24% 的
     * 通透过在静态页面上好看，叠到画面上就变成「文字浮在画面里」，读起来费劲。
     * 所以这里单独做一档**更实**的玻璃：主体提到 ~49%（深色）/ ~75%（浅色），
     * 保留顶部柔光与底部收沉，仍是玻璃而不是实心塑料块。
     * 与 card() 分开是为了不动列表 / 设置页那批静态卡片。
     */
    public static GradientDrawable sheet(boolean dark, float radDp, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        if (UiSkin.ios(c)) return UiSkin.group(c, dark);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radDp * d);
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        if (dark) {
            g.setColors(new int[]{
                    0x24FFFFFF,  // 顶缘柔光：比 card() 略提，仍是一缕
                    0x7D262B34,  // 主体：约 49%，画面透出但不再抢字
                    0x6E1B2027   // 底部：略沉，收住体积
            });
            g.setStroke(Math.max(1, (int) d), 0x1FFFFFFF);
        } else {
            g.setColors(new int[]{
                    0xEDFFFFFF,
                    0xBFFFFFFF,
                    0xAEF2F4F8
            });
            g.setStroke(Math.max(1, (int) d), Theme.c(0x33FB7299));
        }
        return g;
    }

    /** 玻璃小胶囊（搜索历史词、标签用），比卡片略亮一点但仍保持通透 */
    public static GradientDrawable chip(boolean dark, float radDp, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        // iOS：标签是"填充胶囊"，不是玻璃片
        if (UiSkin.ios(c)) return UiSkin.fill(c, UiSkin.fill(dark), radDp);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radDp * d);
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        if (dark) {
            g.setColors(new int[]{
                    0x12FFFFFF,
                    0x3A333945,
                    0x302A2F3A
            });
            g.setStroke(Math.max(1, (int) d), 0x12FFFFFF);
        } else {
            g.setColors(new int[]{
                    0x99FFFFFF,
                    0x4DFFFFFF,
                    0x33F0F2F7
            });
            g.setStroke(Math.max(1, (int) d), Theme.c(0x20FB7299));
        }
        return g;
    }

    /**
     * 【v4.15.3】关注页分段切换栏玻璃背景。选中态主题粉半透明玻璃 + 细粉描边，
     * 未选中态中性通透玻璃。与 card()/chip() 同一材质语言。
     */
    public static GradientDrawable seg(boolean on, boolean dark, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        // iOS 分段控件：选中 = 强调色 20% 填充 + 强调色文字，未选中 = 系统填充色
        if (UiSkin.ios(c)) {
            return UiSkin.fill(c,
                    on ? (dark ? 0x33FF375F : 0x26FF2D55) : UiSkin.fill(dark), 9);
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(9 * d);
        g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        if (on) {
            g.setColors(new int[]{Theme.c(0x4DFB7299), Theme.c(0x30FB7299)});
            g.setStroke(Math.max(1, (int) d), Theme.c(0x3CFB7299));
        } else if (dark) {
            g.setColors(new int[]{0x10FFFFFF, 0x332A2F3A});
            g.setStroke(Math.max(1, (int) d), 0x12FFFFFF);
        } else {
            g.setColors(new int[]{0x80FFFFFF, 0x33EFF1F6});
            g.setStroke(Math.max(1, (int) d), Theme.c(0x1EFB7299));
        }
        return g;
    }

    private GlassUtil() { }
}
