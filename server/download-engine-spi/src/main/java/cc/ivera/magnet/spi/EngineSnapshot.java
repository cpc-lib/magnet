package cc.ivera.magnet.spi;

import java.util.List;

/** 低开销会话快照（规格 §11：总进度刷新用低开销快照）。
 * fileVerifiedBytes 为每个 torrent 文件已通过 piece 校验的字节数，
 * 用于按用户选中文件精确计算进度（规格 §4.3），而非按资源总量比例估算。 */
public record EngineSnapshot(String engineSessionId,
                             SessionState state,
                             boolean metadataReady,
                             long verifiedBytes,
                             long downloadRateBytesPerSec,
                             long uploadRateBytesPerSec,
                             int peerCount,
                             List<Long> fileVerifiedBytes) {

    public static EngineSnapshot empty(String engineSessionId) {
        return new EngineSnapshot(engineSessionId, SessionState.DISCOVERING, false,
                0L, 0L, 0L, 0, List.of());
    }
}
