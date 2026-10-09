package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Local NRDevice entry binding. Peer MiniStore's pairingID belongs to a different registry. */
final class NativeHealthRegistryBinding {
    record Binding(UUID registry,String product,String build) { }
    static Binding bind(UUID pair,UUID epoch,UUID local,UUID peer,
                        HealthRegistryObservationCodec.Observation observation,UUID reservedRegistry,String authenticatedBuild) {
        observation.requireContext(pair,epoch,local,peer);
        if(reservedRegistry==null || reservedRegistry.getMostSignificantBits()==0 && reservedRegistry.getLeastSignificantBits()==0
                || !"Watch7,5".equals(observation.product()) || !"23S303".equals(authenticatedBuild)
                || observation.build()!=null && !authenticatedBuild.equals(observation.build())) {
            throw new IllegalArgumentException("Unsupported/conflicting native Health registry binding");
        }
        // EPSaga updateNRMutableDeviceFromEPDevice:withNRUUID: passes NRUUID at block+0x28;
        // block stores that same value in registry[NRUUID].NRDevicePropertyPairingID (23S303).
        // The reserved local record UUID is retained, never derived from owner generation or peer properties.
        return new Binding(reservedRegistry,observation.product(),authenticatedBuild);
    }
}
