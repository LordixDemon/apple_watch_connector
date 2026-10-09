package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Historical local diagnostic. Never opens Bluetooth, sends IDS or modifies Watch state. */
public final class ClockFaceSessionProbe {
    private static final Path CAPTURES = Paths.get("/data/user/0/dev.applewatchandroid.bridge/files/activation-capture");
    private static final Path ROOT = Paths.get("/data/user/0/dev.applewatchandroid.bridge/files/clockface-session-probe-353");
    private ClockFaceSessionProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[1].equals("historical-range") || args[1].equals("local-subset")
                || args[1].equals("local-reload"))) {
            throw new IllegalArgumentException("Expected canonical pair and historical-range/local-subset/local-reload");
        }
        Path mode = ROOT.resolve(args[1].equals("local-reload") ? "local-subset" : args[1]);
        ClockFaceSyncReceiver receiver = new ClockFaceSyncReceiver(mode.resolve("journal"), mode.resolve("state"), args[0]);
        System.out.println("historicalCapture=true liveDelivery=false watchMutation=false mode=" + args[1]);
        if (args[1].equals("local-reload")) { printSnapshot(receiver); return; }
        if (args[1].equals("historical-range")) {
            List<Path> captures;
            try (var files = Files.list(CAPTURES)) {
                captures = files.filter(path -> path.getFileName().toString().matches("[0-9]+-clockface-sync[.]bin"))
                        .filter(path -> timestamp(path) >= 1791244351391L && timestamp(path) <= 1791244361608L)
                        .sorted(Comparator.comparingLong(ClockFaceSessionProbe::timestamp)).collect(Collectors.toList());
            }
            int accepted = 0;
            for (Path capture : captures) {
                byte[] bytes = read(capture);
                try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(bytes)) {
                    try {
                        receiver.accept(bytes, timestamp(capture));
                        accepted++;
                    } catch (IllegalArgumentException refused) {
                        System.out.println("acceptedFrames=" + accepted + " refusedBatch=" + frame.batchIndex
                                + " reason=" + refused.getMessage());
                        printSnapshot(receiver);
                        return;
                    }
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
            throw new IllegalStateException("Expected the historical capture gap; accepted=" + accepted);
        }
        // Preserve the actual native change, header and session. Index is locally
        // changed to zero to exercise a one-batch transaction, not replay a full sync.
        byte[] start = read(CAPTURES.resolve("1791244351391-clockface-sync.bin"));
        byte[] original = read(CAPTURES.resolve("1791244359472-clockface-sync.bin"));
        byte[] end = read(CAPTURES.resolve("1791244361608-clockface-sync.bin"));
        byte[] subset = null;
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(original)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(ClockFaceSyncAccept.BATCH); out.write(0); out.write(0);
            field(out, 1, frame.header); field(out, 2, frame.sessionId);
            out.write(24); out.write(0);
            for (byte[] change : frame.changes) field(out, 4, change);
            subset = out.toByteArray();
            System.out.println("modifiedEnvelope=true originalIndex=" + frame.batchIndex + " localIndex=0");
            receiver.accept(start, 1791244351391L);
            var batch = receiver.accept(subset, 1791244359472L);
            System.out.println("stage=" + batch.stage() + " observedFacesBeforeEnd=" + receiver.snapshot().configurations.size());
            var completed = receiver.accept(end, 1791244361608L);
            System.out.println("stage=" + completed.stage() + " duplicate=" + completed.duplicate());
            printSnapshot(new ClockFaceSyncReceiver(mode.resolve("journal"), mode.resolve("state"), args[0]));
        } finally {
            Arrays.fill(start, (byte) 0); Arrays.fill(original, (byte) 0); Arrays.fill(end, (byte) 0);
            if (subset != null) Arrays.fill(subset, (byte) 0);
        }
    }

    private static void printSnapshot(ClockFaceSyncReceiver receiver) throws Exception {
        ClockFaceCollection snapshot = receiver.snapshot();
        System.out.println("historicalSnapshot=true observedFaces=" + snapshot.configurations.size()
                + " complete=" + snapshot.complete() + " orderKnown=" + snapshot.orderKnown
                + " selectionKnown=" + snapshot.selectionKnown + " historicalObservedAt=" + snapshot.observedAt);
    }

    private static long timestamp(Path path) {
        return Long.parseLong(path.getFileName().toString().split("-", 2)[0]);
    }

    private static byte[] read(Path path) throws Exception {
        if (Files.size(path) > ClockFaceSyncFrame.MAX_BYTES) throw new IllegalArgumentException("Capture too large");
        return Files.readAllBytes(path);
    }

    private static void field(ByteArrayOutputStream out, int number, byte[] bytes) {
        varint(out, (number << 3) | 2); varint(out, bytes.length); out.write(bytes, 0, bytes.length);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 127); value >>>= 7;
            out.write(value == 0 ? next : next | 128);
        } while (value != 0);
    }
}
