package cc.ivera.magnet.infrastructure.mapper;

import cc.ivera.magnet.domain.resource.TorrentFileInfo;
import cc.ivera.magnet.domain.resource.TorrentResource;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface TorrentResourceMapper {

    TorrentResource findById(@Param("id") UUID id);

    TorrentResource findByHash(@Param("hashType") String hashType, @Param("hashBytes") byte[] hashBytes);

    void insert(TorrentResource resource);

    void insertAlias(@Param("resourceId") UUID resourceId,
                     @Param("hashType") String hashType,
                     @Param("hashBytes") byte[] hashBytes);

    void update(TorrentResource resource);

    void insertFile(@Param("id") UUID id,
                    @Param("resourceId") UUID resourceId,
                    @Param("file") TorrentFileInfo file);

    List<TorrentFileInfo> findFiles(@Param("resourceId") UUID resourceId);
}
