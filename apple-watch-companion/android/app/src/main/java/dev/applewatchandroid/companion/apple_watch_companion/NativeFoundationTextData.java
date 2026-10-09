package dev.applewatchandroid.companion.apple_watch_companion;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Immutable versioned native scalar data, loaded once for the text module. */
final class NativeFoundationTextData {
    static final NativeFoundationTextData INSTANCE = load();
    private final int[] caseKeys, letters, marks, combiningKeys, combiningValues, decompositionKeys;
    private final String[] caseValues;
    private final int[][] decompositions;
    private final int[] extendsRanges, pictographic, prepend, spacing;

    private NativeFoundationTextData(DataInputStream input) throws IOException {
        if (input.readInt() != 0x464e5458 || input.readInt() != 2) throw new IOException("Wrong native text schema");
        caseKeys = new int[count(input, 8192)]; caseValues = new String[caseKeys.length];
        for (int i = 0; i < caseKeys.length; i++) {
            caseKeys[i] = input.readInt();
            char[] text = new char[countByte(input, 16)];
            for (int j = 0; j < text.length; j++) text[j] = input.readChar();
            caseValues[i] = new String(text);
        }
        letters = ranges(input); marks = ranges(input);
        combiningKeys = new int[count(input, 8192)]; combiningValues = new int[combiningKeys.length];
        for (int i = 0; i < combiningKeys.length; i++) {
            combiningKeys[i] = input.readInt(); combiningValues[i] = input.readUnsignedByte();
        }
        decompositionKeys = new int[count(input, 8192)]; decompositions = new int[decompositionKeys.length][];
        for (int i = 0; i < decompositionKeys.length; i++) {
            decompositionKeys[i] = input.readInt(); decompositions[i] = new int[countByte(input, 64)];
            for (int j = 0; j < decompositions[i].length; j++) decompositions[i][j] = input.readInt();
        }
        extendsRanges = ranges(input); pictographic = ranges(input);
        prepend = ranges(input); spacing = ranges(input);
        if (input.read() != -1) throw new IOException("Trailing native text data");
    }

    private static NativeFoundationTextData load() {
        try {
            byte[] compressed = android.util.Base64.decode(NativeFoundationTextPayload.BASE64_GZIP, 0);
            byte[] bytes;
            try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                // API 24-compatible and bounded while reading, including corrupt payloads.
                ByteArrayOutputStream output = new ByteArrayOutputStream(34000);
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > 1024 * 1024) throw new IOException("Native text data too large");
                    output.write(buffer, 0, size);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length > 1024 * 1024) throw new IOException("Native text data too large");
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equals(NativeFoundationTextPayload.SHA256)) throw new IOException("Wrong native text digest");
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                return new NativeFoundationTextData(input);
            }
        } catch (Exception invalid) { throw new ExceptionInInitializerError(invalid); }
    }
    private static int count(DataInputStream input, int max) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > max) throw new IOException("Invalid native text count");
        return count;
    }
    private static int countByte(DataInputStream input, int max) throws IOException {
        int count = input.readUnsignedByte();
        if (count < 1 || count > max) throw new IOException("Invalid native text count");
        return count;
    }
    private static int[] ranges(DataInputStream input) throws IOException {
        int[] result = new int[count(input, 4096) * 2];
        for (int i = 0; i < result.length; i++) result[i] = input.readInt();
        return result;
    }
    private static boolean member(int[] ranges, int scalar) {
        int low = 0, high = ranges.length / 2;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (scalar < ranges[2 * middle]) high = middle;
            else if (scalar > ranges[2 * middle + 1]) low = middle + 1;
            else return true;
        }
        return false;
    }
    boolean letter(int scalar) { return member(letters, scalar); }
    boolean nonBase(int scalar) { return member(marks, scalar); }
    boolean extend(int scalar) { return member(extendsRanges, scalar); }
    boolean pictographic(int scalar) { return member(pictographic, scalar); }
    boolean prepend(int scalar) { return member(prepend, scalar); }
    boolean spacing(int scalar) { return member(spacing, scalar); }
    boolean postcore(int scalar) { return scalar == 0x200d || extend(scalar) || member(spacing, scalar); }
    int combining(int scalar) {
        int index = Arrays.binarySearch(combiningKeys, scalar);
        return index < 0 ? 0 : combiningValues[index];
    }
    String upper(int scalar) {
        int index = Arrays.binarySearch(caseKeys, scalar);
        return index < 0 ? new String(Character.toChars(scalar)) : caseValues[index];
    }
    int[] greekDecomposition(int scalar) {
        int index = Arrays.binarySearch(decompositionKeys, scalar);
        return index < 0 ? null : decompositions[index].clone();
    }
}
