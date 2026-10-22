package com.magnetplatform.domain.parse;

/** 解析任务状态机（规格 §4.2-2） */
public enum ParseStatus {
    PENDING,
    RESOLVING,
    READY,
    FAILED,
    TIMEOUT
}
