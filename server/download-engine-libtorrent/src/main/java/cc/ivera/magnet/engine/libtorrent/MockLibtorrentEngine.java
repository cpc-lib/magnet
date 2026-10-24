package cc.ivera.magnet.engine.libtorrent;

import cc.ivera.magnet.spi.DownloadEngine;
import cc.ivera.magnet.spi.EngineCapabilities;
import cc.ivera.magnet.spi.EngineKind;
import cc.ivera.magnet.spi.EngineOptions;
import cc.ivera.magnet.spi.EngineSession;
import cc.ivera.magnet.spi.EngineSnapshot;
import cc.ivera.magnet.spi.ResourceDescriptor;
import cc.ivera.magnet.spi.ResumeSnapshot;
import cc.ivera.magnet.spi.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * libtorrent 引擎 Mock（P0+P1 阶段）。gRPC Worker 由 native/libtorrent-worker 提供，
 * P2 里程碑接入真实 C++ Sidecar 后以 GrpcLibtorrentEngine 替换（通过
 * magnet.engine.libtorrent.grpc.enabled=true 切换）。
 * 当前仅记录日志并返回空快照，使解析链路可端到端跑通；Magnet 元数据解析
 * 需真实引擎，超时由调度器清扫为 TIMEOUT（属预期行为）。
 */
@Component
@ConditionalOnProperty(name = "magnet.engine.libtorrent.grpc.enabled", havingValue = "false", matchIfMissing = true)
public class MockLibtorrentEngine implements DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(MockLibtorrentEngine.class);

    @Override
    public EngineKind kind() {
        return EngineKind.LIBTORRENT;
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(true, true, true, false, true, true);
    }

    @Override
    public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
        log.info("[mock-libtorrent] createOrAttach resourceId={} metadataOnly={} storageDomain={}",
                resource.resourceId(), options.metadataOnly(), options.storageDomain());
        // engineSessionId 约定即 resourceId（与 Grpc 适配器一致，同一资源同一引擎仅一个活跃会话）
        return new EngineSession(resource.resourceId(), EngineKind.LIBTORRENT, SessionState.DISCOVERING);
    }

    @Override
    public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        log.info("[mock-libtorrent] setDemand session={} files={}", engineSessionId, requestedFileIndices);
    }

    @Override
    public void pauseSession(String engineSessionId) {
        log.info("[mock-libtorrent] pauseSession session={}", engineSessionId);
    }

    @Override
    public void resumeSession(String engineSessionId) {
        log.info("[mock-libtorrent] resumeSession session={}", engineSessionId);
    }

    @Override
    public EngineSnapshot snapshot(String engineSessionId) {
        return EngineSnapshot.empty(engineSessionId);
    }

    @Override
    public ResumeSnapshot saveResume(String engineSessionId) {
        log.info("[mock-libtorrent] saveResume session={}", engineSessionId);
        return new ResumeSnapshot(engineSessionId, 0L, new byte[0]);
    }

    @Override
    public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        log.info("[mock-libtorrent] removeSession session={} removeData={}", engineSessionId, removeUnreferencedData);
    }
}
