package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;



/**
 * Cryptographic state between the proven control IKE_SA_INIT exchange and the
 * first RFC 9370 ML-KEM-1024 IKE_INTERMEDIATE exchange.
 */
final class IkeV2SessionCrypto {
    static final int PAYLOAD_SK = 46;
    static final int PAYLOAD_SKF = 53;
    static final int EXCHANGE_IKE_INTERMEDIATE = 43;
    static final int EXCHANGE_IKE_AUTH = 35;
    static final int EXCHANGE_INFORMATIONAL = 37;
    static final int ML_KEM_1024_METHOD = 37;
    static final int ML_KEM_1024_PUBLIC_KEY_LENGTH = 1568;
    static final int ML_KEM_1024_CIPHERTEXT_LENGTH = 1568;
    static final int ML_KEM_SHARED_SECRET_LENGTH = 32;
    static final int CONTROL_MAX_IKE_PACKET_SIZE = 1280;
    static final int IKE_AUTH_MESSAGE_ID = 2;
    static final int PAIRING_FIRST_GSPM_MESSAGE_ID = 2;
    static final int PAIRING_SECOND_GSPM_MESSAGE_ID = 3;
    static final int PAIRING_FINAL_AUTH_MESSAGE_ID = 4;
    static final int PAIRING_NOTIFIES_MESSAGE_ID = 5;
    static final int PAIRING_NOTIFIES_WATCH_MESSAGE_ID = 0;
    static final int PIN_METHOD_MESSAGE_ID = 3;
    static final int PIN_METHOD_WATCH_MESSAGE_ID = 0;
    static final int ID_TYPE_KEY_ID = 11;
    static final int ID_TYPE_NULL = 13;
    static final int AUTH_METHOD_NULL = 13;
    static final int AUTH_METHOD_GENERIC_SECURE_PASSWORD = 12;
    static final int NOTIFY_INITIAL_CONTACT = 0x4000;
    static final int NOTIFY_PPK_IDENTITY = 0x4034;
    static final int NOTIFY_NO_PPK_AUTH = 0x4035;
    static final int PRIVATE_NOTIFY_AUTH_METHOD_REQUEST = 0xC545;
    static final int PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE = 0xC546;
    static final int PAIRING_AUTH_METHOD_PIN = 2;
    static final int PAYLOAD_GSPM = 49;
    static final String CONTROL_PAIRING_KEY_ID =
            "com.apple.networkrelay.companionlink.pairing.control";

    static final int IKE_HEADER_LENGTH = 28;
    static final int GENERIC_PAYLOAD_HEADER_LENGTH = 4;
    static final int AES_GCM_EXPLICIT_IV_LENGTH = 8;
    static final int PRF_SHA512_LENGTH = 64;
    static final int IKE_FLAG_INITIATOR = 0x08;
    static final int IKE_FLAG_RESPONSE = 0x20;
    static final int PAYLOAD_ID_INITIATOR = 35;
    static final int PAYLOAD_ID_RESPONDER = 36;
    static final int PAYLOAD_AUTH = 39;
    static final int PAYLOAD_NOTIFY = 41;
    static final int PAYLOAD_SA = 33;
    static final int PAYLOAD_TS_INITIATOR = 44;
    static final int PAYLOAD_TS_RESPONDER = 45;
    static final int NRTLV_AUTH_METHOD = 1;
    static final int NRTLV_PIN_SALT = 2;
    static final int MINIMUM_PIN_SALT_LENGTH = 32;
    static final byte[] NULL_AUTH_PAD =
            "Key Pad for IKEv2".getBytes(StandardCharsets.US_ASCII);
    static final byte[] CONTROL_PAIRING_KEY_ID_BYTES =
            CONTROL_PAIRING_KEY_ID.getBytes(StandardCharsets.UTF_8);

    private IkeV2SessionCrypto() {
    }


    static ControlSaInitResponse parseControlSaInitResponse(byte[] packet, byte[] expectedInitiatorSpi) {
        return IkeV2SaInitCodec.parseControlSaInitResponse(packet, expectedInitiatorSpi);
    }

    static ControlSaInitResponse parsePairingSaInitResponse(byte[] packet, byte[] expectedInitiatorSpi) {
        return IkeV2SaInitCodec.parsePairingSaInitResponse(packet, expectedInitiatorSpi);
    }

    static ControlSaInitResponse parseOpticalPairingSaInitResponse(byte[] packet, byte[] expectedInitiatorSpi) {
        return IkeV2SaInitCodec.parseOpticalPairingSaInitResponse(packet, expectedInitiatorSpi);
    }

