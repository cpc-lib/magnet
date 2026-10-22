package com.magnetplatform.spi;

/** 低开销会话快照（规格 §11：总进度刷新用低开销快照） */
public record EngineSnapshot(String engineSessionId,
                             SessionState state,
                             boolean metadataReady,
                             long verifiedBytes,
                             long downloadRateBytesPerSec,
                             long uploadRateBytesPerSec,
                             int peerCount) {

    public static EngineSnapshot empty(String engineSessionId) {
        return new EngineSnapshot(engineSessionId, SessionState.DISCOVERING, false, 0L, 0L, 0L, 0);
    }
}
