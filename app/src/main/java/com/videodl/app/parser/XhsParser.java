package com.videodl.app.parser;

import com.videodl.app.net.Http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【解析引擎 · 小红书】纯本地 HTTP 解析（移植自 xiaohongshu-parser 参考实现），
 * 不经过任何中间服务器。
 *
 * 链路:
 *   1. 分享文本里提取短链 xhslink.cn/xxx（也可能是完整 discovery/explore 链接）
 *   2. GET 短链，手动跟随 302 → 最终形如
 *        https://www.xiaohongshu.com/discovery/item/{noteId}?xsec_token=XXX
 *      注意: 必须走 GET，服务端对 HEAD 直接回 404
 *   3. GET 最终地址 → SSR 页面，数据全在 window.__INITIAL_STATE__ 里
 *   4. 视频笔记: noteData.data.noteData.video.mediaV2 是一段「嵌套的 JSON 字符串」，
 *      二次解析后 stream.h264[] / h265[] / av1[] 每档含 master_url
 *   5. 图文笔记: imageList[] 里按 imageScene=H5_DTL 取 1080 jpg
 *   6. 下载 master_url 存相册（http 统一升级 https）
 *
 * 两个坑（沿用参考实现的对策）:
 *   - INITIAL_STATE 是 JS 字面量，混着 undefined，先清洗成 null 再用 MiniJson 容错解析
 *   - master_url 默认是 http://，CDN 同时支持 https，下载前统一升级
 *
 * 依赖: java.net + MiniJson，无 android 引用，可桌面自测（tests/XhsTest）。
 * 扩展点: INITIAL_STATE 结构变化时优先改 parseHtml()/toImage()/toRep()。
 */
public final class XhsParser {

    /** 与参考实现一致的 iOS Safari UA（短链服务与 SSR 页对此最友好） */
    public static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    /** 小红书自有域名白名单 */
    private static final String[] ALLOWED_HOSTS = {
            "xiaohongshu.com", "xiaohongshu.cn", "xhslink.com", "xhslink.cn",
            "xhscdn.com", "xhs.link", "redbook.com"
    };

    private static final Pattern RE_LINK = Pattern.compile(
            "(https?://)?((?:www\\.)?(?:xhslink\\.cn|xhslink\\.com|xhs\\.link)/[A-Za-z0-9_\\-/]+"
                    + "|www\\.xiaohongshu\\.com/(?:discovery/item|explore)/[0-9a-zA-Z]+"
                    + "(?:\\?[^\\s\"'<>）)】]*)?)");

    /** 最终地址里的 noteId */
    private static final Pattern RE_NOTE_ID = Pattern.compile(
            "/(?:discovery/item|explore|discovery)/([0-9a-zA-Z]{16,32})");

    /** 兜底：页面跳转 / meta refresh 里捞最终地址 */
    private static final Pattern RE_FINAL_URL = Pattern.compile(
            "https?://www\\.xiaohongshu\\.com/(?:discovery/item|explore)/[0-9a-zA-Z]+[^\\s\"'<>]*");

    private static final Pattern RE_MASTER = Pattern.compile(
            "master_url\\\\?\"?\\s*:\\s*\\\\?\"(https?://[^\"\\\\]+)");

    // ---------------------------------------------------------------- 数据模型

    /** 一档视频清晰度 */
    public static final class Rep {
        public String url;
        public String backup;
        public String codec;      // h264 / h265 / av1
        public int width;
        public int height;
        public long size;
        public int bitrate;
        public long durationMs;
        public String quality;    // HD / SD / LD

        public String pick() {
            return (url != null && !url.isEmpty()) ? url : backup;
        }

