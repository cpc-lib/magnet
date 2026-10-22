package com.magnetplatform.infrastructure.mapper;

import com.magnetplatform.domain.task.EngineSessionRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface EngineSessionMapper {

    EngineSessionRecord findByResource(@Param("resourceId") UUID resourceId,
                                       @Param("engineType") String engineType);

    void insert(EngineSessionRecord record);

    void updateState(@Param("id") UUID id, @Param("state") String state,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
