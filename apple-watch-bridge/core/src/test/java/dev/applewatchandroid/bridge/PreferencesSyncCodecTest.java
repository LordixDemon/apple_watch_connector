package dev.applewatchandroid.bridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class PreferencesSyncCodecTest {

    @Test
    public void testUserDefaultsMessageEncodeDecode() {
        List<PreferencesSyncCodec.PreferenceKey> keys = new ArrayList<>();
        keys.add(new PreferencesSyncCodec.PreferenceKey("wornOnRightArm", BinaryPropertyListCodec.encodeBoolean(true), null, true));
        keys.add(new PreferencesSyncCodec.PreferenceKey("invertUI", BinaryPropertyListCodec.encodeBoolean(false), null, true));

        PreferencesSyncCodec.UserDefaultsMessage msg =
                new PreferencesSyncCodec.UserDefaultsMessage("com.apple.nano", 1700000000000L, keys);

        byte[] encoded = msg.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        PreferencesSyncCodec.UserDefaultsMessage decoded =
                PreferencesSyncCodec.UserDefaultsMessage.decode(encoded);

        assertEquals("com.apple.nano", decoded.domain);
        assertEquals(1700000000000L, decoded.timestampMs);
        assertEquals(2, decoded.keys.size());
        assertEquals("wornOnRightArm", decoded.keys.get(0).name);
        assertTrue(BinaryPropertyListCodec.decodeBoolean(decoded.keys.get(0).valueBytes));
        assertEquals("invertUI", decoded.keys.get(1).name);
        assertFalse(BinaryPropertyListCodec.decodeBoolean(decoded.keys.get(1).valueBytes));
    }

    @Test
    public void testActiveFaceSyncHelper() {
        assertThrows(UnsupportedOperationException.class,
                () -> PreferencesSyncCodec.createActiveFaceSync("wayfinder"));
    }

    @Test
    public void testWristOrientationSyncHelper() {
        assertThrows(UnsupportedOperationException.class,
                () -> PreferencesSyncCodec.createWristOrientationSync(false, true));
        var message = new PreferencesSyncCodec.UserDefaultsMessage("com.apple.nano", 1700000000000L,
                List.of(new PreferencesSyncCodec.PreferenceKey("wornOnRightArm", null, "right", true)));
        assertThrows(IllegalArgumentException.class, message::encode);
        assertThrows(IllegalArgumentException.class,
                () -> PreferencesSyncCodec.UserDefaultsMessage.decode(new byte[0]));
    }
}
