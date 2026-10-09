package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class NwServiceConnectorHandshakeTest {
    private static final String ENDPOINT = "class-c-peer";
    private static final String SERVICE = "ids-control-channel";
    private static final String DATA_SERVICE =
            "idstest/localdelivery/"
                    + "UTunDelivery-Default-Default-C";

    @Test
    public void freshPeersBootstrapKeyAndAcceptOriginalRequest() {
        try (NwServiceConnectorHandshake phone =
                     handshake(
                             0x10,
                             100);
             NwServiceConnectorHandshake watch =
                     handshake(
                             0x40,
                             200);
             NwServiceConnectorHandshake.OutgoingStart normal =
                     phone.startNormalRequest(
                             uuid(
                                     0x20),
                             SERVICE)) {
            NwServiceConnectorCodec.NormalStartRequest request =
                    normalRequest(
                            normal);
            try (NwServiceConnectorHandshake.IncomingResult pending =
                         watch.receiveIncomingRequest(
                                 request,
                                 true)) {
                assertEquals(
                        NwServiceConnectorHandshake
                                .IncomingDisposition
                                .WAITING_FOR_REMOTE_KEY,
                        pending.disposition);
                assertTrue(
                        pending.startReverseKeyProbe);
                assertTrue(
                        watch.hasPendingIncomingRequest());
            } finally {
                request.destroy();
            }

            try (NwServiceConnectorHandshake.OutgoingStart probe =
                         watch.startKeyProbe()) {
                NwServiceConnectorCodec.OperationRequest operation =
                        operationRequest(
                                probe);
                byte[] keyFeedback = null;
                try (NwServiceConnectorHandshake.IncomingResult response =
                             phone.receiveIncomingRequest(
                                     operation,
                                     true)) {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition
                                    .REJECTED_TRANSIENT,
                            response.disposition);
                    keyFeedback =
                            response.feedbackFrame();
                } finally {
                    operation.destroy();
                }

                NwServiceConnectorCodec.Feedback decodedKey =
                        feedback(
                                keyFeedback);
                assertEquals(101L, decodedKey.sequence);
                assertEquals(0, keyFeedback[2]);
                byte[] acceptedFrame = null;
                try (NwServiceConnectorHandshake.IncomingBatch batch =
                             watch.receiveKeyProbeFeedback(
                                     decodedKey)) {
                    assertEquals(
                            1,
                            batch.size());
                    NwServiceConnectorHandshake.IncomingResult accepted =
                            batch.get(
                                    0);
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition.ACCEPTED,
                            accepted.disposition);
                    assertFalse(
                            watch.hasPendingIncomingRequest());
                    acceptedFrame =
                            accepted.feedbackFrame();
                } finally {
                    decodedKey.destroy();
                    wipe(
                            keyFeedback);
                }

                NwServiceConnectorCodec.Feedback accepted =
                        feedback(
                                acceptedFrame);
                try {
                    assertEquals(201L, accepted.sequence);
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .OutgoingDisposition.ACCEPTED,
                            phone.receiveNormalFeedback(
                                    accepted));
                    assertFalse(
                            phone.hasPendingNormalRequest());
                } finally {
                    accepted.destroy();
                    wipe(
                            acceptedFrame);
                }
            }
        }
    }

    @Test
    public void noOpFeedbackUsesFreshLocalSequenceForEveryPeerProbe() {
        try (NwServiceConnectorHandshake phone = handshake(0x10, 9000);
             NwServiceConnectorHandshake watch = handshake(0x40, 10)) {
            for (int index = 0; index < 3; index++) {
                try (NwServiceConnectorHandshake.OutgoingStart probe =
                             watch.startKeyProbe()) {
                    NwServiceConnectorCodec.OperationRequest request =
                            operationRequest(probe);
                    try (NwServiceConnectorHandshake.IncomingResult reply =
                                 phone.receiveIncomingRequest(request, true)) {
                        byte[] bytes = reply.feedbackFrame();
                        NwServiceConnectorCodec.Feedback decoded = feedback(bytes);
                        try {
                            assertEquals(10L + index, request.sequence);
                            assertEquals(9000L + index, decoded.sequence);
                            assertEquals(0, bytes[2]);
                            assertEquals(NwServiceConnectorCodec.FeedbackDisposition
                                    .TRANSIENT_REJECTION, decoded.disposition);
                            try (NwServiceConnectorHandshake.IncomingBatch empty =
                                         watch.receiveKeyProbeFeedback(decoded)) {
                                assertEquals(0, empty.size());
                            }
                        } finally {
                            decoded.destroy();
                            wipe(bytes);
                        }
                    } finally {
                        request.destroy();
                    }
                }
            }
        }
    }

    @Test
    public void allowedServiceStillRejectsForgedRequestAfterReverseKeyProbe() {
        try (NwServiceConnectorHandshake phone = handshake(0x10, 100);
             NwServiceConnectorHandshake watch = handshake(0x40, 200);
             NwServiceConnectorHandshake.OutgoingStart start =
                     phone.startNormalRequest(uuid(0x20), SERVICE)) {
            byte[] forgedFrame = start.frame();
            forgedFrame[forgedFrame.length - 1] ^= 1;
            NwServiceConnectorCodec.NormalStartRequest forged =
                    (NwServiceConnectorCodec.NormalStartRequest)
                            NwServiceConnectorCodec.decode(forgedFrame);
            try (NwServiceConnectorHandshake.IncomingResult pending =
                         watch.receiveIncomingRequest(forged, true)) {
                assertTrue(pending.startReverseKeyProbe);
                assertEquals(NwServiceConnectorHandshake.IncomingDisposition
                        .WAITING_FOR_REMOTE_KEY, pending.disposition);
            } finally {
                forged.destroy();
                wipe(forgedFrame);
            }

            try (NwServiceConnectorHandshake.OutgoingStart probe = watch.startKeyProbe()) {
                NwServiceConnectorCodec.OperationRequest request = operationRequest(probe);
                byte[] response = null;
                try (NwServiceConnectorHandshake.IncomingResult reply =
                             phone.receiveIncomingRequest(request, true)) {
                    response = reply.feedbackFrame();
                } finally {
                    request.destroy();
                }
                NwServiceConnectorCodec.Feedback key = feedback(response);
                try (NwServiceConnectorHandshake.IncomingBatch batch =
                             watch.receiveKeyProbeFeedback(key)) {
                    assertEquals(1, batch.size());
                    assertEquals(NwServiceConnectorHandshake.IncomingDisposition
                            .REJECTED_TRANSIENT, batch.get(0).disposition);
                    assertFalse(watch.hasPendingIncomingRequest());
                    byte[] rejectedFrame = batch.get(0).feedbackFrame();
                    try {
                        assertEquals(0, rejectedFrame[2]);
                    } finally {
                        wipe(rejectedFrame);
                    }
                } finally {
                    key.destroy();
                    wipe(response);
                }
            }
        }
    }

    @Test
    public void largerUnsignedUuidWinsSimultaneousNormalRequests() {
        try (NwServiceConnectorHandshake phone =
                     handshake(
                             0x11,
                             1000);
             NwServiceConnectorHandshake watch =
                     handshake(
                             0x51,
                             2000)) {
            bootstrapRemoteKeys(
                    phone,
                    watch);

            try (NwServiceConnectorHandshake.OutgoingStart phoneStart =
                         phone.startNormalRequest(
                                 uuid(
                                         0xe0),
                                 SERVICE);
                 NwServiceConnectorHandshake.OutgoingStart watchStart =
                         watch.startNormalRequest(
                                 uuid(
                                         0x20),
                                 SERVICE)) {
                NwServiceConnectorCodec.NormalStartRequest fromWatch =
                        normalRequest(
                                watchStart);
                NwServiceConnectorCodec.NormalStartRequest fromPhone =
                        normalRequest(
                                phoneStart);
                byte[] acceptedFrame = null;
                try (NwServiceConnectorHandshake.IncomingResult phoneDecision =
                             phone.receiveIncomingRequest(
                                     fromWatch,
                                     true);
                     NwServiceConnectorHandshake.IncomingResult watchDecision =
                             watch.receiveIncomingRequest(
                                     fromPhone,
                                     true)) {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition
                                    .REJECTED_TRANSIENT,
                            phoneDecision.disposition);
                    assertTrue(
                            phone.hasPendingNormalRequest());

                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition.ACCEPTED,
                            watchDecision.disposition);
                    assertTrue(
                            watchDecision.cancelOutgoingNormal);
                    assertFalse(
                            watch.hasPendingNormalRequest());
                    acceptedFrame =
                            watchDecision.feedbackFrame();
                } finally {
                    fromWatch.destroy();
                    fromPhone.destroy();
                }

                NwServiceConnectorCodec.Feedback accepted =
                        feedback(
                                acceptedFrame);
                try {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .OutgoingDisposition.ACCEPTED,
                            phone.receiveNormalFeedback(
                                    accepted));
                } finally {
                    accepted.destroy();
                    wipe(
                            acceptedFrame);
                }
            }
        }
    }

    @Test
    public void unrelatedServicesDoNotCollide() {
        try (NwServiceConnectorHandshake phone =
                     handshake(
                             0x13,
                             3000);
             NwServiceConnectorHandshake watch =
                     handshake(
                             0x53,
                             4000)) {
            bootstrapRemoteKeys(
                    phone,
                    watch);

            try (NwServiceConnectorHandshake.OutgoingStart control =
                         phone.startNormalRequest(
                                 uuid(
                                         0xe0),
                                 SERVICE);
                 NwServiceConnectorHandshake.OutgoingStart data =
                         watch.startNormalRequest(
                                 uuid(
                                         0x20),
                                 DATA_SERVICE)) {
                assertEquals(
                        1,
                        phone.pendingNormalRequestCount());
                NwServiceConnectorCodec.NormalStartRequest fromWatch =
                        normalRequest(
                                data);
                byte[] feedbackFrame = null;
                try (NwServiceConnectorHandshake.IncomingResult decision =
                             phone.receiveIncomingRequest(
                                     fromWatch,
                                     true)) {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition.ACCEPTED,
                            decision.disposition);
                    assertFalse(
                            decision.cancelOutgoingNormal);
                    assertNull(
                            decision.cancelOutgoingServiceName);
                    assertTrue(
                            phone.hasPendingNormalRequest(
                                    SERVICE));
                    assertEquals(
                            1,
                            phone.pendingNormalRequestCount());
                    feedbackFrame =
                            decision.feedbackFrame();
                } finally {
                    fromWatch.destroy();
                }

                NwServiceConnectorCodec.Feedback accepted =
                        feedback(
                                feedbackFrame);
                try {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .OutgoingDisposition.ACCEPTED,
                            watch.receiveNormalFeedback(
                                    accepted));
                    assertFalse(
                            watch.hasPendingNormalRequest());
                } finally {
                    accepted.destroy();
                    wipe(
                            feedbackFrame);
                }
            }
        }
    }

    @Test
    public void oneKeyProbeResolvesEveryQueuedIncomingService() {
        try (NwServiceConnectorHandshake phone =
                     handshake(
                             0x14,
                             100);
             NwServiceConnectorHandshake watch =
                     handshake(
                             0x54,
                             200);
             NwServiceConnectorHandshake.OutgoingStart control =
                     phone.startNormalRequest(
                             uuid(
                                     0x70),
                             SERVICE);
             NwServiceConnectorHandshake.OutgoingStart data =
                     phone.startNormalRequest(
                             uuid(
                                     0x80),
                             DATA_SERVICE)) {
            NwServiceConnectorCodec.NormalStartRequest controlRequest =
                    normalRequest(
                            control);
            NwServiceConnectorCodec.NormalStartRequest dataRequest =
                    normalRequest(
                            data);
            try (NwServiceConnectorHandshake.IncomingResult first =
                         watch.receiveIncomingRequest(
                                 controlRequest,
                                 true);
                 NwServiceConnectorHandshake.IncomingResult second =
                         watch.receiveIncomingRequest(
                                 dataRequest,
                                 true)) {
                assertEquals(
                        NwServiceConnectorHandshake
                                .IncomingDisposition
                                .WAITING_FOR_REMOTE_KEY,
                        first.disposition);
                assertEquals(
                        NwServiceConnectorHandshake
                                .IncomingDisposition
                                .WAITING_FOR_REMOTE_KEY,
                        second.disposition);
                assertTrue(
                        first.startReverseKeyProbe);
                assertFalse(
                        second.startReverseKeyProbe);
                assertEquals(
                        2,
                        watch.pendingIncomingRequestCount());
            } finally {
                controlRequest.destroy();
                dataRequest.destroy();
            }

            try (NwServiceConnectorHandshake.OutgoingStart probe =
                         watch.startKeyProbe()) {
                NwServiceConnectorCodec.OperationRequest operation =
                        operationRequest(
                                probe);
                byte[] keyFeedbackFrame = null;
                try (NwServiceConnectorHandshake.IncomingResult response =
                             phone.receiveIncomingRequest(
                                     operation,
                                     true)) {
                    keyFeedbackFrame =
                            response.feedbackFrame();
                } finally {
                    operation.destroy();
                }

                NwServiceConnectorCodec.Feedback keyFeedback =
                        feedback(
                                keyFeedbackFrame);
                try (NwServiceConnectorHandshake.IncomingBatch batch =
                             watch.receiveKeyProbeFeedback(
                                     keyFeedback)) {
                    assertEquals(
                            2,
                            batch.size());
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition.ACCEPTED,
                            batch.get(
                                    0).disposition);
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition.ACCEPTED,
                            batch.get(
                                    1).disposition);
                    assertFalse(
                            watch.hasPendingIncomingRequest());

                    for (int index = 0;
                            index < batch.size();
                            index++) {
                        byte[] acceptedFrame =
                                batch.get(
                                        index)
                                        .feedbackFrame();
                        NwServiceConnectorCodec.Feedback accepted =
                                feedback(
                                        acceptedFrame);
                        try {
                            assertEquals(
                                    NwServiceConnectorHandshake
                                            .OutgoingDisposition.ACCEPTED,
                                    phone.receiveNormalFeedback(
                                            accepted));
                        } finally {
                            accepted.destroy();
                            wipe(
                                    acceptedFrame);
                        }
                    }
                    assertFalse(
                            phone.hasPendingNormalRequest());
                } finally {
                    keyFeedback.destroy();
                    wipe(
                            keyFeedbackFrame);
                }
            }
        }
    }

    @Test
    public void normalAndCloudConsumeOneProcessGlobalSequence() {
        NwServiceConnectorSequenceAllocator sequences =
                new NwServiceConnectorSequenceAllocator(
                        55);
        byte[] normalPrivate =
                sequence(
                        0x20,
                        32);
        byte[] cloudPrivate =
                sequence(
                        0x60,
                        32);
        try (NwServiceConnectorHandshake normal =
                     NwServiceConnectorHandshake.withPrivateKeyForTest(
                             "fd00::2.61314",
                             61314,
                             normalPrivate,
                             sequences);
             NwServiceConnectorHandshake cloud =
                     NwServiceConnectorHandshake.withPrivateKeyForTest(
                             "fd00::2.61315",
                             61315,
                             cloudPrivate,
                             sequences);
             NwServiceConnectorHandshake.OutgoingStart normalStart =
                     normal.startNormalRequest(
                             uuid(
                                     0x11),
                             DATA_SERVICE);
             NwServiceConnectorHandshake.OutgoingStart cloudProbe =
                     cloud.startKeyProbe()) {
            assertEquals(
                    55,
                    normalStart.sequence);
            assertEquals(
                    56,
                    cloudProbe.sequence);
        } finally {
            wipe(
                    normalPrivate);
            wipe(
                    cloudPrivate);
        }
    }

    @Test
    public void activeConnectionArbitrationUsesUnsignedSequenceThenUuid() {
        assertTrue(
                NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                -1L,
                                uuid(
                                        0x10),
                                10,
                                uuid(
                                        0xf0)));
        assertFalse(
                NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                9,
                                uuid(
                                        0xf0),
                                10,
                                uuid(
                                        0x10)));
        assertTrue(
                NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                10,
                                uuid(
                                        0xf0),
                                10,
                                uuid(
                                        0x10)));
        assertFalse(
                NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                10,
                                uuid(
                                        0x10),
                                10,
                                uuid(
                                        0xf0)));
        assertFalse(
                NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                10,
                                uuid(
                                        0x10),
                                10,
                                uuid(
                                        0x10)));
    }

    @Test
    public void policyAndOperationOneProduceDistinctActions() {
        try (NwServiceConnectorHandshake phone =
                     handshake(
                             0x12,
                             10);
             NwServiceConnectorHandshake watch =
                     handshake(
                             0x52,
                             20)) {
            bootstrapRemoteKeys(
                    phone,
                    watch);

            try (NwServiceConnectorHandshake.OutgoingStart start =
                         phone.startNormalRequest(
                                 uuid(
                                         0x30),
                                 "forbidden")) {
                NwServiceConnectorCodec.NormalStartRequest request =
                        normalRequest(
                                start);
                byte[] frame = null;
                try (NwServiceConnectorHandshake.IncomingResult result =
                             watch.receiveIncomingRequest(
                                     request,
                                     false)) {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .IncomingDisposition
                                    .REJECTED_BY_POLICY,
                            result.disposition);
                    frame =
                            result.feedbackFrame();
                } finally {
                    request.destroy();
                }
                NwServiceConnectorCodec.Feedback rejected =
                        feedback(
                                frame);
                try {
                    assertEquals(
                            NwServiceConnectorHandshake
                                    .OutgoingDisposition
                                    .REJECTED_BY_POLICY,
                            phone.receiveNormalFeedback(
                                    rejected));
                } finally {
                    rejected.destroy();
                    wipe(
                            frame);
                }
            }

            byte[] operationOne =
                    signedOperationOne(
                            sequence(
                                    0x12,
                                    32),
                            61315,
                            99);
            NwServiceConnectorCodec.OperationRequest request =
                    (NwServiceConnectorCodec.OperationRequest)
                            NwServiceConnectorCodec.decode(
                                    operationOne);
            try (NwServiceConnectorHandshake.IncomingResult result =
                         watch.receiveIncomingRequest(
                                 request,
                                 true)) {
                assertTrue(
                        result.retryPendingOutgoing);
                assertEquals(
                        NwServiceConnectorHandshake
                                .IncomingDisposition
                                .REJECTED_TRANSIENT,
                        result.disposition);
            } finally {
                request.destroy();
                wipe(
                        operationOne);
            }
        }
    }

    private static void bootstrapRemoteKeys(
            NwServiceConnectorHandshake first,
            NwServiceConnectorHandshake second) {
        try (NwServiceConnectorHandshake.OutgoingStart probe =
                     first.startKeyProbe()) {
            NwServiceConnectorCodec.OperationRequest request =
                    operationRequest(
                            probe);
            byte[] responseFrame = null;
            try (NwServiceConnectorHandshake.IncomingResult response =
                         second.receiveIncomingRequest(
                                 request,
                                 true)) {
                responseFrame =
                        response.feedbackFrame();
            } finally {
                request.destroy();
            }
            NwServiceConnectorCodec.Feedback feedback =
                    feedback(
                            responseFrame);
            try (NwServiceConnectorHandshake.IncomingBatch ignored =
                         first.receiveKeyProbeFeedback(
                                 feedback)) {
                assertEquals(
                        0,
                        ignored.size());
            } finally {
                feedback.destroy();
                wipe(
                        responseFrame);
            }
        }

        try (NwServiceConnectorHandshake.OutgoingStart probe =
                     second.startKeyProbe()) {
            NwServiceConnectorCodec.OperationRequest request =
                    operationRequest(
                            probe);
            byte[] responseFrame = null;
            try (NwServiceConnectorHandshake.IncomingResult response =
                         first.receiveIncomingRequest(
                                 request,
                                 true)) {
                responseFrame =
                        response.feedbackFrame();
            } finally {
                request.destroy();
            }
            NwServiceConnectorCodec.Feedback feedback =
                    feedback(
                            responseFrame);
            try (NwServiceConnectorHandshake.IncomingBatch ignored =
                         second.receiveKeyProbeFeedback(
                                 feedback)) {
                assertEquals(
                        0,
                        ignored.size());
            } finally {
                feedback.destroy();
                wipe(
                        responseFrame);
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
                            ENDPOINT,
                            IdsControlChannelCodec.CONTROL_PORT,
                            privateKey,
                            sequence);
        } finally {
            wipe(
                    privateKey);
        }
    }

    private static NwServiceConnectorCodec.NormalStartRequest
            normalRequest(
            NwServiceConnectorHandshake.OutgoingStart start) {
        byte[] frame =
                start.frame();
        try {
            return (NwServiceConnectorCodec.NormalStartRequest)
                    NwServiceConnectorCodec.decode(
                            frame);
        } finally {
            wipe(
                    frame);
        }
    }

    private static NwServiceConnectorCodec.OperationRequest
            operationRequest(
            NwServiceConnectorHandshake.OutgoingStart start) {
        byte[] frame =
                start.frame();
        try {
            return (NwServiceConnectorCodec.OperationRequest)
                    NwServiceConnectorCodec.decode(
                            frame);
        } finally {
            wipe(
                    frame);
        }
    }

    private static NwServiceConnectorCodec.Feedback feedback(
            byte[] frame) {
        return (NwServiceConnectorCodec.Feedback)
                NwServiceConnectorCodec.decode(
                        frame);
    }

    private static byte[] signedOperationOne(
            byte[] privateKey,
            int localPort,
            long sequence) {
        byte[] frame =
                NwServiceConnectorCodec.encodeNoOpRequest(
                        localPort,
                        sequence,
                        privateKey);
        byte[] signature = null;
        try {
            Arrays.fill(
                    frame,
                    NwServiceConnectorCodec
                            .OPERATION_SIGNATURE_OFFSET,
                    NwServiceConnectorCodec
                            .OPERATION_CODE_OFFSET,
                    (byte) 0);
            frame[NwServiceConnectorCodec
                    .OPERATION_CODE_OFFSET] =
                    (byte) NwServiceConnectorCodec
                            .OPERATION_RETRY;
            signature =
                    AppleIkeEd25519Auth.sign(
                            privateKey,
                            frame);
            System.arraycopy(
                    signature,
                    0,
                    frame,
                    NwServiceConnectorCodec
                            .OPERATION_SIGNATURE_OFFSET,
                    signature.length);
            return frame;
        } finally {
            wipe(
                    privateKey);
            wipe(
                    signature);
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
                index < value.length;
                index++) {
            value[index] =
                    (byte) (first + index);
        }
        return value;
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
