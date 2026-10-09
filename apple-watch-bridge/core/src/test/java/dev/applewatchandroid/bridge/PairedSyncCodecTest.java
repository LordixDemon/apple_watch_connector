package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class PairedSyncCodecTest {
    @Test
    public void beginningHasActivitiesRequiredByNativeRegistryStartGate() {
        PairedSyncCodec.UserDefaultsMessage started = PairedSyncCodec.initialSyncStarted(800000000.5);
        PairedSyncCodec.UserDefaultsMessage decoded = PairedSyncCodec.decode(PairedSyncCodec.encode(started));
        try {
            Map<String, Object> client = BinaryPropertyListCodec.decodeDictionary(
                    find(decoded.keys(), PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY).value());
            assertEquals(0L, client.get("syncSessionType"));
            assertEquals(2L, client.get("syncProgressState"));
            assertEquals(List.of("InitialSync"), client.get("activeActivityLabels"));
            assertEquals(List.of(), client.get("completedActivityLabels"));
            assertFalse(decoded.isInitialSyncCompletion());
        } finally {
            decoded.destroy();
            started.destroy();
        }
    }

    @Test
    public void initialCompletionRoundTripsExactNpsAndBusinessState() {
        PairedSyncCodec.UserDefaultsMessage completion =
                PairedSyncCodec.initialSyncCompletion(
                        800000000.5);
        byte[] encoded =
                PairedSyncCodec.encode(
                        completion);
        PairedSyncCodec.UserDefaultsMessage decoded =
                PairedSyncCodec.decode(
                        encoded);
        List<PairedSyncCodec.UserDefaultsKey> keys =
                decoded.keys();
        try {
            assertArrayEquals(
                    hex("09 00 00 40 00 84 d7 c7 41 12 14"),
                    Arrays.copyOf(
                            encoded,
                            11));
            assertEquals(
                    PairedSyncCodec.DOMAIN,
                    decoded.domain);
            assertEquals(
                    800000000.5,
                    decoded.timestamp,
                    0.0);
            assertEquals(
                    2,
                    keys.size());
            assertTrue(
                    completion.isInitialSyncCompletion());
            assertTrue(
                    decoded.isInitialSyncCompletion());

            PairedSyncCodec.UserDefaultsKey watchState =
                    find(
                            keys,
                            PairedSyncCodec.WATCH_SYNC_STATE_KEY);
            PairedSyncCodec.UserDefaultsKey clientState =
                    find(
                            keys,
                            PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY);
            assertNull(
                    watchState.twoWaySync);
            assertNull(
                    watchState.timestamp);
            assertNull(
                    clientState.twoWaySync);
            assertNull(
                    clientState.timestamp);

            byte[] watchValue =
                    watchState.value();
            byte[] clientValue =
                    clientState.value();
            try {
                assertEquals(
                        Map.of(
                                "version",
                                1L,
                                "syncProgressState",
                                3L,
                                "globalProgress",
                                100L),
                        BinaryPropertyListCodec.decodeDictionary(
                                watchValue));
                assertEquals(
                        Map.of(
                                "version",
                                1L,
                                "syncProgressState",
                                3L,
                                "syncSessionType",
                                0L,
                                "migrationSync",
                                Boolean.FALSE),
                        BinaryPropertyListCodec.decodeDictionary(
                                clientValue));
            } finally {
                wipe(
                        watchValue);
                wipe(
                        clientValue);
            }
            assertEquals(
                    PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS,
                    decoded.protobufType());
            assertFalse(
                    decoded.response());
        } finally {
            destroy(
                    keys);
            completion.destroy();
            decoded.destroy();
            wipe(
                    encoded);
        }
    }

    @Test
    public void optionalKeyFieldsPreservePresenceAndFixed64WireOrder() {
        byte[] value =
                BinaryPropertyListCodec.encodeDictionary(
                        Map.of(
                                "version",
                                1L));
        PairedSyncCodec.UserDefaultsKey sourceKey =
                new PairedSyncCodec.UserDefaultsKey(
                        PairedSyncCodec.WATCH_SYNC_STATE_KEY,
                        value,
                        Boolean.TRUE,
                        1.5);
        PairedSyncCodec.UserDefaultsMessage source =
                new PairedSyncCodec.UserDefaultsMessage(
                        2.0,
                        PairedSyncCodec.DOMAIN,
                        List.of(
                                sourceKey));
        byte[] encoded =
                PairedSyncCodec.encode(
                        source);
        PairedSyncCodec.UserDefaultsMessage decoded =
                PairedSyncCodec.decode(
                        encoded);
        List<PairedSyncCodec.UserDefaultsKey> decodedKeys =
                decoded.keys();
        try {
            assertEquals(
                    Boolean.TRUE,
                    decodedKeys.get(
                            0).twoWaySync);
            assertEquals(
                    Double.valueOf(
                            1.5),
                    decodedKeys.get(
                            0).timestamp);
            assertFalse(
                    decoded.isInitialSyncCompletion());
        } finally {
            wipe(
                    value);
            sourceKey.destroy();
            source.destroy();
            decoded.destroy();
            destroy(
                    decodedKeys);
            wipe(
                    encoded);
        }
    }

    @Test
    public void envelopeAndMalformedPayloadValidationFailClosed() {
        PairedSyncCodec.UserDefaultsMessage completion =
                PairedSyncCodec.initialSyncCompletion(
                        1.0);
        byte[] payload =
                PairedSyncCodec.encode(
                        completion);
        IdsSocketPairCodec.ProtobufMessage valid =
                envelope(
                        0,
                        false,
                        payload);
        IdsSocketPairCodec.ProtobufMessage response =
                envelope(
                        0,
                        true,
                        payload);
        IdsSocketPairCodec.ProtobufMessage wrongType =
                envelope(
                        2,
                        false,
                        payload);
        try {
            PairedSyncCodec.validateEnvelope(
                    valid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairedSyncCodec.validateEnvelope(
                            response));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairedSyncCodec.validateEnvelope(
                            wrongType));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairedSyncCodec.decode(
                            hex("09 00")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairedSyncCodec.decode(
                            hex("12 14 63 6f 6d")));
            assertTrue(
                    PairedSyncCodec.isService(
                            PairedSyncCodec.PREFERRED_SERVICE));
            assertTrue(
                    PairedSyncCodec.isService(
                            PairedSyncCodec.FALLBACK_SERVICE));
            assertFalse(
                    PairedSyncCodec.isService(
                            "unknown"));
        } finally {
            valid.destroy();
            response.destroy();
            wrongType.destroy();
            completion.destroy();
            wipe(
                    payload);
        }
    }

    private static PairedSyncCodec.UserDefaultsKey find(
            List<PairedSyncCodec.UserDefaultsKey> keys,
            String name) {
        for (PairedSyncCodec.UserDefaultsKey key :
                keys) {
            if (name.equals(
                    key.key)) {
                return key;
            }
        }
        throw new AssertionError(
                "Missing PairedSync key "
                        + name);
    }

    private static IdsSocketPairCodec.ProtobufMessage envelope(
            int type,
            boolean response,
            byte[] payload) {
        return new IdsSocketPairCodec.ProtobufMessage(
                1,
                2,
                IdsSocketPairCodec.FLAG_HAS_TOPIC,
                null,
                "10000000-0000-4000-8000-000000000001",
                PairedSyncCodec.PREFERRED_SERVICE,
                type,
                response,
                payload,
                null);
    }

    private static void destroy(
            List<PairedSyncCodec.UserDefaultsKey> keys) {
        for (PairedSyncCodec.UserDefaultsKey key :
                keys) {
            key.destroy();
        }
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replaceAll(
                        "\\s+",
                        "");
        byte[] result =
                new byte[compact.length() / 2];
        for (int index = 0;
                index < result.length;
                index++) {
            result[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return result;
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
