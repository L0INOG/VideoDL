import com.videodl.app.parser.DouyinParser;

import java.net.HttpURLConnection;
import java.io.InputStream;

/**
 * 抖音链接类型覆盖测试：动图图文 / 多图图文 / 普通视频（回归）。
 * 验证图文作品不被误判为视频（play_addr 是背景音乐 MP3）、
 * 图片走无水印的展示版地址。
 */
public class GalleryTest {
    public static void main(String[] args) throws Exception {
        String[] texts = {
            "1.07 复制打开抖音，看看【小特zhen费电（锁车音分享）的图文作品】独立思考，明辨是非 # 苹果手机特斯拉  https://v.douyin.com/VoMLGBLqa2g/ gbn:/ :5pm 05/29 n@Q.kc",
            "5.35 复制打开抖音，看看【迟遇的图文作品】倘若遇到那个人 那就永远不要分开。# 文案 # 日... https://v.douyin.com/afNvIc9w6fg/ :8pm 12/08 AGv:/ E@U.lp",
            "3.33 复制打开抖音，看看【.....的作品】# cxy# cxy0714# 上海冠军赛# zm... https://v.douyin.com/WIsFoB6Z-h0/ :3pm 11/23 M@j.Pk oDu:/"
        };
        String[] expect = {"images", "images", "video"};
        int fail = 0;
        for (int t = 0; t < texts.length; t++) {
            System.out.println("=========== 用例" + (t + 1) + " ===========");
            DouyinParser.Result r = DouyinParser.parseFromShareText(texts[t], null);
            DouyinParser.AwemeInfo info = r.info;
            System.out.println("标题: " + info.desc.replace('\n', ' '));
            System.out.println("作者: " + info.author);
            System.out.println("判定: " + (info.isVideo() ? "视频" : "图集(" + info.images.size() + "张)"));
            boolean ok;
            if (info.isVideo()) {
                System.out.println("视频地址: " + info.videoUrl.substring(0, Math.min(90, info.videoUrl.length())));
                ok = expect[t].equals("video") && info.videoUrl.contains("ratio=default");
            } else {
                ok = expect[t].equals("images") && !info.images.isEmpty();
                if (ok) {
                    // 无水印校验：地址不应含 "-water" 模板
                    if (info.images.get(0).contains("-water")) {
                        System.out.println("!! 图片地址带 -water 水印模板");
                        ok = false;
                    }
                    HttpURLConnection c = DouyinParser.openStream(info.images.get(0));
                    String ct = c.getContentType();
                    long len = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    byte[] head = new byte[12];
                    int n = in.read(head);
                    in.close();
                    c.disconnect();
                    System.out.println("图片探测: " + ct + "  " + len + "B");
                    ok = ok && ct != null && ct.startsWith("image/") && len > 1000;
                }
            }
            System.out.println(ok ? "PASS" : "FAIL");
            if (!ok) fail++;
        }
        System.out.println(fail == 0 ? "== 全部 PASS ==" : "== " + fail + " 项 FAIL ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
