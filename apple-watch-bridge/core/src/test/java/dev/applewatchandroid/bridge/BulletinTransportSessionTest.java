package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class BulletinTransportSessionTest {
    private static byte[] peer(byte[] core, UUID id, int sequence, Integer state) {
        return BulletinDistributorCodec.packWithTrailer(core, id, sequence, state != null && state == 1, state);
    }
    @Test public void twoEndpointsCompleteNativeOneTwoZeroHandshake() {
        var phone = new BulletinTransportSession();
        var watch = new BulletinTransportSession();
        var first = watch.receive(1, false, phone.wrap(new byte[]{8, 1}));
        assertTrue(first.fresh());
        var phoneAck = phone.receive(12, false, watch.wrap(first.acknowledgement()));
        assertEquals(2, phone.state());
        var watchAck = watch.receive(12, false, phone.wrap(phoneAck.acknowledgement()));
        assertEquals(2, watch.state());
        var terminal = phone.receive(12, false, watch.wrap(watchAck.acknowledgement()));
        assertEquals(0, phone.state());
        byte[] lastAck = phone.wrap(terminal.acknowledgement());
        var split = BulletinDistributorCodec.splitTrailer(lastAck);
        assertNull(split.trailer.state);
        assertFalse(split.trailer.flag);
        var finalWatch = watch.receive(12, false, lastAck);
        assertEquals(0, watch.state());
        assertNull(finalWatch.acknowledgement());
        assertNull(watch.receive(1, false, phone.wrap(new byte[]{8, 1})).acknowledgement());
    }
    @Test public void wrongAckUuidDoesNotCompleteHandshakeAndAssertRestartsIt() {
        var session = new BulletinTransportSession();
        UUID watch = UUID.randomUUID();
        var wrong = new BulletinDistributorCodec.InitialSequenceAck(null, UUID.randomUUID(), 2).encode();
        session.receive(12, false, peer(wrong, watch, 1, null));
        assertEquals(1, session.state());
        var correct = new BulletinDistributorCodec.InitialSequenceAck(null, session.localId(), 2).encode();
        session.receive(12, false, peer(correct, watch, 2, null));
        assertEquals(0, session.state());
        var asserted = new BulletinDistributorCodec.InitialSequenceAck(true, null, null).encode();
        session.receive(12, false, peer(asserted, watch, 3, null));
        assertEquals(1, session.state());
    }
    @Test public void duplicatesAcrossTypesAndOutOfOrderSequencesCannotExecuteTwice() {
        var session = new BulletinTransportSession();
        UUID watch = UUID.randomUUID();
        byte[] a = peer(new byte[]{8, 1}, watch, 10, null);
        assertTrue(session.receive(7, false, a).fresh());
        assertFalse(session.receive(8, false, a).fresh());
        assertTrue(session.receive(7, false, peer(new byte[]{8, 1}, watch, 8, null)).fresh());
        assertFalse(session.receive(7, false, peer(new byte[]{8, 1}, watch, 8, null)).fresh());
        for (int n = 11; n < 280; n++) assertTrue(session.receive(7, false, peer(new byte[]{8, 1}, watch, n, null)).fresh());
        assertFalse(session.receive(7, false, a).fresh());
        UUID replacement = UUID.randomUUID();
        assertTrue(session.receive(7, false, peer(new byte[]{8, 1}, replacement, 1, 1)).fresh());
        assertFalse(session.receive(7, false, peer(new byte[]{8, 1}, watch, 500, 1)).fresh());
    }
    @Test public void resetChangesLocalSessionAndOldAckCannotAdvanceNewSession() {
        var session = new BulletinTransportSession();
        UUID previous = session.localId();
        session.reset();
        assertNotEquals(previous, session.localId());
        byte[] oldAck = new BulletinDistributorCodec.InitialSequenceAck(null, previous, 2).encode();
        session.receive(12, false, peer(oldAck, UUID.randomUUID(), 1, 1));
        assertEquals(1, session.state());
        var trailer = BulletinDistributorCodec.splitTrailer(session.wrap(new byte[]{8, 1})).trailer;
        assertEquals(1, trailer.sequence);
        assertEquals(session.localId(), trailer.sessionId);
    }
    @Test public void ackSchemaAndMalformedFieldsDoNotMutateState() {
        byte[] id = java.util.HexFormat.of().parseHex("08011210000000000000000000000000000000011802");
        var ack = BulletinDistributorCodec.InitialSequenceAck.decode(id);
        assertEquals(Boolean.TRUE, ack.assertion());
        assertEquals(new UUID(0, 1), ack.sessionId());
        assertEquals(Integer.valueOf(2), ack.state());
        assertArrayEquals(id, ack.encode());
        var session = new BulletinTransportSession();
        UUID watch = UUID.randomUUID();
        for (String invalid : new String[]{"0802", "1803", "120100", "08010800", "1a0101", "80"}) {
            byte[] body = java.util.HexFormat.of().parseHex(invalid);
            assertThrows(IllegalArgumentException.class, () -> session.receive(12, false, peer(body, watch, 1, 1)));
            assertEquals(1, session.state());
        }
        assertThrows(IllegalArgumentException.class, () -> session.receive(1, false, peer(new byte[]{8, 1}, watch, 1, 3)));
        assertTrue(session.receive(1, false, peer(new byte[]{8, 1}, watch, 1, 1)).fresh());
    }
    @Test public void nativeTextInputActionContainsMandatoryAppearanceAndBehavior() {
        byte[] bytes = new BulletinDistributorCodec.TextInputAction("reply", "Reply").encode();
        String hex = java.util.HexFormat.of().formatHex(bytes);
        assertTrue(hex.startsWith("0a057265706c7912070a055265706c791801280132"));
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("bplist00"));
        assertThrows(IllegalArgumentException.class, () -> new BulletinDistributorCodec.TextInputAction("", "Reply").encode());
    }
}
