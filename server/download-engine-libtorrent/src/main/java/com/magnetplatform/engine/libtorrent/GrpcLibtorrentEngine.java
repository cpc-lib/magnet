package com.magnetplatform.engine.libtorrent;

import com.google.protobuf.ByteString;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineCapabilities;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import com.magnetplatform.spi.ResumeSnapshot;
import com.magnetplatform.spi.SessionState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import magnetplatform.engine.v1.DownloadEngineGrpc;
import magnetplatform.engine.v1.DownloadEngineOuterClass;
import magnetplatform.engine.v1.DownloadEngineOuterClass.CommandEnvelope;
import magnetplatform.engine.v1.DownloadEngineOuterClass.UpdateFilePrioritiesRequest;
import magnetplatform.engine.v1.DownloadEngineOuterClass.UpdateFilePrioritiesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * libtorrent gRPC Sidecar Client 适配层。
 * 与 MockLibtorrentEngine 通过 @ConditionalOnProperty 互斥注册：
 *   magnet.engine.libtorrent.grpc.enabled=true  → 本 Grpc 实现
 *   默认                                        → Mock 实现
 *
 * 约定：engineSessionId 即 resource.resourceId()（同一资源同一引擎仅一个活跃会话），
 * 适配器拿到 engineSessionId 后直接作为 proto CommandEnvelope.resource_id 使用。
 */
