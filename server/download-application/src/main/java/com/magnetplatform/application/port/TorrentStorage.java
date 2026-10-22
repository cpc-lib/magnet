package com.magnetplatform.application.port;

import java.util.Optional;

public interface TorrentStorage {

    /** 保存上传的 torrent 字节，返回可持久化的相对路径 */
    String saveTorrent(byte[] bytes);

    /** 按相对路径读取；文件丢失返回 empty */
    Optional<byte[]> loadTorrent(String relativePath);
}
