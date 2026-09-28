import com.videodl.app.parser.BiliParser;
import com.videodl.app.parser.DouyinParser;
import com.videodl.app.parser.LinkRouter;
import com.videodl.app.parser.MediaSeg;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 完整真实下载验证（与 App 同一逻辑：checkedStream + pump + 字节对账） */
public class FullDL {
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("######## 抖音 WIsFoB6Z-h0 整文件下载 ########");
        douyin();
        System.out.println();
        System.out.println("######## B站 7T1g4UI 整文件下载（218MB）########");
        bili();
        System.out.println(fail == 0 ? "== 两条链接完整下载全部跑通 ==" : "== 有 FAIL ==");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void douyin() throws Exception {
        String text = "3.33 复制打开抖音，看看【.....的作品】# cxy# cxy0714# 上海冠军赛# zm... "
                + "https://v.douyin.com/WIsFoB6Z-h0/ :3pm 11/23 M@j.Pk oDu:/";
        DouyinParser.Result r = DouyinParser.parseFromShareText(text, null);
        HttpURLConnection conn = DouyinParser.openStream(r.info.videoUrl);
        long expect = conn.getContentLengthLong();
        InputStream in = checkedStream(conn, "video/mp4");
        File out = new File("dy_full.mp4");
        FileOutputStream fos = new FileOutputStream(out);
        long written = pump(conn, in, fos, true);
        fos.close();
        verify("抖音", out, expect, written);
    }

    static void bili() throws Exception {
        String share = "【【线代救命#4】90分钟学透方程组所有考点！同解方程组轻松拿下！-哔哩哔哩】 https://b23.tv/7T1g4UI";
        String bv = BiliParser.resolveBvid(LinkRouter.extractUrl(share));
        BiliParser.ensureWbi("");
        BiliParser.Result r = BiliParser.fetchView(bv, "");
        List<MediaSeg> segs = BiliParser.fetchPlayUrl(r, "");
        HttpURLConnection head = BiliParser.openDownload(segs.get(0).url);
        long expect = head.getContentLengthLong();
        head.disconnect();
        System.out.println("声明大小: " + expect / 1024 / 1024 + "MB");
        File out = new File("bili_full.mp4");
        long written;
        HttpURLConnection conn = null;
        try {
            conn = BiliParser.openDownload(segs.get(0).url);
            InputStream in = checkedStream(conn, "video/mp4");
            FileOutputStream fos = new FileOutputStream(out);
            written = pump(conn, in, fos, false);
            fos.close();
        } finally {
            if (conn != null) conn.disconnect();
        }
        verify("B站", out, expect, written);
    }

    static void verify(String tag, File f, long expect, long written) throws Exception {
        boolean sizeOk = f.length() == expect && written == expect;
        byte[] head = Files.readAllBytes(Paths.get(f.getAbsolutePath()));
        boolean mp4 = head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        System.out.println(tag + ": 文件=" + f.length() / 1024 / 1024 + "MB (" + f.length()
                + "B)  声明=" + expect + "  实写=" + written
                + "  ftyp=" + mp4);
        boolean ok = sizeOk && mp4;
        System.out.println(ok ? "  PASS" : "  FAIL");
        if (!ok) fail++;
    }

    // ==== 与 DownloadService 逐字一致的逻辑 ====
    static InputStream checkedStream(HttpURLConnection conn, String mime) throws IOException {
        conn.setReadTimeout(10_000);
        InputStream in = conn.getInputStream();
        byte[] head = new byte[16];
        int n = in.read(head);
        if (n <= 0) throw new IOException("10秒内无响应");
        if (!com.videodl.app.parser.MediaSniff.valid(mime, head, n)) throw new IOException("非媒体");
        conn.setReadTimeout(60_000);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(head, 0, n);
        return new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), in);
    }

    static long pump(HttpURLConnection conn, InputStream in, OutputStream os, boolean checkSize) throws Exception {
        long total = conn.getContentLengthLong();
        long read = 0;
        long lastUi = 0;
        byte[] buf = new byte[16384];
        int n = in.read(buf);
        if (n <= 0) throw new IOException("中断");
        os.write(buf, 0, n);
        read += n;
        while ((n = in.read(buf)) != -1) {
            os.write(buf, 0, n);
            read += n;
            long now = System.currentTimeMillis();
            if (now - lastUi > 3000) {
                lastUi = now;
                System.out.println("  已下载 " + read / 1024 / 1024 + "MB / " + total / 1024 / 1024 + "MB");
            }
        }
        if (checkSize) {
            if (read == 0) throw new IOException("空文件");
            if (read < 4096) throw new IOException("文件过小");
        }
        return read;
    }
}
