package com.magnetplatform.application;

import com.magnetplatform.application.port.ParseJobRepository;
import com.magnetplatform.application.port.RateLimiter;
import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.application.port.TorrentStorage;
import com.magnetplatform.domain.common.Digests;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.common.Hex;
import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.parse.ParseStatus;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.torrent.ParsedTorrent;
import com.magnetplatform.domain.torrent.TorrentMetaParser;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineCapabilities;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import com.magnetplatform.spi.ResumeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseServiceTest {

    // ---------- 内存假实现 ----------

    static class InMemoryResources implements TorrentResourceRepository {
        final Map<UUID, TorrentResource> byId = new LinkedHashMap<>();
        final Map<String, UUID> aliases = new HashMap<>();
        final Map<UUID, List<TorrentFileInfo>> files = new HashMap<>();

        public Optional<TorrentResource> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<TorrentResource> findByHash(String hashType, byte[] hashBytes) {
            UUID id = aliases.get(hashType + ":" + Hex.encode(hashBytes));
            return Optional.ofNullable(id == null ? null : byId.get(id));
        }

        public void insert(TorrentResource resource) {
            byId.put(resource.getId(), resource);
        }

        public void insertAlias(UUID resourceId, String hashType, byte[] hashBytes) {
            String key = hashType + ":" + Hex.encode(hashBytes);
            if (aliases.containsKey(key)) {
                throw new DataIntegrityViolationException("duplicate alias");
            }
            aliases.put(key, resourceId);
        }

        public void update(TorrentResource resource) {
            byId.put(resource.getId(), resource);
        }

        public void insertFiles(UUID resourceId, List<TorrentFileInfo> fileInfos) {
            files.put(resourceId, fileInfos);
        }

        public List<TorrentFileInfo> findFiles(UUID resourceId) {
            return files.getOrDefault(resourceId, List.of());
        }
    }

    static class InMemoryParseJobs implements ParseJobRepository {
        final Map<UUID, ParseJob> byId = new LinkedHashMap<>();

        public void insert(ParseJob job) {
            byId.put(job.getId(), job);
        }

        public Optional<ParseJob> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public List<ParseJob> findByUser(UUID userId, ParseStatus status, int offset, int limit) {
            return byId.values().stream()
                    .filter(j -> j.getUserId().equals(userId) && (status == null || j.getStatus() == status))
                    .sorted(Comparator.comparing(ParseJob::getCreatedAt).reversed())
                    .skip(offset).limit(limit).toList();
        }

        public long countByUser(UUID userId, ParseStatus status) {
            return byId.values().stream()
                    .filter(j -> j.getUserId().equals(userId) && (status == null || j.getStatus() == status))
                    .count();
        }

        public void markStatus(UUID id, ParseStatus status, String errorCode, String errorMessage) {
            ParseJob j = byId.get(id);
            byId.put(id, new ParseJob(j.getId(), j.getUserId(), j.getResourceId(), j.getInputType(), j.getInputHash(),
                    j.getInputUri(), j.getInputPath(), j.getDisplayHint(), status, errorCode, errorMessage,
                    j.getVersion() + 1, j.getCreatedAt(), LocalDateTime.now()));
        }

        public boolean existsByUserAndResource(UUID userId, UUID resourceId) {
            return byId.values().stream()
                    .anyMatch(j -> j.getUserId().equals(userId) && j.getResourceId().equals(resourceId));
        }

        public int markTimeoutResolving(LocalDateTime cutoff) {
            return 0; // 超时清扫由集成测试覆盖
        }

        public List<ParseJob> findByStatus(ParseStatus status, int limit) {
            return byId.values().stream()
                    .filter(j -> j.getStatus() == status)
                    .sorted(Comparator.comparing(ParseJob::getUpdatedAt))
                    .limit(limit)
                    .toList();
        }
    }

    static class FakeRateLimiter implements RateLimiter {
        long count;

        public long hitParseToday(UUID userId) {
            return ++count;
        }
    }

    static class FakeStorage implements TorrentStorage {
        final Map<String, byte[]> blobs = new HashMap<>();
        String lastSavedPath;

        public String saveTorrent(byte[] bytes) {
            String path = "uploads/" + Hex.encode(Digests.sha256(bytes)) + ".torrent";
            blobs.put(path, bytes);
            lastSavedPath = path;
            return path;
        }

        public Optional<byte[]> loadTorrent(String relativePath) {
            return Optional.ofNullable(blobs.get(relativePath));
        }
    }

    static class RecordingEngine implements DownloadEngine {
        int createCalls;
        byte[] metadataBytes;

        public EngineKind kind() {
            return EngineKind.LIBTORRENT;
        }

        public EngineCapabilities capabilities() {
            return new EngineCapabilities(true, true, true, false, true, true);
        }

        public EngineSession createOrAttach(ResourceDescriptor resource, EngineOptions options) {
            createCalls++;
            return null;
        }

        @Override
        public byte[] fetchMetadata(String engineSessionId) {
            return metadataBytes == null ? new byte[0] : metadataBytes;
        }

        public void setDemand(String engineSessionId, Set<Integer> requestedFileIndices) {
        }

        public void pauseSession(String engineSessionId) {
        }

        public void resumeSession(String engineSessionId) {
        }

        public EngineSnapshot snapshot(String engineSessionId) {
            return null;
        }

        public ResumeSnapshot saveResume(String engineSessionId) {
            return null;
        }

        public void removeSession(String engineSessionId, boolean removeUnreferencedData) {
        }
    }

    // ---------- 用例 ----------

    private static final String MAGNET =
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Test&tr=http://tracker.example/announce";

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private InMemoryResources resources;
    private InMemoryParseJobs jobs;
    private RecordingEngine engine;
    private FakeStorage storage;
    private ParseService service;

    @BeforeEach
    void setUp() {
        resources = new InMemoryResources();
        jobs = new InMemoryParseJobs();
        engine = new RecordingEngine();
        storage = new FakeStorage();
        service = new ParseService(jobs, resources, new FakeRateLimiter(), storage,
                List.of(engine), new ParseProperties(90, 50, null));
    }

    @Test
    void submitMagnet_createsSkeletonResourceAndResolvingJob() {
        ParseJob job = service.submitMagnet(alice, MAGNET);
        assertEquals(ParseStatus.RESOLVING, job.getStatus());
        assertEquals(1, engine.createCalls);
        TorrentResource resource = resources.findById(job.getResourceId()).orElseThrow();
        assertFalse(resource.metadataReady());
        assertEquals(VersionType.V1, resource.getVersionType());
        assertEquals("Test", resource.getName());
    }

    @Test
    void submitMagnet_reusesExistingResource() {
        ParseJob first = service.submitMagnet(alice, MAGNET);
        ParseJob second = service.submitMagnet(bob, MAGNET);
        assertEquals(first.getResourceId(), second.getResourceId());
    }

    @Test
    void submitMagnet_rateLimited() {
        service = new ParseService(jobs, resources, new FakeRateLimiter(), new FakeStorage(),
                List.of(engine), new ParseProperties(90, 1, null));
        service.submitMagnet(alice, MAGNET);
        assertCode(ErrorCodes.RATE_LIMITED, () -> service.submitMagnet(alice, MAGNET));
    }

    @Test
    void submitTorrent_readyAndSharedAcrossUsers() {
        byte[] bytes = tinyTorrent();
        ParseJob first = service.submitTorrent(alice, "a.torrent", bytes);
        assertEquals(ParseStatus.READY, first.getStatus());
        TorrentResource resource = resources.findById(first.getResourceId()).orElseThrow();
        assertTrue(resource.metadataReady());
        assertEquals(1, resource.getFileCount());
        assertEquals(storage.lastSavedPath, resource.getMetadataPath());

        ParseJob second = service.submitTorrent(bob, "b.torrent", bytes);
        assertEquals(first.getResourceId(), second.getResourceId());

        assertEquals(1, service.getResourceFiles(alice, false, first.getResourceId()).files().size());
        assertEquals(1, service.getResourceFiles(bob, false, first.getResourceId()).files().size());
    }

    @Test
    void completeResolvedMetadata_savesWorkerBytesAndAssignsSameResource() {
        byte[] bytes = tinyTorrent();
        ParsedTorrent parsed = new TorrentMetaParser().parse(bytes);
        ParseJob resolving = service.submitMagnet(alice,
                "magnet:?xt=urn:btih:" + Hex.encode(parsed.infoHashV1()) + "&dn=Resolved");
        UUID resourceId = resolving.getResourceId();
        engine.metadataBytes = bytes;

        assertEquals(1, service.completeResolvedMetadata());

        TorrentResource resource = resources.findById(resourceId).orElseThrow();
        assertEquals(resourceId, resource.getId());
        assertEquals(storage.lastSavedPath, resource.getMetadataPath());
        assertArrayEquals(bytes, storage.blobs.get(resource.getMetadataPath()));
        assertEquals(ParseStatus.READY, jobs.findById(resolving.getId()).orElseThrow().getStatus());
    }

    @Test
    void completeResolvedMetadata_rejectsHashMismatchForOriginalResource() {
        ParseJob resolving = service.submitMagnet(alice, MAGNET);
        UUID originalResourceId = resolving.getResourceId();
        engine.metadataBytes = tinyTorrent();

        assertEquals(0, service.completeResolvedMetadata());

        ParseJob failed = jobs.findById(resolving.getId()).orElseThrow();
        assertEquals(ParseStatus.FAILED, failed.getStatus());
        assertEquals(1, resources.byId.size());
        TorrentResource original = resources.findById(originalResourceId).orElseThrow();
        assertFalse(original.metadataReady());
        assertNull(original.getMetadataPath());
    }

    @Test
    void submitTorrent_preservesExistingMetadataPathWhenCompletingMetadata() {
        ParsedTorrent parsed = new TorrentMetaParser().parse(tinyTorrent());
        UUID resourceId = UUID.randomUUID();
        TorrentResource existing = new TorrentResource(resourceId,
                "btih:" + Hex.encode(parsed.infoHashV1()), parsed.infoHashV1(), parsed.infoHashV2(),
                parsed.versionType(), "skeleton", null, null, false, "MAGNET", parsed.trackers(),
                null, LocalDateTime.now(), "resources/existing-metadata.torrent");
        resources.insert(existing);
        resources.insertAlias(resourceId, ParseService.HASH_BTIH, parsed.infoHashV1());

        ParseJob job = service.submitTorrent(alice, "a.torrent", tinyTorrent());

        assertEquals("resources/existing-metadata.torrent",
                resources.findById(job.getResourceId()).orElseThrow().getMetadataPath());
    }

    @Test
    void getResourceFiles_hidesFromStrangers() {
        ParseJob job = service.submitTorrent(alice, "a.torrent", tinyTorrent());
        UUID stranger = UUID.randomUUID();
        assertCode(ErrorCodes.NOT_FOUND, () -> service.getResourceFiles(stranger, false, job.getResourceId()));
    }

    @Test
    void getParse_isolation() {
        ParseJob job = service.submitMagnet(alice, MAGNET);
        assertCode(ErrorCodes.NOT_FOUND, () -> service.getParse(bob, false, job.getId()));
        assertEquals(job.getId(), service.getParse(bob, true, job.getId()).getId()); // 管理员可越权
    }

    @Test
    void retry_failedMagnet_resolvingAgain() {
        ParseJob job = service.submitMagnet(alice, MAGNET);
        jobs.markStatus(job.getId(), ParseStatus.FAILED, "X", "x");
        ParseJob retried = service.retry(alice, false, job.getId());
        assertEquals(ParseStatus.RESOLVING, retried.getStatus());
        assertEquals(2, engine.createCalls);
    }

    @Test
    void retry_readyJob_rejected() {
        ParseJob job = service.submitTorrent(alice, "a.torrent", tinyTorrent());
        assertCode(ErrorCodes.VALIDATION, () -> service.retry(alice, false, job.getId()));
    }

    @Test
    void retry_torrent_readsStoredBytes() {
        ParseJob job = service.submitTorrent(alice, "a.torrent", tinyTorrent());
        jobs.markStatus(job.getId(), ParseStatus.FAILED, null, null);
        assertEquals(ParseStatus.READY, service.retry(alice, false, job.getId()).getStatus());
    }

    @Test
    void retry_torrent_rejectsReplacedValidTorrent() {
        ParseJob job = service.submitTorrent(alice, "a.torrent", tinyTorrent());
        UUID originalResourceId = job.getResourceId();
        jobs.markStatus(job.getId(), ParseStatus.FAILED, null, null);
        storage.blobs.put(job.getInputPath(), anotherTinyTorrent());

        DomainException ex = assertThrows(DomainException.class,
                () -> service.retry(alice, false, job.getId()));

        assertEquals(ErrorCodes.CONFLICT, ex.code());
        assertEquals(ParseStatus.FAILED, jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(Set.of(originalResourceId), resources.byId.keySet());
    }

    @Test
    void retry_torrent_rejectsReplacedBytesWithSameInfoHash() {
        byte[] originalBytes = tinyTorrent();
        byte[] replacedBytes = tinyTorrentWithAnnounce();
        ParsedTorrent originalMeta = new TorrentMetaParser().parse(originalBytes);
        ParsedTorrent replacedMeta = new TorrentMetaParser().parse(replacedBytes);
        assertArrayEquals(originalMeta.infoHashV1(), replacedMeta.infoHashV1());
        assertFalse(Arrays.equals(Digests.sha256(originalBytes), Digests.sha256(replacedBytes)));

        ParseJob job = service.submitTorrent(alice, "a.torrent", originalBytes);
        UUID originalResourceId = job.getResourceId();
        String originalMetadataPath = resources.findById(originalResourceId).orElseThrow().getMetadataPath();
        jobs.markStatus(job.getId(), ParseStatus.FAILED, null, null);
        storage.blobs.put(job.getInputPath(), replacedBytes);

        assertCode(ErrorCodes.CONFLICT, () -> service.retry(alice, false, job.getId()));

        assertEquals(ParseStatus.FAILED, jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(Set.of(originalResourceId), resources.byId.keySet());
        assertEquals(originalMetadataPath, resources.findById(originalResourceId).orElseThrow().getMetadataPath());
    }

    // ---------- 工具 ----------

    @Test
    void metadataPollingReattachesUnresolvedSessionAfterWorkerLoss() {
        service.submitMagnet(alice, MAGNET);
        int before = engine.createCalls;
        assertEquals(0, service.completeResolvedMetadata());
        assertEquals(before + 1, engine.createCalls);
    }

    /** 最小合法 V1 单文件 torrent：5 字节内容、1 个 piece（结构校验不核对内容哈希） */
    private static byte[] tinyTorrent() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("d4:infod6:lengthi5e4:name4:test12:piece lengthi16384e6:pieces20:"
                    .getBytes(StandardCharsets.UTF_8));
            out.write(new byte[20]);
            out.write("ee".getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] anotherTinyTorrent() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("d4:infod6:lengthi5e4:name4:demo12:piece lengthi16384e6:pieces20:"
                    .getBytes(StandardCharsets.UTF_8));
            out.write(new byte[20]);
            out.write("ee".getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] tinyTorrentWithAnnounce() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("d8:announce14:http://tracker4:infod6:lengthi5e4:name4:test12:piece lengthi16384e6:pieces20:"
                    .getBytes(StandardCharsets.UTF_8));
            out.write(new byte[20]);
            out.write("ee".getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertCode(String code, Executable exec) {
        DomainException ex = assertThrows(DomainException.class, exec);
        assertEquals(code, ex.code());
    }
}
