package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.UUID;
import java.security.SecureRandom;
import static org.junit.Assert.*;

public class CompanionProtocolProfileTest {
    @Test public void selectionUsesPeerProtocolNotModelAndRejectsUnverifiedFutureVersions() {
        assertEquals(CompanionProtocolProfile.IOS_26_6, CompanionProtocolProfile.forPeerMaximum(25));
        assertEquals(CompanionProtocolProfile.IOS_27_0_1, CompanionProtocolProfile.forPeerMaximum(26));
        assertEquals(CompanionProtocolProfile.IOS_27_0_1, CompanionProtocolProfile.forPeerMaximum(27));
        assertThrows(IllegalArgumentException.class, () -> CompanionProtocolProfile.forPeerMaximum(28));
        assertThrows(IllegalArgumentException.class, () -> CompanionProtocolProfile.forPeerMaximum(0));
    }
    @Test public void verified27HelloAndDeviceInfoDescribeTheSameReferenceBuild() {
        UUID instance = UUID.randomUUID(), device = UUID.randomUUID();
        var profile = CompanionProtocolProfile.IOS_27_0_1;
        var hello = profile.hello(instance, device, true);
        try {
            assertEquals("5", hello.controlChannelVersion);
            assertEquals(27, hello.pairingProtocolVersion);
            assertEquals(26, hello.minCompatibilityVersion);
            assertEquals(27, hello.maxCompatibilityVersion);
            assertEquals(40, hello.serviceMinCompatibilityVersion);
            assertEquals("24A446", hello.productBuildVersion);
            assertEquals("27.0.1", profile.idsPrivateDeviceData(device.toString()).get("pv"));
            assertEquals(hello.productBuildVersion, profile.idsPrivateDeviceData(device.toString()).get("pb"));
            assertEquals(device.toString().toUpperCase(java.util.Locale.ROOT), profile.idsPrivateDeviceData(device.toString()).get("u"));
        } finally { hello.destroy(); }
    }
    @Test public void idsProfileIsFrozenOnceHelloHasBeenSent() {
        try (var control = IdsControlChannelSession.createDefaultPaired(new SecureRandom(),
                UUID.randomUUID().toString(), true, IdsServiceMapState.APPLE_FIRST_STREAM_ID)) {
            control.useProfile(CompanionProtocolProfile.IOS_27_0_1);
            byte[] bytes = control.startHello();
            assertTrue(bytes.length > 0);
            assertThrows(IllegalStateException.class, () -> control.useProfile(CompanionProtocolProfile.IOS_26_6));
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
    @Test public void pairingModeUsesTheSelectedRangeWithoutExpandingLegacyRange() {
        var current = NanoRegistryClassDCodec.PairingModeRequest.forProfile(0, 27, CompanionProtocolProfile.IOS_27_0_1);
        try {
            assertEquals(Integer.valueOf(26), current.phonePairingProtocolVersionMin);
            assertEquals(Integer.valueOf(27), current.phonePairingProtocolVersionMax);
        } finally { current.destroy(); }
        assertThrows(IllegalArgumentException.class, () -> NanoRegistryClassDCodec.PairingModeRequest.modernIos26_6(0, 27));
        assertThrows(IllegalArgumentException.class, () -> NanoRegistryClassDCodec.PairingModeRequest.forProfile(0, 25, CompanionProtocolProfile.IOS_27_0_1));
    }
    @Test public void newCapabilitiesDoNotInventFeatureGatedHealthOrAudioSupport() {
        var values = java.util.Set.of(CompanionProtocolProfile.IOS_27_0_1.capabilities());
        assertEquals(346, values.size());
        assertTrue(values.contains("a781cb9f-7992-4ee7-a6ba-723b6fb0b65e"));
        assertFalse(values.contains("282f1ee6-a144-4106-affb-2bdb5b59fd0d"));
        assertFalse(values.contains("d2f9b521-e715-4a96-94f9-19c209511ee5"));
        assertFalse(values.contains("704c505e-d459-42a9-b3b1-81b4cfa5911d"));
        assertEquals(345, CompanionProtocolProfile.IOS_26_6.capabilities().length);
    }
}
