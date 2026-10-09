package dev.applewatchandroid.companion.apple_watch_companion;

import android.os.Debug;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Measures the production text processor only. No Binder, app or Watch access. */
public final class NativeMonogramTextBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/native-monogram-[0-9]+\\.json")) throw new IllegalArgumentException();
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException();
        JSONArray rows = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("cases");
        JSONArray measurements = new JSONArray();
        for (String input : new String[]{"ab cd", "ééé", "abcक्‍ष", "abcdß", "abcd\u0600A", "abcd" + "\u0301".repeat(256) + "A"}) {
            String expected = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (row.getString("locale").equals("en_US") && row.getString("input").equals(input)) {
                    expected = row.getString("normalized"); break;
                }
            }
            if (expected == null) throw new IllegalArgumentException("Missing independent native case");
            for (int i = 0; i < 2000; i++) NativeMonogramTextNormalizer.normalize(input, Locale.US);
            System.gc();
            long[] nanos = new long[1000];
            long before = Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"));
            for (int i = 0; i < nanos.length; i++) {
                long start = System.nanoTime();
                String result = NativeMonogramTextNormalizer.normalize(input, Locale.US);
                nanos[i] = System.nanoTime() - start;
                if (!result.equals(expected)) throw new AssertionError("Native benchmark value changed");
            }
            long allocated = Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated")) - before;
            Arrays.sort(nanos);
            measurements.put(new JSONObject().put("inputUtf16", input.length()).put("outputUtf16", expected.length())
                .put("samples", nanos.length).put("p50Micros", nanos[499] / 1000.0)
                .put("p95Micros", nanos[949] / 1000.0).put("allocatedBytesPerCall", allocated / (double)nanos.length));
        }
        System.out.println(new JSONObject().put("localOnly", true).put("scope", "production text processor")
            .put("measurements", measurements));
    }
}
