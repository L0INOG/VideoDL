import com.videodl.app.parser.KsParser;
import com.videodl.app.parser.LinkRouter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;

/**
 * 桌面端自测快手链路（与 App 内运行的是同一份代码）：
 *   平台识别 -> photoId -> SSR 解析 -> 选档 -> 下载探测
 * 用法：java KsSelfTest [分享链接]
 */
public class KsSelfTest {
    public static void main(String[] args) throws Exception {
        String share = args.length > 0 ? args[0]
                : "https://v.kuaishou.com/K3DWvTmX 这就是顶级大佬的松弛感？\"vertu \"威图手机 "
                + "\"松弛感 \"顶级大佬 该作品在快手被播放过834.5万次，点击链接，打开【快手】直接观看！";
        int failures = 0;

        System.out.println("== 0. 平台识别 ==");
        String u0 = LinkRouter.extractUrl(share);
        String p0 = LinkRouter.detect(u0);
        System.out.println("  " + u0 + "  ->  " + p0);
        if (!"kuaishou".equals(p0)) {
            System.out.println("FAIL: 平台识别错误");
            System.exit(1);
        }

        System.out.println("== 1. 短链 -> photoId ==");
        String[] pc = KsParser.resolvePhotoId(u0);
        System.out.println("photoId: " + (pc == null ? "null" : pc[0]));
        System.out.println("cookies: " + (pc == null ? "-" : pc[1]));
        if (pc == null) {
            System.out.println("FAIL: 未拿到 photoId");
            System.exit(1);
        }

        System.out.println("== 2. SSR 解析 ==");
        KsParser.Result r = KsParser.fetch(pc[0], pc[1]);
        System.out.println("标题: " + r.title);
        System.out.println("作者: " + r.author);
        System.out.println("档位数: " + r.reps.size());
        for (KsParser.Rep rep : r.reps) {
            System.out.println("  [" + rep.label() + "] " + rep.width + "x" + rep.height
                    + (rep.backup != null ? " (含备用地址)" : ""));
        }

        System.out.println("== 3. 选档 + 下载探测 ==");
        KsParser.Rep best = r.best();
        System.out.println("选中: " + best.label());
        HttpURLConnection conn = KsParser.openDownload(best.pick());
        conn.setRequestProperty("Range", "bytes=0-65535");
        String type = conn.getContentType();
        InputStream in = conn.getInputStream();
        byte[] head = new byte[12];
        int n = in.read(head);
        in.close();
        conn.disconnect();
        System.out.println("HTTP " + conn.getResponseCode() + "  " + type);
        boolean mp4 = type != null && type.contains("video/")
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        System.out.println(mp4 ? "PASS" : "FAIL: 响应不是 MP4");
        if (!mp4) failures++;

        System.out.println(failures == 0 ? "== 全部 PASS ==" : "== FAIL ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
