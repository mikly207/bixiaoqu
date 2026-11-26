package com.bixiaoqu.app;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站 WBI 签名（按 bilibili-API-collect 公开文档实现）
 * w_rid = md5(排序后参数串 + mixinKey)，mixinKey 由 img/sub key 重排得到
 */
public final class Wbi {

    static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    /** img_key + sub_key 按 MIXIN_TAB 重排，取前 32 位 */
    public static String mixinKey(String imgKey, String subKey) {
        String all = (imgKey + subKey);
        StringBuilder sb = new StringBuilder(32);
        for (int i : MIXIN_TAB) {
            if (i < all.length()) sb.append(all.charAt(i));
            if (sb.length() == 32) break;
        }
        return sb.toString();
    }

    public static String md5hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 对参数做 WBI 签名，返回完整 query（含 wts 与 w_rid）。
     * params 会被复制，不修改调用方数据。
     */
    public static String sign(Map<String, String> params, String mixinKey) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        long wts = System.currentTimeMillis() / 1000L;
        sorted.put("wts", String.valueOf(wts));

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            // 文档要求：值中去除 !'()* 字符
            String v = e.getValue().replaceAll("[!'()*]", "");
            String ev = urlencode(v);
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(ev);
        }
        String w_rid = md5hex(q.toString() + mixinKey);
        // wts 已经在排序后的 q 里，末尾不能再拼一次，否则部分接口验签失败
        return q.toString() + "&w_rid=" + w_rid;
    }

    /** 模拟 JS encodeURIComponent：空格 → %20，并还原 !'()* */
    static String urlencode(String v) {
        try {
            String e = URLEncoder.encode(v, "UTF-8");
            return e.replace("+", "%20")
                    .replace("%21", "!").replace("%27", "'")
                    .replace("%28", "(").replace("%29", ")")
                    .replace("%2A", "*");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Wbi() {}
}
