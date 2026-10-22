package com.magnetplatform.domain.torrent;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.common.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagnetParserTest {

    private final MagnetParser parser = new MagnetParser();

    @Test
    void parsesHexBtih() {
        String hash = "0123456789abcdef0123456789abcdef01234567";
        ParsedMagnet parsed = parser.parse("magnet:?xt=urn:btih:" + hash
                + "&dn=demo&xl=123&tr=https%3A%2F%2Ft.example%2Fann");
        assertArrayEquals(Hex.decode(hash), parsed.btih());
        assertNull(parsed.btmh());
        assertEquals("demo", parsed.displayNameHint());
        assertEquals(123L, parsed.exactLengthHint());
        assertEquals(1, parsed.trackers().size());
    }

    @Test
    void parsesBase32Btih() {
        String hex = "0123456789abcdef0123456789abcdef01234567";
        String base32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"; // 与 hex 相同的 20 字节
        ParsedMagnet parsed = parser.parse("magnet:?xt=urn:btih:" + base32);
        assertArrayEquals(Hex.decode(hex), parsed.btih());
    }

    @Test
    void parsesBtmhMultihash() {
        String digest = "aa".repeat(32);
        ParsedMagnet parsed = parser.parse("magnet:?xt=urn:btmh:1220" + digest);
        assertNull(parsed.btih());
        assertEquals(32, parsed.btmh().length);
        assertEquals(digest, Hex.encode(parsed.btmh()));
    }

    @Test
    void parsesHybridMagnet() {
        String uri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                + "&xt=urn:btmh:1220" + "bb".repeat(32);
        ParsedMagnet parsed = parser.parse(uri);
        assertEquals(com.magnetplatform.domain.resource.VersionType.HYBRID, parsed.versionType());
    }

    @Test
    void rejectsMissingXt() {
        DomainException e = assertThrows(DomainException.class, () -> parser.parse("magnet:?dn=only-name"));
        assertEquals(ErrorCodes.INVALID_MAGNET, e.code());
    }

    @Test
    void rejectsBadBtih() {
        assertThrows(DomainException.class, () -> parser.parse("magnet:?xt=urn:btih:zzz"));
        assertThrows(DomainException.class, () -> parser.parse("magnet:?xt=urn:btih:0123"));
    }

    @Test
    void rejectsBadBtmhPrefix() {
        // multihash 前缀非 0x12 0x20
        assertThrows(DomainException.class,
                () -> parser.parse("magnet:?xt=urn:btmh:1600" + "aa".repeat(32)));
    }

    @Test
    void rejectsNonMagnetUri() {
        assertThrows(DomainException.class, () -> parser.parse("http://example.com/x"));
        assertThrows(DomainException.class, () -> parser.parse(""));
        assertThrows(DomainException.class, () -> parser.parse(null));
    }

    @Test
    void filtersTrackerSchemes() {
        ParsedMagnet parsed = parser.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                + "&tr=file%3A%2F%2F%2Fetc%2Fpasswd&tr=udp%3A%2F%2Ft.example%3A6969%2Fann");
        assertEquals(1, parsed.trackers().size());
        assertTrue(parsed.trackers().get(0).startsWith("udp://"));
    }

    @Test
    void ignoresMalformedXl() {
        ParsedMagnet parsed = parser.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&xl=abc");
        assertNull(parsed.exactLengthHint());
    }
}
