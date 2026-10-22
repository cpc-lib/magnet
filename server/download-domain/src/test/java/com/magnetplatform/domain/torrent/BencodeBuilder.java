package com.magnetplatform.domain.torrent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 测试用 bencode 编码器：Map 按 key 字典序输出（规范形式） */
final class BencodeBuilder {

    private BencodeBuilder() {
    }

    static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, ByteArrayOutputStream out) {
        if (value instanceof String text) {
            writeBytes(text.getBytes(StandardCharsets.UTF_8), out);
        } else if (value instanceof byte[] bytes) {
            writeBytes(bytes, out);
        } else if (value instanceof Number number) {
            out.write('i');
            writeAscii(Long.toString(number.longValue()), out);
            out.write('e');
        } else if (value instanceof List<?> list) {
            out.write('l');
            list.forEach(item -> write(item, out));
            out.write('e');
        } else if (value instanceof Map<?, ?> map) {
            out.write('d');
            new TreeMap<>((Map<String, Object>) map).forEach((key, item) -> {
                writeBytes(key.getBytes(StandardCharsets.UTF_8), out);
                write(item, out);
            });
            out.write('e');
        } else {
            throw new IllegalArgumentException("不支持的类型: " + value);
        }
    }

    private static void writeBytes(byte[] bytes, ByteArrayOutputStream out) {
        writeAscii(Integer.toString(bytes.length), out);
        out.write(':');
        out.write(bytes, 0, bytes.length);
    }

    private static void writeAscii(String text, ByteArrayOutputStream out) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
