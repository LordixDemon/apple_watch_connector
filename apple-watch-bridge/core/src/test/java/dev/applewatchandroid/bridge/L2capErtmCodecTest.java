package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class L2capErtmCodecTest {
    @Test
    public void encodesExactUnsegmentedApplePipeIFrameWithFcs() {
        byte[] emptyUike = hex("04 00 00 FB FF");

        byte[] encoded = L2capErtmCodec.encodeInformationFrame(
                0x0307,
                0,
                0,
                emptyUike,
                true);

        // FCS input:
        // 09 00 07 03 || 00 00 || 04 00 00 FB FF
        // CRC-16/0xA001(init=0) = 0x09BA, transmitted little-endian.
        assertArrayEquals(
                hex("00 00 04 00 00 FB FF BA 09"),
                encoded);

        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(0x0307, encoded, true);
        assertFalse(decoded.supervisory);
        assertEquals(0, decoded.txSequence);
        assertEquals(0, decoded.requestSequence);
        assertEquals(L2capErtmCodec.SAR_UNSEGMENTED, decoded.sar);
        assertTrue(decoded.fcsPresent);
        assertEquals(0x09BA, decoded.fcs);
        assertArrayEquals(emptyUike, decoded.information);
    }

    @Test
    public void encodesExactAppleTerminusIFrameWithoutFcs() {
        byte[] emptyUike = hex("04 00 00 FB FF");

        byte[] encoded = L2capErtmCodec.encodeInformationFrame(
                0x0307,
                0,
                0,
                emptyUike,
                false);

        assertArrayEquals(
                hex("00 00 04 00 00 FB FF"),
                encoded);

        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(0x0307, encoded, false);
        assertFalse(decoded.supervisory);
        assertEquals(0, decoded.txSequence);
        assertEquals(0, decoded.requestSequence);
        assertFalse(decoded.fcsPresent);
        assertEquals(-1, decoded.fcs);
        assertArrayEquals(emptyUike, decoded.information);
    }

    @Test
    public void encodesAndParsesReceiverReadyAcknowledgement() {
        byte[] encoded = L2capErtmCodec.encodeReceiverReady(
                0x0307,
                1,
                false,
                true);

        // FCS input: 04 00 07 03 || 01 01.
        assertArrayEquals(hex("01 01 30 A0"), encoded);

        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(0x0307, encoded, true);
        assertTrue(decoded.supervisory);
        assertEquals(L2capErtmCodec.SUPERVISORY_RR,
                decoded.supervisoryFunction);
        assertEquals(1, decoded.requestSequence);
        assertFalse(decoded.poll);
        assertFalse(decoded.finalBit);
        assertEquals(0, decoded.information.length);
    }

    @Test
    public void encodesReceiverReadyWithoutFcs() {
        byte[] encoded = L2capErtmCodec.encodeReceiverReady(
                0x0307,
                1,
                false,
                false);

        assertArrayEquals(hex("01 01"), encoded);

        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(0x0307, encoded, false);
        assertTrue(decoded.supervisory);
        assertEquals(1, decoded.requestSequence);
        assertFalse(decoded.fcsPresent);
    }

    @Test
    public void handlesPiggybackAckAndResponseSequence() {
        byte[] response = hex("04 00 02 DE AD 4C 21");
        byte[] encoded = L2capErtmCodec.encodeInformationFrame(
                0x0040,
                0,
                1,
                response,
                true);

        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(0x0040, encoded, true);
        assertFalse(decoded.supervisory);
        assertEquals(0, decoded.txSequence);
        assertEquals(1, decoded.requestSequence);
        assertArrayEquals(response, decoded.information);
    }

    @Test
    public void rejectsWrongCidOrDamagedFcs() {
        byte[] encoded = L2capErtmCodec.encodeInformationFrame(
                0x0307,
                0,
                0,
                hex("01 02 03"),
                true);

        assertThrows(
                IllegalArgumentException.class,
                () -> L2capErtmCodec.decode(0x0040, encoded, true));

        encoded[2] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> L2capErtmCodec.decode(0x0307, encoded, true));
    }

    private static byte[] hex(String value) {
        String normalized = value.replaceAll("\\s", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd hex length");
        }
        byte[] output = new byte[normalized.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    normalized.substring(index * 2, index * 2 + 2),
                    16);
        }
        return output;
    }
}
