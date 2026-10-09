package dev.applewatchandroid.companion.apple_watch_companion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.json.JSONObject;

/** app_process local replay; no installed-app state, Binder, NPS or Bluetooth. */
public final class NativeMonogramNormalizationProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/native-monogram-[0-9]+\\.json")) {
            throw new IllegalArgumentException("Expected isolated native text corpus");
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        if (data.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Corpus too large");
        var rows = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("cases");
        int failures = 0;
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            var locale = Locale.forLanguageTag(row.getString("locale").replace('_', '-'));
            String observed = NativeMonogramTextNormalizer.normalize(row.getString("input"), locale);
            if (!row.getString("normalized").equals(observed)) {
                failures++;
                if (failures <= 40) System.out.println("mismatch index=" + i + " locale=" + locale
                    + " expected=" + JSONObject.quote(row.getString("normalized"))
                    + " observed=" + JSONObject.quote(observed));
            }
        }
        for (String bad : new String[]{"\ud800", "\udc00", "a\ud800b", "a".repeat(4097)}) {
            try { NativeMonogramTextNormalizer.normalize(bad, Locale.US); failures++; }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("localOnly=true cases=" + rows.length() + " failures=" + failures);
        if (failures != 0) throw new AssertionError("Native normalization mismatches");
    }
}
