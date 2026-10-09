package dev.applewatchandroid.bridge;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Apple CryptoKitPrivate-compatible SPAKE2+ P-256 Prover used by the
 * secure-password IKEv2 authentication method.
 *
 * <p>The Apple {@code seed:...salt:...context:} constructor is deliberately
 * different from its RFC-compliant constructor. It expands the seed to two
 * 40-byte values and applies the legacy corecrypto FIPS extra-bits reduction:
 * {@code (OS2IP(value) mod (n - 1)) + 1}. The SPAKE2+ transcript and key
 * schedule themselves follow RFC 9383.</p>
 */
final class AppleSpake2PlusProver {
    static final int SHARE_LENGTH = 65;
    static final int CONFIRMATION_TAG_LENGTH = 32;
    static final int SHARED_KEY_LENGTH = 32;
    static final int MINIMUM_SEED_LENGTH = 16;

    private static final int SCALAR_LENGTH = 32;
    private static final int LEGACY_SCALAR_INPUT_LENGTH = 40;
    private static final byte[] APPLE_DERIVATION_INFO =
            "SPAKE2+ Authentication for IKEv2"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONFIRMATION_KEYS_INFO =
            "ConfirmationKeys".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHARED_KEY_INFO =
            "SharedKey".getBytes(StandardCharsets.US_ASCII);
    private static final X9ECParameters P256 =
            CustomNamedCurves.getByName("secp256r1");
    private static final BigInteger ORDER = P256.getN();
    private static final ECPoint M = decodeFixedPoint(
            "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3d"
                    + "cab95afd497333d8fa12f");
    private static final ECPoint N = decodeFixedPoint(
            "03d8bbd6c639c62937b04d997f38c3770719c629d701"
                    + "4d49a24b4f98baa1292b49");
    private static final byte[] M_UNCOMPRESSED = M.getEncoded(false);
    private static final byte[] N_UNCOMPRESSED = N.getEncoded(false);

    private final BigInteger w0;
    private final BigInteger w1;
    private final BigInteger ephemeralScalar;
    private final byte[] context;
    private final byte[] proverId;
    private final byte[] verifierId;
    private final byte[] proverShare;

    private byte[] expectedVerifierTag;
    private byte[] sharedKey;
    private boolean peerShareProcessed;
    private boolean finished;

    private AppleSpake2PlusProver(
            BigInteger w0,
            BigInteger w1,
            BigInteger ephemeralScalar,
            byte[] context,
            byte[] proverId,
            byte[] verifierId) {
        this.w0 = w0;
        this.w1 = w1;
        this.ephemeralScalar = ephemeralScalar;
        this.context = copyRequired("context", context);
        this.proverId = copyRequired("Prover ID", proverId);
        this.verifierId = copyRequired("Verifier ID", verifierId);

        ECPoint share = P256.getG()
                .multiply(ephemeralScalar)
                .add(M.multiply(w0))
                .normalize();
        if (share.isInfinity()) {
            throw new IllegalArgumentException(
                    "SPAKE2+ Prover share is the identity");
        }
        this.proverShare = share.getEncoded(false);
    }

