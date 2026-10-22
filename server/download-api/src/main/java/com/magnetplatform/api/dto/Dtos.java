package com.magnetplatform.api.dto;

import com.magnetplatform.application.Page;
import com.magnetplatform.application.ResourceDetail;
import com.magnetplatform.domain.common.Hex;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.TaskEvent;
import com.magnetplatform.domain.user.AppUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** API 层传输对象集中定义 */
public final class Dtos {

    private Dtos() {
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserView(UUID id, String username, String role) {

        public static UserView from(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getRole().name());
        }
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record SubmitMagnetRequest(@NotBlank String uri) {
    }

    public record ParseJobView(UUID id,
                               UUID resourceId,
                               String inputType,
                               String displayHint,
                               String status,
                               String errorCode,
                               String errorMessage,
                               LocalDateTime createdAt,
                               LocalDateTime updatedAt) {

        public static ParseJobView from(ParseJob job) {
            return new ParseJobView(job.getId(), job.getResourceId(), job.getInputType().name(), job.getDisplayHint(),
                    job.getStatus().name(), job.getErrorCode(), job.getErrorMessage(), job.getCreatedAt(), job.getUpdatedAt());
        }
    }

    public record PageView<T>(List<T> items, long total, int page, int size) {

        public static <S, T> PageView<T> from(Page<S> source, Function<S, T> mapper) {
            return new PageView<>(source.items().stream().map(mapper).toList(),
                    source.total(), source.page(), source.size());
        }
    }

    public record FileView(int fileIndex, String relativePath, long sizeBytes, String v2PiecesRootHex) {

        static FileView from(TorrentFileInfo file) {
            return new FileView(file.getFileIndex(), file.getRelativePath(), file.getSizeBytes(), file.getV2PiecesRootHex());
        }
    }

    public record ResourceView(UUID id,
                               String name,
                               String versionType,
                               String btih,
                               String btmh,
                               Long pieceLength,
                               Long totalBytes,
                               boolean privateFlag,
                               String metadataSource,
                               List<String> trackers,
                               Integer fileCount,
                               boolean metadataReady,
                               LocalDateTime createdAt) {

        static ResourceView from(TorrentResource r) {
            return new ResourceView(r.getId(), r.getName(), r.getVersionType().name(),
                    Hex.encode(r.getBtih()), Hex.encode(r.getBtmh()),
                    r.getPieceLength(), r.getTotalBytes(), r.isPrivateFlag(), r.getMetadataSource(),
                    r.getTrackers(), r.getFileCount(), r.metadataReady(), r.getCreatedAt());
        }
    }

    public record ResourceDetailView(ResourceView resource, List<FileView> files) {

        public static ResourceDetailView from(ResourceDetail detail) {
            return new ResourceDetailView(ResourceView.from(detail.resource()),
                    detail.files().stream().map(FileView::from).toList());
        }
    }

    public record ErrorResponse(String code, String message) {
    }

    // ---------- 下载任务（规格 §9） ----------

    public record CreateTaskRequest(@NotNull UUID parseId,
                                    @NotEmpty List<Integer> fileIndices,
                                    String targetSubdirectory,
                                    Boolean startImmediately,
                                    Integer priority) {
    }

    public record PatchTaskRequest(Integer priority, Long speedLimit) {
    }

    public record TaskView(UUID id,
                           UUID resourceId,
                           String status,
                           long selectedBytes,
                           long verifiedBytes,
                           double progressPercent,
                           int priority,
                           Long speedLimit,
                           String targetSubdirectory,
                           String errorCode,
                           String errorMessage,
                           long version,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt,
                           LocalDateTime startedAt,
                           LocalDateTime completedAt) {

        public static TaskView from(DownloadTask t) {
            return new TaskView(t.getId(), t.getResourceId(), t.getStatus().name(), t.getSelectedBytes(),
                    t.getVerifiedBytes(), t.progressPercent(), t.getPriority(), t.getSpeedLimit(),
                    t.getTargetSubdirectory(), t.getErrorCode(), t.getErrorMessage(), t.getVersion(),
                    t.getCreatedAt(), t.getUpdatedAt(), t.getStartedAt(), t.getCompletedAt());
        }
    }

    public record TaskFileView(int fileIndex,
                               boolean selected,
                               long verifiedBytes,
                               String exportState,
                               String relativePath,
                               long sizeBytes) {

        public static TaskFileView from(DownloadTaskFile f) {
            return new TaskFileView(f.getFileIndex(), f.isSelected(), f.getVerifiedBytes(),
                    f.getExportState(), "", 0L);
        }

        public static TaskFileView from(DownloadTaskFile f, TorrentFileInfo info) {
            return new TaskFileView(f.getFileIndex(), f.isSelected(), f.getVerifiedBytes(),
                    f.getExportState(),
                    info == null ? "" : info.getRelativePath(),
                    info == null ? 0L : info.getSizeBytes());
        }
    }

    public record ExportView(UUID id,
                             int fileIndex,
                             String relativePath,
                             String state,
                             long bytes,
                             String errorMessage) {

        public static ExportView from(FileExport e) {
            return new ExportView(e.getId(), e.getFileIndex(), e.getRelativePath(),
                    e.getState().name(), e.getBytes(), e.getErrorMessage());
        }
    }

    public record TaskEventView(long seq, String eventType, String payloadJson, LocalDateTime createdAt) {

        public static TaskEventView from(TaskEvent e) {
            return new TaskEventView(e.getSeq(), e.getEventType(), e.getPayloadJson(), e.getCreatedAt());
        }
    }
}
