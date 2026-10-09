package dev.applewatchandroid.bridge;

/**
 * Proven application-topic routing above the two initial IDS data lanes.
 *
 * <p>Topics are dynamic IDS ServiceMap keys. They do not create independent
 * TCP connections: services with the same priority and protection class share
 * the same canonical Network.framework service-connector connection.</p>
 */
final class IdsApplicationRoute {
    static final String PB_BRIDGE_SERVICE =
            "com.apple.private.alloy.pbbridge";
    static final String PAIRED_SYNC_SERVICE =
            "com.apple.private.alloy.preferencessync.pairedsync";
    static final String PREFERENCE_SYNC_SERVICE =
            "com.apple.private.alloy.preferencessync";
    static final String BULLETIN_DISTRIBUTOR_SERVICE =
            "com.apple.private.alloy.bulletindistributor";
    static final String BULLETIN_SETTINGS_SERVICE =
            "com.apple.private.alloy.bulletindistributor.settings";
    static final String FIND_MY_LOCAL_SERVICE =
            "com.apple.private.alloy.findmylocaldevice";
    static final String HEALTH_SYNC_SERVICE =
            "com.apple.private.alloy.health.sync.classc";
    static final String TELEPHONY_SERVICE =
            "com.apple.private.alloy.telephony";
    static final String TIMESYNC_SERVICE =
            "com.apple.private.alloy.timesync";
    static final String CLOCKFACE_SYNC_SERVICE =
            "com.apple.private.alloy.clockface.sync";
    static final String TIMEZONESYNC_SERVICE =
            "com.apple.private.alloy.timezonesync";

    final String topic;
    final int idsPriority;
    final int idsProtectionClass;
    final String utunConnectionName;
    final IdsServiceConnectorName serviceConnectorName;
    final IdsIpsecServiceRoute ipsecRoute;

    private IdsApplicationRoute(
            String topic,
            int idsProtectionClass) {
        this.topic = topic;
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

    static IdsApplicationRoute forTopic(
            String topic) {
        if (NanoRegistryPropertyCodec
                .CLASS_D_SERVICE
                .equals(
                        topic)
                || PREFERENCE_SYNC_SERVICE.equals(
                        topic)
                || BULLETIN_SETTINGS_SERVICE.equals(
                        topic)
                || NanoSystemSettingsDiagnostics.TOPIC.equals(topic)
                || IdsDeviceInfoExchange.TOPIC.equals(topic)
                || TIMESYNC_SERVICE.equals(topic)
                || TIMEZONESYNC_SERVICE.equals(topic)
                || CLOCKFACE_SYNC_SERVICE.equals(topic)
                || FIND_MY_LOCAL_SERVICE.equals(topic)) {
            return new IdsApplicationRoute(
                    topic,
                    IdsUtunConnectionName
                            .PROTECTION_CLASS_D);
        }
        if (NanoRegistryPropertyCodec
                .CLASS_C_SERVICE
                .equals(
                        topic)
                || PB_BRIDGE_SERVICE.equals(
                        topic)
                // 23S303 sends pairedsync receipts on Urgent-C. The ordinary
                // preferences service has a separate D service definition;
                // do not inherit that lane for the pairedsync service.
                || PAIRED_SYNC_SERVICE.equals(topic)
                || BULLETIN_DISTRIBUTOR_SERVICE.equals(
                        topic)
                || HEALTH_SYNC_SERVICE.equals(
                        topic)
                || WifiNetworkSyncCodec.TOPIC.equals(topic)
                || SysdiagnoseArchiveInventory.TOPIC.equals(topic)
                || TELEPHONY_SERVICE.equals(
                        topic)) {
            return new IdsApplicationRoute(
                    topic,
                    IdsUtunConnectionName
                            .PROTECTION_CLASS_C);
        }
        throw new IllegalArgumentException(
                "Unknown IDS application topic: " + topic);
    }
}
