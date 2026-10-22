package com.magnetplatform.application;

import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;

import java.util.List;

/** 资源详情 + 已验证文件清单 */
public record ResourceDetail(TorrentResource resource, List<TorrentFileInfo> files) {
}
