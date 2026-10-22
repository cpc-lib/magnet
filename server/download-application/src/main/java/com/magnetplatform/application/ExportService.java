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
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.domain.torrent.PathSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExportService {

    public record OpenedExport(FileExport export, Path path) {
    }

    private final DownloadTaskRepository tasks;
    private final TorrentResourceRepository resources;
    private final FileExportRepository exports;
    private final DownloadStorage downloadStorage;

    public ExportService(DownloadTaskRepository tasks,
                         TorrentResourceRepository resources,
                         FileExportRepository exports,
                         DownloadStorage downloadStorage) {
        this.tasks = tasks;
        this.resources = resources;
        this.exports = exports;
        this.downloadStorage = downloadStorage;
    }

    @Transactional
    public List<FileExport> materializeCompleted(DownloadTask task) {
        DownloadTask reloaded = tasks.findById(task.getId())
                .filter(t -> t.getStatus() != TaskStatus.DELETED)
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
        if (reloaded.getStatus() != TaskStatus.COMPLETED) {
            return exports.findByTask(reloaded.getId());
        }

        Map<Integer, TorrentFileInfo> resourceFiles = resources.findFiles(reloaded.getResourceId()).stream()
                .collect(Collectors.toMap(TorrentFileInfo::getFileIndex, Function.identity()));
        Map<Integer, FileExport> existingByIndex = exports.findByTask(reloaded.getId()).stream()
                .collect(Collectors.toMap(FileExport::getFileIndex, Function.identity(), (a, b) -> b));

        Path resourceRoot = downloadStorage.resourceRoot(reloaded.getResourceId()).toAbsolutePath().normalize();
        Path exportRoot = downloadStorage.exportRoot(reloaded.getUserId(), reloaded.getId()).toAbsolutePath().normalize();

        for (DownloadTaskFile tf : tasks.findFiles(reloaded.getId())) {
            if (!tf.isSelected()) {
                continue;
            }
            TorrentFileInfo info = resourceFiles.get(tf.getFileIndex());
            if (info == null) {
                continue;
            }
            FileExport existing = existingByIndex.get(tf.getFileIndex());
            if (existing != null && existing.getState() == ExportState.READY
                    && Files.isRegularFile(Path.of(existing.getTargetPath()), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            LocalDateTime now = LocalDateTime.now();
            UUID exportId = existing != null ? existing.getId() : UUID.randomUUID();
            try {
                Path source = validateSourceFile(reloaded.getResourceId(), resourceRoot, info.getRelativePath());
                Path target = resolveTargetFile(exportRoot, reloaded.getTargetSubdirectory(), info.getRelativePath());
                if (existing == null) {
                    FileExport pending = new FileExport(exportId, reloaded.getId(), reloaded.getUserId(),
                            reloaded.getResourceId(), tf.getFileIndex(), info.getRelativePath(),
                            target.toString(), ExportState.PENDING, 0L, null, null, now, now);
                    exports.insert(pending);
                    existing = pending;
                }
                long sourceSize = Files.size(source);
                if (info.getSizeBytes() > 0 && sourceSize != info.getSizeBytes()) {
                    throw new DomainException(ErrorCodes.EXPORT_IO_ERROR,
                            "源文件内容长度与种子声明不一致: " + info.getRelativePath());
                }
                downloadStorage.materialize(source, target);
                long copiedSize = Files.size(target);
                if (copiedSize != sourceSize) {
                    throw new DomainException(ErrorCodes.EXPORT_IO_ERROR,
                            "导出文件字节数校验失败: " + info.getRelativePath());
                }
                exports.updateState(exportId, ExportState.READY, copiedSize, null, null, LocalDateTime.now());
            } catch (DomainException e) {
                recordFailure(existing, exportId, reloaded, tf.getFileIndex(), info.getRelativePath(),
                        exportRoot, e.code(), e.getMessage());
            } catch (IOException | UncheckedIOException e) {
                recordFailure(existing, exportId, reloaded, tf.getFileIndex(), info.getRelativePath(),
                        exportRoot, ErrorCodes.EXPORT_IO_ERROR, e.getMessage());
            }
        }
        return exports.findByTask(reloaded.getId());
    }

    @Transactional
    public List<FileExport> listOwned(UUID actorId, boolean admin, UUID taskId) {
        DownloadTask task = getOwnedTask(actorId, admin, taskId);
        List<FileExport> current = admin
                ? exports.findByTask(taskId)
                : exports.findOwned(task.getUserId(), taskId);
        if (task.getStatus() == TaskStatus.COMPLETED
                && current.stream().noneMatch(e -> e.getState() == ExportState.READY)) {
            materializeCompleted(task);
            current = admin
                    ? exports.findByTask(taskId)
                    : exports.findOwned(task.getUserId(), taskId);
        }
        return current.stream()
                .filter(e -> e.getState() != ExportState.DELETED)
                .toList();
    }

    @Transactional
    public OpenedExport openOwned(UUID actorId, boolean admin, UUID taskId, UUID exportId) {
        DownloadTask task = getOwnedTask(actorId, admin, taskId);
        FileExport export = listOwned(actorId, admin, taskId).stream()
                .filter(e -> e.getId().equals(exportId))
                .findFirst()
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "导出文件不存在"));
        if (export.getState() != ExportState.READY) {
            throw new DomainException(ErrorCodes.CONFLICT, "导出文件尚未就绪: " + export.getState());
        }
        Path exportRoot = downloadStorage.exportRoot(task.getUserId(), task.getId()).toAbsolutePath().normalize();
        Path target = Path.of(export.getTargetPath()).toAbsolutePath().normalize();
        if (!target.startsWith(exportRoot) || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException(ErrorCodes.NOT_FOUND, "导出物理文件不存在或已被移除");
        }
        return new OpenedExport(export, target);
    }

    @Transactional
    public void deleteOwned(UUID actorId, boolean admin, UUID taskId, boolean removeOwnExport) {
        DownloadTask task = tasks.findById(taskId)
                .filter(t -> admin || t.getUserId().equals(actorId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
        if (removeOwnExport) {
            downloadStorage.deleteTree(downloadStorage.exportRoot(task.getUserId(), task.getId()));
            exports.deleteOwnedByTask(task.getUserId(), task.getId());
        }
    }

    private DownloadTask getOwnedTask(UUID actorId, boolean admin, UUID taskId) {
        return tasks.findById(taskId)
                .filter(t -> t.getStatus() != TaskStatus.DELETED)
                .filter(t -> admin || t.getUserId().equals(actorId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
    }

    private Path validateSourceFile(UUID resourceId, Path resourceRoot, String relativePath) throws IOException {
        Path source = downloadStorage.resolveResourceFile(resourceId, relativePath).toAbsolutePath().normalize();
        if (!source.startsWith(resourceRoot)) {
            throw new DomainException(ErrorCodes.INVALID_PATH, "源文件路径越界");
        }
        Path cursor = resourceRoot;
        for (Path part : resourceRoot.relativize(source)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new DomainException(ErrorCodes.INVALID_PATH, "源文件包含非法软链接");
            }
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException(ErrorCodes.EXPORT_IO_ERROR, "已验证源文件不存在: " + relativePath);
        }
        return source;
    }

    private static Path resolveTargetFile(Path exportRoot, String targetSubdirectory, String relativePath) {
        String sanitizedRel = PathSanitizer.joinRelativePath(
                Arrays.asList(relativePath.replace('\\', '/').split("/", -1)));
        Path base = exportRoot;
        if (targetSubdirectory != null && !targetSubdirectory.isBlank()) {
            String sanitizedSubdir = PathSanitizer.joinRelativePath(
                    Arrays.asList(targetSubdirectory.replace('\\', '/').split("/", -1)));
            base = base.resolve(sanitizedSubdir).normalize();
        }
        Path target = base.resolve(sanitizedRel).normalize();
        if (!target.startsWith(exportRoot)) {
            throw new DomainException(ErrorCodes.INVALID_PATH, "导出目标路径越界");
        }
        return target;
    }

    private void recordFailure(FileExport existing, UUID exportId, DownloadTask task, int fileIndex,
                               String relativePath, Path exportRoot, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            FileExport failed = new FileExport(exportId, task.getId(), task.getUserId(), task.getResourceId(),
                    fileIndex, relativePath, exportRoot.toString(), ExportState.FAILED, 0L,
                    errorCode, errorMessage, now, now);
            exports.insert(failed);
        } else {
            exports.updateState(exportId, ExportState.FAILED, 0L, errorCode, errorMessage, now);
        }
    }
}
