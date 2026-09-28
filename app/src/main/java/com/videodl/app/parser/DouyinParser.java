package com.videodl.app.parser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.videodl.app.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【解析引擎 · 抖音】纯本地分享链接解析（参考开源项目
 * Evil0ctal/Douyin_TikTok_Download_API、qgeng1465/douyin-watermark-free-downloader
 * 的思路移植为 Java）。
 *
 * 链路:
 *   1. 从分享文案中提取链接（v.douyin.com 短链或 douyin.com/video/{id} 长链）
 *   2. 跟随重定向拿到 aweme_id
 *   3. 向字节官方公开接口注册一个 ttwid cookie（本机直连，非第三方代理服务）
 *   4. 带 ttwid 请求 www.iesdouyin.com/share/{type}/{id}/ 分享页        —— 线路1
 *   5. 线路1失败 → WebView 预热 cookie（net.Line2Loader）→ 主站 detail 接口 —— 线路2
 *   6. 抠出页面内嵌 window._ROUTER_DATA JSON，定位 item_list[0]
 *   7. play_addr.url_list[0] 中的 playwm 替换为 play 即无水印直链
 *
 * 依赖: 仅 java.net 与 org.json（Android 自带），禁止 import 任何 android.* 类，
 *       可在桌面 JVM 上单独运行自测（tests/ParserSelfTest）。
 * 扩展点: 抖音接口字段变化时优先检查 parse()/parseDetailJson() 的取数逻辑。
 */
public final class DouyinParser {

    /** 线路1 使用的 iOS Safari UA（分享页对移动 UA 返回最完整的内嵌数据） */
    public static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** 线路2 使用的移动端 UA，必须与预热 WebView 的 UA 保持一致（指纹统一） */
    public static final String UA_ANDROID =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String TTWID_REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/";
    private static final String TTWID_BODY =
            "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\","
                    + "\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},"
                    + "\"cbUrlProtocol\":\"https\",\"union\":true}";
    private static final String SHARE_PAGE = "https://www.iesdouyin.com/share/%s/%s/";
    private static final String DETAIL_API = "https://www.douyin.com/aweme/v1/web/aweme/detail/";

