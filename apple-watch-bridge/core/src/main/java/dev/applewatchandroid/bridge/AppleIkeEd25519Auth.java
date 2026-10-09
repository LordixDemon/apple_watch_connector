package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Modern Apple ordinary-IKE Ed25519 authentication.
 *
 * <p>iOS 26.6 uses RFC 7427 Digital Signature authentication method 14 with
 * a DER AlgorithmIdentifier for Ed25519 followed by a 64-byte signature. The
 * signature input is the raw IKEv2 signed-octets concatenation; Ed25519 must
 * not be fed a separately SHA-512-hashed digest.</p>
 */
final class AppleIkeEd25519Auth {
    static final int AUTH_METHOD_DIGITAL_SIGNATURE = 14;
    static final int ED25519_PRIVATE_KEY_LENGTH = 32;
    static final int ED25519_PUBLIC_KEY_LENGTH = 32;
    static final int ED25519_SIGNATURE_LENGTH = 64;
    static final int PRF_SHA512_KEY_LENGTH = 64;
    static final int PRF_SHA512_OUTPUT_LENGTH = 64;
    static final int OPPOSITE_NONCE_LENGTH = 32;
    static final int AUTH_BODY_LENGTH =
            4 + 8 + ED25519_SIGNATURE_LENGTH;
    static final int IKE_AUTH_PAYLOAD_LENGTH =
            4 + AUTH_BODY_LENGTH;

    static final byte[] STANDARD_ED25519_ALGORITHM_IDENTIFIER =
            new byte[]{
                    0x07,
                    0x30, 0x05,
                    0x06, 0x03,
                    0x2b, 0x65, 0x70
            };

    private AppleIkeEd25519Auth() {
    }

    static byte[] buildSignedOctets(
            byte[] originalSaInitPacket,
            byte[] oppositeNonce,
            byte[] prfKey,
            byte[] identifierPayloadBody) {
        return buildSignedOctets(
                originalSaInitPacket,
                oppositeNonce,
                prfKey,
                identifierPayloadBody,
                null,
                null,
                0);
    }

    static byte[] buildSignedOctets(
            byte[] originalSaInitPacket,
            byte[] oppositeNonce,
            byte[] prfKey,
            byte[] identifierPayloadBody,
            byte[] initiatorIntermediateAuth,
            byte[] responderIntermediateAuth,
            long authMessageId) {
        requireNonempty(
                "original IKE_SA_INIT packet",
                originalSaInitPacket);
        requireLength(
                "opposite nonce",
                oppositeNonce,
                OPPOSITE_NONCE_LENGTH);
        requireLength(
                "IKE PRF key",
                prfKey,
                PRF_SHA512_KEY_LENGTH);
        if (identifierPayloadBody == null
                || identifierPayloadBody.length < 4) {
            throw new IllegalArgumentException(
                    "identifier payload body is required");
        }
        boolean hasInitiatorIntermediate =
                initiatorIntermediateAuth != null;
        boolean hasResponderIntermediate =
                responderIntermediateAuth != null;
        if (hasInitiatorIntermediate
                != hasResponderIntermediate) {
            throw new IllegalArgumentException(
                    "both intermediate-auth values are required");
        }
        if (authMessageId < 0
                || authMessageId > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    "AUTH message ID is out of range");
        }

