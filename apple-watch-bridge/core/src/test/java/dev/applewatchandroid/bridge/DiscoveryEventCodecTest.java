package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.AuthStyle;
import dev.applewatchandroid.bridge.spi.DiscoveredWatch;
import dev.applewatchandroid.bridge.spi.DiscoveryEventCodec;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import org.junit.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

public final class DiscoveryEventCodecTest {

    @Test
    public void roundTripV2ForStrategy4AndLegacyStrategies() {
        String token4 = UUID.randomUUID().toString();
        DiscoveredWatch s4 = new DiscoveredWatch(
                token4, "Watch7,1", "Watch7,1", "11.2", -48,
                PairingStrategyId.NETWORK_RELAY_S4, false, Set.of(AuthStyle.PIN, AuthStyle.OPTICAL));
        String encoded4 = DiscoveryEventCodec.encodeV2(s4);
        assertTrue(encoded4.startsWith(DiscoveryEventCodec.V2_PREFIX));

        String line = "[WatchHal] [T+00123ms|Δ0012ms] " + encoded4;
        String located = WatchDiscoveryEvent.findV2(line);
        assertEquals(encoded4, located);

        DiscoveredWatch decoded4 = DiscoveryEventCodec.decodeV2(located);
        assertEquals(token4, decoded4.token());
        assertEquals("Watch7,1", decoded4.productType());
        assertEquals("Watch7,1", decoded4.displayName());
        assertEquals("11.2", decoded4.watchOs());
        assertEquals(-48, decoded4.rssi());
        assertEquals(PairingStrategyId.NETWORK_RELAY_S4, decoded4.strategy());
        assertFalse(decoded4.experimental());
        assertTrue(decoded4.authStyles().contains(AuthStyle.PIN));
        assertTrue(decoded4.authStyles().contains(AuthStyle.OPTICAL));

        String token1 = UUID.randomUUID().toString();
        DiscoveredWatch s1 = new DiscoveredWatch(
                token1, null, "Apple Watch (Legacy S1)", "watchOS 1-3", -61,
                PairingStrategyId.SIMULTANEOUS_S1, true, Set.of(AuthStyle.PIN));
        DiscoveredWatch decoded1 = DiscoveryEventCodec.decodeV2(DiscoveryEventCodec.encodeV2(s1));
        assertNull(decoded1.productType());
        assertEquals("Apple Watch (Legacy S1)", decoded1.displayName());
        assertEquals(PairingStrategyId.SIMULTANEOUS_S1, decoded1.strategy());
        assertTrue(decoded1.experimental());
        assertEquals(Set.of(AuthStyle.PIN), decoded1.authStyles());
    }

    @Test
    public void encodeV1MaintainsLegacy5FieldContract() {
        String token = UUID.randomUUID().toString();
        DiscoveredWatch s4 = new DiscoveredWatch(
                token, "Watch6,10", "Watch6,10", "10.6", -53,
                PairingStrategyId.NETWORK_RELAY_S4, false, Set.of(AuthStyle.PIN));
        String v1 = DiscoveryEventCodec.encodeV1(s4);
        assertEquals("WATCH_DISCOVERED_V1:" + token + ":Watch6,10:10.6:-53", v1);
        assertEquals(v1, WatchDiscoveryEvent.find("[WatchHal] " + v1));
    }

    @Test
    public void decodeV2RejectsOversizedUnknownFieldsAndInvalidValues() {
        String validToken = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> DiscoveryEventCodec.decodeV2(
                "WATCH_DISCOVERED_V2:{\"token\":\"not-a-uuid\",\"displayName\":\"W\",\"rssi\":-50,\"strategy\":4,\"experimental\":false}"));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryEventCodec.decodeV2(
                "WATCH_DISCOVERED_V2:{\"token\":\"" + validToken + "\",\"displayName\":\"W\",\"rssi\":-50,\"strategy\":9,\"experimental\":false}"));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryEventCodec.decodeV2(
                "WATCH_DISCOVERED_V2:{\"token\":\"" + validToken + "\",\"displayName\":\"W\",\"rssi\":99,\"strategy\":4,\"experimental\":false}"));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryEventCodec.decodeV2(
                "WATCH_DISCOVERED_V2:{\"token\":\"" + validToken + "\",\"displayName\":\"W\",\"rssi\":-50,\"strategy\":4,\"extra\":1}"));
        String oversized = "WATCH_DISCOVERED_V2:{\"token\":\"" + validToken + "\",\"displayName\":\""
                + "A".repeat(DiscoveryEventCodec.MAX_V2_JSON_LENGTH) + "\",\"rssi\":-50,\"strategy\":4}";
        assertNull(DiscoveryEventCodec.findV2(oversized));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryEventCodec.decodeV2(oversized));
    }
}
