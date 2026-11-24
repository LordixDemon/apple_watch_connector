package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationTextContentTest {
    @Test public void expandedAndInboxStylesRetainTheirContent() {
        assertEquals("Full message", NotificationTextContent.select("Title", null, "Full message", "Summary", null, null, null).body());
        assertEquals("one\ntwo", NotificationTextContent.select("Title", null, null, "Summary", "one\ntwo", null, null).body());
    }
    @Test public void latestConversationMessageIncludesSenderWithoutOldHistory() {
        var content = NotificationTextContent.select(null, "Chat", null, "3 messages", null, "Alex", "Hello 📨");
        assertEquals("Chat", content.title());
        assertEquals("Alex: Hello 📨", content.body());
        assertEquals("Hello", NotificationTextContent.select(null, null, null, null, null, "Alex", "Hello").body());
    }
    @Test public void emptyContentAndFallbacksAreExplicit() {
        assertTrue(NotificationTextContent.select(null, null, null, null, null, null, null).empty());
        assertEquals("Fallback", NotificationTextContent.select(" ", "Fallback", null, "Body", null, null, null).title());
    }
    @Test public void utf8BoundsDoNotSplitEmojiOrNonAsciiText() {
        assertEquals("A😀", NotificationTextContent.limitUtf8("A😀B", 5));
        assertEquals("A", NotificationTextContent.limitUtf8("A😀B", 4));
        var content = NotificationTextContent.select("Я".repeat(4096), null, "😀".repeat(16384), null, null, null, null);
        assertEquals(4096, content.title().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(16384, content.body().getBytes(StandardCharsets.UTF_8).length);
    }
}
