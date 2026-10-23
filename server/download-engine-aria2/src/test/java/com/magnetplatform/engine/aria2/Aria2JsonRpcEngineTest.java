package com.magnetplatform.engine.aria2;

import com.magnetplatform.engine.aria2.client.Aria2Client;
import com.magnetplatform.engine.aria2.client.Aria2RpcException;
import com.magnetplatform.spi.EngineCapabilities;
import com.magnetplatform.spi.EngineKind;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.EngineSession;
import com.magnetplatform.spi.EngineSnapshot;
import com.magnetplatform.spi.ResourceDescriptor;
import com.magnetplatform.spi.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Aria2JsonRpcEngineTest {

    private Aria2Client client;
    private Aria2JsonRpcEngine engine;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(Aria2Client.class);
        engine = new Aria2JsonRpcEngine(client);
    }

    @Test
    void failedDownloadIsNotSilentlyRestartedDuringRecovery() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenReturn(Map.of("status", "error"));
        when(client.addUri(any(), any())).thenReturn(gid);
        EngineSession session = engine.createOrAttach(new ResourceDescriptor(id, null, null, "HTTP",
                "https://example.com/file", List.of(), new byte[0], "D:/host/data/" + id, new byte[0], "file.bin"),
                new EngineOptions(false, "default", 1L, Instant.now().plusSeconds(60)));
        verify(client, never()).removeDownloadResult(gid);
        verify(client, never()).addUri(any(), any());
        assertThat(session.state()).isEqualTo(SessionState.FAILED);
    }

    @Test
    void usesContainerDirectoryAndDeclaredFilenameForResumableDownload() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenThrow(new Aria2RpcException(1, "not found"));
        when(client.addUri(any(), any())).thenReturn(gid);
        engine.createOrAttach(new ResourceDescriptor(id, null, null, "HTTP", "https://example.com/raw",
                List.of(), new byte[0], "D:/host/data/resources/" + id, new byte[0], "report.zip"),
                new EngineOptions(false, "default", 1L, Instant.now().plusSeconds(60)));
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(client).addUri(any(), options.capture());
        assertThat(options.getValue()).containsEntry("dir", "/data/resources/" + id)
                .containsEntry("out", "report.zip").containsEntry("continue", "true")
                .containsEntry("auto-file-renaming", "false").containsEntry("pause", "true");
    }

    @Test
    void rpcFailureMustNotCreateOrReportEmptyProgress() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenThrow(new Aria2RpcException("network unavailable", null));
        ResourceDescriptor resource = new ResourceDescriptor(id, null, null, "HTTP",
                "https://example.com/file", List.of(), new byte[0], "D:/host/data/resources/" + id, new byte[0]);
        assertThatThrownBy(() -> engine.createOrAttach(resource,
                new EngineOptions(false, "default", 1L, Instant.now().plusSeconds(60))))
                .isInstanceOf(Aria2RpcException.class);
        verify(client, never()).addUri(any(), any());
        assertThatThrownBy(() -> engine.snapshot(id)).isInstanceOf(Aria2RpcException.class);
    }

    @Test
    void failedPauseMustPropagate() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenReturn(Map.of("status", "active"));
        Mockito.doThrow(new Aria2RpcException("network unavailable", null)).when(client).pause(gid);
        assertThatThrownBy(() -> engine.pauseSession(id)).isInstanceOf(Aria2RpcException.class);
    }

    @Test
    void failedRemovalMustPropagate() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenReturn(Map.of("status", "active"));
        Mockito.doThrow(new Aria2RpcException("network unavailable", null)).when(client).remove(gid);
        assertThatThrownBy(() -> engine.removeSession(id, true)).isInstanceOf(Aria2RpcException.class);
    }

    @Test
    void pauseAndResumeAreIdempotentForPausedAndCompletedDownloads() {
        String id = UUID.randomUUID().toString();
        String gid = id.replace("-", "").substring(0, 16);
        when(client.tellStatus(gid)).thenReturn(Map.of("status", "paused"));
        engine.pauseSession(id);
        verify(client, never()).pause(gid);
        when(client.tellStatus(gid)).thenReturn(Map.of("status", "complete"));
        engine.resumeSession(id);
        verify(client, never()).unpause(gid);
    }

    @Test
    void capabilitiesAndKind() {
        assertThat(engine.kind()).isEqualTo(EngineKind.ARIA2);
        EngineCapabilities caps = engine.capabilities();
        assertThat(caps.http()).isTrue();
        assertThat(caps.bittorrentV1()).isFalse();
        assertThat(caps.bittorrentV2()).isFalse();
        assertThat(caps.resumable()).isTrue();
    }

    @Test
    void createOrAttachCreatesWhenNotExists() {
        UUID resourceId = UUID.randomUUID();
        String gid = resourceId.toString().replace("-", "").substring(0, 16);
        ResourceDescriptor desc = new ResourceDescriptor(resourceId.toString(), null, null, "HTTP",
                "https://example.com/file.zip", List.of(), new byte[0], "/data/resources/" + resourceId, new byte[0], "file.zip");
        EngineOptions opts = new EngineOptions(false, "default", 1L, Instant.now().plusSeconds(60));

        when(client.tellStatus(gid)).thenThrow(new Aria2RpcException(1, "Not found"));
        when(client.addUri(eq(List.of("https://example.com/file.zip")), any())).thenReturn(gid);

        EngineSession session = engine.createOrAttach(desc, opts);
        assertThat(session.engineSessionId()).isEqualTo(resourceId.toString());
        assertThat(session.engineKind()).isEqualTo(EngineKind.ARIA2);
        assertThat(session.state()).isEqualTo(SessionState.IDLE);

        verify(client).addUri(eq(List.of("https://example.com/file.zip")), any());
    }

    @Test
    void snapshotMapsActiveProgress() {
        UUID resourceId = UUID.randomUUID();
        String gid = resourceId.toString().replace("-", "").substring(0, 16);

        when(client.tellStatus(gid)).thenReturn(Map.of(
                "status", "active",
                "completedLength", "500",
                "totalLength", "1000",
                "downloadSpeed", "50"
        ));

        EngineSnapshot snap = engine.snapshot(resourceId.toString());
        assertThat(snap.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(snap.verifiedBytes()).isEqualTo(500L);
        assertThat(snap.fileVerifiedBytes()).containsExactly(500L);
        assertThat(snap.downloadRateBytesPerSec()).isEqualTo(50L);
        assertThat(snap.metadataReady()).isTrue();
    }

    @Test
    void pauseAndResumeDelegates() {
        UUID resourceId = UUID.randomUUID();
        String gid = resourceId.toString().replace("-", "").substring(0, 16);

        when(client.tellStatus(gid)).thenReturn(Map.of("status", "active"), Map.of("status", "paused"));
        engine.pauseSession(resourceId.toString());
        verify(client).pause(gid);

        engine.resumeSession(resourceId.toString());
        verify(client).unpause(gid);
    }

    @Test
    void removeSessionCleansUp() {
        UUID resourceId = UUID.randomUUID();
        String gid = resourceId.toString().replace("-", "").substring(0, 16);

        when(client.tellStatus(gid)).thenReturn(Map.of("status", "active"), Map.of("status", "removed"));
        engine.removeSession(resourceId.toString(), true);
        verify(client).remove(gid);
        verify(client).removeDownloadResult(gid);
    }
}
