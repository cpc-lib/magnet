package com.magnetplatform.domain.torrent;

import com.magnetplatform.domain.common.Digests;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.resource.VersionType;
import com.magnetplatform.domain.torrent.bencode.BNode;
import com.magnetplatform.domain.torrent.bencode.BencodeDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * .torrent 元数据解析（BEP 3 / BEP 52）：
 * - V1：SHA-1 infohash、pieces 连接哈希、files/length；
 * - V2：SHA-256 infohash、file tree、每文件 pieces root；
 * - Hybrid：两套元数据并存，交叉校验非 pad 文件一致。
 * infohash 对 info 字典的原始字节区间计算，杜绝重编码漂移。
 */
public final class TorrentMetaParser {

    /** .torrent 上传字节上限（规格 §13） */
    public static final int MAX_TORRENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILES = 100_000;
    private static final long MAX_PIECE_LENGTH = 1L << 30;
    private static final int SHA1_LENGTH = 20;
    private static final int SHA256_LENGTH = 32;

    public ParsedTorrent parse(byte[] torrentBytes) {
        if (torrentBytes == null || torrentBytes.length == 0) {
            throw invalid("torrent 内容为空");
        }
        if (torrentBytes.length > MAX_TORRENT_BYTES) {
            throw invalid("torrent 超过字节上限 " + MAX_TORRENT_BYTES);
        }
        BNode root = new BencodeDecoder().decode(torrentBytes, BencodeDecoder.Limits.torrentDefault());
        if (!(root instanceof BNode.BDict rootDict)) {
            throw invalid("顶层必须是字典");
        }
        BNode infoNode = rootDict.values().get("info");
        if (!(infoNode instanceof BNode.BDict info)) {
            throw invalid("缺少 info 字典");
        }
        byte[] infoBytes = Arrays.copyOfRange(torrentBytes, infoNode.start(), infoNode.end());

        long metaVersion = optInt(info, "meta version", 0);
        if (metaVersion != 0 && metaVersion != 1 && metaVersion != 2) {
            throw invalid("不支持的 meta version: " + metaVersion);
        }
        String name = PathSanitizer.sanitizeComponent(optText(info, "name.utf-8", optText(info, "name", null)));
        if (name == null) {
            throw invalid("缺少 name");
        }
        long pieceLength = reqInt(info, "piece length");
        if (pieceLength <= 0 || (pieceLength & (pieceLength - 1)) != 0 || pieceLength > MAX_PIECE_LENGTH) {
            throw invalid("piece length 必须是 2 的幂且不超过 " + MAX_PIECE_LENGTH);
        }
        boolean privateFlag = optInt(info, "private", 0) == 1;
        List<String> trackers = parseTrackers(rootDict);

        boolean hasV2Tree = info.values().get("file tree") instanceof BNode.BDict;
        boolean hasV1Pieces = info.values().get("pieces") instanceof BNode.BBytes;
        boolean hasV1Files = info.values().get("files") instanceof BNode.BList
                || info.values().get("length") instanceof BNode.BInt;

        final VersionType versionType;
        final List<ParsedTorrentFile> files;
        final long totalBytes;
        final byte[] infoHashV1;
        final byte[] infoHashV2;

        if (metaVersion == 2) {
            if (!hasV2Tree) {
                throw invalid("V2 种子缺少 file tree");
            }
            List<ParsedTorrentFile> v2Files = new ArrayList<>();
            walkFileTree(asDict(info.values().get("file tree"), "file tree"), List.of(), v2Files);
            if (v2Files.isEmpty()) {
                throw invalid("file tree 为空");
            }
            infoHashV2 = Digests.sha256(infoBytes);
            if (hasV1Pieces || hasV1Files) {
                // Hybrid：V1 侧校验 + 非 pad 文件交叉一致性
                V1Side v1 = parseV1Side(info, name, pieceLength);
                crossCheckHybrid(v1.files(), v2Files);
                versionType = VersionType.HYBRID;
                infoHashV1 = Digests.sha1(infoBytes);
                totalBytes = v2Files.stream().mapToLong(ParsedTorrentFile::sizeBytes).sum();
            } else {
                versionType = VersionType.V2;
                infoHashV1 = null;
                totalBytes = v2Files.stream().mapToLong(ParsedTorrentFile::sizeBytes).sum();
            }
            files = List.copyOf(v2Files);
        } else {
            if (!hasV1Pieces) {
                throw invalid("V1 种子缺少 pieces");
            }
            V1Side v1 = parseV1Side(info, name, pieceLength);
            versionType = VersionType.V1;
            files = v1.files();
            totalBytes = v1.totalBytes();
            infoHashV1 = Digests.sha1(infoBytes);
            infoHashV2 = null;
        }

        if (files.size() > MAX_FILES) {
            throw invalid("文件数超过限制 " + MAX_FILES);
        }
        return new ParsedTorrent(infoHashV1, infoHashV2, versionType, name, pieceLength,
                totalBytes, privateFlag, trackers, files);
    }

