# P7.1 Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add low-cardinality application/download-engine metrics, a Basic-Auth-protected Prometheus scrape endpoint, and a provisioned health-first Grafana dashboard launched by the Compose monitoring profile.

**Architecture:** Keep the API on the existing host process. Record domain metrics through a small Micrometer-backed component in download-application; expose them through Spring Boot Actuator in download-api; use a dedicated Basic Auth user/password file for Prometheus scraping. Prometheus and Grafana run only under infra/docker-compose.yml's monitoring profile.

**Tech Stack:** Java 21, Spring Boot 3.3.5 Actuator, Micrometer core/Prometheus registry, Spring Security Basic Auth plus existing JWT, MyBatis/MySQL, Docker Compose, Prometheus, Grafana, JUnit 5, AssertJ, Testcontainers.

---

### Task 1: Add the Micrometer metric recorder and red-green tests

**Files:**
- Create: server/download-application/src/main/java/com/magnetplatform/application/MonitoringMetrics.java
- Create: server/download-application/src/main/java/com/magnetplatform/application/TaskStatusEngineCount.java
- Create: server/download-application/src/test/java/com/magnetplatform/application/MonitoringMetricsTest.java
- Modify: server/download-application/pom.xml

- [ ] **Step 1: Write the failing metric behavior tests**

Use a SimpleMeterRegistry and specify the shared-session and low-cardinality behaviors:

    @Test
    void replacingOneSharedSessionDoesNotMultiplyDownloadRate() {
        EngineSnapshot snapshot = new EngineSnapshot(
                "resource-1", SessionState.ACTIVE, true, 90L,
                80L, 7L, 3, List.of(90L));

        metrics.recordSessionSnapshot("resource-1", EngineKind.LIBTORRENT, snapshot);
        metrics.recordSessionSnapshot("resource-1", EngineKind.LIBTORRENT, snapshot);

        assertThat(registry.get("magnet_engine_download_rate_bytes_per_second")
                .tag("engine", "LIBTORRENT").gauge().value()).isEqualTo(80D);
        assertThat(registry.get("magnet_engine_peers")
                .tag("engine", "LIBTORRENT").gauge().value()).isEqualTo(3D);
    }

    @Test
    void taskStatusRefreshReplacesPreviousGaugeValues() {
        metrics.refreshTaskCounts(List.of(
                new TaskStatusEngineCount(TaskStatus.DOWNLOADING, EngineKind.LIBTORRENT, 2L)));

        assertThat(registry.get("magnet_tasks")
                .tag("status", "DOWNLOADING").tag("engine", "LIBTORRENT")
                .gauge().value()).isEqualTo(2D);

        metrics.refreshTaskCounts(List.of());
        assertThat(registry.get("magnet_tasks")
                .tag("status", "DOWNLOADING").tag("engine", "LIBTORRENT")
                .gauge().value()).isZero();
    }

    @Test
    void operationCountersUseOnlyControlledLabels() {
        metrics.recordOperation("parse", "success", "NONE");

        assertThat(registry.get("magnet_task_operations_total")
                .tag("operation", "parse").tag("result", "success")
                .tag("error_type", "NONE").counter().count()).isEqualTo(1D);
    }

- [ ] **Step 2: Run the focused test and verify the expected missing-type failure**

Run:

    mvn -pl server/download-application "-Dtest=MonitoringMetricsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Expected: compilation/test failure because MonitoringMetrics and TaskStatusEngineCount do not exist.

- [ ] **Step 3: Add the minimal Micrometer dependency and implementation**

Add io.micrometer:micrometer-core to download-application/pom.xml. Implement TaskStatusEngineCount as a record with TaskStatus status, EngineKind engine, and long count. Implement MonitoringMetrics with:

    @Component
    public final class MonitoringMetrics {
        private final MeterRegistry registry;
        private final Map<String, SessionMetric> sessions = new HashMap<>();
        private final Map<String, AtomicLong> taskCounts = new HashMap<>();

        public MonitoringMetrics(MeterRegistry registry) {
            this.registry = registry;
            registerStableGauges();
        }

        public synchronized void recordSessionSnapshot(
                String sessionId, EngineKind engine, EngineSnapshot snapshot) {
            sessions.put(sessionId, new SessionMetric(engine, snapshot.state().name(),
                    snapshot.downloadRateBytesPerSec(),
                    snapshot.uploadRateBytesPerSec(),
                    snapshot.peerCount()));
            refreshSessionGauges();
        }

        public synchronized void clearSessionSnapshots() {
            sessions.clear();
            refreshSessionGauges();
        }

        public synchronized void refreshTaskCounts(List<TaskStatusEngineCount> counts) {
            taskCounts.values().forEach(value -> value.set(0L));
            counts.forEach(count ->
                    taskCount(count.status(), count.engine()).set(count.count()));
        }

        public void recordOperation(String operation, String result, String errorType) {
            Counter.builder("magnet_task_operations_total")
                    .tag("operation", operation)
                    .tag("result", result)
                    .tag("error_type", errorType)
                    .register(registry)
                    .increment();
        }
    }

