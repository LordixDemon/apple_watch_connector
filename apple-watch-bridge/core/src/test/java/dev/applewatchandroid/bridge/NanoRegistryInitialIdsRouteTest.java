package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class NanoRegistryInitialIdsRouteTest {
    @Test
    public void classDSetupUsesUrgentDOnNormalDataConnector() {
        NanoRegistryInitialIdsRoute route =
                NanoRegistryInitialIdsRoute
                        .classDSetup();

        assertEquals(
                "com.apple.private.alloy.bluetoothregistry",
                route.topic);
        assertEquals(
                300,
                route.idsPriority);
        assertEquals(
                IdsUtunConnectionName
                        .PROTECTION_CLASS_D,
                route.idsProtectionClass);
        assertEquals(
                "UTunDelivery-Default-Urgent-D",
                route.utunConnectionName);
        assertEquals(
                "idstest/localdelivery/"
                        + "UTunDelivery-Default-Urgent-D",
                route.serviceConnectorName
                        .encode());
        assertEquals(
                IdsIpsecServiceRoute
                        .Connector.NORMAL,
                route.ipsecRoute.connector);
        assertEquals(
                61314,
                route.ipsecRoute.listenerPort);
        assertEquals(
                IdsIpsecServiceRoute
                        .NETWORK_RELAY_CLASS_D,
                route.ipsecRoute
                        .networkRelayDataClass);
        assertFalse(
                route.ipsecRoute
                        .allowsQuickRelay);
    }

    @Test
    public void classCFullPropertiesUsesUrgentCOnNormalDataConnector() {
        NanoRegistryInitialIdsRoute route =
                NanoRegistryInitialIdsRoute
                        .classCProperties();

        assertEquals(
                "com.apple.private.alloy.bluetoothregistryclassc",
                route.topic);
        assertEquals(
                NanoRegistryPropertyCodec
                        .MESSAGE_PRIORITY,
                route.idsPriority);
        assertEquals(
                IdsUtunConnectionName
                        .PROTECTION_CLASS_C,
                route.idsProtectionClass);
        assertEquals(
                "UTunDelivery-Default-Urgent-C",
                route.utunConnectionName);
        assertEquals(
                61314,
                route.ipsecRoute.listenerPort);
        assertEquals(
                IdsIpsecServiceRoute
                        .NETWORK_RELAY_CLASS_C,
                route.ipsecRoute
                        .networkRelayDataClass);
    }

    @Test
    public void topicsRemainDynamicServiceMapKeysNotStreamIds() {
        NanoRegistryInitialIdsRoute classD =
                NanoRegistryInitialIdsRoute
                        .classDSetup();
        NanoRegistryInitialIdsRoute classC =
                NanoRegistryInitialIdsRoute
                        .classCProperties();
        IdsServiceMapState serviceMap =
                new IdsServiceMapState(
                        17);
        try {
            IdsServiceMapState.OutgoingRoute
                    classDStream =
                    serviceMap.routeOutgoing(
                            classD.topic);
            IdsServiceMapState.OutgoingRoute
                    classCStream =
                    serviceMap.routeOutgoing(
                            classC.topic);

            assertEquals(
                    17,
                    classDStream.streamId);
            assertEquals(
                    18,
                    classCStream.streamId);
            assertNotEquals(
                    classDStream.streamId,
                    classCStream.streamId);
        } finally {
            serviceMap.close();
        }
    }

    @Test
    public void unknownInitialTopicFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryInitialIdsRoute
                        .forTopic(
                                "com.apple.private.alloy.unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryInitialIdsRoute
                        .forTopic(
                                null));
    }
}
