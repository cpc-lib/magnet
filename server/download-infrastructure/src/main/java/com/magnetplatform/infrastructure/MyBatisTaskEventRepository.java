package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.TaskEventRepository;
import com.magnetplatform.domain.task.TaskEvent;
import com.magnetplatform.infrastructure.mapper.TaskEventMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisTaskEventRepository implements TaskEventRepository {

    private final TaskEventMapper mapper;

    public MyBatisTaskEventRepository(TaskEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(UUID taskId, String eventType, String payloadJson) {
        long next = mapper.maxSeq(taskId) + 1;
        mapper.insert(new TaskEvent(UUID.randomUUID(), taskId, next, eventType, payloadJson,
                LocalDateTime.now()));
    }

    @Override
    public List<TaskEvent> findAfter(UUID taskId, long afterSeq, int limit) {
        return mapper.findAfter(taskId, afterSeq, limit);
    }
}
