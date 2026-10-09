package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NrOutgoingWireCaptureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void storesIndependentOwnerOnlyCopyAndManifest() throws Exception {
        NrOutgoingWireCapture capture = new NrOutgoingWireCapture(temporary.getRoot().toPath());
        byte[] frame = {3, 0, 0, 0, 3, 1, 2, 3};
        assertNull(capture.record("protobuf", NanoRegistryPropertyCodec.CLASS_C_SERVICE, 4, false, frame));
        Arrays.fill(frame, (byte) 0);
        Path file = capture.directory().resolve("001-protobuf-4.bin");
        assertArrayEquals(new byte[]{3, 0, 0, 0, 3, 1, 2, 3}, Files.readAllBytes(file));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(capture.directory()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(capture.directory().resolve("manifest.tsv")));
        assertTrue(new String(Files.readAllBytes(capture.directory().resolve("manifest.tsv")),
                java.nio.charset.StandardCharsets.UTF_8).contains("001-protobuf-4.bin"));
    }

    @Test public void doesNotCaptureCredentialsActivationOrArbitraryKinds() throws Exception {
        NrOutgoingWireCapture capture = new NrOutgoingWireCapture(temporary.getRoot().toPath());
        byte[] sensitive = {1, 2, 3};
        assertNull(capture.record("protobuf", IdsDeviceInfoExchange.TOPIC, 12, false, sensitive));
        assertNull(capture.record("protobuf", "com.apple.private.alloy.pbbridge", 2, false, sensitive));
        assertNull(capture.record("protobuf", NanoRegistryPropertyCodec.CLASS_D_SERVICE, 16, false, sensitive));
        assertNull(capture.record("../escape", NanoRegistryPropertyCodec.CLASS_C_SERVICE, 4, false, sensitive));
        assertEquals(0, capture.frameCount());
        try (var files = Files.list(capture.directory())) { assertEquals(1, files.count()); }
    }

    @Test public void stopsOnceAtBoundsWithoutThrowing() throws Exception {
        NrOutgoingWireCapture capture = new NrOutgoingWireCapture(temporary.getRoot().toPath(), 1, 8);
        assertNull(capture.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, new byte[8]));
        assertEquals("capture limit reached", capture.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE,
                1, false, new byte[1]));
        assertNull(capture.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, new byte[1]));
        assertEquals(1, capture.frameCount());
        NrOutgoingWireCapture small = new NrOutgoingWireCapture(temporary.getRoot().toPath(), 3, 7);
        assertEquals("capture limit reached", small.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE,
                1, false, new byte[8]));
        assertEquals(0, small.frameCount());
    }

    @Test public void storageFailureDisablesDiagnosticsOnly() throws Exception {
        NrOutgoingWireCapture capture = new NrOutgoingWireCapture(temporary.getRoot().toPath());
        Files.delete(capture.directory().resolve("manifest.tsv"));
        Files.delete(capture.directory());
        String issue = capture.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, new byte[8]);
        assertTrue(issue.startsWith("capture storage unavailable"));
        assertNull(capture.record("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, new byte[8]));
    }
}
