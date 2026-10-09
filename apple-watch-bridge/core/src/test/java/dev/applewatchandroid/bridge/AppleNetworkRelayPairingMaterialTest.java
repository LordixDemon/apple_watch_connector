package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

public final class AppleNetworkRelayPairingMaterialTest {
    @Test
    public void payloadsMatchIosModernWireLayouts() {
        AppleNetworkRelayPairingMaterial material =
                AppleNetworkRelayPairingMaterial.generate(
                        new SecureRandom());
        try {
            byte[] identity = material.identityPayload();
            assertEquals(
                    AppleNetworkRelayPairingMaterial
                            .IDENTITY_PAYLOAD_LENGTH,
                    identity.length);
            assertFalse(allZero(Arrays.copyOfRange(
                    identity,
                    0,
                    16)));
            assertFalse(allZero(Arrays.copyOfRange(
                    identity,
                    16,
                    48)));

            assertModernPublicKeys(
                    material.classDPublicKeysPayload());
            assertModernPublicKeys(
                    material.classCPublicKeysPayload());
        } finally {
            material.destroy();
        }
        assertTrue(material.isDestroyed());
        assertThrows(
                IllegalStateException.class,
                material::identityPayload);
    }

    @Test
    public void classKeysAgreeAndSign() {
        AppleNetworkRelayPairingMaterial first =
                AppleNetworkRelayPairingMaterial.generate(
                        new SecureRandom());
        AppleNetworkRelayPairingMaterial second =
                AppleNetworkRelayPairingMaterial.generate(
                        new SecureRandom());
        byte[] message =
                "NetworkRelay pairing proof"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            byte[] firstD =
                    first.classDPublicKeysPayload();
            byte[] secondD =
                    second.classDPublicKeysPayload();
            byte[] firstDX = Arrays.copyOfRange(
                    firstD,
                    38,
                    70);
            byte[] secondDX = Arrays.copyOfRange(
                    secondD,
                    38,
                    70);
            assertArrayEquals(
                    first.calculateClassDSharedSecret(secondDX),
                    second.calculateClassDSharedSecret(firstDX));

            byte[] signature = first.signClassD(message);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(
                    false,
                    new Ed25519PublicKeyParameters(
                            firstD,
                            3));
            verifier.update(
                    message,
                    0,
                    message.length);
            assertTrue(verifier.verifySignature(signature));
        } finally {
            first.destroy();
            second.destroy();
        }
    }

    @Test
    public void rejectsAllZeroX25519Peer() {
        AppleNetworkRelayPairingMaterial material =
                AppleNetworkRelayPairingMaterial.generate(
                        new SecureRandom());
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> material.calculateClassCSharedSecret(
                            new byte[
                                    AppleNetworkRelayPairingMaterial
                                            .RAW_KEY_LENGTH]));
        } finally {
            material.destroy();
        }
    }

    @Test
    public void privateSnapshotRestoresExactLongLivedIdentity() {
        AppleNetworkRelayPairingMaterial original =
                AppleNetworkRelayPairingMaterial.generate(
                        new SecureRandom());
        byte[] snapshot = original.privateSnapshot();
        AppleNetworkRelayPairingMaterial restored =
                AppleNetworkRelayPairingMaterial
                        .restorePrivateSnapshot(
                                snapshot);
        try {
            assertEquals(
                    AppleNetworkRelayPairingMaterial
                            .PRIVATE_SNAPSHOT_LENGTH,
                    snapshot.length);
            assertArrayEquals(
                    original.identityPayload(),
                    restored.identityPayload());
            assertArrayEquals(
                    original.classDPublicKeysPayload(),
                    restored.classDPublicKeysPayload());
            assertArrayEquals(
                    original.classCPublicKeysPayload(),
                    restored.classCPublicKeysPayload());
            byte[] peerPublic = Arrays.copyOfRange(
                    original.classDPublicKeysPayload(),
                    38,
                    70);
            assertArrayEquals(
                    original.calculateClassDSharedSecret(
                            peerPublic),
                    restored.calculateClassDSharedSecret(
                            peerPublic));

            byte[] corrupt = snapshot.clone();
            corrupt[20] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AppleNetworkRelayPairingMaterial
                            .restorePrivateSnapshot(
                                    corrupt));
            Arrays.fill(corrupt, (byte) 0);
        } finally {
            Arrays.fill(snapshot, (byte) 0);
            restored.destroy();
            original.destroy();
        }
    }

    private static void assertModernPublicKeys(
            byte[] payload) {
        assertEquals(
                AppleNetworkRelayPairingMaterial
                        .MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH,
                payload.length);
        assertEquals(1, payload[0] & 0xff);
        assertEquals(32, be16(payload, 1));
        assertEquals(2, payload[35] & 0xff);
        assertEquals(32, be16(payload, 36));
        assertFalse(allZero(Arrays.copyOfRange(
                payload,
                3,
                35)));
        assertFalse(allZero(Arrays.copyOfRange(
                payload,
                38,
                70)));
    }

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }
}
