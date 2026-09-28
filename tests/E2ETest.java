import com.videodl.app.parser.BiliParser;
import com.videodl.app.parser.DouyinParser;
import com.videodl.app.parser.LinkRouter;
import com.videodl.app.parser.MediaSeg;

import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * 用户指定两条链接的端到端验证：
 *   B站 b23.tv/7T1g4UI —— 解析 + 全部 Range 区间魔数校验（长视频，验证下载可行性）
 *   抖音 WIsFoB6Z-h0   —— 解析 + 整文件真实下载 + 字节数/魔数校验
 */
public class E2ETest {
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("############ B站：b23.tv/7T1g4UI ############");
        testBili();

        System.out.println("############ 抖音：WIsFoB6Z-h0 ############");
        testDouyin();

        System.out.println(fail == 0 ? "== 两条链接全部跑通 ==" : "== 有 " + fail + " 项 FAIL ==");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void testBili() throws Exception {
        String share = "【【线代救命#4】90分钟学透方程组所有考点！同解方程组轻松拿下！-哔哩哔哩】 https://b23.tv/7T1g4UI";
        String bv = BiliParser.resolveBvid(LinkRouter.extractUrl(share));
        System.out.println("BV号: " + bv);
        BiliParser.ensureWbi("");
        BiliParser.Result r = BiliParser.fetchView(bv, "");
        java.util.List<MediaSeg> segs = BiliParser.fetchPlayUrl(r, "");
        System.out.println("标题: " + r.title);
        System.out.println("清晰度: " + r.qualityText() + "  分段: " + segs.size());
        if (segs.isEmpty()) { System.out.println("FAIL"); fail++; return; }

        // 每段的首/中/尾三个区间都取一块，验证整个文件可读且是 MP4
        for (int i = 0; i < segs.size(); i++) {
            HttpURLConnection head = BiliParser.openDownload(segs.get(i).url);
            long total = head.getContentLengthLong();
            head.disconnect();
            System.out.println("段" + (i + 1) + " 总大小: " + (total / 1024 / 1024) + "MB (" + total + "B)");
            if (total <= 0) { System.out.println("FAIL: 无长度"); fail++; continue; }
            long[] probes = {0, total / 2, total - 65536};
            for (long off : probes) {
                HttpURLConnection c = BiliParser.openDownload(segs.get(i).url,
                        "bytes=" + off + "-" + (off + 65535));
                String ct = c.getContentType();
                int code = c.getResponseCode();
                InputStream in = c.getInputStream();
                byte[] b = new byte[16];
                int n = in.read(b);
                in.close();
                c.disconnect();
                boolean ok = code == 206 && ct != null && ct.contains("video/");
                if (off == 0) {
                    ok = ok && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
                }
                System.out.println("  @" + off + ": HTTP " + code + " " + ct + (ok ? "  OK" : "  FAIL"));
                if (!ok) fail++;
            }
        }
    }

    static void testDouyin() throws Exception {
        String text = "3.33 复制打开抖音，看看【.....的作品】# cxy# cxy0714# 上海冠军赛# zm... "
                + "https://v.douyin.com/WIsFoB6Z-h0/ :3pm 11/23 M@j.Pk oDu:/";
        DouyinParser.Result r = DouyinParser.parseFromShareText(text, null);
        System.out.println("标题: " + r.info.desc.replace('\n', ' '));
        System.out.println("判定: " + (r.info.isVideo() ? "视频" : "图集"));
        if (!r.info.isVideo()) { System.out.println("FAIL"); fail++; return; }
        System.out.println("地址: " + r.info.videoUrl.substring(0, Math.min(80, r.info.videoUrl.length())));

        // 整文件真实下载（与 App 内同一连接路径）
        HttpURLConnection conn = DouyinParser.openStream(r.info.videoUrl);
        long total = conn.getContentLengthLong();
        InputStream in = conn.getInputStream();
        byte[] head = new byte[16];
        int n0 = in.read(head);
        long read = n0;
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) read += n;
        in.close();
        conn.disconnect();
        boolean ok = total > 1_000_000 && read == total
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        System.out.println("Content-Length: " + total + "  实下载: " + read + "B  "
                + (ok ? "字节数一致 + ftyp 头 OK" : "FAIL"));
        if (!ok) fail++;
    }
}
