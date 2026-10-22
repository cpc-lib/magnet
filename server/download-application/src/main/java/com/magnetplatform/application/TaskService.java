package com.magnetplatform.application;

import com.magnetplatform.application.port.DownloadTaskRepository;
import com.magnetplatform.application.port.EngineSessionRepository;
import com.magnetplatform.application.port.ParseJobRepository;
import com.magnetplatform.application.port.TaskEventRepository;
import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.application.port.TorrentStorage;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.task.DownloadTask;
import com.magnetplatform.domain.task.DownloadTaskFile;
import com.magnetplatform.domain.task.EngineSessionRecord;
import com.magnetplatform.domain.task.TaskStatus;
import com.magnetplatform.spi.DownloadEngine;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 下载任务编排（规格 §4.3 / §5）：
 * 用户任务独立于共享引擎会话；状态变更先持久化（CAS）后驱动引擎；暂停仅收回该任务需求，
 * 共享会话在有其他活跃任务时保持运行（ADR-003）。
 */
@Service
public class TaskService {

    private final DownloadTaskRepository tasks;
    private final TaskEventRepository events;
    private final EngineSessionRepository sessions;
    private final ParseJobRepository parseJobs;
    private final TorrentResourceRepository resources;
    private final TorrentStorage torrentStorage;
    private final DownloadStorage downloadStorage;
    private final List<DownloadEngine> engines;
    private final ExportService exportService;

    public TaskService(DownloadTaskRepository tasks,
                       TaskEventRepository events,
                       EngineSessionRepository sessions,
                       ParseJobRepository parseJobs,
                       TorrentResourceRepository resources,
                       TorrentStorage torrentStorage,
                       DownloadStorage downloadStorage,
                       List<DownloadEngine> engines) {
        this(tasks, events, sessions, parseJobs, resources, torrentStorage, downloadStorage, engines, null);
    }

    @Autowired
    public TaskService(DownloadTaskRepository tasks,
                       TaskEventRepository events,
                       EngineSessionRepository sessions,
                       ParseJobRepository parseJobs,
                       TorrentResourceRepository resources,
                       TorrentStorage torrentStorage,
                       DownloadStorage downloadStorage,
                       List<DownloadEngine> engines,
                       ExportService exportService) {
        this.tasks = tasks;
        this.events = events;
        this.sessions = sessions;
        this.parseJobs = parseJobs;
        this.resources = resources;
        this.torrentStorage = torrentStorage;
        this.downloadStorage = downloadStorage;
        this.engines = engines;
        this.exportService = exportService;
    }

    // ---------- 用户命令 ----------

    /** 基于已就绪解析结果创建任务（规格 §9.1）：校验文件下标、计算 selectedBytes */
    @Transactional
    public DownloadTask create(UUID userId, UUID parseId, List<Integer> fileIndices,
                               String targetSubdirectory, boolean startImmediately, Integer priority) {
        ParseJob job = parseJobs.findById(parseId)
                .filter(j -> j.getUserId().equals(userId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "解析任务不存在"));
        TorrentResource resource = resources.findById(job.getResourceId()).orElseThrow();
        if (!resource.metadataReady()) {
            throw new DomainException(ErrorCodes.VALIDATION, "元数据未就绪，无法选择文件（可重试解析或上传 .torrent）");
        }
        List<TorrentFileInfo> allFiles = resources.findFiles(resource.getId());
        Set<Integer> indices = validateFileIndices(fileIndices, allFiles);
        long selectedBytes = allFiles.stream()
                .filter(f -> indices.contains(f.getFileIndex()))
                .mapToLong(TorrentFileInfo::getSizeBytes).sum();
        String subdir = sanitizeSubdirectory(targetSubdirectory);
        LocalDateTime now = LocalDateTime.now();
        DownloadTask task = new DownloadTask(UUID.randomUUID(), userId, resource.getId(),
                startImmediately ? TaskStatus.QUEUED : TaskStatus.CREATED,
                selectedBytes, 0L, priority == null ? 0 : priority, null, subdir,
                null, null, 0L, now, now, null, null, null);
        List<DownloadTaskFile> taskFiles = allFiles.stream()
                .map(f -> new DownloadTaskFile(UUID.randomUUID(), task.getId(), f.getFileIndex(),
                        indices.contains(f.getFileIndex()), 0L, "NONE"))
                .toList();
        tasks.insert(task, taskFiles);
        events.append(task.getId(), "TASK_CREATED",
                "{\"selectedBytes\":" + selectedBytes + ",\"fileCount\":" + indices.size() + "}");
        return task;
    }

