package cc.ivera.magnet.infrastructure;

import cc.ivera.magnet.application.port.TaskEventRepository;
import cc.ivera.magnet.domain.task.TaskEvent;
import cc.ivera.magnet.infrastructure.mapper.TaskEventMapper;
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
