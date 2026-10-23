package com.magnetplatform.scheduler;

import com.magnetplatform.application.ReferenceGcService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 共享数据引用 GC 调度（规格 §10.2）：
 * 定期标记无引用资源为回收候选，宽限期后复核并删除物理数据。
 */
@Component
public class GcSweeper {

    private final ReferenceGcService gcService;

    public GcSweeper(ReferenceGcService gcService) {
        this.gcService = gcService;
    }

    @Scheduled(fixedDelayString = "${magnet.gc.sweep-delay-ms:60000}")
    public void sweep() {
        gcService.markCandidates();
        gcService.sweep();
    }
}