    private static final Pattern URL_RE = Pattern.compile(
            "https?://(?:v\\.douyin\\.com/\\S+"
                    + "|(?:www\\.)?iesdouyin\\.com/share/(?:video|slides|note)/\\d+"
                    + "|(?:www\\.|m\\.)?douyin\\.com/(?:video|note|slides)/\\d+"
                    + "|www\\.douyin\\.com/\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_RE = Pattern.compile("/(?:video|slides|note)/(\\d+)");
    private static final Pattern PLAIN_ID_RE = Pattern.compile("www\\.douyin\\.com/(\\d{6,})");
    private static final Pattern ROUTER_RE = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\});?\\s*</script>", Pattern.DOTALL);
    private static final Pattern ROUTER_GREEDY_RE = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*\\})", Pattern.DOTALL);
    private static final Pattern RENDER_RE = Pattern.compile(
            "<script\\s+id=\"RENDER_DATA\"\\s+type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    // ---------------------------------------------------------------- 结果模型

    /** 一次解析得到的作品信息（视频或图集二选一） */
    public static class AwemeInfo {
        public String id = "";
        public String desc = "";
        public String author = "";
        public String videoUrl;         // 主地址：画质最佳（ratio=default 或 bit_rate 最高档）
        public String originalVideoUrl; // 线路1备用：分享页原始地址（720p）
        public String downloadAddrUrl;  // 线路1备用：download_addr（不同下载端点）
        public String coverUrl;
        public final List<String> images = new ArrayList<>();   // 图集图片直链

        public boolean isVideo() {
            return videoUrl != null && !videoUrl.isEmpty();
        }
    }

    public static class Result {
        public AwemeInfo info;
        public String ttwid;   // 本次解析实际使用的 ttwid，调用方可缓存复用
    }

    private DouyinParser() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·抖音] 链接 → 作品 ID
    // ═══════════════════════════════════════════════════════════════

    /** 从任意分享文案中提取抖音链接，找不到返回 null。 */
    public static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_RE.matcher(text);
        if (!m.find()) {
            return null;
        }
        String u = m.group();
        // 分享文案中链接后面常紧跟中文标点，正则的 \S+ 会把它吞进来
        int end = u.length();
        while (end > 0) {
            char c = u.charAt(end - 1);
            if (Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-' || c == '=') {
                break;
            }
            end--;
        }
        return u.substring(0, end);
    }

    /** 解析链接得到 aweme_id 与页面类型（video/note/slides），失败返回 null。 */
    public static String[] resolve(String url) throws IOException {
        Matcher m = ID_RE.matcher(url);
        if (m.find()) {
            return new String[]{m.group(1), pageType(url)};
        }
        m = PLAIN_ID_RE.matcher(url);
        if (m.find()) {
            return new String[]{m.group(1), "video"};
        }
        // 短链：手动跟随重定向（HttpURLConnection 不跟随跨协议重定向）
        String current = url;
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
            m = ID_RE.matcher(finalUrl);
            if (m.find()) {
                return new String[]{m.group(1), pageType(finalUrl)};
            }
            if (code == 200) {
                break;
            }
        }
        return null;
    }

    /** 页面类型由 URL 中的路径段决定，决定分享页模板（video/note/slides）。 */
    private static String pageType(String url) {
        if (url.contains("/note/")) {
            return "note";
        }
        if (url.contains("/slides/")) {
            return "slides";
        }
        return "video";
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·抖音] 线路1 — 分享页解析
    // ═══════════════════════════════════════════════════════════════

    /** 注册 ttwid（字节官方公开 cookie 注册接口），失败返回 null。 */
    public static String fetchTtwid() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(TTWID_REGISTER_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(Http.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(Http.READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", UA);
        OutputStream os = conn.getOutputStream();
        os.write(TTWID_BODY.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        String ttwid = null;
        for (List<String> values : conn.getHeaderFields().values()) {
            if (values == null) {
                continue;
            }
            for (String v : values) {
                if (v != null && v.startsWith("ttwid=")) {
                    String t = v.split(";", 2)[0].substring(6).trim();
                    if (!t.isEmpty()) {
                        ttwid = t;
                    }
                }
            }
        }
        Http.drain(conn);
        return ttwid;
    }

    /** 完整解析：分享文案 -> 作品信息。内部自动注册/重试 ttwid。 */
    public static Result parseFromShareText(String text, String cachedTtwid) throws IOException {
        String url = extractUrl(text);
        if (url == null) {
            throw new IOException("没有在文案里找到抖音链接");
        }
        String[] idType = resolve(url);
        if (idType == null) {
            throw new IOException("无法获取作品 ID，链接可能已失效或被删除");
        }
        return parseSharePage(idType[0], idType[1], cachedTtwid);
    }

    /**
     * 线路1 元数据：按作品 ID 请求分享页解析。ttwid 失效自动换新重试一次。
     */
    public static Result parseSharePage(String awemeId, String pageType, String cachedTtwid) throws IOException {
        String ttwid = cachedTtwid;
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (ttwid == null || ttwid.isEmpty()) {
                    ttwid = fetchTtwid();
                }
                String html = Http.getText(
                        String.format(SHARE_PAGE, pageType, awemeId),
                        Http.headerMap("User-Agent", UA, "Cookie",
                                ttwid == null ? null : "ttwid=" + ttwid),
                        Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
                JSONObject data = extractRouterData(html);
                AwemeInfo info = data == null ? null : parse(data, awemeId);
                if (info != null && (info.isVideo() || !info.images.isEmpty())) {
                    Result r = new Result();
                    r.info = info;
                    r.ttwid = ttwid;
                    return r;
                }
                // 页面无数据：多半是 ttwid 失效/被风控，强制换新 ttwid 再试一次
                ttwid = null;
            } catch (IOException e) {
                lastError = e;
                ttwid = null;
            }
        }
        if (lastError != null) {
            throw new IOException("解析失败：" + lastError.getMessage(), lastError);
        }
        throw new IOException("解析失败：页面未返回作品数据（可能被风控，请稍后重试）");
    }

    /** 从分享页 HTML 中抠出内嵌 JSON（_ROUTER_DATA 优先，RENDER_DATA 兜底）。 */
    public static JSONObject extractRouterData(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = ROUTER_RE.matcher(html);
        if (m.find()) {
            try {
                return new JSONObject(m.group(1));
            } catch (JSONException ignored) {
            }
        }
        m = ROUTER_GREEDY_RE.matcher(html);
        if (m.find()) {
            String s = m.group(1);
            int braceEnd = s.lastIndexOf('}');
            if (braceEnd > 0) {
                try {
                    return new JSONObject(s.substring(0, braceEnd + 1));
                } catch (JSONException ignored) {
                }
            }
        }
        m = RENDER_RE.matcher(html);
        if (m.find()) {
            try {
                return new JSONObject(percentDecode(m.group(1)));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 在 _ROUTER_DATA 深层结构中定位作品对象并抽取字段。 */
    public static AwemeInfo parse(JSONObject data, String id) {
        AwemeInfo info = new AwemeInfo();
        info.id = id == null ? "" : id;
        JSONObject detail = findAweme(data);
        if (detail == null) {
            return info;
        }
        info.desc = detail.optString("desc", "").trim();
        if (info.desc.isEmpty()) {
            JSONObject shareInfo = detail.optJSONObject("share_info");
            if (shareInfo != null) {
                info.desc = shareInfo.optString("share_title", "").trim();
            }
        }
        JSONObject author = detail.optJSONObject("author");
        if (author != null) {
            info.author = firstNonEmpty(author.optString("nickname", ""),
                    author.optString("unique_id", ""), author.optString("short_id", ""));
        }

        // 图文作品（动图/多图）的 video.play_addr 里装的是背景音乐 MP3，
        // 不是视频！只要 images 非空就按图集处理，绝不碰 play_addr。
        JSONArray images = detail.optJSONArray("images");
        boolean hasImages = images != null && images.length() > 0;

        JSONObject video = detail.optJSONObject("video");
        if (!hasImages && video != null) {
            JSONObject play = video.optJSONObject("play_addr");
            if (play == null) {
                play = video.optJSONObject("playAddr");
            }
            String u = firstUrl(play);
            if (u != null) {
                String nw = noWatermark(u);
                info.originalVideoUrl = nw;
                // 分享页默认给 720p；ratio=default 是实测画质最高的一档（画质优先）
                info.videoUrl = nw.replace("ratio=720p", "ratio=default");
            }
            info.coverUrl = firstUrl(video.optJSONObject("cover"));
            // download_addr 走不同下载端点，作为最后兜底
            String du = firstUrl(video.optJSONObject("download_addr"));
            if (du != null) {
                info.downloadAddrUrl = noWatermark(du);
            }
        }

        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject im = images.optJSONObject(i);
                if (im == null) {
                    continue;
                }
                // 必须用展示版 url_list（tplv-dy-lqen-new 模板，无水印）；
                // download_url_list 是 "-water" 模板，会烧录作者抖音号水印
                String u = firstUrl(im);
                if (u != null) {
                    info.images.add(u);
                }
            }
        }
        return info;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·抖音] 线路2 — 主站 detail 接口
    // ═══════════════════════════════════════════════════════════════

    /** 线路2的 detail 接口地址（参数来自 douyin-saver 项目的实测） */
    public static String detailUrl(String awemeId) {
        return DETAIL_API + "?device_platform=webapp&aid=6383&channel=channel_pc_web"
                + "&aweme_id=" + awemeId
                + "&request_source=600&origin_type=video_page"
                + "&update_version_code=170400&pc_client_type=1";
    }

    /**
     * 线路2 元数据：带 WebView 预热的浏览器 cookie 调主站 detail 接口。
     * cookie 串里没有 ttwid 时自动本地注册补上。被 Argus 风控拦截时抛 IOException。
     */
    public static AwemeInfo fetchDetail(String awemeId, String cookies) throws IOException {
        String ck = cookies == null ? "" : cookies.trim();
        if (!ck.contains("ttwid=")) {
            String t = fetchTtwid();
            if (t != null) {
                ck = ck.isEmpty() ? "ttwid=" + t : ck + "; ttwid=" + t;
            }
        }
        String body = Http.getText(detailUrl(awemeId),
                Http.headerMap("User-Agent", UA_ANDROID, "Cookie", ck),
                Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
        if (body.length() < 500) {
            throw new IOException("线路2返回内容异常（长度不足）");
        }
        String lower = body.toLowerCase();
        if (lower.contains("blocked") || lower.contains("argus")) {
            throw new IOException("线路2被风控拦截（Argus）");
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("线路2返回的不是有效 JSON");
        }
        JSONObject aweme = root.optJSONObject("aweme_detail");
        if (aweme == null) {
            JSONArray list = root.optJSONArray("aweme_list");
            if (list != null && list.length() > 0) {
                aweme = list.optJSONObject(0);
            }
        }
        if (aweme == null) {
            throw new IOException("线路2未返回作品数据");
        }
        AwemeInfo info = parseDetailJson(aweme, awemeId);
        if (!info.isVideo() && info.images.isEmpty()) {
            throw new IOException("线路2未返回可下载地址");
        }
        return info;
    }

    /** 解析 detail 接口的 aweme 对象：bit_rate 里挑分辨率最高的一档做主地址。 */
    public static AwemeInfo parseDetailJson(JSONObject aweme, String id) {
        AwemeInfo info = new AwemeInfo();
        info.id = id == null ? "" : id;
        info.desc = aweme.optString("desc", "").trim();
        JSONObject author = aweme.optJSONObject("author");
        if (author != null) {
            info.author = firstNonEmpty(author.optString("nickname", ""),
                    author.optString("unique_id", ""));
        }
        JSONObject video = aweme.optJSONObject("video");
        if (video != null) {
            // bit_rate 多档：选 width*height 最大的（若两档同面积，取先出现的）
            JSONArray br = video.optJSONArray("bit_rate");
            int best = -1;
            String bestUrl = null;
            if (br != null) {
                for (int i = 0; i < br.length(); i++) {
                    JSONObject b = br.optJSONObject(i);
                    if (b == null) {
                        continue;
                    }
                    int area = b.optInt("width", 0) * b.optInt("height", 0);
                    if (area > best) {
                        String u = firstUrl(b.optJSONObject("play_addr"));
                        if (u != null) {
                            best = area;
                            bestUrl = u;
                        }
                    }
                }
            }
            if (bestUrl != null) {
                info.videoUrl = noWatermark(bestUrl);
            }
            String plain = firstUrl(video.optJSONObject("play_addr"));
            if (plain != null) {
                String nw = noWatermark(plain);
                if (info.videoUrl == null) {
                    info.videoUrl = nw;
                } else {
                    info.originalVideoUrl = nw;
                }
            }
            String du = firstUrl(video.optJSONObject("download_addr"));
            if (du != null) {
                info.downloadAddrUrl = noWatermark(du);
            }
            info.coverUrl = firstUrl(video.optJSONObject("cover"));
        }
        JSONArray images = aweme.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                String u = firstUrl(images.optJSONObject(i));
                if (u != null) {
                    info.images.add(u);
                }
            }
        }
        return info;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [下载·抖音] 下载流打开器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 打开一个下载流：手动跟随重定向，带移动端 UA 与抖音 Referer。返回已就绪的连接。
     * 读超时用首字节判死窗口（Http.FIRST_BYTE_TIMEOUT_MS）：
     * 首字节（含响应头）10 秒内不到即视为"完全没开始"，由调用方判死切换线路。
     */
    public static HttpURLConnection openStream(String url) throws IOException {
        return Http.followForDownload(url,
                Http.headerMap("User-Agent", UA, "Referer", "https://www.douyin.com/"),
                "下载地址返回", null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·抖音] JSON 结构工具
    // ═══════════════════════════════════════════════════════════════

    /** 递归找 aweme 对象（兼容 _ROUTER_DATA / item_list 等多种嵌套形态）。 */
    private static JSONObject findAweme(Object root) {
        Object r = walk(root, "aweme");
        if (r instanceof JSONObject) {
            return (JSONObject) r;
        }
        Object items = walk(root, "item_list");
        if (items instanceof JSONArray) {
            JSONArray a = (JSONArray) items;
            if (a.length() > 0) {
                JSONObject first = a.optJSONObject(0);
                if (first != null) {
                    return first;
                }
            }
        }
        return deepFind(root);
    }

    private static Object walk(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject jo = (JSONObject) o;
            if (jo.has(key)) {
                return jo.opt(key);
            }
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                Object r = walk(jo.opt(it.next()), key);
                if (r != null) {
                    return r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object r = walk(a.opt(i), key);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static JSONObject deepFind(Object o) {
        if (o instanceof JSONObject) {
            JSONObject jo = (JSONObject) o;
            if (jo.has("desc") && (jo.has("video") || jo.has("images") || jo.has("aweme_id"))) {
                return jo;
            }
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                Object r = deepFind(jo.opt(it.next()));
                if (r instanceof JSONObject) {
                    return (JSONObject) r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object r = deepFind(a.opt(i));
                if (r instanceof JSONObject) {
                    return (JSONObject) r;
                }
            }
        }
        return null;
    }

    /** 从 url_list / urlList / url 等形态里取第一个 http 开头的地址。 */
    private static String firstUrl(JSONObject node) {
        if (node == null) {
            return null;
        }
        String s = firstUrlFromList(node, "url_list");
        if (s != null) {
            return s;
        }
        s = firstUrlFromList(node, "urlList");
        if (s != null) {
            return s;
        }
        String u = node.optString("url", "");
        return u.startsWith("http") ? u : null;
    }

    /** 从指定名称的地址列表字段里取第一个 http 开头的地址。 */
    private static String firstUrlFromList(JSONObject node, String listKey) {
        if (node == null) {
            return null;
        }
        JSONArray list = node.optJSONArray(listKey);
        if (list != null && list.length() > 0) {
            String s = list.optString(0, "");
            if (s.startsWith("http")) {
                return s;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    /** playwm -> play 得到无水印直链。 */
    public static String noWatermark(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("/playwm/", "/play/").replace("playwm", "play");
    }

    /** 只解码 %XX（不用 URLDecoder，避免把 + 号变成空格）。 */
    private static String percentDecode(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    bos.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            bos.write((byte) c);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
