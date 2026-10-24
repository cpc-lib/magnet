package cc.ivera.magnet.infrastructure.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ResourceGcMapper {

    void upsertCandidate(@Param("resourceId") UUID resourceId,
                         @Param("candidateAt") LocalDateTime candidateAt,
                         @Param("notBefore") LocalDateTime notBefore,
                         @Param("updatedAt") LocalDateTime updatedAt);

    List<UUID> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    void removeCandidate(@Param("resourceId") UUID resourceId);

    void recordFailure(@Param("resourceId") UUID resourceId,
                       @Param("lastError") String lastError,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
