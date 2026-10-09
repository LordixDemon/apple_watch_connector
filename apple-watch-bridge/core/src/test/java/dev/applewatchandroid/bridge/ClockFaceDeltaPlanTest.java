package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public final class ClockFaceDeltaPlanTest {
    private static byte[] resourcePackage(byte[] configuration, byte photo, byte manifest) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("face.json")); zip.write(configuration); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("Resources/photo.jpg")); zip.write(new byte[]{1, photo, 3}); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("Resources/Images.plist")); zip.write(new byte[]{manifest}); zip.closeEntry();
        }
        return output.toByteArray();
    }

    @Test public void addRequiresActualResourceReadbackNotJustMatchingConfiguration() throws Exception {
        var base = baseline(); byte[] config = base.configurations.get(FACE);
        byte[] archive = resourcePackage(config, (byte) 2, (byte) 1);
        try (var plan = ClockFaceDeltaPlan.add(base, NEW_FACE, archive)) {
            var observed = apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed));
            observed.archives.remove(NEW_FACE);
            assertFalse(plan.matches(observed));
            observed.archives.put(NEW_FACE, resourcePackage(config, (byte) 9, (byte) 1));
            assertFalse(plan.matches(observed));
            observed.archives.put(NEW_FACE, resourcePackage(config, (byte) 2, (byte) 9));
            assertFalse(plan.matches(observed)); // a missing/changed manifest can make the same JPEG unusable
        }
        base.archives.put(FACE, archive);
        try (var plan = ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE)) {
            var observed = apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed));
            observed.archives.remove(NEW_FACE);
            assertFalse(plan.matches(observed));
        }
    }
    static ClockFaceCollection baseline() {
        ClockFaceCollection value = new ClockFaceCollection();
        value.configurations.put(FACE, ("{\"bundle id\":\"com.apple.NTKLeghornFaceBundle\",\"customization\":{\"style\":\"digital\"}}")
                .getBytes(StandardCharsets.UTF_8));
        value.ordered.add(FACE); value.selected = FACE; value.selectionKnown = true;
        value.orderKnown = true; value.hasResetBaseline = true; value.observedAt = NOW - 1000;
        try {
            byte[] bytes = NtkFaceChangeEncoder.addConfiguration(FACE, value.configurations.get(FACE));
            try (var message = ClockFaceSyncFrame.decodeChange(bytes)) { value.archives.put(FACE, message.payload()); }
        } catch (Exception invalid) { throw new AssertionError(invalid); }
        return value;
    }

    static ClockFaceCollection apply(ClockFaceDeltaPlan plan, ClockFaceCollection base, long time) throws Exception {
        ClockFaceCollection result = ClockFaceCollection.decode(base.encode());
        var changes = plan.copyChanges();
        try {
            for (byte[] bytes : changes) try (var change = ClockFaceSyncFrame.decodeChange(bytes)) { result.apply(change); }
            result.observedAt = time; return result;
        } finally { for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); }
    }

    @Test public void duplicateIsAddThenAppendThenSelectAndNeverChangesSourceConfiguration() throws Exception {
        var base = baseline(); byte[] original = base.configurations.get(FACE).clone();
        try (var plan = ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE)) {
            var changes = plan.copyChanges(); assertEquals(3, changes.size());
            try {
                List<Integer> types = new ArrayList<>();
                for (byte[] bytes : changes) try (var message = ClockFaceSyncFrame.decodeChange(bytes)) { types.add(message.type()); }
                assertEquals(List.of(0, 5, 4), types);
            } finally { for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); }
            var observed = apply(plan, base, NOW + 1);
            assertEquals(2, observed.configurations.size()); assertEquals(List.of(FACE, NEW_FACE), observed.ordered);
            assertEquals(NEW_FACE, observed.selected); assertTrue(plan.matches(observed));
            assertArrayEquals(original, base.configurations.get(FACE)); assertEquals(FACE, base.selected);
            observed.selected = FACE; assertFalse(plan.matches(observed));
            observed.selected = NEW_FACE; observed.configurations.put(NEW_FACE, "{\"bundle id\":\"different\"}".getBytes(StandardCharsets.UTF_8));
            assertFalse(plan.matches(observed));
        }
    }

    @Test public void unknownOrIncompleteFacesResourcesCollisionsAndFinalFaceRemovalAreRefused() {
        var base = baseline();
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.select(base, NEW_FACE));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.remove(base, FACE));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.duplicate(base, FACE, FACE));
        base.hasResetBaseline = false;
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.select(base, FACE));
        base.hasResetBaseline = true; base.observedAt = 0;
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.select(base, FACE));
        base.observedAt = NOW; base.configurations.put(FACE, "{\"bundle id\":\"com.apple.NTKPhotoFaceBundle\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE));
    }

    @Test public void configurationUpdateRequiresSameNativeBundleAndFreshStructuralReadback() throws Exception {
        var base = baseline();
        byte[] configuration = "{\"customization\":{\"style\":\"analog\"},\"bundle id\":\"com.apple.NTKLeghornFaceBundle\"}".getBytes(StandardCharsets.UTF_8);
        try (var plan = ClockFaceDeltaPlan.update(base, FACE, configuration)) {
            assertFalse(plan.matches(base));
            var observed = apply(plan, base, NOW + 1); assertTrue(plan.matches(observed));
            observed.configurations.put(FACE, base.configurations.get(FACE)); assertFalse(plan.matches(observed));
        }
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.update(base, FACE,
                "{\"bundle id\":\"com.apple.Other\"}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.update(base, FACE, new byte[131073]));
    }

    @Test public void configurationEditMustPreservePhotoAndManifestInNativeReadback() throws Exception {
        var base = baseline();
        byte[] original = base.configurations.get(FACE);
        byte[] changed = new String(original, StandardCharsets.UTF_8).replace("digital", "analog")
                .getBytes(StandardCharsets.UTF_8);
        base.archives.put(FACE, resourcePackage(original, (byte) 2, (byte) 1));
        try (var plan = ClockFaceDeltaPlan.update(base, FACE, changed)) {
            var observed = apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed));
            observed.archives.put(FACE, resourcePackage(changed, (byte) 2, (byte) 9));
            assertFalse(plan.matches(observed));
            observed.archives.put(FACE, resourcePackage(changed, (byte) 9, (byte) 1));
            assertFalse(plan.matches(observed));
            observed.archives.remove(FACE);
            assertFalse(plan.matches(observed));
        }
        base.archives.remove(FACE);
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.update(base, FACE, changed));
    }

    @Test public void removalSelectsRetainedFaceBeforeDeleteAndOrderUsesExactPermutation() throws Exception {
        var base = baseline(); base.configurations.put(NEW_FACE, base.configurations.get(FACE).clone()); base.ordered.add(NEW_FACE);
        try (var plan = ClockFaceDeltaPlan.remove(base, FACE)) {
            var observed = apply(plan, base, NOW + 1); assertTrue(plan.matches(observed));
            assertEquals(NEW_FACE, observed.selected); assertFalse(observed.configurations.containsKey(FACE));
            observed.configurations.put(FACE, base.configurations.get(FACE)); assertFalse(plan.matches(observed));
        }
        try (var plan = ClockFaceDeltaPlan.reorder(base, List.of(NEW_FACE, FACE))) {
            assertTrue(plan.matches(apply(plan, base, NOW + 1))); assertFalse(plan.matches(base));
        }
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.reorder(base, List.of(FACE)));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.reorder(base, List.of(NEW_FACE, NEW_FACE)));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.reorder(base, List.of(FACE, UUID.randomUUID().toString())));
    }

    @Test public void plansAreImmutableDoNotPublishLocalApplicationAndCannotBeReusedAfterClose() throws Exception {
        var base = baseline(); var plan = ClockFaceDeltaPlan.select(base, FACE);
        byte[] first = plan.copyChanges().get(0); Arrays.fill(first, (byte) 0);
        try (var decoded = ClockFaceSyncFrame.decodeChange(plan.copyChanges().get(0))) { assertEquals(4, decoded.type()); }
        base.observedAt = NOW + 1; base.selectionKnown = false; assertFalse(plan.matches(base));
        plan.close(); assertFalse(plan.matches(baseline()));
        assertThrows(IllegalStateException.class, plan::copyChanges);
        assertEquals(FACE, base.selected); assertEquals(1, base.configurations.size());
    }
}
