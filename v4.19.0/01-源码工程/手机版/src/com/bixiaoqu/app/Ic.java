package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.HashMap;

/**
 * 图标工具：统一从 res/drawable/ic_*.xml（24dp 矢量化线稿）取图，
 * 所有图标共用同一套 24×24 视觉框，因此只要给同样的尺寸就一定一样大。
 */
public class Ic {

    static final HashMap<String, Integer> IDS = new HashMap<>();

    /** 图标资源 id（带缓存；找不到返回 0） */
    public static int id(Context c, String name) {
        Integer v = IDS.get(name);
        if (v != null) return v;
        int r = 0;
        try {
            r = c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
        } catch (Throwable ignore) { }
        IDS.put(name, r);
        return r;
    }

    /** 取着色后的 Drawable */
    public static Drawable drawable(Context c, String name, int color) {
        int r = id(c, name);
        if (r == 0) return null;
        Drawable d = null;
        try {
            d = c.getResources().getDrawable(r, c.getTheme());
        } catch (Throwable ignore) { }
        if (d == null) {
            try { d = c.getResources().getDrawable(r); } catch (Throwable ignore) { }
        }
        if (d != null) {
            try { d.mutate().setTint(color); } catch (Throwable ignore) { }
        }
        return d;
    }

    /** 建一个固定边长的图标 ImageView（sizeDp 为图标本体边长，四边留白由调用方控制） */
    public static ImageView view(Context c, String name, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setColorFilter(color);
        int r = id(c, name);
        if (r != 0) v.setImageResource(r);
        float den = c.getResources().getDisplayMetrics().density;
        v.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (sizeDp * den), (int) (sizeDp * den)));
        return v;
    }

    /** 换图 / 换色（不动尺寸） */
    public static void set(ImageView v, String name, int color) {
        if (v == null) return;
        int r = id(v.getContext(), name);
        if (r != 0) v.setImageResource(r);
        v.setColorFilter(color);
    }

    /**
     * 玻璃质感图标：半透明白的圆形/圆角底 + 描边高光 + 居中图标。
     * 用于首页顶栏与底部导航，视觉圆润、有通透感。
     */
    public static ImageView glass(Context c, String name, int iconColor,
                                  int bgColor, int sizeDp, float roundDp) {
        ImageView v = new ImageView(c);
        float den = c.getResources().getDisplayMetrics().density;
        int px = (int) (sizeDp * den);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(bgColor);
        g.setCornerRadius(roundDp * den);
        g.setStroke((int) Math.max(1, den), 0x26FFFFFF);   // 细高光描边
        v.setBackground(g);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setPadding((int) (sizeDp * den * 0.24f), (int) (sizeDp * den * 0.24f),
                (int) (sizeDp * den * 0.24f), (int) (sizeDp * den * 0.24f));
        int r = id(c, name);
        if (r != 0) v.setImageResource(r);
        v.setColorFilter(iconColor);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }

    private Ic() { }
}
