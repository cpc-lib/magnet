package cc.ivera.magnet.spi;

import java.util.Set;

/**
 * 下载引擎抽象（规格 §7）。
 * setDemand 接收同一资源所有活跃用户选择的文件下标并集；
 * 每次 Worker 命令携带 commandId/resourceId/ownerEpoch/deadline 以去重和拒绝过期执行。
 */
public interface DownloadEngine {

    EngineKind kind();

    EngineCapabilities capabilities();

    /** 不可达的 Worker 不得凭控制面仍存活而持续续租。 */
    default boolean available() {
        return true;
    }

    EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options);

    /** 已在数据库提交的续租令牌；适配器后续命令不得延长此截止时间。 */
    default void renewLease(String engineSessionId, long ownerEpoch, java.time.Instant leaseUntil) {
    }

    void setDemand(String engineSessionId, Set<Integer> requestedFileIndices);

    void pauseSession(String engineSessionId);

    void resumeSession(String engineSessionId);

    EngineSnapshot snapshot(String engineSessionId);

    ResumeSnapshot saveResume(String engineSessionId);

    void removeSession(String engineSessionId, boolean removeUnreferencedData);

    /** 轮询已解析的元数据：就绪返回 .torrent 字节，未就绪/不支持返回空数组（Magnet RESOLVING 回填用） */
    default byte[] fetchMetadata(String engineSessionId) {
        return new byte[0];
    }
}
