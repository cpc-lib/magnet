package com.magnetplatform.domain.task;

import java.time.LocalDateTime;
import java.util.UUID;

/** 用户逻辑下载任务（规格 §4.3）：独立于共享会话，持有选中文件集、进度、限速、状态 */
public final class DownloadTask {

    private final UUID id;
    private final UUID userId;
    private final UUID resourceId;
    private final TaskStatus status;
    private final long selectedBytes;
    private final long verifiedBytes;
    private final int priority;
    private final Long speedLimit;
    private final String targetSubdirectory;
    private final String errorCode;
    private final String errorMessage;
    private final long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
    private final LocalDateTime deletedAt;

    public DownloadTask(UUID id, UUID userId, UUID resourceId, TaskStatus status,
                        long selectedBytes, long verifiedBytes, int priority, Long speedLimit,
                        String targetSubdirectory, String errorCode, String errorMessage, long version,
                        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime startedAt,
                        LocalDateTime completedAt, LocalDateTime deletedAt) {
        this.id = id;
        this.userId = userId;
        this.resourceId = resourceId;
        this.status = status;
        this.selectedBytes = selectedBytes;
        this.verifiedBytes = verifiedBytes;
        this.priority = priority;
        this.speedLimit = speedLimit;
        this.targetSubdirectory = targetSubdirectory;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.deletedAt = deletedAt;
    }

    /** MyBatis 反射专用：JDBC 加载的数值是包装类型（Long/Integer），与 primitive 构造函数并存消除歧义 */
    public DownloadTask(UUID id, UUID userId, UUID resourceId, TaskStatus status,
                        Long selectedBytes, Long verifiedBytes, Integer priority, Long speedLimit,
                        String targetSubdirectory, String errorCode, String errorMessage, Long version,
                        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime startedAt,
                        LocalDateTime completedAt, LocalDateTime deletedAt) {
        this(id, userId, resourceId, status,
                selectedBytes == null ? 0L : selectedBytes,
                verifiedBytes == null ? 0L : verifiedBytes,
                priority == null ? 0 : priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage,
                version == null ? 0L : version,
                createdAt, updatedAt, startedAt, completedAt, deletedAt);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResourceId() { return resourceId; }
    public TaskStatus getStatus() { return status; }
    public long getSelectedBytes() { return selectedBytes; }
    public long getVerifiedBytes() { return verifiedBytes; }
    public int getPriority() { return priority; }
    public Long getSpeedLimit() { return speedLimit; }
    public String getTargetSubdirectory() { return targetSubdirectory; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }

    public double progressPercent() {
        return selectedBytes == 0 ? 100.0 : verifiedBytes * 100.0 / selectedBytes;
    }

    public DownloadTask withStatus(TaskStatus target, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, target, selectedBytes, verifiedBytes, priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                startedAt, completedAt, deletedAt);
    }

    public DownloadTask withError(String code, String message, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, verifiedBytes, priority,
                speedLimit, targetSubdirectory, code, message, version + 1, createdAt, now,
                startedAt, completedAt, deletedAt);
    }

    public DownloadTask withProgress(long newVerifiedBytes, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, newVerifiedBytes, priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                startedAt, completedAt, deletedAt);
    }

    public DownloadTask withLimits(Integer newPriority, Long newSpeedLimit, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, verifiedBytes,
                newPriority == null ? priority : newPriority,
                newSpeedLimit == null ? speedLimit : newSpeedLimit,
                targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                startedAt, completedAt, deletedAt);
    }

    public DownloadTask withStartedAt(LocalDateTime started, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, verifiedBytes, priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                started, completedAt, deletedAt);
    }

    public DownloadTask withCompletedAt(LocalDateTime completed, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, verifiedBytes, priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                startedAt, completed, deletedAt);
    }

    public DownloadTask withDeletedAt(LocalDateTime deleted, LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, status, selectedBytes, verifiedBytes, priority,
                speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt, now,
                startedAt, completedAt, deleted);
    }

    /** 单次版本递增完成 DELETED + 墓碑（避免链式 with* 造成版本号连跳导致 CAS 失败） */
    public DownloadTask markDeleted(LocalDateTime now) {
        return new DownloadTask(id, userId, resourceId, TaskStatus.DELETED, selectedBytes, verifiedBytes,
                priority, speedLimit, targetSubdirectory, errorCode, errorMessage, version + 1, createdAt,
                now, startedAt, completedAt, now);
    }
}
