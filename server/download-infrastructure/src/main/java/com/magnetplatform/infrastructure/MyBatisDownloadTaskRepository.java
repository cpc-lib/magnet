package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.infrastructure.mapper.DownloadTaskMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisDownloadTaskRepository implements DownloadTaskRepository {

    private final DownloadTaskMapper mapper;

    public MyBatisDownloadTaskRepository(DownloadTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(DownloadTask task, List<DownloadTaskFile> files) {
        mapper.insert(task);
        files.forEach(mapper::insertFile);
    }

    @Override
    public Optional<DownloadTask> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    @Override
    public List<DownloadTaskFile> findFiles(UUID taskId) {
        return mapper.findFiles(taskId);
    }

    @Override
    public List<DownloadTask> findByUser(UUID userId, TaskStatus status, int offset, int limit) {
        return mapper.findByUser(userId, status, offset, limit);
    }

    @Override
    public long countByUser(UUID userId, TaskStatus status) {
        return mapper.countByUser(userId, status);
    }

    @Override
    public List<DownloadTask> findByStatuses(List<TaskStatus> statuses, int limit) {
        return mapper.findByStatuses(statuses, limit);
    }

    @Override
    public List<DownloadTask> findActiveByResource(UUID resourceId) {
        return mapper.findActiveByResource(resourceId);
    }

    @Override
    public boolean updateCas(DownloadTask task) {
        // task.version 已被 with* 方法 +1，CAS 匹配旧版本号
        return mapper.updateCas(task, task.getVersion() - 1) == 1;
    }
}
