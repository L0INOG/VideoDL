package com.videodl.app.parser;

import org.json.JSONArray;
import org.json.JSONObject;

import com.videodl.app.net.Http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【解析引擎 · 哔哩哔哩】本地解析引擎（移植自 bilibili-parser 参考实现，改用 org.json）。
 * 只和 bilibili / bilivideo 自己的域名通信，全程本地完成。
 *
 * 链路:
 *   短链 b23.tv/xxx --302--> 视频页 URL --正则--> BV 号
 *   -> /x/web-interface/nav 取 wbi 密钥（本地混淆表重排 + MD5 签名）
 *   -> /x/web-interface/view 取标题/UP主/cid（带 wbi 签名）
 *   -> /x/player/playurl 取 durl 播放地址（带 wbi 签名，qn 从高到低尝试）
 *
 * 未登录可拿 720P 单流 MP4；登录 cookie（SESSDATA）可尝试更高清晰度。
 * 注意: B 站 CDN 强制校验 Referer，下载连接必须带 https://www.bilibili.com/。
 * 扩展点: 高清晰度档位与登录态相关逻辑在 fetchPlayUrl 的 qn 表里调整。
 */
public final class BiliParser {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13; PJD110 Build/UKQ1.230924.001; wv) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                    + "Chrome/120.0.6099.144 Mobile Safari/537.36";

    private static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";
    private static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAY_API = "https://api.bilibili.com/x/player/playurl";

    private static final String[] ALLOWED_HOSTS = {
            "bilivideo.com", "bilibili.com", "hdslb.com", "biliapi.com",
            "b23.tv", "bilibili.tv", "akamaized.net", "biliapi.net"
    };

    private static final Pattern BVID_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern AID_PATTERN = Pattern.compile("/av(\\d+)");

    /** wbi 混淆表：把 img_key + sub_key 重排成 32 位 mixin_key */
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static String mixinKey = "";
    private static long mixinKeyTs = 0;

    /** 一次解析得到的视频信息（标题/UP主/cid/分段地址） */
    public static class Result {
        public String bvid = "";
        public String title = "";
        public String up = "";
        public long cid = 0;
        public int quality = 0;
        public final List<MediaSeg> segments = new ArrayList<>();
        public final List<Long> pageCids = new ArrayList<>();

        public String qualityText() {
            switch (quality) {
                case 120: return "4K";
                case 116: return "1080P60";
                case 112: return "1080P+";
                case 80: return "1080P";
                case 64: return "720P";
                case 32: return "480P";
                case 16: return "360P";
                default: return quality > 0 ? (quality + "P") : "默认";
            }
        }
    }

    private BiliParser() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·B站] 链接 → BV 号
    // ═══════════════════════════════════════════════════════════════

    /** 短链重定向 / 长链直接正则 -> BV 号（或 "aid:数字"），失败返回 null。 */
    public static String resolveBvid(String link) throws IOException {
        if (link == null) {
            return null;
        }
        Matcher m = BVID_PATTERN.matcher(link);
        if (m.find()) {
            return m.group(1);
        }
        m = AID_PATTERN.matcher(link);
        if (m.find()) {
            return "aid:" + m.group(1);
        }
        // b23.tv 短链：手动跟随重定向
        String current = link;
        for (int i = 0; i < Http.MAX_REDIRECTS; i++) {
            HttpURLConnection conn = Http.open(current,
                    Http.headerMap("User-Agent", UA),
                    Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            String finalUrl = String.valueOf(conn.getURL());
            Http.drain(conn);
            if (code >= 300 && code < 400 && loc != null) {
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            m = BVID_PATTERN.matcher(finalUrl);
            if (m.find()) {
                return m.group(1);
            }
            if (code == 200) {
                break;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·B站] wbi 签名
    // ═══════════════════════════════════════════════════════════════

    /** nav 接口拿 img_key / sub_key，进程内缓存 1 小时。 */
    public static boolean ensureWbi(String cookies) {
        if (!mixinKey.isEmpty() && System.currentTimeMillis() - mixinKeyTs < 3600_000L) {
            return true;
        }
        HttpURLConnection conn = null;
        try {
            conn = openGet(NAV_API, cookies);
            JSONObject j = new JSONObject(Http.readBody(conn));
            JSONObject wbi = j.optJSONObject("data") == null
                    ? null : j.getJSONObject("data").optJSONObject("wbi_img");
            if (wbi == null) {
                return false;
            }
            String imgKey = tailKey(wbi.optString("img_url", ""));
            String subKey = tailKey(wbi.optString("sub_url", ""));
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                return false;
            }
            mixinKey = mix(imgKey + subKey);
            mixinKeyTs = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String tailKey(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mix(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN_TAB) {
            if (i < raw.length()) {
                sb.append(raw.charAt(i));
            }
        }
        return sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    }

    /** 参数按字典序排序 -> 过滤 !'()* -> URL 编码拼串 -> 追加 w_rid = md5(query + mixin_key) */
    private static String sign(Map<String, String> params) {
        params.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder q = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (q.length() > 0) {
                    q.append('&');
                }
                String v = String.valueOf(e.getValue());
                StringBuilder vv = new StringBuilder();
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    if (c != '!' && c != '\'' && c != '(' && c != ')' && c != '*') {
                        vv.append(c);
                    }
                }
                q.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(vv.toString(), "UTF-8"));
            }
        } catch (Exception e) {
            return q.toString();
        }
        return q + "&w_rid=" + md5(q.toString() + mixinKey);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·B站] 详情与播放地址
    // ═══════════════════════════════════════════════════════════════

    /** 视频详情：标题/UP主/cid/分P。失败抛 IOException。 */
    public static Result fetchView(String bvid, String cookies) throws IOException {
        Map<String, String> p = new TreeMap<>();
        if (bvid.startsWith("aid:")) {
            p.put("aid", bvid.substring(4));
        } else {
            p.put("bvid", bvid);
        }
        HttpURLConnection conn = null;
        try {
            conn = openGet(VIEW_API + "?" + sign(p), cookies);
            JSONObject root = new JSONObject(Http.readBody(conn));
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                throw new IOException("B站未返回视频信息（code=" + root.optInt("code", -1) + "）");
            }
            Result r = new Result();
            r.bvid = data.optString("bvid", bvid);
            r.title = data.optString("title", "").trim();
            JSONObject owner = data.optJSONObject("owner");
            r.up = owner == null ? "" : owner.optString("name", "");
            r.cid = data.optLong("cid", 0);
            JSONArray pages = data.optJSONArray("pages");
            if (pages != null) {
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject it = pages.optJSONObject(i);
                    if (it != null) {
                        r.pageCids.add(it.optLong("cid", 0));
                    }
                }
            }
            if (r.cid == 0 && !r.pageCids.isEmpty()) {
                r.cid = r.pageCids.get(0);
            }
            if (r.cid == 0) {
                throw new IOException("B站未返回 cid");
            }
            return r;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("B站视频信息解析失败：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 播放地址：qn 由高到低尝试，返回 durl 分段列表（每段含主/备地址），同时写入 r.segments。 */
    public static List<MediaSeg> fetchPlayUrl(Result r, String cookies) throws IOException {
        boolean logged = cookies != null && cookies.contains("SESSDATA=");
        int[] qns = logged ? new int[]{116, 112, 80, 64, 32, 16}
                : new int[]{64, 32, 16};
        int[] fnvals = {0, 1};
        IOException last = null;
        for (int fnval : fnvals) {
            for (int qn : qns) {
                try {
                    List<MediaSeg> urls = tryPlay(r, qn, fnval, cookies);
                    if (!urls.isEmpty()) {
                        r.segments.addAll(urls);   // 务必同步进 Result，下载阶段从这里取
                        return urls;
                    }
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw new IOException("未取到B站播放地址" + (last == null ? "" : "：" + last.getMessage()));
    }

    private static List<MediaSeg> tryPlay(Result r, int qn, int fnval, String cookies)
            throws IOException {
        Map<String, String> p = new TreeMap<>();
        if (r.bvid.startsWith("aid:")) {
            p.put("aid", r.bvid.substring(4));
        } else {
            p.put("bvid", r.bvid);
        }
        p.put("cid", String.valueOf(r.cid));
        p.put("qn", String.valueOf(qn));
        p.put("fnval", String.valueOf(fnval));
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("otype", "json");
        p.put("platform", "html5");

        HttpURLConnection conn = null;
        try {
            conn = openGet(PLAY_API + "?" + sign(p), cookies);
            JSONObject root = new JSONObject(Http.readBody(conn));
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return new ArrayList<>();
            }
            r.quality = data.optInt("quality", qn);
            List<MediaSeg> out = new ArrayList<>();
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null) {
                for (int i = 0; i < durl.length(); i++) {
                    JSONObject seg = durl.optJSONObject(i);
                    if (seg == null) {
                        continue;
                    }
                    // 主地址是字符串，backup_url 才是数组
                    String u = seg.optString("url", "");
                    String backup = "";
                    JSONArray backups = seg.optJSONArray("backup_url");
                    if (backups != null && backups.length() > 0) {
                        for (int k = 0; k < backups.length(); k++) {
                            String b = backups.optString(k, "");
                            if (isAllowedUrl(b)) {
                                backup = b;
                                break;
                            }
                        }
                    }
                    if (isAllowedUrl(u)) {
                        out.add(new MediaSeg(u, backup));
                    } else if (!backup.isEmpty()) {
                        out.add(new MediaSeg(backup, ""));
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("B站播放地址解析失败：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络·B站] 请求构造
    // ═══════════════════════════════════════════════════════════════

    /** B站 API 请求：必须带 bilibili 的 Referer/Origin。 */
    private static HttpURLConnection openGet(String url, String cookies) throws IOException {
        return Http.open(url, Http.headerMap(
                "User-Agent", UA,
                "Referer", "https://www.bilibili.com/",
                "Origin", "https://www.bilibili.com",
                "Accept", "application/json, text/plain, */*",
                "Accept-Language", "zh-CN,zh;q=0.9",
                "Cookie", cookies), Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
    }

    /** B站 CDN 下载连接：强制 Referer（CDN 校验），等价于 openDownload(url, null)。 */
    public static HttpURLConnection openDownload(String url) throws IOException {
        return openDownload(url, null);
    }

    /**
     * B站 CDN 下载连接：强制 Referer（CDN 校验）。
     * 手机网络下 CDN 常会 302 到就近节点，必须手动跟随重定向，
     * 否则把 302 响应体当视频写文件（0 秒坏文件的元凶）。
     * 读超时为首字节判死窗口，首个数据块到达后由 pump 放宽。
     *
     * @param range null=完整下载；"bytes=0-1023" 形式=探测用
     */
    public static HttpURLConnection openDownload(String url, String range) throws IOException {
        return Http.followForDownload(url, Http.headerMap(
                "User-Agent", UA,
                "Referer", "https://www.bilibili.com/",
                "Range", range), "B站下载地址返回", null);
    }

    /** 域名白名单：只允许 B站自家/CDN 域，防 SSRF 式滥用。 */
    public static boolean isAllowedUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return false;
        }
        try {
            String host = new URL(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.US);
            for (String h : ALLOWED_HOSTS) {
                if (host.endsWith(h)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
