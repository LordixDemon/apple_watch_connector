package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.UUID;

public final class IosCompanionProfile26_6Test {
    private static final UUID INSTANCE =
            UUID.fromString(
                    "00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID DEVICE =
            UUID.fromString(
                    "10213243-5465-7687-98a9-bacbdcedfe0f");

    @Test
    public void ordinaryPairingProfileOmitsNativeTinkerFlag() {
        IdsControlChannelCodec.HelloMessage hello =
                IosCompanionProfile26_6.hello(
                        INSTANCE,
                        DEVICE,
                        false,
                        false,
                        true);
        try {
            assertEquals(
                    "5",
                    hello.controlChannelVersion);
            assertEquals(
                    "iPhone OS",
                    hello.productName);
            assertEquals(
                    "26.6",
                    hello.productVersion);
            assertEquals(
                    "23G71",
                    hello.productBuildVersion);
            assertEquals(
                    "iPhone18,1",
                    hello.model);
            assertEquals(
                    "V53AP",
                    IosCompanionProfile26_6.HW_MODEL_STRING);
            assertEquals(
                    0x8150L,
                    IosCompanionProfile26_6.CHIP_ID);
            assertEquals(
                    "iPhone 17 Pro",
                    IosCompanionProfile26_6.MARKETING_PRODUCT_NAME);
            assertEquals(
                    3,
                    IosCompanionProfile26_6.SCREEN_SCALE);
            assertEquals(
                    0,
                    IosCompanionProfile26_6.RELEASE_TYPE);
            assertEquals(
                    345,
                    IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS.length);
            assertEquals(
                    "02168e84-5dd8-4b19-9204-a79f04b33a32",
                    IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS[0]);
            assertEquals(
                    "d0482616-cc61-4bf7-9123-cb12db9c1784",
                    IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS[
                            IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS.length
                                    - 1]);
            assertEquals(
                    26,
                    hello.pairingProtocolVersion);
            assertEquals(
                    25,
                    hello.minCompatibilityVersion);
            assertEquals(
                    26,
                    hello.maxCompatibilityVersion);
            assertEquals(
                    INSTANCE,
                    hello.instanceId);
            assertEquals(
                    DEVICE,
                    hello.deviceUniqueId);
            assertEquals(
                    0x3ff,
                    hello.capabilityFlags);
            assertEquals(
                    32,
                    hello.serviceMinCompatibilityVersion);
        } finally {
            hello.destroy();
        }
    }

    @Test
    public void directMessagingAndNonDefaultRulesAreIndependent() {
        IdsControlChannelCodec.HelloMessage direct =
                IosCompanionProfile26_6.hello(
                        INSTANCE,
                        DEVICE,
                        false,
                        true,
                        true);
        IdsControlChannelCodec.HelloMessage nonDefault =
                IosCompanionProfile26_6.hello(
                        INSTANCE,
                        DEVICE,
                        false,
                        true,
                        false);
        try {
            assertEquals(
                    0xbff,
                    direct.capabilityFlags);
            assertEquals(
                    0x3ff,
                    nonDefault.capabilityFlags);
            assertEquals(
                    new UUID(
                            0,
                            0),
                    nonDefault.deviceUniqueId);
        } finally {
            direct.destroy();
            nonDefault.destroy();
        }
    }

    @Test
    public void normalLinkRejectsMissingOrNonCanonicalIdsIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IosCompanionProfile26_6.normalLink(
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> IosCompanionProfile26_6.normalLink(
                        "10213243-5465-7687-98A9-BACBDCEDFE0F"));
    }

    @Test
    public void privateDeviceDataUniqueIdMatchesHelloDeviceIdentity() {
        // watchOS indexes account devices by private-data "u" and matches it
        // against the Hello TLV5 deviceUniqueId; the two must be one identity.
        IdsControlChannelCodec.HelloMessage hello =
                IosCompanionProfile26_6.hello(
                        INSTANCE,
                        DEVICE,
                        false,
                        false,
                        true);
        try {
            java.util.Map<String, String> data =
                    IosCompanionProfile26_6.idsPrivateDeviceData(
                            DEVICE.toString());
            assertEquals(
                    DEVICE.toString()
                            .toUpperCase(
                                    java.util.Locale.ROOT),
                    data.get("u"));
            assertEquals(
                    hello.deviceUniqueId,
                    UUID.fromString(
                            data.get("u")));
            assertEquals(
                    13,
                    data.size());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> IosCompanionProfile26_6.idsPrivateDeviceData(
                            null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> IosCompanionProfile26_6.idsPrivateDeviceData(
                            "not-a-uuid"));
        } finally {
            hello.destroy();
        }
    }
}
