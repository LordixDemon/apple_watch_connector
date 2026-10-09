package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class PbBridgeCodecTest {
    @Test
    public void buddyFinishedPushMatchesFirmwareWireVector() {
        var message = new PbBridgeCodec.PushBuddyFinished();
        assertEquals(3, message.protobufType());
        assertFalse(message.response());
        assertArrayEquals(hex("08 0a"), PbBridgeCodec.encode(message));
    }

    @Test
    public void activationFetchMatchesIndependentWireVector() {
        PbBridgeCodec.ActivationFetchRequest request =
                new PbBridgeCodec.ActivationFetchRequest(
                        hex("01 02 03"));
        byte[] encoded =
                PbBridgeCodec.encode(
                        request);
        PbBridgeCodec.ActivationFetchRequest decoded =
                PbBridgeCodec.decodeActivationFetch(
                        encoded);
        try {
            assertArrayEquals(
                    hex("0a 03 01 02 03"),
                    encoded);
            assertArrayEquals(
                    hex("01 02 03"),
                    decoded.archivedRequest());
            assertEquals(
                    PbBridgeCodec.TYPE_PROXY_ACTIVATION,
                    request.protobufType());
            assertFalse(
                    request.response());
        } finally {
            request.destroy();
            decoded.destroy();
            wipe(encoded);
        }
    }

    @Test
    public void activationDataMatchesIndependentWireVector() {
        PbBridgeCodec.ActivationData transfer =
                new PbBridgeCodec.ActivationData(
                        hex("de ad"),
                        hex("01 02 03"));
        byte[] encoded =
                PbBridgeCodec.encode(
                        transfer);
        PbBridgeCodec.ActivationData decoded =
                PbBridgeCodec.decodeActivationData(
                        encoded);
        try {
            assertArrayEquals(
                    hex("0a 02 de ad 12 03 01 02 03"),
                    encoded);
            assertArrayEquals(
                    hex("de ad"),
                    decoded.activationData());
            assertArrayEquals(
                    hex("01 02 03"),
                    decoded.archivedResponseHeaders());
            assertFalse(
                    transfer.response());
        } finally {
            transfer.destroy();
            decoded.destroy();
            wipe(encoded);
        }
    }

    @Test
    public void activationOutcomeMessagesMatchWatchWireVectors() {
        PbBridgeCodec.ActivationSucceeded success =
                new PbBridgeCodec.ActivationSucceeded();
        PbBridgeCodec.ActivationFailed failure =
                new PbBridgeCodec.ActivationFailed(
                        "failed");
        byte[] successBytes =
                PbBridgeCodec.encode(
                        success);
        byte[] failureBytes =
                PbBridgeCodec.encode(
                        failure);
        PbBridgeCodec.ActivationFailed decoded =
                PbBridgeCodec.decodeActivationFailed(
                        failureBytes);
        try {
            assertArrayEquals(
                    new byte[0],
                    successBytes);
            assertArrayEquals(
                    hex("0a 06 66 61 69 6c 65 64"),
                    failureBytes);
            assertEquals(
                    PbBridgeCodec.TYPE_ACTIVATION_SUCCEEDED,
                    success.protobufType());
            assertEquals(
                    PbBridgeCodec.TYPE_ACTIVATION_FAILED,
                    failure.protobufType());
            assertEquals(
                    "failed",
                    decoded.failureDescription());
            assertFalse(
                    success.response());
            assertFalse(
                    failure.response());
        } finally {
            wipe(successBytes);
            wipe(failureBytes);
        }
    }

    @Test
    public void beganActivatingEncodesEmptyAndValidatesEnvelope() {
        PbBridgeCodec.BeganActivating began =
                new PbBridgeCodec.BeganActivating();
        byte[] encoded =
                PbBridgeCodec.encode(
                        began);
        try {
            assertArrayEquals(
                    new byte[0],
                    encoded);
            assertEquals(
                    PbBridgeCodec.TYPE_BEGAN_ACTIVATING,
                    began.protobufType());
            assertFalse(
                    began.response());

            IdsSocketPairCodec.ProtobufMessage envelope =
                    PbBridgeCodec.envelope(
                            1,
                            1,
                            IdsSocketPairCodec.FLAG_HAS_TOPIC,
                            null,
                            "10000000-0000-4000-8000-000000000001",
                            PbBridgeCodec.SERVICE,
                            began,
                            null);
            try {
                PbBridgeCodec.validateEnvelope(
                        envelope);
            } finally {
                envelope.destroy();
            }
        } finally {
            wipe(encoded);
        }
    }

    @Test
    public void languageStatusUsesDoubleFixed64AndDistinguishesRelaunch() {
        PbBridgeCodec.LanguageAndLocaleStatus withoutRelaunch =
                new PbBridgeCodec.LanguageAndLocaleStatus(
                        1);
        PbBridgeCodec.LanguageAndLocaleStatus afterRelaunch =
                new PbBridgeCodec.LanguageAndLocaleStatus(
                        2);
        PbBridgeCodec.LanguageAndLocaleStatus unknown =
                new PbBridgeCodec.LanguageAndLocaleStatus(
                        3);
        byte[] withoutRelaunchBytes =
                PbBridgeCodec.encode(
                        withoutRelaunch);
        byte[] afterRelaunchBytes =
                PbBridgeCodec.encode(
                        afterRelaunch);
        PbBridgeCodec.LanguageAndLocaleStatus decodedWithoutRelaunch =
                PbBridgeCodec.decodeLanguageAndLocaleStatus(
                        withoutRelaunchBytes);
        PbBridgeCodec.LanguageAndLocaleStatus decodedAfterRelaunch =
                PbBridgeCodec.decodeLanguageAndLocaleStatus(
                        afterRelaunchBytes);
        try {
            assertArrayEquals(
                    hex("09 00 00 00 00 00 00 f0 3f"),
                    withoutRelaunchBytes);
            assertArrayEquals(
                    hex("09 00 00 00 00 00 00 00 40"),
                    afterRelaunchBytes);
            assertEquals(
                    1,
                    decodedWithoutRelaunch.status());
            assertEquals(
                    2,
                    decodedAfterRelaunch.status());
            assertTrue(
                    decodedWithoutRelaunch.completedWithoutRelaunch());
            assertFalse(
                    decodedWithoutRelaunch.completedAfterRelaunch());
            assertTrue(
                    decodedWithoutRelaunch.recognizedCompletion());
            assertFalse(
                    decodedAfterRelaunch.completedWithoutRelaunch());
            assertTrue(
                    decodedAfterRelaunch.completedAfterRelaunch());
            assertTrue(
                    decodedAfterRelaunch.recognizedCompletion());
            assertFalse(
                    unknown.recognizedCompletion());
            assertFalse(
                    withoutRelaunch.response());
            assertFalse(
                    afterRelaunch.response());
        } finally {
            wipe(withoutRelaunchBytes);
            wipe(afterRelaunchBytes);
        }
    }

    @Test
    public void languageLocaleAndTimezoneMatchIndependentWireVectors() {
        byte[] preferences =
                LocalePreferencesArchiveCodec.encodeSetupPreferences(
                        List.of(
                                "en-US",
                                "uk-UA"),
                        "uk_UA");
        PbBridgeCodec.LanguageAndLocale settings =
                new PbBridgeCodec.LanguageAndLocale(
                        List.of(
                                "en-US",
                                "uk-UA"),
                        "uk_UA",
                        preferences);
        PbBridgeCodec.ComputedTimeZone timeZone =
                new PbBridgeCodec.ComputedTimeZone(
                        "Europe/Kyiv");
        byte[] settingsBytes =
                PbBridgeCodec.encode(
                        settings);
        byte[] timeZoneBytes =
                PbBridgeCodec.encode(
                        timeZone);
        PbBridgeCodec.LanguageAndLocale decodedSettings =
                PbBridgeCodec.decodeLanguageAndLocale(
                        settingsBytes);
        PbBridgeCodec.ComputedTimeZone decodedTimeZone =
                PbBridgeCodec.decodeComputedTimeZone(
                        timeZoneBytes);
        try {
            assertArrayEquals(
                    hex("0a 05 65 6e 2d 55 53 "
                            + "0a 05 75 6b 2d 55 41 "
                            + "12 05 75 6b 5f 55 41"),
                    Arrays.copyOf(
                            settingsBytes,
                            21));
            assertEquals(
                    0x1a,
                    settingsBytes[21] & 0xff);
            assertArrayEquals(
                    hex("0a 0b 45 75 72 6f 70 65 2f 4b 79 69 76"),
                    timeZoneBytes);
            assertEquals(
                    List.of(
                            "en-US",
                            "uk-UA"),
                    decodedSettings.appleLanguages());
            assertEquals(
                    "uk_UA",
                    decodedSettings.appleLocale());
            assertArrayEquals(
                    preferences,
                    decodedSettings.archivedPreferences());
            assertEquals(
                    "Europe/Kyiv",
                    decodedTimeZone.computedTimeZone());
        } finally {
            settings.destroy();
            decodedSettings.destroy();
            wipe(preferences);
            wipe(settingsBytes);
            wipe(timeZoneBytes);
        }
    }

    @Test
    public void newSetupMessagesRejectWrongWireShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeLanguageAndLocaleStatus(
                        hex("08 01")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeLanguageAndLocaleStatus(
                        hex("09 00 00 00 00 00 00 f8 7f")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeLanguageAndLocale(
                        hex("0a 02 65 6e 12 05 65 6e 5f 55 53")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeComputedTimeZone(
                        hex("0a 01 ff")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeComputedTimeZone(
                        hex("0a 03 55 54 43 0a 03 47 4d 54")));
    }

    @Test
    public void setupControlMessagesAreExactEmptyProtobufs() {
        PbBridgeCodec.ApplicationMessage[] messages = {
                new PbBridgeCodec.ActivationSucceeded(),
                new PbBridgeCodec.CanBeginActivation(),
                new PbBridgeCodec.PrepareInitialSync(),
                new PbBridgeCodec.PrepareInitialSyncResponse(),
                new PbBridgeCodec.UpdateNanoRegistryNormal()
        };
        int[] expectedTypes = {
                4,
                11,
                21,
                18,
                36
        };

        for (int index = 0;
                index < messages.length;
                index++) {
            byte[] encoded =
                    PbBridgeCodec.encode(
                            messages[index]);
            try {
                assertArrayEquals(
                        new byte[0],
                        encoded);
                assertEquals(
                        expectedTypes[index],
                        messages[index].protobufType());
            } finally {
                wipe(encoded);
            }
        }
        assertTrue(
                messages[3].response());
    }

    @Test
    public void envelopeValidationKeepsTypeTwoRolesAndResponseBitExact() {
        byte[] fetch =
                hex("0a 01 7f");
        byte[] activation =
                hex("0a 01 7f 12 01 01");
        IdsSocketPairCodec.ProtobufMessage fetchEnvelope =
                envelope(
                        PbBridgeCodec.TYPE_PROXY_ACTIVATION,
                        false,
                        fetch);
        IdsSocketPairCodec.ProtobufMessage activationEnvelope =
                envelope(
                        PbBridgeCodec.TYPE_PROXY_ACTIVATION,
                        false,
                        activation);
        IdsSocketPairCodec.ProtobufMessage responseEnvelope =
                envelope(
                        PbBridgeCodec.TYPE_PREPARE_INITIAL_SYNC_RESPONSE,
                        true,
                        new byte[0]);
        try {
            PbBridgeCodec.validateEnvelope(
                    fetchEnvelope);
            PbBridgeCodec.validateEnvelope(
                    activationEnvelope);
            PbBridgeCodec.validateEnvelope(
                    responseEnvelope);
        } finally {
            fetchEnvelope.destroy();
            activationEnvelope.destroy();
            responseEnvelope.destroy();
            wipe(fetch);
            wipe(activation);
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    IdsSocketPairCodec.ProtobufMessage invalid =
                            envelope(
                                    PbBridgeCodec
                                            .TYPE_PREPARE_INITIAL_SYNC_RESPONSE,
                                    false,
                                    new byte[0]);
                    try {
                        PbBridgeCodec.validateEnvelope(
                                invalid);
                    } finally {
                        invalid.destroy();
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    IdsSocketPairCodec.ProtobufMessage invalid =
                            envelope(
                                    PbBridgeCodec.TYPE_PREPARE_INITIAL_SYNC,
                                    false,
                                    new byte[0]);
                    try {
                        PbBridgeCodec.validateEnvelope(
                                invalid);
                    } finally {
                        invalid.destroy();
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeActivationFetch(
                        hex("0a 01 01 12 01 02")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeActivationData(
                        hex("0a 01 01")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PbBridgeCodec.decodeActivationFetch(
                        hex("0a 01 01 0a 01 02")));
    }

    @Test
    public void activationFetchToleratesGroupsAndTrailingData() {
        // Field 1: bytes "01 02 03" (tag 0a, len 03), followed by Field 3 START_GROUP (tag 1b),
        // nested varint field 1 (tag 08, val 01), and Field 3 END_GROUP (tag 1c).
        byte[] withGroup = hex("0a 03 01 02 03 1b 08 01 1c");
        PbBridgeCodec.ActivationFetchRequest req =
                PbBridgeCodec.decodeActivationFetch(withGroup);
        try {
            assertArrayEquals(hex("01 02 03"), req.archivedRequest());
        } finally {
            req.destroy();
        }

        // Field 1 followed by trailing non-tag or unexpected wire type
        byte[] withTrailing = hex("0a 03 01 02 03 1f 00");
        PbBridgeCodec.ActivationFetchRequest req2 =
                PbBridgeCodec.decodeActivationFetch(withTrailing);
        try {
            assertArrayEquals(hex("01 02 03"), req2.archivedRequest());
        } finally {
            req2.destroy();
        }
    }

    @Test
    public void activationFetchDecompressesGzipPayload() throws Exception {
        byte[] uncompressed = hex("0a 03 01 02 03");
        java.io.ByteArrayOutputStream gzipOut = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(gzipOut)) {
            gzip.write(uncompressed);
        }
        byte[] compressed = gzipOut.toByteArray();

        PbBridgeCodec.ActivationFetchRequest req =
                PbBridgeCodec.decodeActivationFetch(compressed);
        try {
            assertArrayEquals(hex("01 02 03"), req.archivedRequest());
        } finally {
            req.destroy();
            wipe(compressed);
        }
    }

    private static IdsSocketPairCodec.ProtobufMessage envelope(
            int protobufType,
            boolean response,
            byte[] payload) {
        return new IdsSocketPairCodec.ProtobufMessage(
                1,
                2,
                IdsSocketPairCodec.FLAG_HAS_TOPIC,
                response
                        ? "20000000-0000-4000-8000-000000000002"
                        : null,
                "10000000-0000-4000-8000-000000000001",
                PbBridgeCodec.SERVICE,
                protobufType,
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

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
