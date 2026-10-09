package dev.applewatchandroid.bridge;

import java.util.Set;

/**
 * The NetworkRelay route selected by the watchOS 26.2 IDS service-connector
 * path.
 *
 * <p>The two service connectors listen on wildcard IPv6 addresses over the
 * NetworkRelay interface subtype. Control always uses Class D and the cloud
 * connector. Local-delivery data derives Class C or D from the exact UTun
 * identifier and uses the cloud connector only for a {@code Cloud} name.</p>
 */
final class IdsIpsecServiceRoute {
    static final int NETWORK_RELAY_INTERFACE_SUBTYPE =
            0x1389;
    static final int NORMAL_LISTENER_PORT =
            61314;
    static final int CLOUD_LISTENER_PORT =
            61315;
    static final int NETWORK_RELAY_CLASS_C =
            3;
    static final int NETWORK_RELAY_CLASS_D =
            4;
    static final int TRAFFIC_CLASS =
            900;
    static final String CONTROL_SERVICE =
            "ids-control-channel";

    enum Connector {
        NORMAL,
        CLOUD
    }

    final String service;
    final Connector connector;
    final int listenerPort;
    final int networkRelayDataClass;
    final boolean allowsQuickRelay;

    private IdsIpsecServiceRoute(
            String service,
            Connector connector,
            int listenerPort,
            int networkRelayDataClass,
            boolean allowsQuickRelay) {
        this.service =
                service;
        this.connector =
                connector;
        this.listenerPort =
                listenerPort;
        this.networkRelayDataClass =
                networkRelayDataClass;
        this.allowsQuickRelay =
                allowsQuickRelay;
    }

    static IdsIpsecServiceRoute control() {
        return new IdsIpsecServiceRoute(
                CONTROL_SERVICE,
                Connector.CLOUD,
                CLOUD_LISTENER_PORT,
                NETWORK_RELAY_CLASS_D,
                true);
    }

    static IdsIpsecServiceRoute localDelivery(
            IdsServiceConnectorName service) {
        if (service == null
                || !service.isLocalDelivery()) {
            throw new IllegalArgumentException(
                    "IDS IPsec data route requires localdelivery");
        }
        boolean ordinary =
                IdsUtunConnectionName
                        .isDefaultPairedIpsecIdentifier(
                                service.name);
        boolean cloud =
                IdsUtunConnectionName
                        .isDefaultPairedCloudIdentifier(
                                service.name);
        if (!ordinary
                && !cloud) {
            throw new IllegalArgumentException(
                    "Unknown default-paired IDS UTun identifier");
        }

        int dataClass;
        if (service.name.endsWith(
                "-C")) {
            dataClass =
                    NETWORK_RELAY_CLASS_C;
        } else if (service.name.endsWith(
                "-D")
                || service.name.endsWith(
                        "-D-"
                                + IdsUtunConnectionName
                                        .RELAY_IDENTIFIER)) {
            dataClass =
                    NETWORK_RELAY_CLASS_D;
        } else {
            throw new IllegalArgumentException(
                    "IDS UTun identifier has no proven C/D route");
        }

        return new IdsIpsecServiceRoute(
                service.encode(),
                cloud
                        ? Connector.CLOUD
                        : Connector.NORMAL,
                cloud
                        ? CLOUD_LISTENER_PORT
                        : NORMAL_LISTENER_PORT,
                dataClass,
                cloud);
    }

    static Set<Integer> listenerPorts() {
        return Set.of(
                NORMAL_LISTENER_PORT,
                CLOUD_LISTENER_PORT);
    }
}
