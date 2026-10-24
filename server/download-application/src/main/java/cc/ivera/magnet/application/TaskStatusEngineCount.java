package cc.ivera.magnet.application;

import cc.ivera.magnet.domain.task.TaskStatus;
import cc.ivera.magnet.spi.EngineKind;

public record TaskStatusEngineCount(TaskStatus status, EngineKind engine, Long count) {
    public TaskStatusEngineCount(TaskStatus status, EngineKind engine, long count) {
        this(status, engine, Long.valueOf(count));
    }
}
