package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NanoRegistryReachabilityProbeTest {
    private static final String REQUEST = "00112233-4455-4677-8899-aabbccddeeff";
    private static final String D = "idstest/localdelivery/UTunDelivery-Default-Urgent-D";
    private static final String C = "idstest/localdelivery/UTunDelivery-Default-Urgent-C";

    private static NanoRegistryReachabilityProbe queued() {
        NanoRegistryReachabilityProbe probe = new NanoRegistryReachabilityProbe();
        assertTrue(probe.start());
        probe.onQueued(42, REQUEST);
        return probe;
    }

    @Test
    public void probeStartsOnceAndUsesTheAlwaysAvailablePingType() {
        NanoRegistryReachabilityProbe probe = new NanoRegistryReachabilityProbe();
        assertEquals(NanoRegistryReachabilityProbe.Status.NOT_STARTED, probe.status());
        assertTrue(probe.start());
        assertFalse(probe.start());
        NanoRegistryClassDCodec.PingRequest request = probe.request();
        try {
            assertEquals(5, request.protobufType());
            assertFalse(request.response());
            assertEquals(300, request.responseIdsPriority);
            assertEquals(8.0, request.timeout, 0);
        } finally { request.destroy(); }
    }

    @Test
    public void deliveryAcknowledgementRequiresSequenceAndClassDAndIsNotAReply() {
        NanoRegistryReachabilityProbe probe = queued();
        assertFalse(probe.onAcknowledgement(D, 41));
        assertFalse(probe.onAcknowledgement(C, 42));
        assertTrue(probe.onAcknowledgement(D, 42));
        assertEquals(NanoRegistryReachabilityProbe.Status.DELIVERED, probe.status());
        assertFalse(probe.onAcknowledgement(D, 42));
    }

    @Test
    public void onlyTheMatchingDaemonResponseConfirmsReachability() {
        NanoRegistryReachabilityProbe probe = queued();
        NanoRegistryClassDCodec.PingResponse reply =
                new NanoRegistryClassDCodec.PingResponse(800_000_000.0, new byte[]{3, 8, 1});
        byte[] bytes = NanoRegistryClassDCodec.encode(reply);
        try {
            assertFalse(probe.onResponse("com.apple.private.alloy.idscredentials", 5, true, REQUEST, bytes));
            assertFalse(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 3, true, REQUEST, bytes));
            assertFalse(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 5, false, REQUEST, bytes));
            assertFalse(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 5, true,
                    "11112233-4455-4677-8899-aabbccddeeff", bytes));
            assertEquals(NanoRegistryReachabilityProbe.Status.QUEUED, probe.status());
            assertTrue(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 5, true,
                    REQUEST.toUpperCase(java.util.Locale.ROOT), bytes));
            assertEquals(NanoRegistryReachabilityProbe.Status.REPLIED, probe.status());
            assertFalse(probe.onAcknowledgement(D, 42));
            assertFalse(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 5, true, REQUEST, bytes));
        } finally { reply.destroy(); java.util.Arrays.fill(bytes, (byte) 0); }
    }

    @Test
    public void clientReceiptRequiresTopicProtectionAndCorrelationAndNeverPretendsToBePingReply() {
        NanoRegistryReachabilityProbe probe = queued();
        assertFalse(probe.onClientAcknowledgement(C, NanoRegistryClassDCodec.SERVICE, REQUEST));
        assertFalse(probe.onClientAcknowledgement(D, "com.apple.private.alloy.idscredentials", REQUEST));
        assertFalse(probe.onClientAcknowledgement(D, NanoRegistryClassDCodec.SERVICE,
                "11112233-4455-4677-8899-aabbccddeeff"));
        assertTrue(probe.onClientAcknowledgement(D, NanoRegistryClassDCodec.SERVICE, REQUEST));
        assertEquals(NanoRegistryReachabilityProbe.Status.CLIENT_ACKNOWLEDGED, probe.status());
        assertFalse(probe.onAcknowledgement(D, 42));
        var reply = new NanoRegistryClassDCodec.PingResponse(800_000_000.0, null);
        byte[] bytes = NanoRegistryClassDCodec.encode(reply);
        try {
            assertTrue(probe.onResponse(NanoRegistryClassDCodec.SERVICE, 5, true, REQUEST, bytes));
            assertEquals(NanoRegistryReachabilityProbe.Status.REPLIED, probe.status());
        } finally { reply.destroy(); java.util.Arrays.fill(bytes, (byte)0); }
    }
}
