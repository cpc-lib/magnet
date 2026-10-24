package cc.ivera.magnet.domain.task;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 用户任务状态机（规格 §5.1）：
 * CREATED → QUEUED → STARTING → DOWNLOADING → VERIFYING → COMPLETED
 * QUEUED|DOWNLOADING → PAUSING → PAUSED → QUEUED
 * DOWNLOADING|STARTING → RETRY_WAIT → QUEUED
 * ANY_NON_TERMINAL → FAILED；ANY → DELETING → DELETED
 * COMPLETED 完整性失败 → REPAIRING → QUEUED
 */
public enum TaskStatus {
    CREATED, QUEUED, STARTING, DOWNLOADING, VERIFYING, COMPLETED,
    PAUSING, PAUSED, RETRY_WAIT, REPAIRING, FAILED, DELETING, DELETED;

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(QUEUED, DELETING)),
            Map.entry(QUEUED, EnumSet.of(STARTING, PAUSING, FAILED, DELETING)),
            Map.entry(STARTING, EnumSet.of(DOWNLOADING, RETRY_WAIT, FAILED, DELETING)),
            Map.entry(DOWNLOADING, EnumSet.of(VERIFYING, PAUSING, RETRY_WAIT, FAILED, DELETING)),
            Map.entry(VERIFYING, EnumSet.of(COMPLETED, FAILED, DELETING)),
            Map.entry(COMPLETED, EnumSet.of(REPAIRING, DELETING)),
            Map.entry(PAUSING, EnumSet.of(PAUSED, FAILED, DELETING)),
            Map.entry(PAUSED, EnumSet.of(QUEUED, DELETING)),
            Map.entry(RETRY_WAIT, EnumSet.of(QUEUED, FAILED, DELETING)),
            Map.entry(REPAIRING, EnumSet.of(QUEUED, FAILED, DELETING)),
            Map.entry(FAILED, EnumSet.of(RETRY_WAIT, DELETING)),
            Map.entry(DELETING, EnumSet.of(DELETED)),
            Map.entry(DELETED, EnumSet.noneOf(TaskStatus.class))
    );

    public boolean canTransitionTo(TaskStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** 非终态（可转 FAILED 的状态；规格 §5.1 ANY_NON_TERMINAL → FAILED） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == DELETED;
    }

    /** 该状态下任务是否向共享会话贡献需求（暂停/终结不贡献，规格 §5.3） */
    public boolean contributesDemand() {
        return this == QUEUED || this == STARTING || this == DOWNLOADING || this == VERIFYING
                || this == RETRY_WAIT || this == REPAIRING;
    }
}
