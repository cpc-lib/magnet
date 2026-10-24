package cc.ivera.magnet.api.controller;

import cc.ivera.magnet.api.dto.Dtos.CreateTaskRequest;
import cc.ivera.magnet.api.dto.Dtos.PageView;
import cc.ivera.magnet.api.dto.Dtos.PatchTaskRequest;
import cc.ivera.magnet.api.dto.Dtos.TaskEventView;
import cc.ivera.magnet.api.dto.Dtos.TaskFileView;
import cc.ivera.magnet.api.dto.Dtos.TaskView;
import cc.ivera.magnet.application.ExportService;
import cc.ivera.magnet.application.MonitoringMetrics;
import cc.ivera.magnet.application.JwtPrincipal;
import cc.ivera.magnet.application.Page;
import cc.ivera.magnet.application.TaskService;
import cc.ivera.magnet.domain.common.DomainException;
import cc.ivera.magnet.domain.common.ErrorCodes;
import cc.ivera.magnet.domain.resource.TorrentFileInfo;
import cc.ivera.magnet.domain.task.DownloadTask;
import cc.ivera.magnet.domain.task.TaskStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 下载任务（规格 §4.3/§5/§9）：创建、查询、暂停/恢复/重试、限速、删除、文件进度、SSE 事件 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final ExportService exportService;
    private final MonitoringMetrics metrics;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public TaskController(TaskService taskService, ExportService exportService, MonitoringMetrics metrics) {
        this.taskService = taskService;
        this.exportService = exportService;
        this.metrics = metrics;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(Authentication auth, @Valid @RequestBody CreateTaskRequest request) {
        return TaskView.from(taskService.create(principal(auth).userId(), request.parseId(),
                request.fileIndices(),
                request.targetSubdirectory() == null ? "" : request.targetSubdirectory(),
                request.startImmediately() == null || request.startImmediately(),
                request.priority()));
    }

    @GetMapping
    public PageView<TaskView> list(Authentication auth,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "30") int size,
                                   @RequestParam(required = false) String status) {
        Page<DownloadTask> result = taskService.listTasks(principal(auth).userId(), taskStatus(status), page, size);
        return PageView.from(result, TaskView::from);
    }

    @GetMapping("/{id}")
    public TaskView get(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return TaskView.from(taskService.getTask(caller.userId(), caller.admin(), id));
    }

    @PostMapping("/{id}/pause")
    public TaskView pause(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return TaskView.from(taskService.pause(caller.userId(), caller.admin(), id));
    }

    @PostMapping("/{id}/resume")
    public TaskView resume(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return TaskView.from(taskService.resume(caller.userId(), caller.admin(), id));
    }

    @PostMapping("/{id}/retry")
    public TaskView retry(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        return TaskView.from(taskService.retry(caller.userId(), caller.admin(), id));
    }

    @PatchMapping("/{id}")
    public TaskView patch(Authentication auth, @PathVariable UUID id,
                          @RequestBody PatchTaskRequest request) {
        JwtPrincipal caller = principal(auth);
        return TaskView.from(taskService.patch(caller.userId(), caller.admin(), id,
                request.priority(), request.speedLimit()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean removeOwnExport) {
        JwtPrincipal caller = principal(auth);
        exportService.deleteOwned(caller.userId(), caller.admin(), id, removeOwnExport);
        taskService.delete(caller.userId(), caller.admin(), id);
    }

    @GetMapping("/{id}/files")
    public List<TaskFileView> files(Authentication auth, @PathVariable UUID id) {
        JwtPrincipal caller = principal(auth);
        DownloadTask task = taskService.getTask(caller.userId(), caller.admin(), id);
        var resourceFiles = taskService.getResourceFileMap(task.getResourceId());
        return taskService.getTaskFiles(caller.userId(), caller.admin(), id).stream()
                .map(f -> TaskFileView.from(f, resourceFiles.get(f.getFileIndex())))
                .toList();
    }

    /**
     * SSE 事件流（规格 §9.2）：先补拉 Last-Event-ID 之后的持久化事件，
     * 再每 2 秒聚合推送当前快照（含进度/速度估算字段位）。
     */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication auth, @PathVariable UUID id,
                             @RequestParam(defaultValue = "0") long afterSeq) {
        JwtPrincipal caller = principal(auth);
        SseEmitter emitter = new SseEmitter(0L);
        metrics.incrementSseConnections();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable closeAction = () -> {
            if (closed.compareAndSet(false, true)) {
                metrics.decrementSseConnections();
            }
        };
        emitter.onCompletion(closeAction);
        emitter.onTimeout(() -> {
            metrics.recordSseDisconnect("timeout");
            closeAction.run();
        });
        emitter.onError(t -> {
            metrics.recordSseDisconnect("error");
            closeAction.run();
        });

        sseExecutor.submit(() -> {
            long cursor = afterSeq;
            try {
                while (!closed.get()) {
                    List<TaskEventView> batch = taskService
                            .eventsAfter(caller.userId(), caller.admin(), id, cursor, 200).stream()
                            .map(TaskEventView::from).toList();
                    for (TaskEventView e : batch) {
                        if (closed.get()) {
                            return;
                        }
                        emitter.send(SseEmitter.event().id(String.valueOf(e.seq()))
                                .name(e.eventType()).data(e.payloadJson() == null ? "{}" : e.payloadJson()));
                        cursor = e.seq();
                    }
                    if (closed.get()) {
                        return;
                    }
                    TaskView snapshot = TaskView.from(taskService.getTask(caller.userId(), caller.admin(), id));
                    emitter.send(SseEmitter.event().name("SNAPSHOT").data(snapshot));
                    if (snapshot.status().equals("COMPLETED") || snapshot.status().equals("FAILED")
                            || snapshot.status().equals("DELETED")) {
                        closeAction.run();
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(2000);
                }
            } catch (IOException e) {
                // send 失败后的错误派发由 Servlet 容器负责，不能再调用 complete 竞争完成流。
                metrics.recordSseDisconnect("client_disconnect");
                closeAction.run();
            } catch (DomainException | IllegalStateException e) {
                // 客户端切页、关闭浏览器或断开连接属于正常生命周期事件，安全结束无需抛异常
                closeAction.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeAction.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                metrics.recordSseDisconnect("error");
                closeAction.run();
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                closeAction.run();
            }
        });
        return emitter;
    }

    static JwtPrincipal principal(Authentication auth) {
        return (JwtPrincipal) auth.getPrincipal();
    }

    private static TaskStatus taskStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCodes.VALIDATION, "未知任务状态: " + status);
        }
    }
}
