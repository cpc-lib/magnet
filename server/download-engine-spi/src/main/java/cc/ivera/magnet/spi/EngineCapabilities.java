package cc.ivera.magnet.spi;

/** 引擎能力声明（规格 §7） */
public record EngineCapabilities(boolean bittorrentV1,
                                 boolean bittorrentV2,
                                 boolean hybrid,
                                 boolean http,
                                 boolean selectiveFiles,
                                 boolean resumable) {
}
