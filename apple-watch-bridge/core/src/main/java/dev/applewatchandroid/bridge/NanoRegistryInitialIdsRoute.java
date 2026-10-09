package dev.applewatchandroid.bridge;

/**
 * Proven IDS transport lanes used by the initial NanoRegistry exchange.
 *
 * <p>The NanoRegistry service topic is multiplexed inside an IDS data
 * connection. It is not the Network.framework service-connector service
 * string. Both initial lanes use IDS priority 300, which selects the
 * {@code Urgent} UTun. Their service protection class then selects the
 * {@code -D} or {@code -C} route.</p>
 */
final class NanoRegistryInitialIdsRoute {
    final String topic;
    final int idsPriority;
    final int idsProtectionClass;
    final String utunConnectionName;
    final IdsServiceConnectorName serviceConnectorName;
    final IdsIpsecServiceRoute ipsecRoute;

    private NanoRegistryInitialIdsRoute(
            String topic,
            int idsProtectionClass) {
        this.topic =
                topic;
        idsPriority =
                IdsUtunConnectionName
                        .PRIORITY_URGENT;
        this.idsProtectionClass =
                idsProtectionClass;
        utunConnectionName =
                IdsUtunConnectionName
                        .defaultPaired(
                                idsPriority,
                                idsProtectionClass);
        serviceConnectorName =
                IdsServiceConnectorName
                        .localDelivery(
                                utunConnectionName);
        ipsecRoute =
                IdsIpsecServiceRoute
                        .localDelivery(
                                serviceConnectorName);
    }

    static NanoRegistryInitialIdsRoute classDSetup() {
        return new NanoRegistryInitialIdsRoute(
                NanoRegistryPropertyCodec
                        .CLASS_D_SERVICE,
                IdsUtunConnectionName
                        .PROTECTION_CLASS_D);
    }

    static NanoRegistryInitialIdsRoute classCProperties() {
        return new NanoRegistryInitialIdsRoute(
                NanoRegistryPropertyCodec
                        .CLASS_C_SERVICE,
                IdsUtunConnectionName
                        .PROTECTION_CLASS_C);
    }

    static NanoRegistryInitialIdsRoute forTopic(
            String topic) {
        if (NanoRegistryPropertyCodec
                .CLASS_D_SERVICE
                .equals(
                        topic)) {
            return classDSetup();
        }
        if (NanoRegistryPropertyCodec
                .CLASS_C_SERVICE
                .equals(
                        topic)) {
            return classCProperties();
        }
        throw new IllegalArgumentException(
                "Unknown initial NanoRegistry IDS topic");
    }
}
