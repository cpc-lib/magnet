package com.magnetplatform.api;

import com.magnetplatform.application.TaskService;
import com.magnetplatform.application.HttpProbeService;
import com.magnetplatform.application.port.FileExportRepository;
import com.magnetplatform.application.port.RateLimiter;
import com.magnetplatform.engine.aria2.client.Aria2Client;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Aria2DownloadIntegrationTest {
    static final Path STORAGE = temporaryStorage();
    static final String SOURCE_HOST = sourceHost();
    static final byte[] PAYLOAD = payload();
    static final AtomicInteger RANGES = new AtomicInteger();
    static final AtomicInteger NONZERO_RANGES = new AtomicInteger();
    static final AtomicInteger GET_REQUESTS = new AtomicInteger();
    static final java.util.concurrent.atomic.AtomicBoolean FAIL_DOWNLOAD = new java.util.concurrent.atomic.AtomicBoolean(true);
    static final javax.net.ssl.SSLContext TLS = tls();
    static final HttpServer SOURCE = source();
    static final HttpServer SECURE_SOURCE = secureSource();
    static final int RPC_PORT = freePort();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("magnet").withUsername("magnet").withPassword("magnet");

    @Container
    static final GenericContainer<?> ARIA2 = new GenericContainer<>(
            new ImageFromDockerfile("magnet-aria2:acceptance", false)
                    .withFileFromPath("Dockerfile", Path.of("../../infra/aria2/Dockerfile")))
            .withExposedPorts(6800)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindPort(RPC_PORT),
                            new com.github.dockerjava.api.model.ExposedPort(6800))))
            .withFileSystemBind(STORAGE.toString(), "/data")
            .withCommand("--enable-rpc=true", "--rpc-listen-all=true", "--rpc-listen-port=6800",
                    "--dir=/data", "--continue=true", "--auto-save-interval=1", "--file-allocation=none",
                    "--enable-dht=false", "--enable-peer-exchange=false", "--console-log-level=warn",
                    "--ca-certificate=/data/fixture-ca.pem")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("magnet.storage.root", STORAGE::toString);
        registry.add("magnet.engine.libtorrent.grpc.enabled", () -> "false");
        registry.add("magnet.engine.aria2.rpc.enabled", () -> "true");
        registry.add("magnet.engine.aria2.rpc.endpoint", Aria2DownloadIntegrationTest::endpoint);
        registry.add("magnet.engine.aria2.rpc.storage-root", () -> "/data");
        registry.add("magnet.parse.sweep-delay-ms", () -> "600000");
        registry.add("magnet.task.dispatch-delay-ms", () -> "600000");
        registry.add("magnet.gc.sweep-delay-ms", () -> "600000");
    }

    @MockBean RateLimiter rateLimiter;
    @Autowired TestRestTemplate rest;
    @Autowired TaskService taskService;
    @Autowired FileExportRepository exports;

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void realDaemonDownloadsPausesRecoversAndExportsExactBytes(String scheme) throws Exception {
        Mockito.when(rateLimiter.hitParseToday(Mockito.any(UUID.class))).thenReturn(1L);
        String token = (String) post("/api/v1/auth/register",
                Map.of("username", "real_" + scheme, "password", "password123"), null).getBody().get("token");
        HttpServer source = scheme.equals("https") ? SECURE_SOURCE : SOURCE;
        int rangesBefore = RANGES.get();
        String uri = scheme + "://" + SOURCE_HOST
                + ":" + source.getAddress().getPort() + "/download";
        ResponseEntity<Map> parsed = post("/api/v1/parses", Map.of("uri", uri), token);
        assertThat(parsed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parsed.getBody().get("status")).isEqualTo("READY");
        UUID resourceId = UUID.fromString((String) parsed.getBody().get("resourceId"));
        ResponseEntity<Map> created = post("/api/v1/tasks",
                Map.of("parseId", parsed.getBody().get("id"), "fileIndices", List.of(0)), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID taskId = UUID.fromString((String) created.getBody().get("id"));
        String taskPath = "/api/v1/tasks/" + taskId;
        String gid = resourceId.toString().replace("-", "").substring(0, 16);
        Aria2Client rpc = new Aria2Client(endpoint(), "", Duration.ofSeconds(5));
        taskService.dispatchTick(3);
        Map started = get(taskPath, token).getBody();
        assertThat(started.get("status")).isEqualTo("DOWNLOADING");
        String startedAt = (String) started.get("startedAt");
        assertThat(startedAt).isNotBlank();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(completed(rpc, gid)).isBetween(1024L * 1024, (long) PAYLOAD.length - 1));

        assertThat(post(taskPath + "/pause", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        taskService.dispatchTick(3);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(rpc.tellStatus(gid).get("status")).isEqualTo("paused"));
        long pausedBytes = completed(rpc, gid);
        Thread.sleep(1200);
        assertThat(completed(rpc, gid)).isEqualTo(pausedBytes);
        assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("PAUSED");
        int getsWhilePaused = GET_REQUESTS.get();
        ARIA2.getDockerClient().restartContainerCmd(ARIA2.getContainerId()).withTimeout(1).exec();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> !rpc.getVersion().isEmpty());
        taskService.dispatchTick(3);
        assertThat(rpc.tellStatus(gid).get("status")).isEqualTo("paused");
        Thread.sleep(1200);
        assertThat(GET_REQUESTS.get()).isEqualTo(getsWhilePaused);

        assertThat(post(taskPath + "/resume", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        taskService.dispatchTick(3);
        assertThat(get(taskPath, token).getBody().get("startedAt")).isEqualTo(startedAt);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(completed(rpc, gid)).isGreaterThan(pausedBytes));

        int rangesBeforeRestart = NONZERO_RANGES.get();
        ARIA2.getDockerClient().restartContainerCmd(ARIA2.getContainerId()).withTimeout(1).exec();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> !rpc.getVersion().isEmpty());
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            taskService.dispatchTick(3);
            assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("VERIFYING");
        });
        taskService.dispatchTick(3);
        Map finished = get(taskPath, token).getBody();
        assertThat(finished.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) finished.get("verifiedBytes")).longValue()).isEqualTo(PAYLOAD.length);
        assertThat(finished.get("startedAt")).isEqualTo(startedAt);
        assertThat(RANGES.get() - rangesBefore).isGreaterThan(1);
        assertThat(NONZERO_RANGES.get()).isGreaterThan(rangesBeforeRestart);
        assertThat(Files.readAllBytes(STORAGE.resolve("resources").resolve(resourceId.toString())
                .resolve("accepted.bin"))).isEqualTo(PAYLOAD);
        assertThat(exports.findByTask(taskId)).isEmpty();
        ResponseEntity<List> listed = rest.exchange(taskPath + "/exports", HttpMethod.GET,
                new HttpEntity<>(headers(token)), List.class);
        assertThat(listed.getBody()).isEmpty();
        ResponseEntity<List> exported = rest.exchange(taskPath + "/exports", HttpMethod.POST,
                new HttpEntity<>(headers(token)), List.class);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exported.getBody()).hasSize(1);
        Map export = (Map) exported.getBody().get(0);
        assertThat(export.get("state")).isEqualTo("READY");
        ResponseEntity<byte[]> content = rest.exchange(taskPath + "/exports/" + export.get("id") + "/content",
                HttpMethod.GET, new HttpEntity<>(headers(token)), byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).isEqualTo(PAYLOAD);
    }

    @Test
    void realDaemonErrorFailsTaskAndExplicitRetryDownloadsAfterSourceRecovers() throws Exception {
        Mockito.when(rateLimiter.hitParseToday(Mockito.any(UUID.class))).thenReturn(1L);
        String token = (String) post("/api/v1/auth/register",
                Map.of("username", "real_retry", "password", "password123"), null).getBody().get("token");
        String uri = "http://" + SOURCE_HOST
                + ":" + SOURCE.getAddress().getPort() + "/failure";
        ResponseEntity<Map> parsed = post("/api/v1/parses", Map.of("uri", uri), token);
        assertThat(parsed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String resourceId = (String) parsed.getBody().get("resourceId");
        ResponseEntity<Map> created = post("/api/v1/tasks",
                Map.of("parseId", parsed.getBody().get("id"), "fileIndices", List.of(0)), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskPath = "/api/v1/tasks/" + created.getBody().get("id");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            taskService.dispatchTick(3);
            assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("FAILED");
        });
        FAIL_DOWNLOAD.set(false);
        taskService.dispatchTick(3);
        assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("FAILED");
        assertThat(post(taskPath + "/retry", Map.of(), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            taskService.dispatchTick(3);
            assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("VERIFYING");
        });
        taskService.dispatchTick(3);
        assertThat(get(taskPath, token).getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(STORAGE.resolve("resources").resolve(resourceId)
                .resolve("accepted.bin"))).isEqualTo(PAYLOAD);
    }

    private static long completed(Aria2Client rpc, String gid) {
        return Long.parseLong((String) rpc.tellStatus(gid).get("completedLength"));
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private static String endpoint() {
        return "http://" + ARIA2.getHost() + ":" + ARIA2.getMappedPort(6800) + "/jsonrpc";
    }

    private static Path temporaryStorage() {
        try { return Files.createTempDirectory("magnet-real-aria2-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static String sourceHost() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.connect(java.net.InetAddress.getByName("192.0.2.1"), 9);
            return socket.getLocalAddress().getHostAddress();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static byte[] payload() {
        byte[] bytes = new byte[16 * 1024 * 1024];
        new java.util.Random(42).nextBytes(bytes);
        return bytes;
    }

    private static HttpServer source() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/download", Aria2DownloadIntegrationTest::serve);
            server.createContext("/failure", Aria2DownloadIntegrationTest::serve);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static HttpServer secureSource() {
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(TLS));
            server.createContext("/download", Aria2DownloadIntegrationTest::serve);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static javax.net.ssl.SSLContext tls() {
        try {
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Path keyStore = STORAGE.resolve("fixture.p12");
            runKeytool(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=aria2 acceptance", "-validity", "2", "-ext", "bc=ca:true",
                    "-ext", "SAN=ip:" + SOURCE_HOST,
                    "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "fixture-password");
            runKeytool(keytool, "-exportcert", "-rfc", "-alias", "fixture", "-keystore", keyStore.toString(),
                    "-storepass", "fixture-password", "-file", STORAGE.resolve("fixture-ca.pem").toString());
            java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStore)) { keys.load(input, "fixture-password".toCharArray()); }
            var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "fixture-password".toCharArray());
            var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            var context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            return context;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static void runKeytool(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    @TestConfiguration
    static class FixtureTrust {
        @Bean
        @Primary
        HttpProbeService fixtureTrustedProbe() {
            return new HttpProbeService(java.net.http.HttpClient.newBuilder().sslContext(TLS)
                    .proxy(java.net.ProxySelector.of(null))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build());
        }
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestURI().getPath().equals("/failure")
                    && !exchange.getRequestMethod().equals("HEAD") && FAIL_DOWNLOAD.get()) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=accepted.bin");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(PAYLOAD.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            GET_REQUESTS.incrementAndGet();
            int start = 0;
            int end = PAYLOAD.length - 1;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                RANGES.incrementAndGet();
                String[] bounds = range.substring("bytes=".length()).split("-", -1);
                start = Integer.parseInt(bounds[0]);
                if (start > 0) NONZERO_RANGES.incrementAndGet();
                if (!bounds[1].isEmpty()) end = Math.min(end, Integer.parseInt(bounds[1]));
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + PAYLOAD.length);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1L);
            for (int offset = start; offset <= end; offset += 16384) {
                exchange.getResponseBody().write(PAYLOAD, offset, Math.min(16384, end - offset + 1));
                exchange.getResponseBody().flush();
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        } catch (IOException ignored) {
            // Pausing and restarting aria2 deliberately closes in-flight HTTP streams.
        }
    }

    @AfterAll
    static void stopSource() { SOURCE.stop(0); SECURE_SOURCE.stop(0); }
}
