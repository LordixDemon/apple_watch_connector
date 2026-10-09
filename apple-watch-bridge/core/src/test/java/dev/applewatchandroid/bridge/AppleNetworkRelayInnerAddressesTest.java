package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

public final class AppleNetworkRelayInnerAddressesTest {
    @Test
    public void generatesExactApplePrefixAndSharedClassSuffixes() {
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        new SecureRandom());
        byte[] initiatorD = addresses.initiatorClassD();
        byte[] responderD = addresses.responderClassD();
        byte[] initiatorC = addresses.initiatorClassC();
        byte[] responderC = addresses.responderClassC();

        assertArrayEquals(
                hex("fd 74 65 72 6d 6e 75 73 00 0d"),
                Arrays.copyOf(initiatorD, 10));
        assertArrayEquals(
                hex("fd 74 65 72 6d 6e 75 73 00 0d"),
                Arrays.copyOf(responderD, 10));
        assertArrayEquals(
                hex("fd 74 65 72 6d 6e 75 73 00 0c"),
                Arrays.copyOf(initiatorC, 10));
        assertArrayEquals(
                hex("fd 74 65 72 6d 6e 75 73 00 0c"),
                Arrays.copyOf(responderC, 10));
        assertArrayEquals(
                Arrays.copyOfRange(initiatorD, 10, 16),
                Arrays.copyOfRange(initiatorC, 10, 16));
        assertArrayEquals(
                Arrays.copyOfRange(responderD, 10, 16),
                Arrays.copyOfRange(responderC, 10, 16));
        assertFalse(Arrays.equals(initiatorD, responderD));
        for (byte[] address
                : new byte[][]{initiatorD, responderD}) {
            for (int offset = 10; offset < 16; offset += 2) {
                assertFalse(address[offset] == 0
                        && address[offset + 1] == 0);
            }
        }

        assertArrayEquals(
                initiatorD,
                addresses.localClassD(
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR));
        assertArrayEquals(
                responderD,
                addresses.remoteClassD(
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR));
        assertArrayEquals(
                responderD,
                addresses.localClassD(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER));
        assertArrayEquals(
                initiatorD,
                addresses.remoteClassD(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER));

        AppleNetworkRelayInnerAddresses restored =
                AppleNetworkRelayInnerAddresses.fromAuthoritative(
                        initiatorD,
                        responderD,
                        initiatorC,
                        responderC);
        assertArrayEquals(
                initiatorD,
                restored.initiatorClassD());
        restored.destroy();

        addresses.destroy();
        assertThrows(
                IllegalStateException.class,
                addresses::initiatorClassD);
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }
}
