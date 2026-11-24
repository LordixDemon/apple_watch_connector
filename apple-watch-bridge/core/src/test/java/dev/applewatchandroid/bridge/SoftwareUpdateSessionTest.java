package dev.applewatchandroid.bridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class SoftwareUpdateSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID PAIR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EPOCH = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final long NOW = 100_000;
    static byte[] archive(String className, Map<String, Object> fields, Object... values) {
        List<Object> objects = new ArrayList<>(List.of("$null"));
        Map<String, Object> root = new LinkedHashMap<>(fields);
        root.put("$class", new AppleBinaryPropertyList.Uid(2));
        objects.add(root);
        objects.add(Map.of("$classname", className, "$classes", List.of(className, "NSObject")));
        objects.addAll(List.of(values));
        return AppleBinaryPropertyList.encode(Map.of("$archiver", "NSKeyedArchiver", "$version", 100000L,
                "$objects", objects, "$top", Map.of("root", new AppleBinaryPropertyList.Uid(1))));
    }
    static byte[] descriptor(String build, boolean terms) {
        return archive("SUBDescriptor", Map.of("productVersion", new AppleBinaryPropertyList.Uid(3),
                "productBuildVersion", new AppleBinaryPropertyList.Uid(4),
                "downloadSize", 1000000L, "TotalRequiredFreeSpace", 2000000L,
                "userDidAcceptTermsAndConditions", terms), "27.0.1", build);
    }
    static byte[] nil() { return archive("NSNull", Map.of()); }
    private SoftwareUpdateSession session(Path dir, String build) throws Exception {
        return new SoftwareUpdateSession(PAIR, EPOCH, "26.2", build, dir);
    }
    private static void state(SoftwareUpdateSession session, int nativeState, byte[] descriptor, long now) throws Exception {
        String id = UUID.randomUUID().toString();
        session.queued(id, session.prepare("UPDATE_QUERY", now));
        assertTrue(session.receive(AppleBinaryPropertyList.encode(Map.of("ManagerState", (long) nativeState,
                "Descriptor", descriptor)), id, now));
    }
    private static Map<?, ?> snapshot(SoftwareUpdateSession session) {
        return (Map<?, ?>) BoundedJson.decode(session.json().getBytes(java.nio.charset.StandardCharsets.UTF_8), 8192);
    }
    @Test public void nativeDescriptorBytesArePreservedWithoutInventingPasscode() {
        byte[] source = descriptor("24R365", true);
        Map<?, ?> request = SoftwareUpdateCodec.message(SoftwareUpdateCodec.request(SoftwareUpdateCodec.INSTALL, source));
        assertArrayEquals(source, (byte[]) request.get("Descriptor"));
        assertEquals("InstallUpdate", request.get("MessageType"));
        assertFalse(request.containsKey("Passcode"));
    }
    @Test public void archiveRejectsOtherClassesAndBadReferences() {
        assertThrows(IllegalArgumentException.class, () -> SoftwareUpdateCodec.descriptor(archive("NSObject", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> SoftwareUpdateCodec.descriptor(archive("SUBDescriptor",
                Map.of("productVersion", new AppleBinaryPropertyList.Uid(9000)))));
        assertThrows(IllegalArgumentException.class, () -> SoftwareUpdateCodec.errorCode("not an archive"));
        assertNull(SoftwareUpdateCodec.progress(nil()));
    }
    @Test public void queryRequiresCorrelatedNewestResponse() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            byte[] reply = AppleBinaryPropertyList.encode(Map.of("ManagerState", 1L, "Descriptor", nil()));
            assertFalse(session.receive(reply, null, NOW));
            String old = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
            session.queued(old, session.prepare("UPDATE_QUERY", NOW));
            session.queued(current, session.prepare("UPDATE_QUERY", NOW));
            assertFalse(session.receive(reply, old, NOW));
            assertTrue(session.receive(reply, current, NOW));
            assertFalse(session.receive(reply, current, NOW));
        }
    }
    @Test public void unsolicitedScanSupersedesOlderQuery() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            String old = UUID.randomUUID().toString();
            session.queued(old, session.prepare("UPDATE_QUERY", NOW));
            assertTrue(session.receive(AppleBinaryPropertyList.encode(Map.of("MessageType", "ScanResult",
                    "Descriptor", descriptor("24R365", true), "Error", nil())), null, NOW));
            assertFalse(session.receive(AppleBinaryPropertyList.encode(Map.of("ManagerState", 1L,
                    "Descriptor", nil())), old, NOW + 1));
            assertEquals("AVAILABLE", snapshot(session).get("phase"));
        }
    }
    @Test public void downloadRequiresFreshMatchingDescriptorAndNativeTerms() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_DOWNLOAD:24R365", NOW));
            state(session, 3, descriptor("24R365", false), NOW);
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_DOWNLOAD:24R365", NOW));
            state(session, 3, descriptor("24R365", true), NOW);
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_DOWNLOAD:24R366", NOW));
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_DOWNLOAD:24R365", NOW + 60001));
            assertEquals("StartDownload", SoftwareUpdateCodec.message(session.prepare("UPDATE_DOWNLOAD:24R365", NOW)).get("MessageType"));
            assertEquals("AVAILABLE", snapshot(session).get("phase")); // Queue preparation isn't native progress.
        }
    }
    @Test public void installRequiresPreparedAndCannotBeRepeatedAfterAmbiguousSubmission() throws Exception {
        Path dir = temporary.newFolder().toPath();
        try (var session = session(dir, "23S303")) {
            state(session, 3, descriptor("24R365", true), NOW);
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_INSTALL:24R365", NOW));
            state(session, 6, descriptor("24R365", true), NOW);
            session.prepare("UPDATE_INSTALL:24R365", NOW);
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_INSTALL:24R365", NOW));
            assertEquals(Boolean.TRUE, snapshot(session).get("installAttempted"));
        }
        try (var resumed = session(dir, "23S303")) {
            state(resumed, 6, descriptor("24R365", true), NOW);
            assertThrows(IllegalStateException.class, () -> resumed.prepare("UPDATE_INSTALL:24R365", NOW));
            assertFalse(Files.readString(dir.resolve("watch-update-checkpoint.json")).contains("Descriptor"));
        }
    }
    @Test public void installedStateTenDoesNotMeanBootedSuccess() throws Exception {
        Path dir = temporary.newFolder().toPath();
        try (var session = session(dir, "23S303")) {
            state(session, 6, descriptor("24R365", true), NOW);
            session.prepare("UPDATE_INSTALL:24R365", NOW);
            state(session, 10, descriptor("24R365", true), NOW);
            assertEquals("VERIFYING_RESTART", snapshot(session).get("phase"));
        }
        try (var resumed = session(dir, "23S303")) {
            state(resumed, 1, nil(), NOW);
            assertNotEquals("COMPLETE", snapshot(resumed).get("phase"));
        }
        try (var resumed = session(dir, "24R365")) {
            state(resumed, 1, nil(), NOW);
            assertEquals("COMPLETE", snapshot(resumed).get("phase"));
            assertFalse(Files.exists(dir.resolve("watch-update-checkpoint.json")));
        }
    }
    @Test public void anotherNativeTargetCannotReplacePendingInstallTarget() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            state(session, 6, descriptor("24R365", true), NOW);
            session.prepare("UPDATE_INSTALL:24R365", NOW);
            state(session, 1, descriptor("23S303", true), NOW);
            assertEquals("24R365", snapshot(session).get("targetBuild"));
            assertNotEquals("COMPLETE", snapshot(session).get("phase"));
        }
    }
    @Test public void emptyScanClearsOldDescriptorAndUnknownStatesDoNotCommit() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            state(session, 3, descriptor("24R365", true), NOW);
            assertTrue(session.receive(AppleBinaryPropertyList.encode(Map.of("MessageType", "ScanResult",
                    "Descriptor", nil(), "Error", nil())), null, NOW));
            assertNull(snapshot(session).get("targetBuild"));
            assertEquals(Boolean.TRUE, snapshot(session).get("scanCompleted"));
            String before = session.json();
            String id = UUID.randomUUID().toString();
            session.queued(id, session.prepare("UPDATE_QUERY", NOW));
            assertThrows(IllegalArgumentException.class, () -> session.receive(
                    AppleBinaryPropertyList.encode(Map.of("ManagerState", 99L)), id, NOW));
            assertEquals(before, session.json());
        }
    }
    @Test public void corruptCheckpointFailsClosedAndNoAgreementIsAcceptedBlindly() throws Exception {
        Path dir = temporary.newFolder().toPath();
        Files.writeString(dir.resolve("watch-update-checkpoint.json"), "{invalid");
        assertThrows(java.io.IOException.class, () -> session(dir, "23S303"));
        assertFalse(SoftwareUpdateSession.allows("UPDATE_TERMS:24R365"));
        assertFalse(SoftwareUpdateSession.allows("UPDATE_INSTALL:24R365\nSTOP"));
    }
    @Test public void malformedDescriptorCannotRefreshAStalePreparedArchive() throws Exception {
        try (var session = session(temporary.newFolder().toPath(), "23S303")) {
            state(session, 6, descriptor("24R365", true), NOW);
            String id = UUID.randomUUID().toString();
            session.queued(id, session.prepare("UPDATE_QUERY", NOW));
            assertThrows(IllegalArgumentException.class, () -> session.receive(AppleBinaryPropertyList.encode(
                    Map.of("ManagerState", 6L, "Descriptor", "invalid")), id, NOW + 1));
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_INSTALL:24R365", NOW + 60001));
            state(session, 6, nil(), NOW + 60002);
            assertNull(snapshot(session).get("targetBuild"));
            assertThrows(IllegalStateException.class, () -> session.prepare("UPDATE_INSTALL:24R365", NOW + 60002));
        }
    }
}
