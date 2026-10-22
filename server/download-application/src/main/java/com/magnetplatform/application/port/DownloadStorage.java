package com.magnetplatform.application.port;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface DownloadStorage {

    Path resourceRoot(UUID resourceId);

    Path resumeFile(UUID resourceId);

    Path exportRoot(UUID userId, UUID taskId);

    Path resolveResourceFile(UUID resourceId, String relativePath);

    void writeResumeAtomically(UUID resourceId, byte[] bytes);

    Optional<byte[]> readResume(UUID resourceId);

    void materialize(Path source, Path target);

    void deleteTree(Path root);
}
