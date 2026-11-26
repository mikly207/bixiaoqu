package com.bixiaoqu.app;

import java.io.InputStream;
import java.io.OutputStream;

import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.PsyModel;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mp3.GetAudio;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Parse;
import de.sciss.jump3r.mp3.VbrMode;
import de.sciss.jump3r.mpg.MPGLib;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.Common;

/**
 * MP3 编码桥接：把 MediaCodec 解出的 PCM(16bit LE 交错) 流实时编码为 MP3。
 * 纯 Java（jump3r / LAME 移植），不依赖 javax.sound，Android 可直接用，
 * 桌面 JVM 亦可运行（已做交叉验证）。
 */
public final class LameBridge {

    public interface Progress {
        void onProgress(double ratio);
    }

    /**
     * @param sampleRate   采样率，如 44100/48000
     * @param channels     1 或 2
     * @param pcm16le      PCM 数据流（16bit 小端交错）
     * @param totalPcm     PCM 总字节数（进度用；<=0 忽略进度）
     * @param bitrateKbps  MP3 目标码率，如 320
     * @param mp3Out       MP3 输出流
     */
    public static void encodeMp3(int sampleRate, int channels,
                                 InputStream pcm16le, long totalPcm,
                                 int bitrateKbps, OutputStream mp3Out,
                                 Progress progress) throws Exception {
        if (channels != 1 && channels != 2)
            throw new IllegalArgumentException("声道数仅支持 1/2，实际 " + channels);

        // 全模块互连（与官方 LameEncoder 构造序列一致）
        Lame lame = new Lame();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets pr = new Presets();
        QuantizePVT qv = new QuantizePVT();
        Quantize q = new Quantize();
        Reservoir rv = new Reservoir();
        Takehiro th = new Takehiro();
        VBRTag vbrTag = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = new ID3Tag();
        MPGLib mpg = new MPGLib();
        Interface itf = new Interface();
        Common cm = new Common();
        Parse parse = new Parse();
        GetAudio gau = new GetAudio();
        PsyModel psy = new PsyModel();

        lame.setModules(ga, bs, pr, qv, q, vbrTag, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbrTag);
        id3.setModules(bs, ver);
        pr.setModules(lame);
        q.setModules(bs, rv, qv, th);
        qv.setModules(th, rv, psy);
        rv.setModules(bs);
        th.setModules(qv);
        vbrTag.setModules(lame, bs, ver);
        gau.setModules(parse, mpg);
        parse.setModules(ver, id3, pr);
        mpg.setModules(itf, cm);
        itf.setModules(vbrTag, cm);

        LameGlobalFlags gfp = lame.lame_init();

        gfp.num_channels = channels;
        gfp.in_samplerate = sampleRate;
        gfp.out_samplerate = sampleRate;
        gfp.brate = bitrateKbps;
        gfp.VBR = VbrMode.vbr_off;              // CBR
        gfp.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        gfp.quality = 2;                        // 0-9，2 = 高质量
        gfp.bWriteVbrTag = false;               // 不写 Xing 头
        gfp.write_id3tag_automatic = false;
        lame.lame_init_params(gfp);

        int frameSamples = 1152;
        int chunkPcm = frameSamples * channels * 2 * 16;  // 每次读取的 PCM 字节
        byte[] in = new byte[chunkPcm];
        int[] left = new int[frameSamples * 16];
        int[] right = new int[frameSamples * 16];
        byte[] mp3buf = new byte[chunkPcm + 7200];

        long fed = 0;
        try {
            int n;
            while ((n = pcm16le.read(in, 0, chunkPcm)) > 0) {
                int samples = n / (channels * 2);
                for (int i = 0; i < samples; i++) {
                    int base = i * channels * 2;
                    int l = (short) ((in[base] & 0xFF) | (in[base + 1] << 8));
                    left[i] = l;
                    if (channels == 2) {
                        int r = (short) ((in[base + 2] & 0xFF) | (in[base + 3] << 8));
                        right[i] = r;
                    }
                }
                int written = lame.lame_encode_buffer_int(
                        gfp, left, channels == 2 ? right : left, samples,
                        mp3buf, 0, mp3buf.length);
                if (written > 0) mp3Out.write(mp3buf, 0, written);
                fed += n;
                if (progress != null && totalPcm > 0)
                    progress.onProgress(Math.min(1.0, (double) fed / totalPcm));
            }
            int written = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.length);
            if (written > 0) mp3Out.write(mp3buf, 0, written);
        } finally {
            lame.lame_close(gfp);
        }
        mp3Out.flush();
    }

    private LameBridge() {}
}
