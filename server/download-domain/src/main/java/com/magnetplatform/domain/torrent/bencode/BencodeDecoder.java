package com.magnetplatform.domain.torrent.bencode;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全 bencode 解码器（规格 §13）：
 * 限制嵌套深度、字符串长度、节点总数；拒绝畸形整数、重复 key、越界长度。
 */
public final class BencodeDecoder {

    public record Limits(int maxDepth, int maxStringBytes, int maxNodes) {

        public static Limits torrentDefault() {
            return new Limits(64, 16 * 1024 * 1024, 2_000_000);
        }
    }

    private byte[] data;
    private int pos;
    private int nodes;
    private Limits limits;

    public BNode decode(byte[] input, Limits limits) {
        if (input == null || input.length == 0) {
            throw invalid("空输入");
        }
        this.data = input;
        this.limits = limits;
        this.pos = 0;
        this.nodes = 0;
        BNode node = parseValue(0);
        if (pos != data.length) {
            throw invalid("存在尾随字节");
        }
        return node;
    }

    private BNode parseValue(int depth) {
        if (depth > limits.maxDepth()) {
            throw invalid("嵌套深度超过限制 " + limits.maxDepth());
        }
        if (++nodes > limits.maxNodes()) {
            throw invalid("节点数超过限制 " + limits.maxNodes());
        }
        if (pos >= data.length) {
            throw invalid("意外结束");
        }
        byte b = data[pos];
        if (b == 'i') {
            return parseInt();
        }
        if (b == 'l') {
            return parseList(depth);
        }
        if (b == 'd') {
            return parseDict(depth);
        }
        if (b >= '0' && b <= '9') {
            return parseBytes();
        }
        throw invalid("非法类型字节 0x" + Integer.toHexString(b & 0xff));
    }

    private BNode.BInt parseInt() {
        int start = pos++;
        int end = indexOf((byte) 'e', pos);
        if (end < 0) {
            throw invalid("整数缺少结束符");
        }
        String text = new String(data, pos, end - pos, StandardCharsets.US_ASCII);
        if (text.isEmpty() || text.length() > 20) {
            throw invalid("整数长度非法");
        }
        if (text.charAt(0) == '0' && text.length() > 1) {
            throw invalid("整数前导零");
        }
        if (text.startsWith("-0")) {
            throw invalid("非法负零");
        }
        if (text.charAt(0) == '-' && text.length() == 1) {
            throw invalid("整数缺少数字");
        }
        for (int i = (text.charAt(0) == '-' ? 1 : 0); i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                throw invalid("整数含非数字字符");
            }
        }
        final long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalid("整数溢出");
        }
        pos = end + 1;
        return new BNode.BInt(value, start, pos);
    }

    private BNode.BBytes parseBytes() {
        int start = pos;
        int colon = indexOf((byte) ':', pos);
        if (colon < 0) {
            throw invalid("字符串缺少冒号");
        }
        if (colon - pos > 10) {
            throw invalid("字符串长度前缀过长");
        }
        long length = 0;
        for (int i = pos; i < colon; i++) {
            if (!Character.isDigit(data[i])) {
                throw invalid("字符串长度含非数字字符");
            }
            length = length * 10 + (data[i] - '0');
        }
        if (length > limits.maxStringBytes()) {
            throw invalid("字符串长度超过限制 " + limits.maxStringBytes());
        }
        if (colon + 1 + length > data.length) {
            throw invalid("字符串长度越界");
        }
        byte[] value = new byte[(int) length];
        System.arraycopy(data, colon + 1, value, 0, (int) length);
        pos = colon + 1 + (int) length;
        return new BNode.BBytes(value, start, pos);
    }

    private BNode.BList parseList(int depth) {
        int start = pos++;
        List<BNode> values = new ArrayList<>();
        while (true) {
            if (pos >= data.length) {
                throw invalid("列表缺少结束符");
            }
            if (data[pos] == 'e') {
                pos++;
                return new BNode.BList(List.copyOf(values), start, pos);
            }
            values.add(parseValue(depth + 1));
        }
    }

    private BNode.BDict parseDict(int depth) {
        int start = pos++;
        Map<String, BNode> values = new LinkedHashMap<>();
        while (true) {
            if (pos >= data.length) {
                throw invalid("字典缺少结束符");
            }
            if (data[pos] == 'e') {
                pos++;
                return new BNode.BDict(values, start, pos);
            }
            if (data[pos] < '0' || data[pos] > '9') {
                throw invalid("字典 key 必须是字符串");
            }
            BNode.BBytes key = parseBytes();
            String keyText = new String(key.value(), StandardCharsets.UTF_8);
            if (values.put(keyText, parseValue(depth + 1)) != null) {
                throw invalid("字典 key 重复: " + keyText);
            }
        }
    }

    private int indexOf(byte target, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCodes.INVALID_TORRENT, "bencode 非法: " + reason);
    }
}
