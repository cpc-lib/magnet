package cc.ivera.magnet.application;

import cc.ivera.magnet.domain.resource.TorrentFileInfo;
import cc.ivera.magnet.domain.resource.TorrentResource;

import java.util.List;

/** 资源详情 + 已验证文件清单 */
public record ResourceDetail(TorrentResource resource, List<TorrentFileInfo> files) {
}
