import com.videodl.app.parser.BiliParser;
import com.videodl.app.parser.LinkRouter;
import com.videodl.app.parser.MediaSeg;

import java.net.HttpURLConnection;
import java.io.InputStream;
import java.util.List;

/**
 * 桌面端自测 B 站链路（与 App 内运行的是同一份代码）：
 *   平台识别 -> BV号 -> wbi -> 详情 -> 播放地址 -> 下载探测（带 Referer）
 * 用法：java BiliSelfTest  （内置两条真实链接：电脑版 + 手机版）
 */
public class BiliSelfTest {
    public static void main(String[] args) throws Exception {
        String[] cases = {
                "【曝Kimi K4提前泄露，ChatGPT Pro Max要卖500美元！| 绿鸭AI日报0925】"
                        + "https://www.bilibili.com/video/BV1schU6REee?vd_source=0d2187ad926b9804e3bb8c28c16008d9 （电脑版分享链接）",
                "【Codex 首款硬件正式发布，1500 块的发光宏键盘-哔哩哔哩】 https://b23.tv/Zxs6CUJ（手机版分享链接）",
                "3.33 复制打开抖音，看看【x的作品】 https://v.douyin.com/WIsFoB6Z-h0/ :3pm" // 反例：抖音链接不应被 B 站处理
        };
        int failures = 0;

        System.out.println("== 0. 平台识别 ==");
        for (String t : cases) {
            String u = LinkRouter.extractUrl(t);
            String p = LinkRouter.detect(u);
            System.out.println("  " + u + "  ->  " + p);
        }
        if (!"bilibili".equals(LinkRouter.detect(LinkRouter.extractUrl(cases[0])))
                || !"bilibili".equals(LinkRouter.detect(LinkRouter.extractUrl(cases[1])))
                || !"douyin".equals(LinkRouter.detect(LinkRouter.extractUrl(cases[2])))) {
            System.out.println("FAIL: 平台识别错误");
            failures++;
        }

        for (int ci = 0; ci < 2; ci++) {
            System.out.println("== 用例" + (ci + 1) + "：B站全链路 ==");
            String url = LinkRouter.extractUrl(cases[ci]);
            String bv = BiliParser.resolveBvid(url);
            System.out.println("BV号: " + bv);
            if (bv == null) {
                System.out.println("FAIL: 解析不到 BV 号");
                failures++;
                continue;
            }
            if (!BiliParser.ensureWbi("")) {
                System.out.println("FAIL: wbi 密钥获取失败");
                failures++;
                continue;
            }
            BiliParser.Result r = BiliParser.fetchView(bv, "");
            System.out.println("标题: " + r.title);
            System.out.println("UP主: " + r.up + "  cid: " + r.cid + "  分P: " + r.pageCids.size());
            List<MediaSeg> segs = BiliParser.fetchPlayUrl(r, "");
            System.out.println("清晰度: " + r.qualityText() + "  分段: " + segs.size());
            if (segs.isEmpty()) {
                System.out.println("FAIL: 无播放地址");
                failures++;
                continue;
            }
            HttpURLConnection conn = BiliParser.openDownload(segs.get(0).url);
            conn.setRequestProperty("Range", "bytes=0-65535");
            long len = conn.getContentLengthLong();
            String type = conn.getContentType();
            InputStream in = conn.getInputStream();
            byte[] head = new byte[12];
            int n = in.read(head);
            in.close();
            conn.disconnect();
            System.out.println("下载探测: HTTP " + conn.getResponseCode() + "  " + type
                    + "  总长" + (len < 0 ? -1 : len) + "B  头 " + String.format("%02x%02x%02x%02x",
                    head[0], head[1], head[2], head[3]));
            boolean ok = type != null && type.contains("video/")
                    && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
            System.out.println(ok ? "PASS" : "FAIL");
            if (!ok) failures++;
        }

        System.out.println(failures == 0 ? "== 全部 PASS ==" : "== 有 " + failures + " 项 FAIL ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
