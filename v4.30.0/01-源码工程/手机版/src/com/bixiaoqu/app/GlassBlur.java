package com.bixiaoqu.app;

import android.graphics.Bitmap;

/**
 * 【v4.23.0】液态玻璃的模糊内核（全 App 共用一份）。
 *
 * 之前这段分离式 box blur 只在 {@link LiquidNavBar} 里私有实现，播放器控制条要复用
 * 就得再抄一遍 —— 两份同样的像素循环，改一处漏一处。抽出来统一维护。
 *
 * 纯 Java、O(n)、不依赖 RenderScript / RenderEffect，API22 可用。
 */
public final class GlassBlur {

    /** 就地模糊（把模糊结果写回同一块像素数组） */
    public static void blurInPlace(int[] pix, int w, int h, int radius) {
        if (pix == null || radius < 1 || w < 1 || h < 1) return;
        if (pix.length < w * h) return;
        int[] tmp = new int[w * h];
        final int win = radius + radius + 1;

        // ---- 水平 ----
        for (int y = 0; y < h; y++) {
            final int base = y * w;
            long sr = 0, sg = 0, sb = 0;
            for (int k = -radius; k <= radius; k++) {
                int c = pix[base + clamp(k, 0, w - 1)];
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                tmp[base + x] = 0xff000000
                        | ((int) (sr / win) << 16)
                        | ((int) (sg / win) << 8)
                        | (int) (sb / win);
                int out = pix[base + clamp(x - radius, 0, w - 1)];
                sr -= (out >> 16) & 0xff;
                sg -= (out >> 8) & 0xff;
                sb -= out & 0xff;
                int in = pix[base + clamp(x + radius + 1, 0, w - 1)];
                sr += (in >> 16) & 0xff;
                sg += (in >> 8) & 0xff;
                sb += in & 0xff;
            }
        }
        // ---- 垂直 ----
        for (int x = 0; x < w; x++) {
            long sr = 0, sg = 0, sb = 0;
            for (int k = -radius; k <= radius; k++) {
                int c = tmp[clamp(k, 0, h - 1) * w + x];
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                pix[y * w + x] = 0xff000000
                        | ((int) (sr / win) << 16)
                        | ((int) (sg / win) << 8)
                        | (int) (sb / win);
                int out = tmp[clamp(y - radius, 0, h - 1) * w + x];
                sr -= (out >> 16) & 0xff;
                sg -= (out >> 8) & 0xff;
                sb -= out & 0xff;
                int in = tmp[clamp(y + radius + 1, 0, h - 1) * w + x];
                sr += (in >> 16) & 0xff;
                sg += (in >> 8) & 0xff;
                sb += in & 0xff;
            }
        }
    }

    /**
     * 缩小 → 模糊 → 返回新位图。宽高按 maxW 等比收缩，像素量降一个数量级，
     * 模糊半径的视觉强度靠「先缩后放」自然变大，开销几乎可以忽略。
     *
     * @return 失败返回 null（调用方回退到纯渐变玻璃，绝不能因此崩）
     */
    public static Bitmap downscaleBlur(Bitmap src, int maxW, int radius) {
        if (src == null || src.isRecycled() || maxW < 1) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w < 1 || h < 1) return null;
            int bw = Math.min(w, maxW);
            int bh = Math.max(1, Math.round(h * (bw / (float) w)));
            Bitmap small = Bitmap.createScaledBitmap(src, bw, bh, true);
            int[] px = new int[bw * bh];
            small.getPixels(px, 0, bw, 0, 0, bw, bh);
            blurInPlace(px, bw, bh, radius);
            small.setPixels(px, 0, bw, 0, 0, bw, bh);
            return small;
        } catch (Throwable t) {
            return null;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private GlassBlur() { }
}
