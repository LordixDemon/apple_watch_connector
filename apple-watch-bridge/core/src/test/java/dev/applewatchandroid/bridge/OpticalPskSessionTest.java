package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OpticalPskSessionTest {
    // Independent Python stdlib hmac/sha512 vectors. Every input is synthetic:
    // key=sequence(0,44), SA_INIT I=sequence(1,264), R=sequence(0x31,210),
    // Ni=sequence(0xa0,32), Nr=sequence(0xc0,32), SKpi/pr=sequence(0x40/80,64),
    // IntAuth I/R=sequence(0xa0/e0,64), IKE_AUTH MID=2, IDi={13,0,0,0}.
    private static final byte[] INITIATOR_MAC = hex(
            "917ee2a91c96bd722ab3a9fa08c2e17b93122cb796e432cda5152fc29ed46c64d"
            + "5fc4e5e81ee7f714cc4511203dbdee7ae3db28030b416d883608305a8cd8d17");
    private static final byte[] RESPONDER_MAC = hex(
            "1be4026e5b0755531d5975949254077b2481f5052c5ef1ecf51d120c07f755a5d"
            + "d4c70394740081718577250f34a425c72f7f083cd0a64a6bfb7313233f35cdd");

    @Test public void signsRequestAndVerifiesIndependentResponderVector() {
        try (Fixture f = new Fixture()) {
            List<byte[]> packets = f.session.request(new SecureRandom());
            assertEquals(1, packets.size());
            IkeV2SessionCrypto.DecryptedIntermediatePart part =
                    IkeV2SessionCrypto.decryptProtectedPacketWithDirectionFlags(
                            packets.get(0), f.keys.initiatorSpi, f.keys.responderSpi,
                            f.keys.skEi, 8, 35, 2);
            assertEquals(35, part.firstInnerPayload);
            assertArrayEquals(new byte[]{41, 0, 0, 8, 13, 0, 0, 0},
                    Arrays.copyOf(part.plaintext, 8));
            assertArrayEquals(INITIATOR_MAC, Arrays.copyOfRange(part.plaintext,
                    part.plaintext.length - 64, part.plaintext.length));
            assertFalse(f.session.authenticated());
            IkeV2SessionCrypto.IkeAuthResponse result = f.session.acceptResponse(
                    f.peer(RESPONDER_MAC, 2, f.id(), 2, 1000).get(0));
            assertNotNull(result);
            assertTrue(f.session.authenticated());
            assertThrows(IllegalStateException.class, () -> f.session.request(new SecureRandom()));
            assertThrows(IllegalStateException.class, () -> f.session.acceptResponse(packets.get(0)));
        }
    }

    @Test public void rejectsWrongMacIdentityMethodAndMessageId() {
        for (int mutation = 0; mutation < 5; mutation++) {
            try (Fixture f = new Fixture()) {
                f.session.request(new SecureRandom());
                byte[] mac = RESPONDER_MAC.clone(), id = f.id();
                int method = 2, message = 2;
                if (mutation == 0) mac[0] ^= 1;
                if (mutation == 1) id[id.length - 1] ^= 1;
                if (mutation == 2) method = 13;
                if (mutation == 3) message = 4;
                byte[] packet = f.peer(mac, method, id, message, 1000).get(0);
                if (mutation == 4) packet[packet.length - 1] ^= 1;
                assertThrows(IllegalArgumentException.class, () -> f.session.acceptResponse(packet));
                assertFalse(f.session.authenticated());
            }
        }
    }

    @Test public void requiresBoundSecretAndIntermediateTranscripts() {
        for (boolean changeSecret : new boolean[]{true, false}) {
            try (Fixture f = new Fixture()) {
                byte[] secret = sequence(0, 44), intAuth = sequence(0xa0, 64);
                if (changeSecret) secret[43] ^= 1; else intAuth[0] ^= 1;
                try (OpticalPskSession changed = new OpticalPskSession(f.initiator, f.response,
                        f.keys, intAuth, sequence(0xe0, 64), secret)) {
                    changed.request(new SecureRandom());
                    byte[] packet = f.peer(RESPONDER_MAC, 2, f.id(), 2, 1000).get(0);
                    assertThrows(IllegalArgumentException.class, () -> changed.acceptResponse(packet));
                    assertFalse(changed.authenticated());
                }
            }
        }
    }

    @Test public void snapshotsTranscriptsAndAcceptsOutOfOrderFragmentRetransmission() {
        try (Fixture f = new Fixture()) {
            Arrays.fill(f.initiator.ikePacket, (byte) 0);
            Arrays.fill(f.response.packet, (byte) 0);
            f.session.request(new SecureRandom());
            List<byte[]> packets = f.peer(RESPONDER_MAC, 2, f.id(), 2, 110);
            assertTrue(packets.size() > 1);
            int last = packets.size() - 1;
            assertNull(f.session.acceptResponse(packets.get(last)));
            assertNull(f.session.acceptResponse(packets.get(last)));
            for (int i = last - 1; i >= 0; i--) {
                assertEquals(i == 0, f.session.acceptResponse(packets.get(i)) != null);
            }
            assertTrue(f.session.authenticated());
        }
    }

    @Test public void closePreventsRequestsAndResponsesWithoutDestroyingTransportKeys() {
        try (Fixture f = new Fixture()) {
            byte[] key = f.keys.skEi.clone();
            f.session.close();
            assertFalse(f.session.authenticated());
            assertArrayEquals(key, f.keys.skEi);
            assertThrows(IllegalStateException.class, () -> f.session.request(new SecureRandom()));
            assertThrows(IllegalStateException.class, () -> f.session.acceptResponse(new byte[0]));
        }
    }

    @Test public void controlRequestSelectsPskWithoutPinSalt() {
        try (Fixture f = new Fixture()) {
            byte[] packet = OpticalPskSession.controlMethodRequest(new SecureRandom(), f.keys).get(0);
            IkeV2SessionCrypto.DecryptedIntermediatePart part =
                    IkeV2SessionCrypto.decryptProtectedPacketWithDirectionFlags(packet,
                            f.keys.initiatorSpi, f.keys.responderSpi, f.keys.skEi, 8, 37, 3);
            assertEquals(41, part.firstInnerPayload);
            assertArrayEquals(hex("0000000c0000c54501000101"), part.plaintext);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final IkeV2Codec.InitiatorState initiator = new IkeV2Codec.InitiatorState(
                sequence(0x10, 8), sequence(0xa0, 32), new byte[56], new byte[56],
                sequence(1, 264), new byte[0]);
        final IkeV2SessionCrypto.ControlSaInitResponse response =
                new IkeV2SessionCrypto.ControlSaInitResponse(sequence(0x30, 8),
                        sequence(0xc0, 32), new byte[56], sequence(0x31, 210));
        final IkeV2SessionCrypto.IkeSaKeys keys = new IkeV2SessionCrypto.IkeSaKeys(
                sequence(0, 64), sequence(0x20, 36), sequence(0x50, 36),
                sequence(0x40, 64), sequence(0x80, 64), initiator.initiatorSpi,
                response.responderSpi, initiator.nonce, response.responderNonce);
        final OpticalPskSession session = new OpticalPskSession(initiator, response, keys,
                sequence(0xa0, 64), sequence(0xe0, 64), sequence(0, 44));
        byte[] id() {
            return concat(new byte[]{11, 0, 0, 0},
                    OpticalPskSession.KEY_ID.getBytes(StandardCharsets.US_ASCII));
        }
        List<byte[]> peer(byte[] mac, int method, byte[] id, int message, int maxSize) {
            byte[] plaintext = concat(payload(39, id), payload(0,
                    concat(new byte[]{(byte) method, 0, 0, 0}, mac)));
            return IkeV2SessionCrypto.encryptProtectedPayload(new SecureRandom(),
                    keys.initiatorSpi, keys.responderSpi, keys.skEr, true, 35, message,
                    plaintext, 36, maxSize);
        }
        @Override public void close() { session.close(); keys.destroy(); }
    }
    private static byte[] sequence(int start, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (start + i);
        return bytes;
    }
    private static byte[] payload(int next, byte[] body) {
        return concat(new byte[]{(byte) next, 0, (byte) ((body.length + 4) >>> 8),
                (byte) (body.length + 4)}, body);
    }
    private static byte[] concat(byte[]... arrays) {
        int length = 0; for (byte[] bytes : arrays) length += bytes.length;
        byte[] result = new byte[length]; int at = 0;
        for (byte[] bytes : arrays) {
            System.arraycopy(bytes, 0, result, at, bytes.length); at += bytes.length;
        }
        return result;
    }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] =
                (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
