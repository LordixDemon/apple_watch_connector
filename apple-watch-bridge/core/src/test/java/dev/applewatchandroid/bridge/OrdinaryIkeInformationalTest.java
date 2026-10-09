package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OrdinaryIkeInformationalTest {
    private static final int LINK_DIRECTOR_NOTIFY = 0xc60e;

    @Test
    public void ikeDeleteAuthenticatesInBothDirectionsWithEmptySpiList() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys = testKeys();
        try {
            for (var role : NrLinkBluetoothPrelude.LocalRole.values()) {
                boolean initiator = role == NrLinkBluetoothPrelude.LocalRole.INITIATOR;
                byte[] packet = OrdinaryIkeInformational.createIkeDeleteRequest(random, role, keys, 9);
                byte[] tampered = packet.clone();
                tampered[tampered.length - 1] ^= 1;
                try {
                    var decrypted = IkeV2SessionCrypto.decryptProtectedPacketWithDirectionFlags(
                            packet, keys.initiatorSpi, keys.responderSpi,
                            initiator ? keys.skEi : keys.skEr,
                            initiator ? IkeV2Codec.IKE_FLAG_INITIATOR : 0,
                            IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL, 9);
                    assertEquals(42, decrypted.firstInnerPayload);
                    org.junit.Assert.assertArrayEquals(new byte[] {0, 0, 0, 8, 1, 0, 0, 0}, decrypted.plaintext);
                    wipe(decrypted.plaintext);
                    assertThrows(RuntimeException.class, () ->
                            IkeV2SessionCrypto.decryptProtectedPacketWithDirectionFlags(
                                    tampered, keys.initiatorSpi, keys.responderSpi,
                                    initiator ? keys.skEi : keys.skEr,
                                    initiator ? IkeV2Codec.IKE_FLAG_INITIATOR : 0,
                                    IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL, 9));
                } finally { wipe(packet); wipe(tampered); }
            }
        } finally { keys.destroy(); }
    }

    @Test
    public void emitsAuthenticatedAnnouncementsForBothIkeRolesAndRejectsTampering() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys = testKeys();
        try {
            for (NrLinkBluetoothPrelude.LocalRole sender : NrLinkBluetoothPrelude.LocalRole.values()) {
                NrLinkBluetoothPrelude.LocalRole peer = sender == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                        ? NrLinkBluetoothPrelude.LocalRole.RESPONDER : NrLinkBluetoothPrelude.LocalRole.INITIATOR;
                for (int type : new int[] {LinkDirectorMessageCodec.HELLO, LinkDirectorMessageCodec.DEVICE_LINK_STATE}) {
                    OrdinaryIkeInformational.LinkDirectorRequest request = OrdinaryIkeInformational
                            .createLinkDirectorRequest(random, sender, keys, 8, 12345, type);
                    OrdinaryIkeInformational.PostConnectResponse ack = null;
                    byte[] tampered = request.packet.clone();
                    tampered[tampered.length - 1] ^= 1;
                    try {
                        assertThrows(RuntimeException.class, () -> OrdinaryIkeInformational
                                .authenticatePostConnectRequestAndCreateResponse(random, peer, keys, tampered, 8));
                        ack = OrdinaryIkeInformational.authenticatePostConnectRequestAndCreateResponse(
                                random, peer, keys, request.packet, 8);
                        assertEquals(OrdinaryIkeInformational.PostConnectRequestKind.LINK_DIRECTOR, ack.requestKind);
                        assertFalse(ack.peerClassCUnlocked);
                        assertTrue(ack.linkDirectorStructure.contains("type=" + type + " length=" + (type == 1 ? 0 : 1)));
                        OrdinaryIkeInformational.authenticatePostConnectResponse(sender, keys, ack.packet, 8);
                    } finally {
                        request.destroy();
                        if (ack != null) ack.destroy();
                        wipe(tampered);
                    }
                }
            }
        } finally { keys.destroy(); }
    }

    @Test
    public void acknowledgesLinkDirectorThenAcceptsClassCUnlock() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys = testKeys();
        byte[] linkDirector = linkDirectorMessage(
                List.of(tlv(1, new byte[0])));
        byte[] request = protectedResponderRequest(
                random,
                keys,
                0,
                LINK_DIRECTOR_NOTIFY,
                linkDirector);
        OrdinaryIkeInformational.PostConnectResponse response = null;
        OrdinaryIkeInformational.UnlockRequest unlock = null;
        OrdinaryIkeInformational.PostConnectResponse unlockResponse =
                null;
        try {
            response = OrdinaryIkeInformational
                    .authenticatePostConnectRequestAndCreateResponse(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                            keys,
                            request,
                            0);
            assertEquals(
                    OrdinaryIkeInformational
                            .PostConnectRequestKind.LINK_DIRECTOR,
                    response.requestKind);
            assertFalse(response.peerClassCUnlocked);
            assertEquals(
                    "version=2 tlvBytes=3 tlvCount=1 "
                            + "entries=[{type=1 length=0}]; "
                            + "identifier/value bytes logged=false",
                    response.linkDirectorStructure);
            OrdinaryIkeInformational.authenticatePostConnectResponse(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    keys,
                    response.packet,
                    0);

            unlock = OrdinaryIkeInformational
                    .createClassCUnlockRequest(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                            keys,
                            1,
                            true);
            unlockResponse = OrdinaryIkeInformational
                    .authenticatePostConnectRequestAndCreateResponse(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                            keys,
                            unlock.packet,
                            1);
            assertEquals(
                    OrdinaryIkeInformational
                            .PostConnectRequestKind.CLASS_C_UNLOCK,
                    unlockResponse.requestKind);
            assertTrue(unlockResponse.peerClassCUnlocked);
            OrdinaryIkeInformational.authenticatePostConnectResponse(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    keys,
                    unlockResponse.packet,
                    1);
        } finally {
            if (response != null) {
                response.destroy();
            }
            if (unlock != null) {
                unlock.destroy();
            }
            if (unlockResponse != null) {
                unlockResponse.destroy();
            }
            wipe(linkDirector);
            wipe(request);
            keys.destroy();
        }
    }

    @Test
    public void acceptsRecoveredAndOpaqueLinkDirectorTlvs() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys = testKeys();
        List<byte[]> tlvs = new ArrayList<>();
        tlvs.add(tlv(1, new byte[0]));
        tlvs.add(tlv(2, sequence(0x10, 18)));
        tlvs.add(tlv(3, sequence(0x30, 6)));
        tlvs.add(tlv(4, sequence(0x40, 64)));
        tlvs.add(tlv(5, new byte[]{1}));
        tlvs.add(tlv(6, new byte[]{1}));
        tlvs.add(tlv(7, new byte[]{1}));
        tlvs.add(tlv(8, new byte[0]));
        tlvs.add(tlv(9, sequence(0x50, 18)));
        tlvs.add(tlv(10, new byte[]{1}));
        tlvs.add(tlv(11, sequence(0x60, 8)));
        tlvs.add(tlv(12, sequence(0x70, 24)));
        tlvs.add(tlv(13, sequence(0x20, 9)));
        tlvs.add(tlv(14, new byte[]{10}));
        tlvs.add(tlv(15, new byte[]{'U', 'S'}));
        tlvs.add(tlv(16, sequence(0x30, 12)));
        tlvs.add(tlv(17, sequence(0x40, 12)));
        tlvs.add(tlv(18, new byte[]{1}));
        tlvs.add(tlv(19, sequence(0x50, 12)));
        tlvs.add(tlv(20, sequence(0x60, 16)));
        tlvs.add(tlv(21, sequence(0x70, 16)));
        tlvs.add(tlv(22, new byte[]{1}));
        tlvs.add(tlv(23, sequence(0x20, 32)));
        tlvs.add(tlv(0xff, sequence(0x30, 3)));
        tlvs.add(tlv(0xfe, new byte[0]));
        tlvs.add(tlv(6, new byte[]{3}));
        tlvs.add(tlv(4, new byte[0]));
        byte[] linkDirector = linkDirectorMessage(tlvs);
        byte[] request = protectedResponderRequest(
                random,
                keys,
                0,
                LINK_DIRECTOR_NOTIFY,
                linkDirector);
        OrdinaryIkeInformational.PostConnectResponse response = null;
        try {
            response = OrdinaryIkeInformational
                    .authenticatePostConnectRequestAndCreateResponse(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                            keys,
                            request,
                            0);
            assertEquals(
                    OrdinaryIkeInformational
                            .PostConnectRequestKind.LINK_DIRECTOR,
                    response.requestKind);
            assertTrue(response.linkDirectorStructure.contains(
                    "{type=14 length=1}"));
            assertTrue(response.linkDirectorStructure.contains(
                    "{type=254 length=0}"));
        } finally {
            if (response != null) {
                response.destroy();
            }
            for (byte[] tlv : tlvs) {
                wipe(tlv);
            }
            wipe(linkDirector);
            wipe(request);
            keys.destroy();
        }
    }

    @Test
    public void rejectsMalformedLinkDirectorMessages() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys = testKeys();
        List<byte[]> invalid = new ArrayList<>();
        try {
            byte[] wrongVersion = linkDirectorMessage(
                    List.of(tlv(1, new byte[0])));
            wrongVersion[0] = 1;
            invalid.add(wrongVersion);

            byte[] reservedSet = linkDirectorMessage(
                    List.of(tlv(1, new byte[0])));
            reservedSet[4] = 1;
            invalid.add(reservedSet);

            byte[] wrongDeclaredLength = linkDirectorMessage(
                    List.of(tlv(1, new byte[0])));
            wrongDeclaredLength[3] = 4;
            invalid.add(wrongDeclaredLength);

            invalid.add(linkDirectorMessage(
                    List.of(new byte[]{2, 0, 18})));

            for (int index = 0; index < invalid.size(); index++) {
                int messageId = index;
                byte[] request = protectedResponderRequest(
                        random,
                        keys,
                        messageId,
                        LINK_DIRECTOR_NOTIFY,
                        invalid.get(index));
                try {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> OrdinaryIkeInformational
                                    .authenticatePostConnectRequestAndCreateResponse(
                                            random,
                                            NrLinkBluetoothPrelude
                                                    .LocalRole.INITIATOR,
                                            keys,
                                            request,
                                            messageId));
                } finally {
                    wipe(request);
                }
            }
        } finally {
            for (byte[] message : invalid) {
                wipe(message);
            }
            keys.destroy();
        }
    }

    private static byte[] protectedResponderRequest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId,
            int notifyType,
            byte[] notifyData) {
        byte[] plaintext = notifyPayload(
                notifyType,
                notifyData);
        try {
            List<byte[]> packets = IkeV2SessionCrypto
                    .encryptProtectedPayloadWithDirectionFlags(
                            random,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            keys.skEr,
                            0,
                            IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                            messageId,
                            plaintext,
                            IkeV2Codec.PAYLOAD_NOTIFY,
                            OrdinaryIkeInformational
                                    .MAXIMUM_PACKET_SIZE);
            assertEquals(1, packets.size());
            return packets.get(0).clone();
        } finally {
            wipe(plaintext);
        }
    }

    private static byte[] linkDirectorMessage(
            List<byte[]> tlvs) {
        int payloadLength = 0;
        for (byte[] tlv : tlvs) {
            payloadLength += tlv.length;
        }
        byte[] output = new byte[16 + payloadLength];
        output[0] = 2;
        putBe16(output, 2, payloadLength);
        byte[] identifier = sequence(0x70, 8);
        try {
            System.arraycopy(identifier, 0, output, 8, 8);
        } finally {
            wipe(identifier);
        }
        int offset = 16;
        for (byte[] tlv : tlvs) {
            System.arraycopy(tlv, 0, output, offset, tlv.length);
            offset += tlv.length;
        }
        return output;
    }

    private static byte[] tlv(
            int type,
            byte[] value) {
        byte[] output = new byte[3 + value.length];
        output[0] = (byte) type;
        putBe16(output, 1, value.length);
        System.arraycopy(value, 0, output, 3, value.length);
        return output;
    }

    private static byte[] notifyPayload(
            int type,
            byte[] data) {
        byte[] output = new byte[8 + data.length];
        putBe16(output, 2, output.length);
        putBe16(output, 6, type);
        System.arraycopy(data, 0, output, 8, data.length);
        return output;
    }

    private static IkeV2SessionCrypto.IkeSaKeys testKeys() {
        return new IkeV2SessionCrypto.IkeSaKeys(
                sequence(0x00, 64),
                sequence(0x20, 36),
                sequence(0x50, 36),
                sequence(0x40, 64),
                sequence(0x80, 64),
                sequence(0x10, 8),
                sequence(0x30, 8),
                sequence(0xa0, 32),
                sequence(0xc0, 32));
    }

    private static byte[] sequence(
            int start,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < length; index++) {
            output[index] = (byte) (start + index);
        }
        return output;
    }

    private static void putBe16(
            byte[] output,
            int offset,
            int value) {
        output[offset] = (byte) (value >>> 8);
        output[offset + 1] = (byte) value;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
