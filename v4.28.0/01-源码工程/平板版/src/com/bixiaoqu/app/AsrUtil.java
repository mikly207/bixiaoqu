package com.bixiaoqu.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 【v4.14.0】在线语音转文字：百度语音 REST API。
 *
 * 合规说明：
 *  - App 不内置任何第三方凭证；API Key / Secret Key 由用户在「录音棚 → 转文字设置」自行填入；
 *  - 仅做标准 HTTPS 调用，不破解、不绕过任何服务；无密钥时不会发起请求。
 *
 * API22 兼容：只用 HttpURLConnection / org.json / android.util.Base64。
 */
public final class AsrUtil {

    /** 转文字结果 */
    public static class Result {
        public String text;
        public String error;
        static Result ok(String t) { Result r = new Result(); r.text = t; return r; }
        static Result fail(String e) { Result r = new Result(); r.error = e; return r; }
    }

    /**
     * 识别本地录音文件（m4a / amr / mp3 / wav / pcm）。
     * @param file   录音文件
     * @param ak     百度 API Key
     * @param sk     百度 Secret Key
     */
    public static Result recognize(File file, String ak, String sk) {
        if (file == null || !file.isFile()) return Result.fail("录音文件不存在");
        if (ak == null || ak.isEmpty() || sk == null || sk.isEmpty())
            return Result.fail("未配置识别密钥，请先在「转文字设置」里填写百度语音的 API Key / Secret Key");
        long len = file.length();
        if (len > 10L * 1024 * 1024) return Result.fail("录音太大（>10MB），百度短语音识别不支持，请录短一些");

        String token = fetchToken(ak, sk);
        if (token == null) return Result.fail("获取识别令牌失败：请检查网络与密钥是否正确");

        HttpURLConnection conn = null;
        try {
            byte[] audio = readAll(file);
            String b64 = Base64.encodeToString(audio, Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("format", "m4a");      // MediaRecorder 录的是 MPEG-4/AAC
            body.put("rate", 16000);
            body.put("channel", 1);
            body.put("cuid", "bxq-recorder");
            body.put("token", token);
            body.put("speech", b64);
            body.put("len", (int) audio.length);
            // dev_pid：1537=普通话(简单)，1536=普通话，1637=方言；用 1537 中文识别
            body.put("dev_pid", 1537);

            URL url = new URL("https://vop.baidu.com/server_api");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "bixiaoqu-recorder");
            byte[] data = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = in != null ? new String(readAll(in), "UTF-8") : "";
            if (in != null) in.close();

            JSONObject o = new JSONObject(resp);
            int errNo = o.optInt("err_no", -1);
            if (errNo == 0) {
                String text = o.optString("result", "");
                if (text.startsWith("[")) {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(text);
                        text = arr.length() > 0 ? arr.optString(0) : "";
                    } catch (Throwable ignore) { }
                }
                if (text.isEmpty()) return Result.fail("识别成功但内容为空（可能是静音或噪声）");
                return Result.ok(text);
            }
            String msg = o.optString("err_msg", "未知错误");
            return Result.fail("识别失败(" + errNo + ")：" + msg
                    + (errNo == 3301 || errNo == 3303 ? "（请确认 m4a 采样率 16k、单声道）" : ""));
        } catch (Throwable e) {
            return Result.fail("识别异常：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 用 AK/SK 换取 access_token */
    static String fetchToken(String ak, String sk) {
        HttpURLConnection conn = null;
        try {
            String u = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(ak, "UTF-8")
                    + "&client_secret=" + URLEncoder.encode(sk, "UTF-8");
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = in != null ? new String(readAll(in), "UTF-8") : "";
            if (in != null) in.close();
            JSONObject o = new JSONObject(resp);
            String t = o.optString("access_token", "");
            return t.isEmpty() ? null : t;
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try { return readAll(in); }
        finally { in.close(); }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private AsrUtil() { }
}
