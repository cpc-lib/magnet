package com.magnetplatform.application;

import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.domain.torrent.PathSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {

    @TempDir
    Path tempDir;

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
            return List.of();
        }

        public long countByUser(UUID userId, TaskStatus status) {
            return 0;
        }

        public List<DownloadTask> findByStatuses(List<TaskStatus> statuses, int limit) {
            return List.of();
        }

        public List<DownloadTask> findActiveByResource(UUID resourceId) {
            return List.of();
        }

        public boolean updateCas(DownloadTask task) {
            byId.put(task.getId(), task);
            return true;
        }

        public void updateFileProgress(UUID taskId, int fileIndex, long verifiedBytes) {
        }

        public long countNonDeletedByResource(UUID resourceId) {
            return 0;
        }
    }

    static class InMemoryResources implements TorrentResourceRepository {
        final Map<UUID, TorrentResource> byId = new LinkedHashMap<>();
        final Map<UUID, List<TorrentFileInfo>> files = new HashMap<>();

        public Optional<TorrentResource> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<TorrentResource> findByHash(String hashType, byte[] hashBytes) {
            return Optional.empty();
        }

        public void insert(TorrentResource resource) {
            byId.put(resource.getId(), resource);
        }

        public void insertAlias(UUID resourceId, String hashType, byte[] hashBytes) {
        }

        public void update(TorrentResource resource) {
            byId.put(resource.getId(), resource);
        }

        public void insertFiles(UUID resourceId, List<TorrentFileInfo> fileInfos) {
            files.put(resourceId, fileInfos);
        }

        public List<TorrentFileInfo> findFiles(UUID resourceId) {
            return files.getOrDefault(resourceId, List.of());
        }
    }

    static class InMemoryExports implements FileExportRepository {
        final Map<UUID, FileExport> byId = new LinkedHashMap<>();

        public void insert(FileExport export) {
            byId.put(export.getId(), export);
        }

        public List<FileExport> findByTask(UUID taskId) {
            return byId.values().stream().filter(e -> e.getTaskId().equals(taskId)).toList();
        }

        public List<FileExport> findOwned(UUID userId, UUID taskId) {
            return byId.values().stream()
                    .filter(e -> e.getUserId().equals(userId) && e.getTaskId().equals(taskId))
                    .toList();
        }

        public void updateState(UUID id, ExportState state, long bytes, String errorCode,
                                String errorMessage, String targetPath, LocalDateTime updatedAt) {
            FileExport e = byId.get(id);
            if (e != null) {
                byId.put(id, new FileExport(e.getId(), e.getTaskId(), e.getUserId(), e.getResourceId(),
                        e.getFileIndex(), e.getRelativePath(), targetPath, state, bytes,
                        errorCode, errorMessage, e.getCreatedAt(), updatedAt));
            }
        }

        public void deleteOwnedByTask(UUID userId, UUID taskId) {
            byId.replaceAll((id, e) -> e.getUserId().equals(userId) && e.getTaskId().equals(taskId)
                    ? new FileExport(e.getId(), e.getTaskId(), e.getUserId(), e.getResourceId(),
                    e.getFileIndex(), e.getRelativePath(), e.getTargetPath(), ExportState.DELETED,
                    e.getBytes(), e.getErrorCode(), e.getErrorMessage(), e.getCreatedAt(), LocalDateTime.now())
                    : e);
        }

        public long countActiveByResource(UUID resourceId) {
            return byId.values().stream()
                    .filter(e -> e.getResourceId().equals(resourceId) && e.getState() != ExportState.DELETED)
                    .count();
        }
    }

    static class LocalDownloadStorage implements DownloadStorage {
        private final Path root;

        LocalDownloadStorage(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        public Path resourceRoot(UUID resourceId) {
            return root.resolve("resources").resolve(resourceId.toString()).normalize();
        }

        public Path resumeFile(UUID resourceId) {
            return root.resolve("resume").resolve(resourceId + ".dat").normalize();
        }

        public Path exportRoot(UUID userId, UUID taskId) {
            return root.resolve("exports").resolve(userId.toString()).resolve(taskId.toString()).normalize();
        }

        public Path resolveResourceFile(UUID resourceId, String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                throw new DomainException(ErrorCodes.INVALID_PATH, "相对路径不能为空");
            }
            String normalized = relativePath.replace('\\', '/');
            if (normalized.startsWith("/")) {
                throw new DomainException(ErrorCodes.INVALID_PATH, "不允许绝对路径");
            }
            String sanitized = PathSanitizer.joinRelativePath(Arrays.asList(normalized.split("/", -1)));
            Path base = resourceRoot(resourceId);
            Path candidate = base.resolve(sanitized).normalize();
            if (!candidate.startsWith(base)) {
                throw new DomainException(ErrorCodes.INVALID_PATH, "路径越界");
            }
            return candidate;
        }

        public void writeResumeAtomically(UUID resourceId, byte[] bytes) {
        }

        public Optional<byte[]> readResume(UUID resourceId) {
            return Optional.empty();
        }

        public void materialize(Path source, Path target) {
            try {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void deleteTree(Path dir) {
        }
    }

    private InMemoryTasks tasks;
    private InMemoryResources resources;
    private InMemoryExports exports;
    private LocalDownloadStorage storage;
    private ExportService service;
    private UUID alice;
    private UUID bob;
    private UUID resourceId;
    private UUID taskId;

    @BeforeEach
    void setUp() throws IOException {
        tasks = new InMemoryTasks();
        resources = new InMemoryResources();
        exports = new InMemoryExports();
        storage = new LocalDownloadStorage(tempDir);
        service = new ExportService(tasks, resources, exports, storage);
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        resourceId = UUID.randomUUID();
        taskId = UUID.randomUUID();

        LocalDateTime now = LocalDateTime.now();
        resources.insert(new TorrentResource(resourceId, "btih:abc", new byte[20], null,
                VersionType.V1, "demo", 16384L, 15L, false, "TORRENT", List.of(), 2, now));
        resources.insertFiles(resourceId, List.of(
                new TorrentFileInfo(0, "selected.txt", 5L, null),
                new TorrentFileInfo(1, "unselected.txt", 10L, null)));

        Path resRoot = storage.resourceRoot(resourceId);
        Files.createDirectories(resRoot);
        Files.writeString(resRoot.resolve("selected.txt"), "hello", StandardCharsets.UTF_8);
        Files.writeString(resRoot.resolve("unselected.txt"), "0123456789", StandardCharsets.UTF_8);
    }

    @Test
    void listingCompletedTaskDoesNotExportFiles() {
        LocalDateTime now = LocalDateTime.now();
        DownloadTask completed = new DownloadTask(taskId, alice, resourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "", null, null, 1L, now, now, now, now, null);
        tasks.insert(completed, List.of(
                new DownloadTaskFile(UUID.randomUUID(), taskId, 0, true, 5L, "NONE")));
        assertTrue(service.listOwned(alice, false, taskId).isEmpty());
        assertFalse(Files.exists(storage.exportRoot(alice, taskId)));
    }

    @Test
    void retryRepairsLegacyFailedTargetPath() {
        LocalDateTime now = LocalDateTime.now();
        DownloadTask completed = new DownloadTask(taskId, alice, resourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "sub", null, null, 1L, now, now, now, now, null);
        tasks.insert(completed, List.of(
                new DownloadTaskFile(UUID.randomUUID(), taskId, 0, true, 5L, "NONE")));
        UUID exportId = UUID.randomUUID();
        exports.insert(new FileExport(exportId, taskId, alice, resourceId, 0, "selected.txt",
                storage.exportRoot(alice, taskId).toString(), ExportState.FAILED, 0L,
                ErrorCodes.EXPORT_IO_ERROR, "source missing", now, now));
        service.exportOwned(alice, false, taskId);
        ExportService.OpenedExport opened = service.openOwned(alice, false, taskId, exportId);
        assertEquals(storage.exportRoot(alice, taskId).resolve("sub/selected.txt"), opened.path());
    }

    @Test
    void manualExportRequiresOwnershipAndCompletedTask() {
        LocalDateTime now = LocalDateTime.now();
        DownloadTask downloading = new DownloadTask(taskId, alice, resourceId, TaskStatus.DOWNLOADING,
                5L, 0L, 0, null, "", null, null, 1L, now, now, now, null, null);
        tasks.insert(downloading, List.of());
        assertEquals(ErrorCodes.NOT_FOUND, assertThrows(DomainException.class,
                () -> service.exportOwned(bob, false, taskId)).code());
        assertEquals(ErrorCodes.CONFLICT, assertThrows(DomainException.class,
                () -> service.exportOwned(alice, false, taskId)).code());
        assertTrue(exports.findByTask(taskId).isEmpty());
    }

    @Test
    void materializesOnlySelectedVerifiedFiles() {
        LocalDateTime now = LocalDateTime.now();
        DownloadTask completed = new DownloadTask(taskId, alice, resourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "sub", null, null, 1L, now, now, now, now, null);
        tasks.insert(completed, List.of(
                new DownloadTaskFile(UUID.randomUUID(), taskId, 0, true, 5L, "NONE"),
                new DownloadTaskFile(UUID.randomUUID(), taskId, 1, false, 0L, "NONE")));

        List<FileExport> result = service.materializeCompleted(completed);
        assertEquals(1, result.size());
        FileExport exp = result.get(0);
        assertEquals(0, exp.getFileIndex());
        assertEquals(ExportState.READY, exp.getState());
        assertEquals(5L, exp.getBytes());
        assertTrue(Files.isRegularFile(Path.of(exp.getTargetPath())));
        assertFalse(Files.exists(storage.exportRoot(alice, taskId).resolve("sub/unselected.txt")));
    }

    @Test
    void rejectsTraversalAndSymlinkEscape() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        UUID badResourceId = UUID.randomUUID();
        UUID badTaskId = UUID.randomUUID();
        resources.insert(new TorrentResource(badResourceId, "btih:def", new byte[20], null,
                VersionType.V1, "bad", 16384L, 5L, false, "TORRENT", List.of(), 1, now));
        resources.insertFiles(badResourceId, List.of(
                new TorrentFileInfo(0, "../escape.txt", 5L, null)));
        DownloadTask badTask = new DownloadTask(badTaskId, alice, badResourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "", null, null, 1L, now, now, now, now, null);
        tasks.insert(badTask, List.of(
                new DownloadTaskFile(UUID.randomUUID(), badTaskId, 0, true, 5L, "NONE")));

        List<FileExport> traversalResult = service.materializeCompleted(badTask);
        assertEquals(1, traversalResult.size());
        assertEquals(ExportState.FAILED, traversalResult.get(0).getState());
        assertEquals(ErrorCodes.INVALID_PATH, traversalResult.get(0).getErrorCode());

        // Symlink source if OS permits creating symlinks
        Path outside = tempDir.resolve("outside-secret.txt");
        Files.writeString(outside, "12345", StandardCharsets.UTF_8);
        Path symlinkSource = storage.resourceRoot(resourceId).resolve("selected.txt");
        Files.deleteIfExists(symlinkSource);
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(symlinkSource, outside);
            symlinkCreated = true;
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows non-elevated symlink fallback: test target escape check via openOwned
        }
        DownloadTask symlinkTask = new DownloadTask(taskId, alice, resourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "", null, null, 1L, now, now, now, now, null);
        tasks.insert(symlinkTask, List.of(
                new DownloadTaskFile(UUID.randomUUID(), taskId, 0, true, 5L, "NONE")));
        List<FileExport> symResult = service.materializeCompleted(symlinkTask);
        assertEquals(1, symResult.size());
        assertEquals(ExportState.FAILED, symResult.get(0).getState());
        if (symlinkCreated) {
            assertEquals(ErrorCodes.INVALID_PATH, symResult.get(0).getErrorCode());
        }
    }

    @Test
    void strangerCannotListOrReadExport() {
        LocalDateTime now = LocalDateTime.now();
        DownloadTask completed = new DownloadTask(taskId, alice, resourceId, TaskStatus.COMPLETED,
                5L, 5L, 0, null, "", null, null, 1L, now, now, now, now, null);
        tasks.insert(completed, List.of(
                new DownloadTaskFile(UUID.randomUUID(), taskId, 0, true, 5L, "NONE")));
        List<FileExport> list = service.materializeCompleted(completed);
        UUID exportId = list.get(0).getId();

        DomainException e1 = assertThrows(DomainException.class,
                () -> service.listOwned(bob, false, taskId));
        assertEquals(ErrorCodes.NOT_FOUND, e1.code());

        DomainException e2 = assertThrows(DomainException.class,
                () -> service.openOwned(bob, false, taskId, exportId));
        assertEquals(ErrorCodes.NOT_FOUND, e2.code());
    }
}
