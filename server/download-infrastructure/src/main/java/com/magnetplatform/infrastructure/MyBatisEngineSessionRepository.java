package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.infrastructure.mapper.EngineSessionMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisEngineSessionRepository implements EngineSessionRepository {

    private final EngineSessionMapper mapper;

    public MyBatisEngineSessionRepository(EngineSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType) {
        return Optional.ofNullable(mapper.findByResource(resourceId, engineType));
    }

    @Override
    public void insert(EngineSessionRecord record) {
        mapper.insert(record);
    }

    @Override
    public void updateState(UUID id, String state, LocalDateTime updatedAt) {
        mapper.updateState(id, state, updatedAt);
    }
}
