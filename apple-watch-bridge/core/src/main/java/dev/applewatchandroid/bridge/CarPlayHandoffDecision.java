package dev.applewatchandroid.bridge;

/** Pure fail-closed policy for selecting direct or cooperative HAL startup. */
final class CarPlayHandoffDecision {
    enum Mode {
        DIRECT_HAL,
        COOPERATIVE_CARPLAY,
        REFUSE
    }

    private CarPlayHandoffDecision() {
    }

    static Mode decide(
            boolean stockBluetoothOff,
            boolean stockBluetoothOn,
            boolean providerAvailable,
            boolean wifiReady,
            boolean vehicleMediaActive,
            boolean alreadyPrepared) {
        if (stockBluetoothOff) {
            return alreadyPrepared
                    ? Mode.REFUSE
                    : Mode.DIRECT_HAL;
        }
        if (!stockBluetoothOn) {
            return Mode.REFUSE;
        }
        return providerAvailable
                && wifiReady
                && vehicleMediaActive
                && !alreadyPrepared
                ? Mode.COOPERATIVE_CARPLAY
                : Mode.REFUSE;
    }
}
