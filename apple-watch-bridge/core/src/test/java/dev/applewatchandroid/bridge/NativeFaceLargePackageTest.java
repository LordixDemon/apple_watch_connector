package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NativeFaceLargePackageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private byte[] archive(byte[] configuration) throws Exception {
        byte[] photo = new byte[1024 * 1024]; new Random(42).nextBytes(photo);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("face.json")); zip.write(configuration); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Resources/photo.jpg")); zip.write(photo); zip.closeEntry();
        }
        return out.toByteArray();
    }
    @Test public void largeTransactionCorrelatesEveryBatchAndWaitsForActualWatchReadback() throws Exception {
        var baseline = ClockFaceDeltaPlanTest.baseline(); byte[] zip = archive(baseline.configurations.get(FACE));
        try (var plan = ClockFaceDeltaPlan.add(baseline, NEW_FACE, zip);
             var session = new ClockFaceDeltaSession(UUID.randomUUID(), UUID.randomUUID(), WATCH, SESSION, plan, 0)) {
            assertTrue(plan.batchCount() > 1);
            session.prepare(header(PHONE, 1), 1); UUID request = UUID.randomUUID(); session.sent(request, 2);
            assertTrue(session.response(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, 0, request,
                    reply(0x66, SESSION, WATCH, true, false, false, 0), NOW, 3));
            var rebuilt = new ByteArrayOutputStream(); UUID load = null; int part = 0;
            for (int index = 0; index < plan.batchCount(); index++) {
                byte[] packet = session.prepare(header(PHONE, index + 2), 4 + index * 3);
                ClockFaceDeltaProtocol.validateRequest(packet);
                try (var frame = ClockFaceSyncFrame.parse(packet)) {
                    assertEquals(index, frame.batchIndex);
                    for (byte[] change : frame.changes) try (var message = ClockFaceSyncFrame.decodeChange(change)) {
                        if (message.type() != 0) continue;
                        assertTrue(message.multipart()); assertEquals(part++, message.partNumber());
                        if (load == null) load = message.wideLoadId(); else assertEquals(load, message.wideLoadId());
                        rebuilt.write(message.payload());
                    }
                }
                request = UUID.randomUUID(); session.sent(request, 5 + index * 3);
                assertFalse(session.response(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, 0, request,
                        reply(0x67, SESSION, WATCH, true, false, false, index + 1), NOW, 6 + index * 3));
                assertTrue(session.response(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, 0, request,
                        reply(0x67, SESSION, WATCH, true, false, false, index), NOW, 6 + index * 3));
            }
            assertArrayEquals(zip, rebuilt.toByteArray());
            assertEquals(ClockFaceDeltaSession.Stage.END_READY, session.stage());
            session.prepare(header(PHONE, 100), 100); request = UUID.randomUUID(); session.sent(request, 101);
            assertTrue(session.response(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, 0, request,
                    reply(0x69, SESSION, WATCH, true, false, false, 0), NOW + 1, 102));
            assertEquals(ClockFaceDeltaSession.Stage.READBACK_WAIT, session.stage()); assertFalse(session.terminal());
        }
    }
    @Test public void nativeMultipartReceiverPersistsLargePackageWithoutPuttingPhotoBytesInState() throws Exception {
        var root = temporary.newFolder().toPath(); String pair = UUID.randomUUID().toString();
        var receiver = new ClockFaceSyncReceiver(root.resolve("journal"), root.resolve("state"), pair);
        byte[] zip = archive(ClockFaceDeltaPlanTest.baseline().configurations.get(FACE));
        receiver.accept(ClockFaceDeltaProtocol.start(header(PHONE, 1), SESSION), NOW);
        int index = 0;
        for (byte[] part : NtkFaceChangeEncoder.addArchiveParts(NEW_FACE, zip)) {
            receiver.accept(ClockFaceDeltaProtocol.batch(header(PHONE, index + 2), SESSION, index, List.of(part)), NOW + index + 1);
            index++;
        }
        receiver.accept(ClockFaceDeltaProtocol.batch(header(PHONE, 100), SESSION, index,
                List.of(NtkFaceChangeEncoder.order(List.of(NEW_FACE)), NtkFaceChangeEncoder.select(NEW_FACE))), NOW + 100);
        assertTrue(receiver.snapshot().configurations.isEmpty());
        receiver.accept(ClockFaceDeltaProtocol.end(header(PHONE, 101), SESSION), NOW + 101);
        var saved = new ClockFaceSyncReceiver(root.resolve("journal"), root.resolve("state"), pair).snapshot();
        assertArrayEquals(zip, saved.nativePackage(NEW_FACE)); assertTrue(saved.archives.isEmpty());
        assertEquals(NativeFacePackageStore.hash(zip), saved.packageDigests.get(NEW_FACE));
        assertTrue(AppleBinaryPropertyList.encode(saved.encode()).length < 4096);
        var observation = ClockFaceObservationCodec.fromCollection(UUID.fromString(pair), UUID.randomUUID(), saved);
        assertTrue(ClockFaceObservationCodec.encode(observation).length < 4096);
        assertEquals(NativeFacePackageStore.hash(zip), ClockFaceObservationCodec.decode(
                ClockFaceObservationCodec.encode(observation)).faces().get(0).packageDigest());
    }
    @Test public void stagedImportsVerifyHashDeleteConsumedFileAndRejectSymbolicLinks() throws Exception {
        var root = temporary.newFolder().toPath(); String upload = UUID.randomUUID().toString();
        byte[] zip = archive(ClockFaceDeltaPlanTest.baseline().configurations.get(FACE));
        String command = ClockFaceDeltaCommand.ADD_FILE + NEW_FACE + ":" + upload + ":" + NativeFacePackageStore.hash(zip);
        Path path = root.resolve(upload + ".watchface"); Files.write(path, zip);
        assertTrue(OperationalCommandPolicy.isAllowed(command));
        try (var plan = ClockFaceDeltaCommand.parse(command).plan(ClockFaceDeltaPlanTest.baseline(), root)) {
            assertTrue(plan.batchCount() > 1); assertFalse(Files.exists(path));
        }
        zip[zip.length - 1] ^= 1; Files.write(path, zip);
        assertThrows(IOException.class, () -> ClockFaceDeltaCommand.parse(command).plan(ClockFaceDeltaPlanTest.baseline(), root));
        Path blob = root.resolve(NativeFacePackageStore.hash(zip) + ".watchface"); Files.createSymbolicLink(blob, path);
        assertThrows(IOException.class, () -> new NativeFacePackageStore(root).save(zip));
    }
}