    /**
     * Creates the OnePlus/initiator-side Prover using the legacy Apple
     * CryptoKitPrivate seed constructor recovered from NetworkExtension.
     */
    static AppleSpake2PlusProver create(
            SecureRandom random,
            byte[] seed,
            byte[] salt,
            byte[] context,
            byte[] initiatorId,
            byte[] responderId) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        LegacyScalars scalars = deriveLegacyScalars(seed, salt);
        try {
            return new AppleSpake2PlusProver(
                    scalarFromBytes("w0", scalars.w0, false),
                    scalarFromBytes("w1", scalars.w1, false),
                    randomScalar(random),
                    context,
                    initiatorId,
                    responderId);
        } finally {
            scalars.destroy();
        }
    }

    /**
     * Deterministic constructor used only for published interoperability
     * vectors. It must never be used for a live exchange.
     */
    static AppleSpake2PlusProver forTest(
            byte[] w0,
            byte[] w1,
            byte[] ephemeralScalar,
            byte[] context,
            byte[] proverId,
            byte[] verifierId) {
        return new AppleSpake2PlusProver(
                scalarFromBytes("w0", w0, true),
                scalarFromBytes("w1", w1, true),
                scalarFromBytes(
                        "ephemeral scalar",
                        ephemeralScalar,
                        true),
                context,
                proverId,
                verifierId);
    }

    byte[] getShare() {
        if (finished) {
            throw new IllegalStateException(
                    "SPAKE2+ state has been destroyed");
        }
        return proverShare.clone();
    }

    /**
     * Processes the Watch's uncompressed P-256 share and returns the Prover
     * confirmation tag. The Watch's confirmation tag must still be checked
     * with {@link #processPeerVerificationTagAndGenerateKey(byte[])}.
     */
    byte[] processPeerShareAndGenerateVerificationTag(
            byte[] verifierShareBytes) {
        if (finished || peerShareProcessed) {
            throw new IllegalStateException(
                    "SPAKE2+ peer share was already processed");
        }
        ECPoint verifierShare =
                decodePeerShare(verifierShareBytes);
        ECPoint adjusted = verifierShare
                .subtract(N.multiply(w0))
                .normalize();
        if (adjusted.isInfinity()) {
            throw new IllegalArgumentException(
                    "SPAKE2+ adjusted peer share is the identity");
        }

        ECPoint z = adjusted
                .multiply(ephemeralScalar)
                .normalize();
        ECPoint v = adjusted
                .multiply(w1)
                .normalize();
        if (z.isInfinity() || v.isInfinity()) {
            throw new IllegalArgumentException(
                    "SPAKE2+ peer share produced an invalid secret");
        }

        byte[] transcript = buildTranscript(
                context,
                proverId,
                verifierId,
                M_UNCOMPRESSED,
                N_UNCOMPRESSED,
                proverShare,
                verifierShare.getEncoded(false),
                z.getEncoded(false),
                v.getEncoded(false),
                scalarToBytes(w0));
        byte[] mainKey = sha256(transcript);
        byte[] confirmationKeys = hkdfSha256(
                mainKey,
                new byte[0],
                CONFIRMATION_KEYS_INFO,
                2 * CONFIRMATION_TAG_LENGTH);
        byte[] proverConfirmationKey = Arrays.copyOfRange(
                confirmationKeys,
                0,
                CONFIRMATION_TAG_LENGTH);
        byte[] verifierConfirmationKey = Arrays.copyOfRange(
                confirmationKeys,
                CONFIRMATION_TAG_LENGTH,
                2 * CONFIRMATION_TAG_LENGTH);
        byte[] proverTag = hmacSha256(
                proverConfirmationKey,
                verifierShare.getEncoded(false));
        expectedVerifierTag = hmacSha256(
                verifierConfirmationKey,
                proverShare);
        sharedKey = hkdfSha256(
                mainKey,
                new byte[0],
                SHARED_KEY_INFO,
                SHARED_KEY_LENGTH);
        peerShareProcessed = true;

        Arrays.fill(transcript, (byte) 0);
        Arrays.fill(mainKey, (byte) 0);
        Arrays.fill(confirmationKeys, (byte) 0);
        Arrays.fill(proverConfirmationKey, (byte) 0);
        Arrays.fill(verifierConfirmationKey, (byte) 0);
        return proverTag;
    }

    /**
     * Verifies the Watch's confirmation tag in constant time and releases the
     * authenticated SPAKE2+ shared key exactly once.
     */
    byte[] processPeerVerificationTagAndGenerateKey(
            byte[] verifierTag) {
        if (finished || !peerShareProcessed) {
            throw new IllegalStateException(
                    "SPAKE2+ peer share has not been processed");
        }
        if (verifierTag == null
                || verifierTag.length != CONFIRMATION_TAG_LENGTH
                || !MessageDigest.isEqual(
                expectedVerifierTag,
                verifierTag)) {
            destroy();
            throw new SecurityException(
                    "SPAKE2+ Watch confirmation tag mismatch");
        }
        byte[] result = sharedKey.clone();
        destroy();
        return result;
    }

    void destroy() {
        wipe(expectedVerifierTag);
        wipe(sharedKey);
        expectedVerifierTag = null;
        sharedKey = null;
        finished = true;
    }

    /**
     * Reproduces {@code _ccspake_reduce_w}, not RFC 9383's direct modulo-n
     * reduction used by Apple's separate rfcCompliant constructor.
     */
    static LegacyScalars deriveLegacyScalars(
            byte[] seed,
            byte[] salt) {
        if (seed == null || seed.length < MINIMUM_SEED_LENGTH) {
            throw new IllegalArgumentException(
                    "Apple SPAKE2+ seed must be at least 16 bytes");
        }
        if (salt == null) {
            throw new IllegalArgumentException(
                    "Apple SPAKE2+ salt is required");
        }
        byte[] expanded = hkdfSha256(
                seed,
                salt,
                APPLE_DERIVATION_INFO,
                2 * LEGACY_SCALAR_INPUT_LENGTH);
        try {
            return new LegacyScalars(
                    reduceLegacy(Arrays.copyOfRange(
                            expanded,
                            0,
                            LEGACY_SCALAR_INPUT_LENGTH)),
                    reduceLegacy(Arrays.copyOfRange(
                            expanded,
                            LEGACY_SCALAR_INPUT_LENGTH,
                            2 * LEGACY_SCALAR_INPUT_LENGTH)));
        } finally {
            Arrays.fill(expanded, (byte) 0);
        }
    }

    private static byte[] reduceLegacy(byte[] extraBits) {
        try {
            BigInteger reduced = new BigInteger(1, extraBits)
                    .mod(ORDER.subtract(BigInteger.ONE))
                    .add(BigInteger.ONE);
            return scalarToBytes(reduced);
        } finally {
            Arrays.fill(extraBits, (byte) 0);
        }
    }

    private static BigInteger randomScalar(
            SecureRandom random) {
        byte[] candidate = new byte[SCALAR_LENGTH];
        try {
            BigInteger scalar;
            do {
                random.nextBytes(candidate);
                scalar = new BigInteger(1, candidate);
            } while (scalar.signum() == 0
                    || scalar.compareTo(ORDER) >= 0);
            return scalar;
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static ECPoint decodePeerShare(
            byte[] encoded) {
        if (encoded == null
                || encoded.length != SHARE_LENGTH
                || encoded[0] != 0x04) {
            throw new IllegalArgumentException(
                    "SPAKE2+ peer share must be an uncompressed "
                            + "P-256 point");
        }
        final ECPoint point;
        try {
            point = P256.getCurve()
                    .decodePoint(encoded)
                    .normalize();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "SPAKE2+ peer share is not on P-256",
                    error);
        }
        if (point.isInfinity()
                || !point.isValid()
                || !point.multiply(ORDER).isInfinity()) {
            throw new IllegalArgumentException(
                    "SPAKE2+ peer share is not in the prime-order group");
        }
        return point;
    }

    private static ECPoint decodeFixedPoint(
            String encodedHex) {
        ECPoint point = P256.getCurve()
                .decodePoint(hex(encodedHex))
                .normalize();
        if (point.isInfinity()
                || !point.isValid()
                || !point.multiply(ORDER).isInfinity()) {
            throw new IllegalStateException(
                    "Invalid SPAKE2+ fixed point");
        }
        return point;
    }

    private static byte[] buildTranscript(
            byte[]... fields) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (byte[] field : fields) {
            writeLittleEndian64(output, field.length);
            output.writeBytes(field);
        }
        return output.toByteArray();
    }

    private static void writeLittleEndian64(
            ByteArrayOutputStream output,
            int value) {
        long remaining = value & 0xffff_ffffL;
        for (int index = 0; index < 8; index++) {
            output.write((int) (remaining & 0xff));
            remaining >>>= 8;
        }
    }

    private static byte[] hkdfSha256(
            byte[] inputKeyMaterial,
            byte[] salt,
            byte[] info,
            int outputLength) {
        HKDFBytesGenerator hkdf =
                new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(
                inputKeyMaterial,
                salt,
                info));
        byte[] output = new byte[outputLength];
        hkdf.generateBytes(output, 0, output.length);
        return output;
    }

    private static byte[] hmacSha256(
            byte[] key,
            byte[] message) {
        HMac hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(message, 0, message.length);
        byte[] output = new byte[hmac.getMacSize()];
        hmac.doFinal(output, 0);
        return output;
    }

    private static byte[] sha256(byte[] input) {
        SHA256Digest digest = new SHA256Digest();
        digest.update(input, 0, input.length);
        byte[] output = new byte[digest.getDigestSize()];
        digest.doFinal(output, 0);
        return output;
    }

    private static BigInteger scalarFromBytes(
            String label,
            byte[] encoded,
            boolean allowZero) {
        if (encoded == null || encoded.length != SCALAR_LENGTH) {
            throw new IllegalArgumentException(
                    label + " must be exactly 32 bytes");
        }
        BigInteger value = new BigInteger(1, encoded);
        if ((!allowZero && value.signum() == 0)
                || value.compareTo(ORDER) >= 0) {
            throw new IllegalArgumentException(
                    label + " is outside the P-256 scalar range");
        }
        return value;
    }

    private static byte[] scalarToBytes(
            BigInteger scalar) {
        byte[] encoded = scalar.toByteArray();
        byte[] fixed = new byte[SCALAR_LENGTH];
        int sourceOffset = Math.max(
                0,
                encoded.length - SCALAR_LENGTH);
        int copyLength = encoded.length - sourceOffset;
        System.arraycopy(
                encoded,
                sourceOffset,
                fixed,
                fixed.length - copyLength,
                copyLength);
        Arrays.fill(encoded, (byte) 0);
        return fixed;
    }

    private static byte[] copyRequired(
            String label,
            byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
        return input.clone();
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Odd hexadecimal input");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(
                    value.charAt(index * 2),
                    16);
            int low = Character.digit(
                    value.charAt(index * 2 + 1),
                    16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(
                        "Invalid hexadecimal input");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class LegacyScalars {
        final byte[] w0;
        final byte[] w1;

        LegacyScalars(byte[] w0, byte[] w1) {
            this.w0 = w0;
            this.w1 = w1;
        }

        void destroy() {
            Arrays.fill(w0, (byte) 0);
            Arrays.fill(w1, (byte) 0);
        }
    }
}
