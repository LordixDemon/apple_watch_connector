package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class PigmentPreferenceOwnerPolicyTest {
    private static final UUID PAIR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Test public void setupReceiptStartsOnlyAfterAuthenticatedIsPairedCommit() {
        for (var state : PairingSessionRecord.DurableState.values()) {
            UUID expected = state.wireValue() >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue() ? PAIR : null;
            assertEquals(expected, PigmentPreferenceOwnerPolicy.owner(PAIR, state, true, null, true));
            assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, state, false, null, true));
            assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, state, true, null, false));
        }
        assertNull(PigmentPreferenceOwnerPolicy.owner((PairingSessionRecord) null, null, true));
        assertNull(PigmentPreferenceOwnerPolicy.owner(null, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED, true, null, true));
        assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, null, true, null, true));
    }
    @Test public void operationalReceiptStillRequiresTheSameActivatedGeneration() {
        var paired = PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED;
        var activated = PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED;
        assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, paired, true, PAIR.toString(), true));
        assertEquals(PAIR, PigmentPreferenceOwnerPolicy.owner(PAIR, activated, true, PAIR.toString(), true));
        assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, activated, true, UUID.randomUUID().toString(), true));
        assertNull(PigmentPreferenceOwnerPolicy.owner(PAIR, activated, true, "invalid", true));
    }
    @Test public void firstSetupNpsValueSurvivesHandoffAndDoesNotSeedAnotherPair() throws Exception {
        var root = Files.createTempDirectory("paired-pigments-setup");
        var owner = PigmentPreferenceOwnerPolicy.owner(PAIR, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                true, null, true);
        long now = 1791253000000L;
        byte[] value = BinaryPropertyListCodec.encodeStringArray(List.of("unknown.setupColor", "standard.blue"));
        var key = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY, value, true, 812945799.0);
        var message = new PairedSyncCodec.UserDefaultsMessage(812945799.0, PigmentPreferenceCodec.DOMAIN, List.of(key), false);
        byte[] wire = PairedSyncCodec.encode(message);
        try {
            var store = new PigmentPreferenceMirror(root, owner);
            assertTrue(store.observe(PigmentPreferenceCodec.decodeObserved(wire, now), now));
            UUID readyEpoch = UUID.randomUUID();
            var handoff = new PigmentPreferenceMirror(root, PAIR).snapshot(readyEpoch);
            assertEquals(PAIR, handoff.pair()); assertEquals(readyEpoch, handoff.epoch());
            assertEquals(List.of("unknown.setupColor", "standard.blue"), handoff.value().names());
            assertEquals(PigmentPreferenceMirror.Origin.REMOTE, handoff.value().origin());
            assertNull(handoff.value().request());
            assertNull(new PigmentPreferenceMirror(root, UUID.randomUUID()).snapshot(readyEpoch));
        } finally {
            Arrays.fill(value, (byte) 0); Arrays.fill(wire, (byte) 0); message.destroy(); key.destroy();
            try (var files = Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        }
    }

    @Test public void setupCapturesBothNativeKeysThroughTheVerifiedNpsEnvelopeBeforeReady() throws Exception {
        var root = Files.createTempDirectory("paired-dual-pigments-setup");
        long now = 1791253000000L;
        var selected = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY,
                BinaryPropertyListCodec.encodeStringArray(List.of("unknown.selected")), true, 812945799.0);
        var automatic = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                BinaryPropertyListCodec.encodeStringArray(List.of("unknown.automatic")), true, 812945798.0);
        var message = new PairedSyncCodec.UserDefaultsMessage(812945799.0, PigmentPreferenceCodec.DOMAIN, List.of(selected, automatic), false);
        byte[] wire = PairedSyncCodec.encode(message);
        try {
            for (String route : List.of(PairedSyncCodec.PREFERRED_SERVICE, PairedSyncCodec.FALLBACK_SERVICE)) {
                assertTrue(PigmentPreferenceCodec.isObservationEnvelope(route, 0, false));
                var owner = PigmentPreferenceOwnerPolicy.owner(PAIR, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                        true, null, true);
                var store = new PigmentPreferenceMirror(root, owner);
                store.observe(PigmentPreferenceCodec.decodeObserved(wire, now),
                        PigmentPreferenceCodec.decodeObserved(wire, now, PigmentPreferenceCodec.AUTO_KEY), now);
                UUID epoch = UUID.randomUUID();
                var ready = PigmentMirrorIpcCodec.decode(PigmentMirrorIpcCodec.encode(new PigmentPreferenceMirror(root, owner).snapshot(epoch)));
                assertEquals(List.of("unknown.selected"), ready.value().names());
                assertEquals(List.of("unknown.automatic"), ready.automatic().names());
                assertEquals(812945798.0, ready.automatic().sourceTimestamp(), 0);
                assertTrue(ready.manualWritable(now));
                assertNull(new PigmentPreferenceMirror(root, UUID.randomUUID()).snapshot(epoch));
            }
        } finally {
            Arrays.fill(wire, (byte) 0); message.destroy(); selected.destroy(); automatic.destroy();
            try (var files = Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        }
    }
}
