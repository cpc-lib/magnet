package com.magnetplatform.engine.aria2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magnetplatform.engine.aria2.client.Aria2Client;
import com.magnetplatform.engine.aria2.client.Aria2RpcException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Aria2ClientTest {

    private HttpServer server;
    private Aria2Client client;
    private String lastRequestBody;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jsonrpc", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            lastRequestBody = new String(bytes, StandardCharsets.UTF_8);

            String responseBody;
            if (lastRequestBody.contains("errorTest")) {
                responseBody = "{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"error\":{\"code\":1,\"message\":\"GID not found\"}}";
            } else if (lastRequestBody.contains("aria2.addUri")) {
                responseBody = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"2089b05e0a3d82d7\"}";
            } else if (lastRequestBody.contains("aria2.tellStatus")) {
                responseBody = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"gid\":\"2089b05e0a3d82d7\",\"status\":\"active\",\"totalLength\":\"1048576\",\"completedLength\":\"524288\",\"downloadSpeed\":\"102400\"}}";
            } else if (lastRequestBody.contains("aria2.getVersion")) {
                responseBody = "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"version\":\"1.36.0\"}}";
            } else {
                responseBody = "{\"jsonrpc\":\"2.0\",\"id\":\"99\",\"result\":\"OK\"}";
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] respBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        client = new Aria2Client("http://127.0.0.1:" + port + "/jsonrpc", "testsecret", Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void addUriSendsTokenAndOptions() {
        String gid = client.addUri(List.of("https://example.com/test.iso"), Map.of("dir", "/tmp", "gid", "2089b05e0a3d82d7"));
        assertThat(gid).isEqualTo("2089b05e0a3d82d7");
        assertThat(lastRequestBody).contains("token:testsecret");
        assertThat(lastRequestBody).contains("aria2.addUri");
        assertThat(lastRequestBody).contains("https://example.com/test.iso");
    }

    @Test
    void tellStatusParsesResponse() {
        Map<String, Object> status = client.tellStatus("2089b05e0a3d82d7");
        assertThat(status).containsEntry("status", "active");
        assertThat(status).containsEntry("totalLength", "1048576");
        assertThat(status).containsEntry("completedLength", "524288");
        assertThat(status).containsEntry("downloadSpeed", "102400");
    }

    @Test
    void getVersionWorks() {
        Map<String, Object> version = client.getVersion();
        assertThat(version).containsEntry("version", "1.36.0");
    }

    @Test
    void rpcErrorThrowsAria2RpcException() {
        // 构造一个会触发 error 响应的 client
        Aria2Client errClient = new Aria2Client(clientEndpoint(), "testsecret", Duration.ofSeconds(3)) {
            @Override
            public void pause(String gid) {
                tellStatus("errorTest");
            }
        };
        assertThatThrownBy(() -> errClient.tellStatus("errorTest"))
                .isInstanceOf(Aria2RpcException.class)
                .hasMessageContaining("GID not found");
    }

    private String clientEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jsonrpc";
    }
}
