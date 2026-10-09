package dev.applewatchandroid.bridge;

import android.os.Debug;
import java.util.*;

/** Synthetic app_process replay only; no transport, Binder or production state. */
public final class NtkPreferenceReplayBenchmark {
    private static final long NOW = 1_800_000_000_000L;
    private static volatile int blackhole;

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No input/state paths accepted");
        for (int count : new int[]{1, 128, 512}) {
            byte[] data = frame(count);
            for (int i = 0; i < 1000; i++) { consume(data, false); consume(data, true); }
            for (int trial = 0; trial < 6; trial++) {
                // Alternate order to limit warming/thermal and GC ordering bias.
                for (int pass = 0; pass < 2; pass++) {
                    boolean shared = (trial + pass) % 2 == 0;
                    System.gc();
                    long allocated = allocated(), started = System.nanoTime();
                    for (int i = 0; i < 1000; i++) consume(data, shared);
                    long elapsed = System.nanoTime() - started, bytes = allocated() - allocated;
                    System.out.println("localOnly=true mode=" + (shared ? "shared" : "separate")
                            + " names=" + count + " trial=" + trial + " iterations=1000"
                            + " nanos=" + elapsed + " allocated=" + bytes);
                }
            }
            Arrays.fill(data, (byte) 0);
        }
        System.out.println("checksum=" + blackhole);
    }

    private static long allocated() {
        String value = Debug.getRuntimeStat("art.gc.bytes-allocated");
        if (value == null) throw new IllegalStateException("ART allocation statistics unavailable");
        return Long.parseLong(value);
    }

    private static void consume(byte[] data, boolean shared) {
        Map<String, List<PigmentPreferenceCodec.Report>> colors;
        List<MonogramPreferenceCodec.Report> text;
        if (shared) {
            try (var envelope = NtkPreferenceEnvelope.decode(data, NOW)) {
                colors = PigmentPreferenceCodec.decodeObservedLists(envelope);
                text = MonogramPreferenceCodec.decodeObserved(envelope);
            }
        } else {
            colors = PigmentPreferenceCodec.decodeObservedLists(data, NOW);
            text = MonogramPreferenceCodec.decodeObserved(data, NOW);
        }
        blackhole = colors.get(PigmentPreferenceCodec.KEY).get(0).names().size()
                + colors.get(PigmentPreferenceCodec.AUTO_KEY).get(0).names().size()
                + text.get(0).text().length();
    }

    private static byte[] frame(int count) {
        var names = new ArrayList<String>();
        for (int i = 0; i < count; i++) names.add("nativeColor" + i);
        double stamp = NOW / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS;
        byte[] colors = BinaryPropertyListCodec.encodeStringArray(names);
        byte[] text = BinaryPropertyListCodec.encodeStringRoot("É");
        var manual = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY, colors, true, stamp);
        var auto = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY, colors, true, stamp);
        var monogram = new PairedSyncCodec.UserDefaultsKey(MonogramPreferenceCodec.KEY, text, true, stamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(stamp, MonogramPreferenceCodec.DOMAIN,
                List.of(manual, auto, monogram), false);
        try { return PairedSyncCodec.encode(message); }
        finally {
            message.destroy(); manual.destroy(); auto.destroy(); monogram.destroy();
            Arrays.fill(colors, (byte) 0); Arrays.fill(text, (byte) 0);
        }
    }
}
