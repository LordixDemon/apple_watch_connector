package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * First post-connect ordinary-IKE private-metadata INFORMATIONAL exchange.
 */
final class OrdinaryIkeInformational {
    static final int MESSAGE_ID = 3;
    static final int EMPTY_RESPONSE_PACKET_LENGTH = 57;
    static final int MAXIMUM_PACKET_SIZE = 1280;

    private static final int NOTIFY_ALWAYS_ON_WIFI = 0xc8c9;
    private static final int NOTIFY_LINK_DIRECTOR_MESSAGE = 0xc60e;
    private static final int CURRENT_TERMINUS_VERSION = 24;
    private static final int LINK_DIRECTOR_VERSION = 2;
    private static final int LINK_DIRECTOR_HEADER_LENGTH = 16;
    private static final int LINK_DIRECTOR_TLV_HEADER_LENGTH = 3;
    private static final int MAXIMUM_LINK_DIRECTOR_TLVS = 32;

    private OrdinaryIkeInformational() {
    }

    static Request createRequest(
            SecureRandom random,
            OrdinaryIkeAuth.ResponderProfile localProfile,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        return createRequest(
                random,
                localProfile,
                keys,
                MESSAGE_ID);
    }

    static Request createRequest(
            SecureRandom random,
            OrdinaryIkeAuth.ResponderProfile localProfile,
            IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId) {
        requireState(
                random,
                localProfile,
                keys);
        requireMessageId(
                messageId);
        byte[] plaintext =
                buildMetadataPlaintext(
                        localProfile);
        try {
            List<byte[]> packets =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            keys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                            messageId,
                            plaintext,
                            IkeV2Codec.PAYLOAD_NOTIFY,
                            MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1) {
                throw new IllegalStateException(
                        "Target metadata INFORMATIONAL "
                                + "unexpectedly fragmented");
            }
            return new Request(
                    packets.get(0),
                    plaintext);
        } finally {
            wipe(plaintext);
        }
    }

    static Response authenticateRequestAndCreateResponse(
            SecureRandom random,
            byte[] expectedRemotePrelude,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] requestPacket) {
        return authenticateRequestAndCreateResponse(
                random,
                expectedRemotePrelude,
                keys,
                requestPacket,
                MESSAGE_ID);
    }

    static Response authenticateRequestAndCreateResponse(
            SecureRandom random,
            byte[] expectedRemotePrelude,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] requestPacket,
            int messageId) {
        if (random == null || keys == null) {
            throw new IllegalArgumentException(
                    "Complete INFORMATIONAL responder "
                            + "state is required");
        }
        requirePrelude(
                expectedRemotePrelude);
        requireMessageId(
                messageId);
        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        requestPacket,
                        keys.initiatorSpi,
                        keys.responderSpi,
                        keys.skEi,
                        false,
                        IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                        messageId);
        if (decrypted.fragmented
                || decrypted.protectedPayloadFlags != 0
                || decrypted.firstInnerPayload
                != IkeV2Codec.PAYLOAD_NOTIFY) {
            throw new IllegalArgumentException(
                    "Metadata INFORMATIONAL request "
                            + "has invalid SK framing");
        }
        OrdinaryIkeAuth.ResponderMetadata metadata =
                parseMetadataPlaintext(
                        decrypted.plaintext,
                        expectedRemotePrelude);
        byte[] responsePacket = null;
        try {
            responsePacket =
                    IkeV2SessionCrypto
                            .encryptEmptyProtectedPayload(
                                    random,
                                    keys.initiatorSpi,
                                    keys.responderSpi,
                                    keys.skEr,
                                    true,
                                    IkeV2SessionCrypto
                                            .EXCHANGE_INFORMATIONAL,
                                    messageId);
            if (responsePacket.length
                    != EMPTY_RESPONSE_PACKET_LENGTH) {
                throw new IllegalStateException(
                        "Empty INFORMATIONAL response "
                                + "has an unexpected length");
            }
            return new Response(
                    responsePacket,
                    metadata);
        } finally {
            wipe(responsePacket);
            if (metadata != null) {
                metadata.destroy();
            }
        }
    }

    static void authenticateEmptyResponse(
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] responsePacket) {
        authenticateEmptyResponse(
                keys,
                responsePacket,
                MESSAGE_ID);
    }

    static void authenticateEmptyResponse(
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] responsePacket,
            int messageId) {
        if (keys == null) {
            throw new IllegalArgumentException(
                    "IKE keys are required");
        }
        requireMessageId(
                messageId);
        if (responsePacket == null
                || responsePacket.length
                != EMPTY_RESPONSE_PACKET_LENGTH) {
            throw new IllegalArgumentException(
                    "Empty INFORMATIONAL response length "
                            + "is invalid");
        }
        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        responsePacket,
                        keys.initiatorSpi,
                        keys.responderSpi,
                        keys.skEr,
                        true,
                        IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                        messageId);
        if (decrypted.fragmented
                || decrypted.protectedPayloadFlags != 0
                || decrypted.firstInnerPayload
                != IkeV2Codec.PAYLOAD_NONE
                || decrypted.plaintext.length != 0) {
            throw new IllegalArgumentException(
                    "INFORMATIONAL response is not an empty SK");
        }
    }

    static UnlockRequest createClassCUnlockRequest(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId,
            boolean classCUnlocked) {
        requireUnlockState(
                random,
                localRole,
                keys,
                messageId);
        boolean localIsInitiator =
                localRole
                        == NrLinkBluetoothPrelude
                        .LocalRole.INITIATOR;
        byte[] plaintext =
                buildClassCUnlockPlaintext(
                        classCUnlocked);
        try {
            List<byte[]> packets =
                    IkeV2SessionCrypto
                            .encryptProtectedPayloadWithDirectionFlags(
                                    random,
                                    keys.initiatorSpi,
                                    keys.responderSpi,
                                    localIsInitiator
                                            ? keys.skEi
                                            : keys.skEr,
                                    localIsInitiator
                                            ? IkeV2Codec
                                                    .IKE_FLAG_INITIATOR
                                            : 0,
                                    IkeV2SessionCrypto
                                            .EXCHANGE_INFORMATIONAL,
                                    messageId,
                                    plaintext,
                                    IkeV2Codec.PAYLOAD_NOTIFY,
                                    MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1) {
                throw new IllegalStateException(
                        "Class-C unlock INFORMATIONAL "
                                + "unexpectedly fragmented");
            }
            return new UnlockRequest(
                    packets.get(0),
                    plaintext,
                    messageId,
                    classCUnlocked);
        } finally {
            wipe(plaintext);
        }
    }

    static LinkDirectorRequest createLinkDirectorRequest(
            SecureRandom random, NrLinkBluetoothPrelude.LocalRole localRole,
            IkeV2SessionCrypto.IkeSaKeys keys, int messageId, long identifier, int type) {
        requireUnlockState(random, localRole, keys, messageId);
        byte[] message = LinkDirectorMessageCodec.encode(identifier, type);
        return createEncodedLinkDirectorRequest(random, localRole, keys, messageId, type, message);
    }

    static LinkDirectorRequest createApplicationServiceRequest(SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole, IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId, long identifier, byte[] value) {
        requireUnlockState(random, localRole, keys, messageId);
        return createEncodedLinkDirectorRequest(random, localRole, keys, messageId, 20,
                LinkDirectorMessageCodec.applicationServiceRequest(identifier, value));
    }

    private static LinkDirectorRequest createEncodedLinkDirectorRequest(SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole, IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId, int type, byte[] message) {
        byte[] plaintext = genericPayload(IkeV2Codec.PAYLOAD_NONE,
                notifyBody(NOTIFY_LINK_DIRECTOR_MESSAGE, message));
        List<byte[]> packets = null;
        try {
            boolean initiator = localRole == NrLinkBluetoothPrelude.LocalRole.INITIATOR;
            packets = IkeV2SessionCrypto.encryptProtectedPayloadWithDirectionFlags(random,
                    keys.initiatorSpi, keys.responderSpi, initiator ? keys.skEi : keys.skEr,
                    initiator ? IkeV2Codec.IKE_FLAG_INITIATOR : 0,
                    IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL, messageId, plaintext,
                    IkeV2Codec.PAYLOAD_NOTIFY, MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1) throw new IllegalStateException("LinkDirector announcement fragmented");
            return new LinkDirectorRequest(packets.get(0), messageId, type);
        } finally {
            wipe(message); wipe(plaintext);
            if (packets != null) for (byte[] packet : packets) wipe(packet);
        }
    }

    /** RFC 7296 3.11: close this IKE SA and its transient child SAs. */
    static byte[] createIkeDeleteRequest(SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole role,
            IkeV2SessionCrypto.IkeSaKeys keys, int messageId) {
        requireUnlockState(random, role, keys, messageId);
        // Generic payload header, protocol IKE=1, SPI size=0, SPI count=0.
        byte[] plaintext = {0, 0, 0, 8, 1, 0, 0, 0};
        List<byte[]> packets = null;
        try {
            boolean initiator = role == NrLinkBluetoothPrelude.LocalRole.INITIATOR;
            packets = IkeV2SessionCrypto.encryptProtectedPayloadWithDirectionFlags(
                    random, keys.initiatorSpi, keys.responderSpi,
                    initiator ? keys.skEi : keys.skEr,
                    initiator ? IkeV2Codec.IKE_FLAG_INITIATOR : 0,
                    IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL, messageId,
                    plaintext, 42, MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1) throw new IllegalStateException("IKE Delete fragmented");
            return packets.get(0).clone();
        } finally {
            wipe(plaintext);
            if (packets != null) for (byte[] packet : packets) wipe(packet);
        }
    }

    static PostConnectResponse
            authenticatePostConnectRequestAndCreateResponse(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] requestPacket,
            int messageId) {
        requireUnlockState(
                random,
                localRole,
                keys,
                messageId);
        boolean remoteIsInitiator =
                localRole
                        == NrLinkBluetoothPrelude
                        .LocalRole.RESPONDER;
        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto
                        .decryptProtectedPacketWithDirectionFlags(
                                requestPacket,
                                keys.initiatorSpi,
                                keys.responderSpi,
                                remoteIsInitiator
                                        ? keys.skEi
                                        : keys.skEr,
                                remoteIsInitiator
                                        ? IkeV2Codec
                                                .IKE_FLAG_INITIATOR
                                        : 0,
                                IkeV2SessionCrypto
                                        .EXCHANGE_INFORMATIONAL,
                                messageId);
        requireSingleNotifyRequest(
                decrypted,
                "Post-connect");
        PostConnectRequest parsedRequest =
                parsePostConnectPlaintext(
                        decrypted.plaintext);
        boolean localIsInitiator = !remoteIsInitiator;
        byte[] responsePacket = null;
        try {
            responsePacket =
                    IkeV2SessionCrypto
                            .encryptEmptyProtectedPayloadWithDirectionFlags(
                                    random,
                                    keys.initiatorSpi,
                                    keys.responderSpi,
                                    localIsInitiator
                                            ? keys.skEi
                                            : keys.skEr,
                                    (localIsInitiator
                                            ? IkeV2Codec
                                                    .IKE_FLAG_INITIATOR
                                            : 0)
                                            | IkeV2Codec
                                                    .IKE_FLAG_RESPONSE,
                                    IkeV2SessionCrypto
                                            .EXCHANGE_INFORMATIONAL,
                                    messageId);
            if (responsePacket.length
                    != EMPTY_RESPONSE_PACKET_LENGTH) {
                throw new IllegalStateException(
                        "Post-connect response has "
                                + "an unexpected length");
            }
            PostConnectResponse result = new PostConnectResponse(
                    responsePacket,
                    messageId,
                    parsedRequest.kind,
                    parsedRequest.peerClassCUnlocked,
                    parsedRequest.linkDirectorStructure);
            for (byte[] message : parsedRequest.linkDirectorMessages)
                result.linkDirectorMessages.add(message.clone());
            return result;
        } finally {
            wipe(responsePacket);
            for (byte[] message : parsedRequest.linkDirectorMessages) wipe(message);
        }
    }

    static void authenticatePostConnectResponse(
            NrLinkBluetoothPrelude.LocalRole localRole,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] responsePacket,
            int messageId) {
        if (localRole == null || keys == null) {
            throw new IllegalArgumentException(
                    "Post-connect response state is required");
        }
        requireMessageId(
                messageId);
        if (responsePacket == null
                || responsePacket.length
                != EMPTY_RESPONSE_PACKET_LENGTH) {
            throw new IllegalArgumentException(
                    "Post-connect response length is invalid");
        }
        boolean remoteIsInitiator =
                localRole
                        == NrLinkBluetoothPrelude
                        .LocalRole.RESPONDER;
        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto
                        .decryptProtectedPacketWithDirectionFlags(
                                responsePacket,
                                keys.initiatorSpi,
                                keys.responderSpi,
                                remoteIsInitiator
                                        ? keys.skEi
                                        : keys.skEr,
                                (remoteIsInitiator
                                        ? IkeV2Codec
                                                .IKE_FLAG_INITIATOR
                                        : 0)
                                        | IkeV2Codec
                                                .IKE_FLAG_RESPONSE,
                                IkeV2SessionCrypto
                                        .EXCHANGE_INFORMATIONAL,
                                messageId);
        if (decrypted.fragmented
                || decrypted.protectedPayloadFlags != 0
                || decrypted.firstInnerPayload
                != IkeV2Codec.PAYLOAD_NONE
                || decrypted.plaintext.length != 0) {
            throw new IllegalArgumentException(
                    "Post-connect response is not an empty SK");
        }
    }

    private static byte[] buildClassCUnlockPlaintext(
            boolean classCUnlocked) {
        return genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                notifyBody(
                        OrdinaryIkeAuth
                                .NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                        new byte[]{
                                classCUnlocked
                                        ? (byte) 0x80
                                        : 0
                        }));
    }

    private static PostConnectRequest parsePostConnectPlaintext(
            byte[] plaintext) {
        List<NotifyData> notifies =
                parseNotifyChain(
                        plaintext);
        try {
            if (notifies.isEmpty()) {
                throw new IllegalArgumentException(
                        "Post-connect INFORMATIONAL notify chain is empty");
            }
            boolean hasClassCUnlock = false;
            boolean peerClassCUnlocked = false;
            String linkDirectorStructure = null;
            List<byte[]> linkDirectorMessages = new java.util.ArrayList<>();

            for (NotifyData notify : notifies) {
                if (notify.type
                        == OrdinaryIkeAuth
                                .NOTIFY_KEYS_AFTER_FIRST_UNLOCK) {
                    if (notify.data.length != 1
                            || (notify.data[0] != 0
                            && notify.data[0] != (byte) 0x80)) {
                        throw new IllegalArgumentException(
                                "Class-C unlock INFORMATIONAL is invalid: "
                                        + describeNotifyStructure(
                                        notifies));
                    }
                    hasClassCUnlock = true;
                    peerClassCUnlocked = notify.data[0] == (byte) 0x80;
                } else if (notify.type == NOTIFY_LINK_DIRECTOR_MESSAGE) {
                    linkDirectorStructure =
                            parseLinkDirectorMessage(
                                    notify.data);
                    linkDirectorMessages.add(notify.data);
                }
            }

            if (linkDirectorStructure == null && !hasClassCUnlock) {
                linkDirectorStructure = describeNotifyStructure(notifies);
            }

            if (hasClassCUnlock && linkDirectorStructure == null) {
                return new PostConnectRequest(
                        PostConnectRequestKind.CLASS_C_UNLOCK,
                        peerClassCUnlocked,
                        null);
            }

            PostConnectRequest result = new PostConnectRequest(
                    PostConnectRequestKind.LINK_DIRECTOR,
                    peerClassCUnlocked,
                    linkDirectorStructure);
            for (byte[] message : linkDirectorMessages) result.linkDirectorMessages.add(message.clone());
            return result;
        } finally {
            for (NotifyData notify : notifies) {
                notify.destroy();
            }
        }
    }

    private static String parseLinkDirectorMessage(
            byte[] data) {
        if (data == null
                || data.length
                < LINK_DIRECTOR_HEADER_LENGTH
                + LINK_DIRECTOR_TLV_HEADER_LENGTH
                || data.length > MAXIMUM_PACKET_SIZE
                || (data[0] & 0xff) != LINK_DIRECTOR_VERSION
                || data[1] != 0
                || data[4] != 0
                || data[5] != 0
                || data[6] != 0
                || data[7] != 0) {
            throw new IllegalArgumentException(
                    "LinkDirectorMessage header is invalid; "
                            + "identifier and data bytes logged=false");
        }
        int declaredLength = be16(data, 2);
        int actualLength =
                data.length - LINK_DIRECTOR_HEADER_LENGTH;
        if (declaredLength != actualLength) {
            throw new IllegalArgumentException(
                    "LinkDirectorMessage TLV length mismatch: "
                            + "declared="
                            + declaredLength
                            + " actual="
                            + actualLength
                            + "; identifier and data bytes logged=false");
        }

        int offset = LINK_DIRECTOR_HEADER_LENGTH;
        int count = 0;
        StringBuilder entries = new StringBuilder("[");
        while (offset < data.length) {
            if (++count > MAXIMUM_LINK_DIRECTOR_TLVS
                    || offset + LINK_DIRECTOR_TLV_HEADER_LENGTH
                    > data.length) {
                throw new IllegalArgumentException(
                        "LinkDirectorMessage TLV chain is invalid; "
                                + "identifier and data bytes logged=false");
            }
            int type = data[offset] & 0xff;
            int length = be16(data, offset + 1);
            int valueOffset =
                    offset + LINK_DIRECTOR_TLV_HEADER_LENGTH;
            if (valueOffset + length > data.length) {
                throw invalidLinkDirectorTlv(
                        type,
                        length,
                        "truncated");
            }
            // watchOS 26.6 bounds the generic TLV chain here and leaves
            // value semantics to type-specific consumers. This path only
            // authenticates, acknowledges and safely describes the message.
            if (count != 1) {
                entries.append(", ");
            }
            entries.append("{type=")
                    .append(type)
                    .append(" length=")
                    .append(length)
                    .append('}');
            offset = valueOffset + length;
        }
        return "version="
                + LINK_DIRECTOR_VERSION
                + " tlvBytes="
                + actualLength
                + " tlvCount="
                + count
                + " entries="
                + entries.append(']')
                + "; identifier/value bytes logged=false";
    }

    private static IllegalArgumentException invalidLinkDirectorTlv(
            int type,
            int length,
            String reason) {
        return new IllegalArgumentException(
                "LinkDirectorMessage TLV is invalid: type="
                        + type
                        + " length="
                        + length
                        + " reason="
                        + reason
                        + "; identifier and data bytes logged=false");
    }

    private static void requireSingleNotifyRequest(
            IkeV2SessionCrypto.DecryptedIntermediatePart decrypted,
            String label) {
        if (decrypted.fragmented
                || decrypted.protectedPayloadFlags != 0
                || decrypted.firstInnerPayload
                != IkeV2Codec.PAYLOAD_NOTIFY) {
            throw new IllegalArgumentException(
                    label
                            + " INFORMATIONAL has invalid SK framing");
        }
    }

    private static String describeNotifyStructure(
            List<NotifyData> notifies) {
        StringBuilder output =
                new StringBuilder(
                        "count=")
                        .append(notifies.size())
                        .append(" entries=[");
        int limit = Math.min(
                notifies.size(),
                16);
        for (int index = 0; index < limit; index++) {
            if (index != 0) {
                output.append(", ");
            }
            NotifyData notify = notifies.get(index);
            output.append("{index=")
                    .append(index)
                    .append(" type=")
                    .append(String.format(
                            Locale.US,
                            "0x%04X",
                            notify.type))
                    .append(" dataLength=")
                    .append(notify.data.length);
            if (notify.type
                    == OrdinaryIkeAuth
                    .NOTIFY_KEYS_AFTER_FIRST_UNLOCK
                    && notify.data.length != 0) {
                output.append(" highBitSet=")
                        .append((notify.data[0] & 0x80) != 0);
            }
            output.append('}');
        }
        if (notifies.size() > limit) {
            output.append(", truncated=true");
        }
        return output.append("] data bytes logged=false")
                .toString();
    }

    private static byte[] buildMetadataPlaintext(
            OrdinaryIkeAuth.ResponderProfile profile) {
        byte[] name = profile.deviceNameData();
        byte[] build = profile.buildVersionData();
        byte[] ids = profile.idsDeviceIdData();
        byte[] prelude = profile.localPrelude();
        List<NotifyData> notifies =
                new ArrayList<>(ids == null ? 8 : 9);
        try {
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_TERMINUS_VERSION,
                    new byte[]{0, CURRENT_TERMINUS_VERSION}));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_DEVICE_NAME,
                    name));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_BUILD_VERSION,
                    build));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_DEVICE_TYPE,
                    new byte[]{(byte) profile.deviceType()}));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth
                            .NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                    new byte[]{
                            profile.keysAfterFirstUnlock()
                                    ? (byte) 0x80
                                    : 0
                    }));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_LOCAL_FLAGS,
                    new byte[8]));
            notifies.add(new NotifyData(
                    NOTIFY_ALWAYS_ON_WIFI,
                    new byte[]{
                            profile.alwaysOnWifi()
                                    ? (byte) 1
                                    : 0
                    }));
            notifies.add(new NotifyData(
                    OrdinaryIkeAuth.NOTIFY_LOCAL_PRELUDE,
                    prelude));
            if (ids != null) {
                notifies.add(new NotifyData(
                        OrdinaryIkeAuth.NOTIFY_IDS_DEVICE_ID,
                        ids));
            }

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            for (int index = 0;
                    index < notifies.size();
                    index++) {
                NotifyData notify =
                        notifies.get(index);
                int next = index + 1 < notifies.size()
                        ? IkeV2Codec.PAYLOAD_NOTIFY
                        : IkeV2Codec.PAYLOAD_NONE;
                output.writeBytes(
                        genericPayload(
                                next,
                                notifyBody(
                                        notify.type,
                                        notify.data)));
            }
            int expectedLength =
                    115
                            + name.length
                            + build.length
                            + (ids == null
                            ? 0
                            : 8 + ids.length);
            if (output.size() != expectedLength) {
                throw new IllegalStateException(
                        "Metadata INFORMATIONAL inner length "
                                + "does not match the recovered formula");
            }
            return output.toByteArray();
        } finally {
            wipe(name);
            wipe(build);
            wipe(ids);
            wipe(prelude);
            for (NotifyData notify : notifies) {
                notify.destroy();
            }
        }
    }

    private static OrdinaryIkeAuth.ResponderMetadata
            parseMetadataPlaintext(
            byte[] plaintext,
            byte[] expectedRemotePrelude) {
        List<NotifyData> notifies =
                parseNotifyChain(
                        plaintext);
        int[] mandatoryTypes = new int[]{
                OrdinaryIkeAuth.NOTIFY_TERMINUS_VERSION,
                OrdinaryIkeAuth.NOTIFY_DEVICE_NAME,
                OrdinaryIkeAuth.NOTIFY_BUILD_VERSION,
                OrdinaryIkeAuth.NOTIFY_DEVICE_TYPE,
                OrdinaryIkeAuth
                        .NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                OrdinaryIkeAuth.NOTIFY_LOCAL_FLAGS,
                NOTIFY_ALWAYS_ON_WIFI,
                OrdinaryIkeAuth.NOTIFY_LOCAL_PRELUDE
        };
        try {
            if (notifies.size() != mandatoryTypes.length
                    && notifies.size()
                    != mandatoryTypes.length + 1) {
                throw new IllegalArgumentException(
                        "Metadata INFORMATIONAL notify "
                                + "count is invalid");
            }
            for (int index = 0;
                    index < notifies.size();
                    index++) {
                int expectedType =
                        index < mandatoryTypes.length
                                ? mandatoryTypes[index]
                                : OrdinaryIkeAuth
                                        .NOTIFY_IDS_DEVICE_ID;
                if (notifies.get(index).type
                        != expectedType) {
                    throw new IllegalArgumentException(
                            "Metadata INFORMATIONAL notify "
                                    + "order is invalid");
                }
            }
            byte[] version = notifies.get(0).data;
            byte[] deviceType = notifies.get(3).data;
            byte[] unlock = notifies.get(4).data;
            byte[] flags = notifies.get(5).data;
            byte[] wifi = notifies.get(6).data;
            byte[] prelude = notifies.get(7).data;
            if (!Arrays.equals(
                    version,
                    new byte[]{0, CURRENT_TERMINUS_VERSION})
                    || deviceType.length != 1
                    || unlock.length != 1
                    || (unlock[0] != 0
                    && unlock[0] != (byte) 0x80)
                    || !Arrays.equals(flags, new byte[8])
                    || wifi.length != 1
                    || (wifi[0] != 0 && wifi[0] != 1)
                    || !Arrays.equals(
                    prelude,
                    expectedRemotePrelude)) {
                throw new IllegalArgumentException(
                        "Metadata INFORMATIONAL fields "
                                + "are invalid");
            }
            return new OrdinaryIkeAuth.ResponderMetadata(
                    decodeRequiredUtf8(
                            "peer device name",
                            notifies.get(1).data),
                    decodeRequiredUtf8(
                            "peer build version",
                            notifies.get(2).data),
                    notifies.size()
                            == mandatoryTypes.length + 1
                            ? decodeRequiredUtf8(
                                    "peer IDS device ID",
                                    notifies.get(8).data)
                            : null,
                    deviceType[0] & 0xff,
                    unlock[0] == (byte) 0x80,
                    wifi[0] != 0);
        } finally {
            for (NotifyData notify : notifies) {
                notify.destroy();
            }
        }
    }

    private static List<NotifyData> parseNotifyChain(
            byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Metadata INFORMATIONAL is empty");
        }
        List<NotifyData> output =
                new ArrayList<>();
        int type = IkeV2Codec.PAYLOAD_NOTIFY;
        int offset = 0;
        try {
            while (type != IkeV2Codec.PAYLOAD_NONE) {
                if (type != IkeV2Codec.PAYLOAD_NOTIFY
                        || offset + 8 > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Metadata INFORMATIONAL payload "
                                    + "chain is invalid");
                }
                int next = plaintext[offset] & 0xff;
                int flags = plaintext[offset + 1] & 0xff;
                int length =
                        be16(
                                plaintext,
                                offset + 2);
                if (flags != 0
                        || length < 8
                        || offset + length > plaintext.length
                        || plaintext[offset + 4] != 0
                        || plaintext[offset + 5] != 0) {
                    throw new IllegalArgumentException(
                            "Metadata INFORMATIONAL Notify "
                                    + "is invalid");
                }
                output.add(new NotifyData(
                        be16(
                                plaintext,
                                offset + 6),
                        Arrays.copyOfRange(
                                plaintext,
                                offset + 8,
                                offset + length)));
                offset += length;
                type = next;
            }
            if (offset != plaintext.length) {
                throw new IllegalArgumentException(
                        "Metadata INFORMATIONAL has "
                                + "trailing bytes");
            }
            return List.copyOf(output);
        } catch (RuntimeException error) {
            for (NotifyData notify : output) {
                notify.destroy();
            }
            throw error;
        }
    }

    private static byte[] notifyBody(
            int type,
            byte[] data) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        4 + data.length);
        output.write(0);
        output.write(0);
        writeBe16(output, type);
        output.writeBytes(data);
        return output.toByteArray();
    }

    private static byte[] genericPayload(
            int next,
            byte[] body) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        4 + body.length);
        output.write(next);
        output.write(0);
        writeBe16(
                output,
                4 + body.length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static void requireState(
            SecureRandom random,
            OrdinaryIkeAuth.ResponderProfile profile,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        if (random == null
                || profile == null
                || keys == null) {
            throw new IllegalArgumentException(
                    "Complete metadata INFORMATIONAL "
                            + "state is required");
        }
    }

    private static void requireUnlockState(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            IkeV2SessionCrypto.IkeSaKeys keys,
            int messageId) {
        if (random == null
                || localRole == null
                || keys == null) {
            throw new IllegalArgumentException(
                    "Complete Class-C unlock INFORMATIONAL "
                            + "state is required");
        }
        requireMessageId(
                messageId);
    }

    private static void requireMessageId(
            int messageId) {
        if (messageId < 0) {
            throw new IllegalArgumentException(
                    "IKE Message ID must be non-negative");
        }
    }

    private static void requirePrelude(
            byte[] prelude) {
        if (prelude == null
                || prelude.length
                != NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    "Expected peer prelude is invalid");
        }
        NrLinkBluetoothPrelude.parse(prelude);
    }

    private static String decodeRequiredUtf8(
            String label,
            byte[] value) {
        if (value == null
                || value.length == 0
                || value.length > 254) {
            throw new IllegalArgumentException(
                    label + " must contain 1..254 bytes");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    label + " is not valid UTF-8",
                    error);
        }
    }

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class Request {
        final byte[] packet;
        final byte[] plaintext;
        private boolean destroyed;

        Request(
                byte[] packet,
                byte[] plaintext) {
            this.packet = packet.clone();
            this.plaintext = plaintext.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            wipe(plaintext);
            destroyed = true;
        }
    }

    static final class Response {
        final byte[] packet;
        final OrdinaryIkeAuth.ResponderMetadata peerMetadata;
        private boolean destroyed;

        Response(
                byte[] packet,
                OrdinaryIkeAuth.ResponderMetadata peerMetadata) {
            this.packet = packet.clone();
            this.peerMetadata =
                    new OrdinaryIkeAuth.ResponderMetadata(
                            peerMetadata.deviceName,
                            peerMetadata.buildVersion,
                            peerMetadata.idsDeviceId,
                            peerMetadata.deviceType,
                            peerMetadata.keysAfterFirstUnlock,
                            peerMetadata.alwaysOnWifi);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            peerMetadata.destroy();
            destroyed = true;
        }
    }

    static final class UnlockRequest {
        final byte[] packet;
        final byte[] plaintext;
        final int messageId;
        final boolean classCUnlocked;
        private boolean destroyed;

        UnlockRequest(
                byte[] packet,
                byte[] plaintext,
                int messageId,
                boolean classCUnlocked) {
            this.packet = packet.clone();
            this.plaintext = plaintext.clone();
            this.messageId = messageId;
            this.classCUnlocked = classCUnlocked;
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            wipe(plaintext);
            destroyed = true;
        }
    }

    static final class LinkDirectorRequest {
        final byte[] packet;
        final int messageId;
        final int type;
        LinkDirectorRequest(byte[] packet, int messageId, int type) {
            this.packet = packet.clone(); this.messageId = messageId; this.type = type;
        }
        void destroy() { wipe(packet); }
    }

    enum PostConnectRequestKind {
        CLASS_C_UNLOCK,
        LINK_DIRECTOR
    }

    private static final class PostConnectRequest {
        final List<byte[]> linkDirectorMessages = new java.util.ArrayList<>();
        final PostConnectRequestKind kind;
        final boolean peerClassCUnlocked;
        final String linkDirectorStructure;

        PostConnectRequest(
                PostConnectRequestKind kind,
                boolean peerClassCUnlocked,
                String linkDirectorStructure) {
            this.kind = kind;
            this.peerClassCUnlocked = peerClassCUnlocked;
            this.linkDirectorStructure = linkDirectorStructure;
        }
    }

    static final class PostConnectResponse {
        final List<byte[]> linkDirectorMessages = new java.util.ArrayList<>();
        final byte[] packet;
        final int messageId;
        final PostConnectRequestKind requestKind;
        final boolean peerClassCUnlocked;
        final String linkDirectorStructure;
        private boolean destroyed;

        PostConnectResponse(
                byte[] packet,
                int messageId,
                PostConnectRequestKind requestKind,
                boolean peerClassCUnlocked,
                String linkDirectorStructure) {
            this.packet = packet.clone();
            this.messageId = messageId;
            this.requestKind = requestKind;
            this.peerClassCUnlocked =
                    peerClassCUnlocked;
            this.linkDirectorStructure =
                    linkDirectorStructure;
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            for (byte[] message : linkDirectorMessages) wipe(message);
            linkDirectorMessages.clear();
            destroyed = true;
        }
    }

    private static final class NotifyData {
        final int type;
        final byte[] data;

        NotifyData(
                int type,
                byte[] data) {
            this.type = type;
            this.data = data.clone();
        }

        void destroy() {
            wipe(data);
        }
    }
}
