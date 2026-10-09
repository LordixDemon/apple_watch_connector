package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public final class NtkFaceResourcesTest {
    @Test public void originalGreenfieldPackageMatchesNativeReadbackWithoutSharingPresentation() throws Exception {
        byte[] shared = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/test/resources/clockface-402-greenfield.watchface"));
        byte[] nativeWire = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/test/resources/clockface-402-greenfield-wire.watchface"));
        assertEquals(Set.of("face.json", "metadata.json", "snapshot.png", "no_borders_snapshot.png"), unzip(shared).keySet());
        assertEquals(Set.of("face.json"), unzip(nativeWire).keySet());
        assertEquals(BoundedJson.decode(NtkFacePayloadCodec.configurationFromZip(shared), 131072),
                BoundedJson.decode(NtkFacePayloadCodec.configurationFromZip(nativeWire), 131072));
        assertTrue(NtkFacePayloadCodec.resourceDigests(shared).isEmpty());
        byte[] wire = NtkFacePayloadCodec.wireArchive(shared);
        assertArrayEquals(shared, java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/test/resources/clockface-402-greenfield.watchface")));
        assertEquals(Set.of("face.json"), unzip(wire).keySet());
        assertTrue(wire.length < 1024);
        assertArrayEquals(unzip(shared).get("face.json"), unzip(wire).get("face.json"));
        var base = ClockFaceDeltaPlanTest.baseline();
        try (var plan = ClockFaceDeltaPlan.add(base, NEW_FACE, shared)) {
            var observed = ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1);
            observed.archives.put(NEW_FACE, nativeWire);
            assertTrue(plan.matches(observed));
            String changed = new String(observed.configurations.get(NEW_FACE), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("analog", "digital");
            observed.configurations.put(NEW_FACE, changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(plan.matches(observed));
        }
    }

    @Test public void sharingExceptionsNeverExcludeNestedOrUnknownResources() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline();
        var files = new LinkedHashMap<>(unzip(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/clockface-402-greenfield.watchface"))));
        files.put("Resources/metadata.json", new byte[]{1});
        files.put("Resources/snapshot.png", new byte[]{2});
        files.put("Resources/no_borders_snapshot.png", new byte[]{3});
        files.put("opaque.bin", new byte[]{4});
        byte[] shared = zip(files);
        byte[] wire = NtkFacePayloadCodec.wireArchive(shared);
        assertEquals(Set.of("face.json", "Resources/metadata.json", "Resources/snapshot.png", "Resources/no_borders_snapshot.png", "opaque.bin"), unzip(wire).keySet());
        for (String name : unzip(wire).keySet()) assertArrayEquals(files.get(name), unzip(wire).get(name));
        assertEquals(Set.of("Resources/metadata.json", "Resources/snapshot.png", "Resources/no_borders_snapshot.png", "opaque.bin"),
                NtkFacePayloadCodec.resourceDigests(shared).keySet());
        try (var plan = ClockFaceDeltaPlan.add(base, NEW_FACE, shared)) {
            var observed = ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed));
            for (String resource : NtkFacePayloadCodec.resourceDigests(shared).keySet()) {
                var missing = new LinkedHashMap<>(files); missing.remove(resource);
                observed.archives.put(NEW_FACE, zip(missing));
                assertFalse(plan.matches(observed));
                var altered = new LinkedHashMap<>(files); altered.put(resource, new byte[]{99});
                observed.archives.put(NEW_FACE, zip(altered));
                assertFalse(plan.matches(observed));
            }
        }
    }
    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
    @Test public void nativeTypeTwoUsesRootResourcesAndReplacesRatherThanOverlays() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline(); byte[] config = base.configurations.get(FACE);
        byte[] original = zip(Map.of("face.json", config, "metadata.json", new byte[]{9},
                "Resources/Images.plist", new byte[]{1}, "Resources/old.jpg", new byte[]{2},
                "Resources/depth/old.bin", new byte[]{3}));
        byte[] replacement = zip(Map.of("face.json", config, "Resources/Images.plist", new byte[]{4},
                "Resources/new.jpg", new byte[]{5}));
        base.archives.put(FACE, original);
        try (var plan = ClockFaceDeltaPlan.resources(base, FACE, replacement, NativeFacePackageStore.hash(original))) {
            var changes = plan.copyChanges(); assertEquals(1, changes.size());
            try (var message = ClockFaceSyncFrame.decodeChange(changes.get(0))) {
                assertEquals(2, message.type()); assertEquals(FACE, message.faceUuid().toString());
                assertEquals(Set.of("Images.plist", "new.jpg"), unzip(message.payload()).keySet());
            }
            var observed = ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1);
            assertTrue(plan.matches(observed)); assertEquals(base.ordered, observed.ordered); assertEquals(base.selected, observed.selected);
            assertArrayEquals(config, observed.configurations.get(FACE));
            var files = unzip(observed.nativePackage(FACE));
            assertEquals(Set.of("face.json", "metadata.json", "Resources/Images.plist", "Resources/new.jpg"), files.keySet());
            assertArrayEquals(new byte[]{9}, files.get("metadata.json"));
            files.put("Resources/old.jpg", new byte[]{2}); observed.archives.put(FACE, zip(files));
            assertFalse(plan.matches(observed)); // deletion is part of the resource proof
            files.remove("Resources/old.jpg"); files.put("Resources/new.jpg", new byte[]{6}); observed.archives.put(FACE, zip(files));
            assertFalse(plan.matches(observed));
        }
    }
    @Test public void stalePackageAndChangedConfigurationAreRejectedBeforeEncoding() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline(); byte[] config = base.configurations.get(FACE);
        byte[] archive = zip(Map.of("face.json", config, "Resources/Images.plist", new byte[]{1}));
        String hash = NativeFacePackageStore.hash(base.nativePackage(FACE));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.resources(base, FACE, archive, "0".repeat(64)));
        byte[] other = zip(Map.of("face.json", "{\"bundle id\":\"different\"}".getBytes(), "Resources/Images.plist", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.resources(base, FACE, other, hash));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaPlan.resources(base, NEW_FACE, archive, hash));
    }
    @Test public void largeResourcesRetainTypeUuidAndWideLoadMetadataAcrossEveryPart() throws Exception {
        byte[] image = new byte[300000]; new Random(9).nextBytes(image);
        byte[] resources = zip(Map.of("Images.plist", new byte[]{1}, "large.jpg", image));
        var parts = NtkFaceChangeEncoder.resourceParts(FACE, resources);
        assertEquals(3, parts.size()); var assembled = new ByteArrayOutputStream(); UUID load = null;
        for (int i = 0; i < parts.size(); i++) try (var message = ClockFaceSyncFrame.decodeChange(parts.get(i))) {
            assertEquals(2, message.type()); assertEquals(FACE, message.faceUuid().toString()); assertTrue(message.multipart());
            if (load == null) load = message.wideLoadId(); else assertEquals(load, message.wideLoadId());
            assertEquals(3, message.numberOfParts()); assertEquals(i, message.partNumber());
            assembled.write(message.payload());
        }
        assertArrayEquals(resources, assembled.toByteArray());
    }
    @Test public void wrongWrapperTraversalAmbiguousPathsAndBombsAreRejected() throws Exception {
        for (String path : List.of("../bad", "/bad", "a/./b", "a//b", "a\\b", "face.json", "Resources/photo.jpg")) {
            byte[] bytes = zip(Map.of(path, new byte[]{1}));
            assertThrows(IOException.class, () -> NtkFaceResources.validate(bytes));
        }
        byte[] conflict = zip(Map.of("photo", new byte[]{1}, "photo/child", new byte[]{2}));
        assertThrows(IOException.class, () -> NtkFaceResources.validate(conflict));
        byte[] bomb = zip(Map.of("huge", new byte[32 * 1024 * 1024 + 1]));
        assertThrows(IOException.class, () -> NtkFaceResources.validate(bomb));
    }
    @Test public void stagedResourceCommandConsumesSealedFileButPinsOriginalPackage() throws Exception {
        var base = ClockFaceDeltaPlanTest.baseline(); byte[] config = base.configurations.get(FACE);
        byte[] archive = zip(Map.of("face.json", config, "Resources/Images.plist", new byte[]{1}));
        var directory = java.nio.file.Files.createTempDirectory("native-resource-staged");
        var token = UUID.randomUUID().toString(); var file = directory.resolve(token + ".watchface");
        try {
            java.nio.file.Files.write(file, archive);
            var command = ClockFaceDeltaCommand.parse(ClockFaceDeltaCommand.RESOURCES_FILE + FACE + ":" + token + ":"
                    + NativeFacePackageStore.hash(archive) + ":" + NativeFacePackageStore.hash(base.nativePackage(FACE)));
            assertEquals(ClockFaceDeltaPlan.Kind.RESOURCES, command.kind());
            try (var plan = command.plan(base, directory)) { assertEquals(ClockFaceDeltaPlan.Kind.RESOURCES, plan.kind); }
            assertFalse(java.nio.file.Files.exists(file));
            assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaCommand.parse(ClockFaceDeltaCommand.RESOURCES_FILE
                    + FACE + ":" + token + ":" + NativeFacePackageStore.hash(archive)));
        } finally { java.nio.file.Files.deleteIfExists(file); java.nio.file.Files.deleteIfExists(directory); }
    }
}
