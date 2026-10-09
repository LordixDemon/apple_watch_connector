package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.math.ec.rfc7748.X448;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.List;

public final class IkeV2CodecTest {
    @Test
    public void opticalSaInitOmitsPinOnlyNotifications() {
        IkeV2Codec.InitiatorState state = IkeV2Codec.createOpticalPairingSaInit(new SecureRandom());
        assertEquals(IkeV2Codec.CONTROL_SA_INIT_LENGTH, state.ikePacket.length);
        assertArrayEquals(IkeV2Codec.buildControlSaInit(state.initiatorSpi,
                state.nonce, state.x448PublicKey), state.ikePacket);
    }

    private static final byte[] FIXED_SPI =
            hex("01 02 03 04 05 06 07 08");
    private static final byte[] FIXED_NONCE = hex(
            "A0 A1 A2 A3 A4 A5 A6 A7"
                    + " A8 A9 AA AB AC AD AE AF"
                    + " B0 B1 B2 B3 B4 B5 B6 B7"
                    + " B8 B9 BA BB BC BD BE BF");
    private static final byte[] FIXED_X448_PUBLIC = hex(
            "00 01 02 03 04 05 06 07"
                    + " 08 09 0A 0B 0C 0D 0E 0F"
                    + " 10 11 12 13 14 15 16 17"
                    + " 18 19 1A 1B 1C 1D 1E 1F"
                    + " 20 21 22 23 24 25 26 27"
                    + " 28 29 2A 2B 2C 2D 2E 2F"
                    + " 30 31 32 33 34 35 36 37");

    @Test
    public void buildsExactRecoveredAppleControlSaInitVector() {
        byte[] packet = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);

        byte[] expected = hex(
                // IKE header.
                "0102030405060708"
                        + "0000000000000000"
                        + "21202208"
                        + "00000000"
                        + "00000108"
                        // SA generic header and one IKE proposal.
                        + "22000040"
                        + "0000003c01010006"
                        // ENCR AES-GCM-16, key length 256.
                        + "0300000c01000014800e0100"
                        // ENCR ChaCha20-Poly1305.
                        + "030000080100001c"
                        // PRF HMAC-SHA2-512.
                        + "0300000802000007"
                        // Additional Key Exchange 1, ML-KEM-1024.
                        + "0300000806000025"
                        // Primary KEM proposals: X448, then X25519.
                        + "0300000804000020"
                        + "000000080400001f"
                        // KE: X448 and the deterministic 56-byte public key.
                        + "28000040"
                        + "00200000"
                        + "000102030405060708090a0b0c0d0e0f"
                        + "101112131415161718191a1b1c1d1e1f"
                        + "202122232425262728292a2b2c2d2e2f"
                        + "3031323334353637"
                        // 32-byte nonce.
                        + "29000024"
                        + "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
                        + "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
                        // Apple fake NAT source hash: 20 zero bytes.
                        + "2900001c00004004"
                        + "0000000000000000000000000000000000000000"
                        // Apple fake NAT destination hash: 20 zero bytes.
                        + "2900001c00004005"
                        + "0000000000000000000000000000000000000000"
                        // Fragmentation support and IKE intermediate support.
                        + "290000080000402e"
                        + "0000000800004036");

