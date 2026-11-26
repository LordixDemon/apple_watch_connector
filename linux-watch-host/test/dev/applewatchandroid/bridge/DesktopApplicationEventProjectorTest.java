package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Standalone regression tests for desktop application event projection. */
public final class DesktopApplicationEventProjectorTest {
    public static void main(String[] args) throws Exception {
        testAboutProjectionAndSentinel();
        testWatchSettingsProjection();
        testUnrelatedAndMalformedPayloads();
        System.out.println("Desktop application event projector: PASS");
    }

    private static void testAboutProjectionAndSentinel() throws IOException {
        DesktopApplicationEventProjector projector = new DesktopApplicationEventProjector();
        byte[] aboutPayload = encodeAbout(123456789L, 4L, 1L, 12L, 82L, true, 512L, 128L);
        String encoded = encodeFrame(
                NanoSystemSettingsDiagnostics.TOPIC,
                NanoSystemSettingsDiagnostics.ABOUT_RESPONSE,
                true,
                aboutPayload);
        DesktopApplicationEventProjector.Result result = projector.project(encoded, 1_750_000_000_123L);
        if (result.wireLines().size() != 1) {
            throw new AssertionError("Expected 1 ABOUT wire line");
        }
        String wire = result.wireLines().get(0);
        if (!wire.startsWith("DESKTOP_ABOUT_V1:{")
                || !wire.contains("\"observedAt\":1750000000123")
                || !wire.contains("\"batteryLevel\":82")
                || !wire.contains("\"isCharging\":true")
                || !wire.contains("\"chargingObserved\":true")
                || !wire.contains("\"availableStorageBytes\":123456789")
                || !wire.contains("\"numberOfApps\":4")) {
            throw new AssertionError("Unexpected ABOUT JSON: " + wire);
        }
        if (result.logSummary().contains(encoded)) {
            throw new AssertionError("Raw base64 leaked into log summary");
        }

        // Exact MobileGestalt sentinel 101 must map to null batteryLevel.
        String sentinelEncoded = encodeFrame(
                NanoSystemSettingsDiagnostics.TOPIC,
                NanoSystemSettingsDiagnostics.ABOUT_RESPONSE,
                true,
                encodeAbout(999L, 0L, 0L, 0L, 101L, false, null, null));
        DesktopApplicationEventProjector.Result sentinelResult = projector.project(sentinelEncoded, 1_750_000_000_999L);
        String sentinelWire = sentinelResult.wireLines().get(0);
        if (!sentinelWire.contains("\"batteryLevel\":null") || !sentinelWire.contains("\"isCharging\":false")) {
            throw new AssertionError("Expected null batteryLevel for sentinel 101: " + sentinelWire);
        }
    }

    private static void testWatchSettingsProjection() {
        DesktopApplicationEventProjector projector = new DesktopApplicationEventProjector();
        long now = 1_750_000_000_000L;
        byte[] payload = WatchSettingsCodec.encode(
                new WatchSettingsCodec.Change(WatchSettingsCodec.Setting.RIGHT_WRIST, true),
                now);
        String encoded = encodeFrame(IdsApplicationRoute.PREFERENCE_SYNC_SERVICE, 0, false, payload);
        DesktopApplicationEventProjector.Result result = projector.project(encoded, now);
        if (result.wireLines().size() != 1) {
            throw new AssertionError("Expected 1 settings wire line");
        }
        String wire = result.wireLines().get(0);
        if (!wire.startsWith("DESKTOP_WATCH_SETTINGS_V1:{")
                || !wire.contains("\"RIGHT_WRIST\":{\"value\":true,\"observedAt\":" + now + "}")) {
            throw new AssertionError("Unexpected watch settings JSON: " + wire);
        }
        // Stale or duplicate report should not re-emit wire line.
        DesktopApplicationEventProjector.Result duplicate = projector.project(encoded, now);
        if (!duplicate.wireLines().isEmpty()) {
            throw new AssertionError("Expected duplicate setting observation to emit no wire lines");
        }
        projector.reset();
        DesktopApplicationEventProjector.Result afterReset = projector.project(encoded, now + 1000L);
        if (afterReset.wireLines().size() != 1) {
            throw new AssertionError("Expected setting observation after reset to emit wire line");
        }
    }

    private static void testUnrelatedAndMalformedPayloads() {
        DesktopApplicationEventProjector projector = new DesktopApplicationEventProjector();
        String other = encodeFrame(IdsApplicationRoute.HEALTH_SYNC_SERVICE, 1, false, new byte[]{1, 2, 3});
        DesktopApplicationEventProjector.Result otherResult = projector.project(other, 1_750_000_000_000L);
        if (!otherResult.wireLines().isEmpty() || !otherResult.logSummary().contains("payload logged=false")) {
            throw new AssertionError("Unexpected handling of non-projected topic");
        }
        DesktopApplicationEventProjector.Result bad = projector.project("not-valid-base64!!!", 1_750_000_000_000L);
        if (!bad.wireLines().isEmpty() || !bad.logSummary().contains("rejected")) {
            throw new AssertionError("Malformed frame was not safely rejected");
        }
    }

    private static String encodeFrame(String topic, int type, boolean response, byte[] payload) {
        try (BridgeApplicationEventCodec.Event event =
                     new BridgeApplicationEventCodec.Event(topic, type, response, payload)) {
            return BridgeBase64.encodeToString(BridgeApplicationEventCodec.encode(event), BridgeBase64.NO_WRAP);
        }
    }

    private static byte[] encodeAbout(
            Long storage, Long apps, Long songs, Long photos,
            Long battery, Boolean charging, Long purgeable, Long deletable) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 1, storage);
        writeVarintField(out, 2, apps);
        writeVarintField(out, 3, songs);
        writeVarintField(out, 4, photos);
        writeVarintField(out, 5, battery);
        if (charging != null) writeVarintField(out, 6, charging ? 1L : 0L);
        writeVarintField(out, 7, purgeable);
        writeVarintField(out, 8, deletable);
        return out.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, Long value) {
        if (value == null) return;
        writeVarint(out, field << 3);
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
