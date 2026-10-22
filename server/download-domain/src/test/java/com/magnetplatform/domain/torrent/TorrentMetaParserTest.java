package com.magnetplatform.domain.torrent;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.resource.VersionType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentMetaParserTest {

    private final TorrentMetaParser parser = new TorrentMetaParser();

    @Test
    void parsesV1MultiFile() {
        ParsedTorrent parsed = parser.parse(TestTorrents.v1());
        assertEquals(VersionType.V1, parsed.versionType());
        assertEquals(20, parsed.infoHashV1().length);
        assertNull(parsed.infoHashV2());
        assertEquals("v1-demo", parsed.name());
        assertEquals(16384L, parsed.pieceLength());
        assertEquals(40000L, parsed.totalBytes());
        assertEquals(2, parsed.files().size());
        assertEquals("dir/a.bin", parsed.files().get(0).relativePath());
        assertEquals(30000L, parsed.files().get(0).sizeBytes());
        assertEquals(1, parsed.trackers().size());
    }

    @Test
    void parsesV1SingleFile() {
        ParsedTorrent parsed = parser.parse(TestTorrents.v1Single());
        assertEquals(VersionType.V1, parsed.versionType());
        assertEquals(1, parsed.files().size());
        assertEquals("single.iso", parsed.files().get(0).relativePath());
    }

    @Test
    void parsesV2FileTree() {
        ParsedTorrent parsed = parser.parse(TestTorrents.v2());
        assertEquals(VersionType.V2, parsed.versionType());
        assertNull(parsed.infoHashV1());
        assertEquals(32, parsed.infoHashV2().length);
        assertEquals(2, parsed.files().size());
        assertNotNull(parsed.files().get(0).v2PiecesRoot());
        assertEquals(32, parsed.files().get(0).v2PiecesRoot().length);
        assertEquals(40000L, parsed.totalBytes());
    }

    @Test
    void parsesHybridAndCrossChecks() {
        ParsedTorrent parsed = parser.parse(TestTorrents.hybrid());
        assertEquals(VersionType.HYBRID, parsed.versionType());
        assertEquals(20, parsed.infoHashV1().length);
        assertEquals(32, parsed.infoHashV2().length);
        // 文件清单不含 .pad
        assertEquals(2, parsed.files().size());
        assertTrue(parsed.files().stream().noneMatch(f -> f.relativePath().startsWith(".pad/")));
        assertEquals(40000L, parsed.totalBytes());
    }

    @Test
    void rejectsZeroPieceLength() {
        Map<String, Object> info = Map.of(
                "name", "bad", "piece length", 0L, "length", 10L, "pieces", new byte[20]);
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        DomainException e = assertThrows(DomainException.class, () -> parser.parse(bytes));
        assertEquals(ErrorCodes.INVALID_TORRENT, e.code());
    }

    @Test
    void rejectsPathTraversal() {
        Map<String, Object> file = Map.of("length", 10000L, "path", List.of("..", "evil.bin"));
        Map<String, Object> file2 = Map.of("length", 30000L, "path", List.of("ok.bin"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "x");
        info.put("piece length", 16384L);
        info.put("pieces", new byte[60]);
        info.put("files", List.of(file, file2));
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        DomainException e = assertThrows(DomainException.class, () -> parser.parse(bytes));
        assertEquals(ErrorCodes.INVALID_PATH, e.code());
    }

    @Test
    void rejectsPiecesSizeMismatch() {
        Map<String, Object> info = Map.of(
                "name", "x", "piece length", 16384L, "length", 100L, "pieces", new byte[40]);
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsPiecesNotMultipleOf20() {
        Map<String, Object> info = Map.of(
                "name", "x", "piece length", 16384L, "length", 100L, "pieces", new byte[21]);
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsMissingInfo() {
        byte[] bytes = BencodeBuilder.encode(Map.of("announce", "https://t.example"));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsV2MissingPiecesRoot() {
        byte[] bytes = TestTorrents.v2WithRoots(
                Map.of("length", 30000L),
                Map.of("length", 10000L, "pieces root", new byte[32]));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsHybridFileMismatch() {
        // V1 文件大小与 V2 不一致
        Map<String, Object> fileA = Map.of("length", 30001L, "path", List.of("dir", "a.bin"));
        Map<String, Object> fileB = Map.of("length", 10000L, "path", List.of("b.bin"));
        Map<String, Object> dir = Map.of("a.bin", Map.of("", Map.of("length", 30000L, "pieces root", new byte[32])));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("dir", dir);
        tree.put("b.bin", Map.of("", Map.of("length", 10000L, "pieces root", new byte[32])));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "bad-hybrid");
        info.put("meta version", 2L);
        info.put("piece length", 16384L);
        info.put("pieces", new byte[60]);
        info.put("files", List.of(fileA, fileB));
        info.put("file tree", tree);
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }

    @Test
    void rejectsGarbageBytes() {
        assertThrows(DomainException.class, () -> parser.parse("not a torrent".getBytes()));
        assertThrows(DomainException.class, () -> parser.parse(new byte[0]));
    }

    @Test
    void rejectsWindowsReservedName() {
        Map<String, Object> file = Map.of("length", 10000L, "path", List.of("CON"));
        Map<String, Object> file2 = Map.of("length", 30000L, "path", List.of("ok.bin"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "x");
        info.put("piece length", 16384L);
        info.put("pieces", new byte[60]);
        info.put("files", List.of(file, file2));
        byte[] bytes = BencodeBuilder.encode(Map.of("info", info));
        assertThrows(DomainException.class, () -> parser.parse(bytes));
    }
}
