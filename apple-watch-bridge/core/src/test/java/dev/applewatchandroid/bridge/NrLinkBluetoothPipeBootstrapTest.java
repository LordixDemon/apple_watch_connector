package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class NrLinkBluetoothPipeBootstrapTest {
    @Test
    public void carriesRawPreludeAsFirstErtmSdu() {
        BtClNormalLinkHandoff handoff =
                openNormalPipe();
        byte[] localUuid = hex(
                "80 00 00 00 00 00 00 00 "
                        + "00 00 00 00 00 00 00 00");
        byte[] remoteUuid = hex(
                "7f ff ff ff ff ff ff ff "
                        + "ff ff ff ff ff ff ff ff");
        NrLinkBluetoothSession session =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(localUuid);
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap
                        .withSessionForTest(
                                handoff,
                                session);
        NrLinkBluetoothPipeBootstrap.PreludeHandoff detached = null;
        L2capErtmSession transport = null;
        try {
            byte[] outbound =
                    bootstrap.buildOutboundPreludeFrame();
            L2capErtmCodec.Frame decodedOutbound =
                    L2capErtmCodec.decode(
                            0x0042,
                            outbound,
                            false);
            assertFalse(decodedOutbound.supervisory);
            assertEquals(0, decodedOutbound.txSequence);
            assertEquals(0, decodedOutbound.requestSequence);
            assertArrayEquals(
                    NrLinkBluetoothPrelude.encodeFreshModern(
                            localUuid,
                            false,
                            false),
                    decodedOutbound.information);
            assertArrayEquals(
                    "TERMINUS".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII),
                    java.util.Arrays.copyOf(
                            decodedOutbound.information,
                            8));

            byte[] inbound =
                    L2capErtmCodec.encodeInformationFrame(
                            0x0041,
                            0,
                            1,
                            NrLinkBluetoothPrelude
                                    .encodeFreshModern(
                                            remoteUuid,
                                            true,
                                            false),
                            false);
            NrLinkBluetoothSession.Negotiated negotiated =
                    bootstrap.acceptInboundPreludeFrame(
                            inbound);
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    negotiated.localRole);
            assertEquals(1, bootstrap.nextOutboundTxSequence());
            assertEquals(
                    1,
                    bootstrap.nextExpectedRemoteTxSequence());
            assertEquals(1, bootstrap.remoteRequestSequence());
            assertTrue(
                    bootstrap.outboundPreludeAcknowledged());

            byte[] acknowledgement =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            L2capErtmCodec.Frame decodedAcknowledgement =
                    L2capErtmCodec.decode(
                            0x0042,
                            acknowledgement,
                            false);
            assertTrue(
                    decodedAcknowledgement.supervisory);
            assertEquals(
                    L2capErtmCodec.SUPERVISORY_RR,
                    decodedAcknowledgement.supervisoryFunction);
            assertEquals(
                    1,
                    decodedAcknowledgement.requestSequence);

            detached = bootstrap.detach();
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    detached.localRole());
            transport = detached.takeTransport();

            // Neither owner may close the scheduler after ownership moved.
            detached.close();
            detached = null;
            bootstrap.close();

            byte[] next =
                    transport.sendInformation(
                            new byte[] {4, 3, 2, 1});
            L2capErtmCodec.Frame decodedNext =
                    L2capErtmCodec.decode(
                            0x0042,
                            next,
                            false);
            assertEquals(
                    1,
                    decodedNext.txSequence);
            assertEquals(
                    1,
                    decodedNext.requestSequence);
        } finally {
            bootstrap.close();
            if (detached != null) {
                detached.close();
            }
            if (transport != null) {
                transport.close();
            }
        }
    }

    @Test
    public void allowsSimultaneousPreludeWithoutInventingAnAck() {
        BtClNormalLinkHandoff handoff =
                openNormalPipe();
        byte[] localUuid = new byte[16];
        byte[] remoteUuid = new byte[16];
        remoteUuid[15] = 1;
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap
                        .withSessionForTest(
                                handoff,
                                NrLinkBluetoothSession
                                        .withLocalUuidForTest(
                                                localUuid));
        try {
            bootstrap.buildOutboundPreludeFrame();
            bootstrap.acceptInboundPreludeFrame(
                    L2capErtmCodec.encodeInformationFrame(
                            0x0041,
                            0,
                            0,
                            NrLinkBluetoothPrelude
                                    .encodeFreshModern(
                                            remoteUuid,
                                            false,
                                            false),
                            false));
            assertFalse(
                    bootstrap.outboundPreludeAcknowledged());
            assertEquals(0, bootstrap.remoteRequestSequence());

            assertThrows(
                    IllegalStateException.class,
                    bootstrap::detach);

            byte[] acknowledgement =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            L2capErtmCodec.Frame decodedAcknowledgement =
                    L2capErtmCodec.decode(
                            0x0042,
                            acknowledgement,
                            false);
            assertEquals(
                    1,
                    decodedAcknowledgement.requestSequence);
            NrLinkBluetoothPipeBootstrap.PreludeHandoff detached =
                    bootstrap.detach();
            L2capErtmSession transport =
                    detached.takeTransport();
            try {
                assertEquals(
                        1,
                        transport.outstandingCount());
                assertEquals(
                        0,
                        transport.oldestOutstandingSequence());

                byte[] firstOrdinary =
                        L2capErtmCodec.encodeInformationFrame(
                                0x0041,
                                1,
                                1,
                                new byte[] {9, 8, 7},
                                false);
                try (L2capErtmSession.InboundResult result =
                        transport.accept(
                                firstOrdinary)) {
                    List<byte[]> delivered =
                            result.deliveredSdus();
                    try {
                        assertEquals(
                                1,
                                delivered.size());
                        assertArrayEquals(
                                new byte[] {9, 8, 7},
                                delivered.get(0));
                    } finally {
                        for (byte[] value : delivered) {
                            java.util.Arrays.fill(
                                    value,
                                    (byte) 0);
                        }
                    }
                }
                assertEquals(
                        0,
                        transport.outstandingCount());
                assertEquals(
                        2,
                        transport.expectedRemoteTxSequence());
            } finally {
                transport.close();
                detached.close();
            }
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void acceptsStandalonePreludeAckBeforeRemotePrelude() {
        BtClNormalLinkHandoff handoff =
                openNormalPipe();
        byte[] localUuid = new byte[16];
        byte[] remoteUuid = new byte[16];
        remoteUuid[15] = 1;
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap
                        .withSessionForTest(
                                handoff,
                                NrLinkBluetoothSession
                                        .withLocalUuidForTest(
                                                localUuid));
        try {
            bootstrap.buildOutboundPreludeFrame();
            byte[] peerAcknowledgement =
                    L2capErtmCodec.encodeReceiverReady(
                            0x0041,
                            1,
                            false,
                            false);
            try (L2capErtmSession.InboundResult result =
                    bootstrap.acceptPeerControlFrame(
                            peerAcknowledgement)) {
                assertTrue(
                        result.deliveredSdus().isEmpty());
                assertTrue(
                        result.immediateOutboundFrames().isEmpty());
            }

            bootstrap.acceptInboundPreludeFrame(
                    L2capErtmCodec.encodeInformationFrame(
                            0x0041,
                            0,
                            1,
                            NrLinkBluetoothPrelude
                                    .encodeFreshModern(
                                            remoteUuid,
                                            false,
                                            false),
                            false));
            assertTrue(
                    bootstrap.outboundPreludeAcknowledged());
            bootstrap.buildRemotePreludeAcknowledgementFrame();
            NrLinkBluetoothPipeBootstrap.PreludeHandoff detached =
                    bootstrap.detach();
            detached.close();
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void rejectsSupervisoryOrSegmentedFirstFrame() {
        BtClNormalLinkHandoff handoff =
                openNormalPipe();
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap
                        .withSessionForTest(
                                handoff,
                                NrLinkBluetoothSession
                                        .withLocalUuidForTest(
                                                new byte[16]));
        try {
            bootstrap.buildOutboundPreludeFrame();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> bootstrap.acceptInboundPreludeFrame(
                            L2capErtmCodec
                                    .encodeReceiverReady(
                                            0x0041,
                                            1,
                                            false,
                                            false)));
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void encodesPairedPreludeWithCustomPairingState() {
        BtClNormalLinkHandoff handoff = openNormalPipe();
        NrLinkBluetoothPipeBootstrap bootstrapState13 =
                NrLinkBluetoothPipeBootstrap.beginPaired(
                        handoff,
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION,
                        new java.security.SecureRandom());
        try {
            byte[] frame13 = bootstrapState13.buildOutboundPreludeFrame();
            L2capErtmCodec.Frame decoded = L2capErtmCodec.decode(0x0042, frame13, false);
            NrLinkBluetoothPrelude.PairingState state13 =
                    NrLinkBluetoothPrelude.PairingState.fromWireValue(decoded.information[9] & 0xff);
            assertEquals(
                    NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION,
                    state13);
        } finally {
            bootstrapState13.close();
        }

        BtClNormalLinkHandoff handoff2 = openNormalPipe();
        NrLinkBluetoothPipeBootstrap bootstrapState20 =
                NrLinkBluetoothPipeBootstrap.beginPaired(
                        handoff2,
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING,
                        new java.security.SecureRandom());
        try {
            byte[] frame20 = bootstrapState20.buildOutboundPreludeFrame();
            L2capErtmCodec.Frame decoded = L2capErtmCodec.decode(0x0042, frame20, false);
            NrLinkBluetoothPrelude.PairingState state20 =
                    NrLinkBluetoothPrelude.PairingState.fromWireValue(decoded.information[9] & 0xff);
            assertEquals(
                    NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING,
                    state20);
        } finally {
            bootstrapState20.close();
        }
    }

    private static BtClNormalLinkHandoff openNormalPipe() {
        BtClNormalLinkHandoff handoff =
                BtClNormalLinkHandoff.begin(0x0b, true);
        handoff.advertise();
        handoff.acceptCommonServices(
                HciCodec.parseBtCl(
                        0x0b,
                        hex("02 03 00 01 02 00")));
        handoff.acceptChannel(
                HciCodec.parseBtCl(
                        0x0b,
                        hex("04 05 00 00 02 00 42 00")));
        return handoff;
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }
}
