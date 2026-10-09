package dev.applewatchandroid.bridge;

import java.util.UUID;

/**
 * Source-pinned iPhone-side identity used by the Watch7,5 setup emulator.
 *
 * <p>These values come from iOS 26.6 build 23G71 for iPhone18,1. The
 * human-readable device name is intentionally generic because a real iPhone
 * name is user-selected; the protocol and compatibility values are not.</p>
 */
final class IosCompanionProfile26_6 {
    static final String DEVICE_NAME = "iPhone";
    static final String CONTROL_CHANNEL_VERSION = "5";
    static final String PRODUCT_NAME = "iPhone OS";
    static final String PRODUCT_VERSION = "26.6";
    static final String PRODUCT_BUILD_VERSION = "23G71";
    static final String MODEL = "iPhone18,1";
    static final String LOCALIZED_MODEL = "iPhone";
    /**
     * MobileGestalt {@code ReleaseType} as read by
     * {@code +[NRLocalPropertyMonitor initializeGetters]} via
     * {@code _getGestaltNumber:"ReleaseType"} for the BuildType slot.
     * Production iOS is 0.
     */
    static final int RELEASE_TYPE = 0;
    /**
     * DeviceTree {@code unique-model} / {@code target-sub-type} /
     * {@code compatible} of iOS 26.6 23G71 {@code iPhone18,1}
     * ({@code DeviceTree.v53ap}).
     */
    static final String HW_MODEL_STRING = "V53AP";
    /**
     * OTA restore metadata for the same build: CPU ID {@code t8150}
     * (A19 Pro). NanoRegistry {@code ChipID} is that SoC identifier,
     * the same mapping as Watch7,5 {@code t8310} → {@code 0x8310}.
     * The OTA DeviceTree {@code chosen.chip-id} slot is zero until
     * fused at boot; do not treat that placeholder as the ChipID.
     */
    static final long CHIP_ID = 0x8150L;
    static final String MARKETING_PRODUCT_NAME = "iPhone 17 Pro";
    /**
     * DeviceTree {@code product.artwork-scale-factor} for iPhone18,1.
     */
    static final int SCREEN_SCALE = 3;
    /**
     * Mach-O header of {@code nanoregistryd} from iOS 26.6 23G71
     * {@code iPhone18,1}: {@code cputype=ARM64 cpusubtype=E}.
     */
    static final int CPU_TYPE_ARM64 = 0x0100000c;
    static final int CPU_SUBTYPE_ARM64E = 2;
    /**
     * {@code +[NRLocalPropertyMonitor initializeGetters]} Capabilities
     * {@code NSSet} for this build. See {@link PhoneCapabilityUuids26_6}.
     */
    static final String[] PHONE_CAPABILITY_UUIDS =
            PhoneCapabilityUuids26_6.VALUES;

    static final long PAIRING_PROTOCOL_VERSION = 26;
    static final long MIN_COMPATIBILITY_VERSION = 25;
    static final long MAX_COMPATIBILITY_VERSION = 26;
    static final int SERVICE_MIN_COMPATIBILITY_VERSION = 32;

    static final long CAPABILITIES_STANDARD_PAIRING = 0x3ffL;
    /** IDSUTunControlMessage_Hello._isTinkerFlagSet reads bit 10. */
    static final long CAPABILITY_FAMILY_PAIRING = 0x400L;
    static final long CAPABILITY_DIRECT_MESSAGING = 0x800L;

    private static final UUID ZERO_UUID =
            new UUID(
                    0,
                    0);

    private IosCompanionProfile26_6() {
    }

