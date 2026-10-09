package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Ephemeral companion identity and modern NetworkRelay Class C/D key material.
 *
 * <p>The public wire layouts match iOS 26.6 {@code terminusd}:
 * C4AF is UUID[16] followed by a raw X25519 public key; C548/C549 each contain
 * an NRTLV type 1 raw Ed25519 public key and an NRTLV type 2 raw X25519 public
 * key. Private values remain in this owner until explicit destruction.</p>
 */
final class AppleNetworkRelayPairingMaterial {
    static final int RAW_KEY_LENGTH = 32;
    static final int IDENTITY_PAYLOAD_LENGTH = 48;
    static final int MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH = 70;
    static final int PRIVATE_SNAPSHOT_LENGTH =
            16
                    + RAW_KEY_LENGTH * 2
                    + RAW_KEY_LENGTH * 4
                    + RAW_KEY_LENGTH * 4;

    private static final int NRTLV_PUBLIC_ED25519 = 1;
    private static final int NRTLV_PUBLIC_X25519 = 2;

    private final byte[] identityUuid;
    private final byte[] identityPrivateX25519;
    private final byte[] identityPublicX25519;
    private final DataClassMaterial classD;
    private final DataClassMaterial classC;
    private boolean destroyed;

    private AppleNetworkRelayPairingMaterial(
            byte[] identityUuid,
            byte[] identityPrivateX25519,
            byte[] identityPublicX25519,
            DataClassMaterial classD,
            DataClassMaterial classC) {
        this.identityUuid = identityUuid.clone();
        this.identityPrivateX25519 =
                identityPrivateX25519.clone();
        this.identityPublicX25519 =
                identityPublicX25519.clone();
        this.classD = classD;
        this.classC = classC;
    }

    static AppleNetworkRelayPairingMaterial generate(
            SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        byte[] uuid = uuidBytes(UUID.randomUUID());
        X25519KeyPair identity =
                generateX25519(random);
        DataClassMaterial classD =
                DataClassMaterial.generate(random);
        DataClassMaterial classC =
                DataClassMaterial.generate(random);
        try {
            return new AppleNetworkRelayPairingMaterial(
                    uuid,
                    identity.privateKey,
                    identity.publicKey,
                    classD,
                    classC);
        } finally {
            wipe(uuid);
            identity.destroy();
        }
    }

    static AppleNetworkRelayPairingMaterial restorePrivateSnapshot(
            byte[] snapshot) {
        if (snapshot == null
                || snapshot.length != PRIVATE_SNAPSHOT_LENGTH) {
            throw new IllegalArgumentException(
                    "NetworkRelay private snapshot has an invalid length");
        }
        int offset = 0;
        byte[] identityUuid =
                slice(snapshot, offset, 16);
        offset += identityUuid.length;
        byte[] identityPrivate =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += identityPrivate.length;
        byte[] identityPublic =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += identityPublic.length;
        byte[] classDPrivateEd =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classDPrivateEd.length;
        byte[] classDPublicEd =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classDPublicEd.length;
        byte[] classDPrivateX =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classDPrivateX.length;
        byte[] classDPublicX =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classDPublicX.length;
        byte[] classCPrivateEd =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classCPrivateEd.length;
        byte[] classCPublicEd =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classCPublicEd.length;
        byte[] classCPrivateX =
                slice(snapshot, offset, RAW_KEY_LENGTH);
        offset += classCPrivateX.length;
        byte[] classCPublicX =
                slice(snapshot, offset, RAW_KEY_LENGTH);

        DataClassMaterial classD = null;
        DataClassMaterial classC = null;
        try {
            requireNonzero(
                    "NetworkRelay identity UUID",
                    identityUuid);
            requireMatchingX25519Pair(
                    "identity",
                    identityPrivate,
                    identityPublic);
            classD = DataClassMaterial.restore(
                    classDPrivateEd,
                    classDPublicEd,
                    classDPrivateX,
                    classDPublicX);
            classC = DataClassMaterial.restore(
                    classCPrivateEd,
                    classCPublicEd,
                    classCPrivateX,
                    classCPublicX);
            AppleNetworkRelayPairingMaterial restored =
                    new AppleNetworkRelayPairingMaterial(
                            identityUuid,
                            identityPrivate,
                            identityPublic,
                            classD,
                            classC);
            classD = null;
            classC = null;
            return restored;
        } finally {
            if (classD != null) {
                classD.destroy();
            }
            if (classC != null) {
                classC.destroy();
            }
            wipe(identityUuid);
            wipe(identityPrivate);
            wipe(identityPublic);
            wipe(classDPrivateEd);
            wipe(classDPublicEd);
            wipe(classDPrivateX);
            wipe(classDPublicX);
            wipe(classCPrivateEd);
            wipe(classCPublicEd);
            wipe(classCPrivateX);
            wipe(classCPublicX);
        }
    }

