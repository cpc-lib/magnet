package cc.ivera.magnet.spi;

/** 引擎会话状态（规格 §5.2） */
public enum SessionState {
    DISCOVERING,
    METADATA_READY,
    ACTIVE,
    IDLE,
    RECOVERING,
    STOPPED,
    FAILED
}
