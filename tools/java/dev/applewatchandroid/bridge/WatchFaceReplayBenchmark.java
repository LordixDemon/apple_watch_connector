package dev.applewatchandroid.bridge;

import java.nio.file.*;
import java.util.*;

/** Local replay only: no Bluetooth, IDS, or production state access. */
public final class WatchFaceReplayBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated replay root");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.getParent().equals(Path.of("/data/local/tmp"))
                || !root.getFileName().toString().matches("watchface-benchmark-[0-9]+")) {
            throw new IllegalArgumentException("Replay must use a separate /data/local/tmp directory");
        }
        List<byte[]> packets = new ArrayList<>();
        try (var paths = Files.list(root.resolve("captures"))) {
            for (Path path : paths.sorted().toList()) {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length > 3 && bytes[1] == 0 && bytes[2] == 0
                        && (bytes[0] == 0x66 || bytes[0] == 0x67 || bytes[0] == 0x69)) {
                    packets.add(bytes);
                }
            }
        }
        System.out.println("localReplay=true transport=false productionState=false packets=" + packets.size());
        for (int trial = 0; trial < 6; trial++) {
            Path run = root.resolve("run-" + UUID.randomUUID());
            String pair = UUID.randomUUID().toString();
            var receiver = new ClockFaceSyncReceiver(run.resolve("journal"), run.resolve("state"), pair);
            long start = System.nanoTime();
            long last = 0;
            for (byte[] packet : packets) {
                try (var frame = ClockFaceSyncFrame.parse(packet)) {
                    var receipt = receiver.accept(packet, System.currentTimeMillis());
                    if (frame.messageId == ClockFaceSyncAccept.END) last = receipt.batches();
                }
            }
            long elapsed = (System.nanoTime() - start) / 1000;
            var snapshot = receiver.snapshot();
            System.out.println("trial=" + trial + " micros=" + elapsed + " batches=" + last
                    + " complete=" + snapshot.complete() + " faces=" + snapshot.configurations.size());
            try (var files = Files.walk(run)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
