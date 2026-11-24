package dev.applewatchandroid.bridge;

/** Bounded content selection shared by Android notification styles. No logging. */
record NotificationTextContent(String title, String body, String subtitle) {
    NotificationTextContent(String title, String body) {
        this(title, body, null);
    }

    static NotificationTextContent select(String title, String conversation, String bigText,
            String text, String lines, String sender, String message) {
        return select(title, null, conversation, bigText, text, lines, sender, message);
    }

    static NotificationTextContent select(String title, String subtitle, String conversation, String bigText,
            String text, String lines, String sender, String message) {
        String heading = conversation != null && !conversation.isBlank()
                ? conversation : first(title, sender);
        String body = first(bigText, lines, text);
        if (message != null && !message.isBlank()) {
            boolean includeSender = conversation != null && !conversation.isBlank()
                    && sender != null && !sender.isBlank()
                    && !sender.trim().equalsIgnoreCase(heading.trim());
            body = includeSender ? sender + ": " + message : message;
        }
        String boundedTitle = limitUtf8(heading, 4096);
        String boundedSub = limitUtf8(subtitle, 1024);
        if (boundedSub.isEmpty() || boundedSub.equalsIgnoreCase(boundedTitle)) boundedSub = null;
        return new NotificationTextContent(boundedTitle, limitUtf8(body, 16384), boundedSub);
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
