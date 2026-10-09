package dev.applewatchandroid.bridge;

/** Keep clipboard IPC comfortably bounded; the complete journal stays on disk. */
final class ClipboardLogText {
    static final int MAX_CHARACTERS = 64 * 1024;

    private ClipboardLogText() {}

    static String tail(String text) {
        if (text.length() <= MAX_CHARACTERS) return text;
        int start = text.length() - MAX_CHARACTERS;
        if (text.charAt(start - 1) != '\n') {
            int newline = text.indexOf('\n', start);
            // A single oversized last line still needs a bounded, nonempty tail.
            if (newline >= 0 && newline < text.length() - 1) start = newline + 1;
        }
        if (Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) start++;
        return text.substring(start);
    }
}