        public String label() {
            StringBuilder sb = new StringBuilder();
            int h = (width > 0 && height > 0) ? Math.min(width, height) : 0;
            if (h >= 1080) sb.append("1080P");
            else if (h >= 720) sb.append("720P");
            else if (h >= 480) sb.append("480P");
            else if (h > 0) sb.append(h).append("P");
            else sb.append("默认");
            if (quality != null && !quality.isEmpty()) sb.append(" ").append(quality);
            if (codec != null && !codec.equalsIgnoreCase("h264")) sb.append(" ").append(codec.toUpperCase());
            return sb.toString();
        }
    }

    /** 一张图（H5_DTL 1080 jpg，已挑好） */
    public static final class Image {
        public String url;
        public int width;
        public int height;
        public boolean livePhoto;     // 实况照片
        public String livePhotoUrl;   // 实况照片配套的小视频（可能为空；暂不下载，留作扩展）

        public String res() {
            return (width > 0 && height > 0) ? width + "x" + height : "";
        }
    }

    public static final class Result {
        public String noteId;
        public String title;      // 笔记标题（经常是空的）
        public String desc;       // 正文
        public String author;     // nickName
        public String cover;
        public long durationMs;
        public final List<Rep> reps = new ArrayList<>();
        public final List<Image> images = new ArrayList<>();

        public boolean isVideo() {
            return !reps.isEmpty();
        }

        public boolean isImageSet() {
            return !images.isEmpty();
        }

        public String displayTitle() {
            if (title != null && title.trim().length() > 0) return title.trim();
            if (desc != null && desc.trim().length() > 0) return desc.trim();
            return "(无标题)";
        }

        /** 去掉 #话题# / [话题] 之后的版本，用来当文件名 */
        public String fileTitle() {
            String t = displayTitle();
            t = t.replaceAll("#[^#]{0,40}\\[话题\\]#?", "");
            t = t.replaceAll("#[^#]{0,40}#", "");
            t = t.replaceAll("[\\\\/:*?\"<>|\\s]+", " ").trim();
            if (t.length() > 24) t = t.substring(0, 24);
            return t.isEmpty() ? "xiaohongshu" : t;
        }

        /** 默认档：优先 H.264 里分辨率最高的，兼容性最好；没有 h264 再取最高的 */
        public Rep best() {
            Rep bestAvc = null, bestAny = null;
            for (Rep r : reps) {
                if (r == null || r.pick() == null) continue;
                if (bestAny == null || r.height > bestAny.height) bestAny = r;
                if (r.codec == null || r.codec.equalsIgnoreCase("h264") || r.codec.equalsIgnoreCase("avc")) {
                    if (bestAvc == null || r.height > bestAvc.height) bestAvc = r;
                }
            }
            return bestAvc != null ? bestAvc : bestAny;
        }

        /** 时长展示文本，如 "1:05"；无时长返回空串。 */
        public String fmtDuration() {
            long sec = durationMs / 1000;
            if (sec <= 0) return "";
            long m = sec / 60, s = sec % 60;
            return m + ":" + (s < 10 ? "0" : "") + s;
        }
    }

