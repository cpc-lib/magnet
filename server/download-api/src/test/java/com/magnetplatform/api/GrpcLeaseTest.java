package com.magnetplatform.api;

import com.magnetplatform.engine.libtorrent.GrpcLibtorrentEngine;
import com.magnetplatform.spi.EngineOptions;
import com.magnetplatform.spi.ResourceDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import magnetplatform.engine.v1.DownloadEngineGrpc;
import magnetplatform.engine.v1.DownloadEngineOuterClass.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GrpcLeaseTest {
    private Server server;
    private GrpcLibtorrentEngine engine;
    private final String rid = UUID.randomUUID().toString();
    private boolean accept = true;
    private CommandEnvelope received;

    @BeforeEach
    void start() throws Exception {
        server = ServerBuilder.forPort(0).addService(new DownloadEngineGrpc.DownloadEngineImplBase() {
            @Override
            public void addTorrent(AddTorrentRequest request, StreamObserver<AddTorrentResponse> response) {
                received = request.getEnvelope();
                response.onNext(AddTorrentResponse.newBuilder().setAccepted(accept).setError("fenced").build());
                response.onCompleted();
            }

            @Override
            public void removeTorrent(RemoveTorrentRequest request, StreamObserver<CommandResponse> response) {
                received = request.getEnvelope();
                response.onNext(CommandResponse.newBuilder().setAccepted(true).build());
                response.onCompleted();
            }
        }).build().start();
        engine = new GrpcLibtorrentEngine("localhost:" + server.getPort(), true);
    }

    @AfterEach
    void stop() throws Exception {
        engine.shutdown();
        server.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void attach(Instant until) {
        engine.createOrAttach(new ResourceDescriptor(rid, null, null, "V1", null,
                List.of(), new byte[]{1}, "resources/" + rid, new byte[0]),
                new EngineOptions(false, "default", 7L, until));
    }

    @Test
    void rejectedAttachDoesNotPretendSessionExists() {
        accept = false;
        assertThrows(IllegalStateException.class, () -> attach(Instant.now().plusSeconds(30)));
        assertThrows(IllegalStateException.class, () -> engine.snapshot(rid));
    }

    @Test
    void removeKeepsEpochAndLeaseUntilWorkerAcknowledges() {
        Instant until = Instant.now().plusSeconds(20);
        attach(until);
        engine.removeSession(rid, true);
        assertEquals(7L, received.getOwnerEpoch());
        assertEquals(until.getEpochSecond(), received.getLeaseUntil().getSeconds());
        assertTrue(received.getDeadline().getSeconds() <= until.getEpochSecond());
        assertThrows(IllegalStateException.class, () -> engine.removeSession(rid, true));
    }

    @Test
    void unattachedMetadataIsPendingAndRpcFailureIsNotAnEmptySnapshot() {
        assertArrayEquals(new byte[0], engine.fetchMetadata(rid));
        attach(Instant.now().plusSeconds(30));
        assertThrows(IllegalStateException.class, () -> engine.snapshot(rid));
        assertThrows(IllegalStateException.class, () -> engine.saveResume(rid));
    }
}
