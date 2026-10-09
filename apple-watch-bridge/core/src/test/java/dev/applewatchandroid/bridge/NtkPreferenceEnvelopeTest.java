package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NtkPreferenceEnvelopeTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final double STAMP = NOW / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS;
    private static final UUID PAIR = UUID.randomUUID(), EPOCH = UUID.randomUUID();

    private static byte[] mixed(String text, byte[] colors) {
        var selected = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY, colors, true, STAMP);
        var automatic = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                BinaryPropertyListCodec.encodeStringArray(List.of("futureColor")), true, STAMP);
        var monogram = new PairedSyncCodec.UserDefaultsKey(MonogramPreferenceCodec.KEY,
                BinaryPropertyListCodec.encodeStringRoot(text), true, STAMP);
        var message = new PairedSyncCodec.UserDefaultsMessage(STAMP, MonogramPreferenceCodec.DOMAIN,
                List.of(selected, automatic, monogram), false);
        try { return PairedSyncCodec.encode(message); }
        finally { message.destroy(); selected.destroy(); automatic.destroy(); monogram.destroy(); }
    }

    @Test public void sameOwnedReceiptIsVisibleBeforeEitherListenerRuns() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        var count = new AtomicInteger();
        Runnable verify = () -> {
            assertEquals(List.of("futureColor"), dispatcher.pigmentPreferences(PAIR, EPOCH).names());
            assertEquals("É", dispatcher.monogramPreferences(PAIR, EPOCH).text());
            assertEquals(NOW, dispatcher.monogramPreferences(PAIR, EPOCH).observedAt());
            count.incrementAndGet();
        };
        BridgeIpcDispatcher.OnPigmentPreferencesListener colors = verify::run;
        BridgeIpcDispatcher.OnMonogramPreferencesListener text = verify::run;
        dispatcher.updateConnectionState(false, "test");
        dispatcher.updateConnectionState(true, "test");
        dispatcher.addPigmentPreferencesListener(colors);
        dispatcher.addMonogramPreferencesListener(text);
        try (var event = new BridgeApplicationEventCodec.Event(PairedSyncCodec.PREFERRED_SERVICE, 0, false,
                mixed("É", BinaryPropertyListCodec.encodeStringArray(List.of("futureColor"))))) {
            dispatcher.observeNativePreferences(event, PAIR, EPOCH, NOW);
            assertEquals(2, count.get());
            dispatcher.observeNativePreferences(event, PAIR, EPOCH, NOW);
            assertEquals(2, count.get());
            dispatcher.removePigmentPreferencesListener(colors);
            dispatcher.removeMonogramPreferencesListener(text);
            dispatcher.observeNativePreferences(event, UUID.randomUUID(), UUID.randomUUID(), NOW);
            assertNull(dispatcher.pigmentPreferences(PAIR, EPOCH));
            assertNull(dispatcher.monogramPreferences(PAIR, EPOCH));
        } finally {
            dispatcher.removePigmentPreferencesListener(colors);
            dispatcher.removeMonogramPreferencesListener(text);
            dispatcher.updateConnectionState(false, "test");
        }
    }

    @Test public void malformedKnownValueDoesNotHideUnrelatedValidPreference() {
        try (var envelope = NtkPreferenceEnvelope.decode(mixed("😀",
                BinaryPropertyListCodec.encodeStringArray(List.of("blue"))), NOW)) {
            assertTrue(MonogramPreferenceCodec.decodeObserved(envelope).isEmpty());
            assertEquals(List.of("blue"), PigmentPreferenceCodec.decodeObservedLists(envelope)
                    .get(PigmentPreferenceCodec.KEY).get(0).names());
        }
        try (var envelope = NtkPreferenceEnvelope.decode(mixed("AB",
                BinaryPropertyListCodec.encodeBoolean(true)), NOW)) {
            assertTrue(PigmentPreferenceCodec.decodeObservedLists(envelope).get(PigmentPreferenceCodec.KEY).isEmpty());
            assertEquals("AB", MonogramPreferenceCodec.decodeObserved(envelope).get(0).text());
            assertEquals(List.of("futureColor"), PigmentPreferenceCodec.decodeObservedLists(envelope)
                    .get(PigmentPreferenceCodec.AUTO_KEY).get(0).names());
        }
    }

    @Test public void rejectedEnvelopeCannotEstablishAnyProjection() {
        byte[] data = mixed("AB", BinaryPropertyListCodec.encodeStringArray(List.of("blue")));
        for (long clock : new long[]{0, -1, NOW - 6000}) {
            assertThrows(IllegalArgumentException.class, () -> NtkPreferenceEnvelope.decode(data, clock));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NtkPreferenceEnvelope.decode(Arrays.copyOf(data, 5), NOW));
    }

    @Test public void nativePerGizmoTwoWayStringWireMatchesIndependentFixture() {
        byte[] expected = Base64.getDecoder().decode(
                "CQAAIKe0NchBEhVjb20uYXBwbGUuTmFub1RpbWVLaXQaSQoOY3VzdG9tTW9ub2dyYW0SLGJwbGlzdDAwYQDJCAAAAAAAAAEBAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAALGAEhAAAgp7Q1yEE=");
        assertArrayEquals(expected, MonogramPreferenceCodec.encodeChangeAt("É", 812345678.25));
        var message = PairedSyncCodec.decodeInbound(expected);
        var keys = message.keys();
        try {
            assertEquals(1, keys.size());
            assertEquals("customMonogram", keys.get(0).key);
            assertEquals(Boolean.TRUE, keys.get(0).twoWaySync);
            assertEquals(message.timestamp, keys.get(0).timestamp, 0);
            assertEquals("É", BinaryPropertyListCodec.decodeStringRoot(keys.get(0).value()));
        } finally { message.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
        for (String invalid : Arrays.asList(null, "", "ABCDEF", "😀", "\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCodec.encodeChangeAt(invalid, STAMP));
        }
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCodec.encodeChangeAt("AB", invalid));
        }
    }
}