    private XhsParser() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·小红书] 分享文本 → 链接 → 笔记地址
    // ═══════════════════════════════════════════════════════════════

    /** 从任意分享文案中提取小红书链接，找不到返回 null。 */
    public static String extractLink(String text) {
        if (text == null) return null;
        Matcher m = RE_LINK.matcher(text);
        if (!m.find()) return null;
        String link = m.group(0);
        if (!link.startsWith("http")) link = "https://" + link;
        return link;
    }

    /**
     * 跟随跳转到最终的笔记页地址（带 xsec_token）。
     * cookies 为输出参数，沿途收下服务端下发的 cookie；返回 null 表示失败。
     */
    public static String resolveNote(String link, StringBuilder cookies) {
        if (link == null) return null;
        String cur = link;
        for (int hop = 0; hop < Http.MAX_REDIRECTS; hop++) {
            if (cur == null) return null;
            if (cur.contains("xiaohongshu.com/") && RE_NOTE_ID.matcher(cur).find()) {
                return cur;   // 已经拿到带 token 的笔记地址
            }
            try {
                HttpURLConnection c = Http.open(cur, headerMap(cookies == null ? null : cookies.toString()),
                        Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
                int code = c.getResponseCode();
                grabCookies(c, cookies);
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    Http.drain(c);
                    if (loc == null) return null;
                    cur = loc.startsWith("/") ? "https://www.xiaohongshu.com" + loc : loc;
                    continue;
                }
                // 有些短链用页面跳转，不是 3xx
                if (code == 200 && !cur.contains("xiaohongshu.com/")) {
                    String body = Http.readBody(c);
                    if (body != null && !body.isEmpty()) {
                        Matcher m = RE_FINAL_URL.matcher(body);
                        if (m.find()) return m.group(0);
                    }
                }
                Http.drain(c);
                return code == 200 ? cur : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String pickNoteId(String url) {
        if (url == null) return null;
        Matcher m = RE_NOTE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·小红书] 抓页面并解析
    // ═══════════════════════════════════════════════════════════════

    /** 抓 SSR 页面并解析，失败抛 IOException。 */
    public static Result fetch(String noteUrl, String cookies) throws IOException {
        HttpURLConnection c = Http.follow(noteUrl, headerMap(cookies),
                Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS, null);
        try {
            int code = c.getResponseCode();
            if (code == 403) {
                throw new IOException("小红书拒绝了本次请求(403)，可能触发了风控，换个网络再试");
            }
            String html = Http.readBody(c);
            if (html == null || html.isEmpty()) {
                throw new IOException("页面为空");
            }
            if (html.contains("error_code=300031") || html.contains("当前笔记暂时无法浏览")) {
                throw new IOException("笔记暂时无法浏览：链接可能已过期，请重新复制分享链接");
            }
            Result r = parseHtml(html);
            if (r == null || (r.reps.isEmpty() && r.images.isEmpty())) {
                throw new IOException("页面里没找到视频和图片（笔记可能已删除，或链接已过期）");
            }
            if (r.noteId == null) {
                r.noteId = pickNoteId(noteUrl);
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** 解析 SSR HTML 里的 window.__INITIAL_STATE__。 */
    public static Result parseHtml(String html) {
        if (html == null) return null;
        int i = html.indexOf("window.__INITIAL_STATE__");
        if (i < 0) return null;
        int eq = html.indexOf('=', i);
        if (eq < 0) return null;
        int end = html.indexOf("</script>", eq);
        String raw = html.substring(eq + 1, end < 0 ? html.length() : end).trim();
        if (raw.endsWith(";")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        if (!raw.startsWith("{")) return null;

        // JS 字面量 -> JSON：undefined 不是合法 JSON 值
        String json = raw.replaceAll(":\\s*undefined", ":null");
        MiniJson root = MiniJson.parse(json);
        if (root == null) return null;

        Result r = new Result();
        // 笔记真正的数据在 noteData.data.noteData，直接 deepFind 会撞上外层容器
        MiniJson note = null;
        MiniJson ndWrap = root.get("noteData");
        if (ndWrap != null) {
            MiniJson d = ndWrap.get("data");
            if (d != null) note = d.get("noteData");
        }
        if (note == null) note = deepFind(root, "noteData");
        if (note != null) {
            r.title = note.asString("title", "");
            MiniJson d = note.get("desc");
            if (d != null && !d.isObject() && !d.isArray()) r.desc = d.asString();
            MiniJson user = note.get("user");
            if (user != null) {
                r.author = user.asString("nickName", "");
                if (r.author.isEmpty()) r.author = user.asString("nickname", "");
            }
            r.noteId = note.asString("noteId", null);
            MiniJson cover = note.get("cover");
            if (cover != null) r.cover = cover.asString("urlDefault", null);
        }

        // 图文：imageList[]
        MiniJson il = note == null ? null : note.get("imageList");
        if (il != null && il.isArray()) {
            for (int k = 0; k < il.size(); k++) {
                Image img = toImage(il.at(k));
                if (img != null) r.images.add(img);
            }
        }
        if (r.images.isEmpty() && note != null) {
            // 结构变了就深挖一层
            MiniJson deep = deepFind(root, "imageList");
            if (deep != null && deep.isArray()) {
                for (int k = 0; k < deep.size(); k++) {
                    Image img = toImage(deep.at(k));
                    if (img != null) r.images.add(img);
                }
            }
        }

        // 视频：video.mediaV2 是「嵌套的 JSON 字符串」
        MiniJson mediaV2 = deepFind(root, "mediaV2");
        if (mediaV2 != null) {
            String inner = mediaV2.asString();
            MiniJson mv = inner == null ? null : MiniJson.parse(inner);
            MiniJson stream = mv == null ? null : deepFind(mv, "stream");
            if (stream != null) {
                Map<String, MiniJson> obj = stream.asObject();
                if (obj != null) {
                    for (Map.Entry<String, MiniJson> e : obj.entrySet()) {
                        String codec = e.getKey();
                        MiniJson arr = e.getValue();
                        if (arr == null || !arr.isArray()) continue;
                        for (int k = 0; k < arr.size(); k++) {
                            Rep rep = toRep(arr.at(k), codec);
                            if (rep != null) r.reps.add(rep);
                        }
                    }
                }
            }
            MiniJson v = mv == null ? null : deepFind(mv, "video");
            if (v != null && r.durationMs <= 0) r.durationMs = v.asInt("duration", 0) * 1000L;
        }

        // 兜底：JSON 路径没走通就正则硬捞
        if (r.reps.isEmpty()) {
            Matcher m = RE_MASTER.matcher(html);
            while (m.find()) {
                String u = m.group(1).replace("\\u002F", "/").replace("\\/", "/");
                if (!isAllowed(u)) continue;
                Rep rep = new Rep();
                rep.url = u;
                r.reps.add(rep);
            }
        }
        if (r.durationMs <= 0 && !r.reps.isEmpty()) r.durationMs = r.reps.get(0).durationMs;
        return r;
    }

    /**
     * 图片地址挑选。
     * 实测：SSR 只给 H5_DTL(1080 jpg) 和 H5_PRV(预览) 两种，
     * 去掉 !h5_1080jpg 后缀去拿原图会被 CDN 403（要签名），所以直接用 H5_DTL。
     */
    private static Image toImage(MiniJson im) {
        if (im == null) return null;
        String url = null;
        MiniJson info = im.get("infoList");
        if (info != null && info.isArray()) {
            String fallback = null;
            for (int i = 0; i < info.size(); i++) {
                MiniJson n = info.at(i);
                if (n == null) continue;
                String scene = n.asString("imageScene", "");
                String u = n.asString("url", null);
                if (u == null || !isAllowed(u)) continue;
                if ("H5_DTL".equals(scene)) {
                    url = u;
                    break;
                }
                if (fallback == null && !scene.contains("PRV")) fallback = u;
            }
            if (url == null) url = fallback;
        }
        if (url == null) {
            String u = im.asString("url", null);
            if (u != null && isAllowed(u)) url = u;
        }
        if (url == null) return null;

        Image img = new Image();
        img.url = url;
        img.width = im.asInt("width", 0);
        img.height = im.asInt("height", 0);
        img.livePhoto = "true".equalsIgnoreCase(im.asString("livePhoto", "false"));
        MiniJson st = im.get("stream");
        MiniJson h264 = st == null ? null : st.get("h264");
        if (h264 != null && h264.isArray() && h264.size() > 0) {
            String mu = h264.at(0).asString("masterUrl", null);
            if (mu != null && isAllowed(mu)) img.livePhotoUrl = mu;
        }
        return img;
    }

    private static Rep toRep(MiniJson n, String codec) {
        if (n == null) return null;
        String url = n.asString("master_url", null);
        List<String> bu = n.asStringList("backup_urls");
        String backup = bu.isEmpty() ? null : bu.get(0);
        String pick = (url != null && !url.isEmpty()) ? url : backup;
        if (pick == null || !isAllowed(pick)) return null;
        Rep rep = new Rep();
        rep.url = url;
        rep.backup = backup;
        rep.codec = codec != null ? codec : n.asString("video_codec", null);
        rep.width = n.asInt("width", 0);
        rep.height = n.asInt("height", 0);
        rep.size = n.asInt("size", 0);
        rep.bitrate = n.asInt("avg_bitrate", 0);
        rep.durationMs = n.asInt("duration", 0);
        rep.quality = n.asString("quality_type", null);
        return rep;
    }

    /** 递归按 key 深度查找（INITIAL_STATE 结构多变，沿参考实现的兼容思路）。 */
    public static MiniJson deepFind(MiniJson node, String key) {
        if (node == null) return null;
        Map<String, MiniJson> obj = node.asObject();
        if (obj != null) {
            MiniJson hit = obj.get(key);
            if (hit != null) return hit;
            for (MiniJson v : obj.values()) {
                MiniJson r = deepFind(v, key);
                if (r != null) return r;
            }
            return null;
        }
        List<MiniJson> arr = node.asArray();
        if (arr != null) {
            for (MiniJson v : arr) {
                MiniJson r = deepFind(v, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络·小红书] 请求构造与 cookie
    // ═══════════════════════════════════════════════════════════════

    /** SSR 页请求头：浏览器式 Accept + 沿途收下的 cookie */
    private static Map<String, String> headerMap(String cookies) {
        return Http.headerMap(
                "User-Agent", UA,
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language", "zh-CN,zh;q=0.9",
                "Cookie", cookies);
    }

    /**
     * 小红书 CDN 下载连接：带 Referer + 域名白名单校验（含每一跳重定向），
     * master_url 是 http 时统一升级 https。
     * 读超时为首字节判死窗口，首个数据块到达后由 pump 放宽。
     */
    public static HttpURLConnection openDownload(String url) throws IOException {
        String u = url;
        if (u != null && u.startsWith("http://")) u = "https://" + u.substring(7);
        if (!isAllowed(u)) {
            throw new IOException("非小红书地址，已拒绝");
        }
        return Http.followForDownload(u, Http.headerMap(
                "User-Agent", UA,
                "Referer", "https://www.xiaohongshu.com/",
                "Accept", "*/*"), "小红书下载地址返回", new Http.HopValidator() {
            @Override
            public boolean allow(String nextUrl) {
                return isAllowed(nextUrl);
            }
        });
    }

    /** 域名白名单校验（主域名精确匹配或其子域）。 */
    public static boolean isAllowed(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase().replace("\\u002f", "/");
        int i = u.indexOf("://");
        if (i < 0) return false;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        int start = i + 3;
        int slash = u.indexOf('/', start);
        String host = (slash < 0 ? u.substring(start) : u.substring(start, slash)).toLowerCase();
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        for (String h : ALLOWED_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    /** 把响应里 Set-Cookie 合并进 cookie 串（短链服务会沿途下发 webId/a1 等）。 */
    private static void grabCookies(HttpURLConnection c, StringBuilder cookies) {
        if (cookies == null) return;
        Map<String, List<String>> hdr = c.getHeaderFields();
        if (hdr == null) return;
        List<String> sc = hdr.get("Set-Cookie");
        if (sc == null) return;
        for (String one : sc) {
            String nv = one.split(";")[0];
            int p = nv.indexOf('=');
            if (p <= 0) continue;
            String name = nv.substring(0, p).trim();
            String cur = cookies.toString();
            int at = cur.indexOf(name + "=");
            if (at >= 0) {
                int end = cur.indexOf(';', at);
                if (end < 0) end = cur.length();
                cookies.replace(at, end, nv);
            } else {
                if (cur.length() > 0) cookies.append("; ");
                cookies.append(nv);
            }
        }
    }
}
