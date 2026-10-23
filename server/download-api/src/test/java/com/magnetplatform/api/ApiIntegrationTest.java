package com.magnetplatform.api;

import com.magnetplatform.application.ExportService;
import com.magnetplatform.application.HttpProbeService;
import com.magnetplatform.engine.aria2.MockAria2Engine;
import com.magnetplatform.application.TaskService;
import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.RateLimiter;
import com.magnetplatform.domain.export.ExportState;
import com.magnetplatform.domain.export.FileExport;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试（Testcontainers MySQL 8，RateLimiter 打桩）：
 * 注册/登录、JWT 鉴权 401、Magnet 提交 RESOLVING、租户隔离 404、
 * .torrent 上传 READY + 文件树 200、坏输入 422、
 * 任务创建/跨租户隔离 404、导出列表与流式读取、removeOwnExport、非法 targetSubdirectory 422。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("magnet")
            .withUsername("magnet")
            .withPassword("magnet");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        String storageRoot = Files.createTempDirectory("magnet-it-").toAbsolutePath().toString();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("magnet.storage.root", () -> storageRoot);
        registry.add("magnet.engine.libtorrent.grpc.enabled", () -> "false");
        registry.add("magnet.engine.aria2.mock.enabled", () -> "true");
        registry.add("magnet.parse.sweep-delay-ms", () -> "600000");
        registry.add("magnet.task.dispatch-delay-ms", () -> "600000");
        registry.add("magnet.gc.sweep-delay-ms", () -> "600000");
    }

    @MockBean
    RateLimiter rateLimiter;

    @SpyBean(name = "mockLibtorrentEngine")
    DownloadEngine downloadEngine;

    @Autowired
    MockAria2Engine mockAria2Engine;

    @SpyBean
    HttpProbeService httpProbeService;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DownloadTaskRepository taskRepository;

    @Autowired
    EngineSessionRepository sessionRepository;

    @Autowired
    FileExportRepository exportRepository;

    @Autowired
    DownloadStorage downloadStorage;

    @Autowired
    ExportService exportService;

    @Autowired
    TaskService taskService;

    @BeforeEach
    void stubQuota() {
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
        Mockito.when(rateLimiter.hitParseToday(Mockito.any(UUID.class))).thenReturn(1L);
    }

    @Test
    void fullParseFlowWithTenantIsolation() throws IOException {
        // 注册两个用户（首个为 ADMIN）
        ResponseEntity<Map> regA = post("/api/v1/auth/register",
                Map.of("username", "alice", "password", "password123"), null);
        assertThat(regA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenA = (String) regA.getBody().get("token");
        assertThat(tokenA).isNotBlank();

        ResponseEntity<Map> regB = post("/api/v1/auth/register",
                Map.of("username", "bob", "password", "password123"), null);
        assertThat(regB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenB = (String) regB.getBody().get("token");

        // 错误密码登录 → 401
        ResponseEntity<Map> badLogin = post("/api/v1/auth/login",
                Map.of("username", "alice", "password", "wrong-password"), null);
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 未携带令牌访问 → 401
        ResponseEntity<Map> unauthenticated = rest.getForEntity("/api/v1/parses", Map.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A 提交合法 Magnet → RESOLVING
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=hello";
        ResponseEntity<Map> parseA = post("/api/v1/parses", Map.of("uri", magnet), tokenA);
        assertThat(parseA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parseA.getBody().get("status")).isEqualTo("RESOLVING");
        String parseAId = (String) parseA.getBody().get("id");

        // B 读 A 的解析任务 → 404（不泄露存在性）
        ResponseEntity<Map> foreign = get("/api/v1/parses/" + parseAId, tokenB);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A 自己的列表能看到 1 条
        ResponseEntity<Map> listA = get("/api/v1/parses?page=1&size=30", tokenA);
        assertThat(listA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) listA.getBody().get("items"))).hasSize(1);

        // A 提交坏 Magnet → 422 INVALID_MAGNET
        ResponseEntity<Map> badMagnet = post("/api/v1/parses",
                Map.of("uri", "magnet:?xt=urn:btih:xyz"), tokenA);
        assertThat(badMagnet.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badMagnet.getBody().get("code")).isEqualTo("INVALID_MAGNET");

        // A 上传最小 V1 .torrent → READY
        ResponseEntity<Map> upload = uploadTorrent(tokenA, minimalV1Torrent());
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upload.getBody().get("status")).isEqualTo("READY");
        String resourceId = (String) upload.getBody().get("resourceId");

        // A 读文件树 → 200，单文件 test 5 字节
        ResponseEntity<Map> files = get("/api/v1/resources/" + resourceId + "/files", tokenA);
        assertThat(files.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> resource = (Map<?, ?>) files.getBody().get("resource");
        assertThat(resource.get("versionType")).isEqualTo("V1");
        assertThat((String) resource.get("btih")).hasSize(40);
        List<?> fileItems = (List<?>) files.getBody().get("files");
        assertThat(fileItems).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) fileItems.get(0);
        assertThat(first.get("relativePath")).isEqualTo("test");
        assertThat(((Number) first.get("sizeBytes")).longValue()).isEqualTo(5L);

        // B 读该资源文件树 → 404
        ResponseEntity<Map> foreignFiles = get("/api/v1/resources/" + resourceId + "/files", tokenB);
        assertThat(foreignFiles.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A 上传垃圾字节 → 422 INVALID_TORRENT
        ResponseEntity<Map> badTorrent = uploadTorrent(tokenA, "not-a-torrent".getBytes(StandardCharsets.UTF_8));
        assertThat(badTorrent.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badTorrent.getBody().get("code")).isEqualTo("INVALID_TORRENT");
    }

    @Test
    void taskAndExportLifecycleWithCrossUserIsolation() throws IOException {
        // 确保首个注册用户（ADMIN）存在后，再注册两个普通租户 userA 与 userB
        post("/api/v1/auth/register", Map.of("username", "rootadmin", "password", "password123"), null);

        ResponseEntity<Map> regUserA = post("/api/v1/auth/register",
                Map.of("username", "user_a", "password", "password123"), null);
        assertThat(regUserA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenA = (String) regUserA.getBody().get("token");
        UUID userAId = UUID.fromString((String) ((Map<?, ?>) regUserA.getBody().get("user")).get("id"));

        ResponseEntity<Map> regUserB = post("/api/v1/auth/register",
                Map.of("username", "user_b", "password", "password123"), null);
        assertThat(regUserB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenB = (String) regUserB.getBody().get("token");

        // 1. A 上传 READY .torrent 并创建任务；B 访问该任务及其文件返回 404
        ResponseEntity<Map> uploadA = uploadTorrent(tokenA, minimalV1Torrent());
        assertThat(uploadA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String parseAId = (String) uploadA.getBody().get("id");
        UUID resourceId = UUID.fromString((String) uploadA.getBody().get("resourceId"));

        // 5. 非法 targetSubdirectory 返回 422 INVALID_PATH
        ResponseEntity<Map> badSubdir = post("/api/v1/tasks",
                Map.of("parseId", parseAId, "fileIndices", List.of(0), "targetSubdirectory", "../escape"),
                tokenA);
        assertThat(badSubdir.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badSubdir.getBody().get("code")).isEqualTo("INVALID_PATH");

        ResponseEntity<Map> createTaskA = post("/api/v1/tasks",
                Map.of("parseId", parseAId, "fileIndices", List.of(0), "targetSubdirectory", "my-downloads"),
                tokenA);
        assertThat(createTaskA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskAIdStr = (String) createTaskA.getBody().get("id");
        UUID taskAId = UUID.fromString(taskAIdStr);

        assertThat(get("/api/v1/tasks/" + taskAIdStr, tokenB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/tasks/" + taskAIdStr + "/files", tokenB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 2. B 提交同一资源；A 与 B 各自能看到自己的解析历史与任务，但互不可见对方的任务
        ResponseEntity<Map> uploadB = uploadTorrent(tokenB, minimalV1Torrent());
        assertThat(uploadB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String parseBId = (String) uploadB.getBody().get("id");
        assertThat(uploadB.getBody().get("resourceId")).isEqualTo(resourceId.toString());

        ResponseEntity<Map> createTaskB = post("/api/v1/tasks",
                Map.of("parseId", parseBId, "fileIndices", List.of(0)),
                tokenB);
        assertThat(createTaskB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskBIdStr = (String) createTaskB.getBody().get("id");
        UUID taskBId = UUID.fromString(taskBIdStr);

        ResponseEntity<Map> parsesA = get("/api/v1/parses?page=1&size=30", tokenA);
        ResponseEntity<Map> parsesB = get("/api/v1/parses?page=1&size=30", tokenB);
        List<String> idsA = ((List<?>) parsesA.getBody().get("items")).stream()
                .map(o -> (String) ((Map<?, ?>) o).get("id")).toList();
        List<String> idsB = ((List<?>) parsesB.getBody().get("items")).stream()
                .map(o -> (String) ((Map<?, ?>) o).get("id")).toList();
        assertThat(idsA).contains(parseAId).doesNotContain(parseBId);
        assertThat(idsB).contains(parseBId).doesNotContain(parseAId);

        assertThat(get("/api/v1/tasks/" + taskBIdStr, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/tasks/" + taskAIdStr, tokenB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 准备资源受控目录下的已验证源文件（5 字节 "hello"），并将任务推进到 COMPLETED + 物化导出
        Path resourceFile = downloadStorage.resolveResourceFile(resourceId, "test");
        Files.createDirectories(resourceFile.getParent());
        Files.writeString(resourceFile, "hello", StandardCharsets.UTF_8);

        completeAndMaterialize(taskAId);
        completeAndMaterialize(taskBId);

        // 3. A 列出并读取自己的导出；B 对同一导出列表与内容均得 404
        ResponseEntity<List> exportsAResp = rest.exchange("/api/v1/tasks/" + taskAIdStr + "/exports",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tokenA)), List.class);
        assertThat(exportsAResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exportsAResp.getBody()).hasSize(1);
        Map<?, ?> exportA = (Map<?, ?>) exportsAResp.getBody().get(0);
        String exportAId = (String) exportA.get("id");
        assertThat(exportA.get("state")).isEqualTo("READY");
        assertThat(((Number) exportA.get("bytes")).longValue()).isEqualTo(5L);

        ResponseEntity<String> contentAResp = rest.exchange(
                "/api/v1/tasks/" + taskAIdStr + "/exports/" + exportAId + "/content",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tokenA)), String.class);
        assertThat(contentAResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentAResp.getBody()).isEqualTo("hello");

        assertThat(get("/api/v1/tasks/" + taskAIdStr + "/exports", tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/v1/tasks/" + taskAIdStr + "/exports/" + exportAId + "/content",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tokenB)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 4a. removeOwnExport=false 保留 A 的导出文件与记录
        Path exportAPath = downloadStorage.exportRoot(userAId, taskAId).resolve("my-downloads/test");
        assertThat(Files.isRegularFile(exportAPath)).isTrue();
        ResponseEntity<Void> delKeepExport = delete("/api/v1/tasks/" + taskAIdStr + "?removeOwnExport=false", tokenA);
        assertThat(delKeepExport.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.isRegularFile(exportAPath)).isTrue();
        assertThat(exportRepository.findByTask(taskAId))
                .extracting(FileExport::getState)
                .containsExactly(ExportState.READY);

        // 4b. removeOwnExport=true 仅移除 A 自己的导出，不影响 B 的导出
        ResponseEntity<Map> createTaskA2 = post("/api/v1/tasks",
                Map.of("parseId", parseAId, "fileIndices", List.of(0)),
                tokenA);
        UUID taskA2Id = UUID.fromString((String) createTaskA2.getBody().get("id"));
        completeAndMaterialize(taskA2Id);
        Path exportA2Path = downloadStorage.exportRoot(userAId, taskA2Id).resolve("test");
        assertThat(Files.isRegularFile(exportA2Path)).isTrue();

        ResponseEntity<Void> delRemoveExport = delete("/api/v1/tasks/" + taskA2Id + "?removeOwnExport=true", tokenA);
        assertThat(delRemoveExport.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(exportA2Path)).isFalse();
        assertThat(exportRepository.findByTask(taskA2Id))
                .filteredOn(e -> e.getState() != ExportState.DELETED)
                .isEmpty();

        // B 的导出依然 READY 且可正常下载
        ResponseEntity<List> exportsBResp = rest.exchange("/api/v1/tasks/" + taskBIdStr + "/exports",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tokenB)), List.class);
        assertThat(exportsBResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String exportBId = (String) ((Map<?, ?>) exportsBResp.getBody().get(0)).get("id");
        ResponseEntity<String> contentBResp = rest.exchange(
                "/api/v1/tasks/" + taskBIdStr + "/exports/" + exportBId + "/content",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tokenB)), String.class);
        assertThat(contentBResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentBResp.getBody()).isEqualTo("hello");
    }

    @Test
    void expiredLeaseCannotRenewAndTakeoverRejectsOldOwner() throws IOException {
        String token = (String) post("/api/v1/auth/register",
                Map.of("username", "lease_user", "password", "password123"), null).getBody().get("token");
        byte[] torrent = minimalTwoFileV1Torrent();
        torrent[torrent.length - 3] = 42; // 与其他测试资源隔离的合法 piece hash
        UUID resourceId = UUID.fromString((String) uploadTorrent(token, torrent)
                .getBody().get("resourceId"));
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        sessionRepository.insertIfAbsent(new EngineSessionRecord(resourceId, resourceId, "expired",
                "LIBTORRENT", "ACTIVE", 3L, now.minusMinutes(1), 0L, now, now));
        assertThat(sessionRepository.renewLease(resourceId, "LIBTORRENT", "expired", 3L, 30)).isFalse();
        EngineSessionRecord acquired = sessionRepository.acquireLease(resourceId, "LIBTORRENT", "replacement", 30)
                .orElseThrow();
        assertThat(acquired.getOwnerEpoch()).isEqualTo(4L);
        assertThat(sessionRepository.updateState(resourceId, "STOPPED", now, "expired", 3L)).isFalse();
        assertThat(sessionRepository.updateResume(resourceId, 999L, "stale", now, "expired", 3L)).isFalse();
        assertThat(sessionRepository.acquireLease(resourceId, "LIBTORRENT", "competitor", 30)).isEmpty();
        assertThat(sessionRepository.renewLease(resourceId, "LIBTORRENT", "expired", 3L, 30)).isFalse();
        assertThat(sessionRepository.updateState(resourceId, "STOPPED", now, "replacement", 4L)).isTrue();
        assertThat(sessionRepository.renewLease(resourceId, "LIBTORRENT", "replacement", 4L, 30)).isFalse();
        EngineSessionRecord next = sessionRepository.acquireLease(resourceId, "LIBTORRENT", "replacement", 30)
                .orElseThrow();
        assertThat(next.getOwnerEpoch()).isEqualTo(5L);
        java.util.concurrent.atomic.AtomicBoolean wrote = new java.util.concurrent.atomic.AtomicBoolean();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> sessionRepository.withLease(next, () -> wrote.set(true)));
        assertThat(wrote.get()).isFalse(); // STOPPED 代次不能继续写入。
        assertThat(sessionRepository.updateState(resourceId, "DISCOVERING", now, "replacement", 5L)).isTrue();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> sessionRepository.withLease(next, () -> {
                    sessionRepository.updateResume(resourceId, 100L, "rolled-back", now, "replacement", 5L);
                    throw new IllegalStateException("simulate failed resource write");
                }));
        assertThat(sessionRepository.findByResource(resourceId, "LIBTORRENT").orElseThrow().getResumeVersion())
                .isZero();
    }

    @Test
    void sharedSessionAcrossTwoUsersAndDemandUnion() throws IOException {
        post("/api/v1/auth/register", Map.of("username", "shared_admin", "password", "password123"), null);
        String tokenA = (String) post("/api/v1/auth/register",
                Map.of("username", "shared_a", "password", "password123"), null).getBody().get("token");
        String tokenB = (String) post("/api/v1/auth/register",
                Map.of("username", "shared_b", "password", "password123"), null).getBody().get("token");

        byte[] twoFileTorrent = minimalTwoFileV1Torrent();
        ResponseEntity<Map> uploadA = uploadTorrent(tokenA, twoFileTorrent);
        ResponseEntity<Map> uploadB = uploadTorrent(tokenB, twoFileTorrent);
        String parseAId = (String) uploadA.getBody().get("id");
        String parseBId = (String) uploadB.getBody().get("id");
        UUID resourceId = UUID.fromString((String) uploadA.getBody().get("resourceId"));
        assertThat(uploadB.getBody().get("resourceId")).isEqualTo(resourceId.toString());

        // A 选文件 0，B 选文件 1
        String taskAId = (String) post("/api/v1/tasks",
                Map.of("parseId", parseAId, "fileIndices", List.of(0)), tokenA).getBody().get("id");
        String taskBId = (String) post("/api/v1/tasks",
                Map.of("parseId", parseBId, "fileIndices", List.of(1)), tokenB).getBody().get("id");

        taskService.dispatchTick(3);

        // 仅存在一个 engine_session 记录，需求并集包含 {0, 1}
        List<EngineSessionRecord> sessionsForResource = sessionRepository.findRecoverable().stream()
                .filter(s -> s.getResourceId().equals(resourceId))
                .toList();
        assertThat(sessionsForResource).hasSize(1);
        assertThat(sessionRepository.findByResource(resourceId, EngineKind.LIBTORRENT.name())).isPresent();
        Mockito.verify(downloadEngine, Mockito.atLeastOnce())
                .setDemand(resourceId.toString(), java.util.Set.of(0, 1));

        // 删除 A 的任务后，B 仍保持 DOWNLOADING，需求变为 {1}
        assertThat(delete("/api/v1/tasks/" + taskAId, tokenA).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        taskService.dispatchTick(3);

        ResponseEntity<Map> taskBView = get("/api/v1/tasks/" + taskBId, tokenB);
        assertThat(taskBView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskBView.getBody().get("status")).isEqualTo("DOWNLOADING");
        Mockito.verify(downloadEngine, Mockito.atLeastOnce())
                .setDemand(resourceId.toString(), java.util.Set.of(1));
    }

    @Test
    void httpUrlParseAndDownloadLifecycleWithAria2Engine() {
        Mockito.doReturn(new HttpProbeService.ProbeResult("archive.iso", 1048576L))
                .when(httpProbeService).probe(Mockito.anyString());

        String token = (String) post("/api/v1/auth/register",
                Map.of("username", "httpuser", "password", "password123"), null).getBody().get("token");

        // 1. 提交 HTTP 直链解析
        ResponseEntity<Map> parseResp = post("/api/v1/parses",
                Map.of("uri", "https://example.com/archive.iso"), token);
        assertThat(parseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parseResp.getBody().get("status")).isEqualTo("READY");
        assertThat(parseResp.getBody().get("inputType")).isEqualTo("HTTP");
        String parseId = (String) parseResp.getBody().get("id");
        String resourceId = (String) parseResp.getBody().get("resourceId");

        // 2. 创建下载任务
        ResponseEntity<Map> taskResp = post("/api/v1/tasks",
                Map.of("parseId", parseId, "fileIndices", List.of(0)), token);
        assertThat(taskResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskId = (String) taskResp.getBody().get("id");

        // 3. 调度启动
        taskService.dispatchTick(3);

        // 验证引擎会话为 ARIA2
        assertThat(sessionRepository.findByResource(UUID.fromString(resourceId), EngineKind.ARIA2.name())).isPresent();

        ResponseEntity<Map> taskView = get("/api/v1/tasks/" + taskId, token);
        assertThat(taskView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taskView.getBody().get("status")).isEqualTo("DOWNLOADING");

        // 4. Mock 完成下载并推进两轮对账
        mockAria2Engine.setSessionProgress(resourceId, 1048576L, 1048576L);
        taskService.dispatchTick(3); // Tick 1: DOWNLOADING -> VERIFYING
        taskService.dispatchTick(3); // Tick 2: VERIFYING -> COMPLETED

        ResponseEntity<Map> completedView = get("/api/v1/tasks/" + taskId, token);
        assertThat(completedView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completedView.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) completedView.getBody().get("verifiedBytes")).longValue()).isEqualTo(1048576L);
    }

    private void completeAndMaterialize(UUID taskId) {
        LocalDateTime now = LocalDateTime.now();
        taskRepository.updateFileProgress(taskId, 0, 5L);
        DownloadTask current = taskRepository.findById(taskId).orElseThrow();
        DownloadTask completed = current.withProgress(5L, now)
                .withStatus(TaskStatus.COMPLETED, now)
                .withCompletedAt(now, now);
        // updateCas expects version == oldVersion + 1
        DownloadTask forCas = new DownloadTask(current.getId(), current.getUserId(), current.getResourceId(),
                TaskStatus.COMPLETED, current.getSelectedBytes(), 5L, current.getPriority(),
                current.getSpeedLimit(), current.getTargetSubdirectory(), null, null,
                current.getVersion() + 1, current.getCreatedAt(), now, now, now, null);
        assertThat(taskRepository.updateCas(forCas)).isTrue();
        exportService.materializeCompleted(forCas);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> post(String path, Object body, String token) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
    }

    private ResponseEntity<Void> delete(String path, String token) {
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), Void.class);
    }

    private ResponseEntity<Map> uploadTorrent(String token, byte[] bytes) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "test.torrent";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        return rest.exchange("/api/v1/parses/upload", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** 最小合法 V1：info{length=5, name=test, piece length=16384, pieces=1×20字节} */
    static byte[] minimalV1Torrent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("d4:infod6:lengthi5e4:name4:test12:piece lengthi16384e6:pieces20:"
                .getBytes(StandardCharsets.UTF_8));
        out.write(new byte[20]);
        out.write("ee".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** 最小合法多文件 V1：info{files=[{length=5,path=[a.txt]},{length=6,path=[b.txt]}], name=multi, piece length=16384, pieces=1×20字节} */
    static byte[] minimalTwoFileV1Torrent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("d4:infod5:filesld6:lengthi5e4:pathl5:a.txteed6:lengthi6e4:pathl5:b.txteee"
                + "4:name5:multi12:piece lengthi16384e6:pieces20:")
                .getBytes(StandardCharsets.UTF_8));
        out.write(new byte[20]);
        out.write("ee".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
