package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class WatchSettingsCodecTest {
    private static final long NOW = 1791253000000L;
    private static final double REFERENCE_NOW = 812945800.0;

    @Test public void nativeOrientationUsesAppleEpochAndRootPlistBoolean() {
        byte[] payload = WatchSettingsCodec.encode(new WatchSettingsCodec.Change(
                WatchSettingsCodec.Setting.RIGHT_WRIST, true), NOW);
        assertEquals(0x09, payload[0]);
        assertEquals(REFERENCE_NOW, ByteBuffer.wrap(payload, 1, 8)
                .order(ByteOrder.LITTLE_ENDIAN).getDouble(), 0);
        var message = PairedSyncCodec.decodeInbound(payload);
        var keys = message.keys();
        try {
            assertEquals("com.apple.nano", message.domain);
            assertEquals(1, keys.size());
            var key = keys.get(0);
            assertEquals("wornOnRightArm", key.key);
            assertEquals(Boolean.TRUE, key.twoWaySync);
            assertEquals(REFERENCE_NOW, key.timestamp, 0);
            // Independent plistlib/CFPropertyList-compatible root-true fixture.
            assertArrayEquals(hex("62706c697374303009080000000000000101000000000000000100000000000000000000000000000009"), key.value());
        } finally { message.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
    }

    @Test public void clockFormatHasBothForceFlagsAndNoTwoWayMutation() {
        byte[] payload = WatchSettingsCodec.encode(new WatchSettingsCodec.Change(
                WatchSettingsCodec.Setting.TIME_24_HOUR, false), NOW);
        var message = PairedSyncCodec.decodeInbound(payload);
        var keys = message.keys();
        try {
            assertEquals(".GlobalPreferences", message.domain);
            assertEquals("AppleICUForce24HourTime", keys.get(0).key);
            assertFalse(BinaryPropertyListCodec.decodeBoolean(keys.get(0).value()));
            assertEquals("AppleICUForce12HourTime", keys.get(1).key);
            assertTrue(BinaryPropertyListCodec.decodeBoolean(keys.get(1).value()));
            for (var key : keys) { assertNull(key.twoWaySync); assertNull(key.timestamp); }
        } finally { message.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
    }

    @Test public void malformedUnknownOrDestructiveCommandsCannotReachNps() {
        for (String command : List.of("SET_WATCH_SETTING:RIGHT_WRIST:YES",
                "SET_WATCH_SETTING:RIGHT_WRIST:true:extra", "SET_WATCH_SETTING:ERASE_DATA:true",
                "SET_WATCH_SETTING:RIGHT_WRIST:true\nSTOP", "SET_WATCH_SETTING:RIGHT_WRIST:")) {
            assertFalse(OperationalCommandPolicy.isAllowed(command));
            assertThrows(IllegalArgumentException.class, () -> WatchSettingsCodec.parseCommand(command));
        }
        assertTrue(OperationalCommandPolicy.isAllowed("SET_WATCH_SETTING:INVERT_SCREEN:false"));
    }

    @Test public void inboundUnknownRemovedWrongDomainAndBadValuesStayUnknown() {
        assertTrue(WatchSettingsCodec.decodeObserved(report("com.apple.Carousel", "wornOnRightArm",
                BinaryPropertyListCodec.encodeBoolean(true), REFERENCE_NOW), NOW).isEmpty());
        assertTrue(WatchSettingsCodec.decodeObserved(report("com.apple.nano", "wornOnRightArm",
                "right".getBytes(), REFERENCE_NOW), NOW).isEmpty());
        assertTrue(WatchSettingsCodec.decodeObserved(report("com.apple.nano", "wornOnRightArm",
                BinaryPropertyListCodec.encodeDictionary(Map.of("value", true)), REFERENCE_NOW), NOW).isEmpty());
        var removed = WatchSettingsCodec.decodeObserved(report("com.apple.nano", "wornOnRightArm", null, REFERENCE_NOW), NOW);
        assertEquals(1, removed.size()); assertNull(removed.get(0).value());
        assertThrows(IllegalArgumentException.class, () -> WatchSettingsCodec.decodeObserved(
                report("com.apple.nano", "wornOnRightArm", BinaryPropertyListCodec.encodeBoolean(true), NOW / 1000.0), NOW));
        assertThrows(IllegalArgumentException.class, () -> WatchSettingsCodec.decodeObserved(new byte[0], NOW));
    }

    @Test public void observationsKeepSourceOrderingTombstonesAndSessionBoundaries() {
        var state = new WatchSettingsObservation();
        var setting = WatchSettingsCodec.Setting.RIGHT_WRIST;
        assertTrue(state.observe(List.of(new WatchSettingsCodec.Observed(setting, true, 10)), NOW));
        assertFalse(state.observe(List.of(new WatchSettingsCodec.Observed(setting, false, 9)), NOW + 1));
        assertFalse(state.observe(List.of(new WatchSettingsCodec.Observed(setting, false, 10)), NOW + 2));
        assertTrue(state.snapshot().get(setting).value());
        assertEquals(NOW, state.snapshot().get(setting).observedAt());
        assertTrue(state.observe(List.of(new WatchSettingsCodec.Observed(setting, null, 11)), NOW + 3));
        assertNull(state.snapshot().get(setting).value());
        assertFalse(state.observe(List.of(new WatchSettingsCodec.Observed(setting, true, 10)), NOW + 4));
        state.clear(); assertTrue(state.snapshot().isEmpty());
    }

    private static byte[] report(String domain, String key, byte[] value, double timestamp) {
        var field = new PairedSyncCodec.UserDefaultsKey(key, value, true, timestamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(timestamp, domain, List.of(field), false);
        try { return PairedSyncCodec.encode(message); }
        finally { field.destroy(); message.destroy(); }
    }
    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
