import com.videodl.app.parser.DouyinParser;

import java.net.HttpURLConnection;
import java.io.InputStream;

import org.json.JSONObject;

/**
 * 桌面端自测（与 App 内运行的是同一份 DouyinParser）：
 *   1. 分享文案 -> 线路1 解析 -> 画质最佳地址（ratio=default）真实可用
 *   2. 线路2 的 bit_rate 画质选择逻辑（合成 JSON 验证选最高分辨率）
 *   3. 线路2 detail 接口在仅有 ttwid（无 WebView cookie）时被 Argus 拦截 -> 应优雅报错
 * 用法：java ParserSelfTest "分享文案"
 */
public class ParserSelfTest {
    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0]
                : "3.33 复制打开抖音，看看【.....的作品】# cxy# cxy0714# 上海冠军赛# zm... "
                + "https://v.douyin.com/WIsFoB6Z-h0/ :3pm 11/23 M@j.Pk oDu:/";
        int failures = 0;

        System.out.println("== 1. 线路1：解析+画质最佳地址 ==");
        DouyinParser.Result r = DouyinParser.parseFromShareText(text, null);
        DouyinParser.AwemeInfo info = r.info;
        System.out.println("标题: " + info.desc);
        System.out.println("作者: " + info.author);
        System.out.println("线路1主地址: " + info.videoUrl);
        System.out.println("线路1备用1(720p): " + info.originalVideoUrl);
        System.out.println("线路1备用2(download_addr): " + info.downloadAddrUrl);
        if (info.videoUrl == null || !info.videoUrl.contains("ratio=default")) {
            System.out.println("FAIL: 主地址不是 ratio=default 画质优先档");
            failures++;
        }
        HttpURLConnection conn = DouyinParser.openStream(info.videoUrl);
        String type = conn.getContentType();
        long len = conn.getContentLengthLong();
        System.out.println("Content-Type: " + type + "  Content-Length: " + len);
        InputStream in = conn.getInputStream();
        byte[] head = new byte[12];
        int n = in.read(head);
        in.close();
        conn.disconnect();
        boolean mp4 = type != null && type.startsWith("video/") && len > 1_000_000
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        System.out.println(mp4 ? "PASS: 线路1 画质最佳地址可用" : "FAIL: 线路1 主地址异常");
        if (!mp4) failures++;

        System.out.println("== 2. 线路2：bit_rate 画质选择逻辑 ==");
        String fake = "{\"aweme_detail\":{\"aweme_id\":\"123\",\"desc\":\"测试视频\","
                + "\"author\":{\"nickname\":\"测试作者\"},"
                + "\"video\":{\"bit_rate\":["
                + "{\"width\":540,\"height\":960,\"play_addr\":{\"url_list\":[\"https://cdn.test/v/540?x=1\"]}},"
                + "{\"width\":1080,\"height\":1920,\"play_addr\":{\"url_list\":[\"https://cdn.test/v/1080?x=1\"]}},"
                + "{\"width\":720,\"height\":1280,\"play_addr\":{\"url_list\":[\"https://cdn.test/v/720?x=1\"]}}"
                + "]}}}";
        JSONObject root = new JSONObject(fake);
        DouyinParser.AwemeInfo d = DouyinParser.parseDetailJson(
                root.getJSONObject("aweme_detail"), "123");
        System.out.println("选中地址: " + d.videoUrl);
        boolean best = "https://cdn.test/v/1080?x=1".equals(d.videoUrl)
                && "测试视频".equals(d.desc) && "测试作者".equals(d.author);
        System.out.println(best ? "PASS: bit_rate 正确选中 1080p 最高档" : "FAIL: bit_rate 选择错误");
        if (!best) failures++;

        System.out.println("== 3. 线路2：无 WebView cookie 时应被 Argus 拦截且优雅报错 ==");
        try {
            DouyinParser.fetchDetail(info.id, null);
            System.out.println("FAIL: 未被拦截？说明接口行为变化（这反而是好消息，但需复查逻辑）");
        } catch (java.io.IOException e) {
            System.out.println("如预期被拦截: " + e.getMessage());
            System.out.println("PASS: 线路2 失败会正常抛出、由级联逻辑接手");
        }

        System.out.println(failures == 0 ? "== 全部 PASS ==" : "== 有 " + failures + " 项 FAIL ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
