package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Offline composition test for the complete Class-D control-channel entry.
 */
public final class IdsControlChannelTransportIntegrationTest {
    private static final byte[] PHONE_ADDRESS =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 10 11 12 13 14 15");
    private static final byte[] WATCH_ADDRESS =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 20 21 22 23 24 25");

    @Test
    public void fastOpenCarriesNwscThenAcceptedStreamCarriesIdsHello() {
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
                                 "ids-control-channel")) {
                byte[] nwscFrame =
                        start.frame();
                Ipv6TcpStream phoneTcp =
                        Ipv6TcpStream.active(
                                PHONE_ADDRESS,
                                WATCH_ADDRESS,
                                49160,
                                IdsControlChannelCodec
                                        .CONTROL_PORT,
                                0xffff_ff90L);
                Ipv6TcpStream watchTcp = null;
                Ipv6TcpStream.PassiveOpen passive = null;
                byte[] syn = null;
                byte[] synAck = null;
                List<byte[]> finalAck =
                        new ArrayList<>();
                List<byte[]> watchDelivery =
                        new ArrayList<>();
                try {
                    syn =
                            phoneTcp.startActiveOpen(
                                    nwscFrame);
                    passive =
                            Ipv6TcpStream.acceptPassive(
                                    syn,
                                    0x1020_3040L);
                    watchTcp =
                            passive.takeStream();
                    synAck =
                            passive.synAcknowledgement();

                    try (Ipv6TcpStream.InboundResult result =
                                 phoneTcp.accept(
                                         synAck)) {
                        finalAck.addAll(
                                result.outboundPackets());
                    }
                    try (Ipv6TcpStream.InboundResult result =
                                 watchTcp.accept(
                                         finalAck.get(0))) {
                        watchDelivery.addAll(
                                result.deliveredBytes());
                    }
                    assertEquals(
                            1,
                            watchDelivery.size());
                    assertArrayEquals(
                            nwscFrame,
                            watchDelivery.get(0));

                    NwServiceConnectorCodec.NormalStartRequest request =
                            (NwServiceConnectorCodec.NormalStartRequest)
                                    NwServiceConnectorCodec.decode(
                                            watchDelivery.get(0));
                    byte[] acceptedFrame = null;
                    try (NwServiceConnectorHandshake.IncomingResult accepted =
                                 watchHandshake.receiveIncomingRequest(
                                         request,
                                         true)) {
                        assertEquals(
                                NwServiceConnectorHandshake
                                        .IncomingDisposition.ACCEPTED,
                                accepted.disposition);
                        acceptedFrame =
                                accepted.feedbackFrame();
                    } finally {
                        request.destroy();
                    }

                    exchangeWatchToPhone(
                            watchTcp,
                            phoneTcp,
                            acceptedFrame,
                            bytes -> {
                                NwServiceConnectorCodec.Feedback feedback =
                                        (NwServiceConnectorCodec.Feedback)
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

                    UUID instance =
                            UUID.fromString(
                                    "00112233-4455-6677-8899-aabbccddeeff");
                    UUID device =
                            UUID.fromString(
                                    "10213243-5465-7687-98a9-bacbdcedfe0f");
                    IdsControlChannelCodec.HelloMessage localHello =
                            IosCompanionProfile26_6.hello(
                                    instance,
                                    device,
                                    true,
                                    false,
                                    true);
                    byte[] helloFrame =
                            IdsControlChannelCodec.encodeFramed(
                                    localHello);
                    localHello.destroy();
                    try {
                        exchangePhoneToWatch(
                                phoneTcp,
                                watchTcp,
                                helloFrame,
                                bytes -> {
                                    try (IdsControlChannelCodec.StreamDecoder
                                                 decoder =
                                                 new IdsControlChannelCodec
                                                         .StreamDecoder()) {
                                        List<IdsControlChannelCodec.Message>
                                                messages =
                                                decoder.push(
                                                        bytes);
                                        try {
                                            assertEquals(
                                                    1,
                                                    messages.size());
                                            IdsControlChannelCodec
                                                    .HelloMessage hello =
                                                    (IdsControlChannelCodec
                                                            .HelloMessage)
                                                            messages.get(0);
                                            assertEquals(
                                                    "iPhone18,1",
                                                    hello.model);
                                            assertEquals(
                                                    26,
                                                    hello
                                                            .pairingProtocolVersion);
                                            assertEquals(
                                                    device,
                                                    hello.deviceUniqueId);
                                            assertEquals(
                                                    instance,
                                                    hello.instanceId);
                                            assertEquals(
                                                    0,
                                                    decoder.bufferedLength());
                                        } finally {
                                            destroyMessages(
                                                    messages);
                                        }
                                    }
                                });
                    } finally {
                        wipe(
                                acceptedFrame);
                        wipe(
                                helloFrame);
                    }
                } finally {
                    phoneTcp.close();
                    if (watchTcp != null) {
                        watchTcp.close();
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
                    wipeAll(
                            finalAck);
                    wipeAll(
                            watchDelivery);
                }
            }
        }
    }

    private static void exchangeWatchToPhone(
            Ipv6TcpStream watch,
            Ipv6TcpStream phone,
            byte[] bytes,
            ByteConsumer consumer) {
        exchange(
                watch,
                phone,
                bytes,
                consumer);
    }

    private static void exchangePhoneToWatch(
            Ipv6TcpStream phone,
            Ipv6TcpStream watch,
            byte[] bytes,
            ByteConsumer consumer) {
        exchange(
                phone,
                watch,
                bytes,
                consumer);
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
                            IdsControlChannelCodec.CONTROL_PORT,
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

    private static void destroyMessages(
            List<IdsControlChannelCodec.Message> messages) {
        for (IdsControlChannelCodec.Message message : messages) {
            message.destroy();
        }
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
}
