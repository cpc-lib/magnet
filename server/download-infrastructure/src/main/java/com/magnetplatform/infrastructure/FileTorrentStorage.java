package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.TorrentStorage;
import com.magnetplatform.domain.common.Digests;
import com.magnetplatform.domain.common.Hex;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** storageRoot 下的上传 torrent 字节存储：uploads/{sha256hex}.torrent，写临时文件后原子改名 */
@Component
public class FileTorrentStorage implements TorrentStorage {

    private final Path root;

    public FileTorrentStorage(StorageProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("uploads"));
        } catch (IOException e) {
            throw new UncheckedIOException("存储目录创建失败: " + root, e);
        }
    }

    @Override
    public String saveTorrent(byte[] bytes) {
        String relative = "uploads/" + Hex.encode(Digests.sha256(bytes)) + ".torrent";
        Path target = root.resolve(relative);
        try {
            Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return relative;
        } catch (IOException e) {
            throw new UncheckedIOException("torrent 落盘失败", e);
        }
    }

    @Override
    public Optional<byte[]> loadTorrent(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
