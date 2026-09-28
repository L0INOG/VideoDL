package com.videodl.app.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【解析】平台识别与链接提取。
 *
 * 功能: 从任意分享文案中提取第一个 http(s) 链接，并按域名判定所属平台。
 * 链路: MainActivity / DownloadService → LinkRouter.detect → 对应平台 Parser。
 * 扩展点: 新增平台时——① 加一个 PLATFORM_XXX 常量；② detect() 里加域名匹配分支；
 *          ③ 在 DownloadService.parseAny / runDownloadTask 的平台分发处接入新 Parser。
 */
public final class LinkRouter {

    public static final String PLATFORM_DOUYIN = "douyin";
    public static final String PLATFORM_BILIBILI = "bilibili";
    public static final String PLATFORM_KUAISHOU = "kuaishou";
    public static final String PLATFORM_XIAOHONGSHU = "xiaohongshu";

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

    private LinkRouter() {
    }

    /** 从任意文案中提取第一个链接（去掉被误吞的尾部标点），找不到返回 null。 */
    public static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        String u = m.group();
        int end = u.length();
        while (end > 0) {
            char c = u.charAt(end - 1);
            // 只认 ASCII 的合法 URL 结尾字符；中文及全角标点（分享文案常见）一律剥掉
            boolean asciiUrlChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || "/_-=?&%.:#~".indexOf(c) >= 0;
            if (asciiUrlChar) {
                break;
            }
            end--;
        }
        return u.substring(0, end);
    }

    /** 按域名判断平台，未知返回 null。 */
    public static String detect(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase();
        if (u.contains("douyin.com") || u.contains("iesdouyin.com")) {
            return PLATFORM_DOUYIN;
        }
        if (u.contains("b23.tv") || u.contains("bilibili.com") || u.contains("bilibili.tv")) {
            return PLATFORM_BILIBILI;
        }
        if (u.contains("kuaishou.com") || u.contains("chenzhongtech.com")) {
            return PLATFORM_KUAISHOU;
        }
        if (u.contains("xhslink.com") || u.contains("xhslink.cn")
                || u.contains("xhs.link") || u.contains("xiaohongshu.com")) {
            return PLATFORM_XIAOHONGSHU;
        }
        return null;
    }
}
