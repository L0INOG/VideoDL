import com.videodl.app.parser.DouyinParser;
import com.videodl.app.parser.KsParser;
import com.videodl.app.parser.LinkRouter;
import com.videodl.app.parser.MediaSniff;
import com.videodl.app.parser.XhsParser;

import org.json.JSONObject;

/**
 * 离线冒烟测试（不需要网络）：验证重构后解析层的纯计算逻辑仍然正确。
 *   1. LinkRouter 链接提取 + 平台识别（含小红书）
 *   2. MediaSniff 魔数校验（放行/拦截）
 *   3. DouyinParser 分享页 _ROUTER_DATA 抽取与字段解析（视频/图集）
 *   4. DouyinParser detail 接口 bit_rate 画质选择
 *   5. KsParser SSR 页 INIT_STATE 解析与 avc 优先选档
 *   6. XhsParser INITIAL_STATE 解析（undefined 字面量清洗 + 嵌套 mediaV2 + 图文）
 * 用法：bash tools/run-tests.sh SmokeTest
 */
public class SmokeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testLinkRouter();
        testMediaSniff();
        testDouyinSharePageParse();
        testDouyinDetailBitRate();
        testKsInitState();
        testXhsInitialState();
        if (failures == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
    }

    private static void check(boolean cond, String name) {
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + name);
        if (!cond) {
            failures++;
        }
    }

    private static void testLinkRouter() {
        System.out.println("== LinkRouter 链接提取与平台识别 ==");
        String dy = "3.33 复制打开抖音 https://v.douyin.com/WIsFoB6Z-h0/ ：看看";
        check("https://v.douyin.com/WIsFoB6Z-h0/".equals(LinkRouter.extractUrl(dy)),
                "短链提取并剥掉尾部中文标点");
        check(LinkRouter.PLATFORM_DOUYIN.equals(LinkRouter.detect(LinkRouter.extractUrl(dy))),
                "抖音域名识别");
        check(LinkRouter.PLATFORM_BILIBILI.equals(LinkRouter.detect("https://b23.tv/abc123")),
                "B站短链识别");
        check(LinkRouter.PLATFORM_KUAISHOU.equals(LinkRouter.detect("https://v.kuaishou.com/abcXY")),
                "快手短链识别");
        check(LinkRouter.PLATFORM_XIAOHONGSHU.equals(LinkRouter.detect("https://xhslink.cn/o/1UXhVDO23Jt")),
                "小红书短链识别");
        check(LinkRouter.PLATFORM_XIAOHONGSHU.equals(
                        LinkRouter.detect("https://www.xiaohongshu.com/explore/abc123?xsec_token=x")),
                "小红书长链识别");
        check(LinkRouter.detect("https://example.com/x") == null, "未知平台返回 null");
    }

    private static void testMediaSniff() {
        System.out.println("== MediaSniff 魔数校验 ==");
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] json = "{code:1}".getBytes();
        check(MediaSniff.valid("video/mp4", mp4, mp4.length), "MP4 魔数放行");
        check(MediaSniff.valid("image/jpeg", jpg, jpg.length), "JPEG 魔数放行");
        check(!MediaSniff.valid("video/mp4", json, json.length), "JSON 伪装视频被拦截");
        check(!MediaSniff.valid("image/jpeg", json, json.length), "JSON 伪装图片被拦截");
        check(!MediaSniff.valid("video/mp4", new byte[0], 0), "空流拒绝");
    }

    private static void testDouyinSharePageParse() throws Exception {
        System.out.println("== DouyinParser 分享页解析 ==");
        // 用 org.json API 直接构造 _ROUTER_DATA 结构，走与线上完全相同的深层定位逻辑
        JSONObject item = new JSONObject()
                .put("desc", "测试视频")
                .put("author", new JSONObject().put("nickname", "作者"))
                .put("video", new JSONObject()
                        .put("play_addr", new JSONObject().put("url_list",
                                new org.json.JSONArray().put("https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0d")))
                        .put("download_addr", new JSONObject().put("url_list",
                                new org.json.JSONArray().put("https://aweme.snssdk.com/aweme/v1/download/?video_id=v0d"))));
        JSONObject router = new JSONObject()
                .put("loaderData", new JSONObject()
                        .put("video_(id:/video/7301)/page", new JSONObject()
                                .put("videoInfoRes", new JSONObject()
                                        .put("item_list", new org.json.JSONArray().put(item)))));
        String html = "<script>window._ROUTER_DATA = " + router + ";</script>";
        JSONObject extracted = DouyinParser.extractRouterData(html);
        check(extracted != null, "_ROUTER_DATA 抽取");
        DouyinParser.AwemeInfo info = DouyinParser.parse(extracted, "7301");
        check("测试视频".equals(info.desc), "标题解析");
        check("作者".equals(info.author), "作者解析");
        check("7301".equals(info.id), "作品 ID");
        check(info.videoUrl != null && info.videoUrl.contains("/play/?"),
                "playwm→play 无水印替换");
        check(info.downloadAddrUrl != null, "download_addr 兜底地址");

        JSONObject imgItem = new JSONObject()
                .put("desc", "图集")
                .put("images", new org.json.JSONArray()
                        .put(new JSONObject().put("url_list", new org.json.JSONArray()
                                .put("https://p3.douyinpic.com/img/x~c5_100x100.webp")))
                        .put(new JSONObject().put("url_list", new org.json.JSONArray()
                                .put("https://p3.douyinpic.com/img/y.jpeg"))));
        String imgHtml = "<script>window._ROUTER_DATA = " + new JSONObject()
                .put("loaderData", new JSONObject()
                        .put("note_page", new JSONObject()
                                .put("videoInfoRes", new JSONObject()
                                        .put("item_list", new org.json.JSONArray().put(imgItem)))))
                + ";</script>";
        DouyinParser.AwemeInfo gi = DouyinParser.parse(DouyinParser.extractRouterData(imgHtml), "8001");
        check(gi.images.size() == 2 && gi.videoUrl == null, "图集地址列表且不误取 play_addr");
    }

    private static void testDouyinDetailBitRate() throws Exception {
        System.out.println("== DouyinParser 线路2 bit_rate 画质选择 ==");
        String json = "{\"aweme_detail\":{\"desc\":\"线路2\","
                + "\"video\":{\"bit_rate\":["
                + "{\"width\":720,\"height\":1280,\"play_addr\":{\"url_list\":[\"https://x/720\"]}},"
                + "{\"width\":1080,\"height\":1920,\"play_addr\":{\"url_list\":[\"https://x/1080\"]}}"
                + "]}}}";
        DouyinParser.AwemeInfo info = DouyinParser.parseDetailJson(
                new JSONObject(json).optJSONObject("aweme_detail"), "9001");
        check("https://x/1080".equals(info.videoUrl), "bit_rate 选分辨率最高档");
    }

    private static void testKsInitState() throws Exception {
        System.out.println("== KsParser INIT_STATE 解析 ==");
        JSONObject initState = new JSONObject()
                .put("t", new JSONObject()
                        .put("photo", new JSONObject()
                                .put("caption", "快手视频")
                                .put("userName", "UP")
                                .put("photoId", "p123"))
                        .put("manifest", new JSONObject()
                                .put("adaptationSet", new org.json.JSONArray().put(new JSONObject()
                                        .put("representation", new org.json.JSONArray()
                                                .put(new JSONObject()
                                                        .put("url", "https://c.a.yximgs.com/hevc.mp4")
                                                        .put("videoCodec", "hevc")
                                                        .put("width", 720).put("height", 1280))
                                                .put(new JSONObject()
                                                        .put("url", "https://c.a.yximgs.com/avc.mp4")
                                                        .put("videoCodec", "avc")
                                                        .put("width", 1080).put("height", 1920)
                                                        .put("backupUrl", new org.json.JSONArray()
                                                                .put("https://c.b.yximgs.com/backup.mp4"))))))));
        String html = "<script>window.INIT_STATE = " + initState + ";</script>";
        KsParser.Result r = KsParser.parseHtml(html);
        check(r != null && !r.reps.isEmpty(), "INIT_STATE 抽取与地址列表");
        check("快手视频".equals(r.title), "标题解析");
        KsParser.Rep best = r.best();
        check(best != null && "https://c.a.yximgs.com/avc.mp4".equals(best.url),
                "avc 优先于更高分辨率的 hevc");
        check("https://c.b.yximgs.com/backup.mp4".equals(best.backup), "备用地址保留");
        check("1080P".equals(best.label()), "清晰度标签（竖屏取短边）");
    }

    private static void testXhsInitialState() {
        System.out.println("== XhsParser INITIAL_STATE 解析 ==");
        // mediaV2 的真实形态：一段被转义进 JSON 字符串值的内嵌 JSON（引号前带反斜杠），
        // 先按正常 JSON 写，再统一转义成嵌入形态
        String innerJson = "{\"stream\":{\"h264\":[{\"master_url\":\"https://sns-video.xhscdn.com/v.mp4\","
                + "\"backup_urls\":[\"https://bak.xhscdn.com/v.mp4\"],\"width\":1080,\"height\":1920,"
                + "\"quality_type\":\"HD\"}],\"h265\":[{\"master_url\":\"https://sns-video.xhscdn.com/v5.mp4\","
                + "\"width\":1080,\"height\":1920}]},\"video\":{\"duration\":65}}";
        String mediaV2 = innerJson.replace("\"", "\\\"");
        // 外层结构对齐真实 SSR 页：noteData.data.noteData 三层包裹，video.mediaV2 在真实笔记对象内，
        // 并在根层级混入一个 undefined（org.json 会挂，parseHtml 必须先清洗）
        String html = "<script>window.__INITIAL_STATE__={\"currentNoteId\":undefined,"
                + "\"noteData\":{\"data\":{\"noteData\":{"
                + "\"title\":\"\",\"desc\":\"周末探店 #骑行[话题]#\","
                + "\"user\":{\"nickName\":\"阿岚\"},\"noteId\":\"665f0a1b000000001e02c3d4\","
                + "\"imageList\":[{\"width\":1080,\"height\":1440,\"livePhoto\":\"false\","
                + "\"infoList\":[{\"imageScene\":\"H5_PRV\",\"url\":\"https://sns-img.xhscdn.com/prv.jpg\"},"
                + "{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-img.xhscdn.com/dtl.jpg\"}]}],"
                + "\"video\":{\"mediaV2\":\"" + mediaV2 + "\"}"
                + "}}}}};</script>";
        XhsParser.Result r = XhsParser.parseHtml(html);
        check(r != null, "undefined 字面量清洗后可解析");
        check(r != null && "阿岚".equals(r.author), "作者解析");
        check(r != null && "665f0a1b000000001e02c3d4".equals(r.noteId), "noteId 解析");
        check(r != null && r.isVideo() && "https://sns-video.xhscdn.com/v.mp4".equals(r.best().url),
                "mediaV2 二次解析 + h264 优先");
        check(r != null && !r.best().label().isEmpty() && r.best().label().contains("1080P"),
                "清晰度标签");
        check(r != null && r.isImageSet() && "https://sns-img.xhscdn.com/dtl.jpg".equals(r.images.get(0).url),
                "图文取 H5_DTL 档");
        check(r != null && r.fmtDuration().equals("1:05"), "时长格式化");
        check(r != null && r.fileTitle().startsWith("周末探店") && !r.fileTitle().contains("#"),
                "文件名去掉 #话题#");
        check(XhsParser.isAllowed("https://sns-video.xhscdn.com/a.mp4")
                && !XhsParser.isAllowed("https://evil.com/a.mp4"), "CDN 域名白名单");
    }
}
