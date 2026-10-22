package com.magnetplatform.domain.torrent;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.common.Hex;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Magnet URI 解析（规格 §3.1）：
 * 独立识别并验证 xt / tr / dn / xl；btih 支持 40 位十六进制与 Base32；
 * btmh 使用 multihash（sha2-256: 0x12 0x20 + 32 字节）并按规范校验。
 */
public final class MagnetParser {

    private static final String PREFIX = "magnet:?";
    private static final int MAX_URI_LENGTH = 8192;
    private static final int MAX_TRACKERS = 64;
    private static final int MAX_DISPLAY_NAME = 512;
    private static final Set<String> TRACKER_SCHEMES = Set.of("http", "https", "udp");
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public ParsedMagnet parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw invalid("URI 为空");
        }
        String trimmed = uri.trim();
        if (trimmed.length() > MAX_URI_LENGTH) {
            throw invalid("URI 超长");
        }
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw invalid("缺少 magnet:? 前缀");
        }
        String query = trimmed.substring(PREFIX.length());

        byte[] btih = null;
        byte[] btmh = null;
        List<String> trackers = new ArrayList<>();
        String displayName = null;
        Long exactLength = null;

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw invalid("参数缺少 '=': " + pair);
            }
            String key = decodeComponent(pair.substring(0, eq));
            String value = decodeComponent(pair.substring(eq + 1));
            switch (key) {
                case "xt" -> {
                    if (value.regionMatches(true, 0, "urn:btih:", 0, 9)) {
                        btih = parseBtih(value.substring(9));
                    } else if (value.regionMatches(true, 0, "urn:btmh:", 0, 9)) {
                        btmh = parseBtmh(value.substring(9));
                    }
                    // 其他 xt（如 x.pe）按规范忽略
                }
                case "tr" -> {
                    if (trackers.size() < MAX_TRACKERS && isAllowedTracker(value)) {
                        trackers.add(value);
                    }
                }
                case "dn" -> {
                    if (value.length() <= MAX_DISPLAY_NAME && !value.isBlank()) {
                        displayName = value;
                    }
                }
                case "xl" -> {
                    try {
                        long parsed = Long.parseLong(value);
                        if (parsed >= 0) {
                            exactLength = parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // xl 仅为提示，非法即忽略
                    }
                }
                default -> {
                    // 未知参数忽略
                }
            }
        }

        if (btih == null && btmh == null) {
            throw invalid("缺少有效 xt（btih 或 btmh）");
        }
        return new ParsedMagnet(btih, btmh, List.copyOf(dedupe(trackers)), displayName, exactLength);
    }

    private static byte[] parseBtih(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9a-f]{40}")) {
            return Hex.decode(lower);
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.matches("[A-Z2-7]{32}")) {
            return base32Decode(upper);
        }
        throw invalid("btih 必须是 40 位十六进制或 32 位 Base32");
    }

    private static byte[] parseBtmh(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.matches("[0-9a-f]{68}")) {
            throw invalid("btmh 必须是 68 位十六进制 multihash");
        }
        byte[] multihash = Hex.decode(lower);
        if ((multihash[0] & 0xff) != 0x12 || (multihash[1] & 0xff) != 0x20) {
            throw invalid("btmh multihash 必须是 sha2-256（0x12 0x20 前缀）");
        }
        byte[] digest = new byte[32];
        System.arraycopy(multihash, 2, digest, 0, 32);
        return digest;
    }

    private static boolean isAllowedTracker(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            return false;
        }
        return TRACKER_SCHEMES.contains(url.substring(0, schemeEnd).toLowerCase(Locale.ROOT));
    }

    private static List<String> dedupe(List<String> trackers) {
        Set<String> seen = new LinkedHashSet<>(trackers);
        return List.copyOf(seen);
    }

    private static byte[] base32Decode(String text) {
        int bits = 0;
        int buffer = 0;
        byte[] out = new byte[20];
        int outPos = 0;
        for (int i = 0; i < text.length(); i++) {
            int v = BASE32_ALPHABET.indexOf(text.charAt(i));
            if (v < 0) {
                throw invalid("Base32 含非法字符");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (outPos >= out.length) {
                    throw invalid("Base32 解码长度越界");
                }
                out[outPos++] = (byte) (buffer >>> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        if (outPos != 20) {
            throw invalid("btih Base32 解码后必须是 20 字节");
        }
        return out;
    }

    private static String decodeComponent(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("参数百分号编码非法");
        }
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCodes.INVALID_MAGNET, reason);
    }
}
