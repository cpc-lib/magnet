package com.magnetplatform.application;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpProbeServiceTest {

    private HttpServer server;
    private HttpProbeService probeService;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        probeService = new HttpProbeService(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probeHeadSuccessWithDisposition() {
        server.createContext("/file.bin", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "10485760");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"custom-name.zip\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        HttpProbeService.ProbeResult result = probeService.probe("http://127.0.0.1:" + port + "/file.bin");
        assertEquals("custom-name.zip", result.filename());
        assertEquals(10485760L, result.contentLength());
    }

    @Test
    void probeHeadSuccessWithRfc5987Utf8() {
        server.createContext("/doc", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "2048");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.pdf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        HttpProbeService.ProbeResult result = probeService.probe("http://127.0.0.1:" + port + "/doc");
        assertEquals("测试.pdf", result.filename());
        assertEquals(2048L, result.contentLength());
    }

    @Test
    void probeHeadFallbackToUriPath() {
        server.createContext("/archive/release-v1.tar.gz", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "5000");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        HttpProbeService.ProbeResult result = probeService.probe("http://127.0.0.1:" + port + "/archive/release-v1.tar.gz");
        assertEquals("release-v1.tar.gz", result.filename());
        assertEquals(5000L, result.contentLength());
    }

    @Test
    void probeHead405FallsBackToGetRange() {
        server.createContext("/stream", exchange -> {
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/999999");
                exchange.getResponseHeaders().set("Content-Disposition", "filename=range-stream.iso");
                exchange.sendResponseHeaders(206, 1);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(new byte[]{0});
                }
            }
            exchange.close();
        });
        server.start();

        HttpProbeService.ProbeResult result = probeService.probe("http://127.0.0.1:" + port + "/stream");
        assertEquals("range-stream.iso", result.filename());
        assertEquals(999999L, result.contentLength());
    }

    @Test
    void probeNonHttpSchemeThrowsValidation() {
        DomainException ex = assertThrows(DomainException.class, () -> probeService.probe("ftp://example.com/test.iso"));
        assertEquals(ErrorCodes.VALIDATION, ex.code());
    }

    @Test
    void probeUnreachableThrowsResolveFailed() {
        server.stop(0);
        DomainException ex = assertThrows(DomainException.class, () -> probeService.probe("http://127.0.0.1:" + port + "/notfound"));
        assertEquals(ErrorCodes.RESOLVE_FAILED, ex.code());
    }
}
