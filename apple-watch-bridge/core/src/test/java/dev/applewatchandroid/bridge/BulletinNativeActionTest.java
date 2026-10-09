package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.Assert.*;

public class BulletinNativeActionTest {
    @Test public void removalUsesNativeFieldsAndRejectsPartialOrUnboundedIdentity() {
        byte[] fixture = hex("0a055055422d4112055245432d411a08636f6d2e63686174");
        var remove = BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(fixture);
        assertEquals("PUB-A", remove.publisherBulletinId);
        assertEquals("REC-A", remove.recordId);
        assertEquals("com.chat", remove.sectionId);
        assertArrayEquals(fixture, remove.encode());
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(hex("1a08636f6d2e63686174")));
        var tooLong = new BulletinDistributorCodec.RemoveBulletinRequest("A".repeat(129), "REC-A", "com.chat");
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(tooLong.encode()));
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(hex("0aff")));
    }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static byte[] pack(byte[] value) {
        return BulletinDistributorCodec.packWithTrailer(value, UUID.fromString("00000000-0000-0000-0000-000000000001"), 3, false, 2);
    }

    @Test public void nativeLightsFieldsHaveBooleanAndPublisherMatchId() {
        // PB fields1 bool,2 publisherMatchID,3 phoneSectionID,4 double,5 replyToken.
        byte[] payload = hex("080112055055422d411a08636f6d2e6368617421000000000000f03f2a05544f4b2d41");
        var value = BulletinDistributorCodec.DidPlayLightsAndSirens.decode(payload);
        assertEquals(Boolean.TRUE, value.didPlay);
        assertEquals("PUB-A", value.publisherMatchId);
        assertEquals("com.chat", value.sectionId);
        assertEquals(1.0, value.dateSeconds, 0);
        assertEquals("TOK-A", value.replyToken);
        assertEquals(9, BulletinDistributorCodec.TYPE_DID_PLAY_LIGHTS_AND_SIRENS);
        assertEquals(14, BulletinDistributorCodec.TYPE_SET_SECTION_SUBTYPE_ICON);
        assertNull(BulletinActionDecoder.decode(14, false, pack(payload)));
        assertNull(BulletinActionDecoder.decode(9, true, pack(payload)));
        payload[1] = 0;
        assertEquals(Boolean.FALSE, BulletinActionDecoder.decode(9, false, pack(payload)).played());
        assertNull(BulletinDistributorCodec.DidPlayLightsAndSirens.decode(new byte[0]).didPlay);
    }

    @Test public void plistlibFixtureDecodesUnicodeReplyThroughNativeActionInfoField4() {
        // Independent Python plistlib fixture, not produced by the codec under test.
        byte[] payload = hex("0a057265706c7912055055422d411a055245432d412208636f6d2e636861742a8b0122880162706c6973743030d101025f101075736572526573706f6e7365496e666fd103045f102c5549557365724e6f74696669636174696f6e416374696f6e526573706f6e73655479706564546578744b657969041f044004380432043504420020d83ddc4b080b1e21500000000000000101000000000000000500000000000000000000000000000063");
        var action = BulletinActionDecoder.decode(7, false, pack(payload));
        assertEquals("Привет 👋", action.replyText());
        assertEquals("PUB-A", action.publisherId());
        assertEquals("REC-A", action.recordId());
        assertEquals("com.chat", action.sectionId());
        assertEquals("reply", action.identifier());
    }

    @Test public void fakeLegacyTextAndRawUtf8AreNeverReplies() {
        byte[] fake = hex("0a057265706c7912055055422d411a055245432d412208636f6d2e636861742a070a0568656c6c6f");
        assertNull(BulletinDistributorCodec.SupplementaryActionRequest.decode(fake).replyText);
        assertNull(BulletinActionDecoder.decode(7, false, pack(fake)));
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.SupplementaryActionRequest.decode(hex("2a0568656c6c6f")));
    }

    @Test public void malformedWireSizesTagsAndDuplicateFlagsFailClosed() {
        String[] invalid = {"00", "0802", "08010800", "0a0161", "120450", "21ff", "1201ff", "80", "0899999999999999999999", "1dff"};
        for (String value : invalid) assertThrows(value, IllegalArgumentException.class,
                () -> BulletinDistributorCodec.DidPlayLightsAndSirens.decode(hex(value)));
    }

    @Test public void missingOrDamagedTrailerCannotExecuteActions() {
        byte[] payload = hex("0a055055422d4112055245432d411a08636f6d2e63686174");
        assertThrows(IllegalArgumentException.class, () -> BulletinActionDecoder.decode(8, false, payload));
        byte[] packed = pack(payload);
        var action = BulletinActionDecoder.decode(8, false, packed);
        assertEquals(BulletinActionDecoder.Kind.DISMISS, action.kind());
        byte[] copy = packed.clone();
        BulletinActionDecoder.decode(8, false, packed);
        assertArrayEquals(copy, packed); // Ownership stays with IPC caller.
        packed[packed.length - 1] = 0x7f;
        assertThrows(IllegalArgumentException.class, () -> BulletinActionDecoder.decode(8, false, packed));
        assertThrows(IllegalArgumentException.class, () -> BulletinDistributorCodec.Trailer.decode(hex("0801220101")));
    }
}
