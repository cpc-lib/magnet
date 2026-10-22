package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.ResourceGcRepository;
import com.magnetplatform.infrastructure.mapper.ResourceGcMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisResourceGcRepository implements ResourceGcRepository {

    private final ResourceGcMapper mapper;

    public MyBatisResourceGcRepository(ResourceGcMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void upsertCandidate(UUID resourceId, LocalDateTime candidateAt,
                                LocalDateTime notBefore, LocalDateTime updatedAt) {
        mapper.upsertCandidate(resourceId, candidateAt, notBefore, updatedAt);
    }

    @Override
    public List<UUID> findDue(LocalDateTime now, int limit) {
        return mapper.findDue(now, limit);
    }

    @Override
    public void removeCandidate(UUID resourceId) {
        mapper.removeCandidate(resourceId);
    }

    @Override
    public void recordFailure(UUID resourceId, String lastError, LocalDateTime updatedAt) {
        mapper.recordFailure(resourceId, lastError, updatedAt);
    }
}
