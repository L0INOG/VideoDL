/**
 * 网络传输层：HTTP 内核与需要 Android 环境的网络辅助组件。
 *
 * <ul>
 *   <li>{@link com.videodl.app.net.Http} — 纯 Java 的 HTTP 内核：
 *       手动重定向跟随、响应排空、gzip 读取、首字节判死超时。
 *       parser 包的三个平台引擎全部经由它发起请求。</li>
 *   <li>{@link com.videodl.app.net.Line2Loader} — 抖音"线路2"的 WebView cookie 预热器
 *       （本包内唯一依赖 android.webkit 的类，不参与桌面自测）。</li>
 * </ul>
 */
package com.videodl.app.net;
