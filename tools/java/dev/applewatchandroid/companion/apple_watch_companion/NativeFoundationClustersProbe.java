package dev.applewatchandroid.companion.apple_watch_companion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.json.JSONObject;

/** Exhaustive native boundary replay in an isolated shell VM. */
public final class NativeFoundationClustersProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/native-clusters-[0-9]+\\.json")) {
            throw new IllegalArgumentException("Expected isolated native boundary corpus");
        }
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Corpus too large");
        JSONObject document = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (document.getInt("schema") != 2 || document.getInt("utf16Limit") != 5
            || !document.getString("runtime").equals("Version 26.2 (Build 23S303)")) {
            throw new IllegalArgumentException("Unverified native boundary corpus");
        }
        var patterns = document.getJSONArray("templates");
        long checked = 0;
        int failures = 0;
        for (int i = 0; i < patterns.length(); i++) {
            var row = patterns.getJSONObject(i);
            if (row.getInt("checked") != 1112064) throw new IllegalArgumentException("Incomplete template");
            int[] ends = new int[0x110000]; Arrays.fill(ends, -1);
            var different = row.getJSONArray("differentEnds");
            for (int j = 0; j < different.length(); j++) {
                var value = different.getJSONArray(j);
                Arrays.fill(ends, value.getInt(0), value.getInt(1) + 1, value.getInt(2));
            }
            for (int cp = 0; cp <= 0x10ffff; cp++) {
                if (cp >= 0xd800 && cp <= 0xdfff) continue;
                String text = row.getString("prefix") + new String(Character.toChars(cp)) + row.getString("suffix");
                int expected = Math.min(text.length(), 5);
                if (expected < text.length() && Character.isHighSurrogate(text.charAt(expected - 1))) expected++;
                if (ends[cp] >= 0) expected = ends[cp];
                int observed = NativeFoundationClusters.prefixEnd(text, 5);
                if (observed != expected) {
                    failures++;
                    if (failures <= 40) System.out.println("mismatch template=" + i + " scalar=" + cp
                        + " expected=" + expected + " observed=" + observed);
                }
                checked++;
            }
        }
        System.out.println("localOnly=true boundaryCases=" + checked + " failures=" + failures);
        if (failures != 0) throw new AssertionError("Native boundary mismatches");
    }
}
