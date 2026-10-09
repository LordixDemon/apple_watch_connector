package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OpticalPairingCodeTest {
    private static final String SYNTHETIC_KEY = "0123456789abcdef".repeat(4);
    private static byte[] payload(String value) {
        return Arrays.copyOf(value.getBytes(StandardCharsets.US_ASCII), 110);
    }
    private static byte[] setup(int identifier, int compatibility, long version) {
        byte[] setup = new byte[12]; setup[0] = 0x20;
        System.arraycopy(WatchSetupMetadataCodec.encodeIdentifier(4, identifier, 19, 14), 0, setup, 1, 4);
        System.arraycopy(WatchSetupMetadataCodec.encodeExtendedMetadata(compatibility, 7, 5, false, version, null), 0, setup, 5, 7);
        return setup;
    }
    @Test public void bindsOpticalKeyToTheAdvertisedWatchAndErasesOnClose() {
        byte[] raw = payload("4--25--12345EOT--" + SYNTHETIC_KEY + "--14--19&&26.2");
        OpticalPairingCode code = OpticalPairingCode.parse(raw);
        byte[] advertisement = setup(12345, 25, 0x001a0200L);
        byte[] secret = code.boundSharedSecret(advertisement);
        assertEquals(44, secret.length);
        assertArrayEquals(advertisement, Arrays.copyOfRange(secret, 32, 44));
        assertEquals("12345EOT", code.advertisedName);
        byte[] authentication = code.authenticationData();
        assertArrayEquals(Arrays.copyOf(secret, 32), authentication);
        Arrays.fill(authentication, (byte) 0);
        assertArrayEquals(Arrays.copyOf(secret, 32), code.authenticationData());
        Arrays.fill(raw, (byte) 0);
        assertEquals(44, code.boundSharedSecret(advertisement).length);
        assertThrows(IllegalArgumentException.class, () -> code.boundSharedSecret(setup(54321, 25, 0x001a0200L)));
        assertThrows(IllegalArgumentException.class, () -> code.boundSharedSecret(setup(12345, 26, 0x001a0200L)));
        assertThrows(IllegalArgumentException.class, () -> code.boundSharedSecret(setup(12345, 25, 0x001a0300L)));
        code.close(); code.close();
        assertThrows(IllegalArgumentException.class, () -> code.boundSharedSecret(advertisement));
        assertThrows(IllegalStateException.class, code::authenticationData);
    }
    @Test public void rejectsUnsupportedProfilesAndDoesNotDisclosePayloadInErrors() {
        for (String value : new String[]{
                "3--25--12345EOT--" + SYNTHETIC_KEY + "--14--19&&26.2",
                "4--25--12345EOT--" + SYNTHETIC_KEY.substring(2) + "--14--19&&26.2",
                "4--25--12345EOT--" + "z".repeat(64) + "--14--19&&26.2",
                "4--25--12345EOT--" + SYNTHETIC_KEY + "--15--19&&26.2",
                "4--25--12345EOT--" + SYNTHETIC_KEY + "--14--19&&invalid",
                "4--25--12345EOT--" + SYNTHETIC_KEY + "--14--19--extra"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OpticalPairingCode.parse(payload(value)));
            assertFalse(error.getMessage().contains(SYNTHETIC_KEY));
        }
        assertThrows(IllegalArgumentException.class, () -> OpticalPairingCode.parse(new byte[109]));
    }
}
