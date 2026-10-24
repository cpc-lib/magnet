package cc.ivera.magnet.infrastructure.mapper;

import cc.ivera.magnet.domain.task.EngineSessionRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface EngineSessionMapper {

    EngineSessionRecord findByResource(@Param("resourceId") UUID resourceId,
                                       @Param("engineType") String engineType);

    void insert(EngineSessionRecord record);

    int updateState(@Param("id") UUID id, @Param("state") String state,
                     @Param("updatedAt") LocalDateTime updatedAt,
                     @Param("workerId") String workerId, @Param("ownerEpoch") long ownerEpoch);

    int updateResume(@Param("id") UUID id,
                      @Param("resumeVersion") long resumeVersion,
                      @Param("resumePath") String resumePath,
                      @Param("updatedAt") LocalDateTime updatedAt,
                      @Param("workerId") String workerId, @Param("ownerEpoch") long ownerEpoch);

    List<EngineSessionRecord> findRecoverable();

    EngineSessionRecord lockOwned(EngineSessionRecord expected);

    UUID lockResource(@Param("resourceId") UUID resourceId);

    /** 幂等占位插入（INSERT IGNORE）：行已存在时不影响原记录 */
    int insertIfAbsent(EngineSessionRecord record);

    /** 续租 CAS（MySQL 时间源）：worker_id 与 owner_epoch 均匹配才延期（规格 §12.2） */
    int renewLease(@Param("resourceId") UUID resourceId,
                   @Param("engineType") String engineType,
                   @Param("workerId") String workerId,
                   @Param("ownerEpoch") long ownerEpoch,
                   @Param("leaseSeconds") int leaseSeconds);

    /** 获取/接管租约 CAS（MySQL 时间源）：空闲/过期/本人持有才成功，epoch 单调 +1（规格 §12.2）。
     *  返回受影响行数：0 表示他人持有有效租约。 */
    int acquireLease(@Param("resourceId") UUID resourceId,
                     @Param("engineType") String engineType,
                     @Param("workerId") String workerId,
                     @Param("leaseSeconds") int leaseSeconds);
}
