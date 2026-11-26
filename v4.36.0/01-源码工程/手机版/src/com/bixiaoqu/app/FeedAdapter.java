package com.bixiaoqu.app;

import android.content.Context;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
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
 * 推荐/关注列表适配器。
 *
 * 【v4.16.0】从「单列 / 双列」升级为「按屏幕宽度自动选 2~5 列」：
 *  · 手机竖屏：2 列
 *  · 手机横屏 / 小平板：3~4 列
 *  · 平板横屏：4 列（对齐 B 站 HD 版横屏排布）
 * 列数由 MainActivity.calcCols() 算好传进来，这里只负责排。
 *
 * 仍用 ListView 而不是 GridView：占位提示行（加载中/未登录）能独占整行，
 * 行间距、下拉刷新也更好控制。
 */
public class FeedAdapter extends BaseAdapter {

    public interface Listener {
        void onItem(VideoItem item);
    }

    final Context ctx;
    final ArrayList<VideoItem> data;
    final boolean grid;          // 是否多列
    final int cols;              // 多列时的列数
    final VideoAdapter single;   // 单列时复用原来的横向行
    final Listener listener;
    final int cardW, coverH, gap;
    final float titleSp;

    /** 兼容旧调用：twoCol = true 等价于 2 列 */
    public FeedAdapter(Context c, ArrayList<VideoItem> d, boolean twoCol, Listener l) {
        this(c, d, twoCol, 2, l);
    }

    public FeedAdapter(Context c, ArrayList<VideoItem> d, boolean twoCol, int columns, Listener l) {
        ctx = c;
        data = d;
        grid = twoCol;
        cols = twoCol ? Math.max(2, columns) : 1;
        listener = l;
        single = new VideoAdapter(c, d);

        float den = c.getResources().getDisplayMetrics().density;
        boolean ios = UiSkin.ios(c);
        // 【v4.31.0】iOS 皮肤：列间距 12dp、页面边距 16dp，标题 14.5sp 常规字重
        gap = (int) ((ios ? 12 : (cols >= 4 ? 8 : 10)) * den);
        int side = ios ? 32 : 20;   // ListView 左右 padding 之和
        int avail = (int) (c.getResources().getDisplayMetrics().widthPixels - side * den);
        cardW = Math.max(1, (avail - gap * (cols - 1)) / cols);
        coverH = (int) (cardW * 9f / 16f);
        // 4~5 列时卡片窄，标题降半号，避免两行就顶满
        if (ios) titleSp = cardW < (int) (150 * den) ? 13f : 14.5f;
        else titleSp = cardW < (int) (150 * den) ? 12f : 13f;
    }

    /** 卡片封面宽（px）：供列表滚动时按真实尺寸预取封面 */
    public int cardWidth() { return cardW; }

    /** 卡片封面高（px）：供列表滚动时按真实尺寸预取封面 */
    public int coverHeight() { return coverH; }

    /** 【v4.17.0】预取封面用的宽：多列时用整块封面，单列时用行内小缩略图 */
    public int prefetchWidth() { return grid ? cardW : single.picW; }

    /** 【v4.17.0】预取封面用的高 */
    public int prefetchHeight() { return grid ? coverH : single.picH; }

    @Override public int getCount() {
        if (!grid) return data.size();
        return (data.size() + cols - 1) / cols;
    }

    @Override public Object getItem(int i) {
        if (!grid) return data.get(i);
        int idx = i * cols;
        return data.get(Math.min(idx, Math.max(0, data.size() - 1)));
    }

    @Override public long getItemId(int i) { return i; }

    @Override public View getView(int i, View cv, ViewGroup p) {
        if (!grid) {
            return single.getView(i, cv instanceof VideoAdapter.Row ? cv : null, p);
        }
        Row row;
        if (cv instanceof Row && ((Row) cv).cards.length == cols) {
            row = (Row) cv;
        } else {
            row = new Row(ctx, cols, gap, this);
        }
        row.bind(i * cols);
        return row;
    }

    /** 多列的一行：cols 个等宽卡片 */
    static class Row extends LinearLayout {

        final Card[] cards;
        final FeedAdapter owner;

        Row(Context c, int cols, int gap, FeedAdapter owner) {
            super(c);
            this.owner = owner;
            setOrientation(HORIZONTAL);
            setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cards = new Card[cols];
            for (int i = 0; i < cols; i++) {
                Card cd = new Card(c, owner);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = gap;
                addView(cd, lp);
                cards[i] = cd;
            }
        }

