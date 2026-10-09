package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NwServiceConnectorCodecTest {
    private static final byte[] REQUEST_UUID =
            hex(
                    "10 11 12 13 14 15 16 17 "
                            + "18 19 1a 1b 1c 1d 1e 1f");

    @Test
    public void normalRequestHasExactLayoutAndZeroSignatureTranscript() {
        byte[] privateKey =
                sequence(
                        0x20,
                        32);
        byte[] publicKey =
                publicKey(
                        privateKey);
        byte[] encoded =
                NwServiceConnectorCodec
                        .encodeNormalStartRequest(
                                0x1234,
                                0x0102_0304_0506_0708L,
                                REQUEST_UUID,
                                "ids-control-channel",
                                privateKey);
        NwServiceConnectorCodec.NormalStartRequest decoded =
                (NwServiceConnectorCodec.NormalStartRequest)
                        NwServiceConnectorCodec.decode(
                                encoded);
        byte[] transcript =
                NwServiceConnectorCodec.signingTranscript(
                        decoded);
        byte[] expectedTranscript =
                encoded.clone();
        Arrays.fill(
                expectedTranscript,
                NwServiceConnectorCodec.NORMAL_SERVICE_OFFSET
                        + "ids-control-channel"
                        .getBytes(StandardCharsets.UTF_8)
                        .length,
                expectedTranscript.length,
                (byte) 0);
        try {
            assertEquals(
                    19,
                    "ids-control-channel"
                            .getBytes(StandardCharsets.UTF_8)
                            .length);
            assertEquals(
                    112,
                    encoded.length);
            assertEquals(
                    110,
                    be16(
                            encoded,
                            0));
            assertEquals(
                    0x1234,
                    be16(
                            encoded,
                            2));
            assertEquals(
                    0x0102_0304_0506_0708L,
                    be64(
                            encoded,
                            4));
            assertArrayEquals(
                    REQUEST_UUID,
                    Arrays.copyOfRange(
                            encoded,
                            12,
                            28));
            assertEquals(
                    19,
                    encoded[28] & 0xff);
            assertArrayEquals(
                    ascii(
                            "ids-control-channel"),
                    Arrays.copyOfRange(
                            encoded,
                            29,
                            48));
            assertArrayEquals(
                    expectedTranscript,
                    transcript);
            assertEquals(
                    "ids-control-channel",
                    decoded.serviceName);
            assertArrayEquals(
                    REQUEST_UUID,
                    decoded.requestUuid);
            assertTrue(
                    NwServiceConnectorCodec
                            .verifySignature(
                                    decoded,
                                    publicKey));

            byte[] wrongPublic =
                    publicKey(
                            sequence(
                                    0x60,
                                    32));
            try {
                assertFalse(
                        NwServiceConnectorCodec
                                .verifySignature(
                                        decoded,
                                        wrongPublic));
            } finally {
                wipe(wrongPublic);
            }
        } finally {
            decoded.destroy();
            wipe(privateKey);
            wipe(publicKey);
            wipe(encoded);
            wipe(transcript);
            wipe(expectedTranscript);
        }
    }

    @Test
    public void noOpRequestHasExact81ByteLayoutAndValidSignature() {
        byte[] privateKey =
                sequence(
                        0x31,
                        32);
        byte[] publicKey =
                publicKey(
                        privateKey);
        byte[] encoded =
                NwServiceConnectorCodec
                        .encodeNoOpRequest(
                                0xef83,
                                0x1122_3344_5566_7788L,
                                privateKey);
        NwServiceConnectorCodec.OperationRequest decoded =
                (NwServiceConnectorCodec.OperationRequest)
                        NwServiceConnectorCodec.decode(
                                encoded);
        byte[] transcript =
                NwServiceConnectorCodec.signingTranscript(
                        decoded);
        try {
            assertEquals(
                    81,
                    encoded.length);
            assertEquals(
                    79,
                    be16(
                            encoded,
                            0));
            assertEquals(
                    0xef83,
                    be16(
                            encoded,
                            2));
            assertEquals(
                    0x1122_3344_5566_7788L,
                    be64(
                            encoded,
                            4));
            assertArrayEquals(
                    new byte[4],
                    Arrays.copyOfRange(
                            encoded,
                            12,
                            16));
            assertEquals(
                    0,
                    encoded[80] & 0xff);
            assertEquals(
                    NwServiceConnectorCodec.OPERATION_NO_OP,
                    decoded.operation);
            assertTrue(
                    NwServiceConnectorCodec
                            .verifySignature(
                                    decoded,
                                    publicKey));
            assertArrayEquals(
                    new byte[64],
                    Arrays.copyOfRange(
                            transcript,
                            16,
                            80));
            assertEquals(
                    0,
                    transcript[80] & 0xff);
        } finally {
            decoded.destroy();
            wipe(privateKey);
            wipe(publicKey);
            wipe(encoded);
            wipe(transcript);
        }
    }

    @Test
    public void decoderSupportsSignedRetryOperationWithoutEmittingIt() {
        byte[] privateKey =
                sequence(
                        0x41,
                        32);
        byte[] publicKey =
                publicKey(
                        privateKey);
        byte[] retry =
                NwServiceConnectorCodec
                        .encodeNoOpRequest(
                                61315,
                                7,
                                privateKey);
        byte[] signature = null;
        NwServiceConnectorCodec.OperationRequest decoded = null;
        try {
            Arrays.fill(
                    retry,
                    16,
                    80,
                    (byte) 0);
            retry[80] =
                    NwServiceConnectorCodec.OPERATION_RETRY;
            signature =
                    AppleIkeEd25519Auth.sign(
                            privateKey,
                            retry);
            System.arraycopy(
                    signature,
                    0,
                    retry,
                    16,
                    64);
            decoded =
                    (NwServiceConnectorCodec.OperationRequest)
                            NwServiceConnectorCodec.decode(
                                    retry);

            assertEquals(
                    NwServiceConnectorCodec.OPERATION_RETRY,
                    decoded.operation);
            assertTrue(
                    NwServiceConnectorCodec
                            .verifySignature(
                                    decoded,
                                    publicKey));
        } finally {
            if (decoded != null) {
                decoded.destroy();
            }
            wipe(privateKey);
            wipe(publicKey);
            wipe(retry);
            wipe(signature);
        }
    }

    @Test
    public void feedbackHasExact44ByteLayoutAndThreeStatuses() {
        byte[] publicKey =
                sequence(
                        0,
                        32);
        byte[] accepted =
                NwServiceConnectorCodec.encodeFeedback(
                        0x0102_0304_0506_0708L,
                        NwServiceConnectorCodec
                                .FeedbackDisposition.ACCEPTED,
                        publicKey);
        NwServiceConnectorCodec.Feedback decoded =
                (NwServiceConnectorCodec.Feedback)
                        NwServiceConnectorCodec.decode(
                                accepted);
        try {
            assertArrayEquals(
                    hex(
                            "00 2a 80 00 "
                                    + "01 02 03 04 05 06 07 08 "
                                    + "00 01 02 03 04 05 06 07 "
                                    + "08 09 0a 0b 0c 0d 0e 0f "
                                    + "10 11 12 13 14 15 16 17 "
                                    + "18 19 1a 1b 1c 1d 1e 1f"),
                    accepted);
            assertEquals(
                    NwServiceConnectorCodec
                            .FeedbackDisposition.ACCEPTED,
                    decoded.disposition);
            assertEquals(
                    0x0102_0304_0506_0708L,
                    decoded.sequence);
            assertArrayEquals(
                    publicKey,
                    decoded.publicEd25519);

            for (NwServiceConnectorCodec.FeedbackDisposition
                    disposition :
                    NwServiceConnectorCodec
                            .FeedbackDisposition.values()) {
                byte[] frame =
                        NwServiceConnectorCodec.encodeFeedback(
                                9,
                                disposition,
                                publicKey);
                NwServiceConnectorCodec.Feedback message =
                        (NwServiceConnectorCodec.Feedback)
                                NwServiceConnectorCodec.decode(
                                        frame);
                try {
                    assertEquals(
                            disposition,
                            message.disposition);
                    assertEquals(
                            disposition.flags,
                            frame[2] & 0xff);
                    assertEquals(
                            0,
                            frame[3] & 0xff);
                } finally {
                    message.destroy();
                    wipe(frame);
                }
            }
        } finally {
            decoded.destroy();
            wipe(publicKey);
            wipe(accepted);
        }
    }

    @Test
    public void streamDecoderHandlesFragmentedAndCoalescedRecords() {
        byte[] privateKey =
                sequence(
                        0x51,
                        32);
        byte[] publicKey =
                publicKey(
                        privateKey);
        byte[] normal =
                NwServiceConnectorCodec
                        .encodeNormalStartRequest(
                                61315,
                                11,
                                REQUEST_UUID,
                                "ids-control-channel",
                                privateKey);
        byte[] noOp =
                NwServiceConnectorCodec
                        .encodeNoOpRequest(
                                61314,
                                12,
                                privateKey);
        byte[] feedback =
                NwServiceConnectorCodec.encodeFeedback(
                        13,
                        NwServiceConnectorCodec
                                .FeedbackDisposition
                                .TRANSIENT_REJECTION,
                        publicKey);
        byte[] all =
                concatenate(
                        normal,
                        noOp,
                        feedback);
        List<NwServiceConnectorCodec.Message> messages =
                new ArrayList<>();
        try (NwServiceConnectorCodec.StreamDecoder decoder =
                     new NwServiceConnectorCodec.StreamDecoder()) {
            messages.addAll(
                    decoder.push(
                            Arrays.copyOfRange(
                                    all,
                                    0,
                                    1)));
            assertTrue(
                    messages.isEmpty());
            assertEquals(
                    1,
                    decoder.bufferedLength());

            messages.addAll(
                    decoder.push(
                            Arrays.copyOfRange(
                                    all,
                                    1,
                                    normal.length + 17)));
            assertEquals(
                    1,
                    messages.size());
            assertTrue(
                    messages.get(0)
                            instanceof NwServiceConnectorCodec
                            .NormalStartRequest);
            assertEquals(
                    17,
                    decoder.bufferedLength());

            messages.addAll(
                    decoder.push(
                            Arrays.copyOfRange(
                                    all,
                                    normal.length + 17,
                                    all.length)));
            assertEquals(
                    3,
                    messages.size());
            assertTrue(
                    messages.get(1)
                            instanceof NwServiceConnectorCodec
                            .OperationRequest);
            assertTrue(
                    messages.get(2)
                            instanceof NwServiceConnectorCodec
                            .Feedback);
            assertEquals(
                    0,
                    decoder.bufferedLength());
        } finally {
            destroyAll(
                    messages);
            wipe(privateKey);
            wipe(publicKey);
            wipe(normal);
            wipe(noOp);
            wipe(feedback);
            wipe(all);
        }
    }

    @Test
    public void remoteKeyStoreImplementsSequenceAndBootstrapDecision() {
        byte[] privateA =
                sequence(
                        0x61,
                        32);
        byte[] publicA =
                publicKey(
                        privateA);
        byte[] privateB =
                sequence(
                        0x71,
                        32);
        byte[] publicB =
                publicKey(
                        privateB);
        byte[] signedA =
                NwServiceConnectorCodec
                        .encodeNormalStartRequest(
                                61315,
                                0x20,
                                REQUEST_UUID,
                                "ids-control-channel",
                                privateA);
        byte[] staleB =
                NwServiceConnectorCodec
                        .encodeNormalStartRequest(
                                61315,
                                0x10,
                                REQUEST_UUID,
                                "ids-control-channel",
                                privateB);
        NwServiceConnectorCodec.NormalStartRequest requestA =
                (NwServiceConnectorCodec.NormalStartRequest)
                        NwServiceConnectorCodec.decode(
                                signedA);
        NwServiceConnectorCodec.NormalStartRequest requestB =
                (NwServiceConnectorCodec.NormalStartRequest)
                        NwServiceConnectorCodec.decode(
                                staleB);
        NwServiceConnectorCodec.Feedback keyA =
                feedback(
                        0x10,
                        publicA);
        NwServiceConnectorCodec.Feedback olderB =
                feedback(
                        0x0f,
                        publicB);
        NwServiceConnectorCodec.Feedback equalB =
                feedback(
                        0x10,
                        publicB);
        try (NwServiceConnectorCodec.RemotePublicKeyStore store =
                     new NwServiceConnectorCodec.RemotePublicKeyStore()) {
            assertEquals(
                    NwServiceConnectorCodec
                            .VerificationDecision.NEEDS_NEWER_KEY,
                    store.evaluate(
                            "peer-v6",
                            requestA));
            assertEquals(
                    NwServiceConnectorCodec
                            .KeyUpdate.STORED_FIRST,
                    store.observeFeedback(
                            "peer-v6",
                            keyA));
            assertEquals(
                    NwServiceConnectorCodec
                            .VerificationDecision.VERIFIED,
                    store.evaluate(
                            "peer-v6",
                            requestA));
            assertEquals(
                    NwServiceConnectorCodec
                            .VerificationDecision
                            .REJECT_STALE_OR_FORGED,
                    store.evaluate(
                            "peer-v6",
                            requestB));
            assertEquals(
                    NwServiceConnectorCodec
                            .KeyUpdate.IGNORED_OLDER,
                    store.observeFeedback(
                            "peer-v6",
                            olderB));
            assertEquals(
                    NwServiceConnectorCodec
                            .KeyUpdate.STORED_REPLACED_KEY,
                    store.observeFeedback(
                            "peer-v6",
                            equalB));
            assertEquals(
                    NwServiceConnectorCodec
                            .VerificationDecision.VERIFIED,
                    store.evaluate(
                            "peer-v6",
                            requestB));
            assertEquals(
                    1,
                    store.size());
        } finally {
            requestA.destroy();
            requestB.destroy();
            keyA.destroy();
            olderB.destroy();
            equalB.destroy();
            wipe(privateA);
            wipe(publicA);
            wipe(privateB);
            wipe(publicB);
            wipe(signedA);
            wipe(staleB);
        }
    }

    @Test
    public void malformedFramesAreRejectedAndPoisonStreamDecoder() {
        byte[] publicKey =
                sequence(
                        0,
                        32);
        byte[] feedback =
                NwServiceConnectorCodec.encodeFeedback(
                        1,
                        NwServiceConnectorCodec
                                .FeedbackDisposition.ACCEPTED,
                        publicKey);
        byte[] reserved =
                feedback.clone();
        reserved[3] = 1;
        byte[] conflicting =
                feedback.clone();
        conflicting[2] = (byte) 0xc0;
        byte[] wrongLength =
                feedback.clone();
        wrongLength[1] = 0x29;
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NwServiceConnectorCodec.decode(
                            reserved));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NwServiceConnectorCodec.decode(
                            conflicting));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NwServiceConnectorCodec.decode(
                            wrongLength));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NwServiceConnectorCodec
                            .encodeNormalStartRequest(
                                    1,
                                    2,
                                    new byte[16],
                                    "service",
                                    new byte[32]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NwServiceConnectorCodec
                            .encodeNormalStartRequest(
                                    1,
                                    2,
                                    REQUEST_UUID,
                                    "bad\u0000service",
                                    new byte[32]));

            try (NwServiceConnectorCodec.StreamDecoder decoder =
                         new NwServiceConnectorCodec.StreamDecoder()) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> decoder.push(
                                new byte[]{
                                        0, 43
                                }));
                assertThrows(
                        IllegalStateException.class,
                        () -> decoder.push(
                                feedback));
            }
        } finally {
            wipe(publicKey);
            wipe(feedback);
            wipe(reserved);
            wipe(conflicting);
            wipe(wrongLength);
        }
    }

    private static NwServiceConnectorCodec.Feedback feedback(
            long sequence,
            byte[] publicKey) {
        byte[] frame =
                NwServiceConnectorCodec.encodeFeedback(
                        sequence,
                        NwServiceConnectorCodec
                                .FeedbackDisposition
                                .TRANSIENT_REJECTION,
                        publicKey);
        try {
            return (NwServiceConnectorCodec.Feedback)
                    NwServiceConnectorCodec.decode(
                            frame);
        } finally {
            wipe(frame);
        }
    }

    private static byte[] publicKey(
            byte[] privateKey) {
        return new Ed25519PrivateKeyParameters(
                privateKey,
                0)
                .generatePublicKey()
                .getEncoded();
    }

    private static void destroyAll(
            List<NwServiceConnectorCodec.Message> messages) {
        for (NwServiceConnectorCodec.Message message : messages) {
            message.destroy();
        }
    }

    private static long be64(
            byte[] bytes,
            int offset) {
        long value = 0;
        for (int index = 0;
                index < 8;
                index++) {
            value =
                    (value << 8)
                            | (bytes[offset + index]
                            & 0xffL);
        }
        return value;
    }

    private static int be16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output =
                new byte[length];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
    }

    private static byte[] ascii(
            String value) {
        return value.getBytes(
                StandardCharsets.US_ASCII);
    }

    private static byte[] concatenate(
            byte[]... values) {
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
}
