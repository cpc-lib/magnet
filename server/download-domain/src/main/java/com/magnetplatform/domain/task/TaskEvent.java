package com.magnetplatform.domain.task;

import java.time.LocalDateTime;
import java.util.UUID;

/** 任务事件审计（规格 §8 task_event；SSE 按 seq 去重/补拉，§9.2） */
public final class TaskEvent {

    private final UUID id;
    private final UUID taskId;
    private final long seq;
    private final String eventType;
    private final String payloadJson;
    private final LocalDateTime createdAt;

    public TaskEvent(UUID id, UUID taskId, long seq, String eventType, String payloadJson,
                     LocalDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.seq = seq;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    /** MyBatis 反射专用：包装类型入参 */
    public TaskEvent(UUID id, UUID taskId, Long seq, String eventType, String payloadJson,
                     LocalDateTime createdAt) {
        this(id, taskId, seq == null ? 0L : seq, eventType, payloadJson, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public long getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
