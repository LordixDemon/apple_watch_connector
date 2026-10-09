package dev.applewatchandroid.bridge;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BulletinDistributorCodecTest {

    @Test
    public void testBulletinEncodeDecode() {
        long now = System.currentTimeMillis();
        BulletinDistributorCodec.Bulletin b = new BulletinDistributorCodec.Bulletin(
                "msg-1234",
                "org.telegram.messenger",
                "Telegram",
                "Alice",
                "Chat",
                "Hello from Android to Apple Watch!",
                "pub-1234",
                "rec-1234",
                "reply-token-xyz",
                true,
                2,
                now);

        byte[] encoded = b.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        BulletinDistributorCodec.Bulletin decoded = BulletinDistributorCodec.Bulletin.decode(encoded);
        assertEquals("msg-1234", decoded.bulletinId);
        assertEquals("org.telegram.messenger", decoded.sectionId);
        assertEquals("Telegram", decoded.sectionDisplayName);
        assertEquals("Alice", decoded.title);
        assertEquals("Hello from Android to Apple Watch!", decoded.messageTitle);
        assertEquals("pub-1234", decoded.publisherBulletinId);
        assertEquals("rec-1234", decoded.recordId);
        assertEquals(true, decoded.includesSound);
        assertEquals(2, decoded.soundAlertType);
    }

    @Test
    public void testBulletinRequestWithTrailerPacking() {
        BulletinDistributorCodec.Bulletin b = new BulletinDistributorCodec.Bulletin(
                "bulletin-1",
                "com.apple.MobileSMS",
                "Messages",
                "Bob",
                null,
                "Are you ready?",
                "bulletin-1",
                "bulletin-1",
                null,
                true,
                2,
                System.currentTimeMillis());

        BulletinDistributorCodec.BulletinRequest req =
                new BulletinDistributorCodec.BulletinRequest(b, true, System.currentTimeMillis(), 1);

        byte[] reqBytes = req.encode();
        UUID session = UUID.randomUUID();

        byte[] packed = BulletinDistributorCodec.packWithTrailer(reqBytes, session, 42, true, 1);
        assertTrue(packed.length > reqBytes.length + 18);

        BulletinDistributorCodec.MessageAndTrailer split = BulletinDistributorCodec.splitTrailer(packed);
        assertNotNull(split.messagePayload);
        assertNotNull(split.trailer);

        assertEquals(42, split.trailer.sequence);
        assertEquals(true, split.trailer.flag);
        assertEquals(session, split.trailer.sessionId);
        assertEquals(Integer.valueOf(1), split.trailer.state);

        BulletinDistributorCodec.BulletinRequest decodedReq =
                BulletinDistributorCodec.BulletinRequest.decode(split.messagePayload);
        assertNotNull(decodedReq.bulletin);
        assertEquals("Bob", decodedReq.bulletin.title);
        assertEquals("Are you ready?", decodedReq.bulletin.messageTitle);
        assertTrue(decodedReq.shouldPlayLightsAndSirens);
        assertEquals(1, decodedReq.updateType);
    }

    @Test
    public void testDismissActionRequest() {
        BulletinDistributorCodec.DismissActionRequest dismiss =
                new BulletinDistributorCodec.DismissActionRequest("pub-555", "rec-555", "org.telegram.messenger");

        byte[] encoded = dismiss.encode();
        BulletinDistributorCodec.DismissActionRequest decoded =
                BulletinDistributorCodec.DismissActionRequest.decode(encoded);

        assertEquals("pub-555", decoded.publisherBulletinId);
        assertEquals("rec-555", decoded.recordId);
        assertEquals("org.telegram.messenger", decoded.sectionId);
    }

    @Test
    public void testSupplementaryActionRequestWithReply() {
        BulletinDistributorCodec.SupplementaryActionRequest reply =
                new BulletinDistributorCodec.SupplementaryActionRequest(
                        "reply-action",
                        "pub-888",
                        "rec-888",
                        "com.whatsapp",
                        "Yes, arriving in 5 minutes!");

        byte[] encoded = reply.encode();
        BulletinDistributorCodec.SupplementaryActionRequest decoded =
                BulletinDistributorCodec.SupplementaryActionRequest.decode(encoded);

        assertEquals("reply-action", decoded.identifier);
        assertEquals("pub-888", decoded.publisherBulletinId);
        assertEquals("rec-888", decoded.recordId);
        assertEquals("com.whatsapp", decoded.sectionId);
        assertEquals("Yes, arriving in 5 minutes!", decoded.replyText);
    }
}
