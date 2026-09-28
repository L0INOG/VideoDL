package com.videodl.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import com.videodl.app.net.Http;
import com.videodl.app.net.Line2Loader;
import com.videodl.app.parser.BiliParser;
import com.videodl.app.parser.DouyinParser;
import com.videodl.app.parser.KsParser;
import com.videodl.app.parser.LinkRouter;
import com.videodl.app.parser.MediaSeg;
import com.videodl.app.parser.MediaSniff;
import com.videodl.app.parser.XhsParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 【下载】解析+下载前台服务，两段式工作。
 *
 * 功能: ACTION_PARSE —— 只解析，结果持有在 pending 里等用户确认；
 *       ACTION_DOWNLOAD —— 下载 pending 里的结果（服务被回收时回退为解析+下载一体）。
 * 链路: parseAny → (抖音级联/B站分段/快手单文件) → saveViaMediaStore / saveLegacy
 *       → Listener 回调界面小窗 + 通知栏进度。
 * 存储: Android 10+ 写 MediaStore.Downloads；Android 8/9 直接写公共下载目录（.part 中转）。
 * 扩展点: 新增平台时在 parseAny 与 runDownloadTask 的两处 platform 分发各加一个分支，
 *          下载方法把地址组装成 MediaSeg 列表后交给 saveSegments 即可。
 */
public class DownloadService extends Service {

    public static final String EXTRA_TEXT = "text";
    public static final String ACTION_PARSE = "com.videodl.app.action.PARSE";
    public static final String ACTION_DOWNLOAD = "com.videodl.app.action.DOWNLOAD";

    /** 构建标记：随每次发版手动更新，可用 aapt/apksigner 检查安装包确认手机上是哪一版 */
    public static final String BUILD = "0928-3";

    /** 公共下载目录下的子目录名（下载/VideoDL） */
    static final String DIR_NAME = "VideoDL";

    /** 一条已保存文件的描述 */
    public static class SavedFile {
        public String name;
        public String uri;
        public String mime;
        public boolean existed;  // true = 本来就有，未重复下载
        public String line;      // 经哪条线路完成
    }

    /** 解析完成但尚未下载的任务快照 */
    private static class PendingJob {
        String platform;
        String url;
        String awemeId;
        String pageType;
        DouyinParser.AwemeInfo info;   // 抖音
        boolean fromLine2;
        BiliParser.Result bili;        // B站
        String bvid;
        KsParser.Result ks;            // 快手
        String ksCookies;
        XhsParser.Result xhs;          // 小红书
        String displayTitle;           // 小窗展示标题
        String sub;                    // 小窗副标题（类型/清晰度/张数）
        String notifyTitle;            // 完成通知标题
    }

    /** 下载服务的界面回调（小窗进度） */
    public interface Listener {
        /** 解析结束（ok=false 时 errorMessage 可用；title/sub 用于展示） */
        void onParsed(boolean ok, String title, String sub, String errorMessage);

        /** 下载进度：视频 index=-1；图集 index=1..count */
        void onProgress(long bytes, long total, int index, int count);

        /** 下载结束（成功或失败）；窗口不自动关闭 */
        void onFinished(boolean success, String message);
    }

    public class LocalBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    private static final String CHANNEL_ID = "download";
    private static final int NOTIFY_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Listener listener;
    private NotificationManager nm;
    private PendingJob pending;
    private String lastText;
    private volatile int curIndex = -1;   // 图集当前第几张（-1=视频）
    private volatile int curCount = 0;    // 图集总数