    byte[] privateSnapshot() {
        requireLive();
        byte[] output =
                new byte[PRIVATE_SNAPSHOT_LENGTH];
        int offset = 0;
        offset = put(output, offset, identityUuid);
        offset = put(
                output,
                offset,
                identityPrivateX25519);
        offset = put(
                output,
                offset,
                identityPublicX25519);
        offset = classD.putPrivateSnapshot(
                output,
                offset);
        offset = classC.putPrivateSnapshot(
                output,
                offset);
        if (offset != output.length) {
            wipe(output);
            throw new IllegalStateException(
                    "NetworkRelay private snapshot size mismatch");
        }
        return output;
    }

    byte[] identityPayload() {
        requireLive();
        byte[] output =
                new byte[IDENTITY_PAYLOAD_LENGTH];
        System.arraycopy(
                identityUuid,
                0,
                output,
                0,
                identityUuid.length);
        System.arraycopy(
                identityPublicX25519,
                0,
                output,
                identityUuid.length,
                identityPublicX25519.length);
        return output;
    }

    byte[] identityUuid() {
        requireLive();
        return identityUuid.clone();
    }

    byte[] openEncryptedIdentity(
            String serviceKeyId,
            byte[] initiatorNonce,
            byte[] responderNonce,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptedIdentifier) {
        requireLive();
        return AppleIkeEncryptedKeyId.open(
                identityPrivateX25519,
                serviceKeyId,
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                responderSpi,
                encryptedIdentifier);
    }

    byte[] classDPublicKeysPayload() {
        requireLive();
        return classD.publicKeysPayload();
    }

    byte[] classCPublicKeysPayload() {
        requireLive();
        return classC.publicKeysPayload();
    }

    byte[] calculateIdentitySharedSecret(
            byte[] peerPublicX25519) {
        requireLive();
        return calculateX25519(
                identityPrivateX25519,
                peerPublicX25519);
    }

    byte[] calculateClassDSharedSecret(
            byte[] peerPublicX25519) {
        requireLive();
        return classD.calculateSharedSecret(
                peerPublicX25519);
    }

    byte[] calculateClassCSharedSecret(
            byte[] peerPublicX25519) {
        requireLive();
        return classC.calculateSharedSecret(
                peerPublicX25519);
    }

    byte[] signClassD(byte[] message) {
        requireLive();
        return classD.sign(message);
    }

    byte[] signClassC(byte[] message) {
        requireLive();
        return classC.sign(message);
    }

    byte[] classDPrivateEd25519() {
        requireLive();
        return classD.privateEd25519.clone();
    }

    byte[] classCPrivateEd25519() {
        requireLive();
        return classC.privateEd25519.clone();
    }

