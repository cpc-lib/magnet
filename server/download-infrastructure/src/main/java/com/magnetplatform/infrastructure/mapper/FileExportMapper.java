package com.magnetplatform.infrastructure.mapper;

import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface FileExportMapper {

    void insert(FileExport export);

    List<FileExport> findByTask(@Param("taskId") UUID taskId);

    List<FileExport> findOwned(@Param("userId") UUID userId, @Param("taskId") UUID taskId);

    void updateState(@Param("id") UUID id, @Param("state") ExportState state,
                     @Param("bytes") long bytes, @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("updatedAt") LocalDateTime updatedAt);

    void deleteOwnedByTask(@Param("userId") UUID userId, @Param("taskId") UUID taskId);
}
