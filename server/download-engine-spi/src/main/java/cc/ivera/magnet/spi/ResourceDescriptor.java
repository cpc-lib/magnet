package cc.ivera.magnet.spi;

import java.util.List;

/**
 * 不可变资源身份与已验证元数据描述。
 * BT hash：btih 20 字节 SHA-1，btmh 32 字节 SHA-256，至少一个非空；HTTP 使用 metadataUri 和 outputFilename。
 */
public record ResourceDescriptor(String resourceId,
                                 byte[] btih,
                                 byte[] btmh,
                                 String versionType,
                                 String metadataUri,
                                 List<String> trackers,
                                 byte[] torrentFile,
                                 String storagePath,
                                 byte[] resumeData,
                                 String outputFilename) {
    public ResourceDescriptor(String resourceId, byte[] btih, byte[] btmh, String versionType,
                              String metadataUri, List<String> trackers, byte[] torrentFile,
                              String storagePath, byte[] resumeData) {
        this(resourceId, btih, btmh, versionType, metadataUri, trackers, torrentFile,
                storagePath, resumeData, null);
    }
}
