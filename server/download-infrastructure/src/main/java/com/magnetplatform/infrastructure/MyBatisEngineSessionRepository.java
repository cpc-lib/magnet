package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.infrastructure.mapper.EngineSessionMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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
    public boolean updateState(UUID id, String state, LocalDateTime updatedAt, String workerId, long ownerEpoch) {
        return mapper.updateState(id, state, updatedAt, workerId, ownerEpoch) > 0;
    }

    @Override
    public boolean updateResume(UUID id, long resumeVersion, String resumePath, LocalDateTime updatedAt,
                                String workerId, long ownerEpoch) {
        return mapper.updateResume(id, resumeVersion, resumePath, updatedAt, workerId, ownerEpoch) > 0;
    }

    @Override
    public List<EngineSessionRecord> findRecoverable() {
        return mapper.findRecoverable();
    }

    @Override
    @Transactional
    public void withLease(EngineSessionRecord expected, Runnable work) {
        lockResource(expected.getResourceId());
        if (mapper.lockOwned(expected) == null) throw new IllegalStateException("Resource ownership lost");
        work.run();
        EngineSessionRecord after = mapper.findByResource(expected.getResourceId(), expected.getEngineType());
        if (after != null && "STOPPED".equals(after.getState())
                && expected.getWorkerId().equals(after.getWorkerId())
                && expected.getOwnerEpoch() == after.getOwnerEpoch()) return;
        if (mapper.lockOwned(expected) == null) throw new IllegalStateException("Resource lease expired during write");
    }

    @Override
    @Transactional
    public void lockResource(UUID resourceId) {
        if (mapper.lockResource(resourceId) == null) throw new IllegalStateException("Resource missing");
    }

    @Override
    public boolean insertIfAbsent(EngineSessionRecord record) {
        return mapper.insertIfAbsent(record) > 0;
    }

    @Override
    public boolean renewLease(UUID resourceId, String engineType, String workerId,
                              long ownerEpoch, int leaseSeconds) {
        return mapper.renewLease(resourceId, engineType, workerId, ownerEpoch, leaseSeconds) > 0;
    }

    @Override
    @Transactional
    public Optional<EngineSessionRecord> acquireLease(UUID resourceId, String engineType,
                                                      String workerId, int leaseSeconds) {
        if (mapper.acquireLease(resourceId, engineType, workerId, leaseSeconds) == 0) {
            return Optional.empty();
        }
        // CAS 胜出后回读新租约（worker_id 已置为本人，读到的即本人记录）
        return Optional.ofNullable(mapper.findByResource(resourceId, engineType));
    }
}
