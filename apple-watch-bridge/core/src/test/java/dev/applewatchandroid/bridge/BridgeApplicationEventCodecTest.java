package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class BridgeApplicationEventCodecTest {
    @Test public void payloadAndRoutingSurviveTheProcessBoundaryWithoutAliasing() {
        byte[] payload = "Ответ с часов\n:second line".getBytes(StandardCharsets.UTF_8);
        byte[] expected = payload.clone();
        try (var source = new BridgeApplicationEventCodec.Event("com.apple.private.alloy.health.sync.classc", 11, true, payload)) {
            Arrays.fill(payload, (byte) 0);
            try (var decoded = BridgeApplicationEventCodec.decode(BridgeApplicationEventCodec.encode(source))) {
                assertEquals(source.topic, decoded.topic);
                assertEquals(11, decoded.protobufType);
                assertTrue(decoded.response);
                assertArrayEquals(expected, decoded.payload());
                byte[] detached = decoded.payload();
                Arrays.fill(detached, (byte) 0);
                assertArrayEquals(expected, decoded.payload());
            }
        }
    }
    @Test public void everyTruncationAndTrailingDataAreRejected() {
        try (var event = new BridgeApplicationEventCodec.Event("test.topic", 7, false, new byte[]{1, 2, 3})) {
            byte[] full = BridgeApplicationEventCodec.encode(event);
            for (int end = 0; end < full.length; end++) {
                byte[] truncated = Arrays.copyOf(full, end);
                assertThrows(IllegalArgumentException.class, () -> BridgeApplicationEventCodec.decode(truncated));
            }
            byte[] trailing = Arrays.copyOf(full, full.length + 1);
            assertThrows(IllegalArgumentException.class, () -> BridgeApplicationEventCodec.decode(trailing));
            full[4] = 2;
            assertThrows(IllegalArgumentException.class, () -> BridgeApplicationEventCodec.decode(full));
        }
    }
    @Test public void limitsAndClosedLifetimeAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeApplicationEventCodec.Event("bad\ntopic", 1, false, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new BridgeApplicationEventCodec.Event("topic", -1, false, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new BridgeApplicationEventCodec.Event("topic", 1, false,
                new byte[BridgeApplicationEventCodec.MAX_PAYLOAD + 1]));
        var event = new BridgeApplicationEventCodec.Event("topic", 1, false, new byte[0]);
        event.close();
        assertThrows(IllegalStateException.class, event::payload);
        assertThrows(IllegalStateException.class, () -> BridgeApplicationEventCodec.encode(event));
    }
}
