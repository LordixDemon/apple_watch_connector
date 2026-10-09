package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class NormalLinkPipeSessionTest {
    private static final int INITIATOR_CID = 0x0040;
    private static final int RESPONDER_CID = 0x0041;

    @Test
    public void resumesAfterBtClPreludeAtTxSeqOne() {
        SecureRandom random =
                new SecureRandom();
        AppleNetworkRelayPairingMaterial androidOriginal =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        AppleNetworkRelayPairingMaterial watchOriginal =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        byte[] androidSnapshot =
                androidOriginal.privateSnapshot();
        OrdinaryIkeAuth.PeerMaterial watchPeer =
                peerFrom(
                        watchOriginal);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        random);

        byte[] androidUuid = new byte[16];
        byte[] watchUuid = new byte[16];
        watchUuid[15] = 1;
        byte[] watchPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        watchUuid,
                        false,
                        false);
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap
                        .withSessionForTest(
                                openNormalPipe(),
                                NrLinkBluetoothSession
                                        .withLocalUuidForTest(
                                                androidUuid));
        NrLinkBluetoothPipeBootstrap.PreludeHandoff detached = null;
        NormalLinkPipeSession pipe = null;
        List<byte[]> firstOrdinary = null;
        try {
            byte[] localPreludeFrame =
                    bootstrap.buildOutboundPreludeFrame();
            L2capErtmCodec.Frame decodedLocal =
                    L2capErtmCodec.decode(
                            0x0042,
                            localPreludeFrame,
                            false);
            assertEquals(
                    0,
                    decodedLocal.txSequence);
            wipe(
                    decodedLocal.information);

            bootstrap.acceptInboundPreludeFrame(
                    L2capErtmCodec.encodeInformationFrame(
                            0x0041,
                            0,
                            1,
                            watchPrelude,
                            false));
            byte[] remotePreludeAck =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            L2capErtmCodec.Frame decodedAck =
                    L2capErtmCodec.decode(
                            0x0042,
                            remotePreludeAck,
                            false);
            assertEquals(
                    1,
                    decodedAck.requestSequence);
            wipe(
                    decodedAck.information);

            detached = bootstrap.detach();
            pipe =
                    createResumedEndpoint(
                            random,
                            detached,
                            androidSnapshot,
                            watchPeer,
                            addresses,
                            false);

            firstOrdinary =
                    pipe.continueAfterPrelude();
            assertEquals(
                    1,
                    firstOrdinary.size());
            L2capErtmCodec.Frame decodedOrdinary =
                    L2capErtmCodec.decode(
                            0x0042,
                            firstOrdinary.get(0),
                            false);
            try {
                assertEquals(
                        1,
                        decodedOrdinary.txSequence);
                assertEquals(
                        1,
                        decodedOrdinary.requestSequence);
                assertEquals(
                        NetworkRelayPacketCodec
                                .TYPE_IKEV2_POINT_TO_POINT,
                        decodedOrdinary.information[0] & 0xff);
            } finally {
                wipe(
                        decodedOrdinary.information);
            }
            NormalLinkPipeSession activePipe =
                    pipe;
            assertThrows(
                    IllegalStateException.class,
                    activePipe::continueAfterPrelude);
        } finally {
            wipeAll(
                    firstOrdinary);
            if (pipe != null) {
                pipe.close();
            }
            if (detached != null) {
                detached.close();
            }
            bootstrap.close();
            androidOriginal.destroy();
            watchOriginal.destroy();
            watchPeer.destroy();
            addresses.destroy();
            wipe(
                    androidSnapshot);
            wipe(
                    androidUuid);
            wipe(
                    watchUuid);
            wipe(
                    watchPrelude);
        }
    }

    @Test
    public void routesBackToBackFreshSaInitRequestsAcrossResponderSlots() {
        SecureRandom random = new SecureRandom();
        AppleNetworkRelayPairingMaterial localOriginal =
                AppleNetworkRelayPairingMaterial.generate(random);
        AppleNetworkRelayPairingMaterial remoteOriginal =
                AppleNetworkRelayPairingMaterial.generate(random);
        byte[] localSnapshot = localOriginal.privateSnapshot();
        OrdinaryIkeAuth.PeerMaterial remotePeer =
                peerFrom(remoteOriginal);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(random);
        byte[] remoteUuid = new byte[16];
        byte[] localUuid = new byte[16];
        localUuid[15] = 1;
        byte[] remotePrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        remoteUuid,
                        false,
                        false);
        byte[] localPrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        localUuid,
                        false,
                        false);
        NormalLinkPipeSession responder = null;
        OrdinaryIkeSaInit.InitiatorState classD = null;
        OrdinaryIkeSaInit.InitiatorState classC = null;
        byte[] localPreludeFrame = null;
        byte[] remotePreludeFrame = null;
        byte[] staleEspFrame = null;
        byte[] classDFrame = null;
        byte[] classCFrame = null;
        byte[] classDResponsePacket = null;
        byte[] classCResponsePacket = null;
        try {
            responder =
                    createEndpoint(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                            RESPONDER_CID,
                            INITIATOR_CID,
                            localPrelude,
                            remotePrelude,
                            localSnapshot,
                            remotePeer,
                            addresses,
                            false);
            localPreludeFrame = responder.start();
            remotePreludeFrame =
                    L2capErtmCodec.encodeInformationFrame(
                            RESPONDER_CID,
                            0,
                            1,
                            remotePrelude,
                            false);
            try (NormalLinkPipeSession.InboundResult prelude =
                         responder.acceptErtmFrame(
                                 remotePreludeFrame)) {
                List<byte[]> acknowledgements =
                        prelude.immediateErtmFrames();
                wipeAll(acknowledgements);
            }

            classD = OrdinaryIkeSaInit.createInitiator(random);
            classC = OrdinaryIkeSaInit.createInitiator(random);
            classDFrame =
                    inboundIkeFrame(
                            RESPONDER_CID,
                            1,
                            1,
                            classD.packet);
            try (NormalLinkPipeSession.InboundResult first =
                         responder.acceptErtmFrame(classDFrame)) {
                List<byte[]> frames = first.immediateErtmFrames();
                try {
                    classDResponsePacket =
                            onlyIkePacket(
                                    INITIATOR_CID,
                                    frames);
                } finally {
                    wipeAll(frames);
                }
            }

            // P26 placed queued ESP after the fresh SA_INIT response but
            // before IKE_AUTH had created either new Child SA.
            staleEspFrame =
                    inboundPreSaStaleEspFrame(
                            RESPONDER_CID,
                            2,
                            1);
            try (NormalLinkPipeSession.InboundResult stale =
                         responder.acceptErtmFrame(
                                 staleEspFrame)) {
                assertEquals(1, stale.preSaStaleEspDrops());
                assertTrue(stale.deliveredIp().isEmpty());
                assertEquals(
                        NormalLinkPipeSession.Phase.CLASS_D,
                        responder.phase());
                List<byte[]> acknowledgements =
                        stale.immediateErtmFrames();
                wipeAll(acknowledgements);
            }

            // The physical Watch may enqueue the independent Class-C
            // IKE_SA_INIT before Class D reaches IKE_AUTH/READY.
            classCFrame =
                    inboundIkeFrame(
                            RESPONDER_CID,
                            3,
                            1,
                            classC.packet);
            try (NormalLinkPipeSession.InboundResult second =
                         responder.acceptErtmFrame(classCFrame)) {
                List<byte[]> frames = second.immediateErtmFrames();
                try {
                    classCResponsePacket =
                            onlyIkePacket(
                                    INITIATOR_CID,
                                    frames);
                } finally {
                    wipeAll(frames);
                }
            }

            OrdinaryIkeSaInit.Response classDResponse =
                    OrdinaryIkeSaInit.parseResponse(
                            classDResponsePacket,
                            classD.initiatorSpi);
            OrdinaryIkeSaInit.Response classCResponse =
                    OrdinaryIkeSaInit.parseResponse(
                            classCResponsePacket,
                            classC.initiatorSpi);
            classDResponse.destroy();
            classCResponse.destroy();
        } finally {
            wipe(localPreludeFrame);
            wipe(remotePreludeFrame);
            wipe(staleEspFrame);
            wipe(classDFrame);
            wipe(classCFrame);
            wipe(classDResponsePacket);
            wipe(classCResponsePacket);
            if (classD != null) {
                classD.destroy();
            }
            if (classC != null) {
                classC.destroy();
            }
            if (responder != null) {
                responder.close();
            }
            localOriginal.destroy();
            remoteOriginal.destroy();
            remotePeer.destroy();
            addresses.destroy();
            wipe(localSnapshot);
            wipe(remoteUuid);
            wipe(localUuid);
            wipe(remotePrelude);
            wipe(localPrelude);
        }
    }

    @Test
    public void discardsOnlyAuthenticatedClassDLinkLocalMulticastBeforeIds() {
        byte[] mldv2 = new byte[76];
        mldv2[0] = 0x60;
        mldv2[5] = 36;
        mldv2[6] = 0;
        mldv2[7] = 1;
        mldv2[8] = (byte) 0xfe;
        mldv2[9] = (byte) 0x80;
        mldv2[24] = (byte) 0xff;
        mldv2[25] = 0x02;
        mldv2[39] = 0x16;
        mldv2[40] = 58;
        mldv2[42] = 5;
        mldv2[43] = 2;
        mldv2[46] = 1;
        mldv2[48] = (byte) 143;
        mldv2[55] = 1;
        mldv2[56] = 4;
        mldv2[60] = (byte) 0xff;
        mldv2[61] = 0x02;
        mldv2[75] = 1;

        byte[] globalSource = mldv2.clone();
        globalSource[8] = 0x20;
        globalSource[9] = 0x01;
        byte[] unicastDestination = mldv2.clone();
        unicastDestination[24] = 0x20;
        byte[] routedHopLimit = mldv2.clone();
        routedHopLimit[7] = 2;
        byte[] ipv4 = mldv2.clone();
        ipv4[0] = 0x40;

        NormalLinkPipeSession.DeliveredIp valid =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        mldv2);
        NormalLinkPipeSession.DeliveredIp secondValid =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        mldv2);
        NormalLinkPipeSession.DeliveredIp classC =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_C,
                        true,
                        mldv2);
        NormalLinkPipeSession.DeliveredIp clear =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        false,
                        mldv2);
        NormalLinkPipeSession.DeliveredIp nonLinkLocal =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        globalSource);
        NormalLinkPipeSession.DeliveredIp unicast =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        unicastDestination);
        NormalLinkPipeSession.DeliveredIp routed =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        routedHopLimit);
        NormalLinkPipeSession.DeliveredIp wrongVersion =
                new NormalLinkPipeSession.DeliveredIp(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        true,
                        ipv4);
        try {
            assertTrue(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(
                                            valid,
                                            secondValid)));
            assertTrue(
                    valid.isAuthenticatedClassDLinkLocalMulticast());
            assertTrue(
                    valid.safeStructure()
                            .contains("sourceLinkLocal=true"));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    new ArrayList<>()));
            assertTrue(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(
                                            valid,
                                            classC)));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(clear)));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(nonLinkLocal)));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(unicast)));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(routed)));
            assertFalse(
                    NormalLinkPipeSession
                            .canDiscardBeforeIdsAttachment(
                                    Arrays.asList(wrongVersion)));
        } finally {
            valid.destroy();
            secondValid.destroy();
            classC.destroy();
            clear.destroy();
            nonLinkLocal.destroy();
            unicast.destroy();
            routed.destroy();
            wrongVersion.destroy();
            wipe(mldv2);
            wipe(globalSource);
            wipe(unicastDestination);
            wipe(routedHopLimit);
            wipe(ipv4);
        }
    }

    @Test
    public void drivesPreludeBothSasIpv6AndIdsEndToEnd() {
        SecureRandom random =
                new SecureRandom();
        AppleNetworkRelayPairingMaterial initiatorOriginal =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        AppleNetworkRelayPairingMaterial responderOriginal =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        byte[] initiatorSnapshot =
                initiatorOriginal.privateSnapshot();
        byte[] responderSnapshot =
                responderOriginal.privateSnapshot();
        OrdinaryIkeAuth.PeerMaterial initiatorPeer =
                peerFrom(
                        responderOriginal);
        OrdinaryIkeAuth.PeerMaterial responderPeer =
                peerFrom(
                        initiatorOriginal);

        byte[] initiatorUuid =
                new byte[16];
        byte[] responderUuid =
                new byte[16];
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
        AppleNetworkRelayInnerAddresses authoritative =
                AppleNetworkRelayInnerAddresses.generate(
                        random);
        AppleNetworkRelayInnerAddresses initiatorProvisional =
                swapAddressRoles(
                        authoritative);

        NormalLinkPipeSession initiator = null;
        NormalLinkPipeSession responder = null;
        NormalLinkIdsSessionBridge phoneBridge = null;
        NormalLinkIdsSessionBridge watchBridge = null;
        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            initiatorD =
                    authoritative.initiatorClassD();
            responderD =
                    authoritative.responderClassD();
            initiatorC =
                    authoritative.initiatorClassC();
            responderC =
                    authoritative.responderClassC();

            initiator =
                    createEndpoint(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                            INITIATOR_CID,
                            RESPONDER_CID,
                            initiatorPrelude,
                            responderPrelude,
                            initiatorSnapshot,
                            initiatorPeer,
                            initiatorProvisional,
                            false);
            responder =
                    createEndpoint(
                            random,
                            NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                            RESPONDER_CID,
                            INITIATOR_CID,
                            responderPrelude,
                            initiatorPrelude,
                            responderSnapshot,
                            responderPeer,
                            authoritative,
                            true);

            List<String> linkDirectorLog = new ArrayList<>();
            initiator.enableLinkDirectorAnnouncements(1, linkDirectorLog::add);
            responder.enableLinkDirectorAnnouncements(100, linkDirectorLog::add);

            ArrayDeque<Transfer> queue =
                    new ArrayDeque<>();
            queue.add(
                    new Transfer(
                            false,
                            initiator.start()));
            queue.add(
                    new Transfer(
                            true,
                            responder.start()));
            PumpStats setup =
                    pump(
                            initiator,
                            responder,
                            queue);

            assertEquals(
                    NormalLinkPipeSession.Phase.READY,
                    initiator.phase());
            assertEquals(
                    NormalLinkPipeSession.Phase.READY,
                    responder.phase());
            assertTrue(
                    initiator.classDEstablished());
            assertTrue(
                    initiator.classCEstablished());
            assertTrue(
                    responder.classDEstablished());
            assertTrue(
                    responder.classCEstablished());
            assertTrue(
                    initiator.registrationBarrierSatisfied());
            assertTrue(
                    responder.registrationBarrierSatisfied());
            assertTrue(
                    initiator.transportSettled());
            assertTrue(
                    responder.transportSettled());
            // An authenticated IKE notification requested while waiting for Final
            // must be retained, then delivered after the peer answers the poll.
            byte[] recoveryPoll = initiator.buildReceiverReadyPoll();
            assertFalse(initiator.transportSettled());
            assertEquals(null, initiator.announceLocalClassCUnlocked());
            assertFalse(initiator.transportSettled());
            queue.add(new Transfer(false, recoveryPoll));
            pump(initiator, responder, queue);
            assertTrue(initiator.transportSettled());
            assertTrue(responder.transportSettled());
            assertTrue(initiator.registrationBarrierSatisfied());
            assertTrue(responder.registrationBarrierSatisfied());
            AppleNetworkRelayInnerAddresses learnedByInitiator =
                    initiator.copyAuthoritativeAddresses();
            try {
                assertArrayEquals(
                        authoritative.initiatorClassD(),
                        learnedByInitiator.initiatorClassD());
                assertArrayEquals(
                        authoritative.responderClassC(),
                        learnedByInitiator.responderClassC());
            } finally {
                learnedByInitiator.destroy();
            }
            assertEquals(2, setup.rawPreludeFrames);
            assertEquals(16, setup.ikeRelayFrames);
            assertEquals(0, linkDirectorLog.stream().filter(v -> v.startsWith("LINK DIRECTOR TX:")).count());
            assertEquals(0, linkDirectorLog.stream().filter(v -> v.startsWith("LINK DIRECTOR ACK:")).count());
            assertFalse(initiator.pollLinkDirectorAnnouncements(System.nanoTime() / 1_000_000L).isEmpty());
            assertFalse(responder.pollLinkDirectorAnnouncements(System.nanoTime() / 1_000_000L).isEmpty());
            assertTrue(
                    setup.maximumInformationLength
                            <= L2capErtmSession.TARGET_MPS);
            assertTrue(
                    setup.deliveredIp.isEmpty());
            setup.destroy();

            byte[] clearD =
                    ipv6Udp(
                            initiatorD,
                            responderD,
                            0,
                            bytes(1, 2, 3, 4));
            byte[] protectedDFrame =
                    initiator.sendIpv6(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            clearD);
            assertEquals(
                    NetworkRelayPacketCodec
                            .TYPE_KNOWN_IPV6_ESP,
                    relayType(
                            RESPONDER_CID,
                            protectedDFrame));
            queue.add(
                    new Transfer(
                            false,
                            protectedDFrame));
            PumpStats dTraffic =
                    pump(
                            initiator,
                            responder,
                            queue);
            assertSingleDelivery(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    clearD,
                    dTraffic.deliveredIp);
            dTraffic.destroy();

            byte[] clearC =
                    ipv6Udp(
                            responderC,
                            initiatorC,
                            2,
                            bytes(9, 8, 7));
            byte[] protectedCFrame =
                    responder.sendIpv6(
                            OrdinaryIkeAuth.DataClass.CLASS_C,
                            clearC);
            assertEquals(
                    NetworkRelayPacketCodec
                            .TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0,
                    relayType(
                            INITIATOR_CID,
                            protectedCFrame));
            queue.add(
                    new Transfer(
                            true,
                            protectedCFrame));
            PumpStats cTraffic =
                    pump(
                            initiator,
                            responder,
                            queue);
            assertSingleDelivery(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    clearC,
                    cTraffic.deliveredIp);
            cTraffic.destroy();

            // Apple may carry the same authenticated Child SA in a generic
            // type-3 LOWPAN_IPHC packet whose outer IPv6 addresses are
            // fe80::IID -> ff02::GG. The unicast address quartet cannot
            // classify that packet; the inbound Child SPI must do so.
            byte[] sourceIid =
                    bytes(1, 2, 3, 4, 5, 6, 7, 8);
            byte multicastGroup = 1;
            byte[] knownProtected =
                    initiator.sendIpv6(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            clearD);
            byte[] lowpanProtected =
                    rewriteKnownEspAsLinkLocalMulticast(
                            RESPONDER_CID,
                            knownProtected,
                            sourceIid,
                            multicastGroup);
            byte[] linkLocalSource = new byte[16];
            linkLocalSource[0] = (byte) 0xfe;
            linkLocalSource[1] = (byte) 0x80;
            System.arraycopy(
                    sourceIid,
                    0,
                    linkLocalSource,
                    8,
                    sourceIid.length);
            byte[] multicastDestination = new byte[16];
            multicastDestination[0] = (byte) 0xff;
            multicastDestination[1] = 0x02;
            multicastDestination[15] = multicastGroup;
            byte[] expectedLinkLocal = clearD.clone();
            expectedLinkLocal[7] = 1;
            System.arraycopy(
                    linkLocalSource,
                    0,
                    expectedLinkLocal,
                    8,
                    16);
            System.arraycopy(
                    multicastDestination,
                    0,
                    expectedLinkLocal,
                    24,
                    16);
            queue.add(
                    new Transfer(
                            false,
                            lowpanProtected));
            PumpStats linkLocalTraffic =
                    pump(
                            initiator,
                            responder,
                            queue);
            assertSingleDelivery(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    expectedLinkLocal,
                    linkLocalTraffic.deliveredIp);
            assertTrue(
                    linkLocalTraffic.deliveredIp.get(0)
                            .isAuthenticatedClassDLinkLocalMulticast());
            linkLocalTraffic.destroy();
            wipe(knownProtected);
            wipe(sourceIid);
            wipe(linkLocalSource);
            wipe(multicastDestination);
            wipe(expectedLinkLocal);

            // Both Class-C SPIs now exist. This delayed packet still has to
            // demultiplex to the already established Class-D IKE SA.
            queue.add(
                    new Transfer(
                            false,
                            initiator
                                    .announceLocalClassCUnlocked()));
            PumpStats delayedUnlock =
                    pump(
                            initiator,
                            responder,
                            queue);
            assertEquals(
                    2,
                    delayedUnlock.ikeRelayFrames);
            assertEquals(
                    NormalLinkPipeSession.Phase.READY,
                    initiator.phase());
            assertEquals(
                    NormalLinkPipeSession.Phase.READY,
                    responder.phase());
            delayedUnlock.destroy();

            byte[] wrongDestination =
                    clearD.clone();
            wrongDestination[39] ^= 1;
            NormalLinkPipeSession activeInitiator =
                    initiator;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> activeInitiator.sendIpv6(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            wrongDestination));
            assertFalse(
                    initiator.phase()
                            == NormalLinkPipeSession.Phase.POISONED);

            // A reset/data burst must leave IKE room to close both SAs;
            // the live 22-flow reconnect used to fill ERTM and abort LDM.
            List<byte[]> burst = new ArrayList<>();
            try {
                for (int index = 0; index < L2capErtmSession.TARGET_TX_WINDOW; index++) {
                    byte[] frame = initiator.sendIpv6(OrdinaryIkeAuth.DataClass.CLASS_D, clearD);
                    if (frame == null) break;
                    burst.add(frame);
                }
                assertEquals(L2capErtmSession.TARGET_TX_WINDOW - 2, burst.size());
                assertEquals(null, initiator.sendIpv6(OrdinaryIkeAuth.DataClass.CLASS_D, clearD));
                // Even with the data window full, an IKE request can be
                // sent and answered. Then IDS should still bootstrap.
                byte[] unlock = initiator.announceLocalClassCUnlocked();
                for (byte[] frame : burst) queue.add(new Transfer(false, frame.clone()));
                queue.add(new Transfer(false, unlock));
                PumpStats drained = pump(initiator, responder, queue);
                try { assertEquals(2, drained.ikeRelayFrames); }
                finally { drained.destroy(); }
            } finally { wipeAll(burst); }

            wipe(clearD);
            wipe(clearC);
            wipe(wrongDestination);

            IdsModernSessionCoordinator phoneIds =
                    new IdsModernSessionCoordinator(
                            new SecureRandom(),
                            100,
                            initiatorD,
                            responderD,
                            initiatorC,
                            responderC,
                            "00000000-0000-4000-8000-000000000001",
                            true);
            IdsModernSessionCoordinator watchIds =
                    new IdsModernSessionCoordinator(
                            new SecureRandom(),
                            300,
                            responderD,
                            initiatorD,
                            responderC,
                            initiatorC,
                            "00000000-0000-4000-8000-000000000002",
                            true);
            phoneBridge =
                    new NormalLinkIdsSessionBridge(
                            initiator,
                            phoneIds);
            initiator = null;
            phoneIds = null;
            watchBridge =
                    new NormalLinkIdsSessionBridge(
                            responder,
                            watchIds);
            responder = null;
            watchIds = null;

            try (NormalLinkIdsSessionBridge.Output started =
                         phoneBridge.startControl();
                 BridgePumpStats control =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 true,
                                 started)) {
                assertEquals(
                        1,
                        control.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
                assertEquals(
                        1,
                        control.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
                assertEquals(
                        0,
                        control.passthroughIp);
            }

            try (NormalLinkIdsSessionBridge.Output started =
                         phoneBridge.startInitialLane(
                                 NanoRegistryPropertyCodec
                                         .CLASS_D_SERVICE);
                 BridgePumpStats classD =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 true,
                                 started)) {
                assertTrue(
                        classD.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
                assertTrue(
                        classD.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
            }

            try (NormalLinkIdsSessionBridge.Output started =
                         phoneBridge.startInitialLane(
                                 NanoRegistryPropertyCodec
                                         .CLASS_C_SERVICE);
                 BridgePumpStats classC =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 true,
                                 started)) {
                assertTrue(
                        classC.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
                assertTrue(
                        classC.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
            }

            assertEquals(
                    2,
                    phoneBridge.idsSnapshot()
                            .joinedDataCount);
            assertEquals(
                    2,
                    watchBridge.idsSnapshot()
                            .joinedDataCount);

            NanoRegistryClassDCodec.PairingModeRequest pairingMode =
                    NanoRegistryClassDCodec
                            .PairingModeRequest
                            .modernIos26_6Ultra2(
                                    NanoRegistryClassDCodec
                                            .COMPATIBILITY_STATE_CONFIGURE,
                                    25);
            IdsModernSessionCoordinator.MessageMetadata metadata =
                    new IdsModernSessionCoordinator.MessageMetadata(
                            1,
                            "10000000-0000-4000-8000-000000000001",
                            0,
                            null,
                            null,
                            null);
            try (NormalLinkIdsSessionBridge.Output sent =
                         phoneBridge.sendClassD(
                                 metadata,
                                 pairingMode);
                 BridgePumpStats delivered =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 true,
                                 sent)) {
                BridgeObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                NanoRegistryPropertyCodec
                                        .CLASS_D_SERVICE);
                assertEquals(
                        NanoRegistryClassDCodec.TYPE_PAIRING_MODE,
                        received.protobufType);
                NanoRegistryClassDCodec.PairingModeRequest decoded =
                        NanoRegistryClassDCodec
                                .decodePairingModeRequest(
                                        received.payload);
                assertEquals(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CONFIGURE,
                        decoded.pairingMode);
                assertEquals(
                        Integer.valueOf(
                                25),
                        decoded.watchPairingProtocolVersion);
                assertEquals(
                        0,
                        delivered.passthroughIp);
                assertEquals(
                        0,
                        delivered.normalControlMessages);
            }

            IdsModernSessionCoordinator.MessageMetadata pbMetadata =
                    new IdsModernSessionCoordinator.MessageMetadata(
                            2,
                            "20000000-0000-4000-8000-000000000002",
                            0,
                            null,
                            null,
                            null);
            try (NormalLinkIdsSessionBridge.Output sent =
                         phoneBridge.sendPbBridge(
                                 pbMetadata,
                                 new PbBridgeCodec.CanBeginActivation());
                 BridgePumpStats delivered =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 true,
                                 sent)) {
                BridgeObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        PbBridgeCodec.TYPE_CAN_BEGIN_ACTIVATION,
                        received.protobufType);
                assertArrayEquals(
                        new byte[0],
                        received.payload);
                assertEquals(
                        0,
                        delivered.passthroughIp);
                assertEquals(
                        0,
                        delivered.normalControlMessages);
            }

            NanoRegistryPropertyCodec.PropertiesChanged watchCapabilities =
                    new NanoRegistryPropertyCodec.PropertiesChanged(
                            true,
                            List.of(
                                    new NanoRegistryPropertyCodec.Property(
                                            "productType",
                                            NanoRegistryPropertyCodec
                                                    .PropertyValue
                                                    .string(
                                                            "Watch7,5")),
                                    new NanoRegistryPropertyCodec.Property(
                                            PairedSyncCapabilityState
                                                    .PROPERTY_NAME,
                                            NanoRegistryPropertyCodec
                                                    .PropertyValue
                                                    .set(
                                                            List.of(
                                                                    NanoRegistryPropertyCodec
                                                                            .PropertyValue
                                                                            .uuid(
                                                                                    PairedSyncCapabilityState
                                                                                            .capabilityUuid()))))),
                            800000000.0);
            IdsModernSessionCoordinator.MessageMetadata capabilityMetadata =
                    new IdsModernSessionCoordinator.MessageMetadata(
                            1,
                            "31000000-0000-4000-8000-000000000001",
                            0,
                            null,
                            null,
                            null);
            try (NormalLinkIdsSessionBridge.Output sent =
                         watchBridge.sendClassC(
                                 capabilityMetadata,
                                 watchCapabilities);
                 BridgePumpStats delivered =
                         pumpBridges(
                                 phoneBridge,
                                 watchBridge,
                                 false,
                                 sent)) {
                delivered.first(
                        "phone",
                        IdsModernSessionCoordinator
                                .EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                assertEquals(
                        PairedSyncCapabilityState.Status.PRESENT,
                        phoneBridge.idsSnapshot()
                                .pairedSyncCapabilityStatus);
            } finally {
                watchCapabilities.destroy();
            }

            PairedSyncCodec.UserDefaultsMessage completion =
                    PairedSyncCodec.initialSyncCompletion(
                            800000000.5);
            IdsModernSessionCoordinator.MessageMetadata syncMetadata =
                    new IdsModernSessionCoordinator.MessageMetadata(
                            3,
                            "30000000-0000-4000-8000-000000000003",
                            0,
                            null,
                            null,
                            null);
            try {
                try (NormalLinkIdsSessionBridge.Output sent =
                             phoneBridge.sendPairedSync(
                                     syncMetadata,
                                     completion);
                     BridgePumpStats delivered =
                             pumpBridges(
                                     phoneBridge,
                                     watchBridge,
                                     true,
                                     sent)) {
                    BridgeObservedEvent received =
                            delivered.first(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.PROTOBUF_RECEIVED,
                                    PairedSyncCodec.PREFERRED_SERVICE);
                    PairedSyncCodec.UserDefaultsMessage decoded =
                            PairedSyncCodec.decode(
                                    received.payload);
                    try {
                        assertTrue(
                                decoded.isInitialSyncCompletion());
                        assertEquals(
                                0,
                                delivered.passthroughIp);
                        assertEquals(
                                0,
                                delivered.normalControlMessages);
                    } finally {
                        decoded.destroy();
                    }
                }
            } finally {
                completion.destroy();
            }

            assertEquals(
                    2,
                    phoneBridge.idsSnapshot()
                            .joinedDataCount);
            assertEquals(
                    2,
                    watchBridge.idsSnapshot()
                            .joinedDataCount);
        } finally {
            if (phoneBridge != null) {
                phoneBridge.close();
            }
            if (watchBridge != null) {
                watchBridge.close();
            }
            if (initiator != null) {
                initiator.close();
            }
            if (responder != null) {
                responder.close();
            }
            initiatorOriginal.destroy();
            responderOriginal.destroy();
            initiatorPeer.destroy();
            responderPeer.destroy();
            authoritative.destroy();
            initiatorProvisional.destroy();
            wipe(initiatorSnapshot);
            wipe(responderSnapshot);
            wipe(initiatorUuid);
            wipe(responderUuid);
            wipe(initiatorPrelude);
            wipe(responderPrelude);
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    private static NormalLinkPipeSession createEndpoint(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole role,
            int localCid,
            int remoteCid,
            byte[] localPrelude,
            byte[] remotePrelude,
            byte[] localSnapshot,
            OrdinaryIkeAuth.PeerMaterial peer,
            AppleNetworkRelayInnerAddresses addresses,
            boolean watch) {
        AppleNetworkRelayPairingMaterial classDMaterial =
                AppleNetworkRelayPairingMaterial
                        .restorePrivateSnapshot(
                                localSnapshot);
        AppleNetworkRelayPairingMaterial classCMaterial =
                AppleNetworkRelayPairingMaterial
                        .restorePrivateSnapshot(
                                localSnapshot);
        OrdinaryClassDSession classD = null;
        OrdinaryClassDSession classC = null;
        AppleNetworkRelayInnerAddresses pipeAddresses = null;
        try {
            classD =
                    OrdinaryClassDSession.create(
                            random,
                            role,
                            classDMaterial,
                            peer.copy(),
                            localPrelude,
                            remotePrelude,
                            profile(
                                    addresses,
                                    localPrelude,
                                    watch));
            classDMaterial = null;
            classC =
                    OrdinaryClassDSession.createClassC(
                            random,
                            role,
                            classCMaterial,
                            peer.copy(),
                            localPrelude,
                            remotePrelude,
                            profile(
                                    addresses,
                                    localPrelude,
                                    watch));
            classCMaterial = null;
            pipeAddresses =
                    copyAddresses(
                            addresses);
            NormalLinkPipeSession output =
                    new NormalLinkPipeSession(
                            new L2capErtmSession(
                                    localCid,
                                    remoteCid,
                                    false),
                            role,
                            localPrelude,
                            remotePrelude,
                            pipeAddresses,
                            classD,
                            classC);
            pipeAddresses = null;
            classD = null;
            classC = null;
            return output;
        } finally {
            if (classDMaterial != null) {
                classDMaterial.destroy();
            }
            if (classCMaterial != null) {
                classCMaterial.destroy();
            }
            if (pipeAddresses != null) {
                pipeAddresses.destroy();
            }
            if (classD != null) {
                classD.close();
            }
            if (classC != null) {
                classC.close();
            }
        }
    }

    private static NormalLinkPipeSession createResumedEndpoint(
            SecureRandom random,
            NrLinkBluetoothPipeBootstrap.PreludeHandoff handoff,
            byte[] localSnapshot,
            OrdinaryIkeAuth.PeerMaterial peer,
            AppleNetworkRelayInnerAddresses addresses,
            boolean watch) {
        NrLinkBluetoothPrelude.LocalRole role =
                handoff.localRole();
        byte[] localPrelude =
                handoff.localPrelude();
        byte[] remotePrelude =
                handoff.remotePrelude();
        AppleNetworkRelayPairingMaterial classDMaterial =
                AppleNetworkRelayPairingMaterial
                        .restorePrivateSnapshot(
                                localSnapshot);
        AppleNetworkRelayPairingMaterial classCMaterial =
                AppleNetworkRelayPairingMaterial
                        .restorePrivateSnapshot(
                                localSnapshot);
        OrdinaryClassDSession classD = null;
        OrdinaryClassDSession classC = null;
        AppleNetworkRelayInnerAddresses pipeAddresses = null;
        try {
            classD =
                    OrdinaryClassDSession.create(
                            random,
                            role,
                            classDMaterial,
                            peer.copy(),
                            localPrelude,
                            remotePrelude,
                            profile(
                                    addresses,
                                    localPrelude,
                                    watch));
            classDMaterial = null;
            classC =
                    OrdinaryClassDSession.createClassC(
                            random,
                            role,
                            classCMaterial,
                            peer.copy(),
                            localPrelude,
                            remotePrelude,
                            profile(
                                    addresses,
                                    localPrelude,
                                    watch));
            classCMaterial = null;
            pipeAddresses =
                    copyAddresses(
                            addresses);
            NormalLinkPipeSession output =
                    NormalLinkPipeSession.resumeAfterPrelude(
                            handoff,
                            pipeAddresses,
                            classD,
                            classC);
            pipeAddresses = null;
            classD = null;
            classC = null;
            return output;
        } finally {
            if (classDMaterial != null) {
                classDMaterial.destroy();
            }
            if (classCMaterial != null) {
                classCMaterial.destroy();
            }
            if (pipeAddresses != null) {
                pipeAddresses.destroy();
            }
            if (classD != null) {
                classD.close();
            }
            if (classC != null) {
                classC.close();
            }
            wipe(
                    localPrelude);
            wipe(
                    remotePrelude);
        }
    }

    private static OrdinaryIkeAuth.ResponderProfile profile(
            AppleNetworkRelayInnerAddresses addresses,
            byte[] localPrelude,
            boolean watch) {
        return new OrdinaryIkeAuth.ResponderProfile(
                addresses,
                localPrelude,
                watch ? "Apple Watch Ultra 2" : "OnePlus 13",
                watch ? "23U67" : "CPH2655",
                watch
                        ? "12345678-1234-1234-1234-123456789012"
                        : null,
                watch ? 2 : 1,
                true,
                true);
    }

    private static PumpStats pump(
            NormalLinkPipeSession initiator,
            NormalLinkPipeSession responder,
            ArrayDeque<Transfer> queue) {
        PumpStats stats =
                new PumpStats();
        int turns = 0;
        while (!queue.isEmpty()) {
            if (++turns > 256) {
                stats.destroy();
                throw new AssertionError(
                        "Normal-link pump did not settle");
            }
            Transfer transfer =
                    queue.removeFirst();
            NormalLinkPipeSession target =
                    transfer.toInitiator
                            ? initiator
                            : responder;
            int targetCid =
                    transfer.toInitiator
                            ? INITIATOR_CID
                            : RESPONDER_CID;
            L2capErtmCodec.Frame decoded =
                    L2capErtmCodec.decode(
                            targetCid,
                            transfer.frame,
                            false);
            try {
                if (!decoded.supervisory) {
                    stats.maximumInformationLength =
                            Math.max(
                                    stats.maximumInformationLength,
                                    decoded.information.length);
                    if (decoded.information.length
                            == NrLinkBluetoothPrelude
                            .EXACT_ENCODED_LENGTH) {
                        stats.rawPreludeFrames++;
                    } else if (decoded.information.length > 0
                            && (decoded.information[0] & 0xff)
                            == NetworkRelayPacketCodec
                            .TYPE_IKEV2_POINT_TO_POINT) {
                        stats.ikeRelayFrames++;
                    }
                }
                try (NormalLinkPipeSession.InboundResult result =
                        target.acceptErtmFrame(
                                transfer.frame)) {
                    List<byte[]> outbound =
                            result.immediateErtmFrames();
                    for (byte[] frame : outbound) {
                        queue.addLast(
                                new Transfer(
                                        !transfer.toInitiator,
                                        frame));
                    }
                    stats.deliveredIp.addAll(
                            result.deliveredIp());
                    List<byte[]> controls =
                            result.controlMessages();
                    wipeAll(
                            controls);
                }
            } finally {
                wipe(decoded.information);
                wipe(transfer.frame);
            }
        }
        return stats;
    }

    private static BridgePumpStats pumpBridges(
            NormalLinkIdsSessionBridge phone,
            NormalLinkIdsSessionBridge watch,
            boolean initialFromPhone,
            NormalLinkIdsSessionBridge.Output initial) {
        ArrayDeque<BridgeTransfer> queue =
                new ArrayDeque<>();
        BridgePumpStats stats =
                new BridgePumpStats();
        captureBridgeOutput(
                initialFromPhone
                        ? "phone"
                        : "watch",
                initialFromPhone
                        ? phone
                        : watch,
                initialFromPhone
                        ? "watch"
                        : "phone",
                initialFromPhone
                        ? watch
                        : phone,
                initial,
                queue,
                stats);
        int turns = 0;
        try {
            while (!queue.isEmpty()) {
                if (++turns > 32768) {
                    throw new AssertionError(
                            "Normal-link/IDS pump did not settle");
                }
                BridgeTransfer transfer =
                        queue.removeFirst();
                try (NormalLinkIdsSessionBridge.Output accepted =
                             transfer.target.acceptErtmFrame(
                                     transfer.frame)) {
                    captureBridgeOutput(
                            transfer.targetName,
                            transfer.target,
                            transfer.sourceName,
                            transfer.source,
                            accepted,
                            queue,
                            stats);
                } finally {
                    transfer.close();
                }
            }
            return stats;
        } catch (RuntimeException | AssertionError failure) {
            stats.close();
            throw failure;
        } finally {
            while (!queue.isEmpty()) {
                queue.removeFirst()
                        .close();
            }
        }
    }

    private static void captureBridgeOutput(
            String ownerName,
            NormalLinkIdsSessionBridge owner,
            String peerName,
            NormalLinkIdsSessionBridge peer,
            NormalLinkIdsSessionBridge.Output output,
            ArrayDeque<BridgeTransfer> queue,
            BridgePumpStats stats) {
        for (IdsModernSessionCoordinator.SessionEvent event :
                output.idsEvents()) {
            stats.events.add(
                    new BridgeObservedEvent(
                            ownerName,
                            event));
        }

        List<byte[]> frames =
                output.ertmFrames();
        for (byte[] frame :
                frames) {
            queue.addLast(
                    new BridgeTransfer(
                            ownerName,
                            owner,
                            peerName,
                            peer,
                            frame));
        }
        frames.clear();

        List<NormalLinkPipeSession.DeliveredIp> passthrough =
                output.passthroughIp();
        stats.passthroughIp +=
                passthrough.size();
        for (NormalLinkPipeSession.DeliveredIp packet :
                passthrough) {
            packet.destroy();
        }
        passthrough.clear();

        List<byte[]> controls =
                output.normalControlMessages();
        stats.normalControlMessages +=
                controls.size();
        wipeAll(
                controls);
    }

    private static int relayType(
            int destinationCid,
            byte[] ertmFrame) {
        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(
                        destinationCid,
                        ertmFrame,
                        false);
        try {
            return decoded.information[0] & 0xff;
        } finally {
            wipe(decoded.information);
        }
    }

    private static byte[] rewriteKnownEspAsLinkLocalMulticast(
            int destinationCid,
            byte[] ertmFrame,
            byte[] sourceIid,
            byte multicastGroup) {
        if (sourceIid == null || sourceIid.length != 8) {
            throw new IllegalArgumentException(
                    "A 64-bit source IID is required");
        }
        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(
                        destinationCid,
                        ertmFrame,
                        false);
        byte[] esp = null;
        byte[] lowpan = null;
        byte[] relay = null;
        try {
            int type = decoded.information[0] & 0xff;
            if (type != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP
                    && type
                    != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP_ECT0) {
                throw new IllegalArgumentException(
                        "Expected a known Class-D ESP frame");
            }
            int payloadLength =
                    ((decoded.information[1] & 0xff) << 8)
                            | (decoded.information[2] & 0xff);
            esp = Arrays.copyOfRange(
                    decoded.information,
                    3,
                    3 + payloadLength);
            lowpan = new byte[2 + 1 + 8 + 1 + esp.length];
            lowpan[0] = 0x79; // TF=3, NH inline, HLIM=1.
            lowpan[1] = 0x1b; // fe80::IID -> ff02::GG.
            lowpan[2] =
                    (byte) OrdinaryChildSaCrypto.ESP_PROTOCOL_NUMBER;
            System.arraycopy(
                    sourceIid,
                    0,
                    lowpan,
                    3,
                    sourceIid.length);
            lowpan[11] = multicastGroup;
            System.arraycopy(
                    esp,
                    0,
                    lowpan,
                    12,
                    esp.length);
            relay = encodeType3ForTest(
                    lowpan);
            return L2capErtmCodec.encodeInformationFrame(
                    destinationCid,
                    decoded.txSequence,
                    decoded.requestSequence,
                    relay,
                    false);
        } finally {
            wipe(decoded.information);
            wipe(esp);
            wipe(lowpan);
            wipe(relay);
        }
    }

    private static byte[] encodeType3ForTest(
            byte[] payload) {
        byte[] covered = new byte[3 + payload.length];
        covered[0] =
                (byte) NetworkRelayPacketCodec
                        .TYPE_ENCAPSULATED_6LOWPAN;
        covered[1] = (byte) (payload.length >>> 8);
        covered[2] = (byte) payload.length;
        System.arraycopy(
                payload,
                0,
                covered,
                3,
                payload.length);
        int checksum =
                IkeV2Codec.internetChecksum(
                        covered);
        byte[] output =
                Arrays.copyOf(
                        covered,
                        covered.length + 2);
        output[covered.length] =
                (byte) (checksum >>> 8);
        output[covered.length + 1] =
                (byte) checksum;
        wipe(covered);
        return output;
    }

    private static byte[] inboundPreSaStaleEspFrame(
            int destinationCid,
            int txSequence,
            int requestSequence) {
        byte[] sourceIid =
                bytes(1, 2, 3, 4, 5, 6, 7, 8);
        byte[] staleEsp = new byte[64];
        byte[] lowpan = new byte[12 + staleEsp.length];
        byte[] relay = null;
        try {
            for (int index = 0;
                    index < staleEsp.length;
                    index++) {
                staleEsp[index] = (byte) (0x40 + index);
            }
            lowpan[0] = 0x79;
            lowpan[1] = 0x1b;
            lowpan[2] =
                    (byte) OrdinaryChildSaCrypto.ESP_PROTOCOL_NUMBER;
            System.arraycopy(
                    sourceIid,
                    0,
                    lowpan,
                    3,
                    sourceIid.length);
            lowpan[11] = 1;
            System.arraycopy(
                    staleEsp,
                    0,
                    lowpan,
                    12,
                    staleEsp.length);
            relay = encodeType3ForTest(lowpan);
            return L2capErtmCodec.encodeInformationFrame(
                    destinationCid,
                    txSequence,
                    requestSequence,
                    relay,
                    false);
        } finally {
            wipe(sourceIid);
            wipe(staleEsp);
            wipe(lowpan);
            wipe(relay);
        }
    }

    private static byte[] inboundIkeFrame(
            int destinationCid,
            int txSequence,
            int requestSequence,
            byte[] ikePacket) {
        byte[] relay = NetworkRelayPacketCodec.encodeIke(ikePacket);
        try {
            return L2capErtmCodec.encodeInformationFrame(
                    destinationCid,
                    txSequence,
                    requestSequence,
                    relay,
                    false);
        } finally {
            wipe(relay);
        }
    }

    private static byte[] onlyIkePacket(
            int destinationCid,
            List<byte[]> ertmFrames) {
        assertEquals(1, ertmFrames.size());
        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(
                        destinationCid,
                        ertmFrames.get(0),
                        false);
        try {
            assertFalse(decoded.supervisory);
            assertEquals(
                    NetworkRelayPacketCodec.TYPE_IKEV2_POINT_TO_POINT,
                    decoded.information[0] & 0xff);
            int payloadLength =
                    ((decoded.information[1] & 0xff) << 8)
                            | (decoded.information[2] & 0xff);
            assertEquals(
                    payloadLength + 5,
                    decoded.information.length);
            return Arrays.copyOfRange(
                    decoded.information,
                    3,
                    3 + payloadLength);
        } finally {
            wipe(decoded.information);
        }
    }

    private static void assertSingleDelivery(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] expected,
            List<NormalLinkPipeSession.DeliveredIp> delivered) {
        assertEquals(1, delivered.size());
        NormalLinkPipeSession.DeliveredIp packet =
                delivered.get(0);
        assertEquals(
                dataClass,
                packet.dataClass);
        assertTrue(
                packet.protectedByEsp);
        assertArrayEquals(
                expected,
                packet.packet);
    }

    private static byte[] ipv6Udp(
            byte[] source,
            byte[] destination,
            int trafficClass,
            byte[] payload) {
        byte[] packet =
                new byte[40 + payload.length];
        packet[0] =
                (byte) (0x60
                        | (trafficClass >>> 4));
        packet[1] =
                (byte) (trafficClass << 4);
        packet[4] =
                (byte) (payload.length >>> 8);
        packet[5] =
                (byte) payload.length;
        packet[6] = 17;
        packet[7] = 64;
        System.arraycopy(
                source,
                0,
                packet,
                8,
                16);
        System.arraycopy(
                destination,
                0,
                packet,
                24,
                16);
        System.arraycopy(
                payload,
                0,
                packet,
                40,
                payload.length);
        return packet;
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

    private static AppleNetworkRelayInnerAddresses copyAddresses(
            AppleNetworkRelayInnerAddresses addresses) {
        byte[] initiatorD =
                addresses.initiatorClassD();
        byte[] responderD =
                addresses.responderClassD();
        byte[] initiatorC =
                addresses.initiatorClassC();
        byte[] responderC =
                addresses.responderClassC();
        try {
            return AppleNetworkRelayInnerAddresses
                    .fromAuthoritative(
                            initiatorD,
                            responderD,
                            initiatorC,
                            responderC);
        } finally {
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    private static AppleNetworkRelayInnerAddresses swapAddressRoles(
            AppleNetworkRelayInnerAddresses addresses) {
        byte[] initiatorD =
                addresses.responderClassD();
        byte[] responderD =
                addresses.initiatorClassD();
        byte[] initiatorC =
                addresses.responderClassC();
        byte[] responderC =
                addresses.initiatorClassC();
        try {
            return AppleNetworkRelayInnerAddresses
                    .fromAuthoritative(
                            initiatorD,
                            responderD,
                            initiatorC,
                            responderC);
        } finally {
            wipe(
                    initiatorD);
            wipe(
                    responderD);
            wipe(
                    initiatorC);
            wipe(
                    responderC);
        }
    }

    private static BtClNormalLinkHandoff openNormalPipe() {
        BtClNormalLinkHandoff handoff =
                BtClNormalLinkHandoff.begin(
                        0x0b,
                        true);
        handoff.advertise();
        handoff.acceptCommonServices(
                HciCodec.parseBtCl(
                        0x0b,
                        hex(
                                "02 03 00 01 02 00")));
        handoff.acceptChannel(
                HciCodec.parseBtCl(
                        0x0b,
                        hex(
                                "04 05 00 00 02 00 42 00")));
        return handoff;
    }

    private static byte[] hex(
            String text) {
        String compact =
                text.replaceAll(
                        "\\s+",
                        "");
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

    private static byte[] bytes(
            int... values) {
        byte[] output =
                new byte[values.length];
        for (int index = 0;
                index < values.length;
                index++) {
            output[index] =
                    (byte) values[index];
        }
        return output;
    }

    private static void wipeAll(
            List<byte[]> values) {
        if (values == null) {
            return;
        }
        for (byte[] value : values) {
            wipe(value);
        }
        values.clear();
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static final class BridgeTransfer
            implements AutoCloseable {
        final String sourceName;
        final NormalLinkIdsSessionBridge source;
        final String targetName;
        final NormalLinkIdsSessionBridge target;
        byte[] frame;
        boolean closed;

        private BridgeTransfer(
                String sourceName,
                NormalLinkIdsSessionBridge source,
                String targetName,
                NormalLinkIdsSessionBridge target,
                byte[] frame) {
            this.sourceName = sourceName;
            this.source = source;
            this.targetName = targetName;
            this.target = target;
            this.frame = frame;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    frame);
            frame =
                    new byte[0];
        }
    }

    private static final class BridgeObservedEvent
            implements AutoCloseable {
        final String owner;
        final IdsModernSessionCoordinator.EventType type;
        final String topic;
        final int protobufType;
        byte[] payload;

        private BridgeObservedEvent(
                String owner,
                IdsModernSessionCoordinator.SessionEvent event) {
            this.owner = owner;
            type = event.type;
            topic = event.topic;
            protobufType =
                    event.protobufType;
            payload =
                    event.payload();
        }

        @Override
        public void close() {
            wipe(
                    payload);
            payload =
                    new byte[0];
        }
    }

    private static final class BridgePumpStats
            implements AutoCloseable {
        final List<BridgeObservedEvent> events =
                new ArrayList<>();
        int passthroughIp;
        int normalControlMessages;

        int count(
                String owner,
                IdsModernSessionCoordinator.EventType type) {
            int count = 0;
            for (BridgeObservedEvent event :
                    events) {
                if (owner.equals(
                        event.owner)
                        && type == event.type) {
                    count++;
                }
            }
            return count;
        }

        BridgeObservedEvent first(
                String owner,
                IdsModernSessionCoordinator.EventType type,
                String topic) {
            for (BridgeObservedEvent event :
                    events) {
                if (owner.equals(
                        event.owner)
                        && type == event.type
                        && topic.equals(
                                event.topic)) {
                    return event;
                }
            }
            throw new AssertionError(
                    "Missing bridged IDS event "
                            + owner
                            + "/"
                            + type
                            + "/"
                            + topic);
        }

        @Override
        public void close() {
            for (BridgeObservedEvent event :
                    events) {
                event.close();
            }
            events.clear();
        }
    }

    private static final class Transfer {
        final boolean toInitiator;
        final byte[] frame;

        Transfer(
                boolean toInitiator,
                byte[] frame) {
            this.toInitiator = toInitiator;
            this.frame = frame;
        }
    }

    private static final class PumpStats {
        final List<NormalLinkPipeSession.DeliveredIp> deliveredIp =
                new ArrayList<>();
        int rawPreludeFrames;
        int ikeRelayFrames;
        int maximumInformationLength;

        void destroy() {
            for (NormalLinkPipeSession.DeliveredIp packet
                    : deliveredIp) {
                packet.destroy();
            }
            deliveredIp.clear();
        }
    }
}
