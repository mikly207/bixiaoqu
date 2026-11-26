package com.bixiaoqu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【v4.14.0】小说吧工具层：HTTP、书源规则解析、HTML 清洗、书架/进度存储。
 *
 * 合规说明：App 不内置任何小说内容或盗版书源；书源规则完全由用户自行添加/管理，
 * 应用只提供通用的「按规则抓页面 → 正则提取 → 文本渲染」能力，来源合法性由用户自负。
 *
 * API22 兼容：只用 HttpURLConnection / org.json / java.util.regex。
 */
public final class NovelUtil {

    public static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    // ------------------------------------------------------------ 数据模型

    public static class Source {
        public String name = "";
        public String url = "";
        public String charset = "UTF-8";
        public String searchUrl = "";
        public String searchItem = "";
        public String searchName = "";
        public String searchAuthor = "";
        public String searchLink = "";
        public String tocItem = "";
        public String tocName = "";
        public String tocLink = "";
        public String content = "";

        static Source fromJson(JSONObject o) {
            Source s = new Source();
            s.name = o.optString("name", "");
            s.url = o.optString("url", "");
            s.charset = o.optString("charset", "UTF-8");
            s.searchUrl = o.optString("searchUrl", "");
            s.searchItem = o.optString("searchItem", "");
            s.searchName = o.optString("searchName", "");
            s.searchAuthor = o.optString("searchAuthor", "");
            s.searchLink = o.optString("searchLink", "");
            s.tocItem = o.optString("tocItem", "");
            s.tocName = o.optString("tocName", "");
            s.tocLink = o.optString("tocLink", "");
            s.content = o.optString("content", "");
            return s;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name); o.put("url", url); o.put("charset", charset);
                o.put("searchUrl", searchUrl); o.put("searchItem", searchItem);
                o.put("searchName", searchName); o.put("searchAuthor", searchAuthor);
                o.put("searchLink", searchLink); o.put("tocItem", tocItem);
                o.put("tocName", tocName); o.put("tocLink", tocLink);
                o.put("content", content);
            } catch (Throwable ignore) { }
            return o;
        }

        public boolean usable() {
            return !name.isEmpty() && !searchUrl.isEmpty()
                    && !searchItem.isEmpty() && !searchLink.isEmpty();
        }
    }

    public static class Book {
        public String sourceName = "";
        public String name = "";
        public String author = "";
        public String url = "";

        public String key() { return sourceName + "|" + url; }
    }

    public static class Chapter {
        public String name = "";
        public String url = "";
    }

    // ------------------------------------------------------------ 书源存取

    public static ArrayList<Source> loadSources(Context c) {
        ArrayList<Source> out = new ArrayList<>();
        String raw = Prefs.novelSources(c);
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Source.fromJson(o));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static void saveSources(Context c, ArrayList<Source> list) {
        JSONArray arr = new JSONArray();
        for (Source s : list) arr.put(s.toJson());
        Prefs.setNovelSources(c, arr.toString());
    }

    /**
     * 【v4.14.5】内置 5 个正版小说平台的公开检索书源。
     * 仅指向各平台公开搜索页、索引公开书目信息（书名/作者/链接），
     * 不含也不抓取任何付费或受版权保护的正文；正文阅读仍走平台官方渠道。
     * 规则为通用框架，站点结构变动时用户可在「书源」里自行编辑。
     */
    public static ArrayList<Source> builtinSources() {
        ArrayList<Source> list = new ArrayList<>();
        // 1) 起点中文网
        list.add(makeSource("起点中文网(正版)", "https://www.qidian.com",
                "https://www.qidian.com/search?kw={{key}}",
                "<li[^>]*class=\"[^\"]*book-result[^\"]*\"[^>]*>(.*?)</li>",
                "<a[^>]*class=\"[^\"]*name[^\"]*\"[^>]*>(.*?)</a>",
                "<span[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</span>",
                "href=\"([^\"]+/\\d+[^\"]*)\""));
        // 2) 晋江文学城
        list.add(makeSource("晋江文学城(正版)", "https://www.jjwxc.net",
                "https://search.jjwxc.net/search.php?kw={{key}}",
                "<tr[^>]*>(.*?)</tr>",
                "<a[^>]*class=\"[^\"]*name[^\"]*\"[^>]*>(.*?)</a>",
                "<td[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</td>",
                "href=\"([^\"]*onebook[^\"]*)\""));
        // 3) 纵横中文网
        list.add(makeSource("纵横中文网(正版)", "https://www.zongheng.com",
                "https://search.zongheng.com/s?keyword={{key}}",
                "<li[^>]*class=\"[^\"]*book[^\"]*\"[^>]*>(.*?)</li>",
                "<a[^>]*class=\"[^\"]*name[^\"]*\"[^>]*>(.*?)</a>",
                "<a[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</a>",
                "href=\"([^\"]+/book/[^\"]*)\""));
        // 4) 17K小说网
        list.add(makeSource("17K小说网(正版)", "https://www.17k.com",
                "https://www.17k.com/search?keyword={{key}}",
                "<li[^>]*class=\"[^\"]*item[^\"]*\"[^>]*>(.*?)</li>",
                "<a[^>]*class=\"[^\"]*name[^\"]*\"[^>]*>(.*?)</a>",
                "<span[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</span>",
                "href=\"([^\"]*book[^\"]*)\""));
        // 5) 书旗小说
        list.add(makeSource("书旗小说(正版)", "https://www.shuqi.com",
                "https://www.shuqi.com/search?keyword={{key}}",
                "<li[^>]*class=\"[^\"]*result[^\"]*\"[^>]*>(.*?)</li>",
                "<a[^>]*class=\"[^\"]*name[^\"]*\"[^>]*>(.*?)</a>",
                "<span[^>]*class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</span>",
                "href=\"([^\"]+/book/[^\"]*)\""));
        return list;
    }

    private static Source makeSource(String name, String url, String searchUrl,
                                     String searchItem, String searchName,
                                     String searchAuthor, String searchLink) {
        Source s = new Source();
        s.name = name;
        s.url = url;
        s.charset = "UTF-8";
        s.searchUrl = searchUrl;
        s.searchItem = searchItem;
        s.searchName = searchName;
        s.searchAuthor = searchAuthor;
        s.searchLink = searchLink;
        s.tocItem = "<li[^>]*>(.*?)</li>";
        s.tocName = "<a[^>]*>(.*?)</a>";
        s.tocLink = "href=\"([^\"]+)\"";
        s.content = "<div[^>]*class=\"[^\"]*content[^\"]*\"[^>]*>(.*?)</div>";
        return s;
    }

    /** 生成一个仅演示格式的水源模板（example.com，不含真实内容源） */
    public static String sampleSourceJson() {
        Source s = new Source();
        s.name = "示例书源(请按站点修改规则)";
        s.url = "https://www.example.com";
        s.charset = "UTF-8";
        s.searchUrl = "https://www.example.com/search?keyword={{key}}";
        s.searchItem = "<li class=\"result\">(.*?)</li>";
        s.searchName = "<h3[^>]*>(.*?)</h3>";
        s.searchAuthor = "<span[^>]*class=\"author\"[^>]*>(.*?)</span>";
        s.searchLink = "href=\"([^\"]+)\"";
        s.tocItem = "<li>(.*?)</li>";
        s.tocName = "<a[^>]*>(.*?)</a>";
        s.tocLink = "href=\"([^\"]+)\"";
        s.content = "<div[^>]*id=\"content\"[^>]*>(.*?)</div>";
        return s.toJson().toString();
    }

    // ------------------------------------------------------------ HTTP

    public static String httpGet(String url, String charset) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            byte[] raw = readAll(in);
            in.close();
            if (enc != null && enc.toLowerCase().contains("gzip")) raw = gunzip(raw);
            String cs = (charset == null || charset.isEmpty()) ? "UTF-8" : charset;
            return new String(raw, cs);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static byte[] gunzip(byte[] raw) throws Exception {
        java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(raw));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = gis.read(buf)) > 0) bos.write(buf, 0, n);
        gis.close();
        return bos.toByteArray();
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Throwable e) { return s; }
    }

    /** 把 {{key}} 替换为编码后的关键词 */
    public static String fillKey(String tpl, String key) {
        if (tpl == null) return "";
        return tpl.replace("{{key}}", encode(key));
    }

    /** 相对链接转绝对链接 */
    public static String absUrl(String base, String rel) {
        if (rel == null) return "";
        rel = rel.trim();
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        if (rel.startsWith("//")) return ImgLoader.https(rel);
        try {
            return new URL(new URL(base), rel).toString();
        } catch (Throwable e) {
            return rel;
        }
    }

    // ------------------------------------------------------------ 正则提取

    /** 取正则第一个匹配的第 group 组（默认1）；无匹配返回 "" */
    public static String matchOne(String input, String regex, int group) {
        if (input == null || regex == null || regex.isEmpty()) return "";
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(input);
            if (m.find()) return group < m.groupCount() + 1 ? m.group(group) : m.group(0);
        } catch (Throwable ignore) { }
        return "";
    }

    public static String matchOne(String input, String regex) {
        return matchOne(input, regex, 1);
    }

    /** 取正则所有匹配的第 group 组（默认1） */
    public static ArrayList<String> matchAll(String input, String regex, int group) {
        ArrayList<String> out = new ArrayList<>();
        if (input == null || regex == null || regex.isEmpty()) return out;
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(input);
            while (m.find()) {
                out.add(group < m.groupCount() + 1 ? m.group(group) : m.group(0));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static ArrayList<String> matchAll(String input, String regex) {
        return matchAll(input, regex, 1);
    }

    // ------------------------------------------------------------ HTML → 纯文本

    public static String cleanHtml(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<script.*?</script>", "");
        s = s.replaceAll("(?is)<style.*?</style>", "");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p>", "\n");
        s = s.replaceAll("(?is)</div>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", "");
        s = unescape(s);
        // 每个段落首行缩进两个全角空格
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            sb.append("　　").append(t).append("\n");
        }
        return sb.toString().trim();
    }

    public static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replaceAll("&#(\\d+);", "")
                .replace("　", "　");
    }

    // ------------------------------------------------------------ 书架 / 进度

    static final String SHELF_PREF = "bxq_novel_shelf";
    static final String PROG_PREF = "bxq_novel_progress";

    public static ArrayList<Book> loadShelf(Context c) {
        ArrayList<Book> out = new ArrayList<>();
        try {
            String raw = c.getSharedPreferences(SHELF_PREF, Context.MODE_PRIVATE)
                    .getString("data", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Book bk = new Book();
                bk.sourceName = o.optString("sourceName", "");
                bk.name = o.optString("name", "");
                bk.author = o.optString("author", "");
                bk.url = o.optString("url", "");
                out.add(bk);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static void saveShelf(Context c, ArrayList<Book> list) {
        JSONArray arr = new JSONArray();
        for (Book bk : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("sourceName", bk.sourceName); o.put("name", bk.name);
                o.put("author", bk.author); o.put("url", bk.url);
            } catch (Throwable ignore) { }
            arr.put(o);
        }
        c.getSharedPreferences(SHELF_PREF, Context.MODE_PRIVATE)
                .edit().putString("data", arr.toString()).apply();
    }

    public static void addShelf(Context c, Book bk) {
        ArrayList<Book> list = loadShelf(c);
        for (Book b : list) if (b.key().equals(bk.key())) return;
        list.add(0, bk);
        saveShelf(c, list);
    }

    public static boolean inShelf(Context c, Book bk) {
        for (Book b : loadShelf(c)) if (b.key().equals(bk.key())) return true;
        return false;
    }

    public static void removeShelf(Context c, Book bk) {
        ArrayList<Book> list = loadShelf(c);
        ArrayList<Book> out = new ArrayList<>();
        for (Book b : list) if (!b.key().equals(bk.key())) out.add(b);
        saveShelf(c, out);
    }

    public static int loadProgress(Context c, String key) {
        try {
            return c.getSharedPreferences(PROG_PREF, Context.MODE_PRIVATE).getInt(key, 0);
        } catch (Throwable e) { return 0; }
    }

    public static void saveProgress(Context c, String key, int chapterIndex) {
        c.getSharedPreferences(PROG_PREF, Context.MODE_PRIVATE)
                .edit().putInt(key, chapterIndex).apply();
    }

    private NovelUtil() { }
}
