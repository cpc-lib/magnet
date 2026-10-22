package com.magnetplatform.application.port;

import com.magnetplatform.domain.task.EngineSessionRecord;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EngineSessionRepository {

    Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType);

    void insert(EngineSessionRecord record);

    void updateState(UUID id, String state, LocalDateTime updatedAt);
}
