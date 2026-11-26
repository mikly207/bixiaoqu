package com.bixiaoqu.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 无损封装：把 DASH 的 video.m4s + audio.m4s 直接合成为一个标准 MP4。
 * 全程只搬运编码帧，不重新编解码 —— CPU 几乎不占用、画质音质零损失。
 */
public final class Mp4Muxer {

    /** @return true=成功；false=失败（调用方应回退合一流方案） */
    public static boolean mux(File video, File audio, File out) {
        MediaExtractor ve = null, ae = null;
        MediaMuxer mux = null;
        try {
            ve = new MediaExtractor();
            ve.setDataSource(video.getAbsolutePath());
            int vIdx = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ve.getTrackCount(); i++) {
                MediaFormat f = ve.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vIdx = i; vf = f; break; }
            }
            if (vIdx < 0 || vf == null) return false;

            int aIdx = -1;
            MediaFormat af = null;
            if (audio != null && audio.exists() && audio.length() > 0) {
                ae = new MediaExtractor();
                ae.setDataSource(audio.getAbsolutePath());
                for (int i = 0; i < ae.getTrackCount(); i++) {
                    MediaFormat f = ae.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { aIdx = i; af = f; break; }
                }
            }

            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                if (vf.containsKey("rotation-degrees")) {
                    mux.setOrientationHint(vf.getInteger("rotation-degrees"));
                }
            } catch (Throwable ignore) { }
            int vTrack = mux.addTrack(vf);
            int aTrack = af != null ? mux.addTrack(af) : -1;
            mux.start();

            copy(ve, vIdx, mux, vTrack);
            if (ae != null && aIdx >= 0) copy(ae, aIdx, mux, aTrack);

            mux.stop();
            return out.exists() && out.length() > 0;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (mux != null) mux.release(); } catch (Throwable ignore) { }
            try { if (ve != null) ve.release(); } catch (Throwable ignore) { }
            try { if (ae != null) ae.release(); } catch (Throwable ignore) { }
        }
    }

    static void copy(MediaExtractor ex, int idx, MediaMuxer mux, int outTrack) {
        ex.selectTrack(idx);
        ByteBuffer buf = ByteBuffer.allocateDirect(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    private Mp4Muxer() {}
}
