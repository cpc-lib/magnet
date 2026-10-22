package com.magnetplatform.domain.torrent.bencode;

import java.util.List;
import java.util.Map;

/**
 * bencode 值节点。start/end 为原始字节区间（end 开区间），
 * 用于对 info 字典按原始字节计算 infohash，避免重编码导致哈希漂移。
 */
public sealed interface BNode {

    int start();

    int end();

    record BInt(long value, int start, int end) implements BNode {
    }

    record BBytes(byte[] value, int start, int end) implements BNode {
    }

    record BList(List<BNode> values, int start, int end) implements BNode {
    }

    /** key 按 bencode 原始字节以 UTF-8 解码；key 在字典内保持原始顺序 */
    record BDict(Map<String, BNode> values, int start, int end) implements BNode {
    }
}
