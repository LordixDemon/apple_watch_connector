package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class AppSyncConduitCodecTest {
    @Test
    public void routesOnClassCAndEncodesDefaultAppsAndSyncFinished() {
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(AppSyncConduitCodec.TOPIC);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C, route.idsProtectionClass);

        Map<?, ?> listMsg = (Map<?, ?>) AppleBinaryPropertyList.decode(AppSyncConduitCodec.encodeDefaultAppsList());
        assertEquals(30L, listMsg.get("T"));
        List<?> apps = (List<?>) listMsg.get("BL");
        assertEquals(25, apps.size());
        assertTrue(apps.contains("com.apple.NanoMaps"));
        assertTrue(apps.contains("com.apple.weather.watchapp"));

        Map<?, ?> doneMsg = (Map<?, ?>) AppleBinaryPropertyList.decode(AppSyncConduitCodec.encodeSyncFinished());
        assertEquals(34L, doneMsg.get("T"));
        assertEquals(1, doneMsg.size());

        long expiry = AppSyncConduitCodec.expiryForUnixMillis((AppSyncConduitCodec.APPLE_EPOCH_OFFSET_SECONDS + 100L) * 1000L);
        assertEquals(3700L, expiry);
    }

    @Test
    public void describesInboundMetadataWithoutLeakingBundleIdentifiers() {
        byte[] inbound = AppleBinaryPropertyList.encode(Map.of(
                "T", 12L,
                "BL", List.of("com.example.private.app")));
        String summary = AppSyncConduitCodec.describeInbound(inbound);
        assertTrue(summary.contains("T=12"));
        assertTrue(summary.contains("BLCount=1"));
        assertFalse(summary.contains("com.example.private.app"));
    }
}
