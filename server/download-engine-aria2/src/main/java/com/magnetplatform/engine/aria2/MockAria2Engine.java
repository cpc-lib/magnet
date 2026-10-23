package com.magnetplatform.engine.aria2;

import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineCapabilities;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import com.magnetplatform.spi.ResumeSnapshot;
import com.magnetplatform.spi.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * aria2 引擎 Mock 实现。
 * 当 magnet.engine.aria2.rpc.enabled=false（默认）时生效，
 * 用于单元测试与本地无需运行 aria2 守护进程的场景。
 */
@Component
@ConditionalOnProperty(name = "magnet.engine.aria2.rpc.enabled", havingValue = "false", matchIfMissing = true)
public class MockAria2Engine implements DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(MockAria2Engine.class);

    private static class MockSession {
        SessionState state;
        long totalLength;
        long completedLength;
        long speed;

        MockSession(SessionState state, long totalLength) {
            this.state = state;
            this.totalLength = totalLength;
            this.completedLength = 0L;
            this.speed = 256 * 1024L; // 256 KB/s
        }
    }

    private final Map<String, MockSession> sessions = new ConcurrentHashMap<>();

    @Override
    public EngineKind kind() {
        return EngineKind.ARIA2;
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(false, false, false, true, false, true);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
        log.info("[mock-aria2] createOrAttach resourceId={} uri={}", resource.resourceId(), resource.metadataUri());
        MockSession session = sessions.computeIfAbsent(resource.resourceId(),
                id -> new MockSession(SessionState.ACTIVE, 1024 * 1024L));
        return new EngineSession(resource.resourceId(), EngineKind.ARIA2, session.state);
    }

    @Override
    public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        log.info("[mock-aria2] setDemand session={} files={}", engineSessionId, requestedFileIndices);
    }

    @Override
    public void pauseSession(String engineSessionId) {
        log.info("[mock-aria2] pauseSession session={}", engineSessionId);
        MockSession session = sessions.get(engineSessionId);
        if (session != null) {
            session.state = SessionState.IDLE;
        }
    }

    @Override
    public void resumeSession(String engineSessionId) {
        log.info("[mock-aria2] resumeSession session={}", engineSessionId);
        MockSession session = sessions.get(engineSessionId);
        if (session != null) {
            session.state = SessionState.ACTIVE;
        }
    }

    @Override
    public EngineSnapshot snapshot(String engineSessionId) {
        MockSession session = sessions.get(engineSessionId);
        if (session == null) {
            return EngineSnapshot.empty(engineSessionId);
        }

        if (session.state == SessionState.ACTIVE && session.completedLength < session.totalLength) {
            session.completedLength = Math.min(session.totalLength, session.completedLength + session.speed);
        }

        return new EngineSnapshot(engineSessionId, session.state, true,
                session.completedLength, session.speed, 0L, 1, List.of(session.completedLength));
    }

    @Override
    public ResumeSnapshot saveResume(String engineSessionId) {
        log.info("[mock-aria2] saveResume session={}", engineSessionId);
        return new ResumeSnapshot(engineSessionId, 1L, new byte[0]);
    }

    @Override
    public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        log.info("[mock-aria2] removeSession session={} removeData={}", engineSessionId, removeUnreferencedData);
        sessions.remove(engineSessionId);
    }

    /** 测试辅助：设置指定会话的进度与总大小 */
    public void setSessionProgress(String engineSessionId, long completed, long total) {
        MockSession session = sessions.computeIfAbsent(engineSessionId, id -> new MockSession(SessionState.ACTIVE, total));
        session.completedLength = completed;
        session.totalLength = total;
    }
}
