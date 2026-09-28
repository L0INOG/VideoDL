package com.videodl.app.parser;

/**
 * 【解析】一段可下载的媒体流：主地址 + 备用地址。
 *
 * 功能: 抖音/B站/快手共用的下载单元抽象（B站 durl 分段、快手 representation 备用地址等都映射到它），
 *       由 DownloadService.saveSegments 统一消费。
 */
public final class MediaSeg {

    public final String url;
    public final String backup;

    public MediaSeg(String url, String backup) {
        this.url = url;
        this.backup = backup;
    }
}
