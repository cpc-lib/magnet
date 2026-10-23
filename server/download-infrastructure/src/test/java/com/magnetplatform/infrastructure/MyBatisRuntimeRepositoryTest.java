package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.ResourceGcRepository;
import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.infrastructure.mapper.FileExportMapper;
import com.magnetplatform.infrastructure.mapper.ResourceGcMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MyBatisRuntimeRepositoryTest {

    @Test
    void fileExportRepositoryDelegatesToMapper() {
        RecordingFileExportMapper mapper = new RecordingFileExportMapper();
        FileExportRepository repository = new MyBatisFileExportRepository(mapper);
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        FileExport export = new FileExport(UUID.randomUUID(), taskId, userId, UUID.randomUUID(), 1,
                "file.bin", "file.bin", ExportState.PENDING, 0L, null, null,
                LocalDateTime.MIN, LocalDateTime.MIN);
        mapper.exports = List.of(export);

        repository.insert(export);
        assertSame(export, mapper.inserted);
        assertEquals(mapper.exports, repository.findByTask(taskId));
        assertEquals(mapper.exports, repository.findOwned(userId, taskId));
        repository.updateState(export.getId(), ExportState.READY, 12L,
                null, null, "file.bin", LocalDateTime.MAX);
        assertEquals(export.getId(), mapper.updatedId);
        assertEquals(ExportState.READY, mapper.updatedState);
        repository.deleteOwnedByTask(userId, taskId);
        assertEquals(userId, mapper.deletedUserId);
        assertEquals(taskId, mapper.deletedTaskId);
    }

    @Test
    void resourceGcRepositoryDelegatesToMapper() {
        RecordingResourceGcMapper mapper = new RecordingResourceGcMapper();
        ResourceGcRepository repository = new MyBatisResourceGcRepository(mapper);
        UUID resourceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        mapper.due = List.of(resourceId);

        repository.upsertCandidate(resourceId, now, now.plusMinutes(1), now);
        assertEquals(resourceId, mapper.upsertResourceId);
        assertEquals(List.of(resourceId), repository.findDue(now, 10));
        repository.removeCandidate(resourceId);
        assertEquals(resourceId, mapper.removedResourceId);
        repository.recordFailure(resourceId, "disk", now);
        assertEquals(resourceId, mapper.failureResourceId);
        assertEquals("disk", mapper.failureMessage);
    }

    private static final class RecordingFileExportMapper implements FileExportMapper {
        private FileExport inserted;
        private List<FileExport> exports = List.of();
        private UUID updatedId;
        private ExportState updatedState;
        private UUID deletedUserId;
        private UUID deletedTaskId;

        @Override
        public void insert(FileExport export) {
            inserted = export;
        }

        @Override
        public List<FileExport> findByTask(UUID taskId) {
            return exports;
        }

        @Override
        public List<FileExport> findOwned(UUID userId, UUID taskId) {
            return exports;
        }

        @Override
        public void updateState(UUID id, ExportState state, long bytes, String errorCode,
                                String errorMessage, String targetPath, LocalDateTime updatedAt) {
            updatedId = id;
            updatedState = state;
        }

        @Override
        public void deleteOwnedByTask(UUID userId, UUID taskId) {
            deletedUserId = userId;
            deletedTaskId = taskId;
        }

        @Override
        public long countActiveByResource(UUID resourceId) {
            return 0L;
        }
    }

    private static final class RecordingResourceGcMapper implements ResourceGcMapper {
        private List<UUID> due = List.of();
        private UUID upsertResourceId;
        private UUID removedResourceId;
        private UUID failureResourceId;
        private String failureMessage;

        @Override
        public void upsertCandidate(UUID resourceId, LocalDateTime candidateAt,
                                    LocalDateTime notBefore, LocalDateTime updatedAt) {
            upsertResourceId = resourceId;
        }

        @Override
        public List<UUID> findDue(LocalDateTime now, int limit) {
            return due;
        }

        @Override
        public void removeCandidate(UUID resourceId) {
            removedResourceId = resourceId;
        }

        @Override
        public void recordFailure(UUID resourceId, String lastError, LocalDateTime updatedAt) {
            failureResourceId = resourceId;
            failureMessage = lastError;
        }
    }
}
