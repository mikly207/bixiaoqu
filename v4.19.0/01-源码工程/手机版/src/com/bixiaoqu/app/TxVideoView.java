package com.bixiaoqu.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

/**
 * 视频显示控件。
 *
 * 【v4.9.3 最终方案：内核用系统 VideoView】
 *
 * 前面两版在这个问题上绕了弯路，记录清楚以免再犯：
 *  · v4.9  用 TextureView → 部分设备「有声音、画面全黑」（渲染通道没接上，非解码问题）
 *  · v4.9.2 用自写 SurfaceView → 把建流绑死在 surfaceCreated 上，Surface 迟迟不来时
 *           连 prepare 都不执行，表现是「一直转圈、声画全无」
 *
 * 结论：视频输出这条链路上的坑（Surface 生命周期、prepare 时序、厂商定制）比想象的多，
 * 自己实现一遍等于把所有机型的兼容性重新踩一遍。系统 VideoView 内部就是
 * SurfaceView + MediaPlayer，已经把「Surface 没就绪先建流、就绪后补 setDisplay」
 * 等边界全部处理好了，v4.8 也实测可用。
 *
 * 所以这里改成「包装 VideoView」，对外接口保持不变（PlayActivity 无需改动）。
 * 代价：VideoView 是独立图层，父容器位移时画面跟随会晚 1~2 帧（Android 7+ 支持变换）。
 */
public class TxVideoView extends FrameLayout {

    final VideoView vv;
    /** 是否已渲染出第一帧（画面自检用） */
    boolean firstFrame = false;

    public TxVideoView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        vv = new VideoView(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.CENTER;
        vv.setLayoutParams(lp);
        addView(vv);
        // 【v4.17.0 GPU 优先级】
        // ① Surface 用 RGBX_8888：SurfaceView 默认请求的是 RGB_565，
        //    解码出来的画面要先降到 16bit 再上屏，既有色彩断层又多一次带宽拷贝。
        //    换成 RGBX_8888 后解码帧直接按 32bit 合成，GPU 合成压力更均匀、
        //    颜色也更准。异常机型上失败也不影响播放（保持默认格式）。
        try {
            vv.getHolder().setFormat(android.graphics.PixelFormat.RGBX_8888);
        } catch (Throwable ignore) { }
        // ② 明确不在最上层：弹幕、进度条等控件必须压在画面之上。
        try {
            vv.setZOrderOnTop(false);
            vv.setZOrderMediaOverlay(false);
        } catch (Throwable ignore) { }
        // ③ 播放中不息屏：息屏会中断解码并丢掉缓冲，恢复时要重新起播。
        try { setKeepScreenOn(true); } catch (Throwable ignore) { }
        // 首帧信号用 VideoView 暴露的 setOnInfoListener（内部会链式转发，
        // 不会破坏 VideoView 自身的状态机；直接动 MediaPlayer 的 listener 有风险）
        try {
            vv.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer m, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) firstFrame = true;
                    return false;
                }
            });
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.17.1】画面容器即将被摘下（或窗口离开）时回调。
     *
     * 系统 VideoView 的 Surface 一旦销毁就会 release 播放器，重新挂上时
     * openVideo() 从 0 开始 prepare —— 用户看到的就是「视频重新播放」。
     * 这里在 detach 之前把真实进度和播放状态交出去，让上层能把断点写回
     * resumePos：即便某条路径仍不可避免地重建了 Surface，也会从这个位置接着播，
     * 而不是从头开始。播放状态（暂停/播放）也一并带过去，退出后不会莫名自动播放。
     */
    public interface OnSurfaceLost {
        void onSurfaceLost(int positionMs, boolean playing);
    }

    OnSurfaceLost onSurfaceLost;

    public void setOnSurfaceLostListener(OnSurfaceLost l) {
        onSurfaceLost = l;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 必须在 super 之前取：super 会触发子 VideoView 销毁 Surface，
        // 之后 getCurrentPosition() 就不可靠了（可能抛异常或返回 0）。
        try {
            if (onSurfaceLost != null) {
                onSurfaceLost.onSurfaceLost(vv.getCurrentPosition(), vv.isPlaying());
            }
        } catch (Throwable ignore) {
        }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ 对外接口（与 VideoView 对齐）

    public void setVideoPath(String path) {
        if (path == null || path.isEmpty()) return;
        firstFrame = false;
        vv.setVideoPath(path);
    }

    public void start() {
        try { vv.start(); } catch (Throwable ignore) { }
    }

    public void pause() {
        try { if (vv.isPlaying()) vv.pause(); } catch (Throwable ignore) { }
    }

    public void seekTo(int ms) {
        try { vv.seekTo(ms); } catch (Throwable ignore) { }
    }

    public int getCurrentPosition() {
        try { return vv.getCurrentPosition(); } catch (Throwable e) { return 0; }
    }

    public int getDuration() {
        try { return vv.getDuration(); } catch (Throwable e) { return 0; }
    }

    public boolean isPlaying() {
        try { return vv.isPlaying(); } catch (Throwable e) { return false; }
    }

    public void stopPlayback() {
        firstFrame = false;
        try { vv.stopPlayback(); } catch (Throwable ignore) { }
    }

    /**
     * 【v4.18.0】彻底交还解码器，但保留 Surface。
     *
     * 换片 / 换清晰度时只调 pause() 是不够的：旧的 MediaPlayer 仍然持有
     * 硬件解码器实例，某些机型上两个解码器并存会导致「新的那路一直不出画面、
     * 声音却正常」（就是用户反馈的黑屏卡死）。stopPlayback() 会把播放器整个释放；
     * 再补一个 suspend()（API 24+，反射调用，低版本忽略）把残留的解码会话也收掉。
     * Surface 本身不受影响，下一次 setVideoPath 仍复用同一块，不需要重建。
     */
    public void reset() {
        firstFrame = false;
        try { vv.stopPlayback(); } catch (Throwable ignore) { }
        try {
            java.lang.reflect.Method m = VideoView.class.getMethod("suspend");
            m.invoke(vv);
        } catch (Throwable ignore) { }
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        vv.setOnPreparedListener(l);
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener l) {
        vv.setOnErrorListener(l);
    }

    /** 是否已经渲染出第一帧 */
    public boolean hasRenderedFrame() {
        if (firstFrame) return true;
        // 部分设备不回调 MEDIA_INFO_VIDEO_RENDERING_START，用进度兜底，避免看门狗误报
        try {
            if (vv.isPlaying() && vv.getCurrentPosition() > 800) return true;
        } catch (Throwable ignore) { }
        return false;
    }

    /** 兼容旧接口：VideoView 自己会管理 Surface，无需外部重绑 */
    public void rebindSurface() {
        // 空实现，保留方法签名避免调用方报错
    }
}
