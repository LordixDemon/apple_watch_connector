package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ClipboardLogTextTest {
    @Test public void preservesSmallLogsAndEmptyText() {
        assertEquals("", ClipboardLogText.tail(""));
        assertEquals("first\nlast\n", ClipboardLogText.tail("first\nlast\n"));
    }

    @Test public void keepsLatestCompleteLinesFromMultiMegabyteJournal() {
        String journal = "old log entry\n".repeat(400000) + "latest entry\n";
        String result = ClipboardLogText.tail(journal);
        assertTrue(result.length() <= ClipboardLogText.MAX_CHARACTERS);
        assertTrue(result.startsWith("old log entry\n"));
        assertTrue(result.endsWith("latest entry\n"));
    }

    @Test public void preservesLineAtExactBoundary() {
        String line = "x".repeat(ClipboardLogText.MAX_CHARACTERS - 1) + "\n";
        assertEquals(line, ClipboardLogText.tail("older\n" + line));
    }

    @Test public void boundsOneOversizedLineIncludingItsFinalNewline() {
        String line = "x".repeat(ClipboardLogText.MAX_CHARACTERS * 2) + "\n";
        String result = ClipboardLogText.tail(line);
        assertEquals(ClipboardLogText.MAX_CHARACTERS, result.length());
        assertTrue(result.endsWith("\n"));
    }

    @Test public void neverSplitsSurrogatePairAtStart() {
        String journal = "old" + "\ud83d\ude00" + "x".repeat(ClipboardLogText.MAX_CHARACTERS - 1);
        String result = ClipboardLogText.tail(journal);
        assertEquals("x".repeat(ClipboardLogText.MAX_CHARACTERS - 1), result);
    }
}
