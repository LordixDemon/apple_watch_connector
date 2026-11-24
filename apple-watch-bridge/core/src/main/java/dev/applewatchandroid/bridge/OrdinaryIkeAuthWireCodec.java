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

/** Wire payload construction, parsing, validation, and SA/CP/Notify encoding for {@link OrdinaryIkeAuth}. */
final class OrdinaryIkeAuthWireCodec {
    private OrdinaryIkeAuthWireCodec() {}

    static byte[] buildInitiatorPlaintext(
            byte[] idiBody,
            byte[] authBody,
            byte[] childSpi,
            OrdinaryIkeAuth.DataClass dataClass) {
        List<TypedBody> bodies = new ArrayList<>();
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_ID_INITIATOR,
                idiBody));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(OrdinaryIkeAuth.NOTIFY_INITIAL_CONTACT, new byte[0])));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_ID_RESPONDER,
                serviceIdentifierBody(dataClass)));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_AUTH,
                authBody));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_CONFIGURATION,
                configurationRequestBody()));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_USE_TRANSPORT_MODE,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_NON_FIRST_FRAGMENTS_ALSO,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_SA,
                initiatorSaBody(childSpi)));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                OrdinaryIkeAuth.TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                OrdinaryIkeAuth.TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS,
                        new byte[0])));
        return encodePayloadChain(bodies);
    }

    static byte[] buildResponderPlaintext(
            byte[] idrBody,
            byte[] authBody,
            byte[] childSpi,
            OrdinaryIkeAuth.ResponderProfile profile,
            OrdinaryIkeAuth.DataClass dataClass) {
        List<TypedBody> bodies = new ArrayList<>();
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(OrdinaryIkeAuth.NOTIFY_INITIAL_CONTACT, new byte[0])));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_ID_RESPONDER,
                idrBody));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_AUTH,
                authBody));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_CONFIGURATION,
                configurationReplyBody(
                        profile.addresses,
                        dataClass)));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_USE_TRANSPORT_MODE,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_NON_FIRST_FRAGMENTS_ALSO,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_SA,
                responderSaBody(childSpi)));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                OrdinaryIkeAuth.TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                OrdinaryIkeAuth.TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        OrdinaryIkeAuth.NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS,
                        new byte[0])));
        addPrivateResponderBodies(
                bodies,
                profile);
        return encodePayloadChain(bodies);
    }

    static void addPrivateResponderBodies(
            List<TypedBody> bodies,
            OrdinaryIkeAuth.ResponderProfile profile) {
        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            initiatorD =
                    profile.addresses.initiatorClassD();
            responderD =
                    profile.addresses.responderClassD();
            initiatorC =
                    profile.addresses.initiatorClassC();
            responderC =
                    profile.addresses.responderClassC();
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_TERMINUS_VERSION,
                    new byte[]{0, OrdinaryIkeAuth.CURRENT_TERMINUS_VERSION});
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_DEVICE_NAME,
                    profile.deviceName);
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_BUILD_VERSION,
                    profile.buildVersion);
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_DEVICE_TYPE,
                    new byte[]{(byte) profile.deviceType});
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                    new byte[]{
                            profile.keysAfterFirstUnlock
                                    ? (byte) 0x80
                                    : 0
                    });
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_LOCAL_FLAGS,
                    new byte[8]);
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_ALWAYS_ON_WIFI,
                    new byte[]{profile.alwaysOnWifi ? (byte) 1 : 0});
            addNotify(
                    bodies,
                    OrdinaryIkeAuth.NOTIFY_LOCAL_PRELUDE,
                    profile.localPrelude);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_INITIATOR_CLASS_D,
                    initiatorD);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_RESPONDER_CLASS_D,
                    responderD);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_INITIATOR_CLASS_C,
                    initiatorC);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_RESPONDER_CLASS_C,
                    responderC);
            if (profile.idsDeviceId != null) {
                addNotify(
                        bodies,
                        OrdinaryIkeAuth.NOTIFY_IDS_DEVICE_ID,
                        profile.idsDeviceId);
            }
        } finally {
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    static void addNotify(
            List<TypedBody> bodies,
            int type,
            byte[] data) {
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(type, data)));
    }

    static byte[] encodePayloadChain(
            List<TypedBody> bodies) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (int index = 0;
                index < bodies.size();
                index++) {
            TypedBody current = bodies.get(index);
            int next = index + 1 < bodies.size()
                    ? bodies.get(index + 1).type
                    : IkeV2Codec.PAYLOAD_NONE;
            output.writeBytes(
                    genericPayload(
                            next,
                            current.body));
        }
        for (TypedBody body : bodies) {
            body.destroy();
        }
        return output.toByteArray();
    }

    static List<Payload> parsePayloadChain(
            int firstType,
            byte[] plaintext) {
        if (firstType == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH payload chain is empty");
        }
        List<Payload> output = new ArrayList<>();
        int type = firstType;
        int offset = 0;
        try {
            while (type != IkeV2Codec.PAYLOAD_NONE) {
                if (offset + 4 > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Ordinary IKE_AUTH payload header "
                                    + "is truncated");
                }
                int next = unsigned(plaintext[offset]);
                int flags = unsigned(plaintext[offset + 1]);
                int length = be16(plaintext, offset + 2);
                if (flags != 0
                        || length < 4
                        || offset + length > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Ordinary IKE_AUTH payload layout "
                                    + "is invalid");
                }
                output.add(new Payload(
                        type,
                        Arrays.copyOfRange(
                                plaintext,
                                offset + 4,
                                offset + length)));
                offset += length;
                type = next;
            }
            if (offset != plaintext.length) {
                throw new IllegalArgumentException(
                        "Ordinary IKE_AUTH payload chain "
                                + "has trailing bytes");
            }
            return List.copyOf(output);
        } catch (RuntimeException error) {
            destroyPayloads(output);
            throw error;
        }
    }

    static void validateInitiatorPayloadTypes(
            List<Payload> payloads) {
        requirePayloadTypes(
                payloads,
                new int[]{
                        OrdinaryIkeAuth.PAYLOAD_ID_INITIATOR,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        OrdinaryIkeAuth.PAYLOAD_ID_RESPONDER,
                        OrdinaryIkeAuth.PAYLOAD_AUTH,
                        OrdinaryIkeAuth.PAYLOAD_CONFIGURATION,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_SA,
                        OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                        OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                        IkeV2Codec.PAYLOAD_NOTIFY
                });
    }

    static void validateResponderPayloadTypes(
            List<Payload> payloads) {
        int requiredCount = 23;
        if (payloads.size() != requiredCount
                && payloads.size() != requiredCount + 1) {
            throw new IllegalArgumentException(
                    "Ordinary responder payload count is invalid: "
                            + "expected=23-or-24 "
                            + describeResponderPayloadStructure(
                            payloads));
        }
        int[] standard = new int[]{
                IkeV2Codec.PAYLOAD_NOTIFY,
                OrdinaryIkeAuth.PAYLOAD_ID_RESPONDER,
                OrdinaryIkeAuth.PAYLOAD_AUTH,
                OrdinaryIkeAuth.PAYLOAD_CONFIGURATION,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_SA,
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                OrdinaryIkeAuth.PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                IkeV2Codec.PAYLOAD_NOTIFY
        };
        for (int index = 0;
                index < standard.length;
                index++) {
            if (payloads.get(index).type
                    != standard[index]) {
                throw new IllegalArgumentException(
                        String.format(
                                Locale.ROOT,
                                "Ordinary responder standard payload "
                                        + "order is invalid: index=%d "
                                        + "expected=0x%02X actual=0x%02X; "
                                        + "bodies logged=false",
                                index,
                                standard[index],
                                payloads.get(index).type));
            }
        }
        for (int index = standard.length;
                index < payloads.size();
                index++) {
            if (payloads.get(index).type
                    != IkeV2Codec.PAYLOAD_NOTIFY) {
                throw new IllegalArgumentException(
                        String.format(
                                Locale.ROOT,
                                "Ordinary responder private payload "
                                        + "is not a Notify: index=%d "
                                        + "expected=0x%02X actual=0x%02X; "
                                        + "bodies logged=false",
                                index,
                                IkeV2Codec.PAYLOAD_NOTIFY,
                                payloads.get(index).type));
            }
        }
    }

    static String describeResponderPayloadStructure(
            byte[] plaintext) {
        List<Payload> payloads =
                parsePayloadChain(
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        plaintext);
        try {
            return describeResponderPayloadStructure(
                    payloads);
        } finally {
            destroyPayloads(payloads);
        }
    }

    static String describeResponderPayloadStructure(
            List<Payload> payloads) {
        StringBuilder output = new StringBuilder();
        int limit = Math.min(payloads.size(), 48);
        output.append("count=")
                .append(payloads.size())
                .append(" payloadTypes=[");
        for (int index = 0; index < limit; index++) {
            if (index != 0) {
                output.append(',');
            }
            output.append(String.format(
                    Locale.ROOT,
                    "0x%02X",
                    payloads.get(index).type));
        }
        if (limit != payloads.size()) {
            output.append(",...");
        }
        output.append("] notifyHeaders=[");
        boolean first = true;
        for (int index = 0; index < limit; index++) {
            Payload payload = payloads.get(index);
            if (payload.type != IkeV2Codec.PAYLOAD_NOTIFY) {
                continue;
            }
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append(index).append(':');
            if (payload.body.length < 4) {
                output.append("truncated(length=")
                        .append(payload.body.length)
                        .append(')');
                continue;
            }
            int protocolId = unsigned(payload.body[0]);
            int spiSize = unsigned(payload.body[1]);
            int notifyType = be16(payload.body, 2);
            int dataLength = payload.body.length - 4 - spiSize;
            output.append(String.format(
                    Locale.ROOT,
                    "type=0x%04X/protocol=%d/spi=%d/dataLength=%d",
                    notifyType,
                    protocolId,
                    spiSize,
                    dataLength));
        }
        output.append("]; bodies logged=false");
        return output.toString();
    }

    static void requirePayloadTypes(
            List<Payload> payloads,
            int[] expected) {
        if (payloads.size() != expected.length) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH payload count is invalid");
        }
        for (int index = 0;
                index < expected.length;
                index++) {
            if (payloads.get(index).type != expected[index]) {
                throw new IllegalArgumentException(
                        "Ordinary IKE_AUTH payload order is invalid");
            }
        }
    }

    static ParsedPrivateBatch parsePrivateBatch(
            List<Payload> payloads,
            byte[] expectedRemotePrelude) {
        int[] mandatoryTypes = new int[]{
                OrdinaryIkeAuth.NOTIFY_TERMINUS_VERSION,
                OrdinaryIkeAuth.NOTIFY_DEVICE_NAME,
                OrdinaryIkeAuth.NOTIFY_BUILD_VERSION,
                OrdinaryIkeAuth.NOTIFY_DEVICE_TYPE,
                OrdinaryIkeAuth.NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                OrdinaryIkeAuth.NOTIFY_LOCAL_FLAGS,
                OrdinaryIkeAuth.NOTIFY_ALWAYS_ON_WIFI,
                OrdinaryIkeAuth.NOTIFY_LOCAL_PRELUDE,
                ApplePairingNotifyPayloads
                        .INNER_INITIATOR_CLASS_D,
                ApplePairingNotifyPayloads
                        .INNER_RESPONDER_CLASS_D,
                ApplePairingNotifyPayloads
                        .INNER_INITIATOR_CLASS_C,
                ApplePairingNotifyPayloads
                        .INNER_RESPONDER_CLASS_C
        };
        if (payloads.size() != mandatoryTypes.length
                && payloads.size()
                != mandatoryTypes.length + 1) {
            throw new IllegalArgumentException(
                    "Ordinary responder private-notify "
                            + "count is invalid");
        }
        byte[][] data = new byte[payloads.size()][];
        try {
            for (int index = 0;
                    index < payloads.size();
                    index++) {
                Notify notify =
                        parseNotify(payloads.get(index));
                int expectedType = index < mandatoryTypes.length
                        ? mandatoryTypes[index]
                        : OrdinaryIkeAuth.NOTIFY_IDS_DEVICE_ID;
                if (notify.type != expectedType) {
                    notify.destroy();
                    throw new IllegalArgumentException(
                            "Ordinary responder private-notify "
                                    + "order is invalid");
                }
                data[index] = notify.data.clone();
                notify.destroy();
            }
            if (!Arrays.equals(
                    data[0],
                    new byte[]{0, OrdinaryIkeAuth.CURRENT_TERMINUS_VERSION})
                    || data[3].length != 1
                    || data[4].length != 1
                    || (data[4][0] != 0
                    && data[4][0] != (byte) 0x80)
                    || !Arrays.equals(data[5], new byte[8])
                    || data[6].length != 1
                    || (data[6][0] != 0
                    && data[6][0] != 1)
                    || !Arrays.equals(
                    data[7],
                    expectedRemotePrelude)) {
                throw new IllegalArgumentException(
                        "Ordinary responder private metadata "
                                + "is invalid");
            }
            String name =
                    decodeRequiredUtf8(
                            "responder device name",
                            data[1]);
            String build =
                    decodeRequiredUtf8(
                            "responder build version",
                            data[2]);
            String ids = payloads.size()
                    == mandatoryTypes.length + 1
                    ? decodeRequiredUtf8(
                            "responder IDS device ID",
                            data[mandatoryTypes.length])
                    : null;
            AppleNetworkRelayInnerAddresses addresses =
                    AppleNetworkRelayInnerAddresses.fromAuthoritative(
                            data[8],
                            data[9],
                            data[10],
                            data[11]);
            OrdinaryIkeAuth.ResponderMetadata metadata =
                    new OrdinaryIkeAuth.ResponderMetadata(
                            name,
                            build,
                            ids,
                            unsigned(data[3][0]),
                            data[4][0] == (byte) 0x80,
                            data[6][0] != 0);
            return new ParsedPrivateBatch(
                    addresses,
                    metadata);
        } finally {
            for (byte[] value : data) {
                wipe(value);
            }
        }
    }

    static byte[] serviceIdentifierBody(
            OrdinaryIkeAuth.DataClass dataClass) {
        if (dataClass == null) {
            throw new IllegalArgumentException(
                    "Ordinary data class is required");
        }
        byte[] output =
                new byte[4 + dataClass.serviceKeyIdBytes.length];
        output[0] =
                (byte) AppleIkeEncryptedKeyId.ID_TYPE_KEY_ID;
        System.arraycopy(
                dataClass.serviceKeyIdBytes,
                0,
                output,
                4,
                dataClass.serviceKeyIdBytes.length);
        return output;
    }

    static void validateServiceIdentifier(
            byte[] body,
            OrdinaryIkeAuth.DataClass dataClass) {
        byte[] expected = serviceIdentifierBody(
                dataClass);
        try {
            if (!Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary "
                                + dataClass
                                + " service identifier "
                                + "is invalid");
            }
        } finally {
            wipe(expected);
        }
    }

    static byte[] configurationRequestBody() {
        byte[] output = new byte[44];
        output[0] = 1;
        putConfigAttribute(
                output,
                4,
                OrdinaryIkeAuth.CONFIG_ATTRIBUTE_INITIATOR_IPV6,
                new byte[16]);
        putConfigAttribute(
                output,
                24,
                OrdinaryIkeAuth.CONFIG_ATTRIBUTE_RESPONDER_IPV6,
                new byte[16]);
        return output;
    }

    static void validateConfigurationRequest(
            byte[] body) {
        byte[] expected = configurationRequestBody();
        try {
            if (!Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary CFG_REQUEST is invalid");
            }
        } finally {
            wipe(expected);
        }
    }

    static byte[] configurationReplyBody(
            AppleNetworkRelayInnerAddresses addresses,
            OrdinaryIkeAuth.DataClass dataClass) {
        byte[] initiator = null;
        byte[] responder = null;
        try {
            initiator = addressFor(
                    addresses,
                    dataClass,
                    true);
            responder = addressFor(
                    addresses,
                    dataClass,
                    false);
            byte[] output = new byte[44];
            output[0] = 2;
            putConfigAttribute(
                    output,
                    4,
                    OrdinaryIkeAuth.CONFIG_ATTRIBUTE_INITIATOR_IPV6,
                    initiator);
            putConfigAttribute(
                    output,
                    24,
                    OrdinaryIkeAuth.CONFIG_ATTRIBUTE_RESPONDER_IPV6,
                    responder);
            return output;
        } finally {
            wipe(initiator);
            wipe(responder);
        }
    }

    static byte[][] parseConfigurationReply(
            byte[] body) {
        if (body == null
                || body.length != 44
                || body[0] != 2
                || body[1] != 0
                || body[2] != 0
                || body[3] != 0
                || be16(body, 4)
                != OrdinaryIkeAuth.CONFIG_ATTRIBUTE_INITIATOR_IPV6
                || be16(body, 6) != 16
                || be16(body, 24)
                != OrdinaryIkeAuth.CONFIG_ATTRIBUTE_RESPONDER_IPV6
                || be16(body, 26) != 16) {
            throw new IllegalArgumentException(
                    "Ordinary CFG_REPLY is invalid");
        }
        return new byte[][]{
                Arrays.copyOfRange(body, 8, 24),
                Arrays.copyOfRange(body, 28, 44)
        };
    }

    static void putConfigAttribute(
            byte[] output,
            int offset,
            int type,
            byte[] address) {
        requireLength(
                "configuration IPv6 address",
                address,
                16);
        output[offset] = (byte) (type >>> 8);
        output[offset + 1] = (byte) type;
        output[offset + 2] = 0;
        output[offset + 3] = 16;
        System.arraycopy(
                address,
                0,
                output,
                offset + 4,
                address.length);
    }

    static byte[] initiatorSaBody(
            byte[] childSpi) {
        requireLength(
                "initiator Child SPI",
                childSpi,
                OrdinaryIkeAuth.CHILD_SPI_LENGTH);
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(40);
        body.write(0);
        body.write(0);
        writeBe16(body, 40);
        body.write(1);
        body.write(3);
        body.write(4);
        body.write(3);
        body.writeBytes(childSpi);
        body.writeBytes(new byte[]{
                0x03, 0x00, 0x00, 0x0c,
                0x01, 0x00, 0x00, 0x1e,
                (byte) 0x80, 0x0e, 0x01, 0x00,
                0x03, 0x00, 0x00, 0x08,
                0x01, 0x00, 0x00, 0x1f,
                0x00, 0x00, 0x00, 0x08,
                0x05, 0x00, 0x00, 0x00
        });
        return body.toByteArray();
    }

    static byte[] responderSaBody(
            byte[] childSpi) {
        requireLength(
                "responder Child SPI",
                childSpi,
                OrdinaryIkeAuth.CHILD_SPI_LENGTH);
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(32);
        body.write(0);
        body.write(0);
        writeBe16(body, 32);
        body.write(1);
        body.write(3);
        body.write(4);
        body.write(2);
        body.writeBytes(childSpi);
        body.writeBytes(new byte[]{
                0x03, 0x00, 0x00, 0x0c,
                0x01, 0x00, 0x00, 0x1e,
                (byte) 0x80, 0x0e, 0x01, 0x00,
                0x00, 0x00, 0x00, 0x08,
                0x05, 0x00, 0x00, 0x00
        });
        return body.toByteArray();
    }

    static byte[] parseInitiatorSa(
            byte[] body) {
        if (body == null || body.length != 40) {
            throw new IllegalArgumentException(
                    "Ordinary initiator Child proposal "
                            + "has an invalid length");
        }
        byte[] childSpi =
                Arrays.copyOfRange(body, 8, 12);
        byte[] expected =
                initiatorSaBody(childSpi);
        try {
            if (isAllZero(childSpi)
                    || !Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary initiator Child proposal "
                                + "is invalid");
            }
            return childSpi.clone();
        } finally {
            wipe(expected);
            wipe(childSpi);
        }
    }

    static byte[] parseResponderSa(
            byte[] body) {
        if (body == null || body.length != 32) {
            throw new IllegalArgumentException(
                    "Ordinary responder Child proposal "
                            + "has an invalid length");
        }
        byte[] childSpi =
                Arrays.copyOfRange(body, 8, 12);
        byte[] expected =
                responderSaBody(childSpi);
        try {
            if (isAllZero(childSpi)
                    || !Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary responder Child proposal "
                                + "is invalid");
            }
            return childSpi.clone();
        } finally {
            wipe(expected);
            wipe(childSpi);
        }
    }

    static void validateTrafficSelectors(
            Payload payload) {
        if (!Arrays.equals(
                payload.body,
                OrdinaryIkeAuth.TRAFFIC_SELECTORS_BODY)) {
            throw new IllegalArgumentException(
                    "Ordinary all-IP traffic selectors "
                            + "are invalid");
        }
    }

    static byte[] notifyBody(
            int type,
            byte[] data) {
        if (type < 0
                || type > 0xffff
                || data == null) {
            throw new IllegalArgumentException(
                    "Notify type and data are required");
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        4 + data.length);
        output.write(0);
        output.write(0);
        writeBe16(output, type);
        output.writeBytes(data);
        return output.toByteArray();
    }

    static Notify parseNotify(
            Payload payload) {
        if (payload.type != IkeV2Codec.PAYLOAD_NOTIFY
                || payload.body.length < 4
                || payload.body[0] != 0
                || payload.body[1] != 0) {
            throw new IllegalArgumentException(
                    "Ordinary Notify body is invalid");
        }
        return new Notify(
                be16(payload.body, 2),
                Arrays.copyOfRange(
                        payload.body,
                        4,
                        payload.body.length));
    }

    static void validateEmptyNotify(
            Payload payload,
            int expectedType) {
        Notify notify = parseNotify(payload);
        try {
            if (notify.type != expectedType
                    || notify.data.length != 0) {
                throw new IllegalArgumentException(
                        "Ordinary IKE notify 0x"
                                + Integer.toHexString(expectedType)
                                + " is invalid");
            }
        } finally {
            notify.destroy();
        }
    }

    static byte[] genericPayload(
            int next,
            byte[] body) {
        int length = 4 + body.length;
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(length);
        output.write(next);
        output.write(0);
        writeBe16(output, length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    static void requireCommonState(
            OrdinaryIkeAuth.DataClass dataClass,
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.PeerMaterial peerMaterial,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        if (dataClass == null
                || random == null
                || localMaterial == null
                || peerMaterial == null
                || keys == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary IKE state is required");
        }
        requireIntermediateAuth(
                initiatorIntAuth,
                responderIntAuth);
        peerMaterial.publicEd25519(
                dataClass);
    }

    static byte[] sign(
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] message) {
        return dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                ? localMaterial.signClassD(message)
                : localMaterial.signClassC(message);
    }

    static byte[] addressFor(
            AppleNetworkRelayInnerAddresses addresses,
            OrdinaryIkeAuth.DataClass dataClass,
            boolean initiator) {
        if (addresses == null || dataClass == null) {
            throw new IllegalArgumentException(
                    "Addresses and ordinary data class are required");
        }
        if (dataClass == OrdinaryIkeAuth.DataClass.CLASS_D) {
            return initiator
                    ? addresses.initiatorClassD()
                    : addresses.responderClassD();
        }
        return initiator
                ? addresses.initiatorClassC()
                : addresses.responderClassC();
    }

    static void requireIntermediateAuth(
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        requireLength(
                "initiator IntAuth",
                initiatorIntAuth,
                OrdinaryIkeAuth.INTERMEDIATE_AUTH_LENGTH);
        requireLength(
                "responder IntAuth",
                responderIntAuth,
                OrdinaryIkeAuth.INTERMEDIATE_AUTH_LENGTH);
    }

    static void validateInitiatorSaInit(
            byte[] requestPacket,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        OrdinaryIkeSaInit.Request request =
                OrdinaryIkeSaInit.parseRequest(
                        requestPacket);
        if (!Arrays.equals(
                request.initiatorSpi,
                keys.initiatorSpi)
                || !Arrays.equals(
                request.nonce,
                keys.initiatorNonce)) {
            throw new IllegalArgumentException(
                    "Ordinary initiator IKE_SA_INIT "
                            + "does not match the key schedule");
        }
    }

    static void validateSaInitTranscript(
            byte[] requestPacket,
            byte[] responsePacket,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        validateInitiatorSaInit(
                requestPacket,
                keys);
        OrdinaryIkeSaInit.Response response =
                OrdinaryIkeSaInit.parseResponse(
                        responsePacket,
                        keys.initiatorSpi);
        if (!Arrays.equals(
                response.responderSpi,
                keys.responderSpi)
                || !Arrays.equals(
                response.nonce,
                keys.responderNonce)) {
            throw new IllegalArgumentException(
                    "Ordinary responder IKE_SA_INIT "
                            + "does not match the key schedule");
        }
    }

    static void requireUnfragmented(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            int expectedFirstPayload,
            String label) {
        if (part == null
                || part.fragmented
                || part.protectedPayloadFlags != 0
                || part.firstInnerPayload
                != expectedFirstPayload) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH "
                            + label
                            + " has invalid SK framing");
        }
    }

    static byte[] randomNonzeroChildSpi(
            SecureRandom random) {
        byte[] spi = new byte[OrdinaryIkeAuth.CHILD_SPI_LENGTH];
        random.nextBytes(spi);
        if (isAllZero(spi)) {
            spi[spi.length - 1] = 1;
        }
        return spi;
    }

    static String decodeRequiredUtf8(
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

    static byte[] encodeRequiredUtf8(
            String label,
            String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
        byte[] encoded =
                value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0
                || encoded.length > 254) {
            wipe(encoded);
            throw new IllegalArgumentException(
                    label + " must encode to 1..254 UTF-8 bytes");
        }
        return encoded;
    }

    static void destroyPayloads(
            List<Payload> payloads) {
        for (Payload payload : payloads) {
            payload.destroy();
        }
    }

    static boolean isAllZero(
            byte[] value) {
        int combined = 0;
        for (byte current : value) {
            combined |= current & 0xff;
        }
        return combined == 0;
    }

    static int unsigned(
            byte value) {
        return value & 0xff;
    }

    static int be16(
            byte[] value,
            int offset) {
        return (unsigned(value[offset]) << 8)
                | unsigned(value[offset + 1]);
    }

    static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    static void requireLength(
            String label,
            byte[] value,
            int length) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    label + " must contain " + length + " bytes");
        }
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class ParsedPrivateBatch {
        AppleNetworkRelayInnerAddresses addresses;
        OrdinaryIkeAuth.ResponderMetadata metadata;

        ParsedPrivateBatch(
                AppleNetworkRelayInnerAddresses addresses,
                OrdinaryIkeAuth.ResponderMetadata metadata) {
            this.addresses = addresses;
            this.metadata = metadata;
        }

        void releaseOwnership() {
            addresses = null;
            metadata = null;
        }
    }

    static final class TypedBody {
        final int type;
        final byte[] body;

        TypedBody(
                int type,
                byte[] body) {
            this.type = type;
            this.body = body.clone();
        }

        void destroy() {
            wipe(body);
        }
    }

    static final class Payload {
        final int type;
        final byte[] body;

        Payload(
                int type,
                byte[] body) {
            this.type = type;
            this.body = body.clone();
        }

        void destroy() {
            wipe(body);
        }
    }

    static final class Notify {
        final int type;
        final byte[] data;

        Notify(
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
