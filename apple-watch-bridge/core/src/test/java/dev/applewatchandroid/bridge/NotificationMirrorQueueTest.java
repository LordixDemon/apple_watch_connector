package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import static org.junit.Assert.*;

public class NotificationMirrorQueueTest {
    private static byte[] fingerprint(int value) { byte[] f = new byte[32]; Arrays.fill(f, (byte) value); return f; }
    private static void post(NotificationMirrorQueue q, String key, int revision) {
        q.upsert(key, "com.chat", fingerprint(revision), i -> new byte[]{8, (byte) revision});
    }
    private static NotificationMirrorQueue restart(NotificationMirrorQueue q) {
        byte[] snapshot = q.encode();
        try { return NotificationMirrorQueue.decode(snapshot); } finally { Arrays.fill(snapshot, (byte) 0); }
    }
    @Test public void offlinePostsSurviveRestartWithoutChangingNativeIdentityOrBytes() {
        var original = new NotificationMirrorQueue(); post(original, "chat-a", 1); post(original, "chat-b", 1);
        var q = restart(original);
        assertEquals(2, q.pending().size());
        for (int n = 0; n < 2; n++) {
            assertEquals(original.entries().get(n).identity(), q.entries().get(n).identity());
            assertEquals(original.entries().get(n).revision(), q.entries().get(n).revision());
            assertArrayEquals(original.entries().get(n).payload(), q.entries().get(n).payload());
        }
        assertNotEquals(q.entries().get(0).identity().publisherId(), q.entries().get(1).identity().publisherId());
        assertTrue(q.acknowledge(q.entries().get(0).revision()));
        assertEquals(1, restart(q).pending().size());
    }
    @Test public void updatesCoalesceButOldReceiptCannotRetireNewText() {
        var q = new NotificationMirrorQueue(); post(q, "a", 1);
        var old = q.entries().get(0);
        assertFalse(q.upsert("a", "com.chat", fingerprint(1), i -> { throw new AssertionError("same revision re-encoded"); }));
        post(q, "a", 2); var next = q.entries().get(0);
        assertEquals(old.identity().publisherId(), next.identity().publisherId());
        assertEquals(old.identity().recordId(), next.identity().recordId());
        assertNotEquals(old.identity().replyToken(), next.identity().replyToken());
        assertNotEquals(old.revision(), next.revision());
        assertFalse(q.acknowledge(old.revision())); assertEquals(1, q.pending().size());
        assertNull(q.matchLights(null, null, old.identity().replyToken()));
        assertEquals(next.identity(), q.matchLights(null, null, next.identity().replyToken()));
    }
    @Test public void removalSurvivesRestartAndReusedAndroidKeyGetsNewRecipientIdentity() {
        var q = new NotificationMirrorQueue(); post(q, "a", 1); var old = q.entries().get(0);
        assertTrue(q.remove("a")); q = restart(q);
        assertNull(q.match(old.identity().publisherId(), null, "com.chat"));
        var remove = q.entries().get(0);
        var nativeRemove = BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(remove.payload());
        assertEquals(old.identity().publisherId(), nativeRemove.publisherBulletinId);
        post(q, "a", 2); assertEquals(2, q.pending().size());
        assertNotEquals(old.identity().publisherId(), q.entries().get(1).identity().publisherId());
        assertFalse(q.acknowledge(old.revision())); assertTrue(q.acknowledge(remove.revision()));
        assertEquals(1, restart(q).entries().size());
    }
    @Test public void acknowledgedActiveIdentityStillAcceptsOnlyItsExactCurrentAction() {
        var q = new NotificationMirrorQueue(); post(q, "a", 1); var i = q.entries().get(0).identity();
        q.acknowledge(q.entries().get(0).revision()); q = restart(q);
        assertTrue(q.pending().isEmpty()); assertEquals(i, q.match(i.publisherId(), i.recordId(), "com.chat"));
        assertNull(q.match(i.publisherId(), "another-record", "com.chat"));
        assertNull(q.match(i.publisherId(), null, "another.app"));
    }
    @Test public void malformedOrTruncatedSnapshotsNeverProducePartialRecipients() {
        var q = new NotificationMirrorQueue(); post(q, "a", 1); byte[] valid = q.encode();
        for (int n = 0; n < valid.length; n++) {
            final byte[] cut = Arrays.copyOf(valid, n);
            assertThrows(IllegalArgumentException.class, () -> NotificationMirrorQueue.decode(cut));
        }
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> NotificationMirrorQueue.decode(trailing));
        valid[8] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> NotificationMirrorQueue.decode(valid));
    }
    @Test public void entryAndByteBoundsRetainPreviousWorkInsteadOfEvictingUnknownReceipts() {
        var q = new NotificationMirrorQueue();
        for (int n = 0; n < NotificationMirrorQueue.MAX_ENTRIES; n++) post(q, "key" + n, 1);
        assertThrows(IllegalStateException.class, () -> post(q, "overflow", 1));
        assertEquals(NotificationMirrorQueue.MAX_ENTRIES, restart(q).pending().size());
        var bytes = new NotificationMirrorQueue();
        int accepted = 0;
        try {
            for (; accepted < 40; accepted++) bytes.upsert("k" + accepted, "com.chat", fingerprint(1), i -> new byte[NotificationMirrorQueue.MAX_PAYLOAD]);
            fail("unbounded byte budget");
        } catch (IllegalArgumentException full) { assertTrue(accepted > 0); }
        assertEquals(accepted, restart(bytes).entries().size());
        assertTrue(bytes.encode().length <= NotificationMirrorQueue.MAX_BYTES);
    }
    @Test public void authenticatedEnvelopeRejectsWrongPairWrongKeyAndTampering() throws Exception {
        var q = new NotificationMirrorQueue(); post(q, "a", 1);
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        var key = generator.generateKey(); UUID pair = UUID.randomUUID();
        byte[] plaintext = q.encode(), sealed = NotificationMirrorEnvelope.seal(plaintext, key, pair);
        assertArrayEquals(plaintext, NotificationMirrorEnvelope.open(sealed, key, pair));
        assertFalse(Arrays.equals(plaintext, sealed));
        assertThrows(Exception.class, () -> NotificationMirrorEnvelope.open(sealed, key, UUID.randomUUID()));
        var another = generator.generateKey();
        assertThrows(Exception.class, () -> NotificationMirrorEnvelope.open(sealed, another, pair));
        sealed[sealed.length - 1] ^= 1;
        assertThrows(Exception.class, () -> NotificationMirrorEnvelope.open(sealed, key, pair));
        assertThrows(IllegalArgumentException.class, () -> NotificationMirrorEnvelope.open(new byte[5], key, pair));
    }
}