        byte[] macedIdentifier = prfSha512(
                prfKey,
                identifierPayloadBody);
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(
                            originalSaInitPacket.length
                                    + oppositeNonce.length
                                    + macedIdentifier.length
                                    + (hasInitiatorIntermediate
                                    ? initiatorIntermediateAuth.length
                                    + responderIntermediateAuth.length
                                    + 4
                                    : 0));
            output.writeBytes(originalSaInitPacket);
            output.writeBytes(oppositeNonce);
            output.writeBytes(macedIdentifier);
            if (hasInitiatorIntermediate) {
                output.writeBytes(initiatorIntermediateAuth);
                output.writeBytes(responderIntermediateAuth);
                writeBe32(output, authMessageId);
            }
            return output.toByteArray();
        } finally {
            wipe(macedIdentifier);
        }
    }

    static byte[] sign(
            byte[] privateEd25519,
            byte[] signedOctets) {
        requireLength(
                "Ed25519 private key",
                privateEd25519,
                ED25519_PRIVATE_KEY_LENGTH);
        requireNonempty(
                "IKE signed octets",
                signedOctets);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(
                true,
                new Ed25519PrivateKeyParameters(
                        privateEd25519,
                        0));
        signer.update(
                signedOctets,
                0,
                signedOctets.length);
        byte[] signature = signer.generateSignature();
        if (signature.length
                != ED25519_SIGNATURE_LENGTH) {
            wipe(signature);
            throw new IllegalStateException(
                    "Unexpected Ed25519 signature length");
        }
        return signature;
    }

    static boolean verify(
            byte[] publicEd25519,
            byte[] signedOctets,
            byte[] signature) {
        requireLength(
                "Ed25519 public key",
                publicEd25519,
                ED25519_PUBLIC_KEY_LENGTH);
        requireNonempty(
                "IKE signed octets",
                signedOctets);
        requireLength(
                "Ed25519 signature",
                signature,
                ED25519_SIGNATURE_LENGTH);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(
                false,
                new Ed25519PublicKeyParameters(
                        publicEd25519,
                        0));
        verifier.update(
                signedOctets,
                0,
                signedOctets.length);
        return verifier.verifySignature(signature);
    }

    static byte[] buildAuthBody(
            byte[] signature) {
        requireLength(
                "Ed25519 signature",
                signature,
                ED25519_SIGNATURE_LENGTH);
        byte[] output = new byte[AUTH_BODY_LENGTH];
        output[0] = AUTH_METHOD_DIGITAL_SIGNATURE;
        System.arraycopy(
                STANDARD_ED25519_ALGORITHM_IDENTIFIER,
                0,
                output,
                4,
                STANDARD_ED25519_ALGORITHM_IDENTIFIER.length);
        System.arraycopy(
                signature,
                0,
                output,
                4
                        + STANDARD_ED25519_ALGORITHM_IDENTIFIER.length,
                signature.length);
        return output;
    }

    static byte[] signAuthBody(
            byte[] privateEd25519,
            byte[] signedOctets) {
        byte[] signature = sign(
                privateEd25519,
                signedOctets);
        try {
            return buildAuthBody(signature);
        } finally {
            wipe(signature);
        }
    }

    static byte[] extractSignature(
            byte[] authBody) {
        validateAuthBody(authBody);
        return Arrays.copyOfRange(
                authBody,
                4
                        + STANDARD_ED25519_ALGORITHM_IDENTIFIER.length,
                authBody.length);
    }

    static boolean verifyAuthBody(
            byte[] publicEd25519,
            byte[] signedOctets,
            byte[] authBody) {
        byte[] signature =
                extractSignature(authBody);
        try {
            return verify(
                    publicEd25519,
                    signedOctets,
                    signature);
        } finally {
            wipe(signature);
        }
    }

    static byte[] buildIkeAuthPayload(
            int nextPayload,
            byte[] authBody) {
        if (nextPayload < 0 || nextPayload > 0xff) {
            throw new IllegalArgumentException(
                    "next payload type is out of range");
        }
        validateAuthBody(authBody);
        byte[] output =
                new byte[IKE_AUTH_PAYLOAD_LENGTH];
        output[0] = (byte) nextPayload;
        output[1] = 0;
        output[2] =
                (byte) (output.length >>> 8);
        output[3] =
                (byte) output.length;
        System.arraycopy(
                authBody,
                0,
                output,
                4,
                authBody.length);
        return output;
    }

    private static void validateAuthBody(
            byte[] authBody) {
        requireLength(
                "Ed25519 AUTH body",
                authBody,
                AUTH_BODY_LENGTH);
        if (unsigned(authBody[0])
                != AUTH_METHOD_DIGITAL_SIGNATURE
                || authBody[1] != 0
                || authBody[2] != 0
                || authBody[3] != 0) {
            throw new IllegalArgumentException(
                    "Malformed Ed25519 AUTH method header");
        }
        byte[] actualAlgorithmIdentifier =
                Arrays.copyOfRange(
                        authBody,
                        4,
                        4
                                + STANDARD_ED25519_ALGORITHM_IDENTIFIER
                                .length);
        try {
            if (!MessageDigest.isEqual(
                    actualAlgorithmIdentifier,
                    STANDARD_ED25519_ALGORITHM_IDENTIFIER)) {
                throw new IllegalArgumentException(
                        "Unexpected Ed25519 AUTH "
                                + "AlgorithmIdentifier");
            }
        } finally {
            wipe(actualAlgorithmIdentifier);
        }
    }

    private static byte[] prfSha512(
            byte[] key,
            byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(
                data,
                0,
                data.length);
        byte[] output =
                new byte[PRF_SHA512_OUTPUT_LENGTH];
        hmac.doFinal(output, 0);
        return output;
    }

    private static void writeBe32(
            ByteArrayOutputStream output,
            long value) {
        output.write((int) (value >>> 24) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) value & 0xff);
    }

    private static void requireNonempty(
            String label,
            byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }

    private static void requireLength(
            String label,
            byte[] value,
            int expectedLength) {
        if (value == null
                || value.length != expectedLength) {
            throw new IllegalArgumentException(
                    label
                            + " must be exactly "
                            + expectedLength
                            + " bytes");
        }
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
