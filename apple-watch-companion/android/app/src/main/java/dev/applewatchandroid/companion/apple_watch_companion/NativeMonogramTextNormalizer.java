package dev.applewatchandroid.companion.apple_watch_companion;

import java.util.Locale;

/** Original composed-range expansion before host-locale uppercase.
 * Uses versioned watchOS data, independent of the device's Android ICU version. */
final class NativeMonogramTextNormalizer {
    private NativeMonogramTextNormalizer() { }

    static String normalize(String input, Locale locale) {
        if (input == null || input.length() > 4096 || locale == null) {
            throw new IllegalArgumentException("Invalid monogram input");
        }
        // Ill-formed UTF-16 cannot make a round trip through a binary plist.
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i))) {
                    throw new IllegalArgumentException("Invalid monogram input");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Invalid monogram input");
            }
        }
        int end = NativeFoundationClusters.prefixEnd(input, 5);
        return NativeFoundationUppercase.uppercase(input.substring(0, end), locale);
    }
}
