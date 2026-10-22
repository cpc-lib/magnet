package com.magnetplatform.spi;

/** 引擎类型：业务 taskId 与引擎句柄严格分离（ADR-005） */
public enum EngineKind {
    LIBTORRENT,
    ARIA2
}
