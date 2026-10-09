package dev.applewatchandroid.bridge;

import org.bouncycastle.math.ec.rfc7748.X448;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact ordinary Class D/Class C IKE_SA_INIT profile used by the Ultra 2
 * after a state-13 NRLinkBluetooth prelude.
 *
 * <p>The wire role is selected by the prelude, so this codec implements both
 * request and response sides. It selects AES-256-GCM, PRF-HMAC-SHA2-512,
 * X448, and ADDKE1/ML-KEM-768. No device identity or long-lived key appears
 * in IKE_SA_INIT.</p>
 */
final class OrdinaryIkeSaInit {
    static final int REQUEST_LENGTH = 274;
    static final int RESPONSE_LENGTH = 266;
    static final int ADDITIONAL_KE_METHOD_ML_KEM_768 = 36;
    static final int NOTIFY_SIGNATURE_HASH_ALGORITHMS = 0x402f;
    static final int SIGNATURE_HASH_IDENTITY = 5;
    static final int CAPABILITY_CHILDLESS = 1;
    static final int CAPABILITY_USE_PPK = 1 << 1;

    private static final int IKE_HEADER_LENGTH = 28;
    private static final int X448_KEY_LENGTH = 56;
    private static final int NONCE_LENGTH = 32;

    private static final byte[] REQUEST_SA_BODY =
            buildRequestSaBody();
    private static final byte[] RESPONSE_SA_BODY =
            buildResponseSaBody();

    private OrdinaryIkeSaInit() {
    }

    static InitiatorState createInitiator(
            SecureRandom random) {
        requireRandom(random);
        byte[] initiatorSpi = randomNonzeroSpi(random);
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        byte[] privateKey = new byte[X448_KEY_LENGTH];
        byte[] publicKey = new byte[X448_KEY_LENGTH];
        X448.generatePrivateKey(random, privateKey);
        X448.generatePublicKey(
                privateKey,
                0,
                publicKey,
                0);
        byte[] packet = buildRequest(
                initiatorSpi,
                nonce,
                publicKey);
        return new InitiatorState(
                initiatorSpi,
                nonce,
                privateKey,
                publicKey,
                packet);
    }

    static ResponderState createResponder(
            SecureRandom random,
            Request request) {
        requireRandom(random);
        if (request == null) {
            throw new IllegalArgumentException(
                    "Ordinary IKE request is required");
        }
        byte[] responderSpi = randomNonzeroSpi(random);
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        byte[] privateKey = new byte[X448_KEY_LENGTH];
        byte[] publicKey = new byte[X448_KEY_LENGTH];
        X448.generatePrivateKey(random, privateKey);
        X448.generatePublicKey(
                privateKey,
                0,
                publicKey,
                0);
        byte[] packet = buildResponse(
                request.initiatorSpi,
                responderSpi,
                nonce,
                publicKey);
        return new ResponderState(
                responderSpi,
                nonce,
                privateKey,
                publicKey,
                packet);
    }

