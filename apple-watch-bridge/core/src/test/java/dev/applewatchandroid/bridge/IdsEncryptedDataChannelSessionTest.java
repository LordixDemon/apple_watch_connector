package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.Test;

public final class IdsEncryptedDataChannelSessionTest {
    @Test
    public void outgoingSetupUsesProductionTypeSixAndDynamicPort() {
        IdsPortMap ports =
                new IdsPortMap();
        IdsSsrcMap ssrcs =
                new IdsSsrcMap();
        IdsEncryptedDataChannelSession session =
                IdsEncryptedDataChannelSession
                        .openOutgoing(
                                ports,
                                ssrcs,
                                new SecureRandom(),
                                classDRoute());
        IdsControlChannelCodec.SetupEncryptedChannelMessage setup =
                session.startSetup();
        try {
            assertEquals(
                    IdsControlChannelCodec
                            .TYPE_SETUP_ENCRYPTED_CHANNEL,
                    setup.type);
            assertEquals(
                    IdsControlChannelCodec.PROTOCOL_TCP,
                    setup.protocol);
            assertEquals(
                    IdsPortMap.FIRST_DYNAMIC_PORT,
                    setup.localPort);
            assertEquals(
                    IdsControlChannelCodec.DATA_PORT,
                    setup.remotePort);
            assertNull(
                    setup.forLocalGuid);
            assertEquals(
                    IdsStreamEncryption.CONTRIBUTION_LENGTH,
                    setup.keyMaterial.length);
            assertEquals(
                    classDRoute().encode(),
                    session.connectorService());
            assertFalse(
                    session.established());
            assertThrows(
                    IllegalStateException.class,
                    session::startSetup);
            assertEquals(
                    1,
                    ports.dynamicAllocatedCount());
            assertEquals(
                    1,
                    ssrcs.allocatedCount());
        } finally {
            setup.destroy();
            session.close();
            assertEquals(
                    0,
                    ports.dynamicAllocatedCount());
            assertEquals(
                    0,
                    ssrcs.allocatedCount());
            ports.close();
            ssrcs.close();
        }
    }

    @Test
    public void incomingResponderCompletesReciprocalEncryptedStream() {
        IdsPortMap phonePorts =
                new IdsPortMap();
        IdsSsrcMap phoneSsrcs =
                new IdsSsrcMap();
        IdsSsrcMap watchSsrcs =
                new IdsSsrcMap();
        IdsEncryptedDataChannelSession phone =
                IdsEncryptedDataChannelSession
                        .openOutgoing(
                                phonePorts,
                                phoneSsrcs,
                                new SecureRandom(),
                                classCRoute());
        IdsControlChannelCodec.SetupEncryptedChannelMessage request =
                phone.startSetup();
        try (IdsEncryptedDataChannelSession.ResponderStart watch =
                     IdsEncryptedDataChannelSession
                             .respondToInitialRequest(
                                     watchSsrcs,
                                     new SecureRandom(),
                                     request)) {
            assertEquals(
                    request.remoteConnectionGuid,
                    watch.reply.forLocalGuid);
            assertEquals(
                    request.remotePort,
                    watch.reply.localPort);
            assertEquals(
                    request.localPort,
                    watch.reply.remotePort);
            assertTrue(
                    watch.session.established());
            assertEquals(
                    IdsEncryptedDataChannelSession
                            .EstablishAction
                            .INCOMING_REQUEST_REPLIED,
                    watch.session.establishAction());

            assertEquals(
                    IdsEncryptedDataChannelSession
                            .EstablishAction
                            .LOCAL_INITIATED_REPLY,
                    phone.acceptPeerSetup(
                            watch.reply));
            assertTrue(
                    phone.established());
            assertEquals(
                    request.localPort,
                    phone.localPort());
            assertEquals(
                    IdsControlChannelCodec.DATA_PORT,
                    watch.session.localPort());

            assertBidirectionalCrypto(
                    phone,
                    watch.session);
        } finally {
            request.destroy();
            phone.close();
            assertEquals(
                    0,
                    phonePorts.dynamicAllocatedCount());
            assertEquals(
                    0,
                    phoneSsrcs.allocatedCount());
            assertEquals(
                    0,
                    watchSsrcs.allocatedCount());
            phonePorts.close();
            phoneSsrcs.close();
            watchSsrcs.close();
        }
    }