    // ---------- V1 ----------

    private record V1Side(List<ParsedTorrentFile> files, long totalBytes) {
    }

    /** 解析 V1 侧文件清单（含 pad 文件参与 pieces 计数），并校验 pieces 数量 */
    private V1Side parseV1Side(BNode.BDict info, String name, long pieceLength) {
        byte[] pieces = reqBytes(info, "pieces");
        if (pieces.length % SHA1_LENGTH != 0) {
            throw invalid("pieces 长度必须是 20 的倍数");
        }
        List<V1Entry> entries = new ArrayList<>();
        BNode filesNode = info.values().get("files");
        if (filesNode instanceof BNode.BList filesList) {
            int index = 0;
            for (BNode item : filesList.values()) {
                if (!(item instanceof BNode.BDict fileDict)) {
                    throw invalid("files 条目必须是字典");
                }
                long length = reqInt(fileDict, "length");
                if (length < 0) {
                    throw invalid("文件长度不能为负");
                }
                List<String> pathComponents = reqPathComponents(fileDict);
                boolean pad = isPadFile(fileDict);
                entries.add(new V1Entry(index++, PathSanitizer.joinRelativePath(pathComponents), length, pad));
            }
        } else if (info.values().get("length") instanceof BNode.BInt lengthNode) {
            long length = lengthNode.value();
            if (length < 0) {
                throw invalid("文件长度不能为负");
            }
            entries.add(new V1Entry(0, name, length, false));
        } else {
            throw invalid("缺少 files 或 length");
        }

        long total = entries.stream().mapToLong(V1Entry::length).sum();
        int expectedPieces = total == 0 ? 0 : (int) ((total + pieceLength - 1) / pieceLength);
        if (pieces.length / SHA1_LENGTH != expectedPieces) {
            throw invalid("pieces 数量与总大小不匹配");
        }
        List<ParsedTorrentFile> files = entries.stream()
                .map(e -> new ParsedTorrentFile(e.index(), e.path(), e.length(), null))
                .toList();
        return new V1Side(List.copyOf(files), total);
    }

    private record V1Entry(int index, String path, long length, boolean pad) {
    }

    private static boolean isPadFile(BNode.BDict fileDict) {
        BNode attr = fileDict.values().get("attr");
        return attr instanceof BNode.BBytes bytes && new String(bytes.value(), StandardCharsets.UTF_8).contains("p");
    }

    // ---------- V2 ----------

    /** 递归遍历 file tree；叶子为含 "" 键的字典 */
    private void walkFileTree(BNode.BDict tree, List<String> prefix, List<ParsedTorrentFile> out) {
        for (Map.Entry<String, BNode> entry : tree.values().entrySet()) {
            String name = entry.getKey();
            if (name.isEmpty()) {
                throw invalid("file tree 中间层不允许出现叶子键");
            }
            if (!(entry.getValue() instanceof BNode.BDict node)) {
                throw invalid("file tree 节点必须是字典");
            }
            PathSanitizer.sanitizeComponent(name);
            BNode leaf = node.values().get("");
            List<String> path = new ArrayList<>(prefix);
            path.add(name);
            if (leaf != null) {
                if (!(leaf instanceof BNode.BDict leafDict)) {
                    throw invalid("file tree 叶子必须是字典");
                }
                if (node.values().size() > 1) {
                    throw invalid("file tree 叶子节点不允许同时拥有子节点");
                }
                long length = reqInt(leafDict, "length");
                if (length < 0) {
                    throw invalid("文件长度不能为负");
                }
                byte[] piecesRoot = null;
                BNode rootNode = leafDict.values().get("pieces root");
                if (rootNode instanceof BNode.BBytes rootBytes) {
                    if (rootBytes.value().length != SHA256_LENGTH) {
                        throw invalid("pieces root 必须是 32 字节");
                    }
                    piecesRoot = rootBytes.value();
                }
                if (length > 0 && piecesRoot == null) {
                    throw invalid("非零长度文件缺少 pieces root");
                }
                if (out.size() >= MAX_FILES) {
                    throw invalid("文件数超过限制 " + MAX_FILES);
                }
                out.add(new ParsedTorrentFile(out.size(), PathSanitizer.joinRelativePath(path), length, piecesRoot));
            } else {
                walkFileTree(node, path, out);
            }
        }
    }

