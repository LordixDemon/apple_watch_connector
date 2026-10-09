package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsServiceConnectorNameTest {
    private static final String RUNTIME_NAME =
            "UTunDelivery-Default-Default-C";

    @Test
    public void localDeliveryMatchesRecoveredIdsComposite() {
        IdsServiceConnectorName value =
                IdsServiceConnectorName.localDelivery(
                        RUNTIME_NAME);

        assertEquals(
                "idstest/localdelivery/"
                        + "UTunDelivery-Default-Default-C",
                value.encode());
        assertTrue(
                value.isLocalDelivery());
        assertEquals(
                value,
                IdsServiceConnectorName.parseCanonical(
                        value.encode()));
    }

    @Test
    public void appleReceiverUsesFirstThreeComponents() {
        IdsServiceConnectorName value =
                IdsServiceConnectorName
                        .parseAppleCompatiblePrefix(
                                "idstest/localdelivery/"
                                        + RUNTIME_NAME
                                        + "/ignored");

        assertEquals(
                RUNTIME_NAME,
                value.name);
        assertEquals(
                "idstest/localdelivery/"
                        + "UTunDelivery-Default-Default-C",
                value.encode());
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName
                        .parseCanonical(
                                "idstest/localdelivery/"
                                        + RUNTIME_NAME
                                        + "/ignored"));
    }

    @Test
    public void registryTopicCannotBeMistakenForCompositeServiceName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName
                        .parseCanonical(
                                NanoRegistryPropertyCodec
                                        .CLASS_D_SERVICE));
        assertFalse(
                IdsServiceConnectorName.of(
                                "com.apple.private.alloy",
                                "bluetoothregistry",
                                "stream")
                        .isLocalDelivery());
    }

    @Test
    public void malformedOrAmbiguousOutboundNamesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName.of(
                        "idstest",
                        "local/delivery",
                        RUNTIME_NAME));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName
                        .parseAppleCompatiblePrefix(
                                "idstest/localdelivery"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName
                        .parseCanonical(
                                "idstest//"
                                        + RUNTIME_NAME));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsServiceConnectorName
                        .parseCanonical(
                                "idstest/localdelivery/"
                                        + RUNTIME_NAME
                                        + "\0"));
    }
}
