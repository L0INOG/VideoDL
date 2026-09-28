/**
 * 平台解析引擎层：把分享链接解析成可直接下载的媒体地址。
 *
 * <p>本包内所有类均为<b>纯 Java</b>（只依赖 java.net / java.util.regex / org.json），
 * 不允许 import 任何 android.* 类——保证整个解析层可以在桌面 JVM 上独立运行自测
 * （见 tests/ 目录与 tools/run-tests.sh）。</p>
 *
 * <p>类职责速览：</p>
 * <ul>
 *   <li>{@link com.videodl.app.parser.LinkRouter}  — 平台识别：分享文案 → 提取链接 → 判定平台</li>
 *   <li>{@link com.videodl.app.parser.DouyinParser} — 抖音：短链 → aweme_id → 分享页/detail 接口 → 无水印直链</li>
 *   <li>{@link com.videodl.app.parser.BiliParser}   — B站：BV号 → wbi 签名 → view/playurl → durl 分段</li>
 *   <li>{@link com.videodl.app.parser.KsParser}     — 快手：短链 → photoId → SSR 页 INIT_STATE → 分档地址</li>
 *   <li>{@link com.videodl.app.parser.XhsParser}    — 小红书：短链 → 笔记页(xsec_token) → INITIAL_STATE → 视频/图文</li>
 *   <li>{@link com.videodl.app.parser.MiniJson}     — 容错 JSON 解析器，仅供小红书 INITIAL_STATE（JS 字面量）使用</li>
 *   <li>{@link com.videodl.app.parser.MediaSeg}     — 一段可下载媒体流（主地址 + 备用地址），三平台通用</li>
 *   <li>{@link com.videodl.app.parser.MediaSniff}   — 媒体魔数校验，防止把风控 JSON/错误页存成媒体文件</li>
 * </ul>
 *
 * <p>扩展新平台时：新建 XxxParser 并在 LinkRouter 与 DownloadService 的两处
 * 平台分发点登记（详见项目 README 的"如何新增平台"）。</p>
 */
package com.videodl.app.parser;
