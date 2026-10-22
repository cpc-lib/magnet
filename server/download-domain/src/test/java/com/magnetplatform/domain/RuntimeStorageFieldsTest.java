package com.magnetplatform.domain;

import com.magnetplatform.domain.resource.TorrentResource;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.task.EngineSessionRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeStorageFieldsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void oldConstructorsLeaveRuntimePathsNull() {
        TorrentResource resource = new TorrentResource(
                UUID.randomUUID(), "btih:test", new byte[]{1}, null, VersionType.V1,
                "name", 16384L, 1L, false, "MAGNET", List.of(), null, NOW);
        EngineSessionRecord session = new EngineSessionRecord(
                UUID.randomUUID(), resource.getId(), "worker", "LIBTORRENT", "PENDING",
                1L, null, 0L, NOW, NOW);

        assertNull(resource.getMetadataPath());
        assertNull(session.getResumePath());
    }

    @Test
    void newConstructorsStoreRuntimePaths() {
        UUID resourceId = UUID.randomUUID();
        TorrentResource resource = new TorrentResource(
                resourceId, "btih:test", new byte[]{1}, null, VersionType.V1,
                "name", 16384L, 1L, false, "MAGNET", List.of(), null, NOW,
                "resources/metadata.torrent");
        EngineSessionRecord session = new EngineSessionRecord(
                UUID.randomUUID(), resourceId, "worker", "LIBTORRENT", "PENDING",
                1L, null, 0L, NOW, NOW, "resume/session.dat");

        assertEquals("resources/metadata.torrent", resource.getMetadataPath());
        assertEquals("resume/session.dat", session.getResumePath());
    }
}
