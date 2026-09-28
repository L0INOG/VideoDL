package com.videodl.app.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.videodl.app.parser.DouyinParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【网络】抖音"线路2"的 cookie 预热器。
 *
 * 功能: 用一个隐藏 WebView 打开抖音主站，让真实浏览器环境自己种下
 *       ttwid / s_v_web_id / msToken 等 cookie，再把这些 cookie 交给
 *       纯 HTTP 的 detail 接口请求（Argus 风控校验的是浏览器指纹）。
 * 链路: DownloadService 抖音解析失败 → 本类预热 → DouyinParser.fetchDetail(cookies)
 * 依赖: android.webkit（本类是 net 包里唯一依赖 Android 的成员，
 *       Http 内核与 parser 包仍保持纯 java 可桌面自测）。
 * 约定: 必须与 DouyinParser.UA_ANDROID 保持同一 UA，保证请求指纹一致。
 */
public final class Line2Loader {

    public interface Callback {
        void onCookies(String cookies);
    }

    private static final String WARM_URL = "https://www.douyin.com/";
    private static final String COOKIE_URL = "https://www.douyin.com/";
    private static final long POLL_INTERVAL_MS = 500;
    /** 异步预热总预算：超时把手上已有的 cookie 交给调用方 */
    private static final long WARM_BUDGET_MS = 20_000;

    private Line2Loader() {
    }

    /**
     * 阻塞版预热，供后台线程调用。超时返回已拿到的 cookie（可能不完整）或 null。
     */
    public static String warmBlocking(Context context, long timeoutMs) {
        final String[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        warm(context, new Callback() {
            @Override
            public void onCookies(String cookies) {
                out[0] = cookies;
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /**
     * 异步预热。任何情况下最终都会回调一次（成功带 cookie 串、失败带 null）。
     */
    public static void warm(Context context, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = null;
                try {
                    wv = new WebView(context);
                    final WebView view = wv;
                    android.webkit.WebSettings s = view.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setBlockNetworkImage(true);
                    s.setUserAgentString(DouyinParser.UA_ANDROID);

                    final CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setAcceptThirdPartyCookies(view, true);
                    cm.removeSessionCookies(null);
                    cm.setAcceptFileSchemeCookies(false);

                    final long deadline = System.currentTimeMillis() + WARM_BUDGET_MS;
                    final boolean[] done = {false};

                    final Runnable finish = new Runnable() {
                        @Override
                        public void run() {
                            if (done[0]) {
                                return;
                            }
                            done[0] = true;
                            String c = cm.getCookie(COOKIE_URL);
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        view.destroy();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                            cb.onCookies(c);
                        }
                    };

                    final Runnable check = new Runnable() {
                        @Override
                        public void run() {
                            if (done[0]) {
                                return;
                            }
                            String c = cm.getCookie(COOKIE_URL);
                            boolean hasCore = c != null && c.contains("ttwid=");
                            // msToken 由页面 JS 延迟种下，有它更稳，但不强求
                            if (hasCore && (c.contains("msToken=") || System.currentTimeMillis() > deadline)) {
                                finish.run();
                                return;
                            }
                            if (System.currentTimeMillis() > deadline) {
                                finish.run();
                                return;
                            }
                            main.postDelayed(this, POLL_INTERVAL_MS);
                        }
                    };

                    view.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView v, String url) {
                            main.postDelayed(check, 300);
                        }
                    });
                    view.loadUrl(WARM_URL);
                    main.postDelayed(check, 1500);
                } catch (Throwable t) {
                    cb.onCookies(null);
                }
            }
        });
    }
}
