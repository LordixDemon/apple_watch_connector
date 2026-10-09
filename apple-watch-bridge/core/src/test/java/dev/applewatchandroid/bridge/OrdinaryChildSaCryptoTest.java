package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public final class OrdinaryChildSaCryptoTest {
    @Test
    public void derivesExactFirstChildKeymat() throws Exception {
        IkeV2SessionCrypto.IkeSaKeys ikeKeys =
                deterministicIkeKeys();
        byte[] initiatorSpi =
                hex("01020304");
        byte[] responderSpi =
                hex("a1a2a3a4");
        OrdinaryChildSaCrypto.ChildSaKeys childKeys =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        initiatorSpi,
                        responderSpi);
        try {
            byte[] seed =
                    concatenate(
                            ikeKeys.initiatorNonce,
                            ikeKeys.responderNonce);
            byte[] first =
                    hmacSha512(
                            ikeKeys.skD,
                            concatenate(
                                    seed,
                                    new byte[]{1}));
            byte[] second =
                    hmacSha512(
                            ikeKeys.skD,
                            concatenate(
                                    first,
                                    seed,
                                    new byte[]{2}));
            byte[] expected =
                    Arrays.copyOf(
                            concatenate(
                                    first,
                                    second),
                            OrdinaryChildSaCrypto.KEYMAT_LENGTH);
            byte[] actual =
                    concatenate(
                            childKeys
                                    .initiatorToResponderKeyMaterial,
                            childKeys
                                    .responderToInitiatorKeyMaterial);
            assertArrayEquals(
                    expected,
                    actual);
            assertEquals(
                    OrdinaryChildSaCrypto
                            .DIRECTION_KEY_MATERIAL_LENGTH,
                    childKeys
                            .initiatorToResponderKeyMaterial
                            .length);
            assertEquals(
                    OrdinaryChildSaCrypto.KEYMAT_LENGTH,
                    actual.length);
            assertFalse(
                    Arrays.equals(
                            childKeys
                                    .initiatorToResponderKeyMaterial,
                            childKeys
                                    .responderToInitiatorKeyMaterial));
            wipe(seed);
            wipe(first);
            wipe(second);
            wipe(expected);
            wipe(actual);
        } finally {
            childKeys.destroy();
            ikeKeys.destroy();
            wipe(initiatorSpi);
            wipe(responderSpi);
        }
    }

    @Test
    public void exchangesIivEspInBothDirectionsAndRejectsReplay() {
        IkeV2SessionCrypto.IkeSaKeys ikeKeys =
                deterministicIkeKeys();
        byte[] initiatorSpi =
                hex("11223344");
        byte[] responderSpi =
                hex("55667788");
        OrdinaryChildSaCrypto.ChildSaKeys initiatorKeys =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        initiatorSpi,
                        responderSpi);
        OrdinaryChildSaCrypto.ChildSaKeys responderKeys =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        initiatorSpi,
                        responderSpi);
        OrdinaryChildSaCrypto.EspSession initiator =
                initiatorKeys.newSession(
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR);
        OrdinaryChildSaCrypto.EspSession responder =
                responderKeys.newSession(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER);
        byte[] payload =
                hex("0102030405");
        try {
            long[] firstSequences = new long[]{
                    0x00000001L,
                    0x40000001L,
                    0x80000001L,
                    0xc0000001L
            };
            for (int trafficClass = 0;
                    trafficClass < 4;
                    trafficClass++) {
                byte[] packet =
                        initiator.encrypt(
                                payload,
                                17,
                                trafficClass);
                assertArrayEquals(
                        responderSpi,
                        Arrays.copyOfRange(
                                packet,
                                0,
                                4));
                assertEquals(
                        firstSequences[trafficClass],
                        unsignedBe32(packet, 4));
                assertEquals(
                        32,
                        packet.length);
                OrdinaryChildSaCrypto.DecryptedEsp decrypted =
                        responder.decrypt(packet);
                try {
                    assertArrayEquals(
                            payload,
                            decrypted.payload);
                    assertEquals(
                            17,
                            decrypted.nextHeader);
                    assertEquals(
                            trafficClass,
                            decrypted.trafficClassIndex);
                } finally {
                    decrypted.destroy();
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> responder.decrypt(packet));
                wipe(packet);
            }

            byte[] reversePacket =
                    responder.encrypt(
                            payload,
                            6,
                            0);
            assertArrayEquals(
                    initiatorSpi,
                    Arrays.copyOfRange(
                            reversePacket,
                            0,
                            4));
            OrdinaryChildSaCrypto.DecryptedEsp reverse =
                    initiator.decrypt(
                            reversePacket);
            try {
                assertArrayEquals(
                        payload,
                        reverse.payload);
                assertEquals(6, reverse.nextHeader);
            } finally {
                reverse.destroy();
            }

            byte[] validAfterBadTag =
                    responder.encrypt(
                            payload,
                            6,
                            1);
            byte[] badTag =
                    validAfterBadTag.clone();
            badTag[badTag.length - 1] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> initiator.decrypt(badTag));
            OrdinaryChildSaCrypto.DecryptedEsp accepted =
                    initiator.decrypt(
                            validAfterBadTag);
            accepted.destroy();
            wipe(reversePacket);
            wipe(validAfterBadTag);
            wipe(badTag);
        } finally {
            wipe(payload);
            initiator.destroy();
            responder.destroy();
            initiatorKeys.destroy();
            responderKeys.destroy();
            ikeKeys.destroy();
            wipe(initiatorSpi);
            wipe(responderSpi);
        }
    }

    @Test
    public void acceptsAuthenticatedNonCanonicalEspPadding()
            throws Exception {
        IkeV2SessionCrypto.IkeSaKeys ikeKeys =
                deterministicIkeKeys();
        byte[] initiatorSpi = hex("11223344");
        byte[] responderSpi = hex("55667788");
        OrdinaryChildSaCrypto.ChildSaKeys responderKeys =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        initiatorSpi,
                        responderSpi);
        OrdinaryChildSaCrypto.EspSession responder =
                responderKeys.newSession(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER);
        byte[] payload = hex("0102030405");
        byte[] packet = null;
        byte[] tampered = null;
        try {
            packet = authenticatedEspPacket(
                    responderSpi,
                    responderKeys
                            .initiatorToResponderKeyMaterial,
                    1,
                    payload,
                    17,
                    new byte[]{0});
            OrdinaryChildSaCrypto.DecryptedEsp decrypted =
                    responder.decrypt(packet);
            try {
                assertArrayEquals(payload, decrypted.payload);
                assertEquals(17, decrypted.nextHeader);
            } finally {
                decrypted.destroy();
            }

            OrdinaryChildSaCrypto.ChildSaKeys freshKeys =
                    OrdinaryChildSaCrypto.derive(
                            ikeKeys,
                            initiatorSpi,
                            responderSpi);
            OrdinaryChildSaCrypto.EspSession freshResponder =
                    freshKeys.newSession(
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER);
            try {
                tampered = packet.clone();
                tampered[tampered.length - 1] ^= 1;
                byte[] rejectedPacket = tampered;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> freshResponder.decrypt(rejectedPacket));
            } finally {
                freshResponder.destroy();
                freshKeys.destroy();
            }
        } finally {
            wipe(payload);
            wipe(packet);
            wipe(tampered);
            responder.destroy();
            responderKeys.destroy();
            ikeKeys.destroy();
            wipe(initiatorSpi);
            wipe(responderSpi);
        }
    }

    @Test
    public void roundTripsBaselineIpv6TransportAndMapsDscp() {
        assertEquals(
                0,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(0));
        assertEquals(
                0,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(18));
        assertEquals(
                1,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(8));
        assertEquals(
                2,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(26));
        assertEquals(
                2,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(40));
        assertEquals(
                3,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(46));
        assertEquals(
                3,
                OrdinaryChildSaCrypto
                        .trafficClassIndexForDscp(48));

        IkeV2SessionCrypto.IkeSaKeys ikeKeys =
                deterministicIkeKeys();
        OrdinaryChildSaCrypto.ChildSaKeys first =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        hex("01010101"),
                        hex("02020202"));
        OrdinaryChildSaCrypto.ChildSaKeys second =
                OrdinaryChildSaCrypto.derive(
                        ikeKeys,
                        hex("01010101"),
                        hex("02020202"));
        OrdinaryChildSaCrypto.EspSession initiator =
                first.newSession(
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR);
        OrdinaryChildSaCrypto.EspSession responder =
                second.newSession(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER);
        byte[] ipv6 =
                baselineIpv6UdpPacket();
        try {
            assertEquals(
                    3,
                    OrdinaryChildSaCrypto
                            .trafficClassIndexForIpv6Packet(
                                    ipv6));
            byte[] protectedPacket =
                    initiator.encryptIpv6Transport(
                            ipv6);
            assertTrue(
                    responder.matchesInboundSpi(
                            protectedPacket,
                            40));
            assertFalse(
                    initiator.matchesInboundSpi(
                            protectedPacket,
                            40));
            assertFalse(
                    responder.matchesInboundSpi(
                            protectedPacket,
                            protectedPacket.length));
            assertEquals(
                    OrdinaryChildSaCrypto
                            .ESP_PROTOCOL_NUMBER,
                    protectedPacket[6] & 0xff);
            assertEquals(
                    0xc0000001L,
                    unsignedBe32(
                            protectedPacket,
                            44));
            byte[] recovered =
                    responder.decryptIpv6Transport(
                            protectedPacket);
            assertArrayEquals(
                    ipv6,
                    recovered);

            byte[] mutated =
                    protectedPacket.clone();
            mutated[mutated.length - 1] ^= 1;
            OrdinaryChildSaCrypto.ChildSaKeys third =
                    OrdinaryChildSaCrypto.derive(
                            ikeKeys,
                            hex("01010101"),
                            hex("02020202"));
            OrdinaryChildSaCrypto.EspSession freshResponder =
                    third.newSession(
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> freshResponder
                                .decryptIpv6Transport(
                                        mutated));
            } finally {
                freshResponder.destroy();
                third.destroy();
            }
            wipe(protectedPacket);
            wipe(recovered);
            wipe(mutated);
        } finally {
            wipe(ipv6);
            initiator.destroy();
            responder.destroy();
            first.destroy();
            second.destroy();
            ikeKeys.destroy();
        }
    }

    private static IkeV2SessionCrypto.IkeSaKeys
            deterministicIkeKeys() {
        return new IkeV2SessionCrypto.IkeSaKeys(
                range(0x00, 64),
                range(0x40, 36),
                range(0x64, 36),
                range(0x88, 64),
                range(0xc8, 64),
                range(0x10, 8),
                range(0x20, 8),
                range(0x30, 32),
                range(0x50, 32));
    }

    private static byte[] baselineIpv6UdpPacket() {
        byte[] udp =
                hex("12345678000c0000deadbeef");
        byte[] packet =
                new byte[40 + udp.length];
        int trafficClass = 46 << 2;
        packet[0] =
                (byte) (0x60
                        | (trafficClass >>> 4));
        packet[1] =
                (byte) (trafficClass << 4);
        packet[4] =
                (byte) (udp.length >>> 8);
        packet[5] =
                (byte) udp.length;
        packet[6] = 17;
        packet[7] = 64;
        packet[8] = (byte) 0xfd;
        packet[23] = 1;
        packet[24] = (byte) 0xfd;
        packet[39] = 2;
        System.arraycopy(
                udp,
                0,
                packet,
                40,
                udp.length);
        wipe(udp);
        return packet;
    }

    private static byte[] hmacSha512(
            byte[] key,
            byte[] data)
            throws Exception {
        Mac mac =
                Mac.getInstance(
                        "HmacSHA512");
        mac.init(
                new SecretKeySpec(
                        key,
                        "HmacSHA512"));
        return mac.doFinal(data);
    }

    private static byte[] authenticatedEspPacket(
            byte[] spi,
            byte[] keyMaterial,
            long sequence,
            byte[] payload,
            int nextHeader,
            byte[] padding)
            throws Exception {
        byte[] aad = new byte[8];
        byte[] nonce = new byte[12];
        byte[] plaintext =
                new byte[payload.length + padding.length + 2];
        byte[] key =
                Arrays.copyOf(
                        keyMaterial,
                        OrdinaryChildSaCrypto.AES_KEY_LENGTH);
        byte[] ciphertext = null;
        try {
            System.arraycopy(spi, 0, aad, 0, spi.length);
            writeBe32(aad, 4, sequence);
            System.arraycopy(
                    keyMaterial,
                    OrdinaryChildSaCrypto.AES_KEY_LENGTH,
                    nonce,
                    0,
                    OrdinaryChildSaCrypto.SALT_LENGTH);
            writeBe32(nonce, 8, sequence);
            System.arraycopy(
                    payload,
                    0,
                    plaintext,
                    0,
                    payload.length);
            System.arraycopy(
                    padding,
                    0,
                    plaintext,
                    payload.length,
                    padding.length);
            plaintext[plaintext.length - 2] =
                    (byte) padding.length;
            plaintext[plaintext.length - 1] =
                    (byte) nextHeader;

            Cipher cipher =
                    Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            ciphertext = cipher.doFinal(plaintext);
            return concatenate(aad, ciphertext);
        } finally {
            wipe(aad);
            wipe(nonce);
            wipe(plaintext);
            wipe(key);
            wipe(ciphertext);
        }
    }

    private static byte[] concatenate(
            byte[]... values) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    private static byte[] range(
            int first,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0;
                index < length;
                index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
    }

    private static long unsignedBe32(
            byte[] value,
            int offset) {
        return ((long) (value[offset] & 0xff) << 24)
                | ((long) (value[offset + 1] & 0xff) << 16)
                | ((long) (value[offset + 2] & 0xff) << 8)
                | (long) (value[offset + 3] & 0xff);
    }

    private static void writeBe32(
            byte[] output,
            int offset,
            long value) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replace(" ", "");
        byte[] output =
                new byte[compact.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
