package com.magnetplatform.domain.torrent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造 V1 / V2 / Hybrid 测试种子字节 */
final class TestTorrents {

    private TestTorrents() {
    }

    /** 标准 V1 多文件：piece length 16KiB，两个文件共 40000 字节 → 3 个 piece */
    static byte[] v1() {
        Map<String, Object> fileA = Map.of("length", 30000L, "path", List.of("dir", "a.bin"));
        Map<String, Object> fileB = Map.of("length", 10000L, "path", List.of("b.bin"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "v1-demo");
        info.put("piece length", 16384L);
        info.put("pieces", new byte[60]);
        info.put("files", List.of(fileA, fileB));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("announce", "https://tracker.example/announce");
        root.put("info", info);
        return BencodeBuilder.encode(root);
    }

    /** V2：file tree 两个文件，各带 32 字节 pieces root */
    static byte[] v2() {
        return v2WithRoots(Map.of("length", 30000L, "pieces root", new byte[32]),
                Map.of("length", 10000L, "pieces root", new byte[32]));
    }

    static byte[] v2WithRoots(Map<String, Object> leafA, Map<String, Object> leafB) {
        Map<String, Object> dir = Map.of("a.bin", Map.of("", leafA));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("dir", dir);
        tree.put("b.bin", Map.of("", leafB));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "v2-demo");
        info.put("meta version", 2L);
        info.put("piece length", 16384L);
        info.put("file tree", tree);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("announce", "udp://tracker.example:6969/announce");
        root.put("info", info);
        return BencodeBuilder.encode(root);
    }

    /** Hybrid：V1 侧带 .pad 文件，非 pad 文件与 V2 一致 */
    static byte[] hybrid() {
        Map<String, Object> fileA = Map.of("length", 30000L, "path", List.of("dir", "a.bin"));
        Map<String, Object> fileB = Map.of("length", 10000L, "path", List.of("b.bin"));
        Map<String, Object> pad = Map.of("attr", "p", "length", 2464L, "path", List.of(".pad", "2464"));
        Map<String, Object> dir = Map.of("a.bin", Map.of("", Map.of("length", 30000L, "pieces root", new byte[32])));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("dir", dir);
        tree.put("b.bin", Map.of("", Map.of("length", 10000L, "pieces root", new byte[32])));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "hybrid-demo");
        info.put("meta version", 2L);
        info.put("piece length", 16384L);
        info.put("pieces", new byte[60]); // (30000+10000+2464)/16384 = 2.59 → 3 piece
        // BEP 52：V1 files 顺序与 file tree 字典序 DFS 一致（b.bin 在 dir/a.bin 前）
        info.put("files", List.of(fileB, fileA, pad));
        info.put("file tree", tree);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", info);
        return BencodeBuilder.encode(root);
    }

    /** 单文件 V1 */
    static byte[] v1Single() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "single.iso");
        info.put("piece length", 16384L);
        info.put("length", 16384L);
        info.put("pieces", new byte[20]);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", info);
        return BencodeBuilder.encode(root);
    }
}
