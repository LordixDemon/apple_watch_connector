package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class NanoRegistryClassDCodecTest {
    @Test
    public void ultra2ModernPairingModeMatchesIndependentWireVector() {
        NanoRegistryClassDCodec.PairingModeRequest request =
                NanoRegistryClassDCodec.PairingModeRequest
                        .modernIos26_6Ultra2(
                                NanoRegistryClassDCodec
                                        .COMPATIBILITY_STATE_CONFIGURE);
        byte[] encoded =
                NanoRegistryClassDCodec.encode(
                        request);
        NanoRegistryClassDCodec.PairingModeRequest decoded =
                NanoRegistryClassDCodec.decodePairingModeRequest(
                        encoded);
        try {
            assertArrayEquals(
                    hex(
                            "08 03 10 1a 18 1a 20 19"),
                    encoded);
            assertEquals(
                    NanoRegistryClassDCodec
                            .COMPATIBILITY_STATE_CONFIGURE,
                    decoded.pairingMode);
            assertEquals(
                    Integer.valueOf(
                            26),
                    decoded.phonePairingProtocolVersionMax);
            assertEquals(
                    Integer.valueOf(
                            26),
                    decoded.watchPairingProtocolVersion);
            assertEquals(
                    Integer.valueOf(
                            25),
                    decoded.phonePairingProtocolVersionMin);
            assertTrue(
                    decoded.hasAllVersionMetadata());
            assertTrue(
                    decoded.matchesIos26_6Ultra2VersionTuple());
            assertFalse(
                    decoded.response());
        } finally {
            wipe(
                    encoded);
        }
    }

    @Test
    public void ultra2FreshSetupStateVectorsCoverOptionalCheckAndNormal() {
        NanoRegistryClassDCodec.PairingModeRequest check =
                NanoRegistryClassDCodec.PairingModeRequest
                        .modernIos26_6Ultra2(
                                NanoRegistryClassDCodec
                                        .COMPATIBILITY_STATE_CHECK);
        NanoRegistryClassDCodec.PairingModeRequest normal =
                NanoRegistryClassDCodec.PairingModeRequest
                        .modernIos26_6Ultra2(
                                NanoRegistryClassDCodec
                                        .COMPATIBILITY_STATE_NORMAL);
        byte[] checkBytes =
                NanoRegistryClassDCodec.encode(
                        check);
        byte[] normalBytes =
                NanoRegistryClassDCodec.encode(
                        normal);
        try {
            assertArrayEquals(
                    hex(
                            "08 01 10 1a 18 1a 20 19"),
                    checkBytes);
            assertArrayEquals(
                    hex(
                            "08 04 10 1a 18 1a 20 19"),
                    normalBytes);
        } finally {
            wipe(
                    checkBytes);
            wipe(
                    normalBytes);
        }
    }

    @Test
    public void legacyPairingModeAndGeneratedResponsePreservePresence() {
        NanoRegistryClassDCodec.PairingModeRequest legacy =
                new NanoRegistryClassDCodec.PairingModeRequest(
                        4,
                        null,
                        null,
                        null);
        byte[] legacyBytes =
                NanoRegistryClassDCodec.encode(
                        legacy);
        NanoRegistryClassDCodec.PairingModeRequest decodedLegacy =
                NanoRegistryClassDCodec.decodePairingModeRequest(
                        legacyBytes);
        NanoRegistryClassDCodec.PairingModeResponse response =
                new NanoRegistryClassDCodec.PairingModeResponse(
                        false);
        byte[] responseBytes =
                NanoRegistryClassDCodec.encode(
                        response);
        NanoRegistryClassDCodec.PairingModeResponse decodedResponse =
                NanoRegistryClassDCodec.decodePairingModeResponse(
                        responseBytes);
        try {
            assertArrayEquals(
                    hex(
                            "08 04"),
                    legacyBytes);
            assertFalse(
                    decodedLegacy.hasAllVersionMetadata());
            assertNull(
                    decodedLegacy.phonePairingProtocolVersionMax);
            assertNull(
                    decodedLegacy.watchPairingProtocolVersion);
            assertNull(
                    decodedLegacy.phonePairingProtocolVersionMin);

            assertArrayEquals(
                    hex(
                            "08 00"),
                    responseBytes);
            assertFalse(
                    decodedResponse.success);
            assertTrue(
                    decodedResponse.response());
        } finally {
            wipe(
                    legacyBytes);
            wipe(
                    responseBytes);
        }
    }

    @Test
    public void unpairSchemaRoundTripsAllOptionalFields() {
        NanoRegistryClassDCodec.DeviceWillUnpairRequest request =
                new NanoRegistryClassDCodec.DeviceWillUnpairRequest(
                        "Apple Watch",
                        true,
                        -1,
                        "abort",
                        false,
                        true);
        byte[] encoded =
                NanoRegistryClassDCodec.encode(
                        request);
        NanoRegistryClassDCodec.DeviceWillUnpairRequest decoded =
                NanoRegistryClassDCodec
                        .decodeDeviceWillUnpairRequest(
                                encoded);
        NanoRegistryClassDCodec.DeviceWillUnpairResponse response =
                new NanoRegistryClassDCodec.DeviceWillUnpairResponse();
        byte[] responseBytes =
                NanoRegistryClassDCodec.encode(
                        response);
        try {
            assertArrayEquals(
                    hex(
                            "0a 0b "
                                    + "41 70 70 6c 65 20 "
                                    + "57 61 74 63 68 "
                                    + "10 01 "
                                    + "18 ff ff ff ff ff ff ff ff ff 01 "
                                    + "22 05 61 62 6f 72 74 "
                                    + "28 00 "
                                    + "30 01"),
                    encoded);
            assertEquals(
                    "Apple Watch",
                    decoded.advertisedName);
            assertEquals(
                    Boolean.TRUE,
                    decoded.shouldObliterate);
            assertEquals(
                    Integer.valueOf(
                            -1),
                    decoded.pairingFailureCode);
            assertEquals(
                    "abort",
                    decoded.abortReason);
            assertEquals(
                    Boolean.FALSE,
                    decoded.shouldBrick);
            assertEquals(
                    Boolean.TRUE,
                    decoded.shouldPreserveEsim);
            assertArrayEquals(
                    new byte[0],
                    responseBytes);
            assertTrue(
                    response.response());
        } finally {
            wipe(
                    encoded);
            wipe(
                    responseBytes);
        }
    }

    @Test
    public void pingRequestAndResponseMatchIndependentWireVectors() {
        byte[] requestPayload =
                hex(
                        "de ad");
        NanoRegistryClassDCodec.PingRequest request =
                new NanoRegistryClassDCodec.PingRequest(
                        300,
                        1.5,
                        2,
                        requestPayload);
        byte[] requestBytes =
                NanoRegistryClassDCodec.encode(
                        request);
        NanoRegistryClassDCodec.PingRequest decodedRequest =
                NanoRegistryClassDCodec.decodePingRequest(
                        requestBytes);

        byte[] responsePayload =
                hex(
                        "ff");
        NanoRegistryClassDCodec.PingResponse response =
                new NanoRegistryClassDCodec.PingResponse(
                        2.0,
                        responsePayload);
        byte[] responseBytes =
                NanoRegistryClassDCodec.encode(
                        response);
        NanoRegistryClassDCodec.PingResponse decodedResponse =
                NanoRegistryClassDCodec.decodePingResponse(
                        responseBytes);
        try {
            assertArrayEquals(
                    hex(
                            "08 ac 02 "
                                    + "11 00 00 00 00 00 00 f8 3f "
                                    + "18 02 "
                                    + "22 02 de ad"),
                    requestBytes);
            assertEquals(
                    300,
                    decodedRequest.responseIdsPriority);
            assertEquals(
                    1.5,
                    decodedRequest.timeout,
                    0);
            assertEquals(
                    2,
                    decodedRequest.pingType);
            assertArrayEquals(
                    requestPayload,
                    decodedRequest.payload);

            assertArrayEquals(
                    hex(
                            "09 00 00 00 00 00 00 00 40 "
                                    + "12 01 ff"),
                    responseBytes);
            assertEquals(
                    2.0,
                    decodedResponse.responseDate,
                    0);
            assertArrayEquals(
                    responsePayload,
                    decodedResponse.payload);
        } finally {
            request.destroy();
            decodedRequest.destroy();
            response.destroy();
            decodedResponse.destroy();
            wipe(
                    requestPayload);
            wipe(
                    responsePayload);
            wipe(
                    requestBytes);
            wipe(
                    responseBytes);
        }
    }

    @Test
    public void remainingProvenRequestSchemasRoundTrip() {
        NanoRegistryClassDCodec.WatchMigrationCompletionRequest migration =
                new NanoRegistryClassDCodec
                        .WatchMigrationCompletionRequest(
                        7);
        byte[] migrationBytes =
                NanoRegistryClassDCodec.encode(
                        migration);
        NanoRegistryClassDCodec.WatchMigrationCompletionRequest
                decodedMigration =
                NanoRegistryClassDCodec
                        .decodeWatchMigrationCompletionRequest(
                                migrationBytes);

        NanoRegistryClassDCodec.RtcMigrationMetricSessionId rtc =
                new NanoRegistryClassDCodec
                        .RtcMigrationMetricSessionId(
                        "rtc");
        byte[] rtcBytes =
                NanoRegistryClassDCodec.encode(
                        rtc);
        NanoRegistryClassDCodec.RtcMigrationMetricSessionId decodedRtc =
                NanoRegistryClassDCodec
                        .decodeRtcMigrationMetricSessionId(
                                rtcBytes);

        String fixedSessionId =
                "123e4567-e89b-12d3-a456-426614174000";
        NanoRegistryClassDCodec.PairingSessionId session =
                new NanoRegistryClassDCodec.PairingSessionId(
                        fixedSessionId);
        byte[] sessionBytes =
                NanoRegistryClassDCodec.encode(
                        session);
        NanoRegistryClassDCodec.PairingSessionId decodedSession =
                NanoRegistryClassDCodec.decodePairingSessionId(
                        sessionBytes);

        NanoRegistryClassDCodec.GraduationRequest graduation =
                new NanoRegistryClassDCodec.GraduationRequest();
        byte[] graduationBytes =
                NanoRegistryClassDCodec.encode(
                        graduation);
        try {
            assertArrayEquals(
                    hex(
                            "08 07"),
                    migrationBytes);
            assertEquals(
                    Integer.valueOf(
                            7),
                    decodedMigration.status);

            assertArrayEquals(
                    hex(
                            "0a 03 72 74 63"),
                    rtcBytes);
            assertEquals(
                    "rtc",
                    decodedRtc.sessionId);

            assertArrayEquals(
                    concat(
                            hex(
                                    "0a 24"),
                            fixedSessionId.getBytes(
                                    StandardCharsets.UTF_8)),
                    sessionBytes);
            assertEquals(
                    fixedSessionId,
                    decodedSession.pairingSessionId);

            assertArrayEquals(
                    new byte[0],
                    graduationBytes);
        } finally {
            wipe(
                    migrationBytes);
            wipe(
                    rtcBytes);
            wipe(
                    sessionBytes);
            wipe(
                    graduationBytes);
        }
    }

    @Test
    public void idsEnvelopePreservesTypeDirectionAndRuntimeFields() {
        NanoRegistryClassDCodec.PairingModeRequest request =
                NanoRegistryClassDCodec.PairingModeRequest
                        .modernIos26_6Ultra2(
                                3);
        IdsSocketPairCodec.ProtobufMessage envelope =
                NanoRegistryClassDCodec.envelope(
                        9,
                        0x2345,
                        IdsSocketPairCodec.FLAG_HAS_TOPIC,
                        null,
                        "00112233-4455-6677-8899-AABBCCDDEEFF",
                        NanoRegistryClassDCodec.SERVICE,
                        request,
                        null);
        byte[] frame =
                IdsSocketPairCodec.encodeProtobuf(
                        envelope);
        IdsSocketPairCodec.ProtobufMessage decodedEnvelope =
                (IdsSocketPairCodec.ProtobufMessage)
                        IdsSocketPairCodec.decode(
                                frame);
        NanoRegistryClassDCodec.ApplicationMessage decoded =
                NanoRegistryClassDCodec.decode(
                        decodedEnvelope);
        try {
            assertEquals(
                    9,
                    decodedEnvelope.sequence);
            assertEquals(
                    0x2345,
                    decodedEnvelope.streamId);
            assertEquals(
                    NanoRegistryClassDCodec.SERVICE,
                    decodedEnvelope.topic);
            assertEquals(
                    NanoRegistryClassDCodec.TYPE_PAIRING_MODE,
                    decodedEnvelope.protobufType);
            assertFalse(
                    decodedEnvelope.response);
            assertTrue(
                    decoded instanceof NanoRegistryClassDCodec
                            .PairingModeRequest);
            assertEquals(
                    3,
                    ((NanoRegistryClassDCodec.PairingModeRequest)
                            decoded).pairingMode);
        } finally {
            envelope.destroy();
            decodedEnvelope.destroy();
            wipe(
                    frame);
        }
    }

    @Test
    public void unsupportedDirectionsAndTypesFailClosed() {
        int[] requestOnlyTypes = {
                NanoRegistryClassDCodec.TYPE_WATCH_MIGRATION_COMPLETION,
                NanoRegistryClassDCodec
                        .TYPE_RTC_MIGRATION_METRIC_SESSION_ID,
                NanoRegistryClassDCodec.TYPE_PAIRING_SESSION_ID,
                NanoRegistryClassDCodec.TYPE_GRADUATION
        };
        for (int type : requestOnlyTypes) {
            IdsSocketPairCodec.ProtobufMessage envelope =
                    protobufEnvelope(
                            type,
                            true,
                            new byte[0]);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NanoRegistryClassDCodec.decode(
                                envelope));
            } finally {
                envelope.destroy();
            }
        }

        IdsSocketPairCodec.ProtobufMessage unknown =
                protobufEnvelope(
                        2,
                        false,
                        new byte[0]);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NanoRegistryClassDCodec.decode(
                            unknown));
        } finally {
            unknown.destroy();
        }
    }

    @Test
    public void malformedPayloadsFailClosedAndUnknownFieldsAreSkipped() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingModeRequest(
                                new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingModeRequest(
                                hex(
                                        "08 01 08 02")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingModeRequest(
                                hex(
                                        "0a 00")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingModeRequest(
                                hex(
                                        "08 06")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingModeResponse(
                                hex(
                                        "08 02")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodeDeviceWillUnpairRequest(
                                new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePingRequest(
                                hex(
                                        "08 01")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingSessionId(
                                hex(
                                        "0a 02 00")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryClassDCodec
                        .decodePairingSessionId(
                                hex(
                                        "0a 08 6e 6f 74 2d 75 75 69 64")));

        NanoRegistryClassDCodec.PairingModeRequest withUnknown =
                NanoRegistryClassDCodec.decodePairingModeRequest(
                        hex(
                                "08 05 10 1a 18 1a 20 19 78 01"));
        assertEquals(
                5,
                withUnknown.pairingMode);
        assertTrue(
                withUnknown.matchesIos26_6Ultra2VersionTuple());

        NanoRegistryClassDCodec.GraduationRequest graduation =
                NanoRegistryClassDCodec.decodeGraduationRequest(
                        hex(
                                "78 01"));
        assertEquals(
                NanoRegistryClassDCodec.TYPE_GRADUATION,
                graduation.protobufType());
    }

    @Test
    public void pairingSessionIdRequiresCanonicalCallerSuppliedUuid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NanoRegistryClassDCodec.PairingSessionId(
                        "not-a-uuid"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NanoRegistryClassDCodec.PairingSessionId(
                        ""));

        UUID fixed =
                UUID.fromString(
                        "123e4567-e89b-12d3-a456-426614174000");
        NanoRegistryClassDCodec.PairingSessionId fromUuid =
                NanoRegistryClassDCodec.PairingSessionId.fromUuid(
                        fixed);
        assertEquals(
                fixed.toString(),
                fromUuid.pairingSessionId);

        NanoRegistryClassDCodec.PairingSessionId absent =
                new NanoRegistryClassDCodec.PairingSessionId(
                        null);
        byte[] absentBytes =
                NanoRegistryClassDCodec.encode(
                        absent);
        try {
            assertArrayEquals(
                    new byte[0],
                    absentBytes);
        } finally {
            wipe(
                    absentBytes);
        }
    }

    private static IdsSocketPairCodec.ProtobufMessage protobufEnvelope(
            int type,
            boolean response,
            byte[] payload) {
        return new IdsSocketPairCodec.ProtobufMessage(
                1,
                2,
                0,
                response
                        ? "request-id"
                        : null,
                "00112233-4455-6677-8899-AABBCCDDEEFF",
                null,
                type,
                response,
                payload,
                null);
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replaceAll(
                        "\\s+",
                        "");
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Odd hex length");
        }
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

    private static byte[] concat(
            byte[] first,
            byte[] second) {
        byte[] output =
                Arrays.copyOf(
                        first,
                        first.length + second.length);
        System.arraycopy(
                second,
                0,
                output,
                first.length,
                second.length);
        wipe(
                first);
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
