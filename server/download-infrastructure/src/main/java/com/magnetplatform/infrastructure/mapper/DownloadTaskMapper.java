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
}