@Component
@ConditionalOnProperty(name = "magnet.engine.libtorrent.grpc.enabled", havingValue = "true")
public class GrpcLibtorrentEngine implements DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(GrpcLibtorrentEngine.class);

    private static final long DEADLINE_SECONDS = 30L;

    private final ManagedChannel channel;
    private final DownloadEngineGrpc.DownloadEngineBlockingStub stub;
    /** P5（§12.2）：按会话记录 MySQL 租约派生的 owner_epoch，命令信封携带用于过期拒绝 */
    private record Lease(long epoch, Instant until) {}
    private final Map<String, Lease> sessionLeases = new ConcurrentHashMap<>();

    public GrpcLibtorrentEngine(
            @Value("${magnet.engine.libtorrent.grpc.endpoint:localhost:50051}") String endpoint,
            @Value("${magnet.engine.libtorrent.grpc.insecure:true}") boolean insecure) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(endpoint);
        if (insecure) {
            builder.usePlaintext();
        }
        this.channel = builder.build();
        this.stub = DownloadEngineGrpc.newBlockingStub(this.channel);
        log.info("[grpc-libtorrent] gRPC channel 已建立 endpoint={} insecure={}", endpoint, insecure);
    }

    @PostConstruct
    public void checkHealth() {
        try {
            DownloadEngineOuterClass.HealthResponse resp = rpc().health(
                    DownloadEngineOuterClass.HealthRequest.newBuilder().build());
            log.info("[grpc-libtorrent] Worker 就绪 ok={} workerId={} libtorrent={}",
                    resp.getOk(), resp.getWorkerId(), resp.getLibtorrentVersion());
        } catch (StatusRuntimeException e) {
            log.warn("[grpc-libtorrent] Worker 尚未就绪: {}", e.getStatus().getDescription());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public EngineKind kind() { return EngineKind.LIBTORRENT; }

    @Override
    public boolean available() {
        try {
            return rpc().health(DownloadEngineOuterClass.HealthRequest.newBuilder().build()).getOk();
        } catch (StatusRuntimeException e) {
            return false;
        }
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(true, true, true, false, true, true);
    }

    // --------------------------------------------------------------------- SPI 实现

    @Override
    public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
        // EngineSessionId 约定即 resourceId（UUID.toString()）
        String sessionId = resource.resourceId();
        Lease requested = new Lease(options.ownerEpoch(), options.leaseUntil());
        Lease existing = sessionLeases.get(sessionId);
        Lease lease = options.metadataOnly() && existing != null && existing.epoch() > 0 ? existing : requested;

        // torrent_file 字节由 TorrentStorage 层提供（当前 TaskService 未接入）；
        // 空 torrent_file 时传 btih/btmh/trackers，Worker 按 info_hash 挂会话走 DHT 抓元数据
        DownloadEngineOuterClass.AddTorrentRequest.Builder builder = DownloadEngineOuterClass.AddTorrentRequest.newBuilder()
                .setEnvelope(buildEnvelope(sessionId, lease))
                .setMetadataOnly(options.metadataOnly())
                .setStoragePath(resolveWorkerStoragePath(resource));
        if (resource.torrentFile() != null && resource.torrentFile().length > 0) {
            builder.setTorrentFile(ByteString.copyFrom(resource.torrentFile()));
        }
        if (resource.resumeData() != null && resource.resumeData().length > 0) {
            builder.setResumeData(ByteString.copyFrom(resource.resumeData()));
        }
        if (resource.btih() != null && resource.btih().length > 0) {
            builder.setBtih(ByteString.copyFrom(resource.btih()));
        }
        if (resource.btmh() != null && resource.btmh().length > 0) {
            builder.setBtmh(ByteString.copyFrom(resource.btmh()));
        }
        if (resource.trackers() != null && !resource.trackers().isEmpty()) {
            builder.addAllTrackers(resource.trackers());
        }
        DownloadEngineOuterClass.AddTorrentRequest req = builder.build();
        try {
            DownloadEngineOuterClass.AddTorrentResponse resp = rpc().addTorrent(req);
            if (!resp.getAccepted()) {
                throw new IllegalStateException("Worker rejected AddTorrent: " + resp.getError());
            }
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker AddTorrent unavailable", e);
        }
        sessionLeases.put(sessionId, lease);
        return new EngineSession(sessionId, EngineKind.LIBTORRENT, SessionState.DISCOVERING);
    }

    @Override
    public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        // 文件优先级：选中=1 未选中=0；UpdateFilePrioritiesRequest.file_priorities 与文件下标一一对应
        // 已知哪些文件下标要下载，但 Worker 侧不知道总文件数——先给选中的下标注 1，其余 Worker 会按缺失补 0
        Map<Integer, Integer> priorities = new HashMap<>();
        requestedFileIndices.forEach(i -> priorities.put(i, 1));
        // 默认优先级 0（不下载）已覆盖所有文件，不需要显式下发
        UpdateFilePrioritiesRequest req = UpdateFilePrioritiesRequest.newBuilder()
                .setEnvelope(buildEnvelope(engineSessionId))
                .addAllFilePriorities(indicesToPriorities(requestedFileIndices))
                .build();
        try {
            UpdateFilePrioritiesResponse resp = rpc().updateFilePriorities(req);
            if (!resp.getAccepted()) {
                throw new IllegalStateException("Worker rejected priorities: " + resp.getError());
            }
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker priorities unavailable", e);
        }
    }

    @Override
    public void pauseSession(String engineSessionId) {
        try {
            var response = rpc().pause(DownloadEngineOuterClass.CommandRequest.newBuilder()
                    .setEnvelope(buildEnvelope(engineSessionId)).build());
            if (!response.getAccepted()) throw new IllegalStateException(response.getError());
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker pause unavailable", e);
        }
    }

    @Override
    public void resumeSession(String engineSessionId) {
        try {
            var response = rpc().resume(DownloadEngineOuterClass.CommandRequest.newBuilder()
                    .setEnvelope(buildEnvelope(engineSessionId)).build());
            if (!response.getAccepted()) throw new IllegalStateException(response.getError());
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker resume unavailable", e);
        }
    }

    @Override
    public EngineSnapshot snapshot(String engineSessionId) {
        try {
            DownloadEngineOuterClass.GetSnapshotResponse resp = rpc().getSnapshot(
                    DownloadEngineOuterClass.GetSnapshotRequest.newBuilder()
                            .setEnvelope(buildEnvelope(engineSessionId)).build());
            SessionState state = SessionState.valueOf(resp.getState());
            return new EngineSnapshot(engineSessionId, state, resp.getMetadataReady(),
                    resp.getVerifiedBytes(),
                    resp.getDownloadRateBytesPerSec(), resp.getUploadRateBytesPerSec(),
                    resp.getPeerCount(),
                    List.copyOf(resp.getFileVerifiedBytesList()));
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker snapshot unavailable", e);
        }
    }

    @Override
    public ResumeSnapshot saveResume(String engineSessionId) {
        try {
            DownloadEngineOuterClass.SaveResumeDataResponse resp = rpc().saveResumeData(
                    DownloadEngineOuterClass.SaveResumeDataRequest.newBuilder()
                            .setEnvelope(buildEnvelope(engineSessionId)).build());
            if (!resp.getAccepted()) throw new IllegalStateException(resp.getError());
            return new ResumeSnapshot(engineSessionId, resp.getResumeVersion(),
                    resp.getResumeData().toByteArray());
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker resume snapshot unavailable", e);
        }
    }

    @Override
    public byte[] fetchMetadata(String engineSessionId) {
        if (!sessionLeases.containsKey(engineSessionId)) return new byte[0];
        try {
            DownloadEngineOuterClass.ResolveMetadataResponse resp = rpc().resolveMetadata(
                    DownloadEngineOuterClass.ResolveMetadataRequest.newBuilder()
                            .setEnvelope(buildEnvelope(engineSessionId)).build());
            if (!resp.getAccepted() || resp.getTorrentFile().isEmpty()) {
                return new byte[0]; // 会话不存在或元数据未就绪（PENDING）
            }
            return resp.getTorrentFile().toByteArray();
        } catch (StatusRuntimeException e) {
            log.warn("[grpc-libtorrent] fetchMetadata 失败 sessionId={} status={}",
                    engineSessionId, e.getStatus().getCode());
            return new byte[0];
        }
    }

    @Override
    public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        try {
            var response = rpc().removeTorrent(DownloadEngineOuterClass.RemoveTorrentRequest.newBuilder()
                    .setEnvelope(buildEnvelope(engineSessionId))
                    .setRemoveUnreferencedData(removeUnreferencedData).build());
            if (!response.getAccepted()) throw new IllegalStateException(response.getError());
            sessionLeases.remove(engineSessionId);
        } catch (StatusRuntimeException e) {
            throw new IllegalStateException("Worker removal unavailable", e);
        }
    }

    // --------------------------------------------------------------------- 辅助

    static String resolveWorkerStoragePath(ResourceDescriptor resource) {
        String raw = resource.storagePath();
        if (raw == null || raw.isBlank()) {
            return "resources/" + resource.resourceId();
        }
        String normalized = raw.trim().replace('\\', '/');
        if (normalized.equals("/data") || normalized.startsWith("/data/")) {
            return normalized;
        }
        int resourcesIdx = normalized.lastIndexOf("/resources/");
        if (resourcesIdx >= 0) {
            return normalized.substring(resourcesIdx + 1);
        }
        if (normalized.startsWith("resources/")) {
            return normalized;
        }
        if (normalized.startsWith("/") || normalized.contains(":")) {
            return "resources/" + resource.resourceId();
        }
        return normalized;
    }

    @Override
    public void renewLease(String sessionId, long ownerEpoch, Instant leaseUntil) {
        sessionLeases.put(sessionId, new Lease(ownerEpoch, leaseUntil));
    }

    private DownloadEngineGrpc.DownloadEngineBlockingStub rpc() {
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    private CommandEnvelope buildEnvelope(String resourceId) {
        Lease lease = sessionLeases.get(resourceId);
        if (lease == null) throw new IllegalStateException("No lease for resource " + resourceId);
        return buildEnvelope(resourceId, lease);
    }

    private CommandEnvelope buildEnvelope(String resourceId, Lease lease) {
        Instant deadline = Instant.now().plusSeconds(DEADLINE_SECONDS);
        if (lease.until().isBefore(deadline)) deadline = lease.until();
        return CommandEnvelope.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setResourceId(resourceId)
                .setOwnerEpoch(lease.epoch())
                .setLeaseUntil(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(lease.until().getEpochSecond()).setNanos(lease.until().getNano()).build())
                .setDeadline(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(deadline.getEpochSecond()).setNanos(deadline.getNano()).build())
                .build();
    }

    /** 把选中的文件下标集合转成优先级数组：选中=1，其余=0。
     *  proto 文件按 index 一一对应；Java 只知道"哪些选中"不知道"总文件数"，
     *  所以传 Map<index,priority>——但 proto 只认 repeated。折中：
     *  先给一个足够大的数组（比如 65536）全部初始化为 0，选中的下标设 1。
     *  真正的上限由 C++ Worker 根据 torrent 文件数截断。 */
    private List<Integer> indicesToPriorities(Set<Integer> indices) {
        int cap = 65536;
        int[] arr = new int[cap];
        indices.forEach(i -> { if (i >= 0 && i < cap) arr[i] = 1; });
        java.util.List<Integer> out = new java.util.ArrayList<>(cap);
        for (int v : arr) out.add(v);
        return out;
    }
}