    static byte[] extractEd25519PublicKey(byte[] modernPublicKeysPayload) {
        if (modernPublicKeysPayload == null
                || modernPublicKeysPayload.length < 35
                || modernPublicKeysPayload[0] != 1
                || modernPublicKeysPayload[1] != 0
                || modernPublicKeysPayload[2] != 32) {
            return null;
        }
        return Arrays.copyOfRange(modernPublicKeysPayload, 3, 35);
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        wipe(identityUuid);
        wipe(identityPrivateX25519);
        wipe(identityPublicX25519);
        classD.destroy();
        classC.destroy();
        destroyed = true;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "NetworkRelay pairing material "
                            + "has been destroyed");
        }
    }

    private static byte[] buildModernPublicKeysPayload(
            byte[] publicEd25519,
            byte[] publicX25519) {
        requireRawKey(
                "public Ed25519 key",
                publicEd25519);
        requireRawKey(
                "public X25519 key",
                publicX25519);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH);
        addNrTlv(
                output,
                NRTLV_PUBLIC_ED25519,
                publicEd25519);
        addNrTlv(
                output,
                NRTLV_PUBLIC_X25519,
                publicX25519);
        byte[] payload = output.toByteArray();
        if (payload.length
                != MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH) {
            wipe(payload);
            throw new IllegalStateException(
                    "Unexpected modern public-key payload length");
        }
        return payload;
    }

    private static void addNrTlv(
            ByteArrayOutputStream output,
            int type,
            byte[] value) {
        output.write(type);
        output.write((value.length >>> 8) & 0xff);
        output.write(value.length & 0xff);
        output.writeBytes(value);
    }

    private static X25519KeyPair generateX25519(
            SecureRandom random) {
        X25519KeyPairGenerator generator =
                new X25519KeyPairGenerator();
        generator.init(
                new X25519KeyGenerationParameters(
                        random));
        AsymmetricCipherKeyPair pair =
                generator.generateKeyPair();
        X25519PrivateKeyParameters privateKey =
                (X25519PrivateKeyParameters) pair.getPrivate();
        X25519PublicKeyParameters publicKey =
                (X25519PublicKeyParameters) pair.getPublic();
        return new X25519KeyPair(
                privateKey.getEncoded(),
                publicKey.getEncoded());
    }

    private static byte[] calculateX25519(
            byte[] privateKey,
            byte[] peerPublicKey) {
        requireRawKey(
                "private X25519 key",
                privateKey);
        requireRawKey(
                "peer public X25519 key",
                peerPublicKey);
        byte[] secret = new byte[RAW_KEY_LENGTH];
        X25519PrivateKeyParameters local =
                new X25519PrivateKeyParameters(
                        privateKey,
                        0);
        X25519PublicKeyParameters peer =
                new X25519PublicKeyParameters(
                        peerPublicKey,
                        0);
        try {
            local.generateSecret(
                    peer,
                    secret,
                    0);
        } catch (IllegalStateException error) {
            wipe(secret);
            throw new IllegalArgumentException(
                    "peer X25519 public key produces "
                            + "an invalid shared secret",
                    error);
        }
        if (allZero(secret)) {
            wipe(secret);
            throw new IllegalArgumentException(
                    "peer X25519 public key produces "
                            + "an all-zero shared secret");
        }
        return secret;
    }

    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static int put(
            byte[] output,
            int offset,
            byte[] value) {
        System.arraycopy(
                value,
                0,
                output,
                offset,
                value.length);
        return offset + value.length;
    }

    private static byte[] slice(
            byte[] input,
            int offset,
            int length) {
        return Arrays.copyOfRange(
                input,
                offset,
                offset + length);
    }

    private static void requireMatchingX25519Pair(
            String label,
            byte[] privateKey,
            byte[] publicKey) {
        requireRawKey(
                label + " private X25519 key",
                privateKey);
        requireRawKey(
                label + " public X25519 key",
                publicKey);
        byte[] derived =
                new X25519PrivateKeyParameters(
                        privateKey,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        try {
            if (!Arrays.equals(derived, publicKey)) {
                throw new IllegalArgumentException(
                        label + " X25519 key pair does not match");
            }
        } finally {
            wipe(derived);
        }
    }

    private static void requireMatchingEd25519Pair(
            String label,
            byte[] privateKey,
            byte[] publicKey) {
        requireRawKey(
                label + " private Ed25519 key",
                privateKey);
        requireRawKey(
                label + " public Ed25519 key",
                publicKey);
        byte[] derived =
                new Ed25519PrivateKeyParameters(
                        privateKey,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        try {
            if (!Arrays.equals(derived, publicKey)) {
                throw new IllegalArgumentException(
                        label + " Ed25519 key pair does not match");
            }
        } finally {
            wipe(derived);
        }
    }

    private static void requireNonzero(
            String label,
            byte[] value) {
        if (value == null || allZero(value)) {
            throw new IllegalArgumentException(
                    label + " must not be all zero");
        }
    }

    private static void requireRawKey(
            String name,
            byte[] value) {
        if (value == null
                || value.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be exactly "
                            + RAW_KEY_LENGTH
                            + " bytes");
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static boolean allZero(byte[] value) {
        int accumulator = 0;
        for (byte item : value) {
            accumulator |= item & 0xff;
        }
        return accumulator == 0;
    }

    private static final class X25519KeyPair {
        final byte[] privateKey;
        final byte[] publicKey;

        X25519KeyPair(
                byte[] privateKey,
                byte[] publicKey) {
            this.privateKey = privateKey.clone();
            this.publicKey = publicKey.clone();
        }

        void destroy() {
            wipe(privateKey);
            wipe(publicKey);
        }
    }

    private static final class DataClassMaterial {
        private final byte[] privateEd25519;
        private final byte[] publicEd25519;
        private final byte[] privateX25519;
        private final byte[] publicX25519;
        private boolean destroyed;

        private DataClassMaterial(
                byte[] privateEd25519,
                byte[] publicEd25519,
                byte[] privateX25519,
                byte[] publicX25519) {
            this.privateEd25519 =
                    privateEd25519.clone();
            this.publicEd25519 =
                    publicEd25519.clone();
            this.privateX25519 =
                    privateX25519.clone();
            this.publicX25519 =
                    publicX25519.clone();
        }

        static DataClassMaterial generate(
                SecureRandom random) {
            Ed25519KeyPairGenerator edGenerator =
                    new Ed25519KeyPairGenerator();
            edGenerator.init(
                    new Ed25519KeyGenerationParameters(
                            random));
            AsymmetricCipherKeyPair edPair =
                    edGenerator.generateKeyPair();
            Ed25519PrivateKeyParameters edPrivate =
                    (Ed25519PrivateKeyParameters)
                            edPair.getPrivate();
            Ed25519PublicKeyParameters edPublic =
                    (Ed25519PublicKeyParameters)
                            edPair.getPublic();
            X25519KeyPair xPair =
                    generateX25519(random);
            try {
                return new DataClassMaterial(
                        edPrivate.getEncoded(),
                        edPublic.getEncoded(),
                        xPair.privateKey,
                        xPair.publicKey);
            } finally {
                xPair.destroy();
            }
        }

        static DataClassMaterial restore(
                byte[] privateEd25519,
                byte[] publicEd25519,
                byte[] privateX25519,
                byte[] publicX25519) {
            requireMatchingEd25519Pair(
                    "data-class",
                    privateEd25519,
                    publicEd25519);
            requireMatchingX25519Pair(
                    "data-class",
                    privateX25519,
                    publicX25519);
            return new DataClassMaterial(
                    privateEd25519,
                    publicEd25519,
                    privateX25519,
                    publicX25519);
        }

        int putPrivateSnapshot(
                byte[] output,
                int offset) {
            requireLive();
            offset = put(
                    output,
                    offset,
                    privateEd25519);
            offset = put(
                    output,
                    offset,
                    publicEd25519);
            offset = put(
                    output,
                    offset,
                    privateX25519);
            return put(
                    output,
                    offset,
                    publicX25519);
        }

        byte[] publicKeysPayload() {
            requireLive();
            return buildModernPublicKeysPayload(
                    publicEd25519,
                    publicX25519);
        }

        byte[] calculateSharedSecret(
                byte[] peerPublicX25519) {
            requireLive();
            return calculateX25519(
                    privateX25519,
                    peerPublicX25519);
        }

        byte[] sign(byte[] message) {
            requireLive();
            if (message == null) {
                throw new IllegalArgumentException(
                        "message is required");
            }
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(
                    true,
                    new Ed25519PrivateKeyParameters(
                            privateEd25519,
                            0));
            signer.update(
                    message,
                    0,
                    message.length);
            return signer.generateSignature();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(privateEd25519);
            wipe(publicEd25519);
            wipe(privateX25519);
            wipe(publicX25519);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "NetworkRelay data-class keys "
                                + "have been destroyed");
            }
        }
    }
}
