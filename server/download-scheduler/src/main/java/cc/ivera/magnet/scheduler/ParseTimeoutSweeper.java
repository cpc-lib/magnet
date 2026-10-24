package cc.ivera.magnet.scheduler;

import cc.ivera.magnet.application.ParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 解析超时清扫：RESOLVING 超过 magnet.parse.timeout-seconds 的任务置为 TIMEOUT（规格 §4.2-2） */
@Component
public class ParseTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(ParseTimeoutSweeper.class);

    private final ParseService parseService;

    public ParseTimeoutSweeper(ParseService parseService) {
        this.parseService = parseService;
    }

    @Scheduled(fixedDelayString = "${magnet.parse.sweep-delay-ms:5000}")
    public void sweep() {
        int completed = parseService.completeResolvedMetadata();
        if (completed > 0) {
            log.info("元数据回填：{} 个 RESOLVING 任务置为 READY", completed);
        }
        int swept = parseService.sweepTimeouts();
        if (swept > 0) {
            log.info("解析超时清扫：{} 个 RESOLVING 任务置为 TIMEOUT", swept);
        }
    }
}
