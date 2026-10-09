package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/** Offline replay on cloned files only. No Bluetooth, IDS or production file access. */
public final class BenchmarkClockFaceReceiver {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("root recreate|reuse trials sessions...");
        Path root = Path.of(args[0]).toRealPath();
        if (!root.startsWith("/data/local/tmp") && !root.startsWith("/tmp")
                && !root.startsWith("/private/tmp")) throw new IllegalArgumentException("Temporary clone required");
        boolean reuse = switch (args[1]) {
            case "reuse" -> true;
            case "recreate" -> false;
            default -> throw new IllegalArgumentException("Unknown receiver mode");
        };
        int trials = Integer.parseInt(args[2]);
        if (trials < 1 || trials > 8) throw new IllegalArgumentException("Bounded trials required");
        Path seed = root.resolve("seed");
        List<Path> pairs;
        try (var dirs = Files.list(seed.resolve("clockface-receiver"))) { pairs = dirs.toList(); }
        if (pairs.size() != 1) throw new IllegalArgumentException("One cloned pair required");
        String pair = pairs.get(0).getFileName().toString();
        UUID.fromString(pair);
        List<List<byte[]>> sessions = new ArrayList<>();
        List<Path> records;
        try (var files = Files.list(seed.resolve("clockface-journal").resolve(pair))) {
            records = files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.sydata")).sorted().toList();
        }
        if (records.size() > 8192) throw new IllegalArgumentException("Cloned journal exceeds bound");
        for (String session : Arrays.copyOfRange(args, 3, args.length)) {
            byte[] start = null, end = null;
            TreeMap<Long, byte[]> batches = new TreeMap<>();
            for (Path path : records) {
                if (Files.size(path) > ClockFaceSyncFrame.MAX_BYTES) throw new IllegalArgumentException("Oversized captured record");
                byte[] bytes = Files.readAllBytes(path);
                if (!path.getFileName().toString().equals(ClockFaceSyncJournal.sha256(bytes) + ".sydata")) {
                    throw new IllegalArgumentException("Captured record digest mismatch");
                }
                try (var frame = ClockFaceSyncFrame.parse(bytes)) {
                    if (!session.equals(frame.sessionText())) continue;
                    if (frame.messageId == ClockFaceSyncAccept.START) {
                        if (!frame.resetSync) throw new IllegalArgumentException("Complete reset session required");
                        if (start == null) start = bytes;
                    } else if (frame.messageId == ClockFaceSyncAccept.END) {
                        if (frame.rollback || frame.endHasError) throw new IllegalArgumentException("Successful END required");
                        if (end == null) end = bytes;
                    } else if (frame.messageId == ClockFaceSyncAccept.BATCH) {
                        batches.putIfAbsent(frame.batchIndex, bytes);
                    }
                }
            }
            if (start == null || end == null || batches.isEmpty()) throw new IllegalArgumentException("Incomplete captured session");
            long index = 0;
            for (long actual : batches.keySet()) if (actual != index++) throw new IllegalArgumentException("Captured batch gap");
            List<byte[]> packets = new ArrayList<>();
            packets.add(start); packets.addAll(batches.values()); packets.add(end); sessions.add(packets);
            System.out.println("capture=" + session + " packets=" + packets.size());
        }
        System.out.println("offline=true transport=false productionState=false reuse=" + reuse + " seedRecords=" + records.size());
        for (int trial = 0; trial < trials; trial++) {
            Path run = root.resolve("run-" + UUID.randomUUID());
            copyTree(seed, run);
            try {
                // Incoming production packets have new hashes. Do not benchmark
                // the cheaper existing-record retransmission branch by accident.
                for (var packets : sessions) {
                    for (byte[] bytes : packets) Files.deleteIfExists(run.resolve("clockface-journal")
                            .resolve(pair).resolve(ClockFaceSyncJournal.sha256(bytes) + ".sydata"));
                }
                System.out.println("trial=" + trial + " initialRecords=" + recordCount(run, pair));
                ClockFaceSyncReceiver retained = receiver(run, pair);
                if (reuse) {
                    long before = System.nanoTime();
                    int retired = retained.compactJournal();
                    System.out.println("trial=" + trial + " startupMicros=" + (System.nanoTime() - before) / 1000
                            + " retired=" + retired);
                }
                for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
                    List<Long> batchTimes = new ArrayList<>();
                    long started = System.nanoTime();
                    ClockFaceSyncReceiver.Receipt last = null;
                    for (byte[] bytes : sessions.get(sessionIndex)) {
                        ClockFaceSyncReceiver current = reuse ? retained : receiver(run, pair);
                        long before = System.nanoTime();
                        last = current.accept(bytes, System.currentTimeMillis());
                        long elapsed = (System.nanoTime() - before) / 1000;
                        if ((bytes[0] & 255) == ClockFaceSyncAccept.BATCH) batchTimes.add(elapsed);
                    }
                    long total = (System.nanoTime() - started) / 1000;
                    if (last == null || !"COMPLETED".equals(last.stage()) || !last.complete()) {
                        throw new IllegalStateException("Replay did not commit a complete native collection");
                    }
                    System.out.println("trial=" + trial + " session=" + sessionIndex + " totalMicros=" + total
                            + " batchP50=" + percentile(batchTimes, 0.50) + " batchP95=" + percentile(batchTimes, 0.95)
                            + " batchP99=" + percentile(batchTimes, 0.99) + " batches=" + last.batches()
                            + " faces=" + last.observedFaces() + " records=" + recordCount(run, pair));
                }
            } finally { deleteTree(run); }
        }
    }

    private static ClockFaceSyncReceiver receiver(Path run, String pair) {
        return new ClockFaceSyncReceiver(run.resolve("clockface-journal"), run.resolve("clockface-receiver"), pair);
    }
    private static long percentile(List<Long> values, double fraction) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }
    private static long recordCount(Path run, String pair) throws IOException {
        try (var files = Files.list(run.resolve("clockface-journal").resolve(pair))) { return files.count(); }
    }
    private static void copyTree(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }
    private static void deleteTree(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
