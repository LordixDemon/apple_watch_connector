package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.Assert.*;

public class NotificationActionClaimTest {
    private static void post(NotificationMirrorQueue q, int revision) {
        byte[] fingerprint = new byte[32]; Arrays.fill(fingerprint, (byte) revision);
        q.upsert("chat-a", "com.chat", fingerprint, identity -> new byte[]{8, (byte) revision});
    }
    private static NotificationMirrorQueue restart(NotificationMirrorQueue q) {
        return NotificationMirrorQueue.decode(q.encode());
    }
    @Test public void oldCardCannotExecuteActionAgainstUpdatedAndroidPendingIntent() {
        var q = new NotificationMirrorQueue(); post(q, 1); var old = q.entries().get(0).identity();
        String oldAction = NotificationMirrorQueue.replyIdentifier(old);
        post(q, 2); var current = q.entries().get(0).identity();
        assertEquals(old.publisherId(), current.publisherId());
        assertNotEquals(oldAction, NotificationMirrorQueue.replyIdentifier(current));
        assertFalse(q.claimReply(current.publisherId(), current.recordId(), current.sectionId(), oldAction));
        assertFalse(q.claimReply(current.publisherId(), current.recordId(), current.sectionId(), current.publisherId() + ".reply"));
        assertTrue(q.claimReply(current.publisherId(), current.recordId(), current.sectionId(), NotificationMirrorQueue.replyIdentifier(current)));
    }
    @Test public void replyClaimSurvivesTransportReceiptProcessRestartAndUncertainAndroidOutcome() {
        var q = new NotificationMirrorQueue(); post(q, 1); var entry = q.entries().get(0); var i = entry.identity();
        String action = NotificationMirrorQueue.replyIdentifier(i);
        assertTrue(q.claimReply(i.publisherId(), i.recordId(), i.sectionId(), action));
        assertTrue(q.acknowledge(entry.revision())); q = restart(q);
        // Even if Android effect/receipt is unknown, restart cannot invoke the same PendingIntent twice.
        assertFalse(q.claimReply(i.publisherId(), i.recordId(), i.sectionId(), action));
        assertTrue(q.entries().get(0).replyClaimed());
        assertFalse(q.entries().get(0).pending());
        post(q, 2); var next = q.entries().get(0).identity();
        assertTrue(q.claimReply(next.publisherId(), next.recordId(), next.sectionId(), NotificationMirrorQueue.replyIdentifier(next)));
    }
    @Test public void invalidRecipientDoesNotConsumeValidActionAndDismissReplayStaysClaimedAcrossUpdates() {
        var q = new NotificationMirrorQueue(); post(q, 1); var i = q.entries().get(0).identity();
        String action = NotificationMirrorQueue.replyIdentifier(i);
        assertFalse(q.claimReply(i.publisherId(), "other-record", i.sectionId(), action));
        assertFalse(q.claimReply(i.publisherId(), i.recordId(), "other.app", action));
        assertFalse(q.claimDismiss("unknown", null, i.sectionId()));
        assertTrue(q.claimDismiss(i.publisherId(), i.recordId(), i.sectionId()));
        q = restart(q); post(q, 2);
        assertFalse(q.claimDismiss(i.publisherId(), i.recordId(), i.sectionId()));
        assertTrue(q.remove("chat-a")); post(q, 3); var replacement = q.entries().get(1).identity();
        assertNotEquals(i.publisherId(), replacement.publisherId());
        assertFalse(q.claimReply(i.publisherId(), i.recordId(), i.sectionId(), action));
        assertTrue(q.claimDismiss(replacement.publisherId(), replacement.recordId(), replacement.sectionId()));
    }
    @Test public void fixedVersionOneFixtureMigratesWithoutLosingPendingWorkOrIds() {
        // Frozen pre-345 journal format; not emitted by the current serializer.
        byte[] v1 = HexFormat.of().parseHex("41574e5101000000010001610008636f6d2e63686174000350554200035245430005544f4b454e"
                + "00000000000000000000000000000001"
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + "0001000000020801");
        var q = NotificationMirrorQueue.decode(v1); var e = q.entries().get(0);
        assertEquals("a", e.identity().androidKey()); assertEquals("PUB", e.identity().publisherId());
        assertEquals(new UUID(0, 1), e.revision()); assertTrue(e.pending());
        assertFalse(e.replyClaimed()); assertFalse(e.dismissClaimed());
        assertArrayEquals(new byte[]{8, 1}, e.payload());
        byte[] migrated = q.encode(); assertEquals(2, migrated[4]);
        assertEquals(e.identity(), NotificationMirrorQueue.decode(migrated).entries().get(0).identity());
        migrated[4] = 3;
        assertThrows(IllegalArgumentException.class, () -> NotificationMirrorQueue.decode(migrated));
    }
    @Test public void nativeSupplementaryReplyIdentifierTravelsIntactAndIsClaimedOnlyOnce() {
        var q = new NotificationMirrorQueue(); post(q, 1); var i = q.entries().get(0).identity();
        String identifier = NotificationMirrorQueue.replyIdentifier(i); assertTrue(identifier.length() <= 128);
        byte[] core = new BulletinDistributorCodec.SupplementaryActionRequest(identifier, i.publisherId(), i.recordId(), i.sectionId(), "тест").encode();
        byte[] wire = BulletinDistributorCodec.packWithTrailer(core, UUID.randomUUID(), 4, false, 0);
        var decoded = BulletinActionDecoder.decode(7, false, wire);
        assertEquals(identifier, decoded.identifier()); assertEquals("тест", decoded.replyText());
        assertTrue(q.claimReply(decoded.publisherId(), decoded.recordId(), decoded.sectionId(), decoded.identifier()));
        assertFalse(restart(q).claimReply(decoded.publisherId(), decoded.recordId(), decoded.sectionId(), decoded.identifier()));
    }
}