        void bind(int start) {
            // 每次复用先把权重与可见性复位（占位行会把权重改大）
            for (Card cd : cards) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cd.getLayoutParams();
                if (lp.weight != 1f) { lp.weight = 1f; cd.setLayoutParams(lp); }
                cd.setVisibility(View.VISIBLE);
            }
            for (int i = 0; i < cards.length; i++) {
                int idx = start + i;
                if (idx >= owner.data.size()) {
                    cards[i].setVisibility(View.INVISIBLE);
                    continue;
                }
                VideoItem it = owner.data.get(idx);
                cards[i].bind(it);
                if (it.isPlaceholder()) {
                    // 占位提示独占整行：本卡权重拉到最大，其余隐藏
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) cards[i].getLayoutParams();
                    lp.weight = cards.length;
                    cards[i].setLayoutParams(lp);
                    for (int j = 0; j < cards.length; j++) {
                        if (j != i) cards[j].setVisibility(View.GONE);
                    }
                    break;
                }
            }
        }
    }

    /** 网格卡片：封面（16:9）+ 标题 + 副标题 */
    static class Card extends LinearLayout {

        RatioImageView img;
        TextView title, sub;
        VideoItem item;
        final FeedAdapter owner;

        Card(Context c, FeedAdapter owner) {
            super(c);
            this.owner = owner;
            float den = c.getResources().getDisplayMetrics().density;
            boolean ios = UiSkin.ios(c);
            setOrientation(VERTICAL);
            setClickable(true);

            img = new RatioImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // 【v4.17.0】占位底色跟随主题的柔和灰蓝，比原来的硬灰 #2a2e36 观感更软
            img.setBackgroundColor(ImgLoader.placeholderColor());
            // 【v4.17.0】圆角从 9dp 提到 12dp：卡片与封面之间留一点呼吸，
            // 边缘过渡更柔和，不再是硬邦邦的直角块。
            // iOS 卡片封面圆角 10dp：比原版 12dp 更收敛，接近系统相册的观感
            final float rad = (ios ? 10 : 12) * den;
            img.setClipToOutline(true);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            addView(img, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            title = new TextView(c);
            title.setTextColor(MainActivity.C_FG);
            title.setTextSize(owner.titleSp);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(2 * den, 1f);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = (int) ((ios ? 9 : 6) * den);
            addView(title, tp);

            sub = new TextView(c);
            sub.setTextColor(MainActivity.C_SUB);
            sub.setTextSize(ios ? 11.5f : 11);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = (int) ((ios ? 5 : 3) * den);
            addView(sub, sp);
        }

        void bind(final VideoItem it) {
            item = it;
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!it.isPlaceholder() && owner.listener != null) {
                        owner.listener.onItem(it);
                    }
                }
            });
            if (it.isPlaceholder()) {
                img.setVisibility(View.GONE);
                title.setVisibility(View.GONE);
                sub.setVisibility(View.VISIBLE);
                sub.setGravity(Gravity.CENTER);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                sub.setText(it.sub);
                setMinimumHeight((int) (60 * getResources().getDisplayMetrics().density));
                return;
            }
            setMinimumHeight(0);
            img.setVisibility(View.VISIBLE);
            title.setVisibility(View.VISIBLE);
            title.setText(it.title);
            sub.setGravity(Gravity.NO_GRAVITY);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            if (TextUtils.isEmpty(it.sub)) {
                sub.setVisibility(View.GONE);
            } else {
                sub.setVisibility(View.VISIBLE);
                sub.setText(it.sub);
            }
            String t = it.thumb();
            if (it.localUri != null && !it.localUri.isEmpty()) {
                ImgLoader.loadUri(img, android.net.Uri.parse(it.localUri), owner.cardW, owner.coverH);
            } else if (t == null) {
                img.setImageBitmap(null);
            } else {
                ImgLoader.load(img, t, owner.cardW, owner.coverH);
            }
        }
    }

    /** 固定 16:9 的封面（权重布局里也能算准高度） */
    static class RatioImageView extends ImageView {
        RatioImageView(Context c) { super(c); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            if (w <= 0) w = (int) (160 * getResources().getDisplayMetrics().density);
            int h = (int) (w * 9f / 16f);
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }
}