        assertEquals(IkeV2Codec.CONTROL_SA_INIT_LENGTH, packet.length);
        assertArrayEquals(expected, packet);
    }

    @Test
    public void appendsExactSecurePasswordAndPpkPairingNotifies() {
        byte[] control = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);
        byte[] pairing = IkeV2Codec.buildPairingSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);

        assertEquals(
                IkeV2Codec.PAIRING_SA_INIT_LENGTH,
                pairing.length);
        assertArrayEquals(
                java.util.Arrays.copyOfRange(control, 28, 256),
                java.util.Arrays.copyOfRange(pairing, 28, 256));
        assertArrayEquals(
                hex("2900000a000040282af9"
                        + "2900000800004033"
                        + "0000000800004036"),
                java.util.Arrays.copyOfRange(
                        pairing,
                        256,
                        pairing.length));
        assertArrayEquals(
                hex("0000011a"),
                java.util.Arrays.copyOfRange(pairing, 24, 28));

        byte[] frame = IkeV2Codec.encodeUikeFrame(pairing);
        assertEquals(
                IkeV2Codec.PAIRING_UIKE_FRAME_LENGTH,
                frame.length);
        assertArrayEquals(
                new byte[]{0x04, 0x01, 0x1a},
                java.util.Arrays.copyOf(frame, 3));
    }

    @Test
    public void wrapsAndIncrementallyDecodesExactUikeFrame() {
        byte[] packet = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);
        byte[] frame = IkeV2Codec.encodeUikeFrame(packet);

        assertEquals(IkeV2Codec.CONTROL_UIKE_FRAME_LENGTH, frame.length);
        assertArrayEquals(
                new byte[]{0x04, 0x01, 0x08},
                new byte[]{frame[0], frame[1], frame[2]});
        int checksumOffset = frame.length - 2;
        int encodedChecksum = ((frame[checksumOffset] & 0xFF) << 8)
                | (frame[checksumOffset + 1] & 0xFF);
        assertEquals(
                IkeV2Codec.internetChecksum(
                        java.util.Arrays.copyOf(frame, checksumOffset)),
                encodedChecksum);

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        assertTrue(decoder.push(
                java.util.Arrays.copyOfRange(frame, 0, 2)).isEmpty());
        assertTrue(decoder.push(
                java.util.Arrays.copyOfRange(frame, 2, 117)).isEmpty());
        List<byte[]> decoded = decoder.push(
                java.util.Arrays.copyOfRange(frame, 117, frame.length));

        assertEquals(1, decoded.size());
        assertArrayEquals(packet, decoded.get(0));
        assertEquals(0, decoder.bufferedLength());
    }

    @Test
    public void retainsBadUikeFrameForForensicLogging() {
        byte[] packet = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);
        byte[] frame = IkeV2Codec.encodeUikeFrame(packet);
        frame[frame.length - 1] ^= 1;

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.push(frame));
        assertEquals(frame.length, decoder.bufferedLength());
        assertArrayEquals(frame, decoder.bufferedBytes());
    }

    @Test
    public void validatesAndSummarizesSaInitResponse() {
        byte[] response = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);
        byte[] responderSpi = hex("11 12 13 14 15 16 17 18");
        System.arraycopy(responderSpi, 0, response, 8, responderSpi.length);
        response[19] = IkeV2Codec.IKE_FLAG_RESPONSE;

        IkeV2Codec.IkePacketSummary summary =
                IkeV2Codec.parseSaInitResponse(response, FIXED_SPI);

        assertArrayEquals(responderSpi, summary.responderSpi);
        assertEquals(0x20, summary.version);
        assertEquals(IkeV2Codec.EXCHANGE_IKE_SA_INIT, summary.exchange);
        assertEquals(IkeV2Codec.IKE_FLAG_RESPONSE, summary.flags);
        assertEquals(0, summary.messageId);
        assertEquals(
                List.of(
                        IkeV2Codec.PAYLOAD_SA,
                        IkeV2Codec.PAYLOAD_KE,
                        IkeV2Codec.PAYLOAD_NONCE,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY),
                summary.payloadTypes);
        assertEquals(
                List.of(
                        IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP,
                        IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP,
                        IkeV2Codec.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                        IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED),
                summary.notifyTypes);
    }

    @Test
    public void rejectsRequestMasqueradingAsSaInitResponse() {
        byte[] request = IkeV2Codec.buildControlSaInit(
                FIXED_SPI,
                FIXED_NONCE,
                FIXED_X448_PUBLIC);
        request[8] = 1;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> IkeV2Codec.parseSaInitResponse(request, FIXED_SPI));
        assertTrue(error.getMessage().contains("not a response"));
    }

    @Test
    public void generatesRealX448KeyMaterialWithoutPlatformProvider() {
        IkeV2Codec.InitiatorState state =
                IkeV2Codec.createControlSaInit(new SecureRandom());
        byte[] derivedPublic = new byte[56];
        X448.generatePublicKey(
                state.x448PrivateKey,
                0,
                derivedPublic,
                0);

        assertFalse(allZero(state.initiatorSpi));
        assertFalse(allZero(state.nonce));
        assertFalse(allZero(state.x448PrivateKey));
        assertFalse(allZero(state.x448PublicKey));
        assertArrayEquals(derivedPublic, state.x448PublicKey);
        assertEquals(IkeV2Codec.CONTROL_SA_INIT_LENGTH, state.ikePacket.length);
        assertEquals(
                IkeV2Codec.CONTROL_UIKE_FRAME_LENGTH,
                state.uikeFrame.length);

        IkeV2Codec.InitiatorState pairing =
                IkeV2Codec.createPairingSaInit(new SecureRandom());
        assertEquals(
                IkeV2Codec.PAIRING_SA_INIT_LENGTH,
                pairing.ikePacket.length);
        assertEquals(
                IkeV2Codec.PAIRING_UIKE_FRAME_LENGTH,
                pairing.uikeFrame.length);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
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
