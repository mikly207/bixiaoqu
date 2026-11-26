package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 带封面的视频列表适配器（主页推荐 / 关注 / 播放页推荐共用）。
 * 注意：getView 内绝不能调用 parent.addView()（AdapterView 会抛异常直接闪退）。
 */
public class VideoAdapter extends BaseAdapter {

    final Context ctx;
    final ArrayList<VideoItem> data;
    final int picW, picH;

    public VideoAdapter(Context c, ArrayList<VideoItem> d) {
        ctx = c;
        data = d;
        picW = PIC_W(c);
        picH = PIC_H(c);
    }

    /** 【v4.31.0】缩略图尺寸抽成静态方法：列表预取要用同一组像素，才能命中同一张缓存 */
    public static int PIC_W(Context c) {
        return (int) (124 * c.getResources().getDisplayMetrics().density);
    }

    public static int PIC_H(Context c) {
        return (int) (76 * c.getResources().getDisplayMetrics().density);
    }

    @Override public int getCount() { return data.size(); }
    @Override public Object getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override public View getView(int i, View cv, ViewGroup p) {
        Row r;
        if (cv instanceof Row) {
            r = (Row) cv;
        } else {
            r = new Row(ctx);
        }
        VideoItem it = data.get(i);

        if (it.isPlaceholder()) {
            r.img.setVisibility(View.GONE);
            r.title.setVisibility(View.GONE);
            r.sub.setVisibility(View.VISIBLE);
            r.sub.setGravity(Gravity.CENTER);
            r.sub.setTextSize(13);
            r.sub.setText(it.sub);
        } else {
            r.img.setVisibility(View.VISIBLE);
            r.title.setVisibility(View.VISIBLE);
            r.sub.setGravity(Gravity.NO_GRAVITY);
            r.sub.setTextSize(11);
            r.title.setText(it.title);
            // 【v4.39.0 排版】保留副标题行高占位，列表行高更平稳
            if (TextUtils.isEmpty(it.sub)) {
                r.sub.setText(" ");
                r.sub.setVisibility(View.INVISIBLE);
            } else {
                r.sub.setVisibility(View.VISIBLE);
                r.sub.setText(it.sub);
            }
            String t = it.thumb();
            if (it.localUri != null && !it.localUri.isEmpty()) {
                ImgLoader.loadUri(r.img, android.net.Uri.parse(it.localUri), picW, picH);
            } else if (t == null) {
                r.img.setImageBitmap(null);
            } else {
                ImgLoader.load(r.img, t, picW, picH);
            }
        }
        return r;
    }

    /** 行容器（纯代码布局，避免引入 layout 资源） */
    static class Row extends LinearLayout {

        ImageView img;
        TextView title;
        TextView sub;

        Row(Context c) {
            super(c);
            float den = c.getResources().getDisplayMetrics().density;
            int pad = (int) (9 * den);
            int gap = (int) (11 * den);
            int pw = (int) (124 * den);
            int ph = (int) (76 * den);

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(pad, pad, pad, pad);

            // 【v4.17.0】圆角 10dp → 14dp：行卡片边缘更圆润，与整体的柔和观感一致
            // 【v4.31.0】iOS：分组卡片取 12dp，缩略图取 8dp（系统「音乐/视频」列表的尺度）
            boolean ios = UiSkin.ios(c);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(MainActivity.C_CARD);
            bg.setCornerRadius((ios ? 12 : 14) * den);
            setBackground(bg);
            setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // 【v4.17.0】占位底色跟随主题的柔和灰蓝
            img.setBackgroundColor(ImgLoader.placeholderColor());
            // 【v4.17.0】缩略图圆角 7dp → 10dp
            final float rad = (UiSkin.ios(c) ? 8 : 10) * den;
            img.setClipToOutline(true);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            addView(img, new LinearLayout.LayoutParams(pw, ph));

            LinearLayout right = new LinearLayout(c);
            right.setOrientation(VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rp.leftMargin = gap;
            addView(right, rp);

            title = new TextView(c);
            title.setTextColor(MainActivity.C_FG);
            title.setTextSize(UiSkin.ios(c) ? 15f : 13.5f);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(2 * den, 1f);
            right.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            sub = new TextView(c);
            sub.setTextColor(MainActivity.C_SUB);
            sub.setTextSize(11);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = (int) (5 * den);
            right.addView(sub, sp);
        }
    }
}
