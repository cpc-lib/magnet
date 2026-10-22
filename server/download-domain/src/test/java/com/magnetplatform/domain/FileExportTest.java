package com.magnetplatform.domain;

import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileExportTest {

    @Test
    void storesExportContractFields() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);

        FileExport export = new FileExport(id, taskId, userId, resourceId, 3,
                "dir/file.bin", "exports/file.bin", ExportState.READY, 42L,
                null, null, now, now);

        assertEquals(id, export.getId());
        assertEquals(taskId, export.getTaskId());
        assertEquals(userId, export.getUserId());
        assertEquals(resourceId, export.getResourceId());
        assertEquals(3, export.getFileIndex());
        assertEquals("dir/file.bin", export.getRelativePath());
        assertEquals("exports/file.bin", export.getTargetPath());
        assertEquals(ExportState.READY, export.getState());
        assertEquals(42L, export.getBytes());
        assertEquals(now, export.getCreatedAt());
        assertEquals(now, export.getUpdatedAt());
    }

    @Test
    void exportStateValuesMatchRuntimeContract() {
        assertEquals(List.of(ExportState.PENDING, ExportState.READY,
                ExportState.FAILED, ExportState.DELETED),
                List.of(ExportState.values()));
    }
}
