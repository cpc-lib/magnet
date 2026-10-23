package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.infrastructure.mapper.FileExportMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisFileExportRepository implements FileExportRepository {

    private final FileExportMapper mapper;

    public MyBatisFileExportRepository(FileExportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(FileExport export) {
        mapper.insert(export);
    }

    @Override
    public List<FileExport> findByTask(UUID taskId) {
        return mapper.findByTask(taskId);
    }

    @Override
    public List<FileExport> findOwned(UUID userId, UUID taskId) {
        return mapper.findOwned(userId, taskId);
    }

    @Override
    public void updateState(UUID id, ExportState state, long bytes, String errorCode,
                            String errorMessage, String targetPath, LocalDateTime updatedAt) {
        mapper.updateState(id, state, bytes, errorCode, errorMessage, targetPath, updatedAt);
    }

    @Override
    public void deleteOwnedByTask(UUID userId, UUID taskId) {
        mapper.deleteOwnedByTask(userId, taskId);
    }

    @Override
    public long countActiveByResource(UUID resourceId) {
        return mapper.countActiveByResource(resourceId);
    }
}
