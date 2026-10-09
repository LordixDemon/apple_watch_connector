package dev.applewatchandroid.companion.apple_watch_companion;

import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Local app_process comparison against captured native data. No app/HAL access. */
public final class NativeFoundationCasingProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("/data/local/tmp/native-foundation-[0-9]+\\.json")
                || !args[1].matches("/data/local/tmp/native-casing-[0-9]+\\.json")) throw new IllegalArgumentException();
        JSONObject doc = read(args[0]);
        JSONArray map = doc.getJSONArray("uppercase");
        var upper = new HashMap<Integer, String>();
        for (int i = 0; i < map.length(); i++) {
            JSONArray row = map.getJSONArray(i); upper.put(row.getInt(0), row.getString(1));
        }
        BitSet letters = ranges(doc.getJSONArray("letterRanges")), marks = ranges(doc.getJSONArray("nonBaseRanges"));
        BitSet extendsSet = ranges(doc.getJSONArray("extendRanges")), pictures = ranges(doc.getJSONArray("pictographicRanges"));
        BitSet prepends = ranges(doc.getJSONArray("prependRanges")), spacing = ranges(doc.getJSONArray("spacingRanges"));
        int[] combining = new int[0x110000];
        JSONArray classes = doc.getJSONArray("combiningClasses");
        for (int i = 0; i < classes.length(); i++) {
            JSONArray row = classes.getJSONArray(i); combining[row.getInt(0)] = row.getInt(1);
        }
        var data = NativeFoundationTextData.INSTANCE;
        int checked = 0;
        for (int scalar = 0; scalar < 0x110000; scalar++) {
            if (scalar >= 0xd800 && scalar <= 0xdfff) continue;
            String expected = upper.getOrDefault(scalar, new String(Character.toChars(scalar)));
            if (!expected.equals(data.upper(scalar)) || letters.get(scalar) != data.letter(scalar)
                    || marks.get(scalar) != data.nonBase(scalar) || combining[scalar] != data.combining(scalar)
                    || extendsSet.get(scalar) != data.extend(scalar) || pictures.get(scalar) != data.pictographic(scalar)
                    || prepends.get(scalar) != data.prepend(scalar) || spacing.get(scalar) != data.spacing(scalar)) {
                throw new AssertionError("Native scalar mismatch: " + scalar);
            }
            checked++;
        }
        System.out.println("localOnly=true nativeScalars=" + checked + " mismatches=0");
        JSONArray rows = read(args[1]).getJSONArray("cases");
        int failures = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String actual = NativeFoundationUppercase.uppercase(row.getString("input"),
                    Locale.forLanguageTag(row.getString("locale").replace('_', '-')));
            if (!actual.equals(row.getString("uppercase"))) {
                failures++;
                if (failures < 30) System.out.println("mismatch index=" + i + " expected="
                    + JSONObject.quote(row.getString("uppercase")) + " actual=" + JSONObject.quote(actual));
            }
        }
        System.out.println("localOnly=true casingCases=" + rows.length() + " failures=" + failures);
        if (failures != 0) throw new AssertionError("Native contextual casing mismatches");
    }
    private static JSONObject read(String path) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(path));
        if (data.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Corpus too large");
        return new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }
    private static BitSet ranges(JSONArray ranges) throws Exception {
        BitSet result = new BitSet(0x110000);
        for (int i = 0; i < ranges.length(); i++) {
            JSONArray row = ranges.getJSONArray(i); result.set(row.getInt(0), row.getInt(1) + 1);
        }
        return result;
    }
}