    // ═══════════════════════════════════════════════════════════════
    //  [服务] 生命周期与指令分发
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "视频下载",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("下载进度通知");
        nm.createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        final String action = intent == null ? ACTION_PARSE : intent.getAction();
        if (ACTION_DOWNLOAD.equals(action)) {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    runDownloadTask();
                }
            });
            return START_NOT_STICKY;
        }
        final String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        lastText = text;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                runParseTask(text);
            }
        });
        return START_NOT_STICKY;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析] 平台识别与三平台元数据
    // ═══════════════════════════════════════════════════════════════

    private void runParseTask(String text) {
        startFg("解析链接中…", 0, true);
        try {
            PendingJob job = parseAny(text);
            pending = job;
            notifyParsed(true, job.displayTitle, job.sub, null);
        } catch (final Exception e) {
            pending = null;
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            notifyDone(false, "解析失败", msg);
            notifyParsed(false, null, null, msg);
        } finally {
            // 解析完成后服务保持存活以持有 pending，等下载指令
            stopForeground(true);
        }
    }

    /** 平台识别 + 各平台解析（只解析不下载），失败抛异常 */
    private PendingJob parseAny(String text) throws Exception {
        String url = LinkRouter.extractUrl(text);
        if (url == null) {
            throw new IOException("没有在文案里找到链接");
        }
        String platform = LinkRouter.detect(url);
        if (platform == null) {
            throw new IOException("只支持抖音、B站、快手、小红书的分享链接");
        }
        // 扩展点：新平台在此登记解析入口
        if (LinkRouter.PLATFORM_BILIBILI.equals(platform)) {
            return parseBili(url);
        }
        if (LinkRouter.PLATFORM_KUAISHOU.equals(platform)) {
            return parseKs(url);
        }
        if (LinkRouter.PLATFORM_XIAOHONGSHU.equals(platform)) {
            return parseXhs(url);
        }
        return parseDouyin(url);
    }

    /**
     * 抖音解析：线路1（ttwid + 分享页）失败时级联线路2（WebView 预热 + 主站 detail 接口）。
     * 两条线路都拿不到可下载地址才算失败。
     */
    private PendingJob parseDouyin(String url) throws Exception {
        startFg("解析抖音链接…", 0, true);
        String[] idType = DouyinParser.resolve(url);
        if (idType == null) {
            throw new IOException("无法获取作品 ID，链接可能已失效或被删除");
        }
        PendingJob job = new PendingJob();
        job.platform = LinkRouter.PLATFORM_DOUYIN;
        job.url = url;
        job.awemeId = idType[0];
        job.pageType = idType[1];

        DouyinParser.AwemeInfo info = null;
        String metaErr = null;
        try {
            SharedPreferences sp = getSharedPreferences("videodl", MODE_PRIVATE);
            DouyinParser.Result r = DouyinParser.parseSharePage(job.awemeId, job.pageType,
                    sp.getString("ttwid", null));
            if (r.ttwid != null && !r.ttwid.isEmpty()) {
                sp.edit().putString("ttwid", r.ttwid).apply();
            }
            info = r.info;
        } catch (IOException e) {
            metaErr = e.getMessage();
        }

        if (info == null || (!info.isVideo() && info.images.isEmpty())) {
            // 线路2 元数据：隐藏 WebView 预热浏览器 cookie → 主站 detail 接口
            startFg("线路2预热中…", 0, true);
            String cookies = Line2Loader.warmBlocking(this, 25_000);
            if (cookies != null) {
                try {
                    info = DouyinParser.fetchDetail(job.awemeId, cookies);
                    job.fromLine2 = true;
                } catch (IOException e2) {
                    metaErr = metaErr == null ? "线路2：" + e2.getMessage()
                            : metaErr + "；线路2：" + e2.getMessage();
                }
            } else {
                metaErr = metaErr == null ? "线路2预热失败" : metaErr + "；线路2预热失败";
            }
        }
        if (info == null || (!info.isVideo() && info.images.isEmpty())) {
            throw new IOException("两条线路都无法解析该作品：" + metaErr);
        }
        job.info = info;
        String title = info.desc.isEmpty() ? "抖音作品" : info.desc;
        if (title.length() > 24) {
            title = title.substring(0, 24) + "…";
        }
        job.displayTitle = title;
        job.sub = info.isVideo() ? "视频 · 已获取下载链接"
                : "图集 · 共 " + info.images.size() + " 张";
        job.notifyTitle = "下载完成：" + title
                + (info.author.isEmpty() ? "" : " - " + info.author);
        return job;
    }

    /** B站解析：BV号 → wbi 签名 → 详情 → durl 分段地址。 */
    private PendingJob parseBili(String url) throws Exception {
        startFg("解析B站链接…", 0, true);
        String bv = BiliParser.resolveBvid(url);
        if (bv == null) {
            throw new IOException("无法获取B站视频 BV 号，链接可能已失效");
        }
        if (!BiliParser.ensureWbi("")) {
            throw new IOException("B站 wbi 密钥获取失败");
        }
        BiliParser.Result r = BiliParser.fetchView(bv, "");
        List<MediaSeg> segs = BiliParser.fetchPlayUrl(r, "");
        if (segs.isEmpty()) {
            throw new IOException("未取到B站播放地址");
        }
        PendingJob job = new PendingJob();
        job.platform = LinkRouter.PLATFORM_BILIBILI;
        job.url = url;
        job.bvid = bv;
        job.bili = r;
        job.displayTitle = shortTitle(r.title, "B站视频");
        job.sub = "视频 · " + r.qualityText() + " · 已获取下载链接";
        job.notifyTitle = "下载完成：" + shortTitle(r.title, "B站视频")
                + (r.up.isEmpty() ? "" : " - " + r.up);
        return job;
    }

    /** 快手解析：短链 → photoId → SSR 页分档地址。 */
    private PendingJob parseKs(String url) throws Exception {
        startFg("解析快手链接…", 0, true);
        String[] pc = KsParser.resolvePhotoId(url);
        if (pc == null) {
            throw new IOException("无法获取快手作品 ID，链接可能已失效");
        }
        KsParser.Result r = KsParser.fetch(pc[0], pc[1]);
        if (r.best() == null) {
            throw new IOException("未取到快手播放地址");
        }
        PendingJob job = new PendingJob();
        job.platform = LinkRouter.PLATFORM_KUAISHOU;
        job.url = url;
        job.ks = r;
        job.ksCookies = pc[1];
        job.displayTitle = shortTitle(r.title, "快手视频");
        job.sub = "视频 · " + r.best().label() + " · 已获取下载链接";
        job.notifyTitle = "下载完成：" + shortTitle(r.title, "快手视频")
                + (r.author.isEmpty() ? "" : " - " + r.author);
        return job;
    }

    /**
     * 小红书解析：短链 302 → 笔记页（带 xsec_token）→ SSR 页 INITIAL_STATE。
     * 视频与图文两种笔记都支持，下载阶段按实际内容走。
     */
    private PendingJob parseXhs(String url) throws Exception {
        startFg("解析小红书链接…", 0, true);
        StringBuilder cookies = new StringBuilder();
        String noteUrl = XhsParser.resolveNote(url, cookies);
        if (noteUrl == null) {
            throw new IOException("无法获取小红书笔记地址，链接可能已失效");
        }
        XhsParser.Result r = XhsParser.fetch(noteUrl, cookies.toString());
        if (!r.isVideo() && !r.isImageSet()) {
            throw new IOException("未取到小红书下载地址");
        }
        PendingJob job = new PendingJob();
        job.platform = LinkRouter.PLATFORM_XIAOHONGSHU;
        job.url = url;
        job.xhs = r;
        job.displayTitle = shortTitle(r.displayTitle(), "小红书笔记");
        job.sub = r.isVideo()
                ? "视频 · " + r.best().label() + " · 已获取下载链接"
                : "图集 · 共 " + r.images.size() + " 张";
        job.notifyTitle = "下载完成：" + shortTitle(r.displayTitle(), "小红书笔记")
                + (r.author == null || r.author.isEmpty() ? "" : " - " + r.author);
        return job;
    }

    /** 标题截断到 24 字（小窗/通知宽度限制），空则用平台默认名。 */
    private static String shortTitle(String raw, String fallback) {
        String t = raw == null || raw.isEmpty() ? fallback : raw;
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [下载] 任务编排（按平台分发）
    // ═══════════════════════════════════════════════════════════════

    private void runDownloadTask() {
        PendingJob job = pending;
        if (job == null) {
            // 服务被回收等场景：回退为解析+下载一体
            if (lastText == null) {
                notifyFinished(false, "没有待下载的任务");
                return;
            }
            try {
                job = parseAny(lastText);
                pending = job;
            } catch (final Exception e) {
                String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                notifyDone(false, "下载失败", msg);
                notifyFinished(false, msg);
                stopForeground(true);
                stopSelf();
                return;
            }
        }
        startFg("开始下载…", 0, true);
        try {
            List<SavedFile> saved = new ArrayList<>();
            // 扩展点：新平台在此登记下载入口
            if (LinkRouter.PLATFORM_BILIBILI.equals(job.platform)) {
                downloadBili(job, saved);
            } else if (LinkRouter.PLATFORM_KUAISHOU.equals(job.platform)) {
                downloadKs(job, saved);
            } else if (LinkRouter.PLATFORM_XIAOHONGSHU.equals(job.platform)) {
                downloadXhs(job, saved);
            } else {
                downloadDouyin(job, saved);
            }

            int fresh = 0;
            boolean usedLine2 = false;
            long totalBytes = 0;
            for (SavedFile sf : saved) {
                if (sf == null) {
                    continue;
                }
                if (!sf.existed) {
                    fresh++;
                    long sz = savedSizeOf(sf);
                    if (sz > 0) {
                        totalBytes += sz;
                    }
                }
                if (sf.line != null && sf.line.startsWith("线路2")) {
                    usedLine2 = true;
                }
            }
            String msg = fresh == 0 ? "文件已存在，未重复下载"
                    : "已保存 " + fresh + " 个文件（共 " + human(totalBytes) + "）到 下载/" + DIR_NAME
                            + (usedLine2 ? "（经线路2完成）" : "");
            notifyDone(true, job.notifyTitle, msg);
            notifyFinished(true, msg);
            pending = null;
        } catch (final Exception e) {
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            notifyDone(false, "下载失败", msg);
            notifyFinished(false, msg);
        } finally {
            stopForeground(true);
            stopSelf();
        }
    }

    /** 抖音下载：视频走双线路级联，图集逐张下载。 */
    private void downloadDouyin(PendingJob job, List<SavedFile> saved) throws Exception {
        DouyinParser.AwemeInfo info = job.info;
        String base = safeName(info);
        if (info.isVideo()) {
            curIndex = -1;
            curCount = 0;
            downloadVideoCascade(job.awemeId, info, base, job.fromLine2, saved);
        } else {
            downloadImages(info, base, saved);
        }
    }

    /** B站下载：durl 分段列表交给通用分段保存。 */
    private void downloadBili(PendingJob job, List<SavedFile> saved) throws Exception {
        curIndex = -1;
        curCount = 0;
        BiliParser.Result r = job.bili;
        String base = sanitizeName(r.title);
        if (base.isEmpty()) {
            base = "bilibili";
        }
        String fileName = base + "_" + job.bvid + ".mp4";
        SavedFile f = saveSegments(r.segments, "video/mp4", fileName, r.qualityText(),
                new MediaOpener() {
                    @Override
                    public HttpURLConnection open(String u) throws IOException {
                        return BiliParser.openDownload(u);
                    }
                });
        if (f != null) {
            saved.add(f);
        }
    }

    /** 快手下载：选中一档清晰度，包装成单段地址列表。 */
    private void downloadKs(PendingJob job, List<SavedFile> saved) throws Exception {
        curIndex = -1;
        curCount = 0;
        KsParser.Result r = job.ks;
        KsParser.Rep best = r.best();
        if (best == null || best.pick() == null) {
            throw new IOException("未取到快手播放地址");
        }
        String base = sanitizeName(r.title);
        if (base.isEmpty()) {
            base = "kuaishou";
        }
        String fileName = base + "_" + r.photoId + ".mp4";
        List<MediaSeg> segs = new ArrayList<>();
        segs.add(new MediaSeg(best.url, best.backup));
        SavedFile f = saveSegments(segs, "video/mp4", fileName, best.label(),
                new MediaOpener() {
                    @Override
                    public HttpURLConnection open(String u) throws IOException {
                        return KsParser.openDownload(u);
                    }
                });
        if (f != null) {
            saved.add(f);
        }
    }

    /**
     * 小红书下载：视频笔记下 master_url（h264 优先最高清），图文笔记逐张下 H5_DTL 图。
     * 一篇笔记同时含视频和图片时两者都下。
     */
    private void downloadXhs(PendingJob job, List<SavedFile> saved) throws Exception {
        XhsParser.Result r = job.xhs;
        String base = sanitizeName(r.fileTitle());
        if (base.isEmpty()) {
            base = "xiaohongshu";
        }
        MediaOpener opener = new MediaOpener() {
            @Override
            public HttpURLConnection open(String u) throws IOException {
                return XhsParser.openDownload(u);
            }
        };

        if (r.isVideo()) {
            curIndex = -1;
            curCount = 0;
            XhsParser.Rep best = r.best();
            if (best == null || best.pick() == null) {
                throw new IOException("未取到小红书视频地址");
            }
            String id = r.noteId == null || r.noteId.isEmpty()
                    ? String.valueOf(System.currentTimeMillis() / 1000) : r.noteId;
            String fileName = base + "_" + id + ".mp4";
            List<MediaSeg> segs = new ArrayList<>();
            segs.add(new MediaSeg(best.url, best.backup));
            SavedFile f = saveSegments(segs, "video/mp4", fileName, best.label(), opener);
            if (f != null) {
                saved.add(f);
            }
        }

        if (r.isImageSet()) {
            curCount = r.images.size();
            int done = 0;
            for (int i = 0; i < r.images.size(); i++) {
                curIndex = i + 1;
                // 每张图开始/完成立即上报，小图下载快也能看到 "图 x/y"
                progress(0, 0);
                String url = r.images.get(i).url;
                String ext = "jpg";
                String mime = "image/jpeg";
                String low = url.toLowerCase();
                String path = low.indexOf('?') < 0 ? low : low.substring(0, low.indexOf('?'));
                if (path.endsWith(".png")) {
                    ext = "png";
                    mime = "image/png";
                } else if (path.endsWith(".webp")) {
                    ext = "webp";
                    mime = "image/webp";
                } else if (path.endsWith(".heic") || path.endsWith(".heif")) {
                    ext = "heic";
                    mime = "image/heic";
                }
                String fileName = (r.images.size() == 1 ? base : base + "_" + (i + 1)) + "." + ext;
                startFg("下载中 " + (i + 1) + "/" + r.images.size() + "：" + fileName,
                        (int) (100L * done / r.images.size()), false);
                SavedFile f = attempt("线路1", url, mime, fileName, opener);
                if (f != null) {
                    saved.add(f);
                }
                done++;
                progress(1, 1);   // 该张完成，进度条立即走到位
            }
            curIndex = -1;
            curCount = 0;
        }
    }

    /**
     * 抖音视频双线路级联：
     *   线路1（画质优先，ratio=default）→ 10 秒完全无响应判死 →
     *   线路2（WebView 预热 cookie + 主站 detail 接口 bit_rate 最高档）→
     *   线路1 备用地址（原始 720p / download_addr，不同下载端点）最后兜底。
     * 一旦某条线路开始出数据，就一条道下完，绝不中途切换。
     */
    private void downloadVideoCascade(String awemeId, DouyinParser.AwemeInfo info,
                                      String base, boolean fromLine2, List<SavedFile> saved)
            throws Exception {
        String fileName = base + ".mp4";
        String mime = "video/mp4";

        Exception last = null;
        SavedFile f = null;
        try {
            f = attempt(fromLine2 ? "线路2" : "线路1", info.videoUrl, mime, fileName);
        } catch (Exception e) {
            last = e;
        }

        if (f == null && last != null && !fromLine2) {
            startFg("线路1无响应，线路2预热中…", 0, true);
            try {
                String cookies = Line2Loader.warmBlocking(this, 25_000);
                if (cookies == null) {
                    throw new IOException("浏览器预热失败");
                }
                DouyinParser.AwemeInfo alt = DouyinParser.fetchDetail(awemeId, cookies);
                if (alt == null || alt.videoUrl == null) {
                    throw new IOException("线路2未返回下载地址");
                }
                f = attempt("线路2", alt.videoUrl, mime, fileName);
            } catch (Exception e2) {
                last = e2;
            }
        }

        if (f == null && last != null) {
            String[] backups = {info.originalVideoUrl, info.downloadAddrUrl};
            for (String u : backups) {
                if (u == null || u.equals(info.videoUrl)) {
                    continue;
                }
                try {
                    f = attempt("线路1备用", u, mime, fileName);
                    break;
                } catch (Exception e3) {
                    last = e3;
                }
            }
        }

        if (f == null && last != null) {
            throw last;
        }
        if (f != null) {
            saved.add(f);
        }
    }

    /** 图集：每张图一条地址；进度上报为第 x/y 张。 */
    private void downloadImages(DouyinParser.AwemeInfo info, String base, List<SavedFile> saved)
            throws Exception {
        curCount = info.images.size();
        int done = 0;
        for (int i = 0; i < info.images.size(); i++) {
            curIndex = i + 1;
            // 每张图开始/完成立即上报，小图下载快也能看到 "图 x/y"
            progress(0, 0);
            String url = info.images.get(i);
            String ext = "jpg";
            String mime = "image/jpeg";
            String low = url.toLowerCase();
            String path = low.indexOf('?') < 0 ? low : low.substring(0, low.indexOf('?'));
            if (path.endsWith(".webp")) {
                ext = "webp";
                mime = "image/webp";
            } else if (path.endsWith(".png")) {
                ext = "png";
                mime = "image/png";
            } else if (path.endsWith(".heif") || path.endsWith(".heic")) {
                ext = "heic";
                mime = "image/heic";
            } else if (path.endsWith(".jpeg")) {
                ext = "jpg";
            }
            String fileName = (info.images.size() == 1 ? base : base + "_" + (i + 1)) + "." + ext;
            startFg("下载中 " + (i + 1) + "/" + info.images.size() + "：" + fileName,
                    (int) (100L * done / info.images.size()), false);
            SavedFile f = attempt("线路1", url, mime, fileName);
            if (f != null) {
                saved.add(f);
            }
            done++;
            progress(1, 1);   // 该张完成，进度条立即走到位
        }
        curIndex = -1;
        curCount = 0;
    }

    private static String oneLine(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return m.length() > 40 ? m.substring(0, 40) + "…" : m;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [下载] 单文件下载尝试（抖音视频/图集共用）
    // ═══════════════════════════════════════════════════════════════

    /** 下载连接的开启方式（各平台 Referer/白名单不同），由调用方注入。 */
    public interface MediaOpener {
        HttpURLConnection open(String url) throws IOException;
    }

    /** 一次下载尝试（抖音默认下载器）：10 秒无首字节或错误页即抛异常；成功后记录历史。 */
    private SavedFile attempt(String line, String url, String mime, String fileName) throws Exception {
        return attempt(line, url, mime, fileName, new MediaOpener() {
            @Override
            public HttpURLConnection open(String u) throws IOException {
                return DouyinParser.openStream(u);
            }
        });
    }

    /**
     * 一次下载尝试：10 秒无首字节或错误页即抛异常；成功后记录历史。
     *
     * @param opener 下载连接的开启方式（各平台 Referer/白名单不同）
     */
    private SavedFile attempt(String line, String url, String mime, String fileName,
                              MediaOpener opener) throws Exception {
        startFg("[" + line + "] 下载中：" + fileName, 0, false);
        HttpURLConnection conn = null;
        try {
            conn = opener.open(url);
            String ct = conn.getContentType();
            if (ct != null && ct.contains("text/html")) {
                throw new IOException("返回网页而非媒体文件（可能被限流）");
            }
            // 魔数校验：防止把风控 JSON/错误页存成媒体文件
            InputStream in = checkedStream(conn, mime);
            long expect = conn.getContentLengthLong();
            final HttpURLConnection fconn = conn;
            final InputStream fin = in;
            ContentSource source = new ContentSource() {
                @Override
                public void writeTo(OutputStream os) throws Exception {
                    pump(fconn, fin, os, fileName, true);
                }
            };
            SavedFile f = Build.VERSION.SDK_INT >= 29
                    ? saveViaMediaStore(mime, fileName, line, source)
                    : saveLegacy(mime, fileName, line, source);
            if (f != null && !f.existed && expect > 0) {
                long savedSize = savedSizeOf(f);
                if (savedSize >= 0 && savedSize != expect) {
                    cleanupPartial(fileName);
                    throw new IOException("下载不完整（" + savedSize + "/" + expect
                            + " 字节），已丢弃该文件");
                }
            }
            if (f != null) {
                addHistory(this, f.name, f.uri);
            }
            return f;
        } catch (Exception e) {
            cleanupPartial(fileName);
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 打开并校验下载流：读首块做魔数校验（在 10 秒首字节判死窗口内），
     * 通过后放宽读超时，返回"首块+网络流"的拼接流供 pump 续读。
     */
    private InputStream checkedStream(HttpURLConnection conn, String mime) throws IOException {
        conn.setReadTimeout(Http.FIRST_BYTE_TIMEOUT_MS);
        InputStream in = conn.getInputStream();
        byte[] head = new byte[16];
        int n = in.read(head);
        if (n <= 0) {
            throw new IOException("10秒内无响应，下载完全未开始");
        }
        if (!MediaSniff.valid(mime, head, n)) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            throw new IOException("返回内容不是有效媒体（可能被风控拦截）");
        }
        conn.setReadTimeout(Http.READ_TIMEOUT_MS);   // 数据开始流动，放宽超时
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(head, 0, n);
        return new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), in);
    }

    // ═══════════════════════════════════════════════════════════════
    //  [存储] 落盘（MediaStore / 传统目录 双路径，单流与分段共用）
    // ═══════════════════════════════════════════════════════════════

    /** 把已打开的下载内容写到输出流（单流直下 / 多段顺序写两种实现）。 */
    private interface ContentSource {
        void writeTo(OutputStream os) throws Exception;
    }

    /**
     * Android 10+ 保存路径：MediaStore.Downloads。
     * 同名文件先验魔数——历史残留的坏文件自动删除重下，好文件直接复用不重复下载。
     */
    private SavedFile saveViaMediaStore(String mime, String fileName, String line,
                                        ContentSource source) throws Exception {
        Cursor c = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{fileName, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/"},
                null);
        if (c != null) {
            boolean exists = c.moveToFirst();
            long id = exists ? c.getLong(0) : -1;
            long size = exists && !c.isNull(1) ? c.getLong(1) : -1;
            c.close();
            if (exists && !validExistingMediaStore(id, size, mime)) {
                getContentResolver().delete(ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null);
                exists = false;
            }
            if (exists) {
                return savedFileRef(fileName, mime, line, true, ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id).toString());
            }
        }

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            throw new IOException("无法创建下载文件（MediaStore 拒绝）");
        }
        OutputStream os = getContentResolver().openOutputStream(uri);
        Exception err = null;
        try {
            source.writeTo(os);
        } catch (Exception e) {
            err = e;
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
        if (err != null) {
            // 先关流再删除 pending 记录
            getContentResolver().delete(uri, null, null);
            throw err;
        }
        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, cv, null, null);
        return savedFileRef(fileName, mime, line, false, uri.toString());
    }

    /**
     * Android 8/9 保存路径：直接写公共下载目录（需存储权限），.part 中转后原子改名。
     */
    private SavedFile saveLegacy(String mime, String fileName, String line,
                                 ContentSource source) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir);
        }
        File out = new File(dir, fileName);
        if (out.exists() && out.length() > 0) {
            if (!validExistingFile(out, mime)) {
                out.delete();   // 历史残留坏文件，删掉重下
            } else {
                return savedFileRef(fileName, mime, line, true, legacyUri(fileName));
            }
        }
        File tmp = new File(dir, fileName + ".part");
        FileOutputStream fos = new FileOutputStream(tmp);
        Exception err = null;
        try {
            source.writeTo(fos);
        } catch (Exception e) {
            err = e;
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        if (err != null) {
            throw err;   // .part 由调用方 cleanupPartial 清理
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("保存文件失败");
        }
        return savedFileRef(fileName, mime, line, false, legacyUri(fileName));
    }

    private static SavedFile savedFileRef(String name, String mime, String line,
                                          boolean existed, String uri) {
        SavedFile f = new SavedFile();
        f.name = name;
        f.mime = mime;
        f.line = line;
        f.existed = existed;
        f.uri = uri;
        return f;
    }

    /** Android 8/9 下已保存文件的 content:// 地址，经 SavedFileProvider 只读打开。 */
    private static String legacyUri(String fileName) throws java.io.UnsupportedEncodingException {
        return "content://" + SavedFileProvider.AUTHORITY + "/" + SavedFileProvider.PATH_SAVED + "/"
                + URLEncoder.encode(fileName, "UTF-8");
    }

    /** 查询已保存文件的真实大小；查不到返回 -1。 */
    private long savedSizeOf(SavedFile f) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && f.uri.startsWith("content://")) {
                Cursor c = getContentResolver().query(Uri.parse(f.uri),
                        new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
                if (c != null) {
                    boolean ok = c.moveToFirst();
                    long size = ok && !c.isNull(0) ? c.getLong(0) : -1;
                    c.close();
                    return size;
                }
                return -1;
            }
            // Android 8/9 provider 路径
            return new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR_NAME + "/" + f.name).length();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 已存在的同媒体文件：验大小 + 魔数，历史坏文件返回 false（调用方删除重下）。 */
    private boolean validExistingMediaStore(long id, long size, String mime) {
        if (size >= 0 && size < 4096) {
            return false;
        }
        try {
            Uri u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            InputStream is = getContentResolver().openInputStream(u);
            if (is == null) {
                return false;
            }
            byte[] b = new byte[16];
            int n = is.read(b);
            is.close();
            return MediaSniff.valid(mime, b, n);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validExistingFile(File f, String mime) {
        if (f.length() < 4096) {
            return false;
        }
        try {
            InputStream is = new FileInputStream(f);
            byte[] b = new byte[16];
            int n = is.read(b);
            is.close();
            return MediaSniff.valid(mime, b, n);
        } catch (Exception e) {
            return false;
        }
    }

    /** 下载失败/不完整时清掉半成品（pending 记录或 .part 文件）。 */
    private void cleanupPartial(String fileName) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                getContentResolver().delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.IS_PENDING + "=1",
                        new String[]{fileName});
            } catch (Exception ignored) {
            }
        } else {
            new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR_NAME), fileName + ".part").delete();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [存储] 数据搬运（pump 系列，输出流的所有权在 save* 方法）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把单个下载流写进输出流并上报进度。只负责关闭输入流 in，
     * 输出流 os 由 saveViaMediaStore / saveLegacy 统一关闭
     * （分段下载时多个段要接力写同一个 os，绝不能在这里关）。
     */
    private long pump(HttpURLConnection conn, InputStream in, OutputStream os, final String label,
                      final boolean checkSize) throws Exception {
        // 首块已在 checkedStream 里读取并校验（判死窗口 + 放宽超时都在那里完成），
        // 这里从"首块+网络流"的拼接流继续读。
        long total = conn.getContentLengthLong();
        long read = 0;
        long lastUi = 0;
        byte[] buf = new byte[16384];
        try {
            int n = in.read(buf);
            if (n <= 0) {
                throw new IOException("下载流异常中断");
            }
            os.write(buf, 0, n);
            read += n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                read += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 300) {
                    lastUi = now;
                    progress(read, total);
                }
            }
        } finally {
            in.close();
        }
        if (checkSize) {
            if (read == 0) {
                throw new IOException("空文件（可能被限流，请稍后重试）");
            }
            if (read < 4096) {
                throw new IOException("文件过小，疑似无效响应");
            }
        }
        return read;
    }

    /**
     * 通用分段下载（B站 durl / 快手单文件）：所有段顺序写进同一个文件。
     * 每段 10 秒首字节判死，段失败自动换该段的备用地址。
     */
    private SavedFile saveSegments(List<MediaSeg> segs, String mime,
                                   String fileName, String quality, MediaOpener opener) throws Exception {
        // 防御：空列表绝不允许静默写出 0 字节文件
        if (segs == null || segs.isEmpty()) {
            throw new IOException("未获取到任何下载地址");
        }
        startFg("[" + quality + "] 下载中：" + fileName, 0, false);
        ContentSource source = new ContentSource() {
            @Override
            public void writeTo(OutputStream os) throws Exception {
                pumpSegments(segs, os, fileName, quality, opener);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return saveViaMediaStore(mime, fileName, quality, source);
            }
            return saveLegacy(mime, fileName, quality, source);
        } catch (Exception e) {
            cleanupPartial(fileName);
            throw e;
        }
    }

    /** 逐段下载写入同一个输出流；每段先主地址、失败换备用地址，均带 10 秒首字节判死。 */
    private long pumpSegments(List<MediaSeg> segs, OutputStream os,
                              String fileName, String quality, MediaOpener opener) throws Exception {
        long writtenAll = 0;
        for (int i = 0; i < segs.size(); i++) {
            MediaSeg seg = segs.get(i);
            String label = "[" + quality + "] 第 " + (i + 1) + "/" + segs.size() + " 段：" + fileName;
            Exception last = null;
            for (String u : new String[]{seg.url, seg.backup}) {
                if (u == null || u.isEmpty()) {
                    continue;
                }
                HttpURLConnection conn = null;
                try {
                    conn = opener.open(u);
                    InputStream in = checkedStream(conn, "video/mp4");
                    long expect = conn.getContentLengthLong();
                    long written = pump(conn, in, os, label, false);
                    // 段完整性对账：实写字节必须等于声明长度，差一字节都不算成功
                    if (expect > 0 && written != expect) {
                        throw new IOException("第 " + (i + 1) + " 段下载不完整（"
                                + written + "/" + expect + " 字节）");
                    }
                    writtenAll += written;
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (u.equals(seg.url) && seg.backup != null && !seg.backup.isEmpty()) {
                        startFg(label + " 无响应，切换备用地址…", 0, true);
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
            if (last != null) {
                throw last;
            }
        }
        return writtenAll;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [存储] 下载历史与文件名
    // ═══════════════════════════════════════════════════════════════

    /**
     * 追加一条下载历史（SharedPreferences，最多留 30 条）。
     * 扩展点: 未来做"下载记录"界面时直接读 videodl/history，每项含 name/uri/time。
     */
    public static void addHistory(Context ctx, String name, String uri) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("videodl", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("history", "[]"));
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("uri", uri);
            o.put("time", System.currentTimeMillis());
            JSONArray n = new JSONArray();
            n.put(o);
            for (int i = 0; i < arr.length() && i < 29; i++) {
                n.put(arr.get(i));
            }
            sp.edit().putString("history", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private String safeName(DouyinParser.AwemeInfo info) {
        String base = sanitizeName(info.desc);
        if (base.isEmpty()) {
            base = "douyin";
        }
        String id = info.id.isEmpty() ? String.valueOf(System.currentTimeMillis() / 1000) : info.id;
        return base + "_" + id;
    }

    /** 文件名清洗（标题 -> 安全文件名片段）。 */
    static String sanitizeName(String s) {
        if (s == null) {
            return "";
        }
        String base = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t#@]", " ")
                .replaceAll("\\s+", " ").trim();
        if (base.length() > 40) {
            base = base.substring(0, 40).trim();
        }
        return base;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [通知] 前台通知与完成通知
    // ═══════════════════════════════════════════════════════════════

    private void startFg(String text, int percent, boolean indeterminate) {
        Notification n = buildNotify(text, percent, indeterminate, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    /** 点击通知回到主界面的 PendingIntent（各通知共用）。 */
    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotify(String text, int percent, boolean indeterminate, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("VideoDL")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (indeterminate) {
            b.setProgress(0, 0, true);
        } else {
            b.setProgress(100, percent, false);
        }
        return b.build();
    }

    private void notifyDone(boolean ok, String title, String text) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true);
        nm.notify(NOTIFY_ID + 1, b.build());
    }

    // ═══════════════════════════════════════════════════════════════
    //  [回调] Listener 派发（切主线程 + 判空）
    // ═══════════════════════════════════════════════════════════════

    /** 统一派发：界面不在前台时静默丢弃，避免服务比界面活得久时 NPE。 */
    private void dispatch(Consumer<Listener> call) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener ll = listener;
                if (ll != null) {
                    call.accept(ll);
                }
            }
        });
    }

    private void notifyParsed(final boolean ok, final String title, final String sub, final String error) {
        dispatch(new Consumer<Listener>() {
            @Override
            public void accept(Listener l) {
                l.onParsed(ok, title, sub, error);
            }
        });
    }

    private void progress(final long read, final long total) {
        dispatch(new Consumer<Listener>() {
            @Override
            public void accept(Listener l) {
                l.onProgress(read, total, curIndex, curCount);
            }
        });
    }

    private void notifyFinished(final boolean ok, final String message) {
        dispatch(new Consumer<Listener>() {
            @Override
            public void accept(Listener l) {
                l.onFinished(ok, message);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  [工具] 人类可读字节数
    // ═══════════════════════════════════════════════════════════════

    /** 字节数 -> "12.3MB" 形式（MainActivity 小窗与完成通知共用）。 */
    static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2fGB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.0fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }
}
