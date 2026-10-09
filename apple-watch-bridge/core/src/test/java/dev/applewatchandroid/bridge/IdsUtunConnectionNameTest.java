package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsUtunConnectionNameTest {
    @Test
    public void defaultPairedIpsecSetMatchesWatchOs262CreationOrder() {
        assertEquals(
                "UTunDelivery-Default-Urgent-C",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_URGENT,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
        assertEquals(
                "UTunDelivery-Default-Urgent-D",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_URGENT,
                        IdsUtunConnectionName.PROTECTION_CLASS_D));
        assertEquals(
                "UTunDelivery-Default-Urgent-D-Relay",
                IdsUtunConnectionName.defaultPairedRelay());
        assertEquals(
                "UTunDelivery-Default-Default-C",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
        assertEquals(
                "UTunDelivery-Default-Default-D",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName.PROTECTION_CLASS_D));
        assertEquals(
                "UTunDelivery-Default-Sync-C",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_SYNC,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
        assertEquals(
                "UTunDelivery-Default-Sync-D",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_SYNC,
                        IdsUtunConnectionName.PROTECTION_CLASS_D));

        assertTrue(
                IdsUtunConnectionName
                        .isDefaultPairedIpsecIdentifier(
                                "UTunDelivery-Default-Default-C"));
        assertFalse(
                IdsUtunConnectionName
                        .isDefaultPairedIpsecIdentifier(
                                "UTunDelivery_Default_C"));
        String defaultC =
                "idstest/localdelivery/UTunDelivery-Default-Default-C";
        assertTrue(
                defaultC.toLowerCase().contains("default-d"));
        assertTrue(
                IdsUtunConnectionName.isClassCIdentifier(defaultC));
        assertFalse(
                IdsUtunConnectionName.isClassDIdentifier(defaultC));
        assertTrue(
                IdsUtunConnectionName.isClassDIdentifier(
                        "idstest/localdelivery/UTunDelivery-Default-Urgent-D"));
        assertFalse(
                IdsUtunConnectionName.isClassCIdentifier(
                        "idstest/localdelivery/UTunDelivery-Default-Urgent-D"));
    }

    @Test
    public void cloudMarkerPrecedesProtectionSuffix() {
        assertEquals(
                "UTunDelivery-Default-UrgentCloud-C",
                IdsUtunConnectionName.defaultPairedCloud(
                        IdsUtunConnectionName.PRIORITY_URGENT,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
        assertEquals(
                "UTunDelivery-Default-DefaultCloud-D",
                IdsUtunConnectionName.defaultPairedCloud(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName.PROTECTION_CLASS_D));
        assertTrue(
                IdsUtunConnectionName
                        .isDefaultPairedCloudIdentifier(
                                "UTunDelivery-Default-UrgentCloud-C"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsUtunConnectionName
                        .defaultPairedCloud(
                                IdsUtunConnectionName.PRIORITY_SYNC,
                                IdsUtunConnectionName
                                        .PROTECTION_CLASS_C));
    }

    @Test
    public void protectionClassOneSharesTheAppleClassCSuffix() {
        assertEquals(
                "UTunDelivery-Default-Default-C",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName
                                .PROTECTION_CLASS_C_ALTERNATE));
        assertEquals(
                "UTunDelivery-Default-Default",
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName
                                .PROTECTION_CLASS_UNSUFFIXED));
    }

    @Test
    public void unsupportedInputsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsUtunConnectionName.defaultPaired(
                        999,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        999));
    }
}
