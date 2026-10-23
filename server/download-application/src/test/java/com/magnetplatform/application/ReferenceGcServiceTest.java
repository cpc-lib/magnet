package com.magnetplatform.application;

import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.ResourceGcRepository;
import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineCapabilities;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import com.magnetplatform.spi.ResumeSnapshot;
import com.magnetplatform.spi.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceGcServiceTest {

    static class InMemoryGcRepo implements ResourceGcRepository {
        final Map<UUID, LocalDateTime> notBefore = new HashMap<>();
        final Map<UUID, String> failures = new HashMap<>();
        final List<UUID> removed = new ArrayList<>();

        public void upsertCandidate(UUID resourceId, LocalDateTime candidateAt,
                                    LocalDateTime notBeforeTime, LocalDateTime updatedAt) {
            notBefore.put(resourceId, notBeforeTime);
            failures.remove(resourceId);
        }

        public List<UUID> findDue(LocalDateTime now, int limit) {
            return notBefore.entrySet().stream()
                    .filter(e -> !e.getValue().isAfter(now))
                    .map(Map.Entry::getKey)
                    .limit(limit)
                    .toList();
        }

        public void removeCandidate(UUID resourceId) {
            notBefore.remove(resourceId);
            removed.add(resourceId);
        }

        public void recordFailure(UUID resourceId, String lastError, LocalDateTime updatedAt) {
            failures.put(resourceId, lastError);
        }
    }

    static class CountingTasks implements DownloadTaskRepository {
        long nonDeleted;

        public void insert(com.magnetplatform.domain.task.DownloadTask task,
                           List<com.magnetplatform.domain.task.DownloadTaskFile> files) {
        }

        public Optional<com.magnetplatform.domain.task.DownloadTask> findById(UUID id) {
            return Optional.empty();
        }

        public List<com.magnetplatform.domain.task.DownloadTaskFile> findFiles(UUID taskId) {
            return List.of();
        }

        public List<com.magnetplatform.domain.task.DownloadTask> findByUser(UUID userId, TaskStatus status,
                                                                             int offset, int limit) {
            return List.of();
        }

        public long countByUser(UUID userId, TaskStatus status) {
            return 0;
        }

        public List<com.magnetplatform.domain.task.DownloadTask> findByStatuses(List<TaskStatus> statuses, int limit) {
            return List.of();
        }

        public List<com.magnetplatform.domain.task.DownloadTask> findActiveByResource(UUID resourceId) {
            return List.of();
        }

        public boolean updateCas(com.magnetplatform.domain.task.DownloadTask task) {
            return true;
        }

        public void updateFileProgress(UUID taskId, int fileIndex, long verifiedBytes) {
        }

        public long countNonDeletedByResource(UUID resourceId) {
            return nonDeleted;
        }
    }

    static class CountingExports implements FileExportRepository {
        long active;

        public void insert(FileExport export) {
        }

        public List<FileExport> findByTask(UUID taskId) {
            return List.of();
        }

        public List<FileExport> findOwned(UUID userId, UUID taskId) {
            return List.of();
        }

        public void updateState(UUID id, ExportState state, long bytes, String errorCode,
                                String errorMessage, LocalDateTime updatedAt) {
        }

        public void deleteOwnedByTask(UUID userId, UUID taskId) {
        }

        public long countActiveByResource(UUID resourceId) {
            return active;
        }
    }

    static class StubSessions implements EngineSessionRepository {
        final Map<UUID, EngineSessionRecord> byResource = new HashMap<>();

        public Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType) {
            return Optional.ofNullable(byResource.get(resourceId));
        }

        public void insert(EngineSessionRecord record) {
        }

        public void updateState(UUID id, String state, LocalDateTime updatedAt) {
        }

        public void updateResume(UUID id, long resumeVersion, String resumePath, LocalDateTime updatedAt) {
        }

        public List<EngineSessionRecord> findRecoverable() {
            return new ArrayList<>(byResource.values());
        }
    }

    static class FakeStorage implements DownloadStorage {
        boolean treeDeleted;

        public Path resourceRoot(UUID resourceId) {
            return Path.of("storage", "resources", resourceId.toString());
        }

        public Path resumeFile(UUID resourceId) {
            return Path.of("storage", "resume", resourceId + ".dat");
        }

        public Path exportRoot(UUID userId, UUID taskId) {
            return Path.of("storage", "exports", userId.toString(), taskId.toString());
        }

        public Path resolveResourceFile(UUID resourceId, String relativePath) {
            return resourceRoot(resourceId).resolve(relativePath);
        }

        public void writeResumeAtomically(UUID resourceId, byte[] bytes) {
        }

        public Optional<byte[]> readResume(UUID resourceId) {
            return Optional.empty();
        }

        public void materialize(Path source, Path target) {
        }

        public void deleteTree(Path root) {
            treeDeleted = true;
        }
    }

    static class RecordingEngine implements DownloadEngine {
        boolean removeCalled;
        boolean removeData;

        public EngineKind kind() {
            return EngineKind.LIBTORRENT;
        }

        public EngineCapabilities capabilities() {
            return new EngineCapabilities(true, true, true, false, true, true);
        }

        public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
            return new EngineSession(resource.resourceId(), EngineKind.LIBTORRENT, SessionState.DISCOVERING);
        }

        public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        }

        public void pauseSession(String engineSessionId) {
        }

        public void resumeSession(String engineSessionId) {
        }

        public EngineSnapshot snapshot(String engineSessionId) {
            return EngineSnapshot.empty(engineSessionId);
        }

        public ResumeSnapshot saveResume(String engineSessionId) {
            return new ResumeSnapshot(engineSessionId, 0L, new byte[0]);
        }

        public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
            removeCalled = true;
            removeData = removeUnreferencedData;
        }
    }

    private CountingTasks tasks;
    private CountingExports exports;
    private InMemoryGcRepo gc;
    private StubSessions sessions;
    private FakeStorage storage;
    private RecordingEngine engine;
    private ReferenceGcService service;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        tasks = new CountingTasks();
        exports = new CountingExports();
        gc = new InMemoryGcRepo();
        sessions = new StubSessions();
        storage = new FakeStorage();
        engine = new RecordingEngine();
        service = new ReferenceGcService(tasks, exports, gc, sessions, storage, List.of(engine), 0L);
        resourceId = UUID.randomUUID();
        sessions.byResource.put(resourceId, new EngineSessionRecord(UUID.randomUUID(), resourceId,
                "w1", EngineKind.LIBTORRENT.name(), SessionState.IDLE.name(), 1L, null, 0L,
                LocalDateTime.now(), LocalDateTime.now()));
    }

    @Test
    void marksCandidateOnlyWhenNoTasksAndNoExports() {
        tasks.nonDeleted = 1;
        exports.active = 0;
        service.markCandidates();
        assertTrue(gc.notBefore.isEmpty(), "有任务时不应标记 GC 候选");

        tasks.nonDeleted = 0;
        exports.active = 1;
        service.markCandidates();
        assertTrue(gc.notBefore.isEmpty(), "有活跃导出时不应标记 GC 候选");

        tasks.nonDeleted = 0;
        exports.active = 0;
        service.markCandidates();
        assertTrue(gc.notBefore.containsKey(resourceId), "无引用时应标记 GC 候选");
    }

    @Test
    void sweepRemovesSessionAndDataWhenStillUnreferenced() {
        tasks.nonDeleted = 0;
        exports.active = 0;
        service.markCandidates();
        service.sweep();

        assertTrue(engine.removeCalled, "应调用引擎 removeSession");
        assertTrue(engine.removeData, "应删除共享数据");
        assertTrue(storage.treeDeleted, "应删除资源物理目录");
        assertTrue(gc.removed.contains(resourceId), "应移除 GC 候选");
    }

    @Test
    void sweepCancelsCandidateWhenReferenceReappears() {
        tasks.nonDeleted = 0;
        exports.active = 0;
        service.markCandidates();

        tasks.nonDeleted = 1; // 宽限期内有新任务引用
        service.sweep();

        assertFalse(engine.removeCalled, "有新引用时不应删除数据");
        assertFalse(storage.treeDeleted, "有新引用时不应删除物理目录");
        assertTrue(gc.removed.contains(resourceId), "应取消候选（移除而不是删除数据）");
    }
}
