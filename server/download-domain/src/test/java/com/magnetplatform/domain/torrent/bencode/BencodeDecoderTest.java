package com.magnetplatform.domain.torrent.bencode;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BencodeDecoderTest {

    private final BencodeDecoder decoder = new BencodeDecoder();
    private final BencodeDecoder.Limits limits = new BencodeDecoder.Limits(8, 1024, 10_000);

    @Test
    void decodesAllTypes() {
        byte[] input = "d1:ai42e1:ble1:c4:spame".getBytes(StandardCharsets.US_ASCII);
        BNode.BDict root = assertInstanceOf(BNode.BDict.class, decoder.decode(input, limits));
        assertEquals(42L, ((BNode.BInt) root.values().get("a")).value());
        assertEquals(0, ((BNode.BList) root.values().get("b")).values().size());
        assertEquals("spam", new String(((BNode.BBytes) root.values().get("c")).value(), StandardCharsets.UTF_8));
    }

    @Test
    void recordsRawSpans() {
        byte[] input = "d4:infod1:xi1eee".getBytes(StandardCharsets.US_ASCII);
        BNode.BDict root = (BNode.BDict) decoder.decode(input, limits);
        BNode info = root.values().get("info");
        assertEquals("d1:xi1ee", new String(input, info.start(), info.end() - info.start(), StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsDepthBomb() {
        // 100 层嵌套列表
        byte[] input = ("l".repeat(100) + "e".repeat(100)).getBytes(StandardCharsets.US_ASCII);
        DomainException e = assertThrows(DomainException.class, () -> decoder.decode(input, limits));
        assertEquals(ErrorCodes.INVALID_TORRENT, e.code());
        assertTrue(e.getMessage().contains("深度"));
    }

    @Test
    void rejectsOversizedString() {
        byte[] input = "99999:x".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, limits));
    }

    @Test
    void rejectsOutOfBoundsString() {
        byte[] input = "10:ab".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, limits));
    }

    @Test
    void rejectsBadIntegers() {
        for (String bad : List.of("i03e", "i-0e", "ie", "i-e", "i1x", "i99999999999999999999e")) {
            assertThrows(DomainException.class,
                    () -> decoder.decode(bad.getBytes(StandardCharsets.US_ASCII), limits), bad);
        }
    }

    @Test
    void rejectsDuplicateKeys() {
        byte[] input = "d1:ai1e1:ai2ee".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, limits));
    }

    @Test
    void rejectsTrailingData() {
        byte[] input = "i1ei2e".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, limits));
    }

    @Test
    void rejectsNonStringDictKey() {
        byte[] input = "di1ei2ee".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, limits));
    }

    @Test
    void enforcesNodeLimit() {
        BencodeDecoder.Limits tight = new BencodeDecoder.Limits(64, 1024, 3);
        byte[] input = "li1ei2ei3ei4ee".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DomainException.class, () -> decoder.decode(input, tight));
    }

    @Test
    void decodesEmptyContainers() {
        BNode.BDict root = (BNode.BDict) decoder.decode("d1:ale1:bdee".getBytes(StandardCharsets.US_ASCII), limits);
        assertEquals(Map.of(), ((BNode.BDict) root.values().get("b")).values());
    }
}
