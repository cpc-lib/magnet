package com.magnetplatform.domain.export;

import java.time.LocalDateTime;
import java.util.UUID;

public final class FileExport {

    private final UUID id;
    private final UUID taskId;
    private final UUID userId;
    private final UUID resourceId;
    private final int fileIndex;
    private final String relativePath;
    private final String targetPath;
    private final ExportState state;
    private final long bytes;
    private final String errorCode;
    private final String errorMessage;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public FileExport(UUID id, UUID taskId, UUID userId, UUID resourceId, int fileIndex,
                      String relativePath, String targetPath, ExportState state, long bytes,
                      String errorCode, String errorMessage, LocalDateTime createdAt,
                      LocalDateTime updatedAt) {
        this.id = id;
        this.taskId = taskId;
        this.userId = userId;
        this.resourceId = resourceId;
        this.fileIndex = fileIndex;
        this.relativePath = relativePath;
        this.targetPath = targetPath;
        this.state = state;
        this.bytes = bytes;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** MyBatis 反射专用：包装类型入参 */
    public FileExport(UUID id, UUID taskId, UUID userId, UUID resourceId, Integer fileIndex,
                      String relativePath, String targetPath, ExportState state, Long bytes,
                      String errorCode, String errorMessage, LocalDateTime createdAt,
                      LocalDateTime updatedAt) {
        this(id, taskId, userId, resourceId, fileIndex == null ? 0 : fileIndex,
                relativePath, targetPath, state, bytes == null ? 0L : bytes,
                errorCode, errorMessage, createdAt, updatedAt);
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getUserId() { return userId; }
    public UUID getResourceId() { return resourceId; }
    public int getFileIndex() { return fileIndex; }
    public String getRelativePath() { return relativePath; }
    public String getTargetPath() { return targetPath; }
    public ExportState getState() { return state; }
    public long getBytes() { return bytes; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
