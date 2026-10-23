package com.magnetplatform.application.port;

import com.magnetplatform.domain.task.EngineSessionRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineSessionRepository {

    Optional<EngineSessionRecord> findByResource(UUID resourceId, String engineType);

    void insert(EngineSessionRecord record);

    void updateState(UUID id, String state, LocalDateTime updatedAt);

    /** 持久化 resume 版本与路径（规格 §12.1：saveResume 后落库） */
    void updateResume(UUID id, long resumeVersion, String resumePath, LocalDateTime updatedAt);

    /** 重启/调度时需重新挂载到引擎的会话（规格 §12.1/§5.3） */
    List<EngineSessionRecord> findRecoverable();
}
