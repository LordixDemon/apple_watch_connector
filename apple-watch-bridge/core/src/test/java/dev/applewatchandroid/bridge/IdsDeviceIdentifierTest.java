package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class IdsDeviceIdentifierTest {
    private static final String CURRENT = "aabbccdd-1122-4333-8444-556677889900";
    private static final String OTHER = "00112233-4455-4666-8777-8899aabbccdd";

    @Test
    public void adoptsExistingPairingOnceAndDoesNotRotateForSubsequentPairingRuns() {
        String adopted = IdsDeviceIdentifier.choose(null, CURRENT.toUpperCase(java.util.Locale.ROOT));
        assertEquals(CURRENT, adopted);
        String persisted = IdsDeviceIdentifier.decode(IdsDeviceIdentifier.encode(adopted));
        assertEquals(CURRENT, IdsDeviceIdentifier.choose(persisted, OTHER));
        assertEquals(CURRENT, IdsDeviceIdentifier.choose(persisted, null));
        assertEquals(CURRENT.toUpperCase(java.util.Locale.ROOT), IosCompanionProfile26_6.wireIdsDeviceIdentifier(persisted));
    }

    @Test
    public void corruptIdentityIsRejectedWithoutSilentlyReplacingAnExistingDevice() {
        assertThrows(IllegalArgumentException.class, () -> IdsDeviceIdentifier.choose("broken", CURRENT));
        assertThrows(IllegalArgumentException.class, () -> IdsDeviceIdentifier.decode(CURRENT.getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> IdsDeviceIdentifier.canonical("00000000-0000-0000-0000-000000000000"));
        assertThrows(IllegalArgumentException.class, () -> IdsDeviceIdentifier.canonical("1-2-3-4-5"));
    }
}
