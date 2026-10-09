package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import java.util.Map;
import org.junit.Test;

public final class TimeSyncLinkCodecTest {
    private static byte[] request(long mach) {
        return AppleBinaryPropertyList.encode(Map.of(
                "kTMLSLinkMsgKey", 8,
                "kTMLSLinkRemoteMachKey", mach,
                "kTMLSLinkRTCKey", 1234.5,
                "kTMLSLinkUncertaintyKey", 0.002));
    }

    @Test
    public void completesNativeRtcExchangeBeforeSendingCocoaDeviceTime() {
        long sample = 12_000_000_000L;
        long now = 13_000_000_000L;
        byte[] request = request(sample);
        assertEquals(8, TimeSyncLinkCodec.messageType(request));
        byte[][] replies = TimeSyncLinkCodec.clockConversionResponses(
                request, 978_307_205_000L, now);
        Map<?, ?> rtc = (Map<?, ?>) AppleBinaryPropertyList.decode(replies[0]);
        assertEquals(9, TimeSyncLinkCodec.messageType(replies[0]));
        assertEquals(12.0, (Double) rtc.get("kTMLSLinkRTCKey"), 0);
        assertEquals(1234.5, (Double) rtc.get("kTMLSLinkRemoteRTCKey"), 0);
        assertEquals(0.003, (Double) rtc.get("kTMLSLinkUncertaintyKey"), 1e-12);
        Map<?, ?> time = (Map<?, ?>) AppleBinaryPropertyList.decode(replies[1]);
        assertEquals(1, TimeSyncLinkCodec.messageType(replies[1]));
        assertEquals(5.0, (Double) time.get("kTMLSLinkTimeKey"), 0);
        assertEquals(13.0, (Double) time.get("kTMLSLinkRTCKey"), 0);
        assertEquals("TMLSSourceDevice", time.get("kTMLSLinkSourceKey"));
        assertEquals(false, time.get("kTMLSLinkReliableKey"));
    }

    @Test
    public void rejectsFutureExpiredAndIncompleteConversionSamples() {
        assertThrows(IllegalArgumentException.class, () ->
                TimeSyncLinkCodec.clockConversionResponses(request(20), 1000, 10));
        assertThrows(IllegalArgumentException.class, () ->
                TimeSyncLinkCodec.clockConversionResponses(request(1), 1000, 301_000_000_001L));
        assertThrows(IllegalArgumentException.class, () ->
                TimeSyncLinkCodec.clockConversionResponses(
                        TimeSyncLinkCodec.requestAcknowledgement(), 1000, 10));
        assertEquals(7, TimeSyncLinkCodec.messageType(TimeSyncLinkCodec.requestAcknowledgement()));
        assertEquals(-1, TimeSyncLinkCodec.messageType(new byte[]{1, 2}));
    }
}
