package com.magnetplatform.application;

import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.SessionState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class MonitoringMetrics {
    private final MeterRegistry registry;
    private final Map<String, SessionMetric> sessions = new HashMap<>();
    private final Map<String, AtomicLong> taskCounts = new HashMap<>();
    private final Map<EngineKind, AtomicLong> downloadRates = new EnumMap<>(EngineKind.class);
    private final Map<EngineKind, AtomicLong> uploadRates = new EnumMap<>(EngineKind.class);
    private final Map<EngineKind, AtomicLong> peers = new EnumMap<>(EngineKind.class);
    private final Map<String, AtomicLong> sessionCounts = new HashMap<>();
    private final AtomicLong sseConnections = new AtomicLong();

    public MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerStableGauges();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public synchronized void clearSession(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            refreshSessionGauges();
        }
    }

    public synchronized void recordSessionSnapshot(
            String sessionId, EngineKind engine, EngineSnapshot snapshot) {
        sessions.put(sessionId, new SessionMetric(engine, snapshot.state().name(),
                snapshot.downloadRateBytesPerSec(),
                snapshot.uploadRateBytesPerSec(), snapshot.peerCount()));
        refreshSessionGauges();
    }

    public synchronized void clearSessionSnapshots() {
        sessions.clear();
        refreshSessionGauges();
    }

    public synchronized void refreshTaskCounts(List<TaskStatusEngineCount> counts) {
        taskCounts.values().forEach(value -> value.set(0L));
        counts.forEach(count -> taskCount(count.status().name(), count.engine().name())
                .set(count.count()));
    }

    public void recordOperation(String operation, String result, String errorType) {
        Counter.builder("magnet_task_operations_total")
                .tag("operation", operation)
                .tag("result", result)
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    public void incrementSseConnections() {
        sseConnections.incrementAndGet();
    }

    public void decrementSseConnections() {
        sseConnections.decrementAndGet();
    }

    public void recordSseDisconnect(String reason) {
        Counter.builder("magnet_sse_disconnects_total")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    private void registerStableGauges() {
        for (EngineKind engine : EngineKind.values()) {
            AtomicLong downloadRate = new AtomicLong();
            downloadRates.put(engine, downloadRate);
            Gauge.builder("magnet_engine_download_rate_bytes_per_second", downloadRate, AtomicLong::doubleValue)
                    .tag("engine", engine.name()).register(registry);

            AtomicLong uploadRate = new AtomicLong();
            uploadRates.put(engine, uploadRate);
            Gauge.builder("magnet_engine_upload_rate_bytes_per_second", uploadRate, AtomicLong::doubleValue)
                    .tag("engine", engine.name()).register(registry);

            AtomicLong peerCount = new AtomicLong();
            peers.put(engine, peerCount);
            Gauge.builder("magnet_engine_peers", peerCount, AtomicLong::doubleValue)
                    .tag("engine", engine.name()).register(registry);

            for (SessionState status : SessionState.values()) {
                AtomicLong sessionCount = new AtomicLong();
                sessionCounts.put(sessionKey(engine, status.name()), sessionCount);
                Gauge.builder("magnet_engine_sessions", sessionCount, AtomicLong::doubleValue)
                        .tags("engine", engine.name(), "status", status.name())
                        .register(registry);
            }
        }
        Gauge.builder("magnet_sse_connections", sseConnections, AtomicLong::doubleValue)
                .register(registry);
    }

    private void refreshSessionGauges() {
        downloadRates.values().forEach(value -> value.set(0L));
        uploadRates.values().forEach(value -> value.set(0L));
        peers.values().forEach(value -> value.set(0L));
        sessionCounts.values().forEach(value -> value.set(0L));

        sessions.values().forEach(session -> {
            downloadRates.get(session.engine()).addAndGet(session.downloadRate());
            uploadRates.get(session.engine()).addAndGet(session.uploadRate());
            peers.get(session.engine()).addAndGet(session.peerCount());
            sessionCounts.get(sessionKey(session.engine(), session.status())).incrementAndGet();
        });
    }

    private AtomicLong taskCount(String status, String engine) {
        String key = status + ':' + engine;
        return taskCounts.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("magnet_tasks", value, AtomicLong::doubleValue)
                    .tags("status", status, "engine", engine)
                    .register(registry);
            return value;
        });
    }

    private static String sessionKey(EngineKind engine, String status) {
        return engine.name() + ':' + status;
    }

    private record SessionMetric(EngineKind engine, String status,
                                 long downloadRate, long uploadRate, long peerCount) {
    }
}
