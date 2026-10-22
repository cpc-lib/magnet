package com.magnetplatform.domain.parse;

import java.time.LocalDateTime;
import java.util.UUID;

public final class ParseJob {

    private final UUID id;
    private final UUID userId;
    private final UUID resourceId;
    private final InputType inputType;
    private final byte[] inputHash;
    private final String inputUri;
    private final String inputPath;
    private final String displayHint;
    private final ParseStatus status;
    private final String errorCode;
    private final String errorMessage;
    private final long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** MyBatis 用：匹配 wrapper 类型（Byte[]） */
    public ParseJob(UUID id, UUID userId, UUID resourceId, InputType inputType, Byte[] inputHash,
                    String inputUri, String inputPath, String displayHint, ParseStatus status,
                    String errorCode, String errorMessage, Long version, LocalDateTime createdAt,
                    LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.resourceId = resourceId;
        this.inputType = inputType;
        this.inputHash = inputHash == null ? null : unwrap(inputHash);
        this.inputUri = inputUri;
        this.inputPath = inputPath;
        this.displayHint = displayHint;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.version = version == null ? 0L : version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 应用层用：primitive 入参 */
    public ParseJob(UUID id, UUID userId, UUID resourceId, InputType inputType, byte[] inputHash,
                    String inputUri, String inputPath, String displayHint, ParseStatus status,
                    String errorCode, String errorMessage, long version, LocalDateTime createdAt,
                    LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.resourceId = resourceId;
        this.inputType = inputType;
        this.inputHash = inputHash;
        this.inputUri = inputUri;
        this.inputPath = inputPath;
        this.displayHint = displayHint;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private static byte[] unwrap(Byte[] wrapped) {
        byte[] r = new byte[wrapped.length];
        for (int i = 0; i < wrapped.length; i++) r[i] = wrapped[i];
        return r;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResourceId() { return resourceId; }
    public InputType getInputType() { return inputType; }
    public byte[] getInputHash() { return inputHash; }
    public String getInputUri() { return inputUri; }
    public String getInputPath() { return inputPath; }
    public String getDisplayHint() { return displayHint; }
    public ParseStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
