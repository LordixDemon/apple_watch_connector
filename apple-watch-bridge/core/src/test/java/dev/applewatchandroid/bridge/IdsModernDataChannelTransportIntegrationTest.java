package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Offline composition test for the production Watch7,5 Class-C data path.
 *
 * <p>SetupEncryptedChannel remains a type-6 control-plane exchange. The
 * accepted modern NW service connection then carries socket-pair frames
 * directly; the legacy e0/SSRC packet envelope is deliberately absent.</p>
 */
public final class IdsModernDataChannelTransportIntegrationTest {
    private static final byte[] PHONE_ADDRESS =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 31 32 33 34 35 36");
    private static final byte[] WATCH_ADDRESS =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 41 42 43 44 45 46");

    @Test
    public void typeSixThenNwscCarriesDirectClassCPropertySnapshot() {
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
                                NanoRegistryInitialIdsRoute
                                        .classCProperties()
                                        .serviceConnectorName);
        IdsControlChannelCodec.SetupEncryptedChannelMessage request =
                phone.startSetup();
        byte[] requestFrame = null;
        IdsControlChannelCodec.Message decodedRequestBase = null;
        try {
            requestFrame =
                    IdsControlChannelCodec.encodeFramed(
                            request);
            decodedRequestBase =
                    IdsControlChannelCodec.decodeFramed(
                            requestFrame);
            assertTrue(
                    decodedRequestBase
                            instanceof IdsControlChannelCodec
                            .SetupEncryptedChannelMessage);
            IdsControlChannelCodec.SetupEncryptedChannelMessage
                    decodedRequest =
                    (IdsControlChannelCodec
                            .SetupEncryptedChannelMessage)
                            decodedRequestBase;
            assertEquals(
                    IdsControlChannelCodec
                            .TYPE_SETUP_ENCRYPTED_CHANNEL,
                    decodedRequest.type);
            assertEquals(
                    phone.connectorService(),
                    IdsServiceConnectorName.of(
                                    decodedRequest.account,
                                    decodedRequest.service,
                                    decodedRequest.name)
                            .encode());

            IdsIpsecServiceRoute route =
                    IdsIpsecServiceRoute.localDelivery(
                            NanoRegistryInitialIdsRoute
                                    .classCProperties()
                                    .serviceConnectorName);
            assertEquals(
                    IdsIpsecServiceRoute.Connector.NORMAL,
                    route.connector);
            assertEquals(
                    IdsControlChannelCodec.DATA_PORT,
                    route.listenerPort);
            assertEquals(
                    IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_C,
                    route.networkRelayDataClass);
            assertFalse(
                    route.allowsQuickRelay);

            try (IdsEncryptedDataChannelSession.ResponderStart watch =
                         IdsEncryptedDataChannelSession
                                 .respondToInitialRequest(
                                         watchSsrcs,
                                         new SecureRandom(),
                                         decodedRequest);
                 IdsDataChannelJoinState<Ipv6TcpStream> watchJoin =
                         new IdsDataChannelJoinState<>(
                                 Ipv6TcpStream::close)) {
                IdsDataChannelJoinState.Result<Ipv6TcpStream>
                        setupResult =
                        watchJoin.receiveSetup(
                                decodedRequest);
                assertEquals(
                        IdsDataChannelJoinState.Action
                                .START_OUTGOING_CONNECTOR,
                        setupResult.action);
                assertEquals(
                        phone.connectorService(),
                        setupResult.connectorService);

                byte[] replyFrame = null;
                IdsControlChannelCodec.Message decodedReplyBase = null;
                try {
                    replyFrame =
                            IdsControlChannelCodec.encodeFramed(
                                    watch.reply);
                    decodedReplyBase =
                            IdsControlChannelCodec.decodeFramed(
                                    replyFrame);
                    assertTrue(
                            decodedReplyBase
                                    instanceof IdsControlChannelCodec
                                    .SetupEncryptedChannelMessage);
                    assertEquals(
                            IdsEncryptedDataChannelSession
                                    .EstablishAction
                                    .LOCAL_INITIATED_REPLY,
                            phone.acceptPeerSetup(
                                    (IdsControlChannelCodec
                                            .SetupEncryptedChannelMessage)
                                            decodedReplyBase));
                    assertTrue(
                            phone.established());
                    assertTrue(
                            watch.session.established());
                } finally {
                    if (decodedReplyBase != null) {
                        decodedReplyBase.destroy();
                    }
                    wipe(
                            replyFrame);
                }

                try (TcpPair dataConnection =
                             openAcceptedDataConnection(
                                     49170,
                                     phone.connectorService())) {
                    assertFalse(
                            phone.localPort()
                                    == 49170);
                    IdsDataChannelJoinState.Result<Ipv6TcpStream>
                            joined =
                            watchJoin.receiveServiceConnection(
                                    phone.connectorService(),
                                    dataConnection.watch);
                    assertEquals(
                            IdsDataChannelJoinState.Action.JOINED,
                            joined.action);
                    assertSame(
                            dataConnection.watch,
                            joined.connection);
                    assertEquals(
                            0,
                            watchJoin.pendingSetupCount());

                    sendAndVerifyDirectPropertySnapshot(
                            dataConnection);
                }
            }
        } finally {
            if (decodedRequestBase != null) {
                decodedRequestBase.destroy();
            }
            request.destroy();
            wipe(
                    requestFrame);
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

    private static void sendAndVerifyDirectPropertySnapshot(
            TcpPair connection) {
        NanoRegistryPropertyCodec.PropertiesChanged snapshot =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "productType",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "iPhone18,1"))),
                        42.0);
        IdsSocketPairCodec.ProtobufMessage envelope =
                NanoRegistryPropertyCodec.envelope(
                        1,
                        0x2345,
                        IdsSocketPairCodec.FLAG_HAS_TOPIC,
                        null,
                        "00112233-4455-6677-8899-AABBCCDDEEFF",
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                        snapshot,
                        null);
        byte[] socketPairFrame = null;
        try {
            socketPairFrame =
                    IdsSocketPairCodec.encodeProtobuf(
                            envelope);
            assertEquals(
                    IdsSocketPairCodec.COMMAND_PROTOBUF,
                    socketPairFrame[0] & 0xff);
            assertFalse(
                    (socketPairFrame[0] & 0xff) == 0xe0);

            exchange(
                    connection.phone,
                    connection.watch,
                    socketPairFrame,
                    bytes -> {
                        IdsSocketPairCodec.Message decodedBase =
                                IdsSocketPairCodec.decode(
                                        bytes);
                        assertTrue(
                                decodedBase
                                        instanceof IdsSocketPairCodec
                                        .ProtobufMessage);
                        IdsSocketPairCodec.ProtobufMessage decodedEnvelope =
                                (IdsSocketPairCodec.ProtobufMessage)
                                        decodedBase;
                        NanoRegistryPropertyCodec.ApplicationMessage
                                decodedApplication = null;
                        try {
                            assertEquals(
                                    NanoRegistryPropertyCodec
                                            .CLASS_C_SERVICE,
                                    decodedEnvelope.topic);
                            assertEquals(
                                    NanoRegistryPropertyCodec
                                            .TYPE_PROPERTIES_CHANGED,
                                    decodedEnvelope.protobufType);
                            decodedApplication =
                                    NanoRegistryPropertyCodec.decode(
                                            decodedEnvelope);
                            assertTrue(
                                    decodedApplication
                                            instanceof
                                            NanoRegistryPropertyCodec
                                            .PropertiesChanged);
                            NanoRegistryPropertyCodec.PropertiesChanged
                                    changed =
                                    (NanoRegistryPropertyCodec
                                            .PropertiesChanged)
                                            decodedApplication;
                            assertTrue(
                                    changed.thisIsAllOfThem);
                            assertEquals(
                                    "productType",
                                    changed.properties.get(
                                            0).name);
                            assertEquals(
                                    "iPhone18,1",
                                    changed.properties.get(
                                            0).value.stringValue);
                        } finally {
                            if (decodedApplication != null) {
                                decodedApplication.destroy();
                            }
                            decodedBase.destroy();
                        }
                    });
        } finally {
            snapshot.destroy();
            envelope.destroy();
            wipe(
                    socketPairFrame);
        }
    }

    private static TcpPair openAcceptedDataConnection(
            int sourcePort,
            String service) {
        try (NwServiceConnectorHandshake phoneHandshake =
                     handshake(
                             0x10,
                             100);
             NwServiceConnectorHandshake watchHandshake =
                     handshake(
                             0x50,
                             200)) {
            bootstrapRemoteKeys(
                    phoneHandshake,
                    watchHandshake);
            try (NwServiceConnectorHandshake.OutgoingStart start =
                         phoneHandshake.startNormalRequest(
                                 uuid(
                                         0x80),
                                 service)) {
                byte[] nwscFrame =
                        start.frame();
                Ipv6TcpStream phone =
                        Ipv6TcpStream.active(
                                PHONE_ADDRESS,
                                WATCH_ADDRESS,
                                sourcePort,
                                IdsControlChannelCodec.DATA_PORT,
                                0xffff_ff90L);
                Ipv6TcpStream watch = null;
                Ipv6TcpStream.PassiveOpen passive = null;
                byte[] syn = null;
                byte[] synAck = null;
                byte[] acceptedFrame = null;
                List<byte[]> finalAck =
                        new ArrayList<>();
                List<byte[]> watchDelivery =
                        new ArrayList<>();
                boolean transferred = false;
                try {
                    syn =
                            phone.startActiveOpen(
                                    nwscFrame);
                    passive =
                            Ipv6TcpStream.acceptPassive(
                                    syn,
                                    0x1020_3040L);
                    watch =
                            passive.takeStream();
                    synAck =
                            passive.synAcknowledgement();

                    try (Ipv6TcpStream.InboundResult result =
                                 phone.accept(
                                         synAck)) {
                        finalAck.addAll(
                                result.outboundPackets());
                    }
                    try (Ipv6TcpStream.InboundResult result =
                                 watch.accept(
                                         finalAck.get(
                                                 0))) {
                        watchDelivery.addAll(
                                result.deliveredBytes());
                    }
                    assertEquals(
                            1,
                            watchDelivery.size());

                    NwServiceConnectorCodec.NormalStartRequest request =
                            (NwServiceConnectorCodec
                                    .NormalStartRequest)
                                    NwServiceConnectorCodec.decode(
                                            watchDelivery.get(
                                                    0));
                    try (NwServiceConnectorHandshake.IncomingResult
                                 accepted =
                                 watchHandshake
                                         .receiveIncomingRequest(
                                                 request,
                                                 true)) {
                        assertEquals(
                                NwServiceConnectorHandshake
                                        .IncomingDisposition
                                        .ACCEPTED,
                                accepted.disposition);
                        acceptedFrame =
                                accepted.feedbackFrame();
                    } finally {
                        request.destroy();
                    }

                    byte[] finalAcceptedFrame =
                            acceptedFrame;
                    exchange(
                            watch,
                            phone,
                            finalAcceptedFrame,
                            bytes -> {
                                NwServiceConnectorCodec.Feedback
                                        feedback =
                                        (NwServiceConnectorCodec
                                                .Feedback)
                                                NwServiceConnectorCodec
                                                        .decode(
                                                                bytes);
                                try {
                                    assertEquals(
                                            NwServiceConnectorHandshake
                                                    .OutgoingDisposition
                                                    .ACCEPTED,
                                            phoneHandshake
                                                    .receiveNormalFeedback(
                                                            feedback));
                                } finally {
                                    feedback.destroy();
                                }
                            });

                    TcpPair pair =
                            new TcpPair(
                                    phone,
                                    watch);
                    transferred = true;
                    return pair;
                } finally {
                    if (!transferred) {
                        phone.close();
                        if (watch != null) {
                            watch.close();
                        }
                    }
                    if (passive != null) {
                        passive.close();
                    }
                    wipe(
                            nwscFrame);
                    wipe(
                            syn);
                    wipe(
                            synAck);
                    wipe(
                            acceptedFrame);
                    wipeAll(
                            finalAck);
                    wipeAll(
                            watchDelivery);
                }
            }
        }
    }

    private static void exchange(
            Ipv6TcpStream sender,
            Ipv6TcpStream receiver,
            byte[] bytes,
            ByteConsumer consumer) {
        List<byte[]> packets =
                new ArrayList<>(
                        sender.send(
                                bytes));
        List<byte[]> acknowledgements =
                new ArrayList<>();
        List<byte[]> deliveries =
                new ArrayList<>();
        byte[] combined = null;
        try {
            for (byte[] packet : packets) {
                try (Ipv6TcpStream.InboundResult result =
                             receiver.accept(
                                     packet)) {
                    acknowledgements.addAll(
                            result.outboundPackets());
                    deliveries.addAll(
                            result.deliveredBytes());
                }
            }
            for (byte[] acknowledgement : acknowledgements) {
                try (Ipv6TcpStream.InboundResult ignored =
                             sender.accept(
                                     acknowledgement)) {
                    assertTrue(
                            ignored.deliveredBytes()
                                    .isEmpty());
                }
            }
            combined =
                    concatenate(
                            deliveries);
            consumer.accept(
                    combined);
        } finally {
            wipe(
                    combined);
            wipeAll(
                    packets);
            wipeAll(
                    acknowledgements);
            wipeAll(
                    deliveries);
        }
    }

    private static void bootstrapRemoteKeys(
            NwServiceConnectorHandshake first,
            NwServiceConnectorHandshake second) {
        bootstrapOneDirection(
                first,
                second);
        bootstrapOneDirection(
                second,
                first);
    }

    private static void bootstrapOneDirection(
            NwServiceConnectorHandshake requester,
            NwServiceConnectorHandshake responder) {
        try (NwServiceConnectorHandshake.OutgoingStart probe =
                     requester.startKeyProbe()) {
            byte[] requestFrame =
                    probe.frame();
            NwServiceConnectorCodec.OperationRequest request =
                    (NwServiceConnectorCodec.OperationRequest)
                            NwServiceConnectorCodec.decode(
                                    requestFrame);
            byte[] feedbackFrame = null;
            try (NwServiceConnectorHandshake.IncomingResult response =
                         responder.receiveIncomingRequest(
                                 request,
                                 true)) {
                feedbackFrame =
                        response.feedbackFrame();
            } finally {
                request.destroy();
                wipe(
                        requestFrame);
            }
            NwServiceConnectorCodec.Feedback feedback =
                    (NwServiceConnectorCodec.Feedback)
                            NwServiceConnectorCodec.decode(
                                    feedbackFrame);
            try (NwServiceConnectorHandshake.IncomingBatch ignored =
                         requester.receiveKeyProbeFeedback(
                                 feedback)) {
                assertEquals(
                        0,
                        ignored.size());
            } finally {
                feedback.destroy();
                wipe(
                        feedbackFrame);
            }
        }
    }

    private static NwServiceConnectorHandshake handshake(
            int firstPrivateByte,
            long sequence) {
        byte[] privateKey =
                sequence(
                        firstPrivateByte,
                        32);
        try {
            return NwServiceConnectorHandshake
                    .withPrivateKeyForTest(
                            "class-c-peer",
                            IdsControlChannelCodec.DATA_PORT,
                            privateKey,
                            sequence);
        } finally {
            wipe(
                    privateKey);
        }
    }

    private static byte[] concatenate(
            List<byte[]> values) {
        int total = 0;
        for (byte[] value : values) {
            total += value.length;
        }
        byte[] output =
                new byte[total];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(
                    value,
                    0,
                    output,
                    offset,
                    value.length);
            offset += value.length;
        }
        return output;
    }

    private static byte[] uuid(
            int first) {
        byte[] value =
                new byte[16];
        value[0] =
                (byte) first;
        for (int index = 1;
                index < value.length;
                index++) {
            value[index] =
                    (byte) index;
        }
        return value;
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] value =
                new byte[length];
        for (int index = 0;
                index < length;
                index++) {
            value[index] =
                    (byte) (first + index);
        }
        return value;
    }

    private static byte[] hex(
            String value) {
        String normalized =
                value.replaceAll(
                        "\\s+",
                        "");
        byte[] output =
                new byte[normalized.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            normalized.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static void wipeAll(
            List<byte[]> values) {
        for (byte[] value : values) {
            wipe(
                    value);
        }
        values.clear();
    }

    private interface ByteConsumer {
        void accept(
                byte[] bytes);
    }

    private static final class TcpPair
            implements AutoCloseable {
        final Ipv6TcpStream phone;
        final Ipv6TcpStream watch;
        private boolean closed;

        TcpPair(
                Ipv6TcpStream phone,
                Ipv6TcpStream watch) {
            this.phone = phone;
            this.watch = watch;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            phone.close();
            watch.close();
        }
    }
}
