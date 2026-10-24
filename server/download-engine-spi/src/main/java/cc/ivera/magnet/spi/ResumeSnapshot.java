package cc.ivera.magnet.spi;

/** 恢复快照：恢复提示而非授权真相或已验证数据的永久凭证（规格 §7.1） */
public record ResumeSnapshot(String engineSessionId,
                             long resumeVersion,
                             byte[] data) {
}
