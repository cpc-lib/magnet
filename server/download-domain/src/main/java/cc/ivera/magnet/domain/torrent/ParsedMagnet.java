package cc.ivera.magnet.domain.torrent;

import cc.ivera.magnet.domain.resource.VersionType;

import java.util.List;

/**
 * Magnet URI 解析结果。dn 与 xl 仅是提示，不能作为可信元数据或磁盘分配依据（规格 §3.1）。
 */
public record ParsedMagnet(byte[] btih,
                           byte[] btmh,
                           List<String> trackers,
                           String displayNameHint,
                           Long exactLengthHint) {

    public VersionType versionType() {
        if (btih != null && btmh != null) {
            return VersionType.HYBRID;
        }
        return btih != null ? VersionType.V1 : VersionType.V2;
    }
}
