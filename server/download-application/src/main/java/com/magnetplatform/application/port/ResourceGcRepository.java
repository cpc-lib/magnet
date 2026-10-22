package com.magnetplatform.application.port;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ResourceGcRepository {

    void upsertCandidate(UUID resourceId, LocalDateTime candidateAt,
                         LocalDateTime notBefore, LocalDateTime updatedAt);

    List<UUID> findDue(LocalDateTime now, int limit);

    void removeCandidate(UUID resourceId);

    void recordFailure(UUID resourceId, String lastError, LocalDateTime updatedAt);
}
