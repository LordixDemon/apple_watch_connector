package dev.applewatchandroid.bridge;

/** Bounded content selection shared by Android notification styles. No logging. */
record NotificationTextContent(String title, String body) {
    static NotificationTextContent select(String title, String conversation, String bigText,
            String text, String lines, String sender, String message) {
        String heading = first(title, conversation, sender);
        String body = first(bigText, lines, text);
        if (message != null && !message.isBlank()) {
            body = conversation != null && !conversation.isBlank() && sender != null && !sender.isBlank()
                    ? sender + ": " + message : message;
        }
        return new NotificationTextContent(limitUtf8(heading, 4096), limitUtf8(body, 16384));
    }

    boolean empty() { return title.isEmpty() && body.isEmpty(); }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    static String limitUtf8(String value, int maximumBytes) {
        if (value == null) return "";
        value = value.trim();
        int bytes = 0, end = 0;
        while (end < value.length()) {
            int point = value.codePointAt(end);
            int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (bytes + size > maximumBytes) break;
            bytes += size;
            end += Character.charCount(point);
        }
        return value.substring(0, end);
    }
}