    static byte[] buildRequest(
            byte[] initiatorSpi,
            byte[] nonce,
            byte[] x448PublicKey) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("initiator nonce", nonce, NONCE_LENGTH);
        requireLength(
                "initiator X448 public key",
                x448PublicKey,
                X448_KEY_LENGTH);
        if (isAllZero(initiatorSpi)) {
            throw new IllegalArgumentException(
                    "Initiator SPI must be non-zero");
        }
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(genericPayload(
                IkeV2Codec.PAYLOAD_KE,
                REQUEST_SA_BODY));
        payloads.add(keyExchangePayload(
                IkeV2Codec.PAYLOAD_NONCE,
                x448PublicKey));
        payloads.add(genericPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                nonce));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP,
                new byte[20]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP,
                new byte[20]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                new byte[0]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                NOTIFY_SIGNATURE_HASH_ALGORITHMS,
                new byte[]{0, SIGNATURE_HASH_IDENTITY}));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED,
                new byte[0]));
        return buildPacket(
                initiatorSpi,
                new byte[8],
                IkeV2Codec.IKE_FLAG_INITIATOR,
                payloads,
                REQUEST_LENGTH);
    }

    static byte[] buildResponse(
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] nonce,
            byte[] x448PublicKey) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("responder SPI", responderSpi, 8);
        requireLength("responder nonce", nonce, NONCE_LENGTH);
        requireLength(
                "responder X448 public key",
                x448PublicKey,
                X448_KEY_LENGTH);
        if (isAllZero(initiatorSpi)
                || isAllZero(responderSpi)) {
            throw new IllegalArgumentException(
                    "IKE SPIs must be non-zero");
        }
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(genericPayload(
                IkeV2Codec.PAYLOAD_KE,
                RESPONSE_SA_BODY));
        payloads.add(keyExchangePayload(
                IkeV2Codec.PAYLOAD_NONCE,
                x448PublicKey));
        payloads.add(genericPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                nonce));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP,
                new byte[20]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP,
                new byte[20]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                new byte[0]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                NOTIFY_SIGNATURE_HASH_ALGORITHMS,
                new byte[]{0, SIGNATURE_HASH_IDENTITY}));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.NOTIFY_CHILDLESS_IKEV2_SUPPORTED,
                new byte[0]));
        payloads.add(notifyPayload(
                IkeV2Codec.PAYLOAD_NONE,
                IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED,
                new byte[0]));
        return buildPacket(
                initiatorSpi,
                responderSpi,
                IkeV2Codec.IKE_FLAG_RESPONSE,
                payloads,
                RESPONSE_LENGTH);
    }

    static Request parseRequest(
            byte[] packet) {
        Parsed parsed = parse(
                packet,
                false,
                null);
        return new Request(
                parsed.initiatorSpi,
                parsed.nonce,
                parsed.x448PublicKey,
                packet);
    }

    static Response parseResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        requireLength(
                "expected initiator SPI",
                expectedInitiatorSpi,
                8);
        Parsed parsed = parse(
                packet,
                true,
                expectedInitiatorSpi);
        return new Response(
                parsed.responderSpi,
                parsed.nonce,
                parsed.x448PublicKey,
                parsed.capabilityFlags,
                packet);
    }

    static IkeV2SessionCrypto.IkeSaKeys
            deriveInitiatorKeys(
            InitiatorState initiator,
            Response response) {
        if (initiator == null || response == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary initiator state is required");
        }
        return IkeV2SessionCrypto
                .deriveInitialX448Keys(
                        initiator.x448PrivateKey,
                        response.x448PublicKey,
                        initiator.initiatorSpi,
                        response.responderSpi,
                        initiator.nonce,
                        response.nonce);
    }

    static IkeV2SessionCrypto.IkeSaKeys
            deriveResponderKeys(
            Request request,
            ResponderState responder) {
        if (request == null || responder == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary responder state is required");
        }
        return IkeV2SessionCrypto
                .deriveInitialX448Keys(
                        responder.x448PrivateKey,
                        request.x448PublicKey,
                        request.initiatorSpi,
                        responder.responderSpi,
                        request.nonce,
                        responder.nonce);
    }

    private static Parsed parse(
            byte[] packet,
            boolean response,
            byte[] expectedInitiatorSpi) {
        int expectedLength =
                response ? RESPONSE_LENGTH : REQUEST_LENGTH;
        if (packet == null
                || packet.length != expectedLength) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_SA_INIT has unexpected length");
        }
        if (be32(packet, 24) != packet.length
                || unsigned(packet[16])
                != IkeV2Codec.PAYLOAD_SA
                || unsigned(packet[17]) != 0x20
                || unsigned(packet[18])
                != IkeV2Codec.EXCHANGE_IKE_SA_INIT
                || be32(packet, 20) != 0) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_SA_INIT header is invalid");
        }
        int expectedFlags = response
                ? IkeV2Codec.IKE_FLAG_RESPONSE
                : IkeV2Codec.IKE_FLAG_INITIATOR;
        if (unsigned(packet[19]) != expectedFlags) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_SA_INIT direction is invalid");
        }
        byte[] initiatorSpi =
                Arrays.copyOfRange(packet, 0, 8);
        byte[] responderSpi =
                Arrays.copyOfRange(packet, 8, 16);
        if (isAllZero(initiatorSpi)
                || (response
                ? isAllZero(responderSpi)
                : !isAllZero(responderSpi))) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_SA_INIT SPI fields are invalid");
        }
        if (expectedInitiatorSpi != null
                && !Arrays.equals(
                expectedInitiatorSpi,
                initiatorSpi)) {
            throw new IllegalArgumentException(
                    "Ordinary IKE response SPIi does not match");
        }

        List<Payload> payloads = parsePayloads(packet);
        int[] expectedTypes = response
                ? new int[]{
                IkeV2Codec.PAYLOAD_SA,
                IkeV2Codec.PAYLOAD_KE,
                IkeV2Codec.PAYLOAD_NONCE,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY
        }
                : new int[]{
                IkeV2Codec.PAYLOAD_SA,
                IkeV2Codec.PAYLOAD_KE,
                IkeV2Codec.PAYLOAD_NONCE,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY
        };
        if (payloads.size() != expectedTypes.length) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_SA_INIT payload count is invalid");
        }
        for (int index = 0;
                index < expectedTypes.length;
                index++) {
            if (payloads.get(index).type
                    != expectedTypes[index]) {
                throw new IllegalArgumentException(
                        "Ordinary IKE_SA_INIT payload order is invalid");
            }
        }

        byte[] expectedSaBody =
                response ? RESPONSE_SA_BODY : REQUEST_SA_BODY;
        if (!Arrays.equals(
                expectedSaBody,
                payloads.get(0).body)) {
            throw new IllegalArgumentException(
                    "Ordinary IKE SA proposal is invalid");
        }
        byte[] keBody = payloads.get(1).body;
        if (keBody.length != 4 + X448_KEY_LENGTH
                || be16(keBody, 0)
                != IkeV2Codec.DH_GROUP_CURVE_448
                || be16(keBody, 2) != 0) {
            throw new IllegalArgumentException(
                    "Ordinary IKE X448 payload is invalid");
        }
        byte[] nonce = payloads.get(2).body;
        if (nonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException(
                    "Ordinary IKE nonce is invalid");
        }
        byte[] x448PublicKey =
                Arrays.copyOfRange(
                        keBody,
                        4,
                        keBody.length);
        if (isAllZero(x448PublicKey)) {
            throw new IllegalArgumentException(
                    "Ordinary IKE X448 public key is zero");
        }

        int capabilityFlags = 0;
        int[] expectedNotifies = response
                ? new int[]{
                IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP,
                IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP,
                IkeV2Codec.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                NOTIFY_SIGNATURE_HASH_ALGORITHMS
        }
                : new int[]{
                IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP,
                IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP,
                IkeV2Codec.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                NOTIFY_SIGNATURE_HASH_ALGORITHMS,
                IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED
        };
        for (int index = 0;
                index < expectedNotifies.length;
                index++) {
            validateNotify(
                    payloads.get(3 + index).body,
                    expectedNotifies[index],
                    index);
        }
        if (response) {
            byte[] optionalBody = payloads.get(7).body;
            int optionalType = notifyType(optionalBody);
            if (optionalType
                    == IkeV2Codec.NOTIFY_CHILDLESS_IKEV2_SUPPORTED) {
                capabilityFlags |= CAPABILITY_CHILDLESS;
            } else if (optionalType
                    == IkeV2Codec.NOTIFY_USE_PPK) {
                // Physical terminusd config has no ordinary PPK setter.
                // Preserve this as a peer capability marker only; pairing
                // PPK material must never alter the ordinary key schedule.
                capabilityFlags |= CAPABILITY_USE_PPK;
            } else {
                throw notifyMismatch(
                        optionalBody,
                        "0x4022-or-0x4033",
                        4);
            }
            validateNotify(
                    optionalBody,
                    optionalType,
                    4);
            validateNotify(
                    payloads.get(8).body,
                    IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED,
                    5);
        }
        return new Parsed(
                initiatorSpi,
                responderSpi,
                nonce,
                x448PublicKey,
                capabilityFlags);
    }

    private static void validateNotify(
            byte[] body,
            int expectedType,
            int notifyIndex) {
        int protocolId = body == null || body.length < 1
                ? -1
                : unsigned(body[0]);
        boolean physicalWatchChildlessQuirk =
                expectedType
                        == IkeV2Codec.NOTIFY_CHILDLESS_IKEV2_SUPPORTED
                        && notifyIndex == 4
                        && protocolId == 1;
        if (body.length < 4
                || (!physicalWatchChildlessQuirk && protocolId != 0)
                || unsigned(body[1]) != 0
                || be16(body, 2) != expectedType) {
            throw notifyMismatch(
                    body,
                    String.format("0x%04X", expectedType),
                    notifyIndex);
        }
        byte[] data =
                Arrays.copyOfRange(body, 4, body.length);
        if (expectedType
                == IkeV2Codec.NOTIFY_NAT_DETECTION_SOURCE_IP
                || expectedType
                == IkeV2Codec.NOTIFY_NAT_DETECTION_DESTINATION_IP) {
            // RFC 7296 NAT-D is always a 20-byte SHA-1 value. Apple uses an
            // all-zero stand-in when its packet delegate has no endpoint,
            // but emits the ordinary SHA-1 value when an endpoint is present.
            // A mismatch is a NAT signal, not a malformed IKE_SA_INIT.
            if (data.length != 20) {
                throw new IllegalArgumentException(
                        "Ordinary packet-delegate NAT hash is invalid");
            }
        } else if (expectedType
                == NOTIFY_SIGNATURE_HASH_ALGORITHMS) {
            if (!Arrays.equals(
                    data,
                    new byte[]{0, SIGNATURE_HASH_IDENTITY})) {
                throw new IllegalArgumentException(
                        "Ordinary signature-hash notify is invalid");
            }
        } else if (data.length != 0) {
            throw new IllegalArgumentException(
                    "Ordinary empty notify contains data");
        }
    }

    private static int notifyType(
            byte[] body) {
        return body == null || body.length < 4
                ? -1
                : be16(body, 2);
    }

    private static IllegalArgumentException notifyMismatch(
            byte[] body,
            String expectedType,
            int notifyIndex) {
        int protocolId = body == null || body.length < 1
                ? -1
                : unsigned(body[0]);
        int spiSize = body == null || body.length < 2
                ? -1
                : unsigned(body[1]);
        int actualType = notifyType(body);
        int bodyLength = body == null ? -1 : body.length;
        String actual = actualType < 0
                ? "unavailable"
                : String.format("0x%04X", actualType);
        return new IllegalArgumentException(
                "Ordinary IKE notify mismatch at index "
                        + notifyIndex
                        + ": expected="
                        + expectedType
                        + " actual="
                        + actual
                        + " protocolId="
                        + protocolId
                        + " spiSize="
                        + spiSize
                        + " bodyLength="
                        + bodyLength
                        + "; data bytes logged=false");
    }

    private static List<Payload> parsePayloads(
            byte[] packet) {
        List<Payload> payloads = new ArrayList<>();
        int type = unsigned(packet[16]);
        int offset = IKE_HEADER_LENGTH;
        while (type != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + 4 > packet.length) {
                throw new IllegalArgumentException(
                        "Ordinary IKE payload header is truncated");
            }
            int next = unsigned(packet[offset]);
            if (unsigned(packet[offset + 1]) != 0) {
                throw new IllegalArgumentException(
                        "Ordinary IKE payload flags are invalid");
            }
            int length = be16(packet, offset + 2);
            if (length < 4
                    || offset + length > packet.length) {
                throw new IllegalArgumentException(
                        "Ordinary IKE payload length is invalid");
            }
            payloads.add(new Payload(
                    type,
                    Arrays.copyOfRange(
                            packet,
                            offset + 4,
                            offset + length)));
            type = next;
            offset += length;
        }
        if (offset != packet.length) {
            throw new IllegalArgumentException(
                    "Ordinary IKE payload chain has trailing bytes");
        }
        return List.copyOf(payloads);
    }

    private static byte[] buildPacket(
            byte[] initiatorSpi,
            byte[] responderSpi,
            int flags,
            List<byte[]> payloads,
            int expectedLength) {
        ByteArrayOutputStream payloadBytes =
                new ByteArrayOutputStream();
        for (byte[] payload : payloads) {
            payloadBytes.writeBytes(payload);
        }
        int length =
                IKE_HEADER_LENGTH + payloadBytes.size();
        if (length != expectedLength) {
            throw new IllegalStateException(
                    "Ordinary IKE_SA_INIT length is "
                            + length
                            + ", expected "
                            + expectedLength);
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(length);
        output.writeBytes(initiatorSpi);
        output.writeBytes(responderSpi);
        output.write(IkeV2Codec.PAYLOAD_SA);
        output.write(0x20);
        output.write(IkeV2Codec.EXCHANGE_IKE_SA_INIT);
        output.write(flags);
        writeBe32(output, 0);
        writeBe32(output, length);
        output.writeBytes(payloadBytes.toByteArray());
        return output.toByteArray();
    }

    private static byte[] keyExchangePayload(
            int nextPayload,
            byte[] x448PublicKey) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + x448PublicKey.length);
        writeBe16(
                body,
                IkeV2Codec.DH_GROUP_CURVE_448);
        writeBe16(body, 0);
        body.writeBytes(x448PublicKey);
        return genericPayload(
                nextPayload,
                body.toByteArray());
    }

    private static byte[] notifyPayload(
            int nextPayload,
            int notifyType,
            byte[] data) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + data.length);
        body.write(0);
        body.write(0);
        writeBe16(body, notifyType);
        body.writeBytes(data);
        return genericPayload(
                nextPayload,
                body.toByteArray());
    }

    private static byte[] genericPayload(
            int nextPayload,
            byte[] body) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        4 + body.length);
        output.write(nextPayload);
        output.write(0);
        writeBe16(output, 4 + body.length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static byte[] buildRequestSaBody() {
        ByteArrayOutputStream transforms =
                new ByteArrayOutputStream();
        transforms.writeBytes(transform(
                true,
                1,
                20,
                new byte[]{
                        (byte) 0x80, 0x0e, 0x01, 0x00
                }));
        transforms.writeBytes(transform(
                true,
                1,
                28,
                new byte[0]));
        transforms.writeBytes(transform(
                true,
                2,
                7,
                new byte[0]));
        transforms.writeBytes(transform(
                true,
                6,
                ADDITIONAL_KE_METHOD_ML_KEM_768,
                new byte[0]));
        transforms.writeBytes(transform(
                true,
                4,
                32,
                new byte[0]));
        transforms.writeBytes(transform(
                false,
                4,
                31,
                new byte[0]));
        return proposal(transforms.toByteArray(), 6);
    }

    private static byte[] buildResponseSaBody() {
        ByteArrayOutputStream transforms =
                new ByteArrayOutputStream();
        transforms.writeBytes(transform(
                true,
                1,
                20,
                new byte[]{
                        (byte) 0x80, 0x0e, 0x01, 0x00
                }));
        transforms.writeBytes(transform(
                true,
                2,
                7,
                new byte[0]));
        transforms.writeBytes(transform(
                true,
                6,
                ADDITIONAL_KE_METHOD_ML_KEM_768,
                new byte[0]));
        transforms.writeBytes(transform(
                false,
                4,
                32,
                new byte[0]));
        return proposal(transforms.toByteArray(), 4);
    }

    private static byte[] proposal(
            byte[] transforms,
            int transformCount) {
        int length = 8 + transforms.length;
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(length);
        output.write(0);
        output.write(0);
        writeBe16(output, length);
        output.write(1);
        output.write(1);
        output.write(0);
        output.write(transformCount);
        output.writeBytes(transforms);
        return output.toByteArray();
    }

    private static byte[] transform(
            boolean more,
            int type,
            int id,
            byte[] attributes) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        8 + attributes.length);
        output.write(more ? 3 : 0);
        output.write(0);
        writeBe16(output, 8 + attributes.length);
        output.write(type);
        output.write(0);
        writeBe16(output, id);
        output.writeBytes(attributes);
        return output.toByteArray();
    }

    private static byte[] randomNonzeroSpi(
            SecureRandom random) {
        byte[] spi = new byte[8];
        random.nextBytes(spi);
        if (isAllZero(spi)) {
            spi[7] = 1;
        }
        return spi;
    }

    private static void requireRandom(
            SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
    }

    private static void requireLength(
            String label,
            byte[] value,
            int length) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    label + " must contain " + length + " bytes");
        }
    }

    private static boolean isAllZero(
            byte[] value) {
        int combined = 0;
        for (byte current : value) {
            combined |= current;
        }
        return combined == 0;
    }

    private static int unsigned(
            byte value) {
        return value & 0xff;
    }

    private static int be16(
            byte[] value,
            int offset) {
        return (unsigned(value[offset]) << 8)
                | unsigned(value[offset + 1]);
    }

    private static int be32(
            byte[] value,
            int offset) {
        return (unsigned(value[offset]) << 24)
                | (unsigned(value[offset + 1]) << 16)
                | (unsigned(value[offset + 2]) << 8)
                | unsigned(value[offset + 3]);
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeBe32(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class InitiatorState {
        final byte[] initiatorSpi;
        final byte[] nonce;
        final byte[] x448PrivateKey;
        final byte[] x448PublicKey;
        final byte[] packet;

        InitiatorState(
                byte[] initiatorSpi,
                byte[] nonce,
                byte[] x448PrivateKey,
                byte[] x448PublicKey,
                byte[] packet) {
            this.initiatorSpi = initiatorSpi.clone();
            this.nonce = nonce.clone();
            this.x448PrivateKey = x448PrivateKey.clone();
            this.x448PublicKey = x448PublicKey.clone();
            this.packet = packet.clone();
        }

        void destroy() {
            wipe(initiatorSpi);
            wipe(nonce);
            wipe(x448PrivateKey);
            wipe(x448PublicKey);
            wipe(packet);
        }
    }

    static final class Request {
        final byte[] initiatorSpi;
        final byte[] nonce;
        final byte[] x448PublicKey;
        final byte[] packet;

        Request(
                byte[] initiatorSpi,
                byte[] nonce,
                byte[] x448PublicKey,
                byte[] packet) {
            this.initiatorSpi = initiatorSpi.clone();
            this.nonce = nonce.clone();
            this.x448PublicKey = x448PublicKey.clone();
            this.packet = packet.clone();
        }

        void destroy() {
            wipe(initiatorSpi);
            wipe(nonce);
            wipe(x448PublicKey);
            wipe(packet);
        }
    }

    static final class ResponderState {
        final byte[] responderSpi;
        final byte[] nonce;
        final byte[] x448PrivateKey;
        final byte[] x448PublicKey;
        final byte[] packet;

        ResponderState(
                byte[] responderSpi,
                byte[] nonce,
                byte[] x448PrivateKey,
                byte[] x448PublicKey,
                byte[] packet) {
            this.responderSpi = responderSpi.clone();
            this.nonce = nonce.clone();
            this.x448PrivateKey = x448PrivateKey.clone();
            this.x448PublicKey = x448PublicKey.clone();
            this.packet = packet.clone();
        }

        void destroy() {
            wipe(responderSpi);
            wipe(nonce);
            wipe(x448PrivateKey);
            wipe(x448PublicKey);
            wipe(packet);
        }
    }

    static final class Response {
        final byte[] responderSpi;
        final byte[] nonce;
        final byte[] x448PublicKey;
        final int capabilityFlags;
        final byte[] packet;

        Response(
                byte[] responderSpi,
                byte[] nonce,
                byte[] x448PublicKey,
                int capabilityFlags,
                byte[] packet) {
            this.responderSpi = responderSpi.clone();
            this.nonce = nonce.clone();
            this.x448PublicKey = x448PublicKey.clone();
            this.capabilityFlags = capabilityFlags;
            this.packet = packet.clone();
        }

        void destroy() {
            wipe(responderSpi);
            wipe(nonce);
            wipe(x448PublicKey);
            wipe(packet);
        }
    }

    private static final class Parsed {
        final byte[] initiatorSpi;
        final byte[] responderSpi;
        final byte[] nonce;
        final byte[] x448PublicKey;
        final int capabilityFlags;

        Parsed(
                byte[] initiatorSpi,
                byte[] responderSpi,
                byte[] nonce,
                byte[] x448PublicKey,
                int capabilityFlags) {
            this.initiatorSpi = initiatorSpi;
            this.responderSpi = responderSpi;
            this.nonce = nonce;
            this.x448PublicKey = x448PublicKey;
            this.capabilityFlags = capabilityFlags;
        }
    }

    private static final class Payload {
        final int type;
        final byte[] body;

        Payload(
                int type,
                byte[] body) {
            this.type = type;
            this.body = body;
        }
    }
}
