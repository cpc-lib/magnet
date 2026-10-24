package cc.ivera.magnet.spi;

/**
 * @param metadataOnly  仅解析元数据，不下载内容；会话按 TTL 释放网络资源（规格 §4.2-6）
 * @param storageDomain 安全存储域；同域同资源才允许共享底层会话
 * @param ownerEpoch   本实例对资源的租约代次（规格 §12.2：MySQL 单调递增 owner_epoch，
 *                     随命令下发用于去重与过期拒绝；无租约语义的解析场景传 0）
 */
public record EngineOptions(boolean metadataOnly,
                            String storageDomain,
                            long ownerEpoch,
                            java.time.Instant leaseUntil) {

    public EngineOptions(boolean metadataOnly, String storageDomain, long ownerEpoch) {
        this(metadataOnly, storageDomain, ownerEpoch,
                metadataOnly ? java.time.Instant.now().plusSeconds(180) : java.time.Instant.EPOCH);
    }

    public static EngineOptions resolveOnly() {
        return new EngineOptions(true, "default", 0L);
    }
}
