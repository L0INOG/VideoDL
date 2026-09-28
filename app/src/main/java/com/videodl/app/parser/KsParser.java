package com.videodl.app.parser;

import org.json.JSONArray;
import org.json.JSONObject;

import com.videodl.app.net.Http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【解析引擎 · 快手】本地解析引擎（移植自 kuaishou-parser 参考实现，改用 org.json）。
 * 纯本地 HTTP，不经过任何中间服务器，无需签名、无需登录。
 *
 * 链路:
 *   1. 分享短链 v.kuaishou.com/xxxx --302--> photoId（同时下发 did/didv cookie）
 *   2. GET https://www.kuaishou.com/short-video/{photoId}（SSR 服务端渲染页）
 *      备用 https://v.m.chenzhongtech.com/fw/photo/{photoId}
 *   3. 页面内 window.INIT_STATE = {...} 明文 JSON
 *   4. 深度找 photo / manifest.adaptationSet[].representation[]，
 *      每档含 url / backupUrl / width / height / videoCodec
 *   5. 优先选 H.264(avc) 里分辨率最高的一档（兼容性最好），直接下载
 *
 * 扩展点: 新的页面形态（INIT_STATE 结构变化）在 parseHtml() 里适配。
 */
public final class KsParser {

    public static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    /** 快手自有域名白名单 */
    private static final String[] ALLOWED_HOSTS = {
            "kuaishou.com", "kuaishou.cn", "chenzhongtech.com", "kwai.com",
            "kwaicdn.com", "oskwai.com", "yximgs.com", "gifshow.com",
            "ksyuncs.com", "ks-cdn.com", "ksyun.com"
    };

    private static final Pattern RE_PHOTO_PARAM = Pattern.compile("[?&]photoId=([A-Za-z0-9_\\-]{6,})");
    private static final Pattern RE_PHOTO_PATH = Pattern.compile("/(?:fw/photo|short-video)/([A-Za-z0-9_\\-]{6,})");
    private static final Pattern RE_PHOTO_LOOSE = Pattern.compile("\"?photoId\"?\\s*[:=]\\s*\"?([A-Za-z0-9]{10,24})");

    // ---------------------------------------------------------------- 数据模型

    /** 一档清晰度 */
    public static final class Rep {
        public String url;
        public String backup;
        public int width;
        public int height;
        public String codec;      // avc / hevc
        public boolean hidden;

        public String pick() {
            return (url != null && !url.isEmpty()) ? url : backup;
        }

        public String label() {
            StringBuilder sb = new StringBuilder(resText());
            if (codec != null && codec.equalsIgnoreCase("hevc")) {
                sb.append(" HEVC");
            }
            return sb.toString();
        }

        private String resText() {
            // 竖屏视频快手给的 720x1280，短边才是清晰度档位
            int h = (width > 0 && height > 0) ? Math.min(width, height) : Math.max(width, height);
            if (h >= 2160) return "4K";
            if (h >= 1440) return "2K";
            if (h >= 1080) return "1080P";
            if (h >= 720) return "720P";
            if (h >= 480) return "480P";
            return h > 0 ? h + "P" : "默认";
        }
    }

    public static final class Result {
        public String photoId = "";
        public String title = "";
        public String author = "";
        public final List<Rep> reps = new ArrayList<>();

        /** 优先 H.264(avc) 里分辨率最高的，兼容性最好；没有 avc 再取任意最高档 */
        public Rep best() {
            Rep bestAvc = null, bestAny = null;
            for (Rep r : reps) {
                if (r == null || r.pick() == null) {
                    continue;
                }
                if (r.hidden && bestAny != null) {
                    continue;
                }
                if (bestAny == null || r.height > bestAny.height) {
                    bestAny = r;
                }
                if (r.codec == null || r.codec.equalsIgnoreCase("avc") || r.codec.equalsIgnoreCase("h264")) {
                    if (bestAvc == null || r.height > bestAvc.height) {
                        bestAvc = r;
                    }
                }
            }
            return bestAvc != null ? bestAvc : bestAny;
        }
    }