Register gauges once with AtomicLong backing values. Replace a session snapshot by internal session key so a shared resource is never counted twice. Do not use user, task, resource, path, URL, or exception text as a tag.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Maven command. Expected: MonitoringMetricsTest passes with zero failures.

- [ ] **Step 5: Commit the isolated metric component**

    git add server/download-application/pom.xml server/download-application/src/main/java/com/magnetplatform/application/MonitoringMetrics.java server/download-application/src/main/java/com/magnetplatform/application/TaskStatusEngineCount.java server/download-application/src/test/java/com/magnetplatform/application/MonitoringMetricsTest.java
    git commit -m "feat(monitoring): add low-cardinality metric recorder"

### Task 2: Refresh task/session gauges and instrument scheduler/domain paths

**Files:**
- Modify: server/download-application/src/main/java/com/magnetplatform/application/port/DownloadTaskRepository.java
- Modify: server/download-infrastructure/src/main/java/com/magnetplatform/infrastructure/mapper/DownloadTaskMapper.java
- Modify: server/download-infrastructure/src/main/resources/mappers/DownloadTaskMapper.xml
- Modify: server/download-infrastructure/src/main/java/com/magnetplatform/infrastructure/MyBatisDownloadTaskRepository.java
- Modify: server/download-application/src/main/java/com/magnetplatform/application/TaskService.java
- Modify: server/download-application/src/main/java/com/magnetplatform/application/ParseService.java
- Modify: server/download-application/src/main/java/com/magnetplatform/application/ExportService.java
- Modify: server/download-application/src/main/java/com/magnetplatform/application/ReferenceGcService.java
- Modify: server/download-scheduler/src/main/java/com/magnetplatform/scheduler/TaskDispatcher.java
- Modify: server/download-application/pom.xml
- Modify: server/download-scheduler/pom.xml
- Test: server/download-application/src/test/java/com/magnetplatform/application/TaskServiceTest.java
- Test: server/download-application/src/test/java/com/magnetplatform/application/ParseServiceTest.java
- Test: server/download-application/src/test/java/com/magnetplatform/application/ExportServiceTest.java
- Test: server/download-application/src/test/java/com/magnetplatform/application/ReferenceGcServiceTest.java

- [ ] **Step 1: Write the failing repository/TaskService regression test**

Add a TaskServiceTest assertion around two tenant tasks sharing one resource. After dispatchTick(3), the MonitoringMetrics registry must contain one LIBTORRENT session rate even though two tasks use that session. Assert the aggregate metric, not merely an engine method invocation.

Add the failing repository contract:

    List<TaskStatusEngineCount> countByStatusAndEngine();

Update the three existing in-memory DownloadTaskRepository fixtures in TaskServiceTest, ExportServiceTest, and ReferenceGcServiceTest with a deterministic empty/count implementation so the focused tests continue compiling while the production mapper is added.

- [ ] **Step 2: Run focused tests and verify the missing projection/metric failure**

    mvn -pl server/download-application "-Dtest=TaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Expected: compilation failure until the repository method, production constructor wiring, and metric calls exist.

- [ ] **Step 3: Implement the exact MyBatis aggregation and TaskService wiring**

Add this result map and query to DownloadTaskMapper.xml:

    <resultMap id="taskStatusEngineCount" type="com.magnetplatform.application.TaskStatusEngineCount">
        <constructor>
            <arg column="status" javaType="com.magnetplatform.domain.task.TaskStatus"/>
            <arg column="engine_type" javaType="com.magnetplatform.spi.EngineKind"/>
            <arg column="task_count" javaType="long"/>
        </constructor>
    </resultMap>

    <select id="countByStatusAndEngine" resultMap="taskStatusEngineCount">
        SELECT t.status,
               CASE WHEN r.version_type = 'HTTP' THEN 'ARIA2'
                    ELSE 'LIBTORRENT' END AS engine_type,
               COUNT(*) AS task_count
        FROM download_task t
        JOIN torrent_resource r ON r.id = t.resource_id
        WHERE t.deleted_at IS NULL
        GROUP BY t.status, engine_type
    </select>

