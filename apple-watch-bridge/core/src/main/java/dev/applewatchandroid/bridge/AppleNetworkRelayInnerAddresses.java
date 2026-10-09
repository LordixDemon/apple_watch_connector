package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The four role-addressed private NetworkRelay inner IPv6 addresses.
 *
 * <p>iOS uses the {@code fd74:6572:6d6e:7573::/80} prefix. Class D and
 * Class C addresses for one endpoint share their final 48 bits and differ
 * only in prefix byte 9 ({@code 0x0d} versus {@code 0x0c}). The stable wire
 * names are initiator/responder; local/remote are projections of the
 * elected IKE role.</p>
 */
final class AppleNetworkRelayInnerAddresses {
    static final int ADDRESS_LENGTH = 16;

    private static final byte[] PREFIX = new byte[]{
            (byte) 0xfd, 0x74, 0x65, 0x72,
            0x6d, 0x6e, 0x75, 0x73,
            0x00, 0x0d
    };
    private static final int HOST_OFFSET = 10;
    private static final int HOST_LENGTH = 6;
    static final byte CLASS_D_MARKER = 0x0d;
    static final byte CLASS_C_MARKER = 0x0c;

    private final byte[] initiatorClassD;
    private final byte[] responderClassD;
    private final byte[] initiatorClassC;
    private final byte[] responderClassC;
    private boolean destroyed;

    private AppleNetworkRelayInnerAddresses(
            byte[] initiatorClassD,
            byte[] responderClassD) {
        validateClassD(
                "initiator Class D",
                initiatorClassD);
        validateClassD(
                "responder Class D",
                responderClassD);
        if (Arrays.equals(
                initiatorClassD,
                responderClassD)) {
            throw new IllegalArgumentException(
                    "Initiator and responder addresses must differ");
        }
        this.initiatorClassD = initiatorClassD.clone();
        this.responderClassD = responderClassD.clone();
        this.initiatorClassC =
                classCFromClassD(initiatorClassD);
        this.responderClassC =
                classCFromClassD(responderClassD);
    }

    /**
     * Creates a provisional four-address set. It becomes authoritative only
     * if the local endpoint is elected IKE responder.
     */
    static AppleNetworkRelayInnerAddresses generate(
            SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        byte[] responder = generateClassD(random);
        byte[] initiator;
        do {
            initiator = generateClassD(random);
        } while (Arrays.equals(responder, initiator));
        try {
            return new AppleNetworkRelayInnerAddresses(
                    initiator,
                    responder);
        } finally {
            wipe(initiator);
            wipe(responder);
        }
    }

    static AppleNetworkRelayInnerAddresses fromAuthoritative(
            byte[] initiatorClassD,
            byte[] responderClassD,
            byte[] initiatorClassC,
            byte[] responderClassC) {
        AppleNetworkRelayInnerAddresses addresses =
                new AppleNetworkRelayInnerAddresses(
                        initiatorClassD,
                        responderClassD);
        byte[] expectedInitiatorC =
                addresses.initiatorClassC();
        byte[] expectedResponderC =
                addresses.responderClassC();
        try {
            if (!Arrays.equals(
                    expectedInitiatorC,
                    initiatorClassC)
                    || !Arrays.equals(
                    expectedResponderC,
                    responderClassC)) {
                addresses.destroy();
                throw new IllegalArgumentException(
                        "Class C addresses must preserve the "
                                + "corresponding Class D host suffix");
            }
            return addresses;
        } finally {
            wipe(expectedInitiatorC);
            wipe(expectedResponderC);
        }
    }

    byte[] initiatorClassD() {
        requireLive();
        return initiatorClassD.clone();
    }

    byte[] responderClassD() {
        requireLive();
        return responderClassD.clone();
    }

    byte[] initiatorClassC() {
        requireLive();
        return initiatorClassC.clone();
    }

    byte[] responderClassC() {
        requireLive();
        return responderClassC.clone();
    }

    byte[] localClassD(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireRole(role);
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? initiatorClassD()
                : responderClassD();
    }

    byte[] remoteClassD(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireRole(role);
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? responderClassD()
                : initiatorClassD();
    }

    byte[] localClassC(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireRole(role);
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? initiatorClassC()
                : responderClassC();
    }

    byte[] remoteClassC(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireRole(role);
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? responderClassC()
                : initiatorClassC();
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        wipe(initiatorClassD);
        wipe(responderClassD);
        wipe(initiatorClassC);
        wipe(responderClassC);
        destroyed = true;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "NetworkRelay inner addresses "
                            + "have been destroyed");
        }
    }

    private static byte[] generateClassD(
            SecureRandom random) {
        byte[] address = new byte[ADDRESS_LENGTH];
        byte[] host = new byte[HOST_LENGTH];
        System.arraycopy(
                PREFIX,
                0,
                address,
                0,
                PREFIX.length);
        try {
            do {
                random.nextBytes(host);
                System.arraycopy(
                        host,
                        0,
                        address,
                        HOST_OFFSET,
                        HOST_LENGTH);
            } while (!hostWordsAreNonzero(address));
            return address;
        } finally {
            wipe(host);
        }
    }

    private static boolean hostWordsAreNonzero(
            byte[] address) {
        for (int offset = HOST_OFFSET;
                offset < ADDRESS_LENGTH;
                offset += 2) {
            if (address[offset] == 0
                    && address[offset + 1] == 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] classCFromClassD(
            byte[] classD) {
        if (classD == null
                || classD.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    "Class D inner address must be 16 bytes");
        }
        byte[] classC = classD.clone();
        classC[9] = CLASS_C_MARKER;
        return classC;
    }

    private static void validateClassD(
            String label,
            byte[] address) {
        if (address == null
                || address.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    label + " address must be 16 bytes");
        }
        for (int index = 0; index < PREFIX.length; index++) {
            if (address[index] != PREFIX[index]) {
                throw new IllegalArgumentException(
                        label + " address has an invalid Apple prefix");
            }
        }
        if (!hostWordsAreNonzero(address)
                && !isDeterministicHostSuffix(address)) {
            throw new IllegalArgumentException(
                    label + " address has an invalid host suffix");
        }
    }

    private static boolean isDeterministicHostSuffix(
            byte[] address) {
        for (int index = HOST_OFFSET;
                index < ADDRESS_LENGTH - 1;
                index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        int finalByte =
                address[ADDRESS_LENGTH - 1] & 0xff;
        return finalByte == 1 || finalByte == 2;
    }

    private static void requireRole(
            NrLinkBluetoothPrelude.LocalRole role) {
        if (role == null) {
            throw new IllegalArgumentException(
                    "IKE role is required");
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