    /** Hybrid 交叉校验：V1 非 pad 文件必须与 V2 叶子在路径与长度上完全一致 */
    private static void crossCheckHybrid(List<ParsedTorrentFile> v1Files, List<ParsedTorrentFile> v2Files) {
        List<ParsedTorrentFile> v1NonPad = v1Files.stream()
                .filter(f -> !isPadPath(f.relativePath()))
                .toList();
        if (v1NonPad.size() != v2Files.size()) {
            throw invalid("Hybrid 的 V1/V2 文件清单数量不一致");
        }
        for (int i = 0; i < v1NonPad.size(); i++) {
            ParsedTorrentFile a = v1NonPad.get(i);
            ParsedTorrentFile b = v2Files.get(i);
            if (!a.relativePath().equals(b.relativePath()) || a.sizeBytes() != b.sizeBytes()) {
                throw invalid("Hybrid 的 V1/V2 文件不一致: " + a.relativePath());
            }
        }
    }

    private static boolean isPadPath(String path) {
        return path.startsWith(".pad/");
    }

    // ---------- 字典取值辅助 ----------

    private static BNode.BDict asDict(BNode node, String what) {
        if (!(node instanceof BNode.BDict dict)) {
            throw invalid(what + " 必须是字典");
        }
        return dict;
    }

    private static long reqInt(BNode.BDict dict, String key) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BInt value)) {
            throw invalid("缺少整数键 " + key);
        }
        return value.value();
    }

    private static long optInt(BNode.BDict dict, String key, long defaultValue) {
        BNode node = dict.values().get(key);
        return node instanceof BNode.BInt value ? value.value() : defaultValue;
    }

    private static byte[] reqBytes(BNode.BDict dict, String key) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BBytes value)) {
            throw invalid("缺少字符串键 " + key);
        }
        return value.value();
    }

    private static String optText(BNode.BDict dict, String key, String defaultValue) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BBytes value)) {
            return defaultValue;
        }
        String text = new String(value.value(), StandardCharsets.UTF_8);
        return text.isEmpty() ? defaultValue : text;
    }

    private static List<String> reqPathComponents(BNode.BDict fileDict) {
        BNode node = fileDict.values().get("path.utf-8");
        if (!(node instanceof BNode.BList)) {
            node = fileDict.values().get("path");
        }
        if (!(node instanceof BNode.BList pathList) || pathList.values().isEmpty()) {
            throw invalid("files 条目缺少 path");
        }
        List<String> components = new ArrayList<>();
        for (BNode component : pathList.values()) {
            if (!(component instanceof BNode.BBytes text)) {
                throw invalid("path 分量必须是字符串");
            }
            components.add(new String(text.value(), StandardCharsets.UTF_8));
        }
        return components;
    }

    private static List<String> parseTrackers(BNode.BDict rootDict) {
        Set<String> trackers = new LinkedHashSet<>();
        BNode announce = rootDict.values().get("announce");
        if (announce instanceof BNode.BBytes url) {
            trackers.add(new String(url.value(), StandardCharsets.UTF_8));
        }
        BNode announceList = rootDict.values().get("announce-list");
        if (announceList instanceof BNode.BList tiers) {
            for (BNode tier : tiers.values()) {
                if (tier instanceof BNode.BList urls) {
                    for (BNode url : urls.values()) {
                        if (url instanceof BNode.BBytes text) {
                            trackers.add(new String(text.value(), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        return List.copyOf(trackers);
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCodes.INVALID_TORRENT, reason);
    }
}
