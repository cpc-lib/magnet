package com.magnetplatform.scheduler;

import com.magnetplatform.application.TaskService;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务调度器（规格 §11）：周期推进任务状态机 —— 启动排队任务（用户并发配额）、
 * 需求并集下发引擎、快照刷新进度、完成对账。真实引擎未接入时任务停在 DOWNLOADING，
 * 属预期行为（同解析 RESOLVING 由超时清扫收尾的模式）。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskService taskService;
    private final int maxActivePerUser;

    public TaskDispatcher(TaskService taskService,
                          @Value("${magnet.task.max-active-per-user:3}") int maxActivePerUser) {
        this.taskService = taskService;
        this.maxActivePerUser = maxActivePerUser;
    }

    @Timed(value = "magnet_scheduler_tick_duration_seconds", histogram = true)
    @Counted(value = "magnet_scheduler_ticks_total")
    @Scheduled(fixedDelayString = "${magnet.task.dispatch-delay-ms:2000}")
    public void tick() {
        try {
            taskService.dispatchTick(maxActivePerUser);
        } catch (RuntimeException e) {
            log.warn("任务调度 tick 异常: {}", e.getMessage());
        }
    }
}
