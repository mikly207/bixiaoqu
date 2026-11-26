package com.bixiaoqu.app;

/** 视频列表项：bvid 为空表示占位提示行（加载中/空/未登录等） */
public class VideoItem {

    public String bvid;
    public String title;
    public String sub;
    public String pic;      // 封面原始地址（http），由 VideoAdapter 转缩略图
    public String localUri; // 本地视频 content:// 地址（离线缓存用，优先于 pic）
    public String extra;    // 附加信息（UP 主 mid 等）
    // 【v4.1 提速】从列表接口直接带来的元数据，用于跳过播放页的 network 往返
    public long cid = 0;
    public float aspect = 0f;
    public long durSec = 0;

    public VideoItem(String bvid, String title, String sub, String pic) {
        this.bvid = bvid;
        this.title = title;
        this.sub = sub;
        this.pic = pic;
    }

    /** 【v4.1】带播放元数据的构造 */
    public VideoItem(String bvid, String title, String sub, String pic,
                     long cid, float aspect, long durSec) {
        this(bvid, title, sub, pic);
        this.cid = cid;
        this.aspect = aspect;
        this.durSec = durSec;
    }

    public boolean isPlaceholder() {
        return bvid == null || bvid.isEmpty();
    }

    /** B 站图床缩略图：https 化 + 裁切缩放，省流量也省解码内存 */
    public String thumb() {
        if (pic == null || pic.isEmpty()) return null;
        String u = ImgLoader.https(pic);
        if (u.isEmpty() || u.contains("@")) return u;
        // 【v4.16.0】320w_200h → 480w_270h：列表卡片在手机上已约 500px 宽，
        // 320 宽的图被拉伸 1.5 倍以上，肉眼可见发糊。480w 作为底线，
        // 平板多列时 ImgLoader.fit() 会再按实际像素继续上调。
        return u + "@480w_270h_1c.jpg";
    }

    /**
     * 【v4.13.12】竖屏全屏海报专用高清封面。
     * thumb() 的 @320w_200h 是横版小图，拉满竖屏整屏会明显发糊；
     * 这里请求竖版比例的较高分辨率，让首帧就绪前的过渡画面也清晰。
     */
    public String thumbHi() {
        if (pic == null || pic.isEmpty()) return null;
        String u = ImgLoader.https(pic);
        int at = u.indexOf('@');
        if (at > 0) u = u.substring(0, at);
        return u + "@720w_1280h_1c.jpg";
    }

    /** 秒 → mm:ss 或 h:mm:ss */
    public static String dur(int sec) {
        if (sec <= 0) return "";
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
    }

    /** 播放量 → 万/亿 */
    public static String views(long v) {
        if (v >= 100000000L) return String.format("%.1f亿", v / 100000000.0);
        if (v >= 10000L) return String.format("%.1f万", v / 10000.0);
        return String.valueOf(v);
    }
}
