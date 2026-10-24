package com.magnetplatform.application;

import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.SessionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringMetricsTest {

    private SimpleMeterRegistry registry;
    private MonitoringMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MonitoringMetrics(registry);
    }

    @Test
    void replacingOneSharedSessionDoesNotMultiplyDownloadRate() {
        EngineSnapshot snapshot = new EngineSnapshot(
                "resource-1", SessionState.ACTIVE, true, 90L,
                80L, 7L, 3, List.of(90L));

        metrics.recordSessionSnapshot("resource-1", EngineKind.LIBTORRENT, snapshot);
        metrics.recordSessionSnapshot("resource-1", EngineKind.LIBTORRENT, snapshot);

        assertEquals(80D, registry.get("magnet_engine_download_rate_bytes_per_second")
                .tag("engine", "LIBTORRENT").gauge().value());
        assertEquals(3D, registry.get("magnet_engine_peers")
                .tag("engine", "LIBTORRENT").gauge().value());
    }

    @Test
    void taskStatusRefreshReplacesPreviousGaugeValues() {
        metrics.refreshTaskCounts(List.of(
                new TaskStatusEngineCount(TaskStatus.DOWNLOADING, EngineKind.LIBTORRENT, 2L)));

        assertEquals(2D, registry.get("magnet_tasks")
                .tag("status", "DOWNLOADING").tag("engine", "LIBTORRENT")
                .gauge().value());

        metrics.refreshTaskCounts(List.of());
        assertEquals(0D, registry.get("magnet_tasks")
                .tag("status", "DOWNLOADING").tag("engine", "LIBTORRENT")
                .gauge().value());
    }

    @Test
    void operationCountersUseOnlyControlledLabels() {
        metrics.recordOperation("parse", "success", "NONE");

        var meter = registry.get("magnet_task_operations_total")
                .tag("operation", "parse").tag("result", "success")
                .tag("error_type", "NONE").counter();

        assertEquals(1D, meter.count());
        Set<String> tagKeys = meter.getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertEquals(Set.of("operation", "result", "error_type"), tagKeys);
        assertTrue(meter.getId().getTags().stream()
                .allMatch(tag -> Set.of("operation", "result", "error_type")
                        .contains(tag.getKey())));
    }
}
