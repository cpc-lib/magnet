package cc.ivera.magnet.domain.resource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class TorrentResource {

    private final UUID id;
    private final String canonicalKey;
    private final byte[] btih;
    private final byte[] btmh;
    private final VersionType versionType;
    private final String name;
    private final Long pieceLength;
    private final Long totalBytes;
    private final boolean privateFlag;
    private final String metadataSource;
    private final List<String> trackers;
    private final Integer fileCount;
    private final LocalDateTime createdAt;
    private final String metadataPath;

    /** MyBatis 用：匹配 MyBatis 实际从 ResultSet 加载的 wrapper 类型（Byte[], Boolean） */
    public TorrentResource(UUID id, String canonicalKey, Byte[] btih, Byte[] btmh,
                           VersionType versionType, String name, Long pieceLength, Long totalBytes,
                           Boolean privateFlag, String metadataSource, List<String> trackers,
                           Integer fileCount, LocalDateTime createdAt) {
        this(id, canonicalKey, btih, btmh, versionType, name, pieceLength, totalBytes,
                privateFlag, metadataSource, trackers, fileCount, createdAt, null);
    }

    /** MyBatis 用：匹配 MyBatis 实际从 ResultSet 加载的 wrapper 类型（Byte[], Boolean） */
    public TorrentResource(UUID id, String canonicalKey, Byte[] btih, Byte[] btmh,
                           VersionType versionType, String name, Long pieceLength, Long totalBytes,
                           Boolean privateFlag, String metadataSource, List<String> trackers,
                           Integer fileCount, LocalDateTime createdAt, String metadataPath) {
        this.id = id;
        this.canonicalKey = canonicalKey;
        this.btih = btih == null ? null : unwrap(btih);
        this.btmh = btmh == null ? null : unwrap(btmh);
        this.versionType = versionType;
        this.name = name;
        this.pieceLength = pieceLength;
        this.totalBytes = totalBytes;
        this.privateFlag = privateFlag != null && privateFlag;
        this.metadataSource = metadataSource;
        this.trackers = trackers;
        this.fileCount = fileCount;
        this.createdAt = createdAt;
        this.metadataPath = metadataPath;
    }

    /** 应用层用：primitive 入参 */
    public TorrentResource(UUID id, String canonicalKey, byte[] btih, byte[] btmh,
                           VersionType versionType, String name, Long pieceLength, Long totalBytes,
                           boolean privateFlag, String metadataSource, List<String> trackers,
                           Integer fileCount, LocalDateTime createdAt) {
        this(id, canonicalKey, btih, btmh, versionType, name, pieceLength, totalBytes,
                privateFlag, metadataSource, trackers, fileCount, createdAt, null);
    }

    /** 应用层用：primitive 入参 */
    public TorrentResource(UUID id, String canonicalKey, byte[] btih, byte[] btmh,
                           VersionType versionType, String name, Long pieceLength, Long totalBytes,
                           boolean privateFlag, String metadataSource, List<String> trackers,
                           Integer fileCount, LocalDateTime createdAt, String metadataPath) {
        this.id = id;
        this.canonicalKey = canonicalKey;
        this.btih = btih;
        this.btmh = btmh;
        this.versionType = versionType;
        this.name = name;
        this.pieceLength = pieceLength;
        this.totalBytes = totalBytes;
        this.privateFlag = privateFlag;
        this.metadataSource = metadataSource;
        this.trackers = trackers;
        this.fileCount = fileCount;
        this.createdAt = createdAt;
        this.metadataPath = metadataPath;
    }

    private static byte[] unwrap(Byte[] wrapped) {
        byte[] r = new byte[wrapped.length];
        for (int i = 0; i < wrapped.length; i++) r[i] = wrapped[i];
        return r;
    }

    public UUID getId() { return id; }
    public String getCanonicalKey() { return canonicalKey; }
    public byte[] getBtih() { return btih; }
    public byte[] getBtmh() { return btmh; }
    public VersionType getVersionType() { return versionType; }
    public String getName() { return name; }
    public Long getPieceLength() { return pieceLength; }
    public Long getTotalBytes() { return totalBytes; }
    public boolean isPrivateFlag() { return privateFlag; }
    public String getMetadataSource() { return metadataSource; }
    public List<String> getTrackers() { return trackers; }
    public Integer getFileCount() { return fileCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getMetadataPath() { return metadataPath; }

    public boolean metadataReady() { return fileCount != null; }
}
