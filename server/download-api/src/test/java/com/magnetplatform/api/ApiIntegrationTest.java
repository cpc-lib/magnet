package com.magnetplatform.api;

import com.magnetplatform.application.port.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试（Testcontainers MySQL 8，RateLimiter 打桩）：
 * 注册/登录、JWT 鉴权 401、Magnet 提交 RESOLVING、租户隔离 404、
 * .torrent 上传 READY + 文件树 200、坏输入 422。
 */
@Testcontainers(disabledWithoutDocker = true) // 本机 Docker Desktop 29.x npipe 代理与 docker-java 不兼容时跳过而非失败；CI/正常 Docker 环境仍执行
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
        registry.add("magnet.parse.sweep-delay-ms", () -> "600000");
    }

    @MockBean
    RateLimiter rateLimiter;

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void stubQuota() {
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

    // ---------- helpers ----------

    private ResponseEntity<Map> post(String path, Object body, String token) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
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
}
