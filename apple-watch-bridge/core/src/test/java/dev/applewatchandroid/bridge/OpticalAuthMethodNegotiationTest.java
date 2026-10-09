package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.security.SecureRandom;
import java.util.List;
import org.junit.Test;

public final class OpticalAuthMethodNegotiationTest {
    @Test public void directSelectionNeedsNoPinSalt() {
        IkeV2SessionCrypto.IkeSaKeys keys = keys();
        try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
            assertEquals(Integer.valueOf(-1), n.accept(response(keys, 0x20, 3, new byte[]{1, 0, 1, 1}, 1000).get(0)));
            assertTrue(n.pskSelected());
            assertFalse(n.requestAcknowledged());
        } finally { keys.destroy(); }
    }

    @Test public void emptyAckOnlyAdvancesTransportThenWatchRequestSelectsPsk() {
        IkeV2SessionCrypto.IkeSaKeys keys = keys();
        try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
            byte[] ack = IkeV2SessionCrypto.createPinAuthMethodEmptyResponseForTest(new SecureRandom(), keys).get(0);
            assertNull(n.accept(ack));
            assertNull(n.accept(ack));
            assertTrue(n.requestAcknowledged());
            assertFalse(n.pskSelected());
            assertEquals(Integer.valueOf(0), n.accept(response(keys, 0, 0, new byte[]{1, 0, 1, 1}, 1000).get(0)));
            assertTrue(n.pskSelected());
        } finally { keys.destroy(); }
    }

    @Test public void rejectsWrongMethodDuplicateSaltTruncatedTlvAndAeadTamper() {
        for (byte[] tlvs : new byte[][]{
                {1, 0, 1, 2}, {1, 0, 1, 1, 1, 0, 1, 1},
                {1, 0, 1, 1, 2, 0, 1, 0}, {1, 0, 2, 1}, {9, 0, 0}}) {
            IkeV2SessionCrypto.IkeSaKeys keys = keys();
            try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
                byte[] packet = response(keys, 0x20, 3, tlvs, 1000).get(0);
                assertThrows(IllegalArgumentException.class, () -> n.accept(packet));
                assertFalse(n.pskSelected());
            } finally { keys.destroy(); }
        }
        IkeV2SessionCrypto.IkeSaKeys keys = keys();
        try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
            byte[] packet = response(keys, 0x20, 3, new byte[]{1, 0, 1, 1}, 1000).get(0);
            packet[packet.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> n.accept(packet));
            assertFalse(n.pskSelected());
        } finally { keys.destroy(); }
    }

    @Test public void acceptsReorderedFragmentsButRequiresAckForPeerRequest() {
        IkeV2SessionCrypto.IkeSaKeys keys = keys();
        try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
            byte[] peer = response(keys, 0, 0, new byte[]{1, 0, 1, 1}, 1000).get(0);
            assertThrows(IllegalArgumentException.class, () -> n.accept(peer));
            byte[] tlvs = new byte[104]; // Auth method plus an unknown optional TLV.
            System.arraycopy(new byte[]{1, 0, 1, 1, 9, 0, 97}, 0, tlvs, 0, 7);
            List<byte[]> packets = response(keys, 0x20, 3, tlvs, 110);
            assertTrue(packets.size() > 1);
            int last = packets.size() - 1;
            assertNull(n.accept(packets.get(last)));
            assertNull(n.accept(packets.get(last)));
            for (int i = last - 1; i >= 0; i--) {
                assertEquals(i == 0, n.accept(packets.get(i)) != null);
            }
            assertTrue(n.pskSelected());
        } finally { keys.destroy(); }
    }

    @Test public void rejectsClosedNegotiation() {
        IkeV2SessionCrypto.IkeSaKeys keys = keys();
        try (OpticalAuthMethodNegotiation n = new OpticalAuthMethodNegotiation(keys)) {
            n.close();
            assertFalse(n.pskSelected());
            assertThrows(IllegalStateException.class, () -> n.accept(new byte[0]));
        } finally { keys.destroy(); }
    }

    private static List<byte[]> response(IkeV2SessionCrypto.IkeSaKeys keys,
            int direction, int mid, byte[] tlvs, int maxSize) {
        byte[] plaintext = new byte[8 + tlvs.length];
        plaintext[2] = (byte) (plaintext.length >>> 8);
        plaintext[3] = (byte) plaintext.length;
        plaintext[6] = (byte) 0xc5; plaintext[7] = 0x46;
        System.arraycopy(tlvs, 0, plaintext, 8, tlvs.length);
        return IkeV2SessionCrypto.encryptProtectedPayloadWithDirectionFlags(new SecureRandom(),
                keys.initiatorSpi, keys.responderSpi, keys.skEr, direction, 37, mid,
                plaintext, 41, maxSize);
    }
    private static IkeV2SessionCrypto.IkeSaKeys keys() {
        return new IkeV2SessionCrypto.IkeSaKeys(new byte[64], new byte[36], new byte[36],
                new byte[64], new byte[64], new byte[8], new byte[8], new byte[32], new byte[32]);
    }
}
