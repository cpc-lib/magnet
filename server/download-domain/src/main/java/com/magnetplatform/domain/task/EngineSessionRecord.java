package com.magnetplatform.domain.task;

import java.time.LocalDateTime;
import java.util.UUID;

/** 引擎会话持久化记录（规格 §8 engine_session；handle 不入库，仅存可重建标识） */
public final class EngineSessionRecord {

    private final UUID id;
    private final UUID resourceId;
    private final String workerId;
    private final String engineType;
    private final String state;
    private final long ownerEpoch;
    private final LocalDateTime leaseUntil;
    private final long resumeVersion;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String resumePath;

    public EngineSessionRecord(UUID id, UUID resourceId, String workerId, String engineType, String state,
                               long ownerEpoch, LocalDateTime leaseUntil, long resumeVersion,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, resourceId, workerId, engineType, state, ownerEpoch, leaseUntil, resumeVersion,
                createdAt, updatedAt, null);
    }

    public EngineSessionRecord(UUID id, UUID resourceId, String workerId, String engineType, String state,
                               long ownerEpoch, LocalDateTime leaseUntil, long resumeVersion,
                               LocalDateTime createdAt, LocalDateTime updatedAt, String resumePath) {
        this.id = id;
        this.resourceId = resourceId;
        this.workerId = workerId;
        this.engineType = engineType;
        this.state = state;
        this.ownerEpoch = ownerEpoch;
        this.leaseUntil = leaseUntil;
        this.resumeVersion = resumeVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resumePath = resumePath;
    }

    /** MyBatis 反射专用：包装类型入参 */
    public EngineSessionRecord(UUID id, UUID resourceId, String workerId, String engineType, String state,
                               Long ownerEpoch, LocalDateTime leaseUntil, Long resumeVersion,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, resourceId, workerId, engineType, state, ownerEpoch, leaseUntil, resumeVersion,
                createdAt, updatedAt, null);
    }

    /** MyBatis 反射专用：包装类型入参 */
    public EngineSessionRecord(UUID id, UUID resourceId, String workerId, String engineType, String state,
                               Long ownerEpoch, LocalDateTime leaseUntil, Long resumeVersion,
                               LocalDateTime createdAt, LocalDateTime updatedAt, String resumePath) {
        this(id, resourceId, workerId, engineType, state,
                ownerEpoch == null ? 0L : ownerEpoch, leaseUntil,
                resumeVersion == null ? 0L : resumeVersion, createdAt, updatedAt, resumePath);
    }

    public UUID getId() { return id; }
    public UUID getResourceId() { return resourceId; }
    public String getWorkerId() { return workerId; }
    public String getEngineType() { return engineType; }
    public String getState() { return state; }
    public long getOwnerEpoch() { return ownerEpoch; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public long getResumeVersion() { return resumeVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getResumePath() { return resumePath; }
}
