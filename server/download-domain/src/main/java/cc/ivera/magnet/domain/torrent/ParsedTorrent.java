package cc.ivera.magnet.domain.torrent;

import cc.ivera.magnet.domain.resource.VersionType;

import java.util.List;

/** .torrent 元数据解析结果（可信元数据；规格 §3.1） */
public record ParsedTorrent(byte[] infoHashV1,
                            byte[] infoHashV2,
                            VersionType versionType,
                            String name,
                            long pieceLength,
                            long totalBytes,
                            boolean privateFlag,
                            List<String> trackers,
                            List<ParsedTorrentFile> files) {
}
