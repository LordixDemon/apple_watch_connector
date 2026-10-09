package dev.applewatchandroid.bridge;

/** Explicit NSS reboot, not erase: Watch7,5 / 23S303 handleRebootDeviceMsg:. */
final class NativeWatchReboot {
    static final String COMMAND = "REBOOT_WATCH";
    static final String TOPIC = NanoSystemSettingsDiagnostics.TOPIC;
    static final int TYPE = 19;
    private boolean attempted;

    /** One attempt per HAL session, including an ambiguous transport failure. */
    void begin(boolean activatedOperationalPair, boolean nativeFaceMutationPending) {
        if (!activatedOperationalPair || nativeFaceMutationPending || attempted) {
            throw new IllegalStateException("Watch reboot requires an activated idle pair and an unused reboot attempt");
        }
        attempted = true;
    }
}