    private KsParser() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·快手] 链接 → photoId
    // ═══════════════════════════════════════════════════════════════

    /**
     * 解析出 photoId，并把短链服务下发的 did/didv cookie 一并返回（callsite 复用）。
     * 返回 String[]{photoId, cookies}，失败返回 null。
     */
    public static String[] resolvePhotoId(String link) {
        if (link == null || link.isEmpty()) {
            return null;
        }
        String direct = pickId(link);
        if (direct != null) {
            return new String[]{direct, ""};
        }
        StringBuilder cookies = new StringBuilder();
        String current = link;
        for (int i = 0; i < Http.MAX_REDIRECTS; i++) {
            HttpURLConnection c = null;
            try {
                c = Http.open(current, headerMap(cookies.toString()),
                        Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
                int code = c.getResponseCode();
                grabCookies(c, cookies);
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    String id = pickId(loc);
                    if (id != null) {
                        return new String[]{id, cookies.toString()};
                    }
                    current = new java.net.URL(new java.net.URL(current), loc).toString();
                    continue;
                }
                String body = Http.readBody(c);
                if (body != null && !body.isEmpty()) {
                    Matcher m = RE_PHOTO_LOOSE.matcher(body);
                    if (m.find()) {
                        return new String[]{m.group(1), cookies.toString()};
                    }
                }
            } catch (IOException e) {
                return null;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            break;
        }
        return null;
    }

    private static String pickId(String s) {
        Matcher m = RE_PHOTO_PARAM.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = RE_PHOTO_PATH.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·快手] photoId → 播放地址
    // ═══════════════════════════════════════════════════════════════

    /** 抓 SSR 页面并解析（自动尝试两个域名），失败抛 IOException。 */
    public static Result fetch(String photoId, String cookies) throws IOException {
        String[] urls = {
                "https://www.kuaishou.com/short-video/" + photoId,
                "https://v.m.chenzhongtech.com/fw/photo/" + photoId
        };
        IOException last = null;
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = Http.follow(u, headerMap(cookies),
                        Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS, null);
                int code = c.getResponseCode();
                if (code != 200) {
                    continue;
                }
                String html = Http.readBody(c);
                Result r = parseHtml(html);
                if (r != null && !r.reps.isEmpty()) {
                    if (r.photoId == null || r.photoId.isEmpty()) {
                        r.photoId = photoId;
                    }
                    return r;
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("快手页面里没找到视频地址（可能被删除/仅好友可见）");
    }

    /** 解析 SSR HTML 里的 window.INIT_STATE。 */
    public static Result parseHtml(String html) {
        if (html == null) {
            return null;
        }
        int i = html.indexOf("window.INIT_STATE");
        if (i < 0) {
            return null;
        }
        int eq = html.indexOf('=', i);
        if (eq < 0) {
            return null;
        }
        int end = html.indexOf("</script>", eq);
        String raw = html.substring(eq + 1, end < 0 ? html.length() : end).trim();
        if (raw.endsWith(";")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        if (!raw.startsWith("{")) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }

        Object photo = deepFind(root, "photo");
        Object as = deepFind(root, "adaptationSet");
        if (photo == null && as == null) {
            return null;
        }

        Result r = new Result();
        if (photo instanceof JSONObject) {
            JSONObject p = (JSONObject) photo;
            r.title = p.optString("caption", "").trim();
            r.author = p.optString("userName", "").trim();
            if (r.author.isEmpty()) {
                r.author = p.optString("user_name", "").trim();
            }
            r.photoId = p.optString("photoId", "");
        }

        // 多档清晰度：adaptationSet 可能是数组也可能单对象
        List<Object> sets = new ArrayList<>();
        if (as instanceof JSONArray) {
            JSONArray a = (JSONArray) as;
            for (int k = 0; k < a.length(); k++) {
                sets.add(a.opt(k));
            }
        } else if (as != null) {
            sets.add(as);
        }
        for (Object setObj : sets) {
            if (!(setObj instanceof JSONObject)) {
                continue;
            }
            JSONArray repsNode = ((JSONObject) setObj).optJSONArray("representation");
            if (repsNode == null) {
                continue;
            }
            for (int k = 0; k < repsNode.length(); k++) {
                JSONObject n = repsNode.optJSONObject(k);
                if (n == null) {
                    continue;
                }
                Rep rep = new Rep();
                Object u = n.opt("url");
                rep.url = u instanceof String ? (String) u : null;
                JSONArray bu = n.optJSONArray("backupUrl");
                if (bu != null) {
                    for (int x = 0; x < bu.length(); x++) {
                        String b = bu.optString(x, "");
                        if (isAllowed(b)) {
                            rep.backup = b;
                            break;
                        }
                    }
                }
                rep.width = n.optInt("width", 0);
                rep.height = n.optInt("height", 0);
                Object codec = n.opt("videoCodec");
                rep.codec = codec instanceof String ? (String) codec : null;
                Object hidden = n.opt("hidden");
                rep.hidden = Boolean.TRUE.equals(hidden) || "true".equalsIgnoreCase(String.valueOf(hidden));
                if (rep.pick() != null && isAllowed(rep.pick())) {
                    r.reps.add(rep);
                }
            }
        }

        // 图集/兜底：有些作品走 mainMvUrls
        if (r.reps.isEmpty()) {
            Object mv = deepFind(root, "mainMvUrls");
            if (mv instanceof JSONArray) {
                JSONArray a = (JSONArray) mv;
                for (int k = 0; k < a.length(); k++) {
                    JSONObject n = a.optJSONObject(k);
                    String u = n == null ? null : n.optString("url", null);
                    if (u != null && isAllowed(u)) {
                        Rep rep = new Rep();
                        rep.url = u;
                        r.reps.add(rep);
                    }
                }
            }
        }
        return r;
    }

    /** 递归按 key 深度查找（沿用参考实现对 INIT_STATE 混淆结构的兼容思路） */
    private static Object deepFind(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject jo = (JSONObject) o;
            Object hit = jo.opt(key);
            if (hit != null) {
                return hit;
            }
            Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                Object r = deepFind(jo.opt(it.next()), key);
                if (r != null) {
                    return r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object r = deepFind(a.opt(i), key);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络·快手] 请求构造与 cookie
    // ═══════════════════════════════════════════════════════════════

    /** SSR 页面请求头：浏览器式 Accept + 短链服务下发的 did/didv cookie */
    private static Map<String, String> headerMap(String cookies) {
        return Http.headerMap(
                "User-Agent", UA,
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language", "zh-CN,zh;q=0.9",
                "Cookie", cookies);
    }

    /**
     * 快手 CDN 下载连接：带 Referer（CDN 校验）+ 域名白名单校验（含每一跳重定向）。
     * CDN 可能 302 到就近节点，必须手动跟随重定向。
     * 读超时为首字节判死窗口，首个数据块到达后由 pump 放宽。
     */
    public static HttpURLConnection openDownload(String url) throws IOException {
        if (!isAllowed(url)) {
            throw new IOException("非快手地址，已拒绝");
        }
        return Http.followForDownload(url, Http.headerMap(
                "User-Agent", UA,
                "Referer", "https://www.kuaishou.com/",
                "Accept", "*/*"), "快手下载地址返回", new Http.HopValidator() {
            @Override
            public boolean allow(String nextUrl) {
                return isAllowed(nextUrl);
            }
        });
    }

    /** 域名白名单校验（主域名精确匹配或其子域）。 */
    public static boolean isAllowed(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase();
        int i = u.indexOf("://");
        if (i < 0 || (!u.startsWith("http://") && !u.startsWith("https://"))) {
            return false;
        }
        int start = i + 3;
        int slash = u.indexOf('/', start);
        String host = slash < 0 ? u.substring(start) : u.substring(start, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        for (String h : ALLOWED_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) {
                return true;
            }
        }
        return false;
    }

    /** 把响应里 Set-Cookie 的 did / didv / kuaishou.web 合并进 cookie 串 */
    private static void grabCookies(HttpURLConnection c, StringBuilder cookies) {
        Map<String, List<String>> hdr = c.getHeaderFields();
        if (hdr == null) {
            return;
        }
        List<String> sc = hdr.get("Set-Cookie");
        if (sc == null) {
            return;
        }
        for (String one : sc) {
            String nv = one.split(";")[0];
            int p = nv.indexOf('=');
            if (p <= 0) {
                continue;
            }
            String name = nv.substring(0, p).trim();
            if (!name.equals("did") && !name.equals("didv") && !name.equals("kuaishou.web")) {
                continue;
            }
            String cur = cookies.toString();
            int at = cur.indexOf(name + "=");
            if (at >= 0) {
                int e = cur.indexOf(';', at);
                if (e < 0) {
                    e = cur.length();
                }
                cookies.replace(at, e, nv);
            } else {
                if (cur.length() > 0) {
                    cookies.append("; ");
                }
                cookies.append(nv);
            }
        }
    }
}
