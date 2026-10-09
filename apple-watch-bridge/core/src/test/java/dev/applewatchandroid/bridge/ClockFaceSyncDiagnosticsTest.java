package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.Test;

public final class ClockFaceSyncDiagnosticsTest {
    @Test
    public void summaryNamesKeysWithoutStringValues() {
        LinkedHashMap<String, Object> face = new LinkedHashMap<>();
        face.put("bundleId", "com.apple.private.face");
        face.put("edited", Boolean.TRUE);
        byte[] encoded = BinaryPropertyListCodec.encodeDictionary(face);
        String summary = ClockFaceSyncDiagnostics.summarize(encoded);
        assertTrue(summary.contains("bundleId=string("));
        assertTrue(summary.contains("edited=bool"));
        assertFalse(summary.contains("com.apple.private.face"));
    }

    @Test
    public void liveFrameIsASyncOfferNotAFaceArchive() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of(
                "src/test/resources/clockface-sync.bin"));
        String summary = ClockFaceSyncDiagnostics.summarize(payload);
        assertTrue(summary.contains("type=0x66"));
        assertTrue(summary.contains("uuids=3"));
        assertTrue(summary.contains("key=senderSyncVersion"));
        assertTrue(summary.contains("plistBytes="));
        assertFalse(summary.contains("74CE40C0"));
        byte[] accept = ClockFaceSyncAccept.accept(payload);
        assertTrue(accept != null);
        assertEquals(0x66, accept[0] & 0xff);
        assertEquals(0, accept[1]);
        assertEquals(0x0a, accept[2] & 0xff);
        assertTrue(indexOf(accept, "W1970-01-01T04:40:01.776".getBytes()) > 0);
        assertTrue(indexOf(accept, new byte[] {0x18, 0x01}) > 0);
        assertEquals(null, ClockFaceSyncAccept.accept(new byte[] {0x69, 0, 0, 0x0a}));
        byte[] batch = new byte[] {
                0x67, 0x00, 0x00,
                0x0a, 0x02, 0x01, 0x02,
                0x12, 0x01, 0x41,
                0x18, 0x05
        };
        byte[] batchReply = ClockFaceSyncAccept.reply(batch);
        assertTrue(batchReply != null);
        assertEquals(0x67, batchReply[0] & 0xff);
        assertEquals(0, batchReply[1]);
        assertEquals(0x18, batchReply[batchReply.length - 2] & 0xff);
        assertEquals(0x05, batchReply[batchReply.length - 1] & 0xff);
        assertEquals(0L, ErtmRetransmitPace.delayMs(4));
        assertEquals(5_000L, ErtmRetransmitPace.delayMs(5));
        assertEquals(30_000L, ErtmRetransmitPace.delayMs(13));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index + needle.length <= haystack.length; index++) {
            for (int match = 0; match < needle.length; match++) {
                if (haystack[index + match] != needle[match]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
