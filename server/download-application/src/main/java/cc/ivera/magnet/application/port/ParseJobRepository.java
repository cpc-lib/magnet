package cc.ivera.magnet.application.port;

import cc.ivera.magnet.domain.parse.ParseJob;
import cc.ivera.magnet.domain.parse.ParseStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParseJobRepository {

    void insert(ParseJob job);

    Optional<ParseJob> findById(UUID id);

    /** 按创建时间倒序；status 为 null 表示不过滤 */
    List<ParseJob> findByUser(UUID userId, ParseStatus status, int offset, int limit);

    long countByUser(UUID userId, ParseStatus status);

    void markStatus(UUID id, ParseStatus status, String errorCode, String errorMessage);

    boolean existsByUserAndResource(UUID userId, UUID resourceId);

    /** RESOLVING 且 updatedAt 早于 cutoff 的任务置为 TIMEOUT，返回影响行数（调度器用） */
    int markTimeoutResolving(LocalDateTime cutoff);

    /** 按状态拉取任务（元数据回填轮询用），按 updatedAt 升序限 limit 条 */
    List<ParseJob> findByStatus(ParseStatus status, int limit);
}
