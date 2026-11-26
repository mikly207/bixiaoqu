package com.bixiaoqu.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * 音频转换（系统 API + 纯 Java LAME）：
 *  - m4a: MediaExtractor + MediaMuxer 重封装（不重编码，零质量损失），支持 m4s 与 mp4 源
 *  - wav: MediaCodec 解码 PCM 并写 WAV 头
 *  - mp3: MediaCodec 解码 PCM → LameBridge（jump3r/LAME）编码，支持 CBR 码率
 */
public final class AudioConverter {

    public interface Progress {
        void onProgress(double ratio);
    }

    private static int findAudioTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------ m4a

    public static void remuxToM4a(String inPath, String outPath, Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(inPath);
            int ti = findAudioTrack(ex);
            if (ti < 0) throw new IOException("文件中没有音频轨");
            ex.selectTrack(ti);
            MediaFormat f = ex.getTrackFormat(ti);

            mux = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mt = mux.addTrack(f);
            mux.start();

            ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            long last = 0;
            while (true) {
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) break;
                info.set(0, sz, ex.getSampleTime(), ex.getSampleFlags());
                mux.writeSampleData(mt, buf, info);
                last = ex.getSampleTime();
                if (p != null && dur > 0) p.onProgress(Math.min(1.0, (double) last / dur));
                ex.advance();
            }
            mux.stop();
        } finally {
            try { ex.release(); } catch (Exception ignore) {}
            if (mux != null) try { mux.release(); } catch (Exception ignore) {}
        }
    }

    // ------------------------------------------------------------ PCM 解码（wav/mp3 共用）

    /** 解码任意容器(m4s/mp4/m4a)音轨为 16bit LE PCM 文件。返回 [sampleRate, channels, totalBytes] */
    public static long[] pcmDecode(String inPath, String pcmOutPath, Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        FileOutputStream fos = null;
        try {
            ex.setDataSource(inPath);
            int ti = findAudioTrack(ex);
            if (ti < 0) throw new IOException("文件中没有音频轨");
            ex.selectTrack(ti);
            MediaFormat f = ex.getTrackFormat(ti);
            String mime = f.getString(MediaFormat.KEY_MIME);

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(f, null, null, 0);
            dec.start();
            fos = new FileOutputStream(pcmOutPath);

            long total = 0;
            long dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            ByteBuffer inBuf = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int sr = f.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;

            while (!outputDone) {
                if (!inputDone) {
                    int ib = dec.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer bb = dec.getInputBuffer(ib);
                        int sz = ex.readSampleData(bb, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ib, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = dec.dequeueOutputBuffer(info, 10000);
                if (ob >= 0) {
                    ByteBuffer ob2 = dec.getOutputBuffer(ob);
                    if (ob2 != null && info.size > 0) {
                        byte[] pcm = new byte[info.size];
                        ob2.get(pcm);
                        fos.write(pcm);
                        total += info.size;
                    }
                    dec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    if (p != null && dur > 0) p.onProgress(Math.min(1.0, (double) info.presentationTimeUs / dur));
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
            fos.close();
            dec.stop();
            return new long[]{sr, ch, total};
        } finally {
            try { ex.release(); } catch (Exception ignore) {}
            if (dec != null) try { dec.release(); } catch (Exception ignore) {}
            if (fos != null) try { fos.close(); } catch (Exception ignore) {}
        }
    }

    // ------------------------------------------------------------ wav

    public static void decodeToWav(String inPath, String outPath, Progress p) throws IOException {
        String tmpPcm = outPath + ".pcm";
        long[] info = pcmDecode(inPath, tmpPcm, p);
        try {
            int sr = (int) info[0], ch = (int) info[1];
            long totalPcm = info[2];
            // WAV 头（小端）
            short bits = 16;
            short blockAlign = (short) (ch * bits / 8);
            int byteRate = sr * blockAlign;
            RandomAccessFile raf = new RandomAccessFile(outPath, "rw");
            raf.setLength(0);
            raf.write(bytes("RIFF"));
            raf.write(le32(totalPcm + 36));
            raf.write(bytes("WAVE"));
            raf.write(bytes("fmt "));
            raf.write(le32(16));
            raf.write(le16(1));          // PCM
            raf.write(le16(ch));
            raf.write(le32(byteRate));
            raf.write(le16(blockAlign));
            raf.write(le16(bits));
            raf.write(bytes("data"));
            raf.write(le32(totalPcm));
            raf.close();
            // 追加 PCM 数据
            FileInputStream is = new FileInputStream(tmpPcm);
            FileOutputStream os = new FileOutputStream(outPath, true);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
        } finally {
            new File(tmpPcm).delete();
        }
    }

    // ------------------------------------------------------------ mp3

    public static void decodeToMp3(String inPath, String outPath, int bitrateKbps,
                                   Progress p) throws IOException {
        String tmpPcm = outPath + ".pcm";
        long[] info = pcmDecode(inPath, tmpPcm, new Progress() {
            @Override public void onProgress(double r) {
                if (p != null) p.onProgress(r * 0.7);
            }
        });
        try {
            FileInputStream is = new FileInputStream(tmpPcm);
            FileOutputStream os = new FileOutputStream(outPath);
            LameBridge.encodeMp3((int) info[0], (int) info[1], is, info[2],
                    bitrateKbps, os, new LameBridge.Progress() {
                        @Override public void onProgress(double r) {
                            if (p != null) p.onProgress(0.7 + r * 0.3);
                        }
                    });
            is.close();
            os.close();
        } catch (Exception e) {
            throw new IOException("MP3编码失败：" + e.getMessage());
        } finally {
            new File(tmpPcm).delete();
        }
    }

    private static byte[] bytes(String s) { return s.getBytes(); }
    private static byte[] le16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
    private static byte[] le32(long v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private AudioConverter() {}
}
