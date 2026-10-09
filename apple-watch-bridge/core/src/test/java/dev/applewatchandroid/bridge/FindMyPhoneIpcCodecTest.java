package dev.applewatchandroid.bridge;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FindMyPhoneIpcCodecTest {
    private final UUID epoch = UUID.randomUUID();
    private final String id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    @Test public void preservesNativeBodyDirectionAndOriginalIdentifier() {
        for (Integer behavior : new Integer[]{null, 0, 1, 2, 3, 4}) {
            var request = new FindMyPhoneIpcCodec.Request(epoch, id, 1,
                    new FindMyLocalDeviceCodec.PlaySoundRequest(1791250000.125, behavior), 10001);
            byte[] frame = FindMyPhoneIpcCodec.encode(request);
            assertEquals(75, frame.length);
            assertEquals(request, FindMyPhoneIpcCodec.decodeRequest(frame));
            assertEquals(behavior == null ? 0 : behavior.intValue(), request.behavior());
            assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeResult(frame));
        }
        var flash = new FindMyPhoneIpcCodec.Request(epoch, id, 2,
                new FindMyLocalDeviceCodec.PlaySoundRequest(1791250000, null), 10001);
        assertEquals(1, FindMyPhoneIpcCodec.decodeRequest(FindMyPhoneIpcCodec.encode(flash)).behavior());
        for (boolean played : new boolean[]{false, true}) {
            var result = new FindMyPhoneIpcCodec.Result(epoch, id, 2, played);
            byte[] frame = FindMyPhoneIpcCodec.encode(result);
            assertEquals(59, frame.length);
            assertEquals(result, FindMyPhoneIpcCodec.decodeResult(frame));
            assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeRequest(frame));
        }
    }
    @Test public void rejectsTruncationOverflowNoncanonicalIdAndIllegalBoolean() {
        var request = new FindMyPhoneIpcCodec.Request(epoch, id, 1,
                new FindMyLocalDeviceCodec.PlaySoundRequest(1791250000, null), 10001);
        byte[] frame = FindMyPhoneIpcCodec.encode(request);
        for (int n = 0; n < frame.length; n++) {
            byte[] shortFrame = Arrays.copyOf(frame, n);
            assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeRequest(shortFrame));
        }
        assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeRequest(Arrays.copyOf(frame, 76)));
        assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeRequest(new byte[81]));
        frame[22] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeRequest(frame));
        byte[] result = FindMyPhoneIpcCodec.encode(new FindMyPhoneIpcCodec.Result(epoch, id, 1, true));
        result[58] = 2;
        assertThrows(IllegalArgumentException.class, () -> FindMyPhoneIpcCodec.decodeResult(result));
        assertThrows(IllegalArgumentException.class, () -> new FindMyPhoneIpcCodec.Result(epoch, "1-2-3-4-5", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new FindMyPhoneIpcCodec.Request(epoch, id, 2,
                new FindMyLocalDeviceCodec.PlaySoundRequest(1791250000, 0), 10001));
    }
}
