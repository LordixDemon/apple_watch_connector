package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public final class OpticalWatermarkPacketsTest {
    private static final String FIXTURE;
    static {
        try (InputStream input = OpticalWatermarkPacketsTest.class.getResourceAsStream("/optical/packet-oracle.json")) {
            FIXTURE = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private static byte[] value(String field) {
        Matcher match = Pattern.compile("\"" + field + "\": \"([0-9a-f]+)\"").matcher(FIXTURE);
        assertTrue(match.find());
        return java.util.HexFormat.of().parseHex(match.group(1));
    }
    private static List<byte[]> packets() {
        Matcher match = Pattern.compile("\"bytes\": \"([0-9a-f]+)\"").matcher(FIXTURE);
        List<byte[]> packets = new ArrayList<>();
        while (match.find()) packets.add(java.util.HexFormat.of().parseHex(match.group(1)));
        assertEquals(32, packets.size());
        return packets;
    }
    @Test public void verifiesNativeCrcAndRejectsEverySingleBitCorruption() {
        byte[] encoded = value("encoded");
        assertTrue(OpticalWatermarkPackets.validCrc(encoded));
        for (int i = 0; i < encoded.length * 8; i++) {
            encoded[i / 8] ^= (byte) (1 << (i % 8));
            assertFalse(OpticalWatermarkPackets.validCrc(encoded));
            encoded[i / 8] ^= (byte) (1 << (i % 8));
        }
    }
    @Test public void recoversNativeOraclePayloadFromEightFramesDespiteLossAndReordering() {
        List<byte[]> packets = packets();
        for (int[] ids : new int[][]{{0, 1, 2, 3, 4, 5, 6, 7}, {31, 7, 15, 0, 23, 3, 11, 27}}) {
            try (OpticalWatermarkPackets decoder = new OpticalWatermarkPackets()) {
                for (int i = 0; i < ids.length; i++) {
                    assertEquals(i < 7 ? OpticalWatermarkPackets.Status.PROGRESS : OpticalWatermarkPackets.Status.COMPLETE,
                            decoder.accept(ids[i], packets.get(ids[i])));
                }
                assertEquals(114, decoder.rank());
                assertArrayEquals(value("payload"), decoder.takePayload());
                assertEquals(0, decoder.rank());
                assertNull(decoder.takePayload());
            }
        }
    }
    @Test public void duplicateFramesDoNotAdvanceProgressAndConflictsClearSecretState() {
        List<byte[]> packets = packets();
        try (OpticalWatermarkPackets decoder = new OpticalWatermarkPackets()) {
            decoder.accept(2, packets.get(2));
            int rank = decoder.rank();
            for (int i = 0; i < 20; i++) {
                assertEquals(OpticalWatermarkPackets.Status.PROGRESS, decoder.accept(2, packets.get(2)));
                assertEquals(rank, decoder.rank());
            }
            byte[] corrupt = packets.get(2).clone();
            corrupt[0] ^= 1;
            assertEquals(OpticalWatermarkPackets.Status.INVALID, decoder.accept(2, corrupt));
            assertEquals(0, decoder.rank());
            assertNull(decoder.takePayload());
            assertThrows(IllegalArgumentException.class, () -> decoder.accept(32, packets.get(0)));
            assertThrows(IllegalArgumentException.class, () -> decoder.accept(0, new byte[16]));
        }
    }
    @Test public void corruptButWellShapedPacketCannotProducePayload() {
        List<byte[]> packets = packets();
        packets.get(0)[0] ^= 1;
        try (OpticalWatermarkPackets decoder = new OpticalWatermarkPackets()) {
            for (int i = 0; i < 7; i++) decoder.accept(i, packets.get(i));
            assertEquals(OpticalWatermarkPackets.Status.INVALID, decoder.accept(7, packets.get(7)));
            assertNull(decoder.takePayload());
            assertEquals(0, decoder.rank());
        }
    }
}
