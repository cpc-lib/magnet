package com.magnetplatform.engine.aria2;

import com.magnetplatform.engine.aria2.client.Aria2Client;
import com.magnetplatform.engine.aria2.client.Aria2RpcException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * aria2 JSON-RPC 引擎适配器（P6 里程碑：多协议 HTTP/HTTPS 支持）。
 * 当 magnet.engine.aria2.rpc.enabled=true 时生效。
 */
@Component
@ConditionalOnProperty(name = "magnet.engine.aria2.rpc.enabled", havingValue = "true")
public class Aria2JsonRpcEngine implements DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(Aria2JsonRpcEngine.class);

    private final Aria2Client client;
    private final String storageRoot;

    @Autowired
    public Aria2JsonRpcEngine(
            @Value("${magnet.engine.aria2.rpc.endpoint:http://localhost:6800/jsonrpc}") String endpoint,
            @Value("${magnet.engine.aria2.rpc.secret:}") String secret,
            @Value("${magnet.engine.aria2.rpc.timeout-seconds:10}") int timeoutSeconds,
            @Value("${magnet.engine.aria2.rpc.storage-root:/data}") String storageRoot) {
        this(new Aria2Client(endpoint, secret, Duration.ofSeconds(timeoutSeconds)), storageRoot);
    }

    public Aria2JsonRpcEngine(Aria2Client client) {
        this(client, "/data");
    }

    public Aria2JsonRpcEngine(Aria2Client client, String storageRoot) {
        this.client = client;
        this.storageRoot = storageRoot.replace('\\', '/').replaceAll("/+$", "");
    }

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
        try {
            client.getVersion();
            return true;
        } catch (Exception e) {
            log.warn("[aria2] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
        String gid = toGid(resource.resourceId());
        try {
            Map<String, Object> status = client.tellStatus(gid);
            SessionState state = mapState((String) status.get("status"));
            log.info("[aria2] attach 现有任务 resourceId={} gid={} state={}", resource.resourceId(), gid, state);
            return new EngineSession(resource.resourceId(), EngineKind.ARIA2, state);
        } catch (Aria2RpcException e) {
            if (!isMissing(e)) throw e;
            // 不存在则创建新下载
            log.info("[aria2] 任务尚未在 aria2 中创建，发起 addUri resourceId={} gid={}", resource.resourceId(), gid);
        }

        Map<String, Object> rpcOptions = new HashMap<>();
        rpcOptions.put("dir", storageRoot + "/resources/" + java.util.UUID.fromString(resource.resourceId()));
        if (resource.outputFilename() == null || resource.outputFilename().isBlank()
                || resource.outputFilename().matches(".*[\\\\/].*")
                || resource.outputFilename().equals(".") || resource.outputFilename().equals("..")) {
            throw new IllegalArgumentException("HTTP resource requires a safe output filename");
        }
        rpcOptions.put("out", resource.outputFilename());
        rpcOptions.put("continue", "true");
        rpcOptions.put("auto-file-renaming", "false");
        rpcOptions.put("allow-overwrite", "false");
        rpcOptions.put("file-allocation", "none");
        rpcOptions.put("split", "4");
        rpcOptions.put("max-connection-per-server", "4");
        rpcOptions.put("min-split-size", "1M");
        rpcOptions.put("gid", gid);
        rpcOptions.put("pause", "true");

        String returnedGid = client.addUri(List.of(resource.metadataUri()), rpcOptions);
        log.info("[aria2] addUri 成功 resourceId={} gid={}", resource.resourceId(), returnedGid);
        return new EngineSession(resource.resourceId(), EngineKind.ARIA2, SessionState.IDLE);
    }

    @Override
    public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        // aria2 HTTP 单文件下载：需求非空即可
        log.debug("[aria2] setDemand session={} files={}", engineSessionId, requestedFileIndices);
    }

    @Override
    public void pauseSession(String engineSessionId) {
        String gid = toGid(engineSessionId);
        String state = (String) client.tellStatus(gid).get("status");
        if ("active".equals(state) || "waiting".equals(state)) {
            client.pause(gid);
            log.info("[aria2] pauseSession 成功 session={} gid={}", engineSessionId, gid);
        }
    }

    @Override
    public void resumeSession(String engineSessionId) {
        String gid = toGid(engineSessionId);
        String state = (String) client.tellStatus(gid).get("status");
        if ("paused".equals(state)) {
            client.unpause(gid);
            log.info("[aria2] resumeSession 成功 session={} gid={}", engineSessionId, gid);
        }
    }

    @Override
    public EngineSnapshot snapshot(String engineSessionId) {
        String gid = toGid(engineSessionId);
        Map<String, Object> status = client.tellStatus(gid);
        String statusStr = (String) status.get("status");
        SessionState state = mapState(statusStr);
        long completed = parseLong(status.get("completedLength"));
        long speed = parseLong(status.get("downloadSpeed"));

        List<Long> fileVerified = List.of(completed);
        return new EngineSnapshot(engineSessionId, state, true, completed, speed, 0L, 1, fileVerified);
    }

    @Override
    public ResumeSnapshot saveResume(String engineSessionId) {
        // aria2 在磁盘目录自动保留断点续传文件（.aria2）
        return new ResumeSnapshot(engineSessionId, 1L, new byte[0]);
    }

    @Override
    public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        String gid = toGid(engineSessionId);
        try {
            String state = (String) client.tellStatus(gid).get("status");
            if (!Set.of("complete", "error", "removed").contains(state)) {
                client.remove(gid);
                state = (String) client.tellStatus(gid).get("status");
                if (!"removed".equals(state)) {
                    throw new IllegalStateException("aria2 removal is not yet confirmed");
                }
            }
            client.removeDownloadResult(gid);
        } catch (Aria2RpcException e) {
            if (!isMissing(e)) throw e;
        }
        log.info("[aria2] removeSession 完成 session={} gid={}", engineSessionId, gid);
    }

    private boolean isMissing(Aria2RpcException e) {
        return e.getCode() == 1 && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("not found");
    }

    private String toGid(String engineSessionId) {
        return engineSessionId.replace("-", "").substring(0, 16);
    }

    private SessionState mapState(String aria2Status) {
        if (aria2Status == null) return SessionState.ACTIVE;
        return switch (aria2Status.toLowerCase()) {
            case "active", "waiting" -> SessionState.ACTIVE;
            case "paused" -> SessionState.IDLE;
            case "complete" -> SessionState.ACTIVE; // 完成时保持 ACTIVE，快照 verifiedBytes==total 驱动两轮对账
            case "error" -> SessionState.FAILED;
            case "removed" -> SessionState.STOPPED;
            default -> SessionState.ACTIVE;
        };
    }

    private long parseLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
