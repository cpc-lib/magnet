package com.magnetplatform.application.port;

import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DownloadTaskRepository {

    void insert(DownloadTask task, List<DownloadTaskFile> files);

    Optional<DownloadTask> findById(UUID id);

    List<DownloadTaskFile> findFiles(UUID taskId);

    /** 按创建时间倒序；status 为 null 表示不过滤；不含已删除 */
    List<DownloadTask> findByUser(UUID userId, TaskStatus status, int offset, int limit);

    long countByUser(UUID userId, TaskStatus status);

    /** 调度器扫描：按状态集合取任务（如 QUEUED 待启动、DOWNLOADING 待刷新） */
    List<DownloadTask> findByStatuses(List<TaskStatus> statuses, int limit);

    /** 资源维度：计算共享会话需求并集（规格 §7 setDemand） */
    List<DownloadTask> findActiveByResource(UUID resourceId);

    /** CAS 更新：WHERE id=? AND version=?，返回 false 表示版本冲突（规格 §8.1） */
    boolean updateCas(DownloadTask task);

    /** 单文件已校验字节 CAS 更新：仅当新值不小于旧值时写入（规格 §4.3/§8.1） */
    void updateFileProgress(UUID taskId, int fileIndex, long verifiedBytes);

    /** 资源下未删除任务数（GC 判定：为 0 才允许回收共享数据，规格 §10.2） */
    long countNonDeletedByResource(UUID resourceId);
}
