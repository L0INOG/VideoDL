package com.videodl.app.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 【网络】各平台解析引擎（抖音/B站/快手/小红书）共用的 HTTP 内核。
 *
 * 功能: 统一"手动重定向跟随 / 响应排空 / gzip 响应体读取 / 首字节判死"这四件事，
 *       此前散落在各 Parser 里各写一份，行为容易漂移。
 * 依赖: 仅 java.net，无 android 引用，可随 parser 包在桌面 JVM 上自测。
 * 约定: 所有网络请求都不允许 HttpURLConnection 自动跟随重定向
 *       （跨协议 https→http 的 302 系统不跟随，且 B站/快手 CDN 需要 302 时换 Referer 域名），
 *       因此这里统一 setInstanceFollowRedirects(false) 后手动处理。
 */
public final class Http {

    /** 连接建立超时 */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 常规读超时（页面 / JSON 接口请求） */
    public static final int READ_TIMEOUT_MS = 20_000;
    /**
     * 下载流"首字节判死"窗口：10 秒内读不到首个数据块即视为该线路死亡，
     * 由调用方切换备用线路；数据开始流动后调用方可放宽读超时。
     */
    public static final int FIRST_BYTE_TIMEOUT_MS = 10_000;
    /** 手动重定向的最大跳数 */
    public static final int MAX_REDIRECTS = 6;

    /** 每一跳重定向落地前的校验钩子（域名白名单等）；返回 false 抛 IOException */
    public interface HopValidator {
        boolean allow(String nextUrl);
    }

    private Http() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络] 连接打开与重定向
    // ═══════════════════════════════════════════════════════════════

    /** 打开连接并设置请求头（尚未发起请求），需要自行控制请求时机时使用。 */
    public static HttpURLConnection open(String url, Map<String, String> headers,
                                         int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return conn;
    }

    /**
     * 打开连接并手动跟随 3xx 重定向，直到拿到非重定向响应。
     * 不对 4xx/5xx 抛异常——把连接原样交给调用方决定如何处理
     * （页面抓取要读错误体，下载流才需要按状态码判死，见 followForDownload）。
     */
    public static HttpURLConnection follow(String url, Map<String, String> headers,
                                           int connectMs, int readMs, HopValidator validator)
            throws IOException {
        String current = url;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection conn = open(current, headers, connectMs, readMs);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                drain(conn);
                if (loc != null) {
                    String next = new URL(new URL(current), loc).toString();
                    if (validator != null && !validator.allow(next)) {
                        throw new IOException("重定向到非白名单域名，已拒绝");
                    }
                    current = next;
                    continue;
                }
                // 3xx 但没有 Location：当作普通响应交回调用方
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    /**
     * 下载流专用：跟随重定向并要求最终状态码 < 400，否则排空响应体并抛 IOException。
     * 读超时固定用首字节判死窗口（FIRST_BYTE_TIMEOUT_MS）。
     *
     * @param errorPrefix 抛错时的前缀文案，如 "B站下载地址返回"，拼上 " HTTP xxx"
     */
    public static HttpURLConnection followForDownload(String url, Map<String, String> headers,
                                                      String errorPrefix, HopValidator validator)
            throws IOException {
        HttpURLConnection conn = follow(url, headers, CONNECT_TIMEOUT_MS,
                FIRST_BYTE_TIMEOUT_MS, validator);
        int code = conn.getResponseCode();
        if (code >= 400) {
            drain(conn);
            throw new IOException(errorPrefix + " HTTP " + code);
        }
        return conn;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络] 响应读取
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET 文本：跟随重定向后读取响应体。
     * 4xx/5xx 不抛异常，读取错误体返回（页面抓取方按内容判断风控/失效）。
     */
    public static String getText(String url, Map<String, String> headers,
                                 int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = follow(url, headers, connectMs, readMs, null);
        try {
            return readBody(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** 读取完整响应体并转 UTF-8 文本；自动 gzip 解压（按魔数识别）；流为空返回 ""。 */
    public static String readBody(HttpURLConnection conn) throws IOException {
        return new String(readBodyBytes(conn), StandardCharsets.UTF_8);
    }

    /** 读取完整响应体字节数组；自动 gzip 解压（按魔数识别）。 */
    public static byte[] readBodyBytes(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return new byte[0];
        }
        byte[] data = readAllBytes(in);
        return maybeGunzip(data);
    }

    /** 读尽整个输入流并关闭。 */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    /** gzip 魔数（1f 8b）开头才尝试解压；解不开按原始字节返回。 */
    private static byte[] maybeGunzip(byte[] data) {
        if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try {
                GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
                return readAllBytes(gz);
            } catch (Exception ignored) {
                // 解不开就按原始字节处理（可能只是巧合前缀）
            }
        }
        return data;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [网络] 工具
    // ═══════════════════════════════════════════════════════════════

    /**
     * 读掉并关闭剩余响应体，让底层连接得以复用/安全断开。
     * 在"不使用响应体"的分支（重定向、错误状态）必须调用，否则连接池会被占满。
     */
    public static void drain(HttpURLConnection conn) {
        try {
            InputStream in = conn.getErrorStream();
            if (in == null && conn.getResponseCode() < 400) {
                in = conn.getInputStream();
            }
            if (in != null) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {
                    // 排空
                }
                in.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** 便捷构造请求头：参数为 key1, value1, key2, value2 …；null 或空值自动跳过。 */
    public static Map<String, String> headerMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null && !kv[i + 1].isEmpty()) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
