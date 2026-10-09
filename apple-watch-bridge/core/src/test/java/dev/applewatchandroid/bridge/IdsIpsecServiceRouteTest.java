package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class IdsIpsecServiceRouteTest {
    @Test
    public void controlUsesCloudListenerButAlwaysClassD() {
        IdsIpsecServiceRoute route =
                IdsIpsecServiceRoute.control();

        assertEquals(
                IdsIpsecServiceRoute.CONTROL_SERVICE,
                route.service);
        assertEquals(
                IdsIpsecServiceRoute.Connector.CLOUD,
                route.connector);
        assertEquals(
                61315,
                route.listenerPort);
        assertEquals(
                4,
                route.networkRelayDataClass);
        assertTrue(
                route.allowsQuickRelay);
        assertEquals(
                0x1389,
                IdsIpsecServiceRoute
                        .NETWORK_RELAY_INTERFACE_SUBTYPE);
        assertEquals(
                900,
                IdsIpsecServiceRoute.TRAFFIC_CLASS);
    }

    @Test
    public void ordinaryLocalDeliveryUsesNormalPortAndNameClass() {
        IdsIpsecServiceRoute classC =
                route(
                        IdsUtunConnectionName
                                .defaultPaired(
                                        IdsUtunConnectionName
                                                .PRIORITY_DEFAULT,
                                        IdsUtunConnectionName
                                                .PROTECTION_CLASS_C));
        assertEquals(
                IdsIpsecServiceRoute.Connector.NORMAL,
                classC.connector);
        assertEquals(
                61314,
                classC.listenerPort);
        assertEquals(
                3,
                classC.networkRelayDataClass);
        assertFalse(
                classC.allowsQuickRelay);

        IdsIpsecServiceRoute classD =
                route(
                        IdsUtunConnectionName
                                .defaultPaired(
                                        IdsUtunConnectionName
                                                .PRIORITY_SYNC,
                                        IdsUtunConnectionName
                                                .PROTECTION_CLASS_D));
        assertEquals(
                61314,
                classD.listenerPort);
        assertEquals(
                4,
                classD.networkRelayDataClass);

        IdsIpsecServiceRoute relay =
                route(
                        IdsUtunConnectionName
                                .defaultPairedRelay());
        assertEquals(
                IdsIpsecServiceRoute.Connector.NORMAL,
                relay.connector);
        assertEquals(
                4,
                relay.networkRelayDataClass);
    }

    @Test
    public void cloudLocalDeliveryUsesCloudPortAndRetainsNameClass() {
        IdsIpsecServiceRoute classC =
                route(
                        IdsUtunConnectionName
                                .defaultPairedCloud(
                                        IdsUtunConnectionName
                                                .PRIORITY_URGENT,
                                        IdsUtunConnectionName
                                                .PROTECTION_CLASS_C));
        assertEquals(
                IdsIpsecServiceRoute.Connector.CLOUD,
                classC.connector);
        assertEquals(
                61315,
                classC.listenerPort);
        assertEquals(
                3,
                classC.networkRelayDataClass);
        assertTrue(
                classC.allowsQuickRelay);

        IdsIpsecServiceRoute classD =
                route(
                        IdsUtunConnectionName
                                .defaultPairedCloud(
                                        IdsUtunConnectionName
                                                .PRIORITY_DEFAULT,
                                        IdsUtunConnectionName
                                                .PROTECTION_CLASS_D));
        assertEquals(
                61315,
                classD.listenerPort);
        assertEquals(
                4,
                classD.networkRelayDataClass);
    }

    @Test
    public void onlyTwoWildcardListenerPortsAreRequired() {
        assertEquals(
                Set.of(
                        61314,
                        61315),
                IdsIpsecServiceRoute
                        .listenerPorts());
    }

    @Test
    public void unsupportedServiceOrIdentifierFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsIpsecServiceRoute
                        .localDelivery(
                                IdsServiceConnectorName.of(
                                        "idstest",
                                        "other",
                                        "UTunDelivery-Default-Default-C")));
        assertThrows(
                IllegalArgumentException.class,
                () -> route(
                        "UTunDelivery-Default-Default"));
        assertThrows(
                IllegalArgumentException.class,
                () -> route(
                        "UTunDelivery-Default-Default-C-extra"));
    }

    private static IdsIpsecServiceRoute route(
            String name) {
        return IdsIpsecServiceRoute
                .localDelivery(
                        IdsServiceConnectorName
                                .localDelivery(
                                        name));
    }
}
