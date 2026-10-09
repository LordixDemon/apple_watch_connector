package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OrdinaryClassDSessionTest {
    @Test
    public void drivesBothElectedRolesThroughEspReady() {
        SecureRandom random = new SecureRandom();
        AppleNetworkRelayPairingMaterial initiatorMaterial =
                AppleNetworkRelayPairingMaterial.generate(random);
        AppleNetworkRelayPairingMaterial responderMaterial =
                AppleNetworkRelayPairingMaterial.generate(random);
        OrdinaryIkeAuth.PeerMaterial initiatorPeer =
                peerFrom(responderMaterial);
        OrdinaryIkeAuth.PeerMaterial responderPeer =
                peerFrom(initiatorMaterial);

        byte[] initiatorUuid = new byte[16];
        byte[] responderUuid = new byte[16];
        initiatorUuid[15] = 1;
        responderUuid[15] = 2;
        byte[] initiatorPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        initiatorUuid,
                        false,
                        false);
        byte[] responderPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        responderUuid,
                        false,
                        false);
        AppleNetworkRelayInnerAddresses responderAddresses =
                AppleNetworkRelayInnerAddresses.generate(random);
        OrdinaryIkeAuth.ResponderProfile responderProfile =
                new OrdinaryIkeAuth.ResponderProfile(
                        responderAddresses,
                        responderPrelude,
                        "Apple Watch",
                        "23U67x",
                        "12345678-1234-1234-1234-123456789012",
                        2,
                        true,
                        true);
        OrdinaryIkeAuth.ResponderProfile initiatorProfile =
                new OrdinaryIkeAuth.ResponderProfile(
                        responderAddresses,
                        initiatorPrelude,
                        "OnePlus 13",
                        "CPH2655",
                        null,
                        1,
                        true,
                        true);

        OrdinaryClassDSession initiator =
                OrdinaryClassDSession.create(
                        random,
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        initiatorMaterial,
                        initiatorPeer,
                        initiatorPrelude,
                        responderPrelude,
                        initiatorProfile);
        OrdinaryClassDSession responder =
                OrdinaryClassDSession.create(
                        random,
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                        responderMaterial,
                        responderPeer,
                        responderPrelude,
                        initiatorPrelude,
                        responderProfile);
        OrdinaryClassDSession.Outbound outbound = null;
        try {
            assertEquals(
                    OrdinaryClassDSession.Phase.INITIATOR_READY,
                    initiator.phase());
            assertEquals(
                    OrdinaryClassDSession.Phase
                            .WAITING_FOR_SA_INIT_REQUEST,
                    responder.phase());

            outbound = initiator.start();
            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_SA_INIT,
                    OrdinaryIkeSaInit.REQUEST_LENGTH);
            byte[] saInitRequest =
                    outbound.ikePacket.clone();
            OrdinaryClassDSession.Outbound next =
                    responder.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_SA_INIT,
                    OrdinaryIkeSaInit.RESPONSE_LENGTH);
            byte[] saInitResponse =
                    outbound.ikePacket.clone();
            OrdinaryClassDSession.Outbound saInitReplay =
                    responder.acceptInboundIke(
                            saInitRequest);
            try {
                assertTrue(
                        responder.lastInboundWasRetransmission());
                assertEquals(
                        OrdinaryClassDSession.Phase
                                .WAITING_FOR_INTERMEDIATE_REQUEST,
                        responder.phase());
                assertArrayEquals(
                        saInitResponse,
                        saInitReplay.ikePacket);
            } finally {
                saInitReplay.destroy();
                wipe(saInitRequest);
            }
            next =
                    initiator.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertEquals(
                    OrdinaryIkeSaInit.CAPABILITY_CHILDLESS,
                    initiator.consumeSaInitCapabilityFlags());
            assertEquals(
                    0,
                    initiator.consumeSaInitCapabilityFlags());

            assertNull(
                    initiator.acceptInboundIke(
                            saInitResponse));
            assertTrue(
                    initiator.lastInboundWasRetransmission());
            assertEquals(
                    OrdinaryClassDSession.Phase
                            .WAITING_FOR_INTERMEDIATE_RESPONSE,
                    initiator.phase());
            byte[] changedSaInitResponse =
                    saInitResponse.clone();
            changedSaInitResponse[
                    changedSaInitResponse.length - 1] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> initiator.acceptInboundIke(
                            changedSaInitResponse));
            wipe(changedSaInitResponse);
            wipe(saInitResponse);

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_INTERMEDIATE,
                    OrdinaryIkeIntermediate.REQUEST_PACKET_LENGTH);
            next =
                    responder.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_INTERMEDIATE,
                    OrdinaryIkeIntermediate.RESPONSE_PACKET_LENGTH);
            next =
                    initiator.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_AUTH,
                    OrdinaryIkeAuth.REQUEST_PACKET_LENGTH);
            next =
                    responder.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertTrue(responder.childSaEstablished());
            assertFalse(responder.established());
            assertFalse(initiator.established());

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_AUTH,
                    712);
            next =
                    initiator.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertTrue(initiator.childSaEstablished());
            assertFalse(initiator.established());

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.INFORMATIONAL,
                    189);
            next =
                    responder.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertTrue(responder.established());

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.INFORMATIONAL,
                    OrdinaryIkeInformational
                            .EMPTY_RESPONSE_PACKET_LENGTH);
            assertNull(
                    initiator.acceptInboundIke(
                            outbound.ikePacket));
            outbound.destroy();
            outbound = null;
            assertTrue(initiator.established());
            assertTrue(initiator.remoteClassCUnlocked());
            assertTrue(responder.remoteClassCUnlocked());

            outbound =
                    responder.announceLocalClassCUnlocked();
            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.INFORMATIONAL,
                    66);
            assertEquals(
                    0,
                    be32(outbound.ikePacket, 20));
            assertEquals(
                    0,
                    outbound.ikePacket[19] & 0x28);
            next = initiator.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertEquals(
                    0x28,
                    outbound.ikePacket[19] & 0x28);
            assertNull(
                    responder.acceptInboundIke(
                            outbound.ikePacket));
            outbound.destroy();
            outbound = null;

            outbound =
                    initiator.announceLocalClassCUnlocked();
            assertEquals(
                    4,
                    be32(outbound.ikePacket, 20));
            assertEquals(
                    0x08,
                    outbound.ikePacket[19] & 0x28);
            byte[] unlockRequest =
                    outbound.ikePacket.clone();
            next = responder.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            byte[] unlockResponse =
                    outbound.ikePacket.clone();
            assertEquals(
                    0x20,
                    outbound.ikePacket[19] & 0x28);
            assertNull(
                    initiator.acceptInboundIke(
                            outbound.ikePacket));
            outbound.destroy();
            outbound = null;

            OrdinaryClassDSession.Outbound replay =
                    responder.acceptInboundIke(
                            unlockRequest);
            try {
                assertArrayEquals(
                        unlockResponse,
                        replay.ikePacket);
            } finally {
                replay.destroy();
                wipe(unlockRequest);
                wipe(unlockResponse);
            }

            // Real protected IKE exchange: discovery waits for both ordinary
            // announcements, retains one message ID through retransmission,
            // and its receipt cannot imply any endpoint or setup completion.
            initiator.enableLinkDirectorAnnouncements(100, ignored -> {});
            byte[] applicationRequest = NativeApplicationServiceDiscovery.request(
                    "test", java.util.UUID.randomUUID(), new byte[]{1,2,3});
            initiator.requestApplicationService(applicationRequest, ignored -> {});
            for (int announcement = 0; announcement < 3; announcement++) {
                outbound = initiator.pollLinkDirectorAnnouncement(1000 + announcement);
                assertTrue(outbound != null);
                byte[] firstSend = outbound.ikePacket.clone();
                assertNull(initiator.pollLinkDirectorAnnouncement(1001 + announcement));
                assertThrows(IllegalStateException.class, initiator::announceLocalClassCUnlocked);
                assertThrows(IllegalStateException.class, initiator::requestIkeDelete);
                if (announcement == 2) {
                    OrdinaryClassDSession.Outbound retry = initiator.pollLinkDirectorAnnouncement(11_002);
                    assertArrayEquals(firstSend, retry.ikePacket);
                    retry.destroy();
                }
                next = responder.acceptInboundIke(outbound.ikePacket);
                assertNull(initiator.acceptInboundIke(next.ikePacket));
                assertNull(initiator.acceptInboundIke(next.ikePacket));
                assertArrayEquals(firstSend, outbound.ikePacket);
                next.destroy(); outbound.destroy(); outbound = null;
            }
            assertTrue(initiator.linkDirectorStateAcknowledged());
            assertTrue(responder.consumeLinkDirectorObservation().contains("type=20"));
            assertNull(initiator.pollLinkDirectorAnnouncement(50_000));
            assertThrows(IllegalStateException.class,
                    () -> initiator.requestApplicationService(applicationRequest, ignored -> {}));

            AppleNetworkRelayInnerAddresses initiatorView =
                    initiator.copyAuthoritativeAddresses();
            AppleNetworkRelayInnerAddresses responderView =
                    responder.copyAuthoritativeAddresses();
            try {
                assertAddressesEqual(
                        responderAddresses,
                        initiatorView);
                assertAddressesEqual(
                        responderAddresses,
                        responderView);
            } finally {
                initiatorView.destroy();
                responderView.destroy();
            }

            byte[] requestPayload =
                    new byte[]{1, 2, 3, 4, 5, 6};
            byte[] esp =
                    initiator.encryptEsp(
                            requestPayload,
                            17,
                            0);
            OrdinaryChildSaCrypto.DecryptedEsp decrypted =
                    responder.decryptEsp(esp);
            try {
                assertArrayEquals(
                        requestPayload,
                        decrypted.payload);
                assertEquals(
                        17,
                        decrypted.nextHeader);
                assertEquals(
                        1L,
                        decrypted.sequence);
            } finally {
                decrypted.destroy();
            }

            byte[] replyPayload =
                    new byte[]{9, 8, 7};
            byte[] replyEsp =
                    responder.encryptEsp(
                            replyPayload,
                            6,
                            3);
            OrdinaryChildSaCrypto.DecryptedEsp reply =
                    initiator.decryptEsp(
                            replyEsp);
            try {
                assertArrayEquals(
                        replyPayload,
                        reply.payload);
                assertEquals(
                        0xc0000001L,
                        reply.sequence);
            } finally {
                reply.destroy();
            }
            wipe(requestPayload);
            wipe(replyPayload);
            wipe(esp);
            wipe(replyEsp);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> initiator.acceptInboundIke(
                            new byte[0]));
        } finally {
            if (outbound != null) {
                outbound.destroy();
            }
            initiator.close();
            responder.close();
            responderAddresses.destroy();
            wipe(initiatorUuid);
            wipe(responderUuid);
            wipe(initiatorPrelude);
            wipe(responderPrelude);
        }
        assertEquals(
                OrdinaryClassDSession.Phase.DESTROYED,
                initiator.phase());
        assertEquals(
                OrdinaryClassDSession.Phase.DESTROYED,
                responder.phase());
    }

    @Test
    public void drivesIndependentClassCWithoutSecondInformational() {
        SecureRandom random = new SecureRandom();
        AppleNetworkRelayPairingMaterial initiatorMaterial =
                AppleNetworkRelayPairingMaterial.generate(random);
        AppleNetworkRelayPairingMaterial responderMaterial =
                AppleNetworkRelayPairingMaterial.generate(random);
        OrdinaryIkeAuth.PeerMaterial initiatorPeer =
                peerFrom(responderMaterial);
        OrdinaryIkeAuth.PeerMaterial responderPeer =
                peerFrom(initiatorMaterial);

        byte[] initiatorUuid = new byte[16];
        byte[] responderUuid = new byte[16];
        initiatorUuid[15] = 1;
        responderUuid[15] = 2;
        byte[] initiatorPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        initiatorUuid,
                        false,
                        false);
        byte[] responderPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        responderUuid,
                        false,
                        false);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(random);
        OrdinaryIkeAuth.ResponderProfile initiatorProfile =
                new OrdinaryIkeAuth.ResponderProfile(
                        addresses,
                        initiatorPrelude,
                        "OnePlus 13",
                        "CPH2655",
                        null,
                        1,
                        true,
                        true);
        OrdinaryIkeAuth.ResponderProfile responderProfile =
                new OrdinaryIkeAuth.ResponderProfile(
                        addresses,
                        responderPrelude,
                        "Apple Watch",
                        "23U67x",
                        "12345678-1234-1234-1234-123456789012",
                        2,
                        true,
                        true);
        OrdinaryClassDSession initiator =
                OrdinaryClassDSession.createClassC(
                        random,
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        initiatorMaterial,
                        initiatorPeer,
                        initiatorPrelude,
                        responderPrelude,
                        initiatorProfile);
        OrdinaryClassDSession responder =
                OrdinaryClassDSession.createClassC(
                        random,
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                        responderMaterial,
                        responderPeer,
                        responderPrelude,
                        initiatorPrelude,
                        responderProfile);
        OrdinaryClassDSession.Outbound outbound = null;
        try {
            assertEquals(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    initiator.dataClass());
            assertEquals(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    responder.dataClass());

            outbound = initiator.start();
            OrdinaryClassDSession.Outbound next =
                    responder.acceptInboundIke(
                            outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            next = initiator.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            next = responder.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            next = initiator.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_AUTH,
                    OrdinaryIkeAuth.REQUEST_PACKET_LENGTH);
            next = responder.acceptInboundIke(
                    outbound.ikePacket);
            outbound.destroy();
            outbound = next;
            assertTrue(responder.established());

            assertOutbound(
                    outbound,
                    OrdinaryClassDSession.Exchange.IKE_AUTH,
                    712);
            assertNull(
                    initiator.acceptInboundIke(
                            outbound.ikePacket));
            outbound.destroy();
            outbound = null;
            assertTrue(initiator.established());

            AppleNetworkRelayInnerAddresses initiatorView =
                    initiator.copyAuthoritativeAddresses();
            byte[] expectedInitiatorC =
                    addresses.initiatorClassC();
            byte[] expectedResponderC =
                    addresses.responderClassC();
            byte[] actualInitiatorC =
                    initiatorView.initiatorClassC();
            byte[] actualResponderC =
                    initiatorView.responderClassC();
            try {
                assertArrayEquals(
                        expectedInitiatorC,
                        actualInitiatorC);
                assertArrayEquals(
                        expectedResponderC,
                        actualResponderC);
            } finally {
                initiatorView.destroy();
                wipe(expectedInitiatorC);
                wipe(expectedResponderC);
                wipe(actualInitiatorC);
                wipe(actualResponderC);
            }

            byte[] payload = new byte[]{7, 6, 5, 4};
            byte[] esp =
                    initiator.encryptEsp(
                            payload,
                            17,
                            2);
            OrdinaryChildSaCrypto.DecryptedEsp decrypted =
                    responder.decryptEsp(esp);
            try {
                assertArrayEquals(
                        payload,
                        decrypted.payload);
                assertEquals(
                        0x80000001L,
                        decrypted.sequence);
            } finally {
                decrypted.destroy();
                wipe(payload);
                wipe(esp);
            }
        } finally {
            if (outbound != null) {
                outbound.destroy();
            }
            initiator.close();
            responder.close();
            addresses.destroy();
            wipe(initiatorUuid);
            wipe(responderUuid);
            wipe(initiatorPrelude);
            wipe(responderPrelude);
        }
    }

    @Test
    public void refusesCallerSuppliedRoleThatContradictsPrelude() {
        SecureRandom random = new SecureRandom();
        AppleNetworkRelayPairingMaterial local =
                AppleNetworkRelayPairingMaterial.generate(random);
        AppleNetworkRelayPairingMaterial remote =
                AppleNetworkRelayPairingMaterial.generate(random);
        OrdinaryIkeAuth.PeerMaterial peer =
                peerFrom(remote);
        byte[] lowerUuid = new byte[16];
        byte[] higherUuid = new byte[16];
        lowerUuid[15] = 1;
        higherUuid[15] = 2;
        byte[] lowerPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        lowerUuid,
                        false,
                        false);
        byte[] higherPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        higherUuid,
                        false,
                        false);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OrdinaryClassDSession.create(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                            local,
                            peer,
                            lowerPrelude,
                            higherPrelude,
                            null));
        } finally {
            local.destroy();
            remote.destroy();
            peer.destroy();
            wipe(lowerUuid);
            wipe(higherUuid);
            wipe(lowerPrelude);
            wipe(higherPrelude);
        }
    }

    private static void assertOutbound(
            OrdinaryClassDSession.Outbound outbound,
            OrdinaryClassDSession.Exchange expectedExchange,
            int expectedIkeLength) {
        assertEquals(
                expectedExchange,
                outbound.exchange);
        assertEquals(
                expectedIkeLength,
                outbound.ikePacket.length);
        assertEquals(
                expectedIkeLength + 5,
                outbound.uikeFrame.length);
        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        int split =
                outbound.uikeFrame.length / 2;
        List<byte[]> first =
                decoder.push(
                        Arrays.copyOfRange(
                                outbound.uikeFrame,
                                0,
                                split));
        assertTrue(first.isEmpty());
        List<byte[]> second =
                decoder.push(
                        Arrays.copyOfRange(
                                outbound.uikeFrame,
                                split,
                                outbound.uikeFrame.length));
        assertEquals(1, second.size());
        assertArrayEquals(
                outbound.ikePacket,
                second.get(0));
        assertEquals(
                0,
                decoder.bufferedLength());
    }

    private static OrdinaryIkeAuth.PeerMaterial peerFrom(
            AppleNetworkRelayPairingMaterial material) {
        byte[] identity =
                material.identityPayload();
        byte[] classD =
                material.classDPublicKeysPayload();
        byte[] classC =
                material.classCPublicKeysPayload();
        try {
            return OrdinaryIkeAuth.PeerMaterial
                    .fromPairingPayloads(
                            identity,
                            classD,
                            classC);
        } finally {
            wipe(identity);
            wipe(classD);
            wipe(classC);
        }
    }

    private static void assertAddressesEqual(
            AppleNetworkRelayInnerAddresses expected,
            AppleNetworkRelayInnerAddresses actual) {
        byte[] expectedInitiator =
                expected.initiatorClassD();
        byte[] expectedResponder =
                expected.responderClassD();
        byte[] actualInitiator =
                actual.initiatorClassD();
        byte[] actualResponder =
                actual.responderClassD();
        try {
            assertArrayEquals(
                    expectedInitiator,
                    actualInitiator);
            assertArrayEquals(
                    expectedResponder,
                    actualResponder);
        } finally {
            wipe(expectedInitiator);
            wipe(expectedResponder);
            wipe(actualInitiator);
            wipe(actualResponder);
        }
    }

    private static int be32(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
