package com.magnetplatform.application;

import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.ResourceGcRepository;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineKind;
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

    public ReferenceGcService(DownloadTaskRepository tasks,
                              FileExportRepository exports,
                              ResourceGcRepository gc,
                              EngineSessionRepository sessions,
                              DownloadStorage storage,
                              List<DownloadEngine> engines,
                              @Value("${magnet.gc.grace-seconds:300}") long graceSeconds) {
        this.tasks = tasks;
        this.exports = exports;
        this.gc = gc;
        this.sessions = sessions;
        this.storage = storage;
        this.engines = engines;
        this.graceSeconds = graceSeconds;
    }

    /** 扫描有会话的资源，对无引用者标记 GC 候选（宽限期后才真正删除）。 */
    @Transactional
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
    @Transactional
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
                        .orElse(null);
                if (session != null) {
                    DownloadEngine engine = libtorrentEngine();
                    if (engine != null) {
                        engine.removeSession(session.getId().toString(), true);
                    }
                }
                storage.deleteTree(storage.resourceRoot(resourceId));
                java.nio.file.Path resume = storage.resumeFile(resourceId);
                try {
                    java.nio.file.Files.deleteIfExists(resume);
                } catch (java.io.IOException ignored) {
                    // resume 文件删除失败不阻塞资源目录回收
                }
                gc.removeCandidate(resourceId);
                log.info("[gc] 回收资源物理数据完成 resource={}", resourceId);
            } catch (RuntimeException e) {
                gc.recordFailure(resourceId, e.getMessage(), now);
                log.warn("[gc] 回收资源失败 resource={}: {}", resourceId, e.getMessage());
            }
        }
    }

    private DownloadEngine libtorrentEngine() {
        return engines.stream().filter(e -> e.kind() == EngineKind.LIBTORRENT).findFirst().orElse(null);
    }
}