    @Test
    public void plainSetupChannelResponderIsEstablishedWithoutSrtpKeys() {
        IdsControlChannelCodec.SetupChannelMessage request =
                new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        20001,
                        IdsControlChannelCodec.DATA_PORT,
                        "11111111-1111-4111-8111-111111111111",
                        null,
                        IdsServiceConnectorName.LOCAL_ACCOUNT,
                        IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                        classDRoute().name,
                        null);
        IdsEncryptedDataChannelSession session =
                IdsEncryptedDataChannelSession.openPlainEstablished(
                        new SecureRandom(),
                        request);
        try {
            assertTrue(
                    session.established());
            assertEquals(
                    IdsEncryptedDataChannelSession
                            .EstablishAction
                            .INCOMING_REQUEST_REPLIED,
                    session.establishAction());
            assertEquals(
                    20001,
                    session.localPort());
            assertFalse(
                    session.localGuid().isEmpty());
        } finally {
            request.destroy();
            session.close();
        }
    }

    @Test
    public void simultaneousOpenKeepsOnlyTheSmallerGuidAddressPair() {
        IdsPortMap leftPorts =
                new IdsPortMap();
        IdsPortMap rightPorts =
                new IdsPortMap();
        IdsSsrcMap leftSsrcs =
                new IdsSsrcMap();
        IdsSsrcMap rightSsrcs =
                new IdsSsrcMap();
        IdsEncryptedDataChannelSession left =
                IdsEncryptedDataChannelSession
                        .openOutgoing(
                                leftPorts,
                                leftSsrcs,
                                new SecureRandom(),
                                classDRoute());
        IdsEncryptedDataChannelSession right =
                IdsEncryptedDataChannelSession
                        .openOutgoing(
                                rightPorts,
                                rightSsrcs,
                                new SecureRandom(),
                                classDRoute());
        IdsControlChannelCodec.SetupEncryptedChannelMessage fromLeft =
                left.startSetup();
        IdsControlChannelCodec.SetupEncryptedChannelMessage fromRight =
                right.startSetup();
        try {
            IdsEncryptedDataChannelSession.EstablishAction leftAction =
                    left.acceptPeerSetup(
                            fromRight);
            IdsEncryptedDataChannelSession.EstablishAction rightAction =
                    right.acceptPeerSetup(
                            fromLeft);

            boolean leftGuidIsLower =
                    fromLeft.remoteConnectionGuid.compareTo(
                            fromRight.remoteConnectionGuid)
                            < 0;
            assertEquals(
                    leftGuidIsLower
                            ? IdsEncryptedDataChannelSession
                            .EstablishAction
                            .SIMULTANEOUS_LOCAL_WINS
                            : IdsEncryptedDataChannelSession
                            .EstablishAction
                            .SIMULTANEOUS_REMOTE_WINS,
                    leftAction);
            assertEquals(
                    leftGuidIsLower
                            ? IdsEncryptedDataChannelSession
                            .EstablishAction
                            .SIMULTANEOUS_REMOTE_WINS
                            : IdsEncryptedDataChannelSession
                            .EstablishAction
                            .SIMULTANEOUS_LOCAL_WINS,
                    rightAction);

            IdsEncryptedDataChannelSession winner =
                    leftGuidIsLower
                            ? left
                            : right;
            IdsEncryptedDataChannelSession follower =
                    leftGuidIsLower
                            ? right
                            : left;
            IdsPortMap winnerPorts =
                    leftGuidIsLower
                            ? leftPorts
                            : rightPorts;
            IdsPortMap followerPorts =
                    leftGuidIsLower
                            ? rightPorts
                            : leftPorts;
            assertEquals(
                    IdsPortMap.FIRST_DYNAMIC_PORT,
                    winner.localPort());
            assertEquals(
                    IdsControlChannelCodec.DATA_PORT,
                    follower.localPort());
            assertEquals(
                    1,
                    winnerPorts.dynamicAllocatedCount());
            assertEquals(
                    0,
                    followerPorts.dynamicAllocatedCount());
            assertEquals(
                    winner.localPort(),
                    follower.remotePort());
            assertEquals(
                    winner.remotePort(),
                    follower.localPort());
            assertBidirectionalCrypto(
                    left,
                    right);
        } finally {
            fromLeft.destroy();
            fromRight.destroy();
            left.close();
            right.close();
            assertEquals(
                    0,
                    leftPorts.dynamicAllocatedCount());
            assertEquals(
                    0,
                    rightPorts.dynamicAllocatedCount());
            leftPorts.close();
            rightPorts.close();
            leftSsrcs.close();
            rightSsrcs.close();
        }
    }

    @Test
    public void malformedReciprocalPortsDoNotMutatePendingSession() {
        IdsPortMap ports =
                new IdsPortMap();
        IdsSsrcMap ssrcs =
                new IdsSsrcMap();
        IdsEncryptedDataChannelSession session =
                IdsEncryptedDataChannelSession
                        .openOutgoing(
                                ports,
                                ssrcs,
                                new SecureRandom(),
                                classDRoute());
        IdsControlChannelCodec.SetupEncryptedChannelMessage request =
                session.startSetup();
        byte[] remoteContribution =
                new byte[
                        IdsStreamEncryption
                                .CONTRIBUTION_LENGTH];
        new SecureRandom().nextBytes(
                remoteContribution);
        IdsControlChannelCodec.SetupEncryptedChannelMessage malformed =
                new IdsControlChannelCodec
                        .SetupEncryptedChannelMessage(
                                IdsControlChannelCodec.PROTOCOL_TCP,
                                IdsControlChannelCodec.DATA_PORT,
                                request.localPort + 1,
                                "f0112233-4455-4677-8899-aabbccddeeff",
                                request.remoteConnectionGuid,
                                request.account,
                                request.service,
                                request.name,
                                7,
                                8,
                                remoteContribution);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.acceptPeerSetup(
                            malformed));
            assertFalse(
                    session.established());
            assertEquals(
                    1,
                    ports.dynamicAllocatedCount());
            assertThrows(
                    IllegalStateException.class,
                    () -> session.encryptLegacyUtunTcp(
                            new byte[] {1}));
        } finally {
            wipe(
                    remoteContribution);
            malformed.destroy();
            request.destroy();
            session.close();
            assertEquals(
                    0,
                    ports.dynamicAllocatedCount());
            assertEquals(
                    0,
                    ssrcs.allocatedCount());
            ports.close();
            ssrcs.close();
        }
    }

    private static void assertBidirectionalCrypto(
            IdsEncryptedDataChannelSession first,
            IdsEncryptedDataChannelSession second) {
        byte[] firstPayload =
                new byte[] {
                        1,
                        2,
                        3,
                        4
                };
        byte[] firstFrame =
                first.encryptLegacyUtunTcp(
                        firstPayload);
        IdsStreamEncryption.DecryptedPacket atSecond =
                second.decryptLegacyUtun(
                        firstFrame);
        try {
            assertTrue(
                    atSecond.tcp);
            assertArrayEquals(
                    firstPayload,
                    atSecond.payload);
        } finally {
            atSecond.destroy();
            wipe(
                    firstFrame);
            wipe(
                    firstPayload);
        }

        byte[] secondPayload =
                new byte[] {
                        9,
                        8,
                        7
                };
        byte[] secondFrame =
                second.encryptLegacyUtunTcp(
                        secondPayload);
        IdsStreamEncryption.DecryptedPacket atFirst =
                first.decryptLegacyUtun(
                        secondFrame);
        try {
            assertTrue(
                    atFirst.tcp);
            assertArrayEquals(
                    secondPayload,
                    atFirst.payload);
        } finally {
            atFirst.destroy();
            wipe(
                    secondFrame);
            wipe(
                    secondPayload);
        }
    }

    private static IdsServiceConnectorName classDRoute() {
        return NanoRegistryInitialIdsRoute
                .classDSetup()
                .serviceConnectorName;
    }

    private static IdsServiceConnectorName classCRoute() {
        return NanoRegistryInitialIdsRoute
                .classCProperties()
                .serviceConnectorName;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
