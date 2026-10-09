package dev.applewatchandroid.bridge;

/**
 * Non-secret local companion metadata bound into each ordinary-IKE session.
 *
 * <p>This is deliberately separate from {@link PairingSessionRecord}: that
 * record describes the remote Watch, while these values describe the Android
 * endpoint and must never be populated from Watch metadata.</p>
 */
final class NormalLinkLocalDeviceProfile {
    private final String deviceName;
    private final String buildVersion;
    private final String idsDeviceId;
    private final int deviceType;
    private final boolean keysAfterFirstUnlock;
    private final boolean alwaysOnWifi;

    NormalLinkLocalDeviceProfile(
            String deviceName,
            String buildVersion,
            String idsDeviceId,
            int deviceType,
            boolean keysAfterFirstUnlock,
            boolean alwaysOnWifi) {
        if (deviceName == null
                || deviceName.isBlank()
                || buildVersion == null
                || buildVersion.isBlank()
                || deviceType < 0
                || deviceType > 0xff) {
            throw new IllegalArgumentException(
                    "Complete local normal-link device metadata is required");
        }
        this.deviceName = deviceName;
        this.buildVersion = buildVersion;
        this.idsDeviceId =
                idsDeviceId == null
                        || idsDeviceId.isBlank()
                        ? null
                        : idsDeviceId;
        this.deviceType = deviceType;
        this.keysAfterFirstUnlock =
                keysAfterFirstUnlock;
        this.alwaysOnWifi = alwaysOnWifi;
    }

    static NormalLinkLocalDeviceProfile androidCompanion(
            String deviceName,
            String buildVersion) {
        return new NormalLinkLocalDeviceProfile(
                deviceName,
                buildVersion,
                null,
                1,
                true,
                true);
    }

    OrdinaryIkeAuth.ResponderProfile bind(
            AppleNetworkRelayInnerAddresses addresses,
            byte[] localPrelude) {
        return new OrdinaryIkeAuth.ResponderProfile(
                addresses,
                localPrelude,
                deviceName,
                buildVersion,
                idsDeviceId,
                deviceType,
                keysAfterFirstUnlock,
                alwaysOnWifi);
    }
}
