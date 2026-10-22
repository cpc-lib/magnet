package com.magnetplatform.application;

import com.magnetplatform.application.port.ParseJobRepository;
import com.magnetplatform.application.port.RateLimiter;
import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.application.port.TorrentStorage;
import com.magnetplatform.domain.common.Digests;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.common.Hex;
import com.magnetplatform.domain.parse.InputType;
import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.parse.ParseStatus;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.torrent.MagnetParser;
import com.magnetplatform.domain.torrent.ParsedMagnet;
import com.magnetplatform.domain.torrent.ParsedTorrent;
import com.magnetplatform.domain.torrent.ParsedTorrentFile;
import com.magnetplatform.domain.torrent.TorrentMetaParser;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.ResourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 解析编排（规格 §4.2）：
 * Magnet → 限流 → 格式校验 → 资源去重（无则建骨架）→ RESOLVING 任务 → 引擎 resolve-only；
 * .torrent → 限流 → 元数据校验 → 资源去重 / Hybrid 升级 / 元数据补全 → 字节落盘 → READY 任务。
 */
@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    public static final String HASH_BTIH = "btih";
    public static final String HASH_BTMH = "btmh";

    private final ParseJobRepository parseJobs;
    private final TorrentResourceRepository resources;
    private final RateLimiter rateLimiter;
    private final TorrentStorage storage;
    private final List<DownloadEngine> engines;
    private final ParseProperties properties;
    private final MagnetParser magnetParser = new MagnetParser();
    private final TorrentMetaParser torrentParser = new TorrentMetaParser();

    public ParseService(ParseJobRepository parseJobs,
                        TorrentResourceRepository resources,
                        RateLimiter rateLimiter,
                        TorrentStorage storage,
                        List<DownloadEngine> engines,
                        ParseProperties properties) {
        this.parseJobs = parseJobs;
        this.resources = resources;
        this.rateLimiter = rateLimiter;
        this.storage = storage;
        this.engines = engines;
        this.properties = properties;
    }

    @Transactional
    public ParseJob submitMagnet(UUID userId, String uri) {
        consumeQuota(userId);
        ParsedMagnet magnet = magnetParser.parse(uri);
        TorrentResource resource = findResource(magnet.btih(), magnet.btmh())
                .orElseGet(() -> createSkeletonResource(magnet));
        ParseJob job = newJob(userId, resource.getId(), InputType.MAGNET,
                primaryHash(magnet.btih(), magnet.btmh()), uri, null,
                magnet.displayNameHint(), ParseStatus.RESOLVING);
        parseJobs.insert(job);
        dispatchResolve(resource);
        log.info("[parse] submitMagnet userId={} resourceId={} jobId={} status=RESOLVING", userId, resource.getId(), job.getId());
        return job;
    }

    @Transactional
    public ParseJob submitTorrent(UUID userId, String filename, byte[] bytes) {
        consumeQuota(userId);
        if (bytes == null || bytes.length == 0) {
            throw new DomainException(ErrorCodes.INVALID_TORRENT, "torrent 内容为空");
        }
        if (bytes.length > properties.maxTorrentBytes()) {
            throw new DomainException(ErrorCodes.INVALID_TORRENT,
                    "torrent 超过字节上限 " + properties.maxTorrentBytes());
        }
        ParsedTorrent meta = torrentParser.parse(bytes);
        String path = storage.saveTorrent(bytes);
        TorrentResource resource = resolveTorrentResource(meta, path);
        ParseJob job = newJob(userId, resource.getId(), InputType.TORRENT, Digests.sha256(bytes),
                filename, path, resource.getName(), ParseStatus.READY);
        parseJobs.insert(job);
        log.info("[parse] submitTorrent userId={} resourceId={} jobId={} name={} files={}", userId, resource.getId(), job.getId(), resource.getName(), resource.getFileCount());
        return job;
    }

    /** 仅 FAILED / TIMEOUT 可重试；Magnet 重新下发解析，torrent 重新读取已存字节 */
    @Transactional
    public ParseJob retry(UUID actorId, boolean admin, UUID jobId) {
        ParseJob job = getOwned(actorId, admin, jobId);
        if (job.getStatus() != ParseStatus.FAILED && job.getStatus() != ParseStatus.TIMEOUT) {
            throw new DomainException(ErrorCodes.VALIDATION, "仅失败或超时的解析任务可重试");
        }
        if (job.getInputType() == InputType.MAGNET) {
            ParsedMagnet magnet = magnetParser.parse(job.getInputUri());
            TorrentResource resource = findResource(magnet.btih(), magnet.btmh())
                    .orElseGet(() -> createSkeletonResource(magnet));
            parseJobs.markStatus(job.getId(), ParseStatus.RESOLVING, null, null);
            dispatchResolve(resource);
        } else {
            byte[] bytes = storage.loadTorrent(job.getInputPath())
                    .orElseThrow(() -> new DomainException(ErrorCodes.INPUT_LOST,
                            "上传的 torrent 文件已丢失，请重新上传"));
            if (job.getInputHash() != null
                    && !Arrays.equals(job.getInputHash(), Digests.sha256(bytes))) {
                throw new DomainException(ErrorCodes.CONFLICT,
                        "上传的 torrent 文件内容已被替换，请重新上传");
            }
            ParsedTorrent meta = torrentParser.parse(bytes);
            TorrentResource resource = resources.findById(job.getResourceId())
                    .orElseThrow(() -> new DomainException(ErrorCodes.INPUT_LOST,
                            "解析任务关联的资源已丢失"));
            validateResolvedMetadata(resource, meta);
            resolveTorrentResource(resource, meta, job.getInputPath());
            parseJobs.markStatus(job.getId(), ParseStatus.READY, null, null);
        }
        return parseJobs.findById(job.getId()).orElseThrow();
    }

    public ParseJob getParse(UUID actorId, boolean admin, UUID jobId) {
        return getOwned(actorId, admin, jobId);
    }

    public Page<ParseJob> listParses(UUID userId, ParseStatus status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new DomainException(ErrorCodes.VALIDATION, "分页参数不合法（page>=1, 1<=size<=100）");
        }
        long total = parseJobs.countByUser(userId, status);
        List<ParseJob> items = parseJobs.findByUser(userId, status, (page - 1) * size, size);
        return new Page<>(items, total, page, size);
    }

    /** 非管理员需本人存在该资源的解析记录，否则按 404 处理（不泄露资源存在性） */
    public ResourceDetail getResourceFiles(UUID actorId, boolean admin, UUID resourceId) {
        TorrentResource resource = resources.findById(resourceId)
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "资源不存在"));
        if (!admin && !parseJobs.existsByUserAndResource(actorId, resourceId)) {
            throw new DomainException(ErrorCodes.NOT_FOUND, "资源不存在");
        }
        return new ResourceDetail(resource, resources.findFiles(resourceId));
    }

    /** 调度器调用：超时的 RESOLVING 任务置为 TIMEOUT */
    @Transactional
    public int sweepTimeouts() {
        return parseJobs.markTimeoutResolving(LocalDateTime.now().minusSeconds(properties.timeoutSeconds()));
    }

    /** RESOLVING 任务元数据回填：引擎轮询到 .torrent 字节则补全资源元数据并置 READY，返回本轮完成数（调度器用） */
    @Transactional
    public int completeResolvedMetadata() {
        DownloadEngine engine = engines.stream()
                .filter(e -> e.kind() == EngineKind.LIBTORRENT)
                .findFirst()
                .orElse(null);
        if (engine == null) {
            return 0;
        }
        int completed = 0;
        for (ParseJob job : parseJobs.findByStatus(ParseStatus.RESOLVING, 50)) {
            byte[] torrentBytes = engine.fetchMetadata(job.getResourceId().toString());
            if (torrentBytes.length == 0) {
                continue; // 未就绪（PENDING）或会话不存在
            }
            try {
                TorrentResource resource = resources.findById(job.getResourceId())
                        .orElseThrow(() -> new DomainException(ErrorCodes.INPUT_LOST,
                                "解析任务关联的资源已丢失"));
                // 先校验并原子落盘，再补全原始骨架资源的元数据/文件列表
                ParsedTorrent meta = torrentParser.parse(torrentBytes);
                validateResolvedMetadata(resource, meta);
                String metadataPath = storage.saveTorrent(torrentBytes);
                resource = resolveTorrentResource(resource, meta, metadataPath);
                parseJobs.markStatus(job.getId(), ParseStatus.READY, null, null);
                completed++;
                log.info("[parse] 元数据回填 READY jobId={} resourceId={} name={} files={}",
                        job.getId(), resource.getId(), resource.getName(), resource.getFileCount());
            } catch (DomainException e) {
                parseJobs.markStatus(job.getId(), ParseStatus.FAILED, e.code(), e.getMessage());
            }
        }
        return completed;
    }

    // ---------- private ----------

    private void consumeQuota(UUID userId) {
        if (rateLimiter.hitParseToday(userId) > properties.perUserDailyLimit()) {
            throw new DomainException(ErrorCodes.RATE_LIMITED, "超出每日解析配额");
        }
    }

    private ParseJob getOwned(UUID actorId, boolean admin, UUID jobId) {
        return parseJobs.findById(jobId)
                .filter(j -> admin || j.getUserId().equals(actorId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "解析任务不存在"));
    }

    private Optional<TorrentResource> findResource(byte[] btih, byte[] btmh) {
        if (btih != null) {
            Optional<TorrentResource> found = resources.findByHash(HASH_BTIH, btih);
            if (found.isPresent()) {
                return found;
            }
        }
        return btmh != null ? resources.findByHash(HASH_BTMH, btmh) : Optional.empty();
    }

    /** 仅 Magnet 时的骨架资源：元数据未就绪（fileCount=null），等待引擎解析或用户补传 .torrent */
    private TorrentResource createSkeletonResource(ParsedMagnet magnet) {
        TorrentResource resource = new TorrentResource(UUID.randomUUID(),
                canonicalKey(magnet.btih(), magnet.btmh()),
                magnet.btih(), magnet.btmh(), magnet.versionType(),
                magnet.displayNameHint(), null, null, false,
                "MAGNET", magnet.trackers(), null, LocalDateTime.now());
        insertWithAliases(resource);
        return resource;
    }

    /** 解析 .torrent 元数据并做资源去重 / Hybrid 升级 / 骨架资源元数据补全 */
    private TorrentResource resolveTorrentResource(ParsedTorrent meta, String metadataPath) {
        return resolveTorrentResource(findResource(meta.infoHashV1(), meta.infoHashV2()).orElse(null), meta, metadataPath);
    }

    private TorrentResource resolveTorrentResource(TorrentResource existing, ParsedTorrent meta, String metadataPath) {
        if (existing == null) {
            TorrentResource resource = new TorrentResource(UUID.randomUUID(),
                    canonicalKey(meta.infoHashV1(), meta.infoHashV2()),
                    meta.infoHashV1(), meta.infoHashV2(), meta.versionType(),
                    meta.name(), meta.pieceLength(), meta.totalBytes(), meta.privateFlag(),
                    "TORRENT", meta.trackers(), meta.files().size(), LocalDateTime.now(), metadataPath);
            insertWithAliases(resource);
            resources.insertFiles(resource.getId(), toFileInfos(meta.files()));
            return resource;
        }

        byte[] btih = existing.getBtih() != null ? existing.getBtih() : meta.infoHashV1();
        byte[] btmh = existing.getBtmh() != null ? existing.getBtmh() : meta.infoHashV2();
        try {
            if (existing.getBtih() == null && meta.infoHashV1() != null) {
                resources.insertAlias(existing.getId(), HASH_BTIH, meta.infoHashV1());
            }
            if (existing.getBtmh() == null && meta.infoHashV2() != null) {
                resources.insertAlias(existing.getId(), HASH_BTMH, meta.infoHashV2());
            }
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCodes.CONFLICT, "哈希已归属其他资源，元数据不一致");
        }
        boolean hybridUpgraded = btih != existing.getBtih() || btmh != existing.getBtmh();
        boolean metadataPathAdded = existing.getMetadataPath() == null && metadataPath != null;
        if (hybridUpgraded || !existing.metadataReady() || metadataPathAdded) {
            VersionType versionType = btih != null && btmh != null ? VersionType.HYBRID
                    : (btih != null ? VersionType.V1 : VersionType.V2);
            String resolvedMetadataPath = existing.getMetadataPath() != null
                    ? existing.getMetadataPath() : metadataPath;
            TorrentResource updated = new TorrentResource(existing.getId(), existing.getCanonicalKey(),
                    btih, btmh, versionType,
                    meta.name(), meta.pieceLength(), meta.totalBytes(), meta.privateFlag(),
                    existing.getMetadataSource(),
                    meta.trackers().isEmpty() ? existing.getTrackers() : meta.trackers(),
                    meta.files().size(), existing.getCreatedAt(), resolvedMetadataPath);
            resources.update(updated);
            if (!existing.metadataReady()) {
                resources.insertFiles(existing.getId(), toFileInfos(meta.files()));
            }
            return updated;
        }
        return existing;
    }

    private static void validateResolvedMetadata(TorrentResource resource, ParsedTorrent meta) {
        if (hashMismatch(resource.getBtih(), meta.infoHashV1())
                || hashMismatch(resource.getBtmh(), meta.infoHashV2())) {
            throw new DomainException(ErrorCodes.CONFLICT,
                    "引擎返回的 torrent 哈希与原始 Magnet 不匹配");
        }
    }

    private static boolean hashMismatch(byte[] registered, byte[] resolved) {
        return registered != null && registered.length > 0 && !Arrays.equals(registered, resolved);
    }

    private void insertWithAliases(TorrentResource resource) {
        try {
            resources.insert(resource);
            if (resource.getBtih() != null) {
                resources.insertAlias(resource.getId(), HASH_BTIH, resource.getBtih());
            }
            if (resource.getBtmh() != null) {
                resources.insertAlias(resource.getId(), HASH_BTMH, resource.getBtmh());
            }
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCodes.CONFLICT, "资源哈希冲突");
        }
    }

    private void dispatchResolve(TorrentResource resource) {
        DownloadEngine engine = engines.stream()
                .filter(e -> e.kind() == EngineKind.LIBTORRENT)
                .findFirst()
                .orElse(null);
        if (engine == null) {
            log.warn("[parse] LIBTORRENT 引擎未接入，resourceId={} 保持 RESOLVING 等待超时清扫", resource.getId());
            return; // 引擎未接入：任务保持 RESOLVING，由超时清扫收尾
        }
        try {
            engine.createOrAttach(new ResourceDescriptor(resource.getId().toString(),
                            resource.getBtih(), resource.getBtmh(), resource.getVersionType().name(),
                            null, resource.getTrackers(), null,
                            "resources/" + resource.getId(), null),
                    EngineOptions.resolveOnly());
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCodes.WORKER_UNAVAILABLE, "下载引擎暂不可用: " + e.getMessage());
        }
    }

    private static ParseJob newJob(UUID userId, UUID resourceId, InputType type, byte[] inputHash,
                                   String inputUri, String inputPath, String displayHint, ParseStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new ParseJob(UUID.randomUUID(), userId, resourceId, type, inputHash, inputUri, inputPath,
                displayHint, status, null, null, 0L, now, now);
    }

    private static String canonicalKey(byte[] btih, byte[] btmh) {
        return btih != null ? "btih:" + Hex.encode(btih) : "btmh:" + Hex.encode(btmh);
    }

    private static byte[] primaryHash(byte[] btih, byte[] btmh) {
        return btih != null ? btih : btmh;
    }

    private static Byte[] toWrapped(byte[] bytes) {
        if (bytes == null) return null;
        Byte[] wrapped = new Byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) wrapped[i] = bytes[i];
        return wrapped;
    }

    private static Boolean toWrapped(boolean b) {
        return b;
    }

    private static List<TorrentFileInfo> toFileInfos(List<ParsedTorrentFile> files) {
        return files.stream()
                .map(f -> new TorrentFileInfo(f.index(), f.relativePath(), f.sizeBytes(),
                        Hex.encode(f.v2PiecesRoot())))
                .toList();
    }
}