Inject MonitoringMetrics into the Spring constructor of TaskService. Keep a test-only overload delegating to a disabled/simple registry so existing unit fixtures remain behaviorally unchanged. In dispatchTick, call:

    metrics.refreshTaskCounts(tasks.countByStatusAndEngine());

In the owned-session path, call recordSessionSnapshot once per resource session; clear the recorder at recovery start and clear a session when it becomes idle/stopped. Record lease failures and engine errors with bounded error_type values only. Do not alter lease, demand-union, file-progress, completion, or resume semantics.

- [ ] **Step 4: Add timing/counter annotations without changing business behavior**

Add micrometer-core to download-scheduler. Annotate existing public boundaries with fixed names:

    @Timed(value = "magnet_scheduler_tick_duration_seconds", histogram = true)
    @Counted(value = "magnet_scheduler_ticks_total")
    public void tick() {
        try {
            taskService.dispatchTick(maxActivePerUser);
        } catch (RuntimeException e) {
            log.warn("任务调度 tick 异常: {}", e.getMessage());
        }
    }

Use @Timed on ParseService submit/retry/sweep methods, ExportService.materializeCompleted, and ReferenceGcService.markCandidates/sweep. Register one TimedAspect and one CountedAspect bean in the API module. Existing exception handling and state transitions must remain unchanged.

- [ ] **Step 5: Add SSE connection gauges and engine-rate assertions**

Inject MonitoringMetrics into TaskController. Increment the SSE connection gauge after creating the emitter; decrement exactly once through an AtomicBoolean-guarded close callback used by completion, timeout, error, and worker exit. Keep the existing localized-disconnect behavior.

- [ ] **Step 6: Run application/scheduler tests and verify the red-green result**

    mvn -pl server/download-application,server/download-scheduler -am "-Dtest=MonitoringMetricsTest,TaskServiceTest,ParseServiceTest,ExportServiceTest,ReferenceGcServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Expected: all selected tests pass and no existing task lifecycle assertion changes.

- [ ] **Step 7: Commit the lifecycle instrumentation**

    git add server/download-application server/download-infrastructure/src/main/java/com/magnetplatform/infrastructure/mapper/DownloadTaskMapper.java server/download-infrastructure/src/main/resources/mappers/DownloadTaskMapper.xml server/download-scheduler
    git commit -m "feat(monitoring): instrument task and engine lifecycle"

### Task 3: Expose and protect the Prometheus endpoint

**Files:**
- Modify: server/download-api/pom.xml
- Modify: server/download-api/src/main/resources/application.yml
- Modify: server/download-api/src/main/java/com/magnetplatform/api/security/SecurityConfig.java
- Create: server/download-api/src/main/java/com/magnetplatform/api/security/MetricsSecurityProperties.java
- Create: server/download-api/src/main/java/com/magnetplatform/api/security/MetricsUserDetailsService.java
- Create: server/download-api/src/test/java/com/magnetplatform/api/MetricsEndpointIntegrationTest.java

- [ ] **Step 1: Write the failing endpoint-authentication integration test**

Use a Spring Boot random-port test with a temporary password file. Assert no-auth and Bearer requests return 401, while Basic Auth with user prometheus and the test password returns 200 and a body containing magnet_ metrics.

- [ ] **Step 2: Run the test and verify the missing endpoint/security failure**

    mvn -pl server/download-api "-Dtest=MetricsEndpointIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Expected: failure because Actuator/Prometheus dependencies and the dedicated Basic Auth chain are absent.

- [ ] **Step 3: Add Actuator, Prometheus registry, configuration, and ordered security**

Add spring-boot-starter-actuator and micrometer-registry-prometheus to download-api/pom.xml. Configure management exposure to health,prometheus and hide health details. Add magnet.metrics.scrape-user with default prometheus and magnet.metrics.scrape-password-file with default ./infra/.secrets/prometheus-scrape-password.

Read the password file once at startup and fail closed if it is missing or blank. Add an @Order(1) SecurityFilterChain matching only /actuator/prometheus with HTTP Basic. Keep the JWT chain at @Order(2) for business APIs; do not permit the metrics endpoint in the JWT chain.

- [ ] **Step 4: Run the endpoint test and verify all auth cases**

Run the same focused Maven command. Expected: unauthorized no-auth/Bearer requests and a successful Prometheus response with Basic Auth.

