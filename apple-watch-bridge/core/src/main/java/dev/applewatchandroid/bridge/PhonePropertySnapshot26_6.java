package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical iOS 26.6 phone-side NanoRegistry full-snapshot schema.
 *
 * <p>Keys are the NSString values referenced by NRDeviceProperty symbols,
 * not the suffixes of those symbols. For example, NRDevicePropertyBuildType
 * holds {@code buildString}; the wire must preserve that exact spelling.</p>
 *
 * <p>The schema is intentionally separate from the Watch7,5 schema:
 * the reference iPhone publishes 60 getter slots, while the watch publishes
 * 75. Missing getter values remain properties with an absent protobuf value,
 * matching Apple's full-poll packer.</p>
 */
final class PhonePropertySnapshot26_6 {
    private static final double APPLE_REFERENCE_UNIX_SECONDS =
            978_307_200.0;

    static final List<String> PROPERTY_NAMES =
            List.of(
                    "backgroundAtrialFibrillationVersion",
                    "backgroundAtrialFibrillationUDI",
                    "backgroundAtrialFibrillationReleaseYear",
                    "electrocardiogramVersion",
                    "electrocardiogramUDI",
                    "electrocardiogramReleaseYear",
                    "electrocardiogramV2AvailableRegions",
                    "menstrualCyclesAlgorithmsVersion",
                    "cardioFitnessVersion",
                    "cardioFitnessAvailableRegions",
                    "medicationScheduleCompatibilityVersion",
                    "sleepCoachingVersion",
                    "sleepActionsVersion",
                    "sleepTrackingVersion",
                    "oxygenSaturationRecordingVersion",
                    "name",
                    "bluetoothMACAddress",
                    "WIFIMACAddress",
                    "totalStorage",
                    "serialNumber",
                    "productType",
                    "homeButtonType",
                    "localizedModel",
                    "regionCode",
                    "regionInfo",
                    "modelNumber",
                    "hwModelStr",
                    "UDID",
                    "mainScreenWidth",
                    "mainScreenHeight",
                    "screenSize",
                    "class",
                    "screenScale",
                    "buildString",
                    "enclosureMaterial",
                    "_advertisingIdentifierSeed",
                    "pairedDeviceCount",
                    "greenTea",
                    "signingFuse",
                    "_supportedPairingStrategy",
                    "deviceNameString",
                    "currentUserLocale",
                    "deviceInDemoMode",
                    "preferredLanguages",
                    "marketingVersion",
                    "IMEI",
                    "MEID",
                    "IMEI2",
                    "capabilities",
                    "isInternalInstall",
                    "regulatoryModelNumber",
                    "hasSEP",
                    "hasSecureElement",
                    "marketingProductName",
                    "CPUType",
                    "CPUSubType",
                    "mainScreenClass",
                    "chipID",
                    "isAltAccount",
                    "MDMManagementState");

    private static final Set<String> PROPERTY_NAME_SET =
            Set.copyOf(
                    PROPERTY_NAMES);

    private PhonePropertySnapshot26_6() {
    }

    static NanoRegistryPropertyCodec.PropertiesChanged fullSnapshot(
            Map<String, NanoRegistryPropertyCodec.PropertyValue> values,
            double bornOn) {
        if (!Double.isFinite(
                bornOn)) {
            throw new IllegalArgumentException(
                    "Phone property snapshot timestamp is not finite");
        }
        return new NanoRegistryPropertyCodec.PropertiesChanged(
                true,
                propertyList(
                        values),
                bornOn);
    }

    static NanoRegistryPropertyCodec.PropertyResponse propertyResponse(
            Map<String, NanoRegistryPropertyCodec.PropertyValue> values) {
        return new NanoRegistryPropertyCodec.PropertyResponse(
                propertyList(
                        values));
    }

    static List<NanoRegistryPropertyCodec.Property> propertyList(
            Map<String, NanoRegistryPropertyCodec.PropertyValue> values) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Phone property values are absent");
        }
        for (String name : values.keySet()) {
            if (name == null
                    || !PROPERTY_NAME_SET.contains(
                    name)) {
                throw new IllegalArgumentException(
                        "Unknown iOS 26.6 phone property: "
                                + name);
            }
        }
        int populatedValues = 0;
        List<NanoRegistryPropertyCodec.Property> properties =
                new ArrayList<>(
                        PROPERTY_NAMES.size());
        for (String name : PROPERTY_NAMES) {
            NanoRegistryPropertyCodec.PropertyValue value =
                    values.get(
                            name);
            if (value != null) {
                populatedValues++;
            }
            properties.add(
                    new NanoRegistryPropertyCodec.Property(
                            name,
                            value));
        }
        if (populatedValues == 0) {
            throw new IllegalArgumentException(
                    "A full phone snapshot needs at least one decoded value");
        }
        return properties;
    }

    static double bornOnFromUnixMilliseconds(
            long unixMilliseconds) {
        return unixMilliseconds / 1000.0
                - APPLE_REFERENCE_UNIX_SECONDS;
    }
}
