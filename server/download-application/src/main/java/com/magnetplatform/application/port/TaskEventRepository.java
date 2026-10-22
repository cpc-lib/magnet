package com.magnetplatform.application.port;

import com.magnetplatform.domain.task.TaskEvent;

import java.util.List;
import java.util.UUID;

public interface TaskEventRepository {

    /** seq 由实现按 (task_id, seq) 单调递增分配 */
    void append(UUID taskId, String eventType, String payloadJson);

    /** afterSeq 之后的事件，按 seq 升序（SSE Last-Event-ID 补拉，规格 §9.2） */
    List<TaskEvent> findAfter(UUID taskId, long afterSeq, int limit);
}
