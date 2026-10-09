package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public final class BridgeCommandCodecTest {
    @Test public void roundTripPreservesExactRequestIdentityEpochAndDeadline() {
        var request = new BridgeCommandCodec.Request(UUID.randomUUID(), UUID.randomUUID(), 60_000, "SEND_BULLETIN:AA==");
        assertEquals(request, BridgeCommandCodec.decodeRequest(BridgeCommandCodec.encode(request)));
        for (var stage : BridgeCommandCodec.Stage.values()) {
            var status = new BridgeCommandCodec.Status(request.id(), request.epoch(), stage);
            assertEquals(status, BridgeCommandCodec.decodeStatus(BridgeCommandCodec.encode(status)));
        }
    }
    @Test public void invalidVersionTypeTruncationAndTrailingBytesAreRejected() {
        byte[] request = BridgeCommandCodec.encode(new BridgeCommandCodec.Request(UUID.randomUUID(), UUID.randomUUID(), 1000, "PING_WATCH"));
        for (int length = 0; length < request.length; length++) {
            byte[] truncated = Arrays.copyOf(request, length);
            assertThrows(IllegalArgumentException.class, () -> BridgeCommandCodec.decodeRequest(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> BridgeCommandCodec.decodeStatus(request));
        assertThrows(IllegalArgumentException.class, () -> BridgeCommandCodec.decodeRequest(Arrays.copyOf(request, request.length+1)));
        request[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> BridgeCommandCodec.decodeRequest(request));
    }
    @Test public void setupAndStdinInjectionCannotEnterOperationalEnvelope() {
        UUID id = UUID.randomUUID(), epoch = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new BridgeCommandCodec.Request(id, epoch, 1000, "FINISH_SETUP"));
        assertThrows(IllegalArgumentException.class, () -> new BridgeCommandCodec.Request(id, epoch, 1000, "PING_WATCH\nSTOP"));
        assertThrows(IllegalArgumentException.class, () -> new BridgeCommandCodec.Request(id, epoch, 0, "PING_WATCH"));
    }
}
