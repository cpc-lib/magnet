package com.magnetplatform.application;

import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.spi.EngineKind;

public record TaskStatusEngineCount(TaskStatus status, EngineKind engine, long count) {
}