    /**
     * Private device data for the IDS device-info response (command 12).
     *
     * <p>The {@code "u"} entry is the single identifier the watch uses to index
     * account devices ({@code -[IDSDAccountController updateDevicePropertiesWithDevices:]})
     * and to resolve incoming message sender IDs
     * ({@code -[IDSDAccount fromIDForCBUUID:deviceID:]}): devices without it are
     * skipped, and NanoRegistry then cannot map our messages to a paired device.
     * It must equal the identifier carried by the control-channel Hello (TLV 5)
     * and by the top-level {@code identifier} field of the same response.</p>
     *
     * <p>Capability and color entries mirror what iOS 26.6
     * {@code -[IDSPairingManager _localDevicePrivateData]} (identityservicesd,
     * 23G71) emits for an iPhone with a SIM: Apple Pay, Handoff, tethering,
     * SMS/MMS relay and phone calls are all {@code "1"} because
     * FTDeviceSupport/MGGetBoolAnswer/IDSCTAdapter answer true on such an
     * iPhone. {@code "c"}/{@code "ec"} are the DeviceColor/DeviceEnclosureColor
     * strings the same builder adds when MobileGestalt reports them (non-nil on
     * iPhone); watchOS 26.2 sends {@code c="1" ec="8"} itself, and the values
     * only select the peer icon artwork. A watch that sees an "iPhone" with all
     * capabilities zeroed treats it as a no-radio accessory and skips the
     * service bring-up the sync chain depends on.</p>
     */
    static java.util.Map<String, String> idsPrivateDeviceData(
            String localIdsDeviceUuid) {
        // Actual IDSFoundation NSString values from 23G71, not symbol suffixes.
        // IDSPairingManager emits string-valued version/feature fields.
        java.util.Map<String, String> data =
                new java.util.LinkedHashMap<>();
        data.put("v", "1");
        data.put("pn", PRODUCT_NAME);
        data.put("pv", PRODUCT_VERSION);
        data.put("pb", PRODUCT_BUILD_VERSION);
        data.put("ap", "1");
        data.put("h", "1");
        data.put("t", "1");
        data.put("s", "1");
        data.put("m", "1");
        data.put("p", "1");
        data.put("c", "1");
        data.put("ec", "8");
        data.put("u", uppercaseIdsDeviceUuid(localIdsDeviceUuid));
        return java.util.Collections.unmodifiableMap(data);
    }

    /**
     * Validates like {@link #wireIdsDeviceIdentifier} but accepts either case:
     * the device-info exchange carries the identifier already uppercased.
     */
    private static String uppercaseIdsDeviceUuid(
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is absent");
        }
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is not a UUID",
                    invalid);
        }
        if (!parsed.toString().equalsIgnoreCase(
                value)) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is not canonical");
        }
        return parsed.toString()
                .toUpperCase(
                        java.util.Locale.ROOT);
    }

    static NormalLinkLocalDeviceProfile normalLink(
            String localIdsDeviceUuid) {
        requireCanonicalUuid(
                localIdsDeviceUuid);
        return new NormalLinkLocalDeviceProfile(
                DEVICE_NAME,
                PRODUCT_BUILD_VERSION,
                wireIdsDeviceIdentifier(localIdsDeviceUuid),
                ApplePairingNotifyPayloads
                        .COMPANION_DEVICE_TYPE,
                true,
                true);
    }

    static String wireIdsDeviceIdentifier(String localIdsDeviceUuid) {
        requireCanonicalUuid(localIdsDeviceUuid);
        return localIdsDeviceUuid.toUpperCase(java.util.Locale.ROOT);
    }

    static IdsControlChannelCodec.HelloMessage hello(
            UUID connectionInstanceId,
            UUID persistentDeviceId,
            boolean familyPairing,
            boolean supportsDirectMessaging,
            boolean defaultPairedRoute) {
        if (connectionInstanceId == null
                || persistentDeviceId == null) {
            throw new IllegalArgumentException(
                    "IDS Hello local UUIDs are absent");
        }
        long capabilities = CAPABILITIES_STANDARD_PAIRING;
        if (familyPairing) capabilities |= CAPABILITY_FAMILY_PAIRING;
        if (supportsDirectMessaging
                && defaultPairedRoute) {
            capabilities |=
                    CAPABILITY_DIRECT_MESSAGING;
        }
        return new IdsControlChannelCodec.HelloMessage(
                CONTROL_CHANNEL_VERSION,
                PRODUCT_NAME,
                PRODUCT_VERSION,
                PRODUCT_BUILD_VERSION,
                MODEL,
                PAIRING_PROTOCOL_VERSION,
                MIN_COMPATIBILITY_VERSION,
                MAX_COMPATIBILITY_VERSION,
                connectionInstanceId,
                defaultPairedRoute
                        ? persistentDeviceId
                        : ZERO_UUID,
                capabilities,
                SERVICE_MIN_COMPATIBILITY_VERSION);
    }

    private static void requireCanonicalUuid(
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is absent");
        }
        try {
            if (!UUID.fromString(
                    value).toString().equals(
                    value)) {
                throw new IllegalArgumentException(
                        "Local IDS device UUID is not canonical");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is not canonical",
                    invalid);
        }
    }
}
