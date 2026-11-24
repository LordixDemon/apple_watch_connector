package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


/** Control-channel IKE_AUTH and PIN-auth-method IKEv2 codecs. */
final class IkeV2ControlAuthCodec {
    private IkeV2ControlAuthCodec() {}

    static IkeV2SessionCrypto.IkeAuthRequest createControlIkeAuthRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        IkeV2SaInitCodec.validateIkeAuthState(
                random,
                initiator,
                response,
                updatedKeys,
                intAuthI,
                intAuthR);

        byte[] idiBody = IkeV2SaInitCodec.buildIdentificationBody(
                IkeV2SessionCrypto.ID_TYPE_NULL,
                new byte[0]);
        byte[] idrBody = IkeV2SaInitCodec.buildIdentificationBody(
                IkeV2SessionCrypto.ID_TYPE_KEY_ID,
                IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID_BYTES);
        byte[] initiatorSignedOctets = IkeV2SessionCrypto.concatenate(
                initiator.ikePacket,
                response.responderNonce,
                IkeV2SessionCrypto.prf(updatedKeys.skPi, idiBody),
                IkeV2SaInitCodec.buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID));
        byte[] authData = IkeV2SaInitCodec.computeNullAuthenticationData(
                updatedKeys.skPi,
                initiatorSignedOctets);

        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream();
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                idiBody));
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER,
                IkeV2SaInitCodec.buildNotifyBody(
                        0,
                        IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                        new byte[0])));
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2SessionCrypto.PAYLOAD_AUTH,
                idrBody));
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2SaInitCodec.buildAuthenticationBody(
                        IkeV2SessionCrypto.AUTH_METHOD_NULL,
                        authData)));

        List<byte[]> packets = IkeV2SessionCrypto.encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEi,
                false,
                IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID,
                plaintext.toByteArray(),
                IkeV2SessionCrypto.PAYLOAD_ID_INITIATOR,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        return new IkeV2SessionCrypto.IkeAuthRequest(
                packets,
                plaintext.toByteArray(),
                authData);
    }

    static List<byte[]> createControlIkeAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        IkeV2SaInitCodec.validateIkeAuthState(
                random,
                initiator,
                response,
                updatedKeys,
                intAuthI,
                intAuthR);
        byte[] idrBody = IkeV2SaInitCodec.buildIdentificationBody(
                IkeV2SessionCrypto.ID_TYPE_KEY_ID,
                IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID_BYTES);
        byte[] responderSignedOctets = IkeV2SessionCrypto.concatenate(
                response.packet,
                initiator.nonce,
                IkeV2SessionCrypto.prf(updatedKeys.skPr, idrBody),
                IkeV2SaInitCodec.buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID));
        byte[] authData = IkeV2SaInitCodec.computeNullAuthenticationData(
                updatedKeys.skPr,
                responderSignedOctets);
        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream();
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2SessionCrypto.PAYLOAD_AUTH,
                idrBody));
        plaintext.writeBytes(IkeV2SaInitCodec.genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2SaInitCodec.buildAuthenticationBody(
                        IkeV2SessionCrypto.AUTH_METHOD_NULL,
                        authData)));
        return IkeV2SessionCrypto.encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                true,
                IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID,
                plaintext.toByteArray(),
                IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static IkeV2SessionCrypto.PinAuthMethodRequest createPinAuthMethodRequest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method request state is required");
        }
        byte[] notifyData = new byte[]{
                IkeV2SessionCrypto.NRTLV_AUTH_METHOD,
                0,
                1,
                IkeV2SessionCrypto.PAIRING_AUTH_METHOD_PIN
        };
        byte[] plaintext = IkeV2SaInitCodec.genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2SaInitCodec.buildNotifyBody(
                        0,
                        IkeV2SessionCrypto.PRIVATE_NOTIFY_AUTH_METHOD_REQUEST,
                        notifyData));
        List<byte[]> packets = IkeV2SessionCrypto.encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEi,
                false,
                IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                IkeV2SessionCrypto.PIN_METHOD_MESSAGE_ID,
                plaintext,
                IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        return new IkeV2SessionCrypto.PinAuthMethodRequest(
                packets,
                plaintext);
    }

    static List<byte[]> createPinAuthMethodResponseForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] pinSalt) {
        if (random == null
                || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method response state is required");
        }
        byte[] plaintext =
                buildPinAuthMethodResponsePlaintext(pinSalt);
        return IkeV2SessionCrypto.encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                true,
                IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                IkeV2SessionCrypto.PIN_METHOD_MESSAGE_ID,
                plaintext,
                IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static List<byte[]> createPinAuthMethodEmptyResponseForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method acknowledgement state is required");
        }
        return List.of(encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEr,
                IkeV2SessionCrypto.IKE_FLAG_RESPONSE,
                IkeV2SessionCrypto.PIN_METHOD_MESSAGE_ID));
    }

    static List<byte[]> createPinAuthMethodWatchRequestForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] pinSalt) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "Watch PIN auth-method request state is required");
        }
        byte[] plaintext =
                buildPinAuthMethodResponsePlaintext(pinSalt);
        return IkeV2SessionCrypto.encryptProtectedPayloadWithDirectionFlags(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                0,
                IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                IkeV2SessionCrypto.PIN_METHOD_WATCH_MESSAGE_ID,
                plaintext,
                IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static List<byte[]> createWatchInformationalAcknowledgement(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            int watchMessageId) {
        if (random == null
                || updatedKeys == null
                || watchMessageId < 0) {
            throw new IllegalArgumentException(
                    "Watch INFORMATIONAL acknowledgement state "
                            + "is required");
        }
        return List.of(encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEi,
                IkeV2SessionCrypto.IKE_FLAG_INITIATOR | IkeV2SessionCrypto.IKE_FLAG_RESPONSE,
                watchMessageId));
    }

    static byte[] buildPinAuthMethodResponsePlaintext(
            byte[] pinSalt) {
        if (pinSalt == null
                || pinSalt.length < IkeV2SessionCrypto.MINIMUM_PIN_SALT_LENGTH) {
            throw new IllegalArgumentException(
                    "PIN auth-method response requires a 32-byte salt");
        }
        ByteArrayOutputStream notifyData =
                new ByteArrayOutputStream(7 + pinSalt.length);
        notifyData.write(IkeV2SessionCrypto.NRTLV_AUTH_METHOD);
        IkeV2SessionCrypto.writeBe16(notifyData, 1);
        notifyData.write(IkeV2SessionCrypto.PAIRING_AUTH_METHOD_PIN);
        notifyData.write(IkeV2SessionCrypto.NRTLV_PIN_SALT);
        IkeV2SessionCrypto.writeBe16(notifyData, pinSalt.length);
        notifyData.writeBytes(pinSalt);
        byte[] plaintext = IkeV2SaInitCodec.genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2SaInitCodec.buildNotifyBody(
                        0,
                        IkeV2SessionCrypto.PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE,
                        notifyData.toByteArray()));
        return plaintext;
    }

    static byte[] encryptEmptyProtectedInformational(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int messageId) {
        byte[] iv = new byte[IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH];
        random.nextBytes(iv);
        return IkeV2SessionCrypto.encryptProtectedPacket(
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                directionFlags,
                IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                messageId,
                IkeV2SessionCrypto.PAYLOAD_SK,
                IkeV2Codec.PAYLOAD_NONE,
                0,
                0,
                new byte[0],
                encryptionKeyMaterial,
                iv);
    }

    static IkeV2SessionCrypto.IkeAuthResponse parseControlIkeAuthPlaintext(
            int firstPayload,
            byte[] plaintext,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        IkeV2SessionCrypto.requireLength("initiator IntAuth", intAuthI, IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        IkeV2SessionCrypto.requireLength("responder IntAuth", intAuthR, IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        if (firstPayload == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "IKE_AUTH response plaintext is empty");
        }

        byte[] idrBody = null;
        byte[] authData = null;
        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                throw new IllegalArgumentException(
                        "IKE_AUTH payload header is truncated");
            }
            int nextPayload = IkeV2SessionCrypto.unsigned(plaintext[offset]);
            int payloadFlags = IkeV2SessionCrypto.unsigned(plaintext[offset + 1]);
            int payloadLength = IkeV2SessionCrypto.be16(plaintext, offset + 2);
            if (payloadLength < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength > plaintext.length) {
                throw new IllegalArgumentException(
                        "IKE_AUTH payload length is invalid");
            }
            payloadTypes.add(payloadType);
            int bodyOffset = offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER) {
                if (idrBody != null
                        || bodyLength
                        != 4 + IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID_BYTES.length
                        || IkeV2SessionCrypto.unsigned(plaintext[bodyOffset])
                        != IkeV2SessionCrypto.ID_TYPE_KEY_ID
                        || plaintext[bodyOffset + 1] != 0
                        || plaintext[bodyOffset + 2] != 0
                        || plaintext[bodyOffset + 3] != 0
                        || !Arrays.equals(
                        IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID_BYTES,
                        Arrays.copyOfRange(
                                plaintext,
                                bodyOffset + 4,
                                offset + payloadLength))) {
                    throw new IllegalArgumentException(
                            "Unexpected control IKE_AUTH responder ID");
                }
                idrBody = Arrays.copyOfRange(
                        plaintext,
                        bodyOffset,
                        offset + payloadLength);
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_AUTH) {
                if (authData != null
                        || bodyLength
                        != 4 + IkeV2SessionCrypto.PRF_SHA512_LENGTH
                        || IkeV2SessionCrypto.unsigned(plaintext[bodyOffset])
                        != IkeV2SessionCrypto.AUTH_METHOD_NULL
                        || plaintext[bodyOffset + 1] != 0
                        || plaintext[bodyOffset + 2] != 0
                        || plaintext[bodyOffset + 3] != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected control IKE_AUTH authentication");
                }
                authData = Arrays.copyOfRange(
                        plaintext,
                        bodyOffset + 4,
                        offset + payloadLength);
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY) {
                if (bodyLength < 4) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH notify payload is truncated");
                }
                int spiSize = IkeV2SessionCrypto.unsigned(plaintext[bodyOffset + 1]);
                if (bodyLength < 4 + spiSize) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH notify SPI exceeds payload");
                }
                int notifyType = IkeV2SessionCrypto.be16(plaintext, bodyOffset + 2);
                notifyTypes.add(notifyType);
                if (notifyType < 0x4000) {
                    throw new IllegalArgumentException(
                            String.format(
                                    Locale.US,
                                    "Watch returned IKE_AUTH error "
                                            + "notify 0x%04X",
                                    notifyType));
                }
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_SA
                    || payloadType == IkeV2SessionCrypto.PAYLOAD_TS_INITIATOR
                    || payloadType == IkeV2SessionCrypto.PAYLOAD_TS_RESPONDER) {
                throw new IllegalArgumentException(
                        "Watch returned a Child-SA payload to childless "
                                + "IKE_AUTH");
            } else if ((payloadFlags & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "Unsupported critical IKE_AUTH payload "
                                + payloadType);
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != plaintext.length) {
            throw new IllegalArgumentException(
                    "Trailing bytes follow IKE_AUTH payload chain");
        }
        if (idrBody == null || authData == null) {
            throw new IllegalArgumentException(
                    "IKE_AUTH response lacks IDr or AUTH");
        }

        byte[] responderSignedOctets = IkeV2SessionCrypto.concatenate(
                response.packet,
                initiator.nonce,
                IkeV2SessionCrypto.prf(updatedKeys.skPr, idrBody),
                IkeV2SaInitCodec.buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID));
        byte[] expectedAuthData =
                IkeV2SaInitCodec.computeNullAuthenticationData(
                        updatedKeys.skPr,
                        responderSignedOctets);
        if (!MessageDigest.isEqual(
                expectedAuthData,
                authData)) {
            throw new IllegalArgumentException(
                    "IKE_AUTH responder NULL-AUTH verification failed");
        }
        return new IkeV2SessionCrypto.IkeAuthResponse(
                payloadTypes,
                notifyTypes,
                authData.length);
    }

    static IkeV2SessionCrypto.PinAuthMethodResponse parsePinAuthMethodPlaintext(
            int firstPayload,
            byte[] plaintext,
            int peerRequestMessageId) {
        if (firstPayload == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "PIN auth-method response plaintext is empty");
        }

        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int authMethod = -1;
        byte[] pinSalt = null;
        boolean responseNotifySeen = false;
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                throw new IllegalArgumentException(
                        "PIN auth-method payload header is truncated");
            }
            int nextPayload = IkeV2SessionCrypto.unsigned(plaintext[offset]);
            int payloadFlags = IkeV2SessionCrypto.unsigned(plaintext[offset + 1]);
            int payloadLength = IkeV2SessionCrypto.be16(plaintext, offset + 2);
            if (payloadLength < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength > plaintext.length) {
                throw new IllegalArgumentException(
                        "PIN auth-method payload length is invalid");
            }
            payloadTypes.add(payloadType);
            int bodyOffset = offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY) {
                if (bodyLength < 4) {
                    throw new IllegalArgumentException(
                            "PIN auth-method notify is truncated");
                }
                int protocolId = IkeV2SessionCrypto.unsigned(plaintext[bodyOffset]);
                int spiSize = IkeV2SessionCrypto.unsigned(plaintext[bodyOffset + 1]);
                if (bodyLength < 4 + spiSize) {
                    throw new IllegalArgumentException(
                            "PIN auth-method notify SPI exceeds payload");
                }
                int notifyType = IkeV2SessionCrypto.be16(plaintext, bodyOffset + 2);
                notifyTypes.add(notifyType);
                if (notifyType < 0x4000) {
                    throw new IllegalArgumentException(
                            String.format(
                                    Locale.US,
                                    "Watch returned INFORMATIONAL error "
                                            + "notify 0x%04X",
                                    notifyType));
                }
                if (notifyType
                        == IkeV2SessionCrypto.PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE) {
                    if (responseNotifySeen
                            || protocolId != 0
                            || spiSize != 0) {
                        throw new IllegalArgumentException(
                                "Unexpected PIN auth-method response notify");
                    }
                    responseNotifySeen = true;
                    int tlvOffset = bodyOffset + 4;
                    int payloadEnd = offset + payloadLength;
                    while (tlvOffset < payloadEnd) {
                        if (tlvOffset + 3 > payloadEnd) {
                            throw new IllegalArgumentException(
                                    "PIN auth-method NRTLV header "
                                            + "is truncated");
                        }
                        int tlvType = IkeV2SessionCrypto.unsigned(plaintext[tlvOffset]);
                        int tlvLength = IkeV2SessionCrypto.be16(
                                plaintext,
                                tlvOffset + 1);
                        tlvOffset += 3;
                        if (tlvOffset + tlvLength > payloadEnd) {
                            throw new IllegalArgumentException(
                                    "PIN auth-method NRTLV value "
                                            + "is truncated");
                        }
                        if (tlvType == IkeV2SessionCrypto.NRTLV_AUTH_METHOD) {
                            if (authMethod != -1 || tlvLength != 1) {
                                throw new IllegalArgumentException(
                                        "Duplicate or malformed PIN "
                                                + "auth-method NRTLV");
                            }
                            authMethod =
                                    IkeV2SessionCrypto.unsigned(plaintext[tlvOffset]);
                        } else if (tlvType == IkeV2SessionCrypto.NRTLV_PIN_SALT) {
                            if (pinSalt != null
                                    || tlvLength
                                    < IkeV2SessionCrypto.MINIMUM_PIN_SALT_LENGTH) {
                                throw new IllegalArgumentException(
                                        "Duplicate or short PIN salt "
                                                + "NRTLV");
                            }
                            pinSalt = Arrays.copyOfRange(
                                    plaintext,
                                    tlvOffset,
                                    tlvOffset + tlvLength);
                        }
                        tlvOffset += tlvLength;
                    }
                }
            } else if ((payloadFlags & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "Unsupported critical INFORMATIONAL payload "
                                + payloadType);
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != plaintext.length) {
            throw new IllegalArgumentException(
                    "Trailing bytes follow PIN auth-method payload chain");
        }
        if (!responseNotifySeen
                || authMethod != IkeV2SessionCrypto.PAIRING_AUTH_METHOD_PIN
                || pinSalt == null) {
            throw new IllegalArgumentException(
                    "Watch did not select PIN auth with a salt");
        }
        return new IkeV2SessionCrypto.PinAuthMethodResponse(
                payloadTypes,
                notifyTypes,
                authMethod,
                pinSalt,
                peerRequestMessageId);
    }
}