    public Page<DownloadTask> listTasks(UUID userId, TaskStatus status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new DomainException(ErrorCodes.VALIDATION, "分页参数不合法（page>=1, 1<=size<=100）");
        }
        long total = tasks.countByUser(userId, status);
        return new Page<>(tasks.findByUser(userId, status, (page - 1) * size, size), total, page, size);
    }

    public DownloadTask getTask(UUID actorId, boolean admin, UUID taskId) {
        return getOwned(actorId, admin, taskId);
    }

    public List<DownloadTaskFile> getTaskFiles(UUID actorId, boolean admin, UUID taskId) {
        getOwned(actorId, admin, taskId);
        return tasks.findFiles(taskId);
    }

    public Map<Integer, TorrentFileInfo> getResourceFileMap(UUID resourceId) {
        return resources.findFiles(resourceId).stream()
                .collect(Collectors.toMap(TorrentFileInfo::getFileIndex, Function.identity()));
    }

    /** 逻辑暂停：QUEUED|DOWNLOADING → PAUSING；调度器随后收回需求并置 PAUSED（规格 §5.1/§5.3） */
    @Transactional
    public DownloadTask pause(UUID actorId, boolean admin, UUID taskId) {
        DownloadTask task = getOwned(actorId, admin, taskId);
        return switch (task.getStatus()) {
            case QUEUED, DOWNLOADING, STARTING, VERIFYING -> {
                DownloadTask updated = transition(task, TaskStatus.PAUSING);
                events.append(taskId, "STATUS", "{\"status\":\"PAUSING\"}");
                yield updated;
            }
            case PAUSING, PAUSED -> task; // 幂等
            default -> throw new DomainException(ErrorCodes.CONFLICT,
                    "当前状态 " + task.getStatus() + " 不可暂停");
        };
    }

    /** 恢复：PAUSED|CREATED → QUEUED，重新参与需求计算（规格 §4.3 恢复时重新对账数据可用性） */
    @Transactional
    public DownloadTask resume(UUID actorId, boolean admin, UUID taskId) {
        DownloadTask task = getOwned(actorId, admin, taskId);
        return switch (task.getStatus()) {
            case PAUSED, CREATED -> {
                DownloadTask updated = transition(task, TaskStatus.QUEUED);
                events.append(taskId, "STATUS", "{\"status\":\"QUEUED\"}");
                yield updated;
            }
            case QUEUED, STARTING, DOWNLOADING, VERIFYING -> task; // 幂等
            default -> throw new DomainException(ErrorCodes.CONFLICT,
                    "当前状态 " + task.getStatus() + " 不可恢复");
        };
    }

    /** 失败重试：FAILED → RETRY_WAIT，调度器重新入队（规格 §5.1） */
    @Transactional
    public DownloadTask retry(UUID actorId, boolean admin, UUID taskId) {
        DownloadTask task = getOwned(actorId, admin, taskId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new DomainException(ErrorCodes.CONFLICT,
                    "当前状态 " + task.getStatus() + " 不可重试（仅 FAILED）");
        }
        DownloadTask updated = transition(task, TaskStatus.RETRY_WAIT);
        events.append(taskId, "STATUS", "{\"status\":\"RETRY_WAIT\"}");
        return updated;
    }

    /** 修改用户级限速/优先级（规格 §9 PATCH） */
    @Transactional
    public DownloadTask patch(UUID actorId, boolean admin, UUID taskId, Integer priority, Long speedLimit) {
        DownloadTask task = getOwned(actorId, admin, taskId);
        if (task.getStatus().isTerminal()) {
            throw new DomainException(ErrorCodes.CONFLICT, "终态任务不可修改");
        }
        if (priority != null && (priority < 0 || priority > 10)) {
            throw new DomainException(ErrorCodes.VALIDATION, "priority 取值 0-10");
        }
        if (speedLimit != null && speedLimit < 0) {
            throw new DomainException(ErrorCodes.VALIDATION, "speedLimit 不可为负");
        }
        return update(task.withLimits(priority, speedLimit, LocalDateTime.now()));
    }

    /** 删除：ANY → DELETING → DELETED，保留墓碑防异步回写复活（规格 §4.3/§8.1）；重复删除幂等 */
    @Transactional
    public void delete(UUID actorId, boolean admin, UUID taskId) {
        DownloadTask task = tasks.findById(taskId)
                .filter(t -> admin || t.getUserId().equals(actorId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
        if (task.getStatus() == TaskStatus.DELETED) {
            return; // 幂等
        }
        if (task.getStatus() != TaskStatus.DELETING) {
            update(task.withStatus(TaskStatus.DELETING, LocalDateTime.now()));
        }
        DownloadTask current = tasks.findById(taskId).orElseThrow();
        update(current.markDeleted(LocalDateTime.now()));
        events.append(taskId, "STATUS", "{\"status\":\"DELETED\"}");
    }

    // ---------- 调度器协同（TaskDispatcher 调用） ----------

    /** 扫描待调度任务：QUEUED 启动、PAUSING 收需求、RETRY_WAIT 重入队、活跃任务刷进度 */
    @Transactional
    public void dispatchTick(int maxActivePerUser) {
        LocalDateTime now = LocalDateTime.now();
        // 1. RETRY_WAIT → QUEUED
        for (DownloadTask t : tasks.findByStatuses(List.of(TaskStatus.RETRY_WAIT), 100)) {
            transition(t, TaskStatus.QUEUED);
        }
        // 2. PAUSING：需求已在新一轮 setDemand 中排除，直接落 PAUSED
        for (DownloadTask t : tasks.findByStatuses(List.of(TaskStatus.PAUSING), 100)) {
            transition(t, TaskStatus.PAUSED);
            events.append(t.getId(), "STATUS", "{\"status\":\"PAUSED\"}");
        }
        // 3. QUEUED → STARTING → DOWNLOADING：按用户并发配额，需求并集下发引擎
        List<DownloadTask> queued = tasks.findByStatuses(List.of(TaskStatus.QUEUED), 200);
        for (DownloadTask t : queued) {
            long active = tasks.findByUser(t.getUserId(), null, 0, 1000).stream()
                    .filter(x -> x.getStatus() == TaskStatus.STARTING || x.getStatus() == TaskStatus.DOWNLOADING)
                    .count();
            if (active >= maxActivePerUser) {
                continue;
            }
            try {
                ensureEngineSession(t.getResourceId());
                DownloadTask started = transition(t, TaskStatus.STARTING)
                        .withStartedAt(now, now);
                update(started);
                DownloadTask current = tasks.findById(t.getId()).orElseThrow();
                transition(current, TaskStatus.DOWNLOADING);
                events.append(t.getId(), "STATUS", "{\"status\":\"DOWNLOADING\"}");
            } catch (DomainException e) {
                DownloadTask withError = update(t.withError(e.code(), e.getMessage(), now));
                transition(withError, TaskStatus.FAILED);
                events.append(t.getId(), "STATUS",
                        "{\"status\":\"FAILED\",\"errorCode\":\"" + e.code() + "\"}");
            }
        }
        // 4. 每个活跃资源：需求并集下发 + 快照刷新进度
        pushDemandAndRefreshProgress();
    }

    /** 对账完成判定：verified >= selected → VERIFYING → COMPLETED（规格 §5.1） */
    @Transactional
    public void pushDemandAndRefreshProgress() {
        List<DownloadTask> actives = tasks.findByStatuses(
                List.of(TaskStatus.DOWNLOADING, TaskStatus.VERIFYING), 500);
        Map<UUID, List<DownloadTask>> byResource = actives.stream()
                .collect(Collectors.groupingBy(DownloadTask::getResourceId));
        for (Map.Entry<UUID, List<DownloadTask>> entry : byResource.entrySet()) {
            UUID resourceId = entry.getKey();
            EngineSessionRecord session = sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name())
                    .orElse(null);
            if (session == null) {
                continue;
            }
            DownloadEngine engine = libtorrentEngine();
            if (engine == null) {
                continue;
            }
            Set<Integer> demand = demandUnion(resourceId);
            engine.setDemand(session.getId().toString(), demand);
            EngineSnapshot snap = engine.snapshot(session.getId().toString());
            LocalDateTime now = LocalDateTime.now();
            for (DownloadTask t : entry.getValue()) {
                if (t.getStatus() == TaskStatus.DOWNLOADING && t.getSelectedBytes() > 0) {
                    long share = Math.min(t.getSelectedBytes(),
                            snap.verifiedBytes() * t.getSelectedBytes()
                                    / Math.max(1, resourceTotalBytes(resourceId)));
                    if (share != t.getVerifiedBytes()) {
                        update(t.withProgress(share, now));
                    }
                    if (share >= t.getSelectedBytes()) {
                        DownloadTask current = tasks.findById(t.getId()).orElseThrow();
                        transition(current, TaskStatus.VERIFYING);
                    }
                } else if (t.getStatus() == TaskStatus.VERIFYING && snap.metadataReady()) {
                    DownloadTask completed = transition(t, TaskStatus.COMPLETED)
                            .withCompletedAt(now, now);
                    update(completed);
                    events.append(t.getId(), "STATUS", "{\"status\":\"COMPLETED\"}");
                    if (exportService != null) {
                        exportService.materializeCompleted(completed);
                    }
                }
            }
        }
    }

    /** SSE/REST 通用：任务事件补拉（规格 §9.2） */
    public List<com.magnetplatform.domain.task.TaskEvent> eventsAfter(UUID actorId, boolean admin,
                                                                      UUID taskId, long afterSeq, int limit) {
        getOwned(actorId, admin, taskId);
        return events.findAfter(taskId, afterSeq, Math.min(limit, 500));
    }

    // ---------- private ----------

    private DownloadTask getOwned(UUID actorId, boolean admin, UUID taskId) {
        return tasks.findById(taskId)
                .filter(t -> t.getStatus() != TaskStatus.DELETED)
                .filter(t -> admin || t.getUserId().equals(actorId))
                .orElseThrow(() -> new DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
    }

    /** 状态机守卫 + CAS 落库（规格 §5.3/§8.1） */
    private DownloadTask transition(DownloadTask task, TaskStatus target) {
        if (!task.getStatus().canTransitionTo(target)) {
            throw new DomainException(ErrorCodes.CONFLICT,
                    "非法状态迁移 " + task.getStatus() + " → " + target);
        }
        return update(task.withStatus(target, LocalDateTime.now()));
    }

    private DownloadTask update(DownloadTask task) {
        if (!tasks.updateCas(task)) {
            throw new DomainException(ErrorCodes.CONFLICT, "任务版本冲突，请刷新后重试");
        }
        return task;
    }

    private void ensureEngineSession(UUID resourceId) {
        if (sessions.findByResource(resourceId, EngineKind.LIBTORRENT.name()).isPresent()) {
            return;
        }
        DownloadEngine engine = libtorrentEngine();
        if (engine == null) {
            throw new DomainException(ErrorCodes.WORKER_UNAVAILABLE, "下载引擎未接入");
        }
        TorrentResource resource = resources.findById(resourceId)
                .orElseThrow(() -> new DomainException(ErrorCodes.INPUT_LOST,
                        "资源已丢失，无法启动下载"));
        if (!resource.metadataReady() || resource.getMetadataPath() == null) {
            throw new DomainException(ErrorCodes.INPUT_LOST,
                    "资源的 torrent 元数据已丢失，请重新解析或上传");
        }
        byte[] torrentBytes = torrentStorage.loadTorrent(resource.getMetadataPath())
                .orElseThrow(() -> new DomainException(ErrorCodes.INPUT_LOST,
                        "资源的 torrent 元数据已丢失，请重新解析或上传"));
        byte[] resumeBytes = downloadStorage.readResume(resourceId).orElse(null);
        try {
            EngineSession session = engine.createOrAttach(
                    new ResourceDescriptor(resourceId.toString(), resource.getBtih(), resource.getBtmh(),
                            resource.getVersionType().name(), resource.getMetadataPath(), resource.getTrackers(),
                            torrentBytes, downloadStorage.resourceRoot(resource.getId()).toString(), resumeBytes),
                    new EngineOptions(false, "default"));
            sessions.insert(new EngineSessionRecord(resourceId, resourceId, "local-worker-1",
                    EngineKind.LIBTORRENT.name(), session.state().name(), 1L, null, 0L,
                    LocalDateTime.now(), LocalDateTime.now()));
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCodes.WORKER_UNAVAILABLE, "引擎会话创建失败: " + e.getMessage());
        }
    }

    private DownloadEngine libtorrentEngine() {
        return engines.stream().filter(e -> e.kind() == EngineKind.LIBTORRENT).findFirst().orElse(null);
    }

    /** 同一资源所有活跃任务选中文件下标并集（规格 §7 setDemand） */
    private Set<Integer> demandUnion(UUID resourceId) {
        Set<Integer> union = new HashSet<>();
        for (DownloadTask t : tasks.findActiveByResource(resourceId)) {
            tasks.findFiles(t.getId()).stream()
                    .filter(DownloadTaskFile::isSelected)
                    .forEach(f -> union.add(f.getFileIndex()));
        }
        return union;
    }

    private long resourceTotalBytes(UUID resourceId) {
        return resources.findById(resourceId).map(r -> r.getTotalBytes() == null ? 0L : r.getTotalBytes()).orElse(0L);
    }

    private static Set<Integer> validateFileIndices(List<Integer> fileIndices, List<TorrentFileInfo> allFiles) {
        if (fileIndices == null || fileIndices.isEmpty()) {
            throw new DomainException(ErrorCodes.VALIDATION, "fileIndices 不可为空");
        }
        Set<Integer> valid = allFiles.stream().map(TorrentFileInfo::getFileIndex).collect(Collectors.toSet());
        Set<Integer> indices = new HashSet<>(fileIndices);
        List<Integer> bad = indices.stream().filter(i -> !valid.contains(i)).toList();
        if (!bad.isEmpty()) {
            throw new DomainException(ErrorCodes.VALIDATION, "非法文件下标: " + bad);
        }
        return indices;
    }

    /** 防目录穿越：仅允许相对子目录，剔除 .. 与绝对路径（规格 §13） */
    private static String sanitizeSubdirectory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isBlank() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || part.contains(":")) {
                throw new DomainException(ErrorCodes.VALIDATION, "targetSubdirectory 含非法路径片段");
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }
}
