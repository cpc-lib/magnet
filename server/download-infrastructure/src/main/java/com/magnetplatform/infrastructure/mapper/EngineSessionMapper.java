package com.magnetplatform.infrastructure.mapper;

import com.magnetplatform.domain.task.EngineSessionRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface EngineSessionMapper {

    EngineSessionRecord findByResource(@Param("resourceId") UUID resourceId,
                                       @Param("engineType") String engineType);

    void insert(EngineSessionRecord record);

    void updateState(@Param("id") UUID id, @Param("state") String state,
                     @Param("updatedAt") LocalDateTime updatedAt);

    void updateResume(@Param("id") UUID id,
                      @Param("resumeVersion") long resumeVersion,
                      @Param("resumePath") String resumePath,
                      @Param("updatedAt") LocalDateTime updatedAt);

    List<EngineSessionRecord> findRecoverable();
}
