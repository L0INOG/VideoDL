package com.videodl.app.parser;

/**
 * 【解析】媒体文件魔数校验。
 *
 * 功能: 防止把风控 JSON/网页错误页当视频图片存下来；
 *       各平台下载流的首块校验与"已存在文件复用"前都必须过这一关。
 * 扩展点: 支持新媒体类型（如 m4a 音频）时在 valid() 里加魔数分支。
 */
public final class MediaSniff {

    private MediaSniff() {
    }

    /** 按期望的 MIME 校验文件头，不认识的类型放行。 */
    public static boolean valid(String mime, byte[] b, int n) {
        if (b == null || n <= 0) {
            return false;
        }
        if (mime == null) {
            return true;
        }
        if (mime.startsWith("video/mp4")) {
            // MP4：第4-7字节是 "ftyp"
            return n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
        }
        if (mime.startsWith("image/webp")) {
            // WebP：RIFF....WEBP
            return n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        }
        if (mime.startsWith("image/jpeg")) {
            return n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8;
        }
        if (mime.startsWith("image/png")) {
            return n >= 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        }
        if (mime.startsWith("image/")) {
            // 其他图片类型（heic 等）：至少不能是文本/JSON
            return !looksLikeText(b, n);
        }
        // 其他类型不校验
        return true;
    }

    /** JSON/HTML 错误页都以 '{' '<' 或 BOM 开头 */
    private static boolean looksLikeText(byte[] b, int n) {
        int c = b[0] & 0xff;
        return c == '{' || c == '<' || c == '[' || c == 0xEF;
    }
}
