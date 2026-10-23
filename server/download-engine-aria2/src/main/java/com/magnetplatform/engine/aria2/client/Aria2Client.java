package com.magnetplatform.engine.aria2.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * aria2 JSON-RPC 2.0 客户端实现。
 * 遵循官方 RPC 规范：支持 token 鉴权与基础任务生命周期调用。
 */
public class Aria2Client {

    private final URI endpoint;
    private final String secret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestId = new AtomicLong(1);

    public Aria2Client(String endpoint, String secret, Duration timeout) {
        this(endpoint, secret, timeout, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build(), new ObjectMapper());
    }

    public Aria2Client(String endpoint, String secret, Duration timeout, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = URI.create(endpoint);
        this.secret = secret;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String addUri(List<String> uris, Map<String, Object> options) {
        List<Object> params = new ArrayList<>();
        params.add(uris);
        if (options != null && !options.isEmpty()) {
            params.add(options);
        }
        JsonNode result = invoke("aria2.addUri", params);
        return result.asText();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> tellStatus(String gid) {
        List<Object> params = List.of(gid);
        JsonNode result = invoke("aria2.tellStatus", params);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public void pause(String gid) {
        invoke("aria2.pause", List.of(gid));
    }

    public void unpause(String gid) {
        invoke("aria2.unpause", List.of(gid));
    }

    public void remove(String gid) {
        invoke("aria2.remove", List.of(gid));
    }

    public void removeDownloadResult(String gid) {
        invoke("aria2.removeDownloadResult", List.of(gid));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getVersion() {
        JsonNode result = invoke("aria2.getVersion", Collections.emptyList());
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    private JsonNode invoke(String method, List<Object> params) {
        List<Object> actualParams = new ArrayList<>();
        if (secret != null && !secret.isBlank()) {
            actualParams.add("token:" + secret);
        }
        actualParams.addAll(params);

        Map<String, Object> requestPayload = Map.of(
                "jsonrpc", "2.0",
                "id", String.valueOf(requestId.getAndIncrement()),
                "method", method,
                "params", actualParams
        );

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestPayload);
        } catch (IOException e) {
            throw new Aria2RpcException("序列化 JSON-RPC 请求失败: " + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Aria2RpcException("调用 aria2 RPC 网络异常: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Aria2RpcException("aria2 RPC 请求被中断", e);
        }

        if (response.statusCode() != 200) {
            throw new Aria2RpcException("aria2 RPC 响应非 200 HTTP 状态: " + response.statusCode() + " body=" + response.body(), null);
        }

        JsonNode responseNode;
        try {
            responseNode = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new Aria2RpcException("解析 aria2 RPC 响应 JSON 失败: " + e.getMessage(), e);
        }

        if (responseNode.has("error") && !responseNode.get("error").isNull()) {
            JsonNode errorNode = responseNode.get("error");
            int code = errorNode.has("code") ? errorNode.get("code").asInt() : -1;
            String message = errorNode.has("message") ? errorNode.get("message").asText() : "未知错误";
            throw new Aria2RpcException(code, message);
        }

        return responseNode.get("result");
    }
}
