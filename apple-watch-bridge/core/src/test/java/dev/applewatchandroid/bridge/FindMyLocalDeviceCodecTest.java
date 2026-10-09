package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class FindMyLocalDeviceCodecTest {
    // Native field 1 fixed64 double Unix 1700000000.25, field 2 int32 behavior 1.
    private static final byte[] REQUEST = {9, 0, 0, 16, 64, (byte) 252, 84, (byte) 217, 65, 16, 1};

    @Test public void nativeUnixTimestampFixtureAndBehaviorAreExact() {
        var request = FindMyLocalDeviceCodec.PlaySoundRequest.decode(1, REQUEST);
        assertEquals(1700000000.25, request.unixSeconds(), 0);
        assertEquals(Integer.valueOf(1), request.behaviorOverride());
        assertArrayEquals(REQUEST, request.encode());
        assertTrue(request.freshAt(1700000000250L));
        assertTrue(request.freshAt(1700000030249L));
        assertFalse(request.freshAt(1700000030250L));
        assertFalse(request.freshAt(1699999990000L));
        var defaultRequest = FindMyLocalDeviceCodec.PlaySoundRequest.decode(2, Arrays.copyOf(REQUEST, 9));
        assertNull(defaultRequest.behaviorOverride());
    }

    @Test public void didPlayFalseIsFalseAndEmptyIsNotSuccess() {
        assertFalse(FindMyLocalDeviceCodec.PlaySoundResponse.decode(new byte[]{8, 0}).didPlay());
        assertTrue(FindMyLocalDeviceCodec.PlaySoundResponse.decode(new byte[]{8, 1}).didPlay());
        for (byte[] malformed : new byte[][]{new byte[0], {8}, {8, 2}, {8, 1, 8, 0},
                {10, 1, 0}, {8, 1, 18, 3, 0}, {0}, {8, (byte) 128}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FindMyLocalDeviceCodec.PlaySoundResponse.decode(malformed));
        }
    }

    @Test public void malformedRequestsAreRejectedWithoutPartialDefaults() {
        for (byte[] malformed : new byte[][]{new byte[0], {10, 1, 0}, Arrays.copyOf(REQUEST, 8),
                {9, 0, 0, 0, 0, 0, 0, 0, 0}, // zero timestamp
                {9, 0, 0, 0, 0, 0, 0, (byte) 248, 127}, // NaN
                {9, 0, 0, 0, 0, 0, 0, (byte) 240, 127}, // infinity
                {9, 0, 0, 16, 64, (byte) 252, 84, (byte) 217, 65, 16, 5},
                {9, 0, 0, 16, 64, (byte) 252, 84, (byte) 217, 65, 16, 1, 16, 0}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FindMyLocalDeviceCodec.PlaySoundRequest.decode(1, malformed));
        }
        assertThrows(IllegalArgumentException.class,
                () -> FindMyLocalDeviceCodec.PlaySoundRequest.decode(3, REQUEST));
        assertThrows(IllegalArgumentException.class,
                () -> FindMyLocalDeviceCodec.PlaySoundResponse.decode(new byte[513]));
    }

    @Test public void unknownFieldsAreSkippedOnlyWhenCompleteAndBounded() {
        assertTrue(FindMyLocalDeviceCodec.PlaySoundResponse.decode(
                new byte[]{8, 1, 26, 2, 0, 0, 37, 0, 0, 0, 0}).didPlay());
        byte[] tooMany = new byte[66];
        for (int i = 0; i < 66; i += 2) { tooMany[i] = 24; tooMany[i + 1] = 0; }
        assertThrows(IllegalArgumentException.class,
                () -> FindMyLocalDeviceCodec.PlaySoundResponse.decode(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> FindMyLocalDeviceCodec.PlaySoundResponse.decode(new byte[]{
                        8, 1, 24, (byte) 128, (byte) 128, (byte) 128, (byte) 128, (byte) 128,
                        (byte) 128, (byte) 128, (byte) 128, (byte) 128, 2}));
    }
}
