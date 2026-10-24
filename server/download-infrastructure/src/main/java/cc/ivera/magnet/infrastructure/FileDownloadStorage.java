package cc.ivera.magnet.infrastructure;

import cc.ivera.magnet.application.port.DownloadStorage;
import cc.ivera.magnet.domain.common.DomainException;
import cc.ivera.magnet.domain.common.ErrorCodes;
import cc.ivera.magnet.domain.torrent.PathSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Component
public class FileDownloadStorage implements DownloadStorage {

    private final Path root;

    @Autowired
    public FileDownloadStorage(StorageProperties properties) {
        this(properties.root());
    }

    public FileDownloadStorage(String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("存储目录创建失败: " + this.root, e);
        }
    }

    @Override
    public Path resourceRoot(UUID resourceId) {
        return root.resolve("resources").resolve(resourceId.toString());
    }

    @Override
    public Path resumeFile(UUID resourceId) {
        return root.resolve("resume").resolve(resourceId + ".dat");
    }

    @Override
    public Path exportRoot(UUID userId, UUID taskId) {
        return root.resolve("exports").resolve(userId.toString()).resolve(taskId.toString());
    }

    @Override
    public Path resolveResourceFile(UUID resourceId, String relativePath) {
        if (relativePath == null) {
            throw new DomainException(ErrorCodes.INVALID_PATH, "路径分量列表不能为空");
        }
        String sanitized = PathSanitizer.joinRelativePath(
                Arrays.asList(relativePath.replace('\\', '/').split("/", -1)));
        Path resourceRoot = resourceRoot(resourceId);
        Path candidate = resourceRoot.resolve(sanitized).normalize();
        if (!candidate.startsWith(resourceRoot)) {
            throw new DomainException(ErrorCodes.INVALID_PATH, "资源文件路径超出资源目录");
        }
        return candidate;
    }

    @Override
    public void writeResumeAtomically(UUID resourceId, byte[] bytes) {
        Path target = resumeFile(resourceId);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFailure) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("resume 文件写入失败: " + target, e);
        }
    }

    @Override
    public Optional<byte[]> readResume(UUID resourceId) {
        Path target = resumeFile(resourceId);
        if (Files.notExists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new UncheckedIOException("resume 文件读取失败: " + target, e);
        }
    }

    @Override
    public void materialize(Path source, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.size(source) >= 16L * 1024 * 1024) {
                try {
                    Files.deleteIfExists(target);
                    Files.createLink(target, source);
                    return;
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 跨卷或不支持硬链接时回退到临时文件拷贝
                }
            }
            Path temporary = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFailure) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("文件物化失败: " + target, e);
        }
    }

    @Override
    public void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("目录删除失败: " + root, e);
        }
    }
}
