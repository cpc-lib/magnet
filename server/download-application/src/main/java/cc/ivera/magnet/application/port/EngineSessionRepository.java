package cc.ivera.magnet.application.port;

import cc.ivera.magnet.domain.task.EngineSessionRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineSessionRepository {

    Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType);

    void insert(EngineSessionRecord record);

    boolean updateState(UUID id, String state, LocalDateTime updatedAt, String workerId, long ownerEpoch);

    /** 持久化 resume 版本与路径（规格 §12.1：saveResume 后落库） */
    boolean updateResume(UUID id, long resumeVersion, String resumePath, LocalDateTime updatedAt,
                         String workerId, long ownerEpoch);

    /** 重启/调度时需重新挂载到引擎的会话（规格 §12.1/§5.3） */
    List<EngineSessionRecord> findRecoverable();

    /** 与租约内 GC 共用资源行锁；新任务插入与最后一次引用核查互斥。 */
    void lockResource(UUID resourceId);

    /** 在数据库资源行锁内执行状态/Resume/GC 写入；生产实现同时检查数据库租约时间。 */
    default void withLease(EngineSessionRecord expected, Runnable work) {
        EngineSessionRecord current = findByResource(expected.getResourceId(), expected.getEngineType()).orElseThrow();
        if (!current.getWorkerId().equals(expected.getWorkerId())
                || current.getOwnerEpoch() != expected.getOwnerEpoch()) {
            throw new IllegalStateException("Resource ownership lost");
        }
        work.run();
    }

    /** 幂等占位：行不存在时插入默认记录（规格 §12.2 租约获取的前置步骤）。
     *  返回 true 表示本次调用实际插入了行。 */
    boolean insertIfAbsent(EngineSessionRecord record);

    /** 续租（MySQL 时间源 CAS，规格 §12.2）：仅当 worker_id 与 owner_epoch 均匹配时延期。
     *  返回 false 表示已失去所有权（被接管或代次过期），调用方必须停止下发写命令。 */
    boolean renewLease(UUID resourceId, String engineType, String workerId,
                       long ownerEpoch, int leaseSeconds);

    /** 获取/接管租约（MySQL 时间源 CAS）：仅当租约空闲、过期或已由本人持有时成功，
     *  成功则 worker_id 置为本人且 owner_epoch 单调 +1。空 Optional 表示他人持有有效租约。 */
    java.util.Optional<EngineSessionRecord> acquireLease(UUID resourceId, String engineType,
                                                         String workerId, int leaseSeconds);
}