    static IkeAuthRequest createControlIkeAuthRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        return IkeV2ControlAuthCodec.createControlIkeAuthRequest(
                random, initiator, response, updatedKeys, intAuthI, intAuthR);
    }

    static List<byte[]> createControlIkeAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        return IkeV2ControlAuthCodec.createControlIkeAuthResponseForTest(
                random, initiator, response, updatedKeys, intAuthI, intAuthR);
    }

    static PairingGspmInitiator createPairingGspmInitiator(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] sharedSecret) {
        return IkeV2PairingExchangeCodec.createPairingGspmInitiator(
                random, initiator, response, ppkKeys, intAuthI, intAuthR, sharedSecret);
    }

    static PairingGspmInitiator createPairingGspmInitiatorForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            AppleSpake2PlusProver prover) {
        return IkeV2PairingExchangeCodec.createPairingGspmInitiatorForTest(
                random, initiator, response, ppkKeys, intAuthI, intAuthR, prover);
    }

    static List<byte[]> createPairingFinalAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] firstInitiatorGspmMessage,
            byte[] firstResponderGspmMessage,
            byte[] gspmSessionKey) {
        return IkeV2PairingExchangeCodec.createPairingFinalAuthResponseForTest(
                random, initiator, response, ppkKeys, intAuthI, intAuthR,
                firstInitiatorGspmMessage, firstResponderGspmMessage, gspmSessionKey);
    }

    static String describePairingProtectedPayloadStructure(DecryptedIntermediatePart part, String stage) {
        return IkeV2PairingExchangeCodec.describePairingProtectedPayloadStructure(part, stage);
    }

    static PinAuthMethodRequest createPinAuthMethodRequest(SecureRandom random, IkeSaKeys updatedKeys) {
        return IkeV2ControlAuthCodec.createPinAuthMethodRequest(random, updatedKeys);
    }

    static List<byte[]> createPinAuthMethodResponseForTest(SecureRandom random, IkeSaKeys updatedKeys, byte[] pinSalt) {
        return IkeV2ControlAuthCodec.createPinAuthMethodResponseForTest(random, updatedKeys, pinSalt);
    }

    static List<byte[]> createPinAuthMethodEmptyResponseForTest(SecureRandom random, IkeSaKeys updatedKeys) {
        return IkeV2ControlAuthCodec.createPinAuthMethodEmptyResponseForTest(random, updatedKeys);
    }

    static List<byte[]> createPinAuthMethodWatchRequestForTest(SecureRandom random, IkeSaKeys updatedKeys, byte[] pinSalt) {
        return IkeV2ControlAuthCodec.createPinAuthMethodWatchRequestForTest(random, updatedKeys, pinSalt);
    }

    static List<byte[]> createWatchInformationalAcknowledgement(SecureRandom random, IkeSaKeys updatedKeys, int watchMessageId) {
        return IkeV2ControlAuthCodec.createWatchInformationalAcknowledgement(random, updatedKeys, watchMessageId);
    }

    static PairingPrivateNotifyRequest createPairingPrivateNotifyRequest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies) {
        return IkeV2PairingExchangeCodec.createPairingPrivateNotifyRequest(random, updatedKeys, notifies);
    }

    static PairingPrivateNotifyRequest createPairingPrivateNotifyRequest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies,
            int messageId) {
        return IkeV2PairingExchangeCodec.createPairingPrivateNotifyRequest(
                random, updatedKeys, notifies, messageId);
    }

    static List<byte[]> createPairingPrivateNotifyEmptyResponseForTest(SecureRandom random, IkeSaKeys updatedKeys) {
        return IkeV2PairingExchangeCodec.createPairingPrivateNotifyEmptyResponseForTest(random, updatedKeys);
    }

    static List<byte[]> createPairingPrivateNotifyWatchRequestForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies) {
        return IkeV2PairingExchangeCodec.createPairingPrivateNotifyWatchRequestForTest(random, updatedKeys, notifies);
    }

    static List<byte[]> createPairingPrivateNotifyWatchRequestForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies,
            int maxIkePacketSize) {
        return IkeV2PairingExchangeCodec.createPairingPrivateNotifyWatchRequestForTest(
                random, updatedKeys, notifies, maxIkePacketSize);
    }

    static byte[] computeIntermediateIntAuthForProfile(
            IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        return IkeV2ProtectedPacketCrypto.computeIntermediateIntAuthForProfile(keys, response, firstInnerPayload, protectedPayloadFlags, plaintext);
    }

    static byte[] buildIntermediateIntAuthInput(
            byte[] initiatorSpi,
            byte[] responderSpi,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        return IkeV2ProtectedPacketCrypto.buildIntermediateIntAuthInput(initiatorSpi, responderSpi, response, firstInnerPayload, protectedPayloadFlags, plaintext);
    }

    static byte[] buildKePayload(byte[] keyExchangeData) {
        return IkeV2ProtectedPacketCrypto.buildKePayload(keyExchangeData);
    }

    static IkeSaKeys deriveInitialKeys(
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response) {
        return IkeV2ProtectedPacketCrypto.deriveInitialKeys(initiator, response);
    }

    static IkeSaKeys deriveInitialX448Keys(
            byte[] localX448PrivateKey,
            byte[] peerX448PublicKey,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] initiatorNonce,
            byte[] responderNonce) {
        return IkeV2ProtectedPacketCrypto.deriveInitialX448Keys(localX448PrivateKey, peerX448PublicKey, initiatorSpi, responderSpi, initiatorNonce, responderNonce);
    }

    static AdditionalKeRequest createAdditionalKeRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys initialKeys) {
        return IkeV2ProtectedPacketCrypto.createAdditionalKeRequest(random, initiator, response, initialKeys);
    }

    static AdditionalKeResult completeAdditionalKeyExchange(
            AdditionalKeRequest request,
            byte[] responderCiphertext,
            IkeSaKeys initialKeys) {
        return IkeV2ProtectedPacketCrypto.completeAdditionalKeyExchange(request, responderCiphertext, initialKeys);
    }

    static IkeSaKeys updateKeysWithAdditionalSecret(
            IkeSaKeys initialKeys,
            byte[] additionalSharedSecret) {
        return IkeV2ProtectedPacketCrypto.updateKeysWithAdditionalSecret(initialKeys, additionalSharedSecret);
    }

    static IkeSaKeys applyMandatoryPairingPpk(
            IkeSaKeys primeKeys,
            byte[] ppk) {
        return IkeV2ProtectedPacketCrypto.applyMandatoryPairingPpk(primeKeys, ppk);
    }

    static List<byte[]> encryptIntermediatePayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        return IkeV2ProtectedPacketCrypto.encryptIntermediatePayload(random, initiatorSpi, responderSpi, encryptionKeyMaterial, response, innerPayloadBytes, firstInnerPayload, maximumPacketSize);
    }

    static List<byte[]> encryptProtectedPayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int exchangeType,
            int messageId,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        return IkeV2ProtectedPacketCrypto.encryptProtectedPayload(random, initiatorSpi, responderSpi, encryptionKeyMaterial, response, exchangeType, messageId, innerPayloadBytes, firstInnerPayload, maximumPacketSize);
    }

    static byte[] encryptEmptyProtectedPayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int exchangeType,
            int messageId) {
        return IkeV2ProtectedPacketCrypto.encryptEmptyProtectedPayload(random, initiatorSpi, responderSpi, encryptionKeyMaterial, response, exchangeType, messageId);
    }

    static byte[] encryptEmptyProtectedPayloadWithDirectionFlags(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int exchangeType,
            int messageId) {
        return IkeV2ProtectedPacketCrypto.encryptEmptyProtectedPayloadWithDirectionFlags(random, initiatorSpi, responderSpi, encryptionKeyMaterial, directionFlags, exchangeType, messageId);
    }

    static List<byte[]>
            encryptProtectedPayloadWithDirectionFlags(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int exchangeType,
            int messageId,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        return IkeV2ProtectedPacketCrypto.encryptProtectedPayloadWithDirectionFlags(random, initiatorSpi, responderSpi, encryptionKeyMaterial, directionFlags, exchangeType, messageId, innerPayloadBytes, firstInnerPayload, maximumPacketSize);
    }

    static byte[] encryptProtectedPacket(
            byte[] initiatorSpi,
            byte[] responderSpi,
            int directionFlags,
            int exchangeType,
            int messageId,
            int protectedPayloadType,
            int firstInnerPayload,
            int fragmentNumber,
            int totalFragments,
            byte[] plaintextChunk,
            byte[] encryptionKeyMaterial,
            byte[] explicitIv) {
        return IkeV2ProtectedPacketCrypto.encryptProtectedPacket(initiatorSpi, responderSpi, directionFlags, exchangeType, messageId, protectedPayloadType, firstInnerPayload, fragmentNumber, totalFragments, plaintextChunk, encryptionKeyMaterial, explicitIv);
    }

    static DecryptedIntermediatePart decryptProtectedIntermediate(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            boolean response) {
        return IkeV2ProtectedPacketCrypto.decryptProtectedIntermediate(packet, expectedInitiatorSpi, expectedResponderSpi, encryptionKeyMaterial, response);
    }

    static DecryptedIntermediatePart decryptProtectedPacket(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int expectedExchangeType,
            int expectedMessageId) {
        return IkeV2ProtectedPacketCrypto.decryptProtectedPacket(packet, expectedInitiatorSpi, expectedResponderSpi, encryptionKeyMaterial, response, expectedExchangeType, expectedMessageId);
    }

    static DecryptedIntermediatePart
            decryptProtectedPacketWithDirectionFlags(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            int expectedDirectionFlags,
            int expectedExchangeType,
            int expectedMessageId) {
        return IkeV2ProtectedPacketCrypto.decryptProtectedPacketWithDirectionFlags(packet, expectedInitiatorSpi, expectedResponderSpi, encryptionKeyMaterial, expectedDirectionFlags, expectedExchangeType, expectedMessageId);
    }

    static byte[] prf(byte[] key, byte[] data) {
        return IkeV2ProtectedPacketCrypto.prf(key, data);
    }

    static byte[] concatenate(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length = Math.addExact(length, array.length);
        }
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(
                    array,
                    0,
                    output,
                    offset,
                    array.length);
            offset += array.length;
        }
        return output;
    }

    static int unsigned(byte value) {
        return value & 0xFF;
    }

    static int be16(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8)
                | unsigned(bytes[offset + 1]);
    }

    static int be32(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 24)
                | (unsigned(bytes[offset + 1]) << 16)
                | (unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    static boolean matchesRange(
            byte[] packet,
            int offset,
            byte[] expected) {
        if (offset < 0
                || expected == null
                || offset + expected.length > packet.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length; index++) {
            difference |= packet[offset + index]
                    ^ expected[index];
        }
        return difference == 0;
    }

    static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    static void writeBe32(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    static void requireLength(
            String label,
            byte[] bytes,
            int expectedLength) {
        if (bytes == null || bytes.length != expectedLength) {
            throw new IllegalArgumentException(
                    label + " must be " + expectedLength + " bytes");
        }
    }

    static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static final class ControlSaInitResponse {
        final byte[] responderSpi;
        final byte[] responderNonce;
        final byte[] responderX448PublicKey;
        final byte[] packet;

        ControlSaInitResponse(
                byte[] responderSpi,
                byte[] responderNonce,
                byte[] responderX448PublicKey,
                byte[] packet) {
            this.responderSpi =
                    Arrays.copyOf(responderSpi, responderSpi.length);
            this.responderNonce =
                    Arrays.copyOf(responderNonce, responderNonce.length);
            this.responderX448PublicKey = Arrays.copyOf(
                    responderX448PublicKey,
                    responderX448PublicKey.length);
            this.packet = Arrays.copyOf(packet, packet.length);
        }
    }

    static final class IkeSaKeys {
        final byte[] skD;
        final byte[] skEi;
        final byte[] skEr;
        final byte[] skPi;
        final byte[] skPr;
        final byte[] initiatorSpi;
        final byte[] responderSpi;
        final byte[] initiatorNonce;
        final byte[] responderNonce;

        IkeSaKeys(
                byte[] skD,
                byte[] skEi,
                byte[] skEr,
                byte[] skPi,
                byte[] skPr,
                byte[] initiatorSpi,
                byte[] responderSpi,
                byte[] initiatorNonce,
                byte[] responderNonce) {
            this.skD = Arrays.copyOf(skD, skD.length);
            this.skEi = Arrays.copyOf(skEi, skEi.length);
            this.skEr = Arrays.copyOf(skEr, skEr.length);
            this.skPi = Arrays.copyOf(skPi, skPi.length);
            this.skPr = Arrays.copyOf(skPr, skPr.length);
            this.initiatorSpi =
                    Arrays.copyOf(initiatorSpi, initiatorSpi.length);
            this.responderSpi =
                    Arrays.copyOf(responderSpi, responderSpi.length);
            this.initiatorNonce =
                    Arrays.copyOf(initiatorNonce, initiatorNonce.length);
            this.responderNonce =
                    Arrays.copyOf(responderNonce, responderNonce.length);
        }

        void destroy() {
            wipe(skD);
            wipe(skEi);
            wipe(skEr);
            wipe(skPi);
            wipe(skPr);
            wipe(initiatorSpi);
            wipe(responderSpi);
            wipe(initiatorNonce);
            wipe(responderNonce);
        }
    }

    static final class AdditionalKeRequest {
        final MLKEMPrivateKeyParameters privateKey;
        final byte[] publicKey;
        final List<byte[]> ikePackets;
        final byte[] initiatorIntAuth;

        AdditionalKeRequest(
                MLKEMPrivateKeyParameters privateKey,
                byte[] publicKey,
                List<byte[]> ikePackets,
                byte[] initiatorIntAuth) {
            this.privateKey = privateKey;
            this.publicKey =
                    Arrays.copyOf(publicKey, publicKey.length);
            this.ikePackets = List.copyOf(ikePackets);
            this.initiatorIntAuth = Arrays.copyOf(
                    initiatorIntAuth,
                    initiatorIntAuth.length);
        }
    }

    static final class AdditionalKeResult {
        final byte[] sharedSecret;
        final IkeSaKeys updatedKeys;

        AdditionalKeResult(
                byte[] sharedSecret,
                IkeSaKeys updatedKeys) {
            this.sharedSecret =
                    Arrays.copyOf(sharedSecret, sharedSecret.length);
            this.updatedKeys = updatedKeys;
        }
    }

    static final class DecryptedIntermediatePart {
        final boolean fragmented;
        final int fragmentNumber;
        final int totalFragments;
        final int firstInnerPayload;
        final int protectedPayloadFlags;
        final byte[] plaintext;

        DecryptedIntermediatePart(
                boolean fragmented,
                int fragmentNumber,
                int totalFragments,
                int firstInnerPayload,
                int protectedPayloadFlags,
                byte[] plaintext) {
            this.fragmented = fragmented;
            this.fragmentNumber = fragmentNumber;
            this.totalFragments = totalFragments;
            this.firstInnerPayload = firstInnerPayload;
            this.protectedPayloadFlags = protectedPayloadFlags;
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
        }
    }

    static final class IntermediateResponseAccumulator {
        private final IkeSaKeys keys;
        private final byte[] initiatorSpi;
        private final byte[] responderSpi;
        private final byte[] skEr;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int firstProtectedPayloadFlags;
        private int receivedFragments;
        private byte[] responderIntAuth;

        IntermediateResponseAccumulator(IkeSaKeys keys) {
            this.keys = keys;
            this.initiatorSpi =
                    Arrays.copyOf(
                            keys.initiatorSpi,
                            keys.initiatorSpi.length);
            this.responderSpi =
                    Arrays.copyOf(
                            keys.responderSpi,
                            keys.responderSpi.length);
            this.skEr =
                    Arrays.copyOf(keys.skEr, keys.skEr.length);
        }

        byte[] accept(byte[] packet) {
            DecryptedIntermediatePart part =
                    decryptProtectedIntermediate(
                            packet,
                            initiatorSpi,
                            responderSpi,
                            skEr,
                            true);
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented response");
                }
                responderIntAuth = IkeV2SaInitCodec.computeIntermediateIntAuth(
                        keys,
                        true,
                        part.firstInnerPayload,
                        part.protectedPayloadFlags,
                        part.plaintext);
                return IkeV2SaInitCodec.parseMlKemKePayload(
                        part.firstInnerPayload,
                        part.plaintext);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
            } else if (fragments.length
                    != part.totalFragments) {
                throw new IllegalArgumentException(
                        "IKE fragment total changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "IKE fragment retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload =
                        part.firstInnerPayload;
                firstProtectedPayloadFlags =
                        part.protectedPayloadFlags;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            byte[] completePlaintext = plaintext.toByteArray();
            responderIntAuth = IkeV2SaInitCodec.computeIntermediateIntAuth(
                    keys,
                    true,
                    firstInnerPayload,
                    firstProtectedPayloadFlags,
                    completePlaintext);
            return IkeV2SaInitCodec.parseMlKemKePayload(
                    firstInnerPayload,
                    completePlaintext);
        }

        byte[] responderIntAuth() {
            if (responderIntAuth == null) {
                throw new IllegalStateException(
                        "IKE_INTERMEDIATE response is incomplete");
            }
            return Arrays.copyOf(
                    responderIntAuth,
                    responderIntAuth.length);
        }
    }

    static final class IkeAuthRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;
        final int authenticationDataLength;

        IkeAuthRequest(
                List<byte[]> ikePackets,
                byte[] plaintext,
                byte[] authenticationData) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
            this.authenticationDataLength =
                    authenticationData.length;
        }
    }

    static final class IkeAuthResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authenticationDataLength;

        IkeAuthResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authenticationDataLength) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    static final class IkeAuthResponseAccumulator {
        private final IkeV2Codec.InitiatorState initiator;
        private final ControlSaInitResponse response;
        private final IkeSaKeys updatedKeys;
        private final byte[] intAuthI;
        private final byte[] intAuthR;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int receivedFragments;

        IkeAuthResponseAccumulator(
                IkeV2Codec.InitiatorState initiator,
                ControlSaInitResponse response,
                IkeSaKeys updatedKeys,
                byte[] intAuthI,
                byte[] intAuthR) {
            this.initiator = initiator;
            this.response = response;
            this.updatedKeys = updatedKeys;
            this.intAuthI =
                    Arrays.copyOf(intAuthI, intAuthI.length);
            this.intAuthR =
                    Arrays.copyOf(intAuthR, intAuthR.length);
        }

        IkeAuthResponse accept(byte[] packet) {
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            IKE_AUTH_MESSAGE_ID);
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "IKE_AUTH response");
                }
                return IkeV2ControlAuthCodec.parseControlIkeAuthPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        initiator,
                        response,
                        updatedKeys,
                        intAuthI,
                        intAuthR);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
            } else if (fragments.length
                    != part.totalFragments) {
                throw new IllegalArgumentException(
                        "IKE_AUTH fragment total changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH fragment retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload =
                        part.firstInnerPayload;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            return IkeV2ControlAuthCodec.parseControlIkeAuthPlaintext(
                    firstInnerPayload,
                    plaintext.toByteArray(),
                    initiator,
                    response,
                    updatedKeys,
                    intAuthI,
                    intAuthR);
        }
    }

    static final class PairingExchangeRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;
        final int messageId;
        final int gspmDataLength;
        final int authenticationDataLength;

        PairingExchangeRequest(
                List<byte[]> ikePackets,
                byte[] plaintext,
                int messageId,
                int gspmDataLength,
                int authenticationDataLength) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
            this.messageId = messageId;
            this.gspmDataLength = gspmDataLength;
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    static final class PairingAuthResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authenticationDataLength;

        PairingAuthResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authenticationDataLength) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    /**
     * Stateful three-request Apple GSPM initiator:
     * X/Y, confirmation tags, then secure-password AUTH.
     */
    static final class PairingGspmInitiator {
        private static final int READY_FIRST_REQUEST = 0;
        private static final int AWAITING_FIRST_RESPONSE = 1;
        private static final int AWAITING_SECOND_RESPONSE = 2;
        private static final int AWAITING_FINAL_RESPONSE = 3;
        private static final int COMPLETE = 4;
        private static final int DESTROYED = 5;

        private final IkeV2Codec.InitiatorState initiator;
        private final ControlSaInitResponse response;
        private final IkeSaKeys ppkKeys;
        private final byte[] intAuthI;
        private final byte[] intAuthR;
        private final AppleSpake2PlusProver prover;
        private final byte[] initiatorIdBody;
        private final byte[] responderIdBody;
        private final byte[] firstInitiatorGspmMessage;

        private byte[] firstResponderGspmMessage;
        private byte[] gspmSessionKey;
        private int stage = READY_FIRST_REQUEST;

        PairingGspmInitiator(
                IkeV2Codec.InitiatorState initiator,
                ControlSaInitResponse response,
                IkeSaKeys ppkKeys,
                byte[] intAuthI,
                byte[] intAuthR,
                AppleSpake2PlusProver prover,
                byte[] initiatorIdBody,
                byte[] responderIdBody) {
            this.initiator = initiator;
            this.response = response;
            this.ppkKeys = ppkKeys;
            this.intAuthI =
                    Arrays.copyOf(intAuthI, intAuthI.length);
            this.intAuthR =
                    Arrays.copyOf(intAuthR, intAuthR.length);
            this.prover = prover;
            this.initiatorIdBody =
                    Arrays.copyOf(
                            initiatorIdBody,
                            initiatorIdBody.length);
            this.responderIdBody =
                    Arrays.copyOf(
                            responderIdBody,
                            responderIdBody.length);
            this.firstInitiatorGspmMessage =
                    prover.getShare();
        }

        PairingExchangeRequest createFirstRequest(
                SecureRandom random) {
            requireStage(
                    READY_FIRST_REQUEST,
                    "first GSPM request");
            byte[] plaintext =
                    IkeV2PairingExchangeCodec.buildInitialPairingGspmPlaintext(
                            initiatorIdBody,
                            responderIdBody,
                            firstInitiatorGspmMessage);
            List<byte[]> packets = encryptProtectedPayload(
                    random,
                    ppkKeys.initiatorSpi,
                    ppkKeys.responderSpi,
                    ppkKeys.skEi,
                    false,
                    EXCHANGE_IKE_AUTH,
                    PAIRING_FIRST_GSPM_MESSAGE_ID,
                    plaintext,
                    PAYLOAD_ID_INITIATOR,
                    CONTROL_MAX_IKE_PACKET_SIZE);
            stage = AWAITING_FIRST_RESPONSE;
            return new PairingExchangeRequest(
                    packets,
                    plaintext,
                    PAIRING_FIRST_GSPM_MESSAGE_ID,
                    firstInitiatorGspmMessage.length,
                    0);
        }

        PairingExchangeRequest
                acceptFirstResponseAndCreateSecondRequest(
                SecureRandom random,
                byte[] packet) {
            requireStage(
                    AWAITING_FIRST_RESPONSE,
                    "first GSPM response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_FIRST_GSPM_MESSAGE_ID);
            byte[] responderShare =
                    IkeV2PairingExchangeCodec.parsePairingGspmResponse(
                            part,
                            true,
                            AppleSpake2PlusProver.SHARE_LENGTH);
            byte[] proverConfirmation = null;
            try {
                proverConfirmation =
                        prover.processPeerShareAndGenerateVerificationTag(
                                responderShare);
                firstResponderGspmMessage =
                        Arrays.copyOf(
                                responderShare,
                                responderShare.length);
                byte[] plaintext =
                        IkeV2PairingExchangeCodec.buildSingleGspmPlaintext(
                                proverConfirmation);
                List<byte[]> packets =
                        encryptProtectedPayload(
                                random,
                                ppkKeys.initiatorSpi,
                                ppkKeys.responderSpi,
                                ppkKeys.skEi,
                                false,
                                EXCHANGE_IKE_AUTH,
                                PAIRING_SECOND_GSPM_MESSAGE_ID,
                                plaintext,
                                PAYLOAD_GSPM,
                                CONTROL_MAX_IKE_PACKET_SIZE);
                stage = AWAITING_SECOND_RESPONSE;
                return new PairingExchangeRequest(
                        packets,
                        plaintext,
                        PAIRING_SECOND_GSPM_MESSAGE_ID,
                        proverConfirmation.length,
                        0);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(responderShare);
                wipe(proverConfirmation);
            }
        }

        PairingExchangeRequest
                acceptSecondResponseAndCreateFinalAuthRequest(
                SecureRandom random,
                byte[] packet) {
            requireStage(
                    AWAITING_SECOND_RESPONSE,
                    "second GSPM response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_SECOND_GSPM_MESSAGE_ID);
            byte[] verifierConfirmation =
                    IkeV2PairingExchangeCodec.parsePairingGspmResponse(
                            part,
                            false,
                            AppleSpake2PlusProver
                                    .CONFIRMATION_TAG_LENGTH);
            byte[] authData = null;
            try {
                gspmSessionKey =
                        prover.processPeerVerificationTagAndGenerateKey(
                                verifierConfirmation);
                authData = IkeV2PairingExchangeCodec.createPairingAuthenticationData(
                        false,
                        initiator,
                        response,
                        ppkKeys,
                        intAuthI,
                        intAuthR,
                        initiatorIdBody,
                        firstInitiatorGspmMessage,
                        firstResponderGspmMessage,
                        gspmSessionKey);
                byte[] plaintext = concatenate(
                        IkeV2SaInitCodec.genericPayload(
                                PAYLOAD_NOTIFY,
                                IkeV2SaInitCodec.buildAuthenticationBody(
                                        AUTH_METHOD_GENERIC_SECURE_PASSWORD,
                                        authData)),
                        IkeV2SaInitCodec.genericPayload(
                                IkeV2Codec.PAYLOAD_NONE,
                                IkeV2SaInitCodec.buildNotifyBody(
                                        0,
                                        NOTIFY_PPK_IDENTITY,
                                        AppleWatchPairingCrypto
                                                .fixedPpkIdentityData())));
                List<byte[]> packets =
                        encryptProtectedPayload(
                                random,
                                ppkKeys.initiatorSpi,
                                ppkKeys.responderSpi,
                                ppkKeys.skEi,
                                false,
                                EXCHANGE_IKE_AUTH,
                                PAIRING_FINAL_AUTH_MESSAGE_ID,
                                plaintext,
                                PAYLOAD_AUTH,
                                CONTROL_MAX_IKE_PACKET_SIZE);
                stage = AWAITING_FINAL_RESPONSE;
                return new PairingExchangeRequest(
                        packets,
                        plaintext,
                        PAIRING_FINAL_AUTH_MESSAGE_ID,
                        0,
                        authData.length);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(verifierConfirmation);
                wipe(authData);
            }
        }

        PairingAuthResponse acceptFinalAuthResponse(
                byte[] packet) {
            requireStage(
                    AWAITING_FINAL_RESPONSE,
                    "final pairing AUTH response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_FINAL_AUTH_MESSAGE_ID);
            ParsedPairingFinalAuth parsed =
                    IkeV2PairingExchangeCodec.parsePairingFinalAuthResponse(part);
            byte[] expectedAuthenticationData = null;
            try {
                expectedAuthenticationData =
                        IkeV2PairingExchangeCodec.createPairingAuthenticationData(
                                true,
                                initiator,
                                response,
                                ppkKeys,
                                intAuthI,
                                intAuthR,
                                responderIdBody,
                                firstInitiatorGspmMessage,
                                firstResponderGspmMessage,
                                gspmSessionKey);
                if (!MessageDigest.isEqual(
                        expectedAuthenticationData,
                        parsed.authenticationData)) {
                    throw new SecurityException(
                            "Watch GSPM AUTH verification failed");
                }
                stage = COMPLETE;
                wipe(gspmSessionKey);
                gspmSessionKey = null;
                return new PairingAuthResponse(
                        parsed.payloadTypes,
                        parsed.notifyTypes,
                        parsed.authenticationData.length);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(expectedAuthenticationData);
                parsed.destroy();
            }
        }

        boolean isComplete() {
            return stage == COMPLETE;
        }

        void destroy() {
            if (stage == DESTROYED) {
                return;
            }
            prover.destroy();
            wipe(intAuthI);
            wipe(intAuthR);
            wipe(initiatorIdBody);
            wipe(responderIdBody);
            wipe(firstInitiatorGspmMessage);
            wipe(firstResponderGspmMessage);
            wipe(gspmSessionKey);
            firstResponderGspmMessage = null;
            gspmSessionKey = null;
            stage = DESTROYED;
        }

        private void requireStage(
                int expectedStage,
                String operation) {
            if (stage != expectedStage) {
                throw new IllegalStateException(
                        "Cannot process "
                                + operation
                                + " in pairing stage "
                                + stage);
            }
        }
    }

    static final class ParsedPairingFinalAuth {
        final byte[] authenticationData;
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;

        ParsedPairingFinalAuth(
                byte[] authenticationData,
                List<Integer> payloadTypes,
                List<Integer> notifyTypes) {
            this.authenticationData = Arrays.copyOf(
                    authenticationData,
                    authenticationData.length);
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
        }

        void destroy() {
            wipe(authenticationData);
        }
    }

    static final class PinAuthMethodRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;

        PinAuthMethodRequest(
                List<byte[]> ikePackets,
                byte[] plaintext) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
        }
    }

    static final class PinAuthMethodResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authMethod;
        final byte[] pinSalt;
        final int peerRequestMessageId;

        PinAuthMethodResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authMethod,
                byte[] pinSalt,
                int peerRequestMessageId) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authMethod = authMethod;
            this.pinSalt = Arrays.copyOf(pinSalt, pinSalt.length);
            this.peerRequestMessageId = peerRequestMessageId;
        }
    }

    static final class PinAuthMethodResponseAccumulator {
        private final IkeSaKeys updatedKeys;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int receivedFragments;
        private int fragmentedPeerRequestMessageId = -1;
        private boolean requestAcknowledged;

        PinAuthMethodResponseAccumulator(IkeSaKeys updatedKeys) {
            if (updatedKeys == null) {
                throw new IllegalArgumentException(
                        "Updated IKE keys are required");
            }
            this.updatedKeys = updatedKeys;
        }

        PinAuthMethodResponse accept(byte[] packet) {
            DecryptedIntermediatePart part;
            int peerRequestMessageId;
            boolean directResponseHeader =
                    packet != null
                            && packet.length >= IKE_HEADER_LENGTH
                            && unsigned(packet[18])
                            == EXCHANGE_INFORMATIONAL
                            && (unsigned(packet[19])
                            & (IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE))
                            == IKE_FLAG_RESPONSE
                            && be32(packet, 20)
                            == PIN_METHOD_MESSAGE_ID;
            if (!requestAcknowledged || directResponseHeader) {
                part = decryptProtectedPacket(
                        packet,
                        updatedKeys.initiatorSpi,
                        updatedKeys.responderSpi,
                        updatedKeys.skEr,
                        true,
                        EXCHANGE_INFORMATIONAL,
                        PIN_METHOD_MESSAGE_ID);
                if (!part.fragmented
                        && part.firstInnerPayload
                        == IkeV2Codec.PAYLOAD_NONE
                        && part.plaintext.length == 0) {
                    requestAcknowledged = true;
                    return null;
                }
                if (requestAcknowledged) {
                    throw new IllegalArgumentException(
                            "Retransmitted PIN auth-method ACK "
                                    + "was not empty");
                }
                peerRequestMessageId = -1;
            } else {
                part = decryptProtectedPacketWithDirectionFlags(
                        packet,
                        updatedKeys.initiatorSpi,
                        updatedKeys.responderSpi,
                        updatedKeys.skEr,
                        0,
                        EXCHANGE_INFORMATIONAL,
                        PIN_METHOD_WATCH_MESSAGE_ID);
                peerRequestMessageId =
                        PIN_METHOD_WATCH_MESSAGE_ID;
            }
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "PIN auth-method response");
                }
                return IkeV2ControlAuthCodec.parsePinAuthMethodPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        peerRequestMessageId);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
                fragmentedPeerRequestMessageId =
                        peerRequestMessageId;
            } else if (fragments.length != part.totalFragments) {
                throw new IllegalArgumentException(
                        "PIN auth-method fragment total changed");
            } else if (fragmentedPeerRequestMessageId
                    != peerRequestMessageId) {
                throw new IllegalArgumentException(
                        "PIN auth-method fragment direction changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "PIN auth-method fragment "
                                    + "retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload = part.firstInnerPayload;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            return IkeV2ControlAuthCodec.parsePinAuthMethodPlaintext(
                    firstInnerPayload,
                    plaintext.toByteArray(),
                    fragmentedPeerRequestMessageId);
        }

        boolean requestAcknowledged() {
            return requestAcknowledged;
        }
    }

    static final class PairingPrivateNotifyRequest {
        final List<byte[]> ikePackets;
        final List<Integer> notifyTypes;
        final List<Integer> dataLengths;

        PairingPrivateNotifyRequest(
                List<byte[]> ikePackets,
                List<Integer> notifyTypes,
                List<Integer> dataLengths) {
            this.ikePackets = List.copyOf(ikePackets);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.dataLengths = List.copyOf(dataLengths);
        }
    }

    enum WatchRequestRetryStatus {
        NEED_MORE_FRAGMENTS,
        COMPLETE_RETRY,
        REJECTED
    }

    /**
     * Constant-time matcher for byte-identical authenticated IKEv2 peer request
     * retransmissions during final-acknowledgement wait (RFC 7296 section 2.1).
     */
    static final class WatchRequestRetryMatcher implements AutoCloseable {
        private final byte[][] expectedWirePackets;
        private final int maxPacketLength;
        private int nextFragmentIndex;
        private boolean closed;

        WatchRequestRetryMatcher(byte[][] sourceWirePackets) {
            if (sourceWirePackets == null || sourceWirePackets.length == 0) {
                throw new IllegalArgumentException("Retry wire packets are required");
            }
            byte[][] copied = new byte[sourceWirePackets.length][];
            int maxLength = 0;
            try {
                for (int i = 0; i < sourceWirePackets.length; i++) {
                    byte[] packet = sourceWirePackets[i];
                    if (packet == null || packet.length == 0) {
                        throw new IllegalArgumentException("Retry wire packet is empty");
                    }
                    copied[i] = packet.clone();
                    if (packet.length > maxLength) {
                        maxLength = packet.length;
                    }
                }
            } catch (RuntimeException error) {
                for (byte[] entry : copied) {
                    wipe(entry);
                }
                throw error;
            }
            this.expectedWirePackets = copied;
            this.maxPacketLength = maxLength;
        }

        int maxPacketLength() {
            return maxPacketLength;
        }

        boolean hasPartialMatch() {
            return nextFragmentIndex > 0;
        }

        WatchRequestRetryStatus accept(byte[] incomingIkePacket) {
            if (closed) {
                throw new IllegalStateException("Watch request retry matcher has been closed");
            }
            if (incomingIkePacket == null) {
                return WatchRequestRetryStatus.REJECTED;
            }
            byte[] expected = expectedWirePackets[nextFragmentIndex];
            if (incomingIkePacket.length != expected.length
                    || !MessageDigest.isEqual(incomingIkePacket, expected)) {
                return WatchRequestRetryStatus.REJECTED;
            }
            nextFragmentIndex++;
            if (nextFragmentIndex == expectedWirePackets.length) {
                nextFragmentIndex = 0;
                return WatchRequestRetryStatus.COMPLETE_RETRY;
            }
            return WatchRequestRetryStatus.NEED_MORE_FRAGMENTS;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            for (byte[] packet : expectedWirePackets) {
                wipe(packet);
            }
            closed = true;
        }
    }

    static final class PairingPrivateNotifyResponse {
        private final List<
                ApplePairingNotifyPayloads.PrivateNotify>
                notifies;
        private final byte[][] authenticatedPeerRequestWirePackets;
        final int peerRequestMessageId;
        private boolean destroyed;

        PairingPrivateNotifyResponse(
                List<ApplePairingNotifyPayloads.PrivateNotify>
                        source,
                int peerRequestMessageId) {
            this(source, peerRequestMessageId, new byte[0][]);
        }

        PairingPrivateNotifyResponse(
                List<ApplePairingNotifyPayloads.PrivateNotify>
                        source,
                int peerRequestMessageId,
                byte[][] peerRequestWirePackets) {
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    copied = new ArrayList<>(source.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : source) {
                copied.add(
                        new ApplePairingNotifyPayloads
                                .PrivateNotify(
                                notify.type(),
                                notify.data()));
            }
            byte[][] wireCopy = new byte[peerRequestWirePackets == null ? 0 : peerRequestWirePackets.length][];
            try {
                if (peerRequestWirePackets != null) {
                    for (int i = 0; i < peerRequestWirePackets.length; i++) {
                        byte[] packet = peerRequestWirePackets[i];
                        if (packet == null || packet.length == 0) {
                            throw new IllegalArgumentException(
                                    "Authenticated peer request wire packet is empty");
                        }
                        wireCopy[i] = packet.clone();
                    }
                }
            } catch (RuntimeException error) {
                for (byte[] entry : wireCopy) {
                    wipe(entry);
                }
                for (ApplePairingNotifyPayloads.PrivateNotify notify : copied) {
                    notify.destroy();
                }
                throw error;
            }
            this.notifies = List.copyOf(copied);
            this.authenticatedPeerRequestWirePackets = wireCopy;
            this.peerRequestMessageId =
                    peerRequestMessageId;
        }

        WatchRequestRetryMatcher createRetryMatcher() {
            requireLive();
            if (authenticatedPeerRequestWirePackets.length == 0) {
                throw new IllegalStateException(
                        "Authenticated peer request wire packets are unavailable");
            }
            return new WatchRequestRetryMatcher(authenticatedPeerRequestWirePackets);
        }

        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies() {
            requireLive();
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    copied = new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                copied.add(
                        new ApplePairingNotifyPayloads
                                .PrivateNotify(
                                notify.type(),
                                notify.data()));
            }
            return List.copyOf(copied);
        }

        List<Integer> notifyTypes() {
            requireLive();
            List<Integer> types =
                    new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                types.add(notify.type());
            }
            return List.copyOf(types);
        }

        List<Integer> dataLengths() {
            requireLive();
            List<Integer> lengths =
                    new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                byte[] data = notify.data();
                try {
                    lengths.add(data.length);
                } finally {
                    wipe(data);
                }
            }
            return List.copyOf(lengths);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
            for (byte[] wirePacket : authenticatedPeerRequestWirePackets) {
                wipe(wirePacket);
            }
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Watch private-notify response "
                                + "has been destroyed");
            }
        }
    }

    static final class PairingPrivateNotifyResponseAccumulator {
        private final IkeSaKeys updatedKeys;
        private final int requestMessageId;
        private byte[][] fragments;
        private byte[][] peerRequestWirePackets;
        private int firstInnerPayload;
        private int receivedFragments;
        private boolean requestAcknowledged;
        private PairingPrivateNotifyResponse
                completedPeerRequest;

        PairingPrivateNotifyResponseAccumulator(
                IkeSaKeys updatedKeys) {
            this(updatedKeys, PAIRING_NOTIFIES_MESSAGE_ID);
        }

        PairingPrivateNotifyResponseAccumulator(IkeSaKeys updatedKeys, int requestMessageId) {
            if (updatedKeys == null || (requestMessageId != PAIRING_NOTIFIES_MESSAGE_ID
                    && requestMessageId != OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID)) {
                throw new IllegalArgumentException(
                        "Updated IKE keys are required");
            }
            this.updatedKeys = updatedKeys;
            this.requestMessageId = requestMessageId;
        }

        PairingPrivateNotifyResponse accept(
                byte[] packet) {
            if (isProtectedInformationalHeader(
                    packet,
                    IKE_FLAG_RESPONSE,
                    requestMessageId)) {
                DecryptedIntermediatePart acknowledgement =
                        decryptProtectedPacket(
                                packet,
                                updatedKeys.initiatorSpi,
                                updatedKeys.responderSpi,
                                updatedKeys.skEr,
                                true,
                                EXCHANGE_INFORMATIONAL,
                                requestMessageId);
                if (acknowledgement.fragmented
                        || acknowledgement.firstInnerPayload
                        != IkeV2Codec.PAYLOAD_NONE
                        || acknowledgement.plaintext.length != 0) {
                    throw new IllegalArgumentException(
                            "Pairing private-notify ACK is not empty");
                }
                requestAcknowledged = true;
                return completedPeerRequest;
            }
            if (!isProtectedInformationalHeader(
                    packet,
                    0,
                    PAIRING_NOTIFIES_WATCH_MESSAGE_ID)) {
                throw new IllegalArgumentException(
                        "Unexpected pairing private-notify "
                                + "INFORMATIONAL header");
            }
            DecryptedIntermediatePart part =
                    decryptProtectedPacketWithDirectionFlags(
                            packet,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEr,
                            0,
                            EXCHANGE_INFORMATIONAL,
                            PAIRING_NOTIFIES_WATCH_MESSAGE_ID);
            PairingPrivateNotifyResponse response;
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "Watch private-notify request");
                }
                response = IkeV2PairingExchangeCodec.parsePrivateNotifyPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        PAIRING_NOTIFIES_WATCH_MESSAGE_ID,
                        new byte[][]{packet});
            } else {
                if (fragments == null) {
                    fragments =
                            new byte[part.totalFragments][];
                    peerRequestWirePackets =
                            new byte[part.totalFragments][];
                } else if (fragments.length
                        != part.totalFragments) {
                    throw new IllegalArgumentException(
                            "Watch private-notify fragment "
                                    + "total changed");
                }
                int index = part.fragmentNumber - 1;
                if (fragments[index] != null) {
                    if (!Arrays.equals(
                            fragments[index],
                            part.plaintext)) {
                        throw new IllegalArgumentException(
                                "Watch private-notify fragment "
                                        + "retransmission differs");
                    }
                    return requestAcknowledged
                            ? completedPeerRequest
                            : null;
                }
                fragments[index] = part.plaintext;
                peerRequestWirePackets[index] = packet.clone();
                receivedFragments++;
                if (part.fragmentNumber == 1) {
                    firstInnerPayload =
                            part.firstInnerPayload;
                }
                if (receivedFragments
                        != fragments.length) {
                    return null;
                }
                ByteArrayOutputStream plaintext =
                        new ByteArrayOutputStream();
                for (byte[] fragment : fragments) {
                    if (fragment == null) {
                        return null;
                    }
                    plaintext.writeBytes(fragment);
                }
                response = IkeV2PairingExchangeCodec.parsePrivateNotifyPlaintext(
                        firstInnerPayload,
                        plaintext.toByteArray(),
                        PAIRING_NOTIFIES_WATCH_MESSAGE_ID,
                        peerRequestWirePackets);
            }
            if (completedPeerRequest != null) {
                response.destroy();
                throw new IllegalArgumentException(
                        "Watch sent duplicate private-notify request");
            }
            completedPeerRequest = response;
            return requestAcknowledged
                    ? completedPeerRequest
                    : null;
        }

        boolean requestAcknowledged() {
            return requestAcknowledged;
        }

        void destroy() {
            if (fragments != null) {
                for (byte[] fragment : fragments) {
                    wipe(fragment);
                }
            }
            if (peerRequestWirePackets != null) {
                for (byte[] wirePacket : peerRequestWirePackets) {
                    wipe(wirePacket);
                }
            }
        }

        private static boolean
                isProtectedInformationalHeader(
                byte[] packet,
                int directionFlags,
                int messageId) {
            return packet != null
                    && packet.length >= IKE_HEADER_LENGTH
                    && unsigned(packet[18])
                    == EXCHANGE_INFORMATIONAL
                    && (unsigned(packet[19])
                    & (IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE))
                    == directionFlags
                    && be32(packet, 20) == messageId;
        }
    }

    static SecretWithEncapsulation encapsulateForTest(
            SecureRandom random,
            byte[] publicKey) {
        MLKEMPublicKeyParameters parameters =
                new MLKEMPublicKeyParameters(
                        MLKEMParameters.ml_kem_1024,
                        publicKey);
        return new MLKEMGenerator(random)
                .generateEncapsulated(parameters);
    }
}
