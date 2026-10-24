package cc.ivera.magnet.infrastructure;

import cc.ivera.magnet.application.port.ParseJobRepository;
import cc.ivera.magnet.domain.parse.ParseJob;
import cc.ivera.magnet.domain.parse.ParseStatus;
import cc.ivera.magnet.infrastructure.mapper.ParseJobMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisParseJobRepository implements ParseJobRepository {

    private final ParseJobMapper mapper;

    public MyBatisParseJobRepository(ParseJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ParseJob job) {
        mapper.insert(job);
    }

    @Override
    public Optional<ParseJob> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    @Override
    public List<ParseJob> findByUser(UUID userId, ParseStatus status, int offset, int limit) {
        return mapper.findByUser(userId, status, offset, limit);
    }

    @Override
    public long countByUser(UUID userId, ParseStatus status) {
        return mapper.countByUser(userId, status);
    }

    @Override
    public void markStatus(UUID id, ParseStatus status, String errorCode, String errorMessage) {
        mapper.markStatus(id, status, errorCode, errorMessage, LocalDateTime.now());
    }

    @Override
    public boolean existsByUserAndResource(UUID userId, UUID resourceId) {
        return mapper.countByUserAndResource(userId, resourceId) > 0;
    }

    @Override
    public int markTimeoutResolving(LocalDateTime cutoff) {
        return mapper.markTimeoutResolving(cutoff, LocalDateTime.now());
    }

    @Override
    public List<ParseJob> findByStatus(ParseStatus status, int limit) {
        return mapper.findByStatus(status, limit);
    }
}