- [ ] **Step 5: Commit endpoint exposure and security**

    git add server/download-api/pom.xml server/download-api/src/main/resources/application.yml server/download-api/src/main/java/com/magnetplatform/api/security server/download-api/src/test/java/com/magnetplatform/api/MetricsEndpointIntegrationTest.java
    git commit -m "feat(monitoring): expose protected prometheus metrics"

### Task 4: Add the monitoring Compose profile and Grafana provisioning

**Files:**
- Modify: .gitignore
- Modify: .env.example
- Modify: infra/docker-compose.yml
- Create: infra/prometheus/prometheus.yml
- Create: infra/grafana/provisioning/datasources/prometheus.yml
- Create: infra/grafana/provisioning/dashboards/dashboards.yml
- Create: infra/grafana/dashboards/magnet-platform-overview.json
- Modify: README.md

- [ ] **Step 1: Establish the red configuration check**

Run:

    docker compose --profile monitoring config

Record that the pre-change configuration does not list prometheus and grafana.

- [ ] **Step 2: Add the ignored secret directory and profile-scoped services**

Add infra/.secrets/ to .gitignore. Add the password-file path to .env.example without a password value. Add profile-scoped Prometheus and Grafana services with ports bound to 127.0.0.1:9090 and 127.0.0.1:3000. Mount the password file read-only into Prometheus and mount Grafana provisioning/dashboards read-only. Keep MySQL, Redis, Worker, and aria2 unchanged; add only a grafana-data volume.

- [ ] **Step 3: Add Prometheus and Grafana provisioning**

Prometheus must target host.docker.internal:8080, use metrics_path /actuator/prometheus, username prometheus, and the mounted password_file. Set scrape_interval to 5s. Provision Grafana's default datasource to http://prometheus:9090 and a dashboard provider pointing at /var/lib/grafana/dashboards.

The Magnet Platform Overview JSON must implement the approved health-first layout: health, task counts, sessions, recent errors, queue, engine rates, scheduler latency, lease state, and parse/export/GC/SSE timings. Queries must use only the metric names and low-cardinality labels from the design.

- [ ] **Step 4: Validate the Compose configuration**

    docker compose --profile monitoring config

Expected: exit code 0, services prometheus/grafana present under the profile, and no secret value in output.

- [ ] **Step 5: Update README with safe local startup**

Document creation of infra/.secrets/prometheus-scrape-password, starting the profile with docker compose --profile monitoring up -d prometheus grafana, opening Grafana at http://localhost:3000, and keeping the secret untracked. Keep the existing default docker compose up -d flow unchanged.

- [ ] **Step 6: Commit the monitoring stack**

    git add .gitignore .env.example README.md infra/docker-compose.yml infra/prometheus infra/grafana
    git commit -m "feat(monitoring): provision prometheus and grafana stack"

### Task 5: Full verification and repository handoff

**Files:**
- Modify: HANDOFF.md
- Modify: ISSUE.md

- [ ] **Step 1: Run targeted backend verification**

    mvn -pl server/download-api -am "-Dtest=MetricsEndpointIntegrationTest,MonitoringMetricsTest,TaskServiceTest,ParseServiceTest,ExportServiceTest,ReferenceGcServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Expected: zero failures and zero errors. If Docker is unavailable and Testcontainers skips cases, record the exact skipped count and do not call it a full pass.

- [ ] **Step 2: Start only the monitoring profile and verify live wiring**

After creating the local ignored password file, run:

    docker compose --profile monitoring up -d prometheus grafana
    Invoke-WebRequest -UseBasicParsing http://localhost:9090/-/ready
    Invoke-WebRequest -UseBasicParsing http://localhost:3000/api/health

Confirm the provisioned datasource and Magnet Platform Overview load. Do not restart existing user services outside the monitoring profile.

- [ ] **Step 3: Run repository baseline verification**

    mvn test
    cd web; npm run build; cd ..

Expected: Maven BUILD SUCCESS and frontend production build success. Record integration-test skips if Docker is unavailable.

- [ ] **Step 4: Update handoff and issue records with evidence**

Mark P7.1 monitoring delivered in HANDOFF.md, record the exact tests/commands, and document the Basic Auth/password-file decision. Update ISSUE.md with any fixed monitoring issue and commit hash; add unresolved issues only when a real defect remains.

- [ ] **Step 5: Review the final diff and commit documentation**

    git status --short
    git diff --check
    git diff --stat HEAD~5..HEAD
    git add HANDOFF.md ISSUE.md
    git commit -m "docs: record P7 monitoring delivery"

Before reporting completion, confirm no .env, target/, node_modules/, local secret file, or unrelated pre-existing change was staged.
