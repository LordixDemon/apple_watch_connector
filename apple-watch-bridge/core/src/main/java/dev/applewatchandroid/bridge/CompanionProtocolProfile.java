package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Verified reference profiles, selected by protocol evidence rather than Watch model. */
enum CompanionProtocolProfile {
    IOS_26_6("26.6", "23G71", 25, 26, 32),
    // iOS 27.0.1 24A446: NR systemVersions init 0x23d2de2a4;
    // IDS didConnectControlChannel 0x1006fef34 (control=5, service minimum NSNumber=40).
    IOS_27_0_1("27.0.1", "24A446", 26, 27, 40);

    final String version, build;
    final int minimum, maximum, serviceMinimum;
    CompanionProtocolProfile(String version, String build, int minimum, int maximum, int serviceMinimum) {
        this.version = version; this.build = build; this.minimum = minimum;
        this.maximum = maximum; this.serviceMinimum = serviceMinimum;
    }
    static CompanionProtocolProfile forPeerMaximum(int maximum) {
        if (maximum == 25) return IOS_26_6;
        if (maximum == 26 || maximum == 27) return IOS_27_0_1;
        throw new IllegalArgumentException("No verified companion protocol profile for the observed Watch version");
    }
    boolean accepts(int peer) { return peer >= minimum && peer <= maximum; }
    IdsControlChannelCodec.HelloMessage hello(UUID instance, UUID device, boolean direct) {
        var legacy = IosCompanionProfile26_6.hello(instance, device, false, direct, true);
        try {
            return new IdsControlChannelCodec.HelloMessage(legacy.controlChannelVersion, legacy.productName,
                    version, build, legacy.model, maximum, minimum, maximum, instance, device,
                    legacy.capabilityFlags, serviceMinimum);
        } finally { legacy.destroy(); }
    }
    NormalLinkLocalDeviceProfile normalLink(String identifier) {
        return new NormalLinkLocalDeviceProfile(IosCompanionProfile26_6.DEVICE_NAME, build,
                IosCompanionProfile26_6.wireIdsDeviceIdentifier(identifier), ApplePairingNotifyPayloads.COMPANION_DEVICE_TYPE, true, true);
    }
    java.util.Map<String, String> idsPrivateDeviceData(String identifier) {
        var data = new java.util.LinkedHashMap<>(IosCompanionProfile26_6.idsPrivateDeviceData(identifier));
        data.put("pv", version); data.put("pb", build);
        return java.util.Collections.unmodifiableMap(data);
    }
    String[] capabilities() {
        if (this == IOS_26_6) return PhoneCapabilityUuids26_6.VALUES.clone();
        var values = new java.util.LinkedHashSet<>(java.util.List.of(PhoneCapabilityUuids26_6.VALUES));
        // Removed from 24A446 NRLocalPropertyMonitor getter; three new unconditional entries.
        values.remove("282f1ee6-a144-4106-affb-2bdb5b59fd0d");
        values.remove("b4c9f855-8bbe-5bd6-81ce-0a24d8e4aa74");
        values.add("6f13ff03-6511-4180-bbf3-4c231c10d458");
        values.add("3b512c1e-f8d5-4fe7-b109-1ad8ef7f924d");
        values.add("a781cb9f-7992-4ee7-a6ba-723b6fb0b65e");
        // Do not invent the new AllDayHeartRate/AudioIntelligence feature-gate results.
        return values.toArray(String[]::new);
    }
}
