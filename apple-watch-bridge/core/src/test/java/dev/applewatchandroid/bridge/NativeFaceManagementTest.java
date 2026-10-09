package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class NativeFaceManagementTest {
    private static byte[] archive(byte[] config, String name, byte[] resource) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("face.json")); zip.write(config); zip.closeEntry();
            if (name != null) { zip.putNextEntry(new ZipEntry(name)); zip.write(resource); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    @Test public void commandsValidateEveryMutationAndCannotSmuggleMalformedOrArbitraryPackets() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline();
        String encoded = Base64.getEncoder().encodeToString(base.configurations.get(FACE));
        for (String command : List.of(ClockFaceDeltaCommand.REMOVE + FACE,
                ClockFaceDeltaCommand.REORDER + FACE, ClockFaceDeltaCommand.UPDATE + FACE + ":" + encoded,
                ClockFaceDeltaCommand.ADD + NEW_FACE + ":" + Base64.getEncoder().encodeToString(base.archives.get(FACE)))) {
            assertTrue(OperationalCommandPolicy.isAllowed(command));
            assertTrue(ClockFaceDeltaCommand.matches(command));
        }
        for (String command : List.of(ClockFaceDeltaCommand.REORDER + FACE + "," + FACE,
                ClockFaceDeltaCommand.UPDATE + FACE + ":e30=", ClockFaceDeltaCommand.UPDATE + FACE + ":@@@",
                ClockFaceDeltaCommand.ADD + FACE + ":" + encoded, ClockFaceDeltaCommand.REMOVE + FACE + "\nPING_WATCH")) {
            assertFalse(command, OperationalCommandPolicy.isAllowed(command));
        }
        try (var plan = ClockFaceDeltaCommand.parse(ClockFaceDeltaCommand.ADD + NEW_FACE + ":"
                + Base64.getEncoder().encodeToString(base.archives.get(FACE))).plan(base)) {
            assertTrue(plan.matches(ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1)));
        }
    }
    @Test public void addingAndDuplicatingAnyObservedBundlePreservesResourcesAndOpaqueNativeConfiguration() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline();
        byte[] config = "{\"bundle id\":\"com.apple.NTKPhotoFaceBundle\",\"customization\":{\"color\":\"native-token\"},\"complications\":{\"bottom\":{\"descriptor\":{\"intent\":\"opaque\"}}}}".getBytes(StandardCharsets.UTF_8);
        byte[] resource = {0,1,2,3,4,5};
        byte[] zip = archive(config, "Resources/photo.jpg", resource);
        base.configurations.put(FACE, config); base.archives.put(FACE, zip);
        try (var plan = ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE)) {
            var changes = plan.copyChanges();
            try (var message = ClockFaceSyncFrame.decodeChange(changes.get(0))) { assertArrayEquals(zip, message.payload()); }
            var observed = ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed)); assertArrayEquals(zip, observed.archives.get(NEW_FACE));
            try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
                input.getNextEntry(); input.closeEntry(); input.getNextEntry(); assertArrayEquals(resource, input.readAllBytes());
            }
        }
        var restored = ClockFaceCollection.decode(base.encode()); assertArrayEquals(zip, restored.archives.get(FACE));
        var projected = ClockFaceObservationCodec.fromCollection(UUID.randomUUID(), UUID.randomUUID(), restored);
        var decoded = ClockFaceObservationCodec.decode(ClockFaceObservationCodec.encode(projected));
        assertArrayEquals(zip, Base64.getDecoder().decode(decoded.faces().get(0).archiveBase64()));
    }
    @Test public void legacySnapshotsCannotInventPackagesAndConfigurationEditsPreserveResources() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline(); var saved = base.encode(); saved.remove("archives");
        var legacy = ClockFaceCollection.decode(saved); assertTrue(legacy.archives.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.duplicate(legacy, FACE, NEW_FACE));
        byte[] resource = {0, 12, 32, 64};
        base.archives.put(FACE, archive(base.configurations.get(FACE), "Resources/photo.jpg", resource));
        byte[] changed = "{\"bundle id\":\"com.apple.NTKLeghornFaceBundle\",\"customization\":{\"color\":\"changed\"}}".getBytes(StandardCharsets.UTF_8);
        byte[] change = NtkFaceChangeEncoder.update(FACE, changed);
        try (var message = ClockFaceSyncFrame.decodeChange(change)) { base.apply(message); }
        assertArrayEquals(changed, NtkFacePayloadCodec.configurationFromZip(base.nativePackage(FACE))); assertTrue(base.complete());
        try (var input = new ZipInputStream(new ByteArrayInputStream(base.nativePackage(FACE)))) {
            input.getNextEntry(); input.closeEntry(); input.getNextEntry(); assertArrayEquals(resource, input.readAllBytes());
        }
        try (var message = ClockFaceSyncFrame.decodeChange(change)) { legacy.apply(message); }
        assertNull(legacy.nativePackage(FACE));
    }
    @Test public void newFamilyNeedsActualReadbackAndTraversalOversizedArchivesAndCollectionCollisionsAreRefused() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline();
        byte[] unknown = "{\"bundle id\":\"com.apple.Unknown\"}".getBytes(StandardCharsets.UTF_8);
        byte[] unsupported = archive(unknown, null, null);
        try (var plan = ClockFaceDeltaPlan.add(base, NEW_FACE, unsupported)) { assertFalse(plan.matches(base)); assertEquals(1, base.configurations.size()); }
        byte[] unsafe = archive(base.configurations.get(FACE), "../escape", new byte[1]);
        assertThrows(IOException.class, () -> ClockFaceDeltaPlan.add(base, NEW_FACE, unsafe));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.add(base, FACE, base.archives.get(FACE)));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.add(base, NEW_FACE, new byte[NativeFacePackageStore.MAX_PACKAGE_BYTES + 1]));
    }
}
