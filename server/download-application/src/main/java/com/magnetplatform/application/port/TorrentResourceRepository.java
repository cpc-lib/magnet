package com.magnetplatform.application.port;

import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TorrentResourceRepository {

    Optional<TorrentResource> findById(UUID id);

    /** 通过 hash 别名表查找资源（hashType: btih | btmh） */
    Optional<TorrentResource> findByHash(String hashType, byte[] hashBytes);

    void insert(TorrentResource resource);

    void insertAlias(UUID resourceId, String hashType, byte[] hashBytes);

    /** 元数据补全 / Hybrid 升级 */
    void update(TorrentResource resource);

    void insertFiles(UUID resourceId, List<TorrentFileInfo> files);

    List<TorrentFileInfo> findFiles(UUID resourceId);
}
