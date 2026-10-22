package com.magnetplatform.spi;

import java.util.List;

/**
 * 不可变 Torrent 身份与已验证元数据描述。
 * hash 字段：btih 20 字节 SHA-1，btmh 32 字节 SHA-256，均可空但至少一个非空。
 */
public record ResourceDescriptor(String resourceId,
                                 byte[] btih,
                                 byte[] btmh,
                                 String versionType,
                                 String metadataUri,
                                 List<String> trackers,
                                 byte[] torrentFile,
                                 String storagePath,
                                 byte[] resumeData) {
}
