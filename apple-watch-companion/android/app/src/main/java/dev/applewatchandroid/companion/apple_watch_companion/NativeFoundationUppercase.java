package dev.applewatchandroid.companion.apple_watch_companion;

import java.util.*;

/** Portable uppercase rules from CoreFoundation, backed by captured scalar data.
 * Not ICU Greek casing: Foundation preserves non-tonos decomposition/diacritics.
 * The scalar tables and contextual behavior are independently replayed on Android. */
// Algorithm reference: swift-corelibs-foundation CFUniChar.c, commit
// b2112d2d80c4365dbb32479d89bb3177bfda8ea8 (Apple Inc./Swift authors,
// Apache 2.0 with Runtime Library Exception). Native runtime data remains separate.
final class NativeFoundationUppercase {
    private static final int AFTER_I = 2, MORE_ABOVE = 4, GREEK_TONOS = 16;
    private static final NativeFoundationTextData DATA = NativeFoundationTextData.INSTANCE;
    private NativeFoundationUppercase() { }

    static String uppercase(String input, Locale locale) {
        String language = locale.getLanguage();
        boolean special = language.equals("az") || language.equals("lt") || language.equals("tr")
            || language.equals("nl") || language.equals("el");
        StringBuilder result = new StringBuilder();
        int flags = 0;
        for (int index = 0; index < input.length();) {
            int scalar = input.codePointAt(index), next = index + Character.charCount(scalar);
            int previousFlags = flags;
            flags = 0;
            // CoreFoundation handles capital sigma before the language branch;
            // uppercase sigma never establishes the Greek-tonos context flag.
            if (special && scalar != 0x03a3) {
                if (language.equals("lt") && scalar == 0x0307
                        && (previousFlags & (AFTER_I | MORE_ABOVE)) == (AFTER_I | MORE_ABOVE)) {
                    flags = AFTER_I;
                } else if (language.equals("lt") && (scalar == 'i' || scalar == 'j') && moreAbove(input, next)) {
                    flags = AFTER_I | MORE_ABOVE;
                } else if ((previousFlags & GREEK_TONOS) != 0 && DATA.nonBase(scalar)
                        || greek(scalar) && DATA.letter(scalar)) {
                    flags = GREEK_TONOS;
                }
            }
            if ((flags & GREEK_TONOS) != 0 && scalar == 0x0301) {
                // A tonos following a Greek letter is removed.
            } else if ((flags & GREEK_TONOS) != 0 && scalar == 0x0344) {
                result.append('\u0308');
            } else if ((flags & GREEK_TONOS) != 0 && appendGreek(result, scalar)) {
                // Decompose only when a tonos is present. Other suffixes stay literal.
            } else if (language.equals("lt") && scalar == 0x0307 && (flags & AFTER_I) != 0) {
                // Source flags can be set only by the immediately previous i/j.
            } else if ((language.equals("tr") || language.equals("az")) && (scalar == 'i' || scalar == 0x0130)) {
                result.append('\u0130');
            } else {
                result.append(DATA.upper(scalar));
            }
            index = next;
        }
        return result.toString();
    }
    private static boolean greek(int scalar) {
        return scalar >= 0x0370 && scalar < 0x0400 || scalar >= 0x1f00 && scalar < 0x2000;
    }
    private static boolean moreAbove(String input, int index) {
        while (index < input.length()) {
            int scalar = input.codePointAt(index), property = DATA.combining(scalar);
            if (property == 230) return true;
            if (property == 0) break;
            index += Character.charCount(scalar);
        }
        return false;
    }
    private static boolean appendGreek(StringBuilder result, int scalar) {
        int[] decomposed = DATA.greekDecomposition(scalar);
        if (decomposed == null || Arrays.stream(decomposed).noneMatch(v -> v == 0x0301)) return false;
        result.append(DATA.upper(decomposed[0]));
        for (int i = 1; i < decomposed.length; i++) if (decomposed[i] != 0x0301) result.appendCodePoint(decomposed[i]);
        return true;
    }
}
