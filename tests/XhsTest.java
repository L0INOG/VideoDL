import com.videodl.app.parser.XhsParser;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * 小红书解析链路验证（纯 JVM，不依赖 Android，与 App 共用同一份 XhsParser）。
 * 视频笔记和图文笔记都能跑，自动识别。
 *
 *   bash tools/run-tests.sh XhsTest                      # 内置示例链接
 *   bash tools/run-tests.sh XhsTest "小红书分享文案"       # 指定链接
 */
public class XhsTest {

    public static void main(String[] args) {
        String share = args.length > 0 ? args[0]
                : "杰伦来周杰了吧！ @周杰伦吧 https://xhslink.cn/o/1UXhVDO23Jt "
                + "先复制再进入【小红书】，笔记内容马上出现。";

        System.out.println("== 1. 从分享文本提取链接 ==");
        String link = XhsParser.extractLink(share);
        System.out.println("   link = " + link);
        if (link == null) {
            System.out.println("   !! 没抠出链接");
            System.exit(1);
        }

        System.out.println("== 2. 短链 -> 笔记地址(带 xsec_token) ==");
        StringBuilder cookies = new StringBuilder();
        String noteUrl = XhsParser.resolveNote(link, cookies);
        System.out.println("   noteId = " + XhsParser.pickNoteId(noteUrl));
        if (noteUrl == null) {
            System.out.println("   !! 跳转失败");
            System.exit(1);
        }

        System.out.println("== 3. 抓 SSR 页面并解析 ==");
        XhsParser.Result r;
        try {
            r = XhsParser.fetch(noteUrl, cookies.toString());
        } catch (Exception e) {
            System.out.println("   !! " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("   标题   : " + r.displayTitle());
        System.out.println("   文件名 : " + r.fileTitle());
        System.out.println("   作者   : " + r.author);

        boolean ok = false;
        if (r.isVideo()) {
            System.out.println("   类型   : 视频, 时长 " + r.durationMs + " ms (" + r.fmtDuration() + ")");
            System.out.println("== 4. 视频档位 ==");
            for (int i = 0; i < r.reps.size(); i++) {
                XhsParser.Rep rep = r.reps.get(i);
                System.out.println("     [" + i + "] " + rep.label() + "  " + rep.width + "x" + rep.height
                        + "  " + (rep.size / 1024) + "KB");
                System.out.println("         " + clip(rep.pick()));
            }
            XhsParser.Rep best = r.best();
            if (best != null) {
                System.out.println("   默认选中: " + best.label() + "  白名单=" + XhsParser.isAllowed(best.pick()));
                ok |= save(best.pick(), "build/xhs_out.mp4");
            }
        }

        if (r.isImageSet()) {
            System.out.println("   类型   : 图文, " + r.images.size() + " 张");
            System.out.println("== 4. 图片列表 ==");
            int n = 0;
            for (int i = 0; i < r.images.size(); i++) {
                XhsParser.Image im = r.images.get(i);
                System.out.println("     [" + i + "] " + im.res()
                        + (im.livePhoto ? "  实况照片" : "") + "  白名单="
                        + XhsParser.isAllowed(im.url));
                System.out.println("         " + clip(im.url));
                if (i < 3 && save(im.url, "build/xhs_img_" + (i + 1) + ".jpg")) n++;
            }
            System.out.println("   已落盘 " + n + " 张 (仅前 3 张做验证)");
            ok |= n > 0;
        }

        if (!r.isVideo() && !r.isImageSet()) {
            System.out.println("   !! 既没视频也没图片");
        }
        System.out.println(ok ? "== 全部 PASS ==" : "== 有下载失败，请检查 ==");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean save(String url, String file) {
        try {
            HttpURLConnection c = XhsParser.openDownload(url);
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(file);
            byte[] tmp = new byte[65536];
            int total = 0, k;
            while ((k = in.read(tmp)) > 0) {
                out.write(tmp, 0, k);
                total += k;
            }
            out.close();
            in.close();
            String type = c.getHeaderField("Content-Type");
            c.disconnect();
            System.out.println("        -> " + file + "  " + total + " 字节, " + type);
            return total > 0;
        } catch (Exception e) {
            System.out.println("        -> !! " + e);
            return false;
        }
    }

    private static String clip(String s) {
        if (s == null) return "null";
        return s.length() <= 100 ? s : s.substring(0, 100) + "...";
    }
}
