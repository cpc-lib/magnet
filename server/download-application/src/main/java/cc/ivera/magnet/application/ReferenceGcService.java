package cc.ivera.magnet.application;

import cc.ivera.magnet.application.port.DownloadStorage;
import cc.ivera.magnet.application.port.DownloadTaskRepository;
import cc.ivera.magnet.application.port.EngineSessionRepository;
import cc.ivera.magnet.application.port.FileExportRepository;
import cc.ivera.magnet.application.port.ResourceGcRepository;
import cc.ivera.magnet.domain.task.EngineSessionRecord;
import cc.ivera.magnet.spi.DownloadEngine;
import cc.ivera.magnet.spi.EngineKind;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 共享数据引用计数与垃圾回收（规格 §10.2）。
 * 仅当某资源无任何未删除任务、无活跃导出时才标记为 GC 候选；
 * 经过宽限期后再次核查，确认仍无引用才调用引擎删除会话与物理数据。
 */
@Service
public class ReferenceGcService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceGcService.class);

    private final DownloadTaskRepository tasks;
    private final FileExportRepository exports;
    private final ResourceGcRepository gc;
    private final EngineSessionRepository sessions;
    private final DownloadStorage storage;
    private final List<DownloadEngine> engines;
    private final long graceSeconds;
    private final String workerId;
    private final int leaseSeconds;

    public ReferenceGcService(DownloadTaskRepository tasks,
                              FileExportRepository exports,
                              ResourceGcRepository gc,
                              EngineSessionRepository sessions,
                              DownloadStorage storage,
                              List<DownloadEngine> engines,
                              @Value("${magnet.gc.grace-seconds:300}") long graceSeconds,
                              @Value("${magnet.worker.id:local-worker-1}") String workerId,
                              @Value("${magnet.worker.lease-seconds:30}") int leaseSeconds) {
        this.tasks = tasks;
        this.exports = exports;
        this.gc = gc;
        this.sessions = sessions;
        this.storage = storage;
        this.engines = engines;
        this.graceSeconds = graceSeconds;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
    }

    /** 扫描有会话的资源，对无引用者标记 GC 候选（宽限期后才真正删除）。 */
    @Transactional
    @Timed(value = "magnet_gc_duration_seconds", histogram = true)
    public void markCandidates() {
        LocalDateTime now = LocalDateTime.now();
        for (EngineSessionRecord session : sessions.findRecoverable()) {
            UUID resourceId = session.getResourceId();
            if (tasks.countNonDeletedByResource(resourceId) > 0) {
                continue;
            }
            if (exports.countActiveByResource(resourceId) > 0) {
                continue;
            }
            gc.upsertCandidate(resourceId, now, now.plusSeconds(graceSeconds), now);
            log.info("[gc] 标记资源为回收候选 resource={} graceSeconds={}", resourceId, graceSeconds);
        }
    }

    /** 清扫到期候选：复核无引用后删除引擎会话与物理数据（规格 §10.2 容错：异常保留候选待下次）。 */
    @Timed(value = "magnet_gc_duration_seconds", histogram = true)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        for (UUID resourceId : gc.findDue(now, 50)) {
            if (tasks.countNonDeletedByResource(resourceId) > 0
                    || exports.countActiveByResource(resourceId) > 0) {
                gc.removeCandidate(resourceId);
                continue;
            }
            try {
                EngineSessionRecord session = sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name())
                        .or(() -> sessions.findByResource(resourceId, EngineKind.ARIA2.name()))
                        .orElse(null);
                if (session == null) {
                    gc.removeCandidate(resourceId);
                    continue;
                }
                String engineType = session.getEngineType();
                DownloadEngine reachable = engineFor(engineType);
                if (reachable == null || !reachable.available()) {
                    throw new IllegalStateException("Worker unavailable for GC");
                }
                if (!workerId.equals(session.getWorkerId())
                        || !sessions.renewLease(resourceId, engineType, workerId,
                                session.getOwnerEpoch(), leaseSeconds)) {
                    throw new IllegalStateException("GC requires a live owned resource lease");
                }
                EngineSessionRecord owned = sessions.findByResource(resourceId, engineType).orElseThrow();
                sessions.withLease(session, () -> {
                    if (tasks.countNonDeletedByResource(resourceId) > 0
                            || exports.countActiveByResource(resourceId) > 0) {
                        gc.removeCandidate(resourceId);
                        return;
                    }
                    DownloadEngine engine = engineFor(owned.getEngineType());
                    if (engine == null) throw new IllegalStateException("Worker unavailable for GC");
                    engine.renewLease(resourceId.toString(), owned.getOwnerEpoch(),
                            owned.getLeaseUntil().toInstant(java.time.ZoneOffset.UTC));
                    engine.removeSession(session.getId().toString(), true);
                    if (EngineKind.ARIA2.name().equals(engineType)) {
                        // aria2 只移除下载记录；确认停止后在资源行锁内清理宿主机数据。
                        storage.deleteTree(storage.resourceRoot(resourceId));
                    }
                    java.nio.file.Path resume = storage.resumeFile(resourceId);
                    try {
                        java.nio.file.Files.deleteIfExists(resume);
                    } catch (java.io.IOException ignored) {
                        // resume 文件删除失败不阻塞资源目录回收
                    }
                    gc.removeCandidate(resourceId);
                    if (!sessions.updateState(session.getId(), "STOPPED", now, workerId, session.getOwnerEpoch())) {
                        throw new IllegalStateException("GC ownership lost");
                    }
                    log.info("[gc] 回收资源物理数据完成 resource={}", resourceId);
                });
            } catch (RuntimeException e) {
                gc.recordFailure(resourceId, e.getMessage(), now);
                log.warn("[gc] 回收资源失败 resource={}: {}", resourceId, e.getMessage());
            }
        }
    }

    private DownloadEngine engineFor(String engineTypeName) {
        try {
            EngineKind kind = EngineKind.valueOf(engineTypeName);
            return engines.stream().filter(e -> e.kind() == kind).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private DownloadEngine libtorrentEngine() {
        return engineFor(EngineKind.LIBTORRENT.name());
    }
}
