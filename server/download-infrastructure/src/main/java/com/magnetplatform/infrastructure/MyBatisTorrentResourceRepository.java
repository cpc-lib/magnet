package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.TorrentResourceRepository;
import com.magnetplatform.domain.resource.TorrentFileInfo;
import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.infrastructure.mapper.TorrentResourceMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisTorrentResourceRepository implements TorrentResourceRepository {

    private final TorrentResourceMapper mapper;

    public MyBatisTorrentResourceRepository(TorrentResourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TorrentResource> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    @Override
    public Optional<TorrentResource> findByHash(String hashType, byte[] hashBytes) {
        return Optional.ofNullable(mapper.findByHash(hashType, hashBytes));
    }

    @Override
    public void insert(TorrentResource resource) {
        mapper.insert(resource);
    }

    @Override
    public void insertAlias(UUID resourceId, String hashType, byte[] hashBytes) {
        mapper.insertAlias(resourceId, hashType, hashBytes);
    }

    @Override
    public void update(TorrentResource resource) {
        mapper.update(resource);
    }

    @Override
    public void insertFiles(UUID resourceId, List<TorrentFileInfo> files) {
        for (TorrentFileInfo file : files) {
            mapper.insertFile(UUID.randomUUID(), resourceId, file);
        }
    }

    @Override
    public List<TorrentFileInfo> findFiles(UUID resourceId) {
        return mapper.findFiles(resourceId);
    }
}
