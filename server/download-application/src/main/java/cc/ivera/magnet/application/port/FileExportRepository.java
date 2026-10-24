package cc.ivera.magnet.application.port;

import cc.ivera.magnet.domain.export.ExportState;
import cc.ivera.magnet.domain.export.FileExport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface FileExportRepository {

    void insert(FileExport export);

    List<FileExport> findByTask(UUID taskId);

    List<FileExport> findOwned(UUID userId, UUID taskId);

    void updateState(UUID id, ExportState state, long bytes, String errorCode,
                     String errorMessage, String targetPath, LocalDateTime updatedAt);

    void deleteOwnedByTask(UUID userId, UUID taskId);

    /** 资源下非 DELETED 导出数（GC 判定：为 0 才允许回收共享数据，规格 §10.2） */
    long countActiveByResource(UUID resourceId);
}
