package dev.applewatchandroid.bridge;

import org.bouncycastle.math.ec.rfc7748.X448;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;


/** IKEv2 SA_INIT / ML-KEM KE / INT_AUTH / payload framing helpers for Apple Watch channels. */
final class IkeV2SaInitCodec {
    private IkeV2SaInitCodec() {}

    static IkeV2SessionCrypto.ControlSaInitResponse parseControlSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        return parseSaInitResponse(
                packet,
                expectedInitiatorSpi,
                false);
    }

    static IkeV2SessionCrypto.ControlSaInitResponse parsePairingSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        return parseSaInitResponse(
                packet,
                expectedInitiatorSpi,
                true);
    }

    static IkeV2SessionCrypto.ControlSaInitResponse parseOpticalPairingSaInitResponse(
            byte[] packet, byte[] expectedInitiatorSpi) {
        IkeV2SessionCrypto.ControlSaInitResponse response = parseSaInitResponse(packet, expectedInitiatorSpi, false);
        IkeV2Codec.IkePacketSummary summary =
                IkeV2Codec.parseSaInitResponse(packet, expectedInitiatorSpi);
        if (summary.notifyTypes.contains(IkeV2Codec.NOTIFY_SECURE_PASSWORD_METHODS)
                || summary.notifyTypes.contains(IkeV2Codec.NOTIFY_USE_PPK)) {
            throw new IllegalArgumentException("Watch selected a PIN profile for optical PSK pairing");
        }
        return response;
    }

    static IkeV2SessionCrypto.ControlSaInitResponse parseSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            boolean pairing) {
        IkeV2Codec.IkePacketSummary summary =
                IkeV2Codec.parseSaInitResponse(
                        packet,
                        expectedInitiatorSpi);

        byte[] responderNonce = null;
        byte[] responderX448 = null;
        boolean selectedProposalSeen = false;
        boolean securePasswordSelected = false;
        boolean ppkSupported = false;
        int payloadType = IkeV2SessionCrypto.unsigned(packet[16]);
        int offset = IkeV2SessionCrypto.IKE_HEADER_LENGTH;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            int nextPayload = IkeV2SessionCrypto.unsigned(packet[offset]);
            int payloadLength = IkeV2SessionCrypto.be16(packet, offset + 2);
            if (payloadType == IkeV2Codec.PAYLOAD_SA) {
                parseSelectedControlProposal(
                        packet,
                        offset,
                        payloadLength);
                selectedProposalSeen = true;
            } else if (payloadType == IkeV2Codec.PAYLOAD_KE) {
                if (payloadLength != 8 + 56
                        || IkeV2SessionCrypto.be16(packet, offset + 4)
                        != IkeV2Codec.DH_GROUP_CURVE_448
                        || IkeV2SessionCrypto.be16(packet, offset + 6) != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected control response KE payload");
                }
                responderX448 = Arrays.copyOfRange(
                        packet,
                        offset + 8,
                        offset + payloadLength);
            } else if (payloadType == IkeV2Codec.PAYLOAD_NONCE) {
                if (payloadLength != 4 + 32) {
                    throw new IllegalArgumentException(
                            "Unexpected control response nonce length");
                }
                responderNonce = Arrays.copyOfRange(
                        packet,
                        offset + 4,
                        offset + payloadLength);
            } else if (payloadType == IkeV2Codec.PAYLOAD_NOTIFY) {
                int protocolId = IkeV2SessionCrypto.unsigned(packet[offset + 4]);
                int spiSize = IkeV2SessionCrypto.unsigned(packet[offset + 5]);
                int notifyType = IkeV2SessionCrypto.be16(packet, offset + 6);
                boolean physicalWatchChildlessQuirk =
                        notifyType
                                == IkeV2Codec
                                .NOTIFY_CHILDLESS_IKEV2_SUPPORTED
                                && protocolId == 1;
                if ((!physicalWatchChildlessQuirk
                        && protocolId != 0)
                        || spiSize != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected IKE_SA_INIT notify SPI");
                }
                if (notifyType
                        == IkeV2Codec.NOTIFY_SECURE_PASSWORD_METHODS) {
                    if (protocolId != 0
                            || payloadLength != 10
                            || IkeV2SessionCrypto.be16(packet, offset + 8)
                            != IkeV2Codec.SECURE_PASSWORD_SPAKE2_PLUS) {
                        throw new IllegalArgumentException(
                                "Watch selected an unexpected "
                                        + "secure-password method");
                    }
                    securePasswordSelected = true;
                } else if (notifyType
                        == IkeV2Codec.NOTIFY_USE_PPK) {
                    if (protocolId != 0
                            || payloadLength != 8) {
                        throw new IllegalArgumentException(
                                "Watch returned malformed USE_PPK");
                    }
                    ppkSupported = true;
                }
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (!selectedProposalSeen
                || responderX448 == null
                || responderNonce == null
                || (pairing && !securePasswordSelected)
                || (pairing && !ppkSupported)
                || !summary.notifyTypes.contains(
                IkeV2Codec.NOTIFY_CHILDLESS_IKEV2_SUPPORTED)
                || !summary.notifyTypes.contains(
                IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED)) {
            throw new IllegalArgumentException(
                    (pairing ? "Pairing" : "Control")
                            + " response lacks selected SA, X448, nonce, "
                            + (pairing
                            ? "SPAKE2+, PPK, "
                            : "")
                            + "childless-IKEv2 support, or "
                            + "intermediate-exchange support");
        }
        return new IkeV2SessionCrypto.ControlSaInitResponse(
                summary.responderSpi,
                responderNonce,
                responderX448,
                packet);
    }

    static void validateIkeAuthState(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        if (random == null
                || initiator == null
                || response == null
                || updatedKeys == null
                || response.packet.length == 0) {
            throw new IllegalArgumentException(
                    "Complete IKE_AUTH state is required");
        }
        IkeV2SessionCrypto.requireLength("initiator IntAuth", intAuthI, IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        IkeV2SessionCrypto.requireLength("responder IntAuth", intAuthR, IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        if (!Arrays.equals(
                initiator.initiatorSpi,
                updatedKeys.initiatorSpi)
                || !Arrays.equals(
                response.responderSpi,
                updatedKeys.responderSpi)
                || !Arrays.equals(
                initiator.nonce,
                updatedKeys.initiatorNonce)
                || !Arrays.equals(
                response.responderNonce,
                updatedKeys.responderNonce)) {
            throw new IllegalArgumentException(
                    "IKE_AUTH transcript and key schedule do not match");
        }
    }

    static byte[] computeIntermediateIntAuth(
            IkeV2SessionCrypto.IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        byte[] input = buildIntermediateIntAuthInput(
                keys.initiatorSpi,
                keys.responderSpi,
                response,
                firstInnerPayload,
                protectedPayloadFlags,
                plaintext);
        return IkeV2SessionCrypto.prf(
                response ? keys.skPr : keys.skPi,
                input);
    }

    static byte[] computeIntermediateIntAuthForProfile(
            IkeV2SessionCrypto.IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        if (keys == null) {
            throw new IllegalArgumentException(
                    "IKE keys are required");
        }
        return computeIntermediateIntAuth(
                keys,
                response,
                firstInnerPayload,
                protectedPayloadFlags,
                plaintext);
    }

    static byte[] buildIntermediateIntAuthInput(
            byte[] initiatorSpi,
            byte[] responderSpi,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        IkeV2SessionCrypto.requireLength("initiator SPI", initiatorSpi, 8);
        IkeV2SessionCrypto.requireLength("responder SPI", responderSpi, 8);
        if (plaintext == null
                || firstInnerPayload == IkeV2Codec.PAYLOAD_NONE
                || (protectedPayloadFlags & ~0xFF) != 0) {
            throw new IllegalArgumentException(
                    "Invalid IKE_INTERMEDIATE IntAuth input");
        }
        int adjustedPayloadLength =
                IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH + plaintext.length;
        int adjustedPacketLength =
                IkeV2SessionCrypto.IKE_HEADER_LENGTH + adjustedPayloadLength;
        ByteArrayOutputStream input =
                new ByteArrayOutputStream(
                        adjustedPacketLength);
        input.writeBytes(initiatorSpi);
        input.writeBytes(responderSpi);
        input.write(IkeV2SessionCrypto.PAYLOAD_SK);
        input.write(0x20);
        input.write(IkeV2SessionCrypto.EXCHANGE_IKE_INTERMEDIATE);
        input.write(response
                ? IkeV2SessionCrypto.IKE_FLAG_RESPONSE
                : IkeV2SessionCrypto.IKE_FLAG_INITIATOR);
        IkeV2SessionCrypto.writeBe32(input, 1);
        IkeV2SessionCrypto.writeBe32(input, adjustedPacketLength);
        input.write(firstInnerPayload);
        input.write(protectedPayloadFlags);
        IkeV2SessionCrypto.writeBe16(input, adjustedPayloadLength);
        input.writeBytes(plaintext);
        return input.toByteArray();
    }

    static byte[] buildIkeAuthIntAuth(
            byte[] intAuthI,
            byte[] intAuthR,
            int messageIdValue) {
        if (messageIdValue < 0) {
            throw new IllegalArgumentException(
                    "IKE_AUTH message ID must be nonnegative");
        }
        ByteArrayOutputStream messageId =
                new ByteArrayOutputStream(4);
        IkeV2SessionCrypto.writeBe32(messageId, messageIdValue);
        return IkeV2SessionCrypto.concatenate(
                intAuthI,
                intAuthR,
                messageId.toByteArray());
    }

    static byte[] computeNullAuthenticationData(
            byte[] primeKey,
            byte[] signedOctets) {
        return IkeV2SessionCrypto.prf(
                IkeV2SessionCrypto.prf(primeKey, IkeV2SessionCrypto.NULL_AUTH_PAD),
                signedOctets);
    }

    static byte[] buildIdentificationBody(
            int identificationType,
            byte[] identificationData) {
        if (identificationData == null
                || identificationType <= 0
                || identificationType > 0xFF) {
            throw new IllegalArgumentException(
                    "Invalid IKE identification");
        }
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + identificationData.length);
        body.write(identificationType);
        body.write(0);
        body.write(0);
        body.write(0);
        body.writeBytes(identificationData);
        return body.toByteArray();
    }

    static byte[] buildAuthenticationBody(
            int authenticationMethod,
            byte[] authenticationData) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + authenticationData.length);
        body.write(authenticationMethod);
        body.write(0);
        body.write(0);
        body.write(0);
        body.writeBytes(authenticationData);
        return body.toByteArray();
    }

    static byte[] buildNotifyBody(
            int protocolId,
            int notifyType,
            byte[] data) {
        if (protocolId < 0
                || protocolId > 0xFF
                || notifyType < 0
                || notifyType > 0xFFFF
                || data == null) {
            throw new IllegalArgumentException(
                    "Invalid IKE notification");
        }
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(4 + data.length);
        body.write(protocolId);
        body.write(0);
        IkeV2SessionCrypto.writeBe16(body, notifyType);
        body.writeBytes(data);
        return body.toByteArray();
    }

    static byte[] genericPayload(
            int nextPayload,
            byte[] body) {
        int length = IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH + body.length;
        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(length);
        payload.write(nextPayload);
        payload.write(0);
        IkeV2SessionCrypto.writeBe16(payload, length);
        payload.writeBytes(body);
        return payload.toByteArray();
    }

    static byte[] buildKePayload(byte[] keyExchangeData) {
        int length = 8 + keyExchangeData.length;
        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(length);
        payload.write(IkeV2Codec.PAYLOAD_NONE);
        payload.write(0);
        IkeV2SessionCrypto.writeBe16(payload, length);
        IkeV2SessionCrypto.writeBe16(payload, IkeV2SessionCrypto.ML_KEM_1024_METHOD);
        IkeV2SessionCrypto.writeBe16(payload, 0);
        payload.writeBytes(keyExchangeData);
        return payload.toByteArray();
    }

    static byte[] parseMlKemKePayload(
            int firstPayload,
            byte[] plaintext) {
        if (firstPayload != IkeV2Codec.PAYLOAD_KE
                || plaintext.length
                != 8 + IkeV2SessionCrypto.ML_KEM_1024_CIPHERTEXT_LENGTH
                || IkeV2SessionCrypto.unsigned(plaintext[0])
                != IkeV2Codec.PAYLOAD_NONE
                || IkeV2SessionCrypto.be16(plaintext, 2) != plaintext.length
                || IkeV2SessionCrypto.be16(plaintext, 4)
                != IkeV2SessionCrypto.ML_KEM_1024_METHOD
                || IkeV2SessionCrypto.be16(plaintext, 6) != 0) {
            throw new IllegalArgumentException(
                    "Unexpected ML-KEM-1024 KE response payload");
        }
        return Arrays.copyOfRange(plaintext, 8, plaintext.length);
    }

    static void parseSelectedControlProposal(
            byte[] packet,
            int payloadOffset,
            int payloadLength) {
        if (payloadLength < 4 + 8) {
            throw new IllegalArgumentException(
                    "Selected control SA proposal is truncated");
        }
        int proposalOffset =
                payloadOffset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
        int proposalLength = IkeV2SessionCrypto.be16(packet, proposalOffset + 2);
        if (proposalOffset + proposalLength
                != payloadOffset + payloadLength
                || IkeV2SessionCrypto.unsigned(packet[proposalOffset]) != 0
                || IkeV2SessionCrypto.unsigned(packet[proposalOffset + 4]) != 1
                || IkeV2SessionCrypto.unsigned(packet[proposalOffset + 5]) != 1
                || IkeV2SessionCrypto.unsigned(packet[proposalOffset + 6]) != 0) {
            throw new IllegalArgumentException(
                    "Unexpected selected control SA proposal header");
        }
        int declaredTransforms =
                IkeV2SessionCrypto.unsigned(packet[proposalOffset + 7]);
        int transformOffset = proposalOffset + 8;
        int transforms = 0;
        boolean aesGcm256 = false;
        boolean prfSha512 = false;
        boolean additionalMlKem1024 = false;
        boolean x448 = false;
        while (transformOffset
                < proposalOffset + proposalLength) {
            if (transformOffset + 8
                    > proposalOffset + proposalLength) {
                throw new IllegalArgumentException(
                        "Selected SA transform is truncated");
            }
            int nextTransform =
                    IkeV2SessionCrypto.unsigned(packet[transformOffset]);
            int transformLength =
                    IkeV2SessionCrypto.be16(packet, transformOffset + 2);
            if (transformLength < 8
                    || transformOffset + transformLength
                    > proposalOffset + proposalLength) {
                throw new IllegalArgumentException(
                        "Selected SA transform length is invalid");
            }
            int type = IkeV2SessionCrypto.unsigned(packet[transformOffset + 4]);
            int id = IkeV2SessionCrypto.be16(packet, transformOffset + 6);
            byte[] attributes = Arrays.copyOfRange(
                    packet,
                    transformOffset + 8,
                    transformOffset + transformLength);
            if (type == 1
                    && id == 20
                    && Arrays.equals(
                    attributes,
                    new byte[]{
                            (byte) 0x80,
                            0x0E,
                            0x01,
                            0x00
                    })) {
                aesGcm256 = true;
            } else if (type == 2
                    && id == 7
                    && attributes.length == 0) {
                prfSha512 = true;
            } else if (type == 6
                    && id == IkeV2SessionCrypto.ML_KEM_1024_METHOD
                    && attributes.length == 0) {
                additionalMlKem1024 = true;
            } else if (type == 4
                    && id == IkeV2Codec.DH_GROUP_CURVE_448
                    && attributes.length == 0) {
                x448 = true;
            } else {
                throw new IllegalArgumentException(
                        "Unexpected selected control SA transform "
                                + type
                                + "/"
                                + id);
            }
            transforms++;
            transformOffset += transformLength;
            if (transformOffset
                    < proposalOffset + proposalLength
                    && nextTransform != 3) {
                throw new IllegalArgumentException(
                        "Selected SA transform chain ended early");
            }
            if (transformOffset
                    == proposalOffset + proposalLength
                    && nextTransform != 0) {
                throw new IllegalArgumentException(
                        "Selected SA transform chain has no end");
            }
        }
        if (transforms != declaredTransforms
                || transforms != 4
                || !aesGcm256
                || !prfSha512
                || !additionalMlKem1024
                || !x448) {
            throw new IllegalArgumentException(
                    "Watch selected an unexpected control proposal");
        }
    }
}
