package com.magnetplatform.infrastructure.mapper;

import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskStatus;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface DownloadTaskMapper {

    void insert(DownloadTask task);

    void insertFile(DownloadTaskFile file);

    DownloadTask findById(@Param("id") UUID id);

    List<DownloadTaskFile> findFiles(@Param("taskId") UUID taskId);

    List<DownloadTask> findByUser(@Param("userId") UUID userId,
                                  @Param("status") TaskStatus status,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    long countByUser(@Param("userId") UUID userId, @Param("status") TaskStatus status);

    List<DownloadTask> findByStatuses(@Param("statuses") List<TaskStatus> statuses,
                                      @Param("limit") int limit);

    List<DownloadTask> findActiveByResource(@Param("resourceId") UUID resourceId);

    /** CAS：WHERE id AND version=expectedVersion，返回影响行数 */
    int updateCas(@Param("task") DownloadTask task, @Param("expectedVersion") long expectedVersion);

    /** 单文件已校验字节 CAS 更新：仅当新值不小于旧值时写入 */
    void updateFileProgress(@Param("taskId") UUID taskId,
                            @Param("fileIndex") int fileIndex,
                            @Param("verifiedBytes") long verifiedBytes);

    long countNonDeletedByResource(@Param("resourceId") UUID resourceId);

    List<com.magnetplatform.application.TaskStatusEngineCount> countByStatusAndEngine();
}
