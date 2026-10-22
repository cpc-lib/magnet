package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.DownloadStorage;
import com.magnetplatform.domain.common.DomainException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDownloadStorageTest {

    private static final UUID RESOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TASK_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void storagePathsStayInsideConfiguredRoot() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());

        assertEquals(root.resolve("resources/" + RESOURCE_ID), storage.resourceRoot(RESOURCE_ID));
        assertEquals(root.resolve("resume/" + RESOURCE_ID + ".dat"), storage.resumeFile(RESOURCE_ID));
        assertEquals(root.resolve("exports/" + USER_ID + "/" + TASK_ID),
                storage.exportRoot(USER_ID, TASK_ID));
        assertEquals(root.resolve("resources/" + RESOURCE_ID + "/dir/file.bin"),
                storage.resolveResourceFile(RESOURCE_ID, "dir/file.bin"));
        assertThrows(DomainException.class,
                () -> storage.resolveResourceFile(RESOURCE_ID, "../outside"));
        assertThrows(DomainException.class,
                () -> storage.resolveResourceFile(RESOURCE_ID, null));
    }

    @Test
    void resumeBytesCanBeWrittenAndRead() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());
        byte[] bytes = {1, 2, 3};

        assertTrue(storage.readResume(RESOURCE_ID).isEmpty());
        storage.writeResumeAtomically(RESOURCE_ID, bytes);

        assertArrayEquals(bytes, storage.readResume(RESOURCE_ID).orElseThrow());
    }

    @Test
    void readResumeThrowsWhenExistingTargetCannotBeRead() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());
        Files.createDirectories(storage.resumeFile(RESOURCE_ID));

        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> storage.readResume(RESOURCE_ID));
        assertTrue(error.getMessage().contains("resume 文件读取失败"));
    }

    @Test
    void resumeWriteReplacesExistingTargetAndLeavesNoTemporaryFile() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());
        Path target = storage.resumeFile(RESOURCE_ID);

        storage.writeResumeAtomically(RESOURCE_ID, new byte[]{1});
        storage.writeResumeAtomically(RESOURCE_ID, new byte[]{2, 3});

        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(target));
        try (var paths = Files.list(target.getParent())) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                    .startsWith("." + target.getFileName() + "-")));
        }
    }

    @Test
    void resumeWriteCleansTemporaryFileWhenTargetCannotBeReplaced() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());
        Path target = storage.resumeFile(RESOURCE_ID);
        Files.createDirectories(target);
        Files.write(target.resolve("keep"), new byte[]{9});

        assertThrows(UncheckedIOException.class,
                () -> storage.writeResumeAtomically(RESOURCE_ID, new byte[]{1}));
        try (var paths = Files.list(target.getParent())) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                    .startsWith("." + target.getFileName() + "-")));
        }
    }

    @Test
    void materializeCopiesFileAndDeleteTreeRemovesDirectory() throws IOException {
        Path root = Files.createTempDirectory("magnet-storage-");
        DownloadStorage storage = new FileDownloadStorage(root.toString());
        Path source = Files.createTempFile("magnet-source-", ".dat");
        Files.write(source, new byte[]{4, 5, 6});
        Path target = storage.exportRoot(USER_ID, TASK_ID).resolve("file.bin");

        storage.materialize(source, target);
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(target));

        storage.deleteTree(target.getParent());
        assertFalse(Files.exists(target.getParent()));
    }
}
