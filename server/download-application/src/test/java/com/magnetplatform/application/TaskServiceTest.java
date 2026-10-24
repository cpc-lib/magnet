package com.magnetplatform.application;

import java.util.stream.Collectors;

import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.application.port.ParseJobRepository;
import com.magnetplatform.application.port.TaskEventRepository;
import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.application.port.TorrentStorage;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.parse.InputType;
import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.parse.ParseStatus;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.domain.task.TaskEvent;
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
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {

    // ---------- 内存假实现 ----------

    static class InMemoryTasks implements DownloadTaskRepository {
        final Map<UUID, DownloadTask> byId = new LinkedHashMap<>();
        final Map<UUID, List<DownloadTaskFile>> files = new HashMap<>();

        public void insert(DownloadTask task, List<DownloadTaskFile> taskFiles) {
            byId.put(task.getId(), task);
            files.put(task.getId(), new ArrayList<>(taskFiles));
        }

        public Optional<DownloadTask> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public List<DownloadTaskFile> findFiles(UUID taskId) {
            return files.getOrDefault(taskId, List.of());
        }

        public List<DownloadTask> findByUser(UUID userId, TaskStatus status, int offset, int limit) {
            return byId.values().stream()
                    .filter(t -> t.getUserId().equals(userId) && t.getDeletedAt() == null)
                    .filter(t -> status == null || t.getStatus() == status)
                    .sorted(Comparator.comparing(DownloadTask::getCreatedAt).reversed())
                    .skip(offset).limit(limit).toList();
        }

        public long countByUser(UUID userId, TaskStatus status) {
            return byId.values().stream()
                    .filter(t -> t.getUserId().equals(userId) && t.getDeletedAt() == null)
                    .filter(t -> status == null || t.getStatus() == status)
                    .count();
        }

        public List<DownloadTask> findByStatuses(List<TaskStatus> statuses, int limit) {
            return byId.values().stream()
                    .filter(t -> statuses.contains(t.getStatus()) && t.getDeletedAt() == null)
                    .limit(limit).toList();
        }

        public List<DownloadTask> findActiveByResource(UUID resourceId) {
            return byId.values().stream()
                    .filter(t -> t.getResourceId().equals(resourceId) && t.getDeletedAt() == null)
                    .filter(t -> t.getStatus().contributesDemand())
                    .toList();
        }

        public boolean updateCas(DownloadTask task) {
            DownloadTask current = byId.get(task.getId());
            if (current == null || current.getVersion() != task.getVersion() - 1) {
                return false;
            }
            byId.put(task.getId(), task);
            return true;
        }

        public List<TaskStatusEngineCount> countByStatusAndEngine() {
            return byId.values().stream()
                    .filter(t -> t.getDeletedAt() == null)
                    .collect(Collectors.groupingBy(
                            DownloadTask::getStatus,
                            Collectors.counting()))
                    .entrySet().stream()
                    .map(e -> new TaskStatusEngineCount(e.getKey(), EngineKind.LIBTORRENT, e.getValue()))
                    .toList();
        }

        public void updateFileProgress(UUID taskId, int fileIndex, long verifiedBytes) {
            List<DownloadTaskFile> list = files.get(taskId);
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                DownloadTaskFile f = list.get(i);
                if (f.getFileIndex() == fileIndex) {
                    list.set(i, new DownloadTaskFile(f.getId(), f.getTaskId(), f.getFileIndex(),
                            f.isSelected(), verifiedBytes, f.getExportState()));
                    return;
                }
            }
        }

        public long countNonDeletedByResource(UUID resourceId) {
            return byId.values().stream()
                    .filter(t -> t.getResourceId().equals(resourceId) && t.getDeletedAt() == null)
                    .count();
        }
    }

    static class InMemoryEvents implements TaskEventRepository {
        final Map<UUID, List<TaskEvent>> byTask = new HashMap<>();

        public void append(UUID taskId, String eventType, String payloadJson) {
            List<TaskEvent> list = byTask.computeIfAbsent(taskId, k -> new ArrayList<>());
            list.add(new TaskEvent(UUID.randomUUID(), taskId, list.size() + 1L, eventType, payloadJson,
                    LocalDateTime.now()));
        }

        public List<TaskEvent> findAfter(UUID taskId, long afterSeq, int limit) {
            return byTask.getOrDefault(taskId, List.of()).stream()
                    .filter(e -> e.getSeq() > afterSeq).limit(limit).toList();
        }
    }

    static class FakeTorrentStorage implements TorrentStorage {
        final String path = "uploads/demo.torrent";
        final byte[] bytes = new byte[]{1, 2, 3};

        public String saveTorrent(byte[] bytes) {
            return path;
        }

        public Optional<byte[]> loadTorrent(String relativePath) {
            return path.equals(relativePath) ? Optional.of(bytes) : Optional.empty();
        }
    }

    static class FakeDownloadStorage implements DownloadStorage {
        boolean writeResumeCalled;

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
            writeResumeCalled = true;
        }

        public Optional<byte[]> readResume(UUID resourceId) {
            return Optional.empty();
        }

        public void materialize(Path source, Path target) {
        }

        public void deleteTree(Path root) {
        }
    }

    static class RecordingEngine implements DownloadEngine {
        boolean available = true;

        public boolean available() { return available; }
        ResourceDescriptor resource;
        EngineOptions lastOptions;
        int createCalls;
        Set<Integer> lastDemand;
        boolean paused;
        boolean resumed;
        EngineSnapshot snapshot = EngineSnapshot.empty("s");
        ResumeSnapshot resume = new ResumeSnapshot("s", 1L, new byte[]{1, 2, 3});

        public EngineKind kind() {
            return EngineKind.LIBTORRENT;
        }

        public EngineCapabilities capabilities() {
            return new EngineCapabilities(true, true, true, false, true, true);
        }

        public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
            createCalls++;
            this.resource = resource;
            this.lastOptions = options;
            return new EngineSession(resource.resourceId(), EngineKind.LIBTORRENT, SessionState.DISCOVERING);
        }

        public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
            lastDemand = requestedFileIndices;
        }

        public void pauseSession(String engineSessionId) {
            paused = true;
        }

        public void resumeSession(String engineSessionId) {
            resumed = true;
        }

        public java.util.function.Function<String, EngineSnapshot> snapshotFn;

        public EngineSnapshot snapshot(String engineSessionId) {
            if (snapshotFn != null) {
                return snapshotFn.apply(engineSessionId);
            }
            return snapshot;
        }

        public ResumeSnapshot saveResume(String engineSessionId) {
            return resume;
        }

        public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        }
    }

    static class InMemorySessions implements EngineSessionRepository {
        public void lockResource(UUID resourceId) {
        }
        final Map<String, EngineSessionRecord> byKey = new HashMap<>();
        boolean failRenewals;

        public Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType) {
            return Optional.ofNullable(byKey.get(resourceId + ":" + engineType));
        }

        public void insert(EngineSessionRecord record) {
            byKey.put(record.getResourceId() + ":" + record.getEngineType(), record);
        }

        public boolean updateState(UUID id, String state, LocalDateTime updatedAt, String workerId, long ownerEpoch) {
            for (Map.Entry<String, EngineSessionRecord> e : byKey.entrySet()) {
                if (e.getValue().getId().equals(id) && e.getValue().getWorkerId().equals(workerId)
                        && e.getValue().getOwnerEpoch() == ownerEpoch) {
                    EngineSessionRecord r = e.getValue();
                    e.setValue(new EngineSessionRecord(r.getId(), r.getResourceId(), r.getWorkerId(),
                            r.getEngineType(), state, r.getOwnerEpoch(), r.getLeaseUntil(),
                            r.getResumeVersion(), r.getCreatedAt(), updatedAt, r.getResumePath()));
                    return true;
                }
            }
            return false;
        }

        public boolean updateResume(UUID id, long resumeVersion, String resumePath, LocalDateTime updatedAt, String workerId, long ownerEpoch) {
            for (Map.Entry<String, EngineSessionRecord> e : byKey.entrySet()) {
                if (e.getValue().getId().equals(id) && e.getValue().getWorkerId().equals(workerId)
                        && e.getValue().getOwnerEpoch() == ownerEpoch) {
                    EngineSessionRecord r = e.getValue();
                    e.setValue(new EngineSessionRecord(r.getId(), r.getResourceId(), r.getWorkerId(),
                            r.getEngineType(), r.getState(), r.getOwnerEpoch(), r.getLeaseUntil(),
                            resumeVersion, r.getCreatedAt(), updatedAt, resumePath));
                    return true;
                }
            }
            return false;
        }

        public List<EngineSessionRecord> findRecoverable() {
            return byKey.values().stream()
                    .filter(r -> !"STOPPED".equals(r.getState()) && !"FAILED".equals(r.getState()))
                    .toList();
        }

        public boolean insertIfAbsent(EngineSessionRecord record) {
            String key = record.getResourceId() + ":" + record.getEngineType();
            if (byKey.containsKey(key)) {
                return false;
            }
            byKey.put(key, record);
            return true;
        }

        public boolean renewLease(UUID resourceId, String engineType, String workerId,
                                  long ownerEpoch, int leaseSeconds) {
            if (failRenewals) {
                return false;
            }
            EngineSessionRecord r = byKey.get(resourceId + ":" + engineType);
            if (r == null || !r.getWorkerId().equals(workerId) || r.getOwnerEpoch() != ownerEpoch
                    || r.getLeaseUntil() == null || !r.getLeaseUntil().isAfter(LocalDateTime.now())) {
                return false;
            }
            byKey.put(resourceId + ":" + engineType,
                    withLease(r, workerId, r.getOwnerEpoch(), LocalDateTime.now().plusSeconds(leaseSeconds)));
            return true;
        }

        public Optional<EngineSessionRecord> acquireLease(UUID resourceId, String engineType,
                                                          String workerId, int leaseSeconds) {
            String key = resourceId + ":" + engineType;
            EngineSessionRecord r = byKey.get(key);
            if (r == null) {
                return Optional.empty();
            }
            LocalDateTime now = LocalDateTime.now();
            boolean free = r.getLeaseUntil() == null || r.getLeaseUntil().isBefore(now);
            if (!free) {
                return Optional.empty();
            }
            EngineSessionRecord updated = withLease(r, workerId, r.getOwnerEpoch() + 1,
                    now.plusSeconds(leaseSeconds));
            byKey.put(key, updated);
            return Optional.of(updated);
        }

        private static EngineSessionRecord withLease(EngineSessionRecord r, String workerId,
                                                     long ownerEpoch, LocalDateTime leaseUntil) {
            return new EngineSessionRecord(r.getId(), r.getResourceId(), workerId, r.getEngineType(),
                    r.getState(), ownerEpoch, leaseUntil, r.getResumeVersion(),
                    r.getCreatedAt(), LocalDateTime.now(), r.getResumePath());
        }
    }

    static class InMemoryParseJobs implements ParseJobRepository {
        final Map<UUID, ParseJob> byId = new LinkedHashMap<>();

        public void insert(ParseJob job) { byId.put(job.getId(), job); }

        public Optional<ParseJob> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }

        public List<ParseJob> findByUser(UUID userId, ParseStatus status, int offset, int limit) { return List.of(); }

        public long countByUser(UUID userId, ParseStatus status) { return 0; }

        public void markStatus(UUID id, ParseStatus status, String errorCode, String errorMessage) { }

        public boolean existsByUserAndResource(UUID userId, UUID resourceId) {
            return byId.values().stream()
                    .anyMatch(j -> j.getUserId().equals(userId) && j.getResourceId().equals(resourceId));
        }

        public int markTimeoutResolving(LocalDateTime cutoff) { return 0; }

        public List<ParseJob> findByStatus(ParseStatus status, int limit) { return List.of(); }
    }

    static class InMemoryResources implements TorrentResourceRepository {
        final Map<UUID, TorrentResource> byId = new LinkedHashMap<>();
        final Map<UUID, List<TorrentFileInfo>> files = new HashMap<>();

        public Optional<TorrentResource> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }

        public Optional<TorrentResource> findByHash(String hashType, byte[] hashBytes) { return Optional.empty(); }

        public void insert(TorrentResource resource) { byId.put(resource.getId(), resource); }

        public void insertAlias(UUID resourceId, String hashType, byte[] hashBytes) { }

        public void update(TorrentResource resource) { byId.put(resource.getId(), resource); }

        public void insertFiles(UUID resourceId, List<TorrentFileInfo> fileInfos) {
            files.put(resourceId, fileInfos);
        }

        public List<TorrentFileInfo> findFiles(UUID resourceId) {
            return files.getOrDefault(resourceId, List.of());
        }
    }

    // ---------- 用例 ----------

    private InMemoryTasks tasks;
    private InMemoryEvents events;
    private InMemorySessions sessions;
    private InMemoryParseJobs parseJobs;
    private InMemoryResources resources;
    private FakeTorrentStorage torrentStorage;
    private FakeDownloadStorage downloadStorage;
    private TaskService service;
    private UUID alice;
    private UUID bob;
    private UUID resourceId;
    private UUID parseId;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTasks();
        events = new InMemoryEvents();
        sessions = new InMemorySessions();
        parseJobs = new InMemoryParseJobs();
        resources = new InMemoryResources();
        torrentStorage = new FakeTorrentStorage();
        downloadStorage = new FakeDownloadStorage();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(), "local-worker-1", 30);
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        resourceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        resources.insert(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 150L, false, "TORRENT", List.of(), 2, now));
        resources.insertFiles(resourceId, List.of(
                new TorrentFileInfo(0, "a.bin", 100L, null),
                new TorrentFileInfo(1, "b.bin", 50L, null)));
        parseId = UUID.randomUUID();
        parseJobs.insert(new ParseJob(parseId, alice, resourceId, InputType.TORRENT, null,
                "demo.torrent", "/x", "demo", ParseStatus.READY, null, null, 0L, now, now));
    }

    @Test
    void dispatchQueuedTaskPassesTorrentBytesAndResourceStoragePath() {
        resources.update(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 150L, false, "TORRENT", List.of(), 2,
                LocalDateTime.now(), torrentStorage.path));
        RecordingEngine engine = new RecordingEngine();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(engine), "local-worker-1", 30);

        service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);

        assertNotNull(engine.resource);
        assertTrue(engine.resource.torrentFile().length > 0);
        assertArrayEquals(torrentStorage.bytes, engine.resource.torrentFile());
        assertEquals(downloadStorage.resourceRoot(resourceId).toString(), engine.resource.storagePath());
    }

    @Test
    void dispatchQueuedTaskFailsInputLostWhenMetadataPathMissing() {
        RecordingEngine engine = new RecordingEngine();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(engine), "local-worker-1", 30);

        DownloadTask queued = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);

        DownloadTask failed = tasks.findById(queued.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        assertEquals(ErrorCodes.INPUT_LOST, failed.getErrorCode());
        assertEquals(0, engine.createCalls);
        assertNull(engine.resource);
    }

    @Test
    void dispatchQueuedTaskFailsInputLostWhenTorrentIsMissing() {
        resources.update(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 150L, false, "TORRENT", List.of(), 2,
                LocalDateTime.now(), torrentStorage.path));
        TorrentStorage emptyStorage = new FakeTorrentStorage() {
            @Override
            public Optional<byte[]> loadTorrent(String relativePath) {
                return Optional.empty();
            }
        };
        RecordingEngine engine = new RecordingEngine();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                emptyStorage, downloadStorage, List.of(engine), "local-worker-1", 30);

        DownloadTask queued = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);

        DownloadTask failed = tasks.findById(queued.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        assertEquals(ErrorCodes.INPUT_LOST, failed.getErrorCode());
        assertEquals(0, engine.createCalls);
        assertNull(engine.resource);
    }

    @Test
    void dispatchQueuedTaskFailsInputLostWhenResourceIsMissing() {
        RecordingEngine engine = new RecordingEngine();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(engine), "local-worker-1", 30);

        DownloadTask queued = service.create(alice, parseId, List.of(0), null, true, null);
        resources.byId.remove(resourceId);
        service.dispatchTick(1);

        DownloadTask failed = tasks.findById(queued.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        assertEquals(ErrorCodes.INPUT_LOST, failed.getErrorCode());
        assertEquals(0, engine.createCalls);
        assertNull(engine.resource);
    }

    @Test
    void createComputesSelectedBytesAndRecordsEvent() {
        DownloadTask task = service.create(alice, parseId, List.of(0, 1), "iso/2026", true, 5);
        assertEquals(TaskStatus.QUEUED, task.getStatus());
        assertEquals(150L, task.getSelectedBytes());
        assertEquals(5, task.getPriority());
        assertEquals("iso/2026", task.getTargetSubdirectory());
        assertEquals(2, tasks.findFiles(task.getId()).size());
        assertEquals(1, events.findAfter(task.getId(), 0, 10).size());
    }

    @Test
    void createStartLaterYieldsCreated() {
        DownloadTask task = service.create(alice, parseId, List.of(1), null, false, null);
        assertEquals(TaskStatus.CREATED, task.getStatus());
        assertEquals(50L, task.getSelectedBytes());
        assertEquals(0, task.getPriority());
        assertEquals("", task.getTargetSubdirectory());
    }

    @Test
    void createRejectsInvalidIndexAndPathTraversal() {
        assertCode(ErrorCodes.VALIDATION, () -> service.create(alice, parseId, List.of(99), null, true, null));
        assertCode(ErrorCodes.VALIDATION, () -> service.create(alice, parseId, List.of(), null, true, null));
        assertCode(ErrorCodes.INVALID_PATH,
                () -> service.create(alice, parseId, List.of(0), "../escape", true, null));
    }

    @Test
    void createRejectsForeignParse() {
        assertCode(ErrorCodes.NOT_FOUND, () -> service.create(bob, parseId, List.of(0), null, true, null));
    }

    @Test
    void pauseResumePreservesOriginalStartedAt() {
        service = serviceWithEngine(new RecordingEngine());
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        assertNull(task.getStartedAt());
        LocalDateTime beforeStart = LocalDateTime.now();
        service.dispatchTick(1);
        DownloadTask downloading = tasks.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.DOWNLOADING, downloading.getStatus());
        assertNotNull(downloading.getStartedAt());
        assertFalse(downloading.getStartedAt().isBefore(beforeStart));

        // 模拟已下载一段时间的持久化任务，避免依赖系统时钟精度或 sleep。
        LocalDateTime originalStart = LocalDateTime.of(2026, 1, 1, 12, 0);
        tasks.updateCas(downloading.withStartedAt(originalStart, LocalDateTime.now()));
        for (int cycle = 0; cycle < 2; cycle++) {
            service.pause(alice, false, task.getId());
            service.dispatchTick(1);
            DownloadTask paused = tasks.findById(task.getId()).orElseThrow();
            assertEquals(TaskStatus.PAUSED, paused.getStatus());
            assertEquals(originalStart, paused.getStartedAt());
            DownloadTask queued = service.resume(alice, false, task.getId());
            assertEquals(originalStart, queued.getStartedAt());
            service.dispatchTick(1);
            DownloadTask resumed = tasks.findById(task.getId()).orElseThrow();
            assertEquals(TaskStatus.DOWNLOADING, resumed.getStatus());
            assertEquals(originalStart, resumed.getStartedAt());
        }
    }

    @Test
    void pauseResumeCycle() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        DownloadTask pausing = service.pause(alice, false, task.getId());
        assertEquals(TaskStatus.PAUSING, pausing.getStatus());
        // 幂等
        assertEquals(TaskStatus.PAUSING, service.pause(alice, false, task.getId()).getStatus());
        // 调度器落 PAUSED 后可恢复
        DownloadTask paused = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.PAUSED, LocalDateTime.now());
        tasks.updateCas(paused);
        DownloadTask queued = service.resume(alice, false, task.getId());
        assertEquals(TaskStatus.QUEUED, queued.getStatus());
    }

    @Test
    void pauseRejectsTerminal() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        DownloadTask completed = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.STARTING, LocalDateTime.now());
        tasks.updateCas(completed);
        DownloadTask dl = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.DOWNLOADING, LocalDateTime.now());
        tasks.updateCas(dl);
        DownloadTask verifying = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.VERIFYING, LocalDateTime.now());
        tasks.updateCas(verifying);
        DownloadTask done = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.COMPLETED, LocalDateTime.now());
        tasks.updateCas(done);
        assertCode(ErrorCodes.CONFLICT, () -> service.pause(alice, false, task.getId()));
    }

    @Test
    void retryOnlyFromFailed() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        assertCode(ErrorCodes.CONFLICT, () -> service.retry(alice, false, task.getId()));
        DownloadTask failed = tasks.findById(task.getId()).orElseThrow()
                .withStatus(TaskStatus.FAILED, LocalDateTime.now());
        tasks.updateCas(failed);
        assertEquals(TaskStatus.RETRY_WAIT, service.retry(alice, false, task.getId()).getStatus());
    }

    @Test
    void patchValidatesRanges() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        DownloadTask patched = service.patch(alice, false, task.getId(), 7, 1024L);
        assertEquals(7, patched.getPriority());
        assertEquals(1024L, patched.getSpeedLimit());
        assertCode(ErrorCodes.VALIDATION, () -> service.patch(alice, false, task.getId(), 11, null));
        assertCode(ErrorCodes.VALIDATION, () -> service.patch(alice, false, task.getId(), null, -1L));
    }

    @Test
    void deleteIsIdempotentAndHidesTask() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.delete(alice, false, task.getId());
        assertNotNull(tasks.findById(task.getId()).orElseThrow().getDeletedAt());
        service.delete(alice, false, task.getId()); // 幂等
        assertCode(ErrorCodes.NOT_FOUND, () -> service.getTask(alice, false, task.getId()));
    }

    @Test
    void tenantIsolationOnAllCommands() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        assertCode(ErrorCodes.NOT_FOUND, () -> service.pause(bob, false, task.getId()));
        assertCode(ErrorCodes.NOT_FOUND, () -> service.resume(bob, false, task.getId()));
        assertCode(ErrorCodes.NOT_FOUND, () -> service.patch(bob, false, task.getId(), 1, null));
        assertCode(ErrorCodes.NOT_FOUND, () -> service.delete(bob, false, task.getId()));
        assertCode(ErrorCodes.NOT_FOUND, () -> service.getTaskFiles(bob, false, task.getId()));
        // 管理员可越权
        assertEquals(task.getId(), service.getTask(bob, true, task.getId()).getId());
    }

    @Test
    void illegalTransitionRejectedByStateMachine() {
        DownloadTask task = service.create(alice, parseId, List.of(0), null, false, null);
        // CREATED 不可直接 DOWNLOADING
        assertTrue(!task.getStatus().canTransitionTo(TaskStatus.DOWNLOADING));
        assertTrue(task.getStatus().canTransitionTo(TaskStatus.QUEUED));
    }

    // ---------- P3 共享会话与精确进度 ----------

    private TaskService serviceWithEngine(RecordingEngine engine) {
        resources.update(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 150L, false, "TORRENT", List.of(), 2,
                LocalDateTime.now(), torrentStorage.path));
        return new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(engine), "local-worker-1", 30);
    }

    @Test
    void progressUsesPerFileVerifiedBytesNotProportional() {
        RecordingEngine engine = new RecordingEngine();
        // 文件0=100字节已校验100，文件1=50字节已校验30
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                130L, 0L, 0L, 0, List.of(100L, 30L));
        service = serviceWithEngine(engine);

        DownloadTask task = service.create(alice, parseId, List.of(1), null, true, null);
        service.dispatchTick(1);

        DownloadTask refreshed = tasks.findById(task.getId()).orElseThrow();
        // 只选文件1，已校验30字节，不应按资源总量130*50/150=43 计算
        assertEquals(30L, refreshed.getVerifiedBytes());
        assertEquals(TaskStatus.DOWNLOADING, refreshed.getStatus());
    }

    @Test
    void sharedSessionDemandUnionAcrossUsers() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 80L, 10L, 4, List.of(0L, 0L));
        service = serviceWithEngine(engine);

        // Bob 也需要自己的解析记录（同一资源）
        UUID bobParseId = UUID.randomUUID();
        parseJobs.insert(new ParseJob(bobParseId, bob, resourceId, InputType.TORRENT, null,
                "demo.torrent", "/x", "demo", ParseStatus.READY, null, null, 0L,
                LocalDateTime.now(), LocalDateTime.now()));

        DownloadTask aliceTask = service.create(alice, parseId, List.of(0), null, true, null);
        DownloadTask bobTask = service.create(bob, bobParseId, List.of(1), null, true, null);
        service.dispatchTick(3);

        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(aliceTask.getId()).orElseThrow().getStatus());
        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(bobTask.getId()).orElseThrow().getStatus());
        // 需求并集 = {0, 1}
        assertEquals(Set.of(0, 1), engine.lastDemand);

        assertEquals(80D, service.getMetrics().getRegistry()
                .get("magnet_engine_download_rate_bytes_per_second")
                .tag("engine", "LIBTORRENT").gauge().value());
        assertEquals(4D, service.getMetrics().getRegistry()
                .get("magnet_engine_peers")
                .tag("engine", "LIBTORRENT").gauge().value());
    }


    @Test
    void pauseOneUserKeepsSessionRunningForOther() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 0L, 0L, 0, List.of(0L, 0L));
        service = serviceWithEngine(engine);

        UUID bobParseId = UUID.randomUUID();
        parseJobs.insert(new ParseJob(bobParseId, bob, resourceId, InputType.TORRENT, null,
                "demo.torrent", "/x", "demo", ParseStatus.READY, null, null, 0L,
                LocalDateTime.now(), LocalDateTime.now()));

        DownloadTask aliceTask = service.create(alice, parseId, List.of(0), null, true, null);
        DownloadTask bobTask = service.create(bob, bobParseId, List.of(1), null, true, null);
        service.dispatchTick(1);
        engine.paused = false;

        service.pause(alice, false, aliceTask.getId());
        service.dispatchTick(1);

        // Alice 的暂停只收回她的需求，Bob 仍下载；会话不暂停
        assertEquals(Set.of(1), engine.lastDemand);
        assertFalse(engine.paused, "共享会话不应因单个用户暂停而停止");
        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(bobTask.getId()).orElseThrow().getStatus());
    }

    @Test
    void allUsersPausedSavesResumeAndPausesSession() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 0L, 0L, 0, List.of(0L, 0L));
        service = serviceWithEngine(engine);

        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        engine.paused = false;
        downloadStorage.writeResumeCalled = false;

        service.pause(alice, false, task.getId());
        service.dispatchTick(1);

        // 无任何活跃需求 → 保存 resume + 暂停会话
        assertTrue(downloadStorage.writeResumeCalled, "无需求时应保存 resume");
        assertTrue(engine.paused, "无需求时应暂停共享会话");
        EngineSessionRecord session = sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name())
                .orElseThrow();
        assertEquals(SessionState.IDLE.name(), session.getState());
    }

    @Test
    void completionRequiresAllSelectedFilesVerified() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                100L, 0L, 0L, 0, List.of(100L, 50L));
        service = serviceWithEngine(engine);

        DownloadTask task = service.create(alice, parseId, List.of(0, 1), null, true, null);
        service.dispatchTick(1); // DOWNLOADING → 进度全量 → VERIFYING
        assertEquals(TaskStatus.VERIFYING, tasks.findById(task.getId()).orElseThrow().getStatus());

        service.dispatchTick(1); // 第二次快照确认 → COMPLETED
        assertEquals(TaskStatus.COMPLETED, tasks.findById(task.getId()).orElseThrow().getStatus());
        assertEquals(150L, tasks.findById(task.getId()).orElseThrow().getVerifiedBytes());
    }

    // ---------- P5 租约与 Fencing（规格 §12.2） ----------

    @Test
    void authoritativeRecheckCanReducePreviouslyVerifiedFileProgress() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                90L, 0L, 0L, 0, List.of(90L, 0L));
        service = serviceWithEngine(engine);
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                20L, 0L, 0L, 0, List.of(20L, 0L));
        service.dispatchTick(1);
        assertEquals(20L, tasks.findFiles(task.getId()).get(0).getVerifiedBytes());
        assertEquals(20L, tasks.findById(task.getId()).orElseThrow().getVerifiedBytes());
    }

    @Test
    void unavailableWorkerCannotKeepRenewingLeaseWhileApiIsAlive() {
        RecordingEngine engine = new RecordingEngine();
        service = serviceWithEngine(engine);
        service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        EngineSessionRecord before = sessions.findByResource(resourceId, "LIBTORRENT").orElseThrow();
        engine.available = false;
        service.dispatchTick(1);
        assertEquals(before.getLeaseUntil(), sessions.findByResource(resourceId, "LIBTORRENT")
                .orElseThrow().getLeaseUntil());

        sessions.insert(InMemorySessions.withLease(before, "local-worker-1", before.getOwnerEpoch(),
                LocalDateTime.now().minusSeconds(1)));
        RecordingEngine replacement = new RecordingEngine();
        TaskService other = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(replacement), "worker-2", 30);
        other.dispatchTick(1);
        assertEquals(before.getOwnerEpoch() + 1, replacement.lastOptions.ownerEpoch());
        assertEquals("worker-2", sessions.findByResource(resourceId, "LIBTORRENT").orElseThrow().getWorkerId());
    }

    @Test
    void expiredSelfLeaseIsReacquiredForActiveDownload() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 0L, 0L, 0, List.of(0L, 0L));
        service = serviceWithEngine(engine);
        service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        EngineSessionRecord before = sessions.findByResource(resourceId, "LIBTORRENT").orElseThrow();
        sessions.insert(InMemorySessions.withLease(before, "local-worker-1", before.getOwnerEpoch(),
                LocalDateTime.now().minusSeconds(1)));

        service.dispatchTick(1);

        assertEquals(before.getOwnerEpoch() + 1, engine.lastOptions.ownerEpoch());
        assertNotNull(engine.lastOptions.leaseUntil());
    }

    @Test
    void secondSchedulerDoesNotDriveLiveForeignSession() {
        RecordingEngine first = new RecordingEngine();
        service = serviceWithEngine(first);
        service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        RecordingEngine second = new RecordingEngine();
        TaskService other = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(second), "worker-2", 30);

        other.dispatchTick(1);

        assertEquals(0, second.createCalls);
        assertNull(second.lastDemand);
        assertFalse(second.paused);
    }

    @Test
    void leaseRenewFailureFencesSessionStopsWriting() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 0L, 0L, 0, List.of(0L, 0L));
        service = serviceWithEngine(engine);

        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(task.getId()).orElseThrow().getStatus());

        // 模拟租约丢失（被其他控制实例接管）：续租 CAS 失败 → 必须停止写（fail-closed）
        engine.lastDemand = null;
        engine.paused = false;
        sessions.failRenewals = true;
        service.dispatchTick(1);

        assertFalse(engine.paused, "失去租约后不得再向共享会话下发命令，由 Worker 看门狗终止旧进程");
        assertFalse(downloadStorage.writeResumeCalled, "失去租约后不得写共享 resume");
        assertEquals(SessionState.ACTIVE.name(),
                sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name()).orElseThrow().getState());
        assertNull(engine.lastDemand, "续租失败后不得继续下发需求");
    }

    @Test
    void takeoverExpiredForeignLeaseReattachesWithIncrementedEpoch() {
        RecordingEngine engine = new RecordingEngine();
        engine.snapshot = new EngineSnapshot("s", SessionState.ACTIVE, true,
                0L, 0L, 0L, 0, List.of(0L, 0L));
        service = serviceWithEngine(engine);

        // 他人（w2）持有的已过期租约，epoch=3
        sessions.insert(new EngineSessionRecord(resourceId, resourceId, "w2",
                EngineKind.LIBTORRENT.name(), SessionState.ACTIVE.name(), 3L,
                LocalDateTime.now().minusMinutes(5), 0L, LocalDateTime.now(), LocalDateTime.now()));

        service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);

        // 接管成功：epoch 单调 +1，重新挂载携带新 epoch
        assertEquals(4L, engine.lastOptions.ownerEpoch(), "接管后 createOrAttach 必须携带新 epoch");
        EngineSessionRecord leased = sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name())
                .orElseThrow();
        assertEquals("local-worker-1", leased.getWorkerId());
        assertEquals(4L, leased.getOwnerEpoch());
        assertEquals(1, engine.createCalls);
    }

    @Test
    void validForeignLeaseFailsClosedTaskStaysQueued() {
        RecordingEngine engine = new RecordingEngine();
        service = serviceWithEngine(engine);

        // 他人（w2）持有有效租约
        sessions.insert(new EngineSessionRecord(resourceId, resourceId, "w2",
                EngineKind.LIBTORRENT.name(), SessionState.ACTIVE.name(), 3L,
                LocalDateTime.now().plusSeconds(60), 0L, LocalDateTime.now(), LocalDateTime.now()));

        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);

        // fail-closed：抢不到租约不写引擎；瞬态冲突任务保持 QUEUED 等待下轮，不判 FAILED
        assertEquals(0, engine.createCalls, "他人持有有效租约时不得挂载引擎");
        DownloadTask queued = tasks.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.QUEUED, queued.getStatus());
        assertEquals(ErrorCodes.WORKER_UNAVAILABLE, queued.getErrorCode());
    }

    @Test
    void explicitRetryRemovesFailedAria2ResultWithoutDeletingPartialFile() {
        boolean[] removed = {false};
        RecordingEngine engine = new RecordingEngine() {
            public EngineKind kind() { return EngineKind.ARIA2; }
            public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
                this.resource = resource;
                return new EngineSession(resource.resourceId(), EngineKind.ARIA2, snapshot.state());
            }
            public void removeSession(String id, boolean data) {
                assertFalse(data, "重试须保留部分下载文件");
                removed[0] = true;
                snapshot = EngineSnapshot.empty(id);
            }
        };
        service = serviceWithEngine(engine);
        resources.update(new TorrentResource(resourceId, "http:retry", (byte[]) null, (byte[]) null,
                VersionType.HTTP, "demo.bin", null, 150L, false, "HTTP", List.of(), 2,
                LocalDateTime.now(), "http://example.com/demo.bin"));
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        engine.snapshot = new EngineSnapshot(resourceId.toString(), SessionState.FAILED, true,
                0L, 0L, 0L, 0, List.of(0L));
        service.dispatchTick(1);
        assertEquals(TaskStatus.FAILED, tasks.findById(task.getId()).orElseThrow().getStatus());
        service.retry(alice, false, task.getId());
        service.dispatchTick(1);
        assertTrue(removed[0]);
        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void failedEngineSnapshotCannotCompleteTaskEvenWithFullByteCount() {
        RecordingEngine engine = new RecordingEngine();
        service = serviceWithEngine(engine);
        DownloadTask task = service.create(alice, parseId, List.of(0), null, true, null);
        service.dispatchTick(1);
        engine.snapshot = new EngineSnapshot(resourceId.toString(), SessionState.FAILED, true,
                100L, 0L, 0L, 0, List.of(100L, 0L));
        service.dispatchTick(1);
        assertEquals(TaskStatus.FAILED, tasks.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void httpTaskRoutesToAria2EngineAndCompletesLifecycle() {
        RecordingEngine btEngine = new RecordingEngine();
        RecordingAria2Engine aria2Engine = new RecordingAria2Engine();
        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(btEngine, aria2Engine), "local-worker-1", 30);

        UUID httpResId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        TorrentResource httpResource = new TorrentResource(httpResId, "http:test-hash", (byte[]) null, (byte[]) null,
                VersionType.HTTP, "archive.iso", null, 1000L, false, "HTTP",
                List.of(), 1, now, "https://example.com/archive.iso");
        resources.byId.put(httpResId, httpResource);
        resources.files.put(httpResId, List.of(new TorrentFileInfo(0, "archive.iso", 1000L, null)));

        UUID httpParseId = UUID.randomUUID();
        parseJobs.byId.put(httpParseId, new ParseJob(httpParseId, alice, httpResId,
                InputType.HTTP, null, "https://example.com/archive.iso", null, "archive.iso",
                ParseStatus.READY, null, null, 0, now, now));

        DownloadTask task = service.create(alice, httpParseId, List.of(0), null, true, null);
        assertEquals(TaskStatus.QUEUED, task.getStatus());

        // dispatchTick: 驱动挂载
        service.dispatchTick(1);

        // 断言：严格路由到 ARIA2 引擎，BT 引擎从未被调用
        assertEquals(1, aria2Engine.createCalls, "HTTP 任务必须路由至 ARIA2 引擎挂载");
        assertEquals(0, btEngine.createCalls, "BT 引擎不得参与 HTTP 任务处理");
        EngineSessionRecord session = sessions.findByResource(httpResId, EngineKind.ARIA2.name()).orElseThrow();
        assertEquals("ARIA2", session.getEngineType());
        assertEquals(TaskStatus.DOWNLOADING, tasks.findById(task.getId()).orElseThrow().getStatus());

        // 推进进度至 100%
        aria2Engine.snapshot = new EngineSnapshot(httpResId.toString(), SessionState.ACTIVE, true,
                1000L, 100L, 0L, 1, List.of(1000L));

        // 第 1 tick: DOWNLOADING -> VERIFYING
        service.dispatchTick(1);
        assertEquals(TaskStatus.VERIFYING, tasks.findById(task.getId()).orElseThrow().getStatus());

        // 第 2 tick: VERIFYING -> COMPLETED
        service.dispatchTick(1);
        DownloadTask completed = tasks.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
        assertEquals(1000L, completed.getVerifiedBytes());
    }

    static class RecordingAria2Engine extends RecordingEngine {
        @Override
        public EngineKind kind() {
            return EngineKind.ARIA2;
        }

        @Override
        public EngineCapabilities capabilities() {
            return new EngineCapabilities(false, false, false, true, false, true);
        }
    }

    private static void assertCode(String code, Executable executable) {
        DomainException e = assertThrows(DomainException.class, executable);
        assertEquals(code, e.code(), e::getMessage);
    }

    @Test
    void slowOrStuckSnapshotDoesNotBlockOtherSessionsOrSchedulerTick() {
        LocalDateTime now = LocalDateTime.now();
        resources.update(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 150L, false, "TORRENT", List.of(), 2,
                now, torrentStorage.path));

        UUID resB = UUID.randomUUID();
        resources.insert(new TorrentResource(resB, "btih:fast-b", new byte[20], null,
                VersionType.V1, "demoB", 16384L, 100L, false, "TORRENT", List.of(), 1,
                now, torrentStorage.path));
        resources.insertFiles(resB, List.of(new TorrentFileInfo(0, "b.bin", 100L, null)));

        UUID parseB = UUID.randomUUID();
        parseJobs.insert(new ParseJob(parseB, alice, resB, InputType.TORRENT, null,
                "b.torrent", "/b", "b", ParseStatus.READY, null, null, 0L, now, now));

        DownloadTask taskA = service.create(alice, parseId, List.of(0), null, true, 0);
        DownloadTask taskB = service.create(alice, parseB, List.of(0), null, true, 0);

        RecordingEngine engine = new RecordingEngine();
        engine.snapshotFn = sessionId -> {
            if (sessionId.equals(resourceId.toString())) {
                try {
                    Thread.sleep(1500); // 超过 1000ms 超时熔断阈值
                } catch (InterruptedException ignored) {}
                return new EngineSnapshot(sessionId, SessionState.ACTIVE, true,
                        50L, 0L, 0L, 0, List.of(50L, 0L));
            } else {
                return new EngineSnapshot(sessionId, SessionState.ACTIVE, true,
                        100L, 0L, 0L, 0, List.of(100L));
            }
        };

        service = new TaskService(tasks, events, sessions, parseJobs, resources,
                torrentStorage, downloadStorage, List.of(engine), "local-worker-1", 30);

        long start = System.currentTimeMillis();
        // 第一次 tick：QUEUED -> STARTING -> DOWNLOADING
        service.dispatchTick(10);
        // 第二次 tick：会触发快照拉取与超时熔断，resB 完成两轮核查变为 COMPLETED
        service.dispatchTick(10);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 4000, "调度总耗时应受超时熔断保护，实际: " + elapsed + "ms");

        DownloadTask currentA = tasks.findById(taskA.getId()).orElseThrow();
        assertEquals(TaskStatus.DOWNLOADING, currentA.getStatus());

        DownloadTask currentB = tasks.findById(taskB.getId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, currentB.getStatus());
        assertEquals(100L, currentB.getVerifiedBytes());
    }
}
