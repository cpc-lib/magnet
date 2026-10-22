package com.magnetplatform.spi;

/**
 * @param metadataOnly  仅解析元数据，不下载内容；会话按 TTL 释放网络资源（规格 §4.2-6）
 * @param storageDomain 安全存储域；同域同资源才允许共享底层会话
 */
public record EngineOptions(boolean metadataOnly,
                            String storageDomain) {

    public static EngineOptions resolveOnly() {
        return new EngineOptions(true, "default");
    }
}
