package com.magnetplatform.application.port;

import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface FileExportRepository {

    void insert(FileExport export);

    List<FileExport> findByTask(UUID taskId);

    List<FileExport> findOwned(UUID userId, UUID taskId);

    void updateState(UUID id, ExportState state, long bytes, String errorCode,
                     String errorMessage, LocalDateTime updatedAt);

    void deleteOwnedByTask(UUID userId, UUID taskId);
}
