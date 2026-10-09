package dev.applewatchandroid.bridge;

import org.bouncycastle.math.ec.rfc7748.X448;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Exact first control-session packet used by iOS NetworkExtension on the
 * direct {@code com.apple.terminusPairing} Bluetooth pipe.
 *
 * <p>The packet layout and proposal ordering in this class were recovered from
 * iOS 26.6 (23G71). This class deliberately owns only IKE_SA_INIT; later
 * protected exchanges are isolated in {@link IkeV2SessionCrypto}.</p>
 */
final class IkeV2Codec {
    static final int UIKE_TYPE_IKEV2_POINT_TO_POINT = 0x04;

    static final int PAYLOAD_NONE = 0;
    static final int PAYLOAD_SA = 33;
    static final int PAYLOAD_KE = 34;
    static final int PAYLOAD_NONCE = 40;
    static final int PAYLOAD_NOTIFY = 41;

    static final int EXCHANGE_IKE_SA_INIT = 34;
    static final int IKE_FLAG_INITIATOR = 0x08;
    static final int IKE_FLAG_RESPONSE = 0x20;

    static final int NOTIFY_NAT_DETECTION_SOURCE_IP = 0x4004;
    static final int NOTIFY_NAT_DETECTION_DESTINATION_IP = 0x4005;
    static final int NOTIFY_CHILDLESS_IKEV2_SUPPORTED = 0x4022;
    static final int NOTIFY_SECURE_PASSWORD_METHODS = 0x4028;
    static final int NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED = 0x402E;
    static final int NOTIFY_USE_PPK = 0x4033;
    static final int NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED = 0x4036;
    static final int SECURE_PASSWORD_SPAKE2_PLUS = 0x2AF9;

    static final int DH_GROUP_CURVE_448 = 32;
    static final int CONTROL_SA_INIT_LENGTH = 264;
    static final int CONTROL_UIKE_FRAME_LENGTH = 269;
    static final int PAIRING_SA_INIT_LENGTH = 282;
    static final int PAIRING_UIKE_FRAME_LENGTH = 287;

    private static final int IKE_HEADER_LENGTH = 28;
    private static final int UIKE_HEADER_LENGTH = 3;
    private static final int UIKE_CHECKSUM_LENGTH = 2;
    private static final int X448_KEY_LENGTH = 56;
    private static final int NONCE_LENGTH = 32;

    private IkeV2Codec() {
    }

    static InitiatorState createControlSaInit(SecureRandom random) {
        return createSaInit(random, false);
    }

    static InitiatorState createPairingSaInit(SecureRandom random) {
        return createSaInit(random, true);
    }

    /** Optical PSK pairing offers the childless X448/ML-KEM profile without
     * the PIN-only SECURE_PASSWORD_METHODS and USE_PPK notifications. */
    static InitiatorState createOpticalPairingSaInit(SecureRandom random) {
        return createSaInit(random, false);
    }

    private static InitiatorState createSaInit(
            SecureRandom random,
            boolean pairing) {
        if (random == null) {
            throw new IllegalArgumentException("SecureRandom is required");
        }

        byte[] initiatorSpi = new byte[8];
        random.nextBytes(initiatorSpi);
        if (isAllZero(initiatorSpi)) {
            initiatorSpi[initiatorSpi.length - 1] = 1;
        }

        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);

        byte[] x448PrivateKey = new byte[X448_KEY_LENGTH];
        X448.generatePrivateKey(random, x448PrivateKey);
        byte[] x448PublicKey = new byte[X448_KEY_LENGTH];
        X448.generatePublicKey(x448PrivateKey, 0, x448PublicKey, 0);

        byte[] ikePacket = pairing
                ? buildPairingSaInit(
                        initiatorSpi,
                        nonce,
                        x448PublicKey)
                : buildControlSaInit(
                        initiatorSpi,
                        nonce,
                        x448PublicKey);
        byte[] uikeFrame = encodeUikeFrame(ikePacket);
        return new InitiatorState(
                initiatorSpi,
                nonce,
                x448PrivateKey,
                x448PublicKey,
                ikePacket,
                uikeFrame);
    }

    /**
     * Pure deterministic builder exposed to unit tests.
     */
    static byte[] buildControlSaInit(
            byte[] initiatorSpi,
            byte[] nonce,
            byte[] x448PublicKey) {
        return buildSaInit(
                initiatorSpi,
                nonce,
                x448PublicKey,
                false);
    }

    static byte[] buildPairingSaInit(
            byte[] initiatorSpi,
            byte[] nonce,
            byte[] x448PublicKey) {
        return buildSaInit(
                initiatorSpi,
                nonce,
                x448PublicKey,
                true);
    }

    private static byte[] buildSaInit(
            byte[] initiatorSpi,
            byte[] nonce,
            byte[] x448PublicKey,
            boolean pairing) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("nonce", nonce, NONCE_LENGTH);
        requireLength("X448 public key", x448PublicKey, X448_KEY_LENGTH);
        if (isAllZero(initiatorSpi)) {
            throw new IllegalArgumentException("Initiator SPI must be non-zero");
        }

        byte[] sa = genericPayload(
                PAYLOAD_KE,
                buildControlSaProposal());

        ByteArrayOutputStream keBody = new ByteArrayOutputStream();
        writeBe16(keBody, DH_GROUP_CURVE_448);
        writeBe16(keBody, 0);
        keBody.writeBytes(x448PublicKey);
        byte[] ke = genericPayload(PAYLOAD_NONCE, keBody.toByteArray());

        byte[] noncePayload = genericPayload(PAYLOAD_NOTIFY, nonce);
        byte[] natSource = notifyPayload(
                PAYLOAD_NOTIFY,
                NOTIFY_NAT_DETECTION_SOURCE_IP,
                new byte[20]);
        byte[] natDestination = notifyPayload(
                PAYLOAD_NOTIFY,
                NOTIFY_NAT_DETECTION_DESTINATION_IP,
                new byte[20]);
        byte[] fragmentation = notifyPayload(
                PAYLOAD_NOTIFY,
                NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED,
                new byte[0]);
        byte[] securePasswordMethods = pairing
                ? notifyPayload(
                        PAYLOAD_NOTIFY,
                        NOTIFY_SECURE_PASSWORD_METHODS,
                        new byte[]{
                                (byte) (SECURE_PASSWORD_SPAKE2_PLUS >>> 8),
                                (byte) SECURE_PASSWORD_SPAKE2_PLUS
                        })
                : new byte[0];
        byte[] usePpk = pairing
                ? notifyPayload(
                        PAYLOAD_NOTIFY,
                        NOTIFY_USE_PPK,
                        new byte[0])
                : new byte[0];
        byte[] intermediate = notifyPayload(
                PAYLOAD_NONE,
                NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED,
                new byte[0]);

        ByteArrayOutputStream payloads = new ByteArrayOutputStream();
        payloads.writeBytes(sa);
        payloads.writeBytes(ke);
        payloads.writeBytes(noncePayload);
        payloads.writeBytes(natSource);
        payloads.writeBytes(natDestination);
        payloads.writeBytes(fragmentation);
        payloads.writeBytes(securePasswordMethods);
        payloads.writeBytes(usePpk);
        payloads.writeBytes(intermediate);

        int packetLength = IKE_HEADER_LENGTH + payloads.size();
        int expectedLength = pairing
                ? PAIRING_SA_INIT_LENGTH
                : CONTROL_SA_INIT_LENGTH;
        if (packetLength != expectedLength) {
            throw new IllegalStateException(
                    "Unexpected "
                            + (pairing ? "pairing" : "control")
                            + " IKE_SA_INIT length "
                            + packetLength);
        }

        ByteArrayOutputStream packet = new ByteArrayOutputStream(packetLength);
        packet.writeBytes(initiatorSpi);
        packet.writeBytes(new byte[8]);
        packet.write(PAYLOAD_SA);
        packet.write(0x20); // IKEv2, major version 2 and minor version 0.
        packet.write(EXCHANGE_IKE_SA_INIT);
        packet.write(IKE_FLAG_INITIATOR);
        writeBe32(packet, 0); // Message ID.
        writeBe32(packet, packetLength);
        packet.writeBytes(payloads.toByteArray());
        return packet.toByteArray();
    }

    private static byte[] buildControlSaProposal() {
        ByteArrayOutputStream transforms = new ByteArrayOutputStream();

        // Apple generator ordering recovered from
        // -[NEIKEv2IKESAPayload generatePayloadData]:
        // encryption, integrity (none for AEAD), PRF, additional KEMs, primary
        // KEMs.
        transforms.writeBytes(transform(
                true,
                1,
                20,
                new byte[]{
                        (byte) 0x80, 0x0E, 0x01, 0x00
                })); // AES-GCM-16, 256-bit key.
        transforms.writeBytes(transform(
                true,
                1,
                28,
                new byte[0])); // ChaCha20-Poly1305.
        transforms.writeBytes(transform(
                true,
                2,
                7,
                new byte[0])); // PRF-HMAC-SHA2-512.
        transforms.writeBytes(transform(
                true,
                6,
                37,
                new byte[0])); // Additional Key Exchange 1: ML-KEM-1024.
        transforms.writeBytes(transform(
                true,
                4,
                32,
                new byte[0])); // Curve448.
        transforms.writeBytes(transform(
                false,
                4,
                31,
                new byte[0])); // Curve25519.

        int proposalLength = 8 + transforms.size();
        ByteArrayOutputStream proposal =
                new ByteArrayOutputStream(proposalLength);
        proposal.write(0); // Last proposal.
        proposal.write(0);
        writeBe16(proposal, proposalLength);
        proposal.write(1); // Proposal number.
        proposal.write(1); // Protocol ID: IKE.
        proposal.write(0); // SPI size.
        proposal.write(6); // Number of transforms.
        proposal.writeBytes(transforms.toByteArray());
        return proposal.toByteArray();
    }

    private static byte[] transform(
            boolean hasFollowingTransform,
            int transformType,
            int transformId,
            byte[] attributes) {
        int length = 8 + attributes.length;
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        output.write(hasFollowingTransform ? 3 : 0);
        output.write(0);
        writeBe16(output, length);
        output.write(transformType);
        output.write(0);
        writeBe16(output, transformId);
        output.writeBytes(attributes);
        return output.toByteArray();
    }

    private static byte[] notifyPayload(
            int nextPayload,
            int notifyType,
            byte[] data) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(4 + data.length);
        body.write(0); // Protocol ID: no protocol.
        body.write(0); // SPI size.
        writeBe16(body, notifyType);
        body.writeBytes(data);
        return genericPayload(nextPayload, body.toByteArray());
    }

    private static byte[] genericPayload(int nextPayload, byte[] body) {
        int length = 4 + body.length;
        ByteArrayOutputStream payload = new ByteArrayOutputStream(length);
        payload.write(nextPayload);
        payload.write(0); // Critical bit clear and reserved bits zero.
        writeBe16(payload, length);
        payload.writeBytes(body);
        return payload.toByteArray();
    }

    static byte[] encodeUikeFrame(byte[] ikePacket) {
        if (ikePacket == null || ikePacket.length > 0xFFFF) {
            throw new IllegalArgumentException(
                    "uIKE payload length must fit uint16");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream(
                UIKE_HEADER_LENGTH
                        + ikePacket.length
                        + UIKE_CHECKSUM_LENGTH);
        frame.write(UIKE_TYPE_IKEV2_POINT_TO_POINT);
        writeBe16(frame, ikePacket.length);
        frame.writeBytes(ikePacket);
        int checksum = internetChecksum(frame.toByteArray());
        writeBe16(frame, checksum);
        return frame.toByteArray();
    }

    static int internetChecksum(byte[] bytes) {
        long sum = 0;
        int offset = 0;
        while (offset + 1 < bytes.length) {
            sum += ((bytes[offset] & 0xFF) << 8)
                    | (bytes[offset + 1] & 0xFF);
            sum = (sum & 0xFFFF) + (sum >>> 16);
            offset += 2;
        }
        if (offset < bytes.length) {
            sum += (bytes[offset] & 0xFF) << 8;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return ((int) ~sum) & 0xFFFF;
    }

    static IkePacketSummary parseSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        requireLength("expected initiator SPI", expectedInitiatorSpi, 8);
        if (packet == null || packet.length < IKE_HEADER_LENGTH) {
            throw new IllegalArgumentException("IKE packet is truncated");
        }
        int declaredLength = be32(packet, 24);
        if (declaredLength != packet.length) {
            throw new IllegalArgumentException(
                    "IKE length mismatch: declared="
                            + declaredLength
                            + " actual="
                            + packet.length);
        }
        if (!Arrays.equals(
                expectedInitiatorSpi,
                Arrays.copyOfRange(packet, 0, 8))) {
            throw new IllegalArgumentException(
                    "IKE response initiator SPI does not match request");
        }
        byte[] responderSpi = Arrays.copyOfRange(packet, 8, 16);
        if (isAllZero(responderSpi)) {
            throw new IllegalArgumentException(
                    "IKE response has a zero responder SPI");
        }
        int version = unsigned(packet[17]);
        if ((version >>> 4) != 2) {
            throw new IllegalArgumentException(
                    String.format(
                            Locale.US,
                            "Unexpected IKE version 0x%02X",
                            version));
        }
        int exchange = unsigned(packet[18]);
        if (exchange != EXCHANGE_IKE_SA_INIT) {
            throw new IllegalArgumentException(
                    "Unexpected IKE exchange " + exchange);
        }
        int flags = unsigned(packet[19]);
        if ((flags & IKE_FLAG_RESPONSE) == 0) {
            throw new IllegalArgumentException(
                    String.format(
                            Locale.US,
                            "IKE_SA_INIT is not a response; flags=0x%02X",
                            flags));
        }
        long messageId = Integer.toUnsignedLong(be32(packet, 20));
        if (messageId != 0) {
            throw new IllegalArgumentException(
                    "IKE_SA_INIT response message ID is " + messageId);
        }

        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int payloadType = unsigned(packet[16]);
        int offset = IKE_HEADER_LENGTH;
        while (payloadType != PAYLOAD_NONE) {
            if (offset + 4 > packet.length) {
                throw new IllegalArgumentException(
                        "IKE payload header is truncated");
            }
            int nextPayload = unsigned(packet[offset]);
            int payloadLength = be16(packet, offset + 2);
            if (payloadLength < 4 || offset + payloadLength > packet.length) {
                throw new IllegalArgumentException(
                        "Invalid IKE payload length "
                                + payloadLength
                                + " at offset "
                                + offset);
            }
            payloadTypes.add(payloadType);
            if (payloadType == PAYLOAD_NOTIFY) {
                if (payloadLength < 8) {
                    throw new IllegalArgumentException(
                            "Notify payload is truncated");
                }
                int spiSize = unsigned(packet[offset + 5]);
                if (8 + spiSize > payloadLength) {
                    throw new IllegalArgumentException(
                            "Notify SPI exceeds payload length");
                }
                notifyTypes.add(be16(packet, offset + 6));
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != packet.length) {
            throw new IllegalArgumentException(
                    "IKE payload chain ended at "
                            + offset
                            + " of "
                            + packet.length);
        }
        return new IkePacketSummary(
                responderSpi,
                version,
                exchange,
                flags,
                messageId,
                payloadTypes,
                notifyTypes);
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static int be16(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8)
                | unsigned(bytes[offset + 1]);
    }

    private static int be32(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 24)
                | (unsigned(bytes[offset + 1]) << 16)
                | (unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    private static void writeBe16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeBe32(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void requireLength(
            String label,
            byte[] bytes,
            int expectedLength) {
        if (bytes == null || bytes.length != expectedLength) {
            throw new IllegalArgumentException(
                    label + " must be " + expectedLength + " bytes");
        }
    }

    private static boolean isAllZero(byte[] bytes) {
        int combined = 0;
        for (byte value : bytes) {
            combined |= value;
        }
        return combined == 0;
    }

    static final class UikeStreamDecoder {
        private byte[] buffer = new byte[0];

        List<byte[]> push(byte[] bytes) {
            if (bytes == null) {
                throw new IllegalArgumentException("uIKE stream chunk is null");
            }
            byte[] joined = Arrays.copyOf(
                    buffer,
                    buffer.length + bytes.length);
            System.arraycopy(
                    bytes,
                    0,
                    joined,
                    buffer.length,
                    bytes.length);
            buffer = joined;

            List<byte[]> packets = new ArrayList<>();
            int consumed = 0;
            while (buffer.length - consumed >= UIKE_HEADER_LENGTH) {
                int type = unsigned(buffer[consumed]);
                if (type != UIKE_TYPE_IKEV2_POINT_TO_POINT) {
                    throw new IllegalArgumentException(String.format(
                            Locale.US,
                            "Unexpected uIKE type 0x%02X",
                            type));
                }
                int payloadLength = be16(buffer, consumed + 1);
                int frameLength = UIKE_HEADER_LENGTH
                        + payloadLength
                        + UIKE_CHECKSUM_LENGTH;
                if (buffer.length - consumed < frameLength) {
                    break;
                }
                int checksumOffset =
                        consumed + UIKE_HEADER_LENGTH + payloadLength;
                int receivedChecksum = be16(buffer, checksumOffset);
                int expectedChecksum = internetChecksum(Arrays.copyOfRange(
                        buffer,
                        consumed,
                        checksumOffset));
                if (receivedChecksum != expectedChecksum) {
                    throw new IllegalArgumentException(String.format(
                            Locale.US,
                            "uIKE checksum mismatch received=0x%04X expected=0x%04X",
                            receivedChecksum,
                            expectedChecksum));
                }
                packets.add(Arrays.copyOfRange(
                        buffer,
                        consumed + UIKE_HEADER_LENGTH,
                        checksumOffset));
                consumed += frameLength;
            }
            if (consumed != 0) {
                buffer = Arrays.copyOfRange(buffer, consumed, buffer.length);
            }
            return packets;
        }

        int bufferedLength() {
            return buffer.length;
        }

        byte[] bufferedBytes() {
            return Arrays.copyOf(buffer, buffer.length);
        }
    }

    static final class InitiatorState {
        final byte[] initiatorSpi;
        final byte[] nonce;
        final byte[] x448PrivateKey;
        final byte[] x448PublicKey;
        final byte[] ikePacket;
        final byte[] uikeFrame;

        InitiatorState(
                byte[] initiatorSpi,
                byte[] nonce,
                byte[] x448PrivateKey,
                byte[] x448PublicKey,
                byte[] ikePacket,
                byte[] uikeFrame) {
            this.initiatorSpi =
                    Arrays.copyOf(initiatorSpi, initiatorSpi.length);
            this.nonce = Arrays.copyOf(nonce, nonce.length);
            this.x448PrivateKey =
                    Arrays.copyOf(x448PrivateKey, x448PrivateKey.length);
            this.x448PublicKey =
                    Arrays.copyOf(x448PublicKey, x448PublicKey.length);
            this.ikePacket = Arrays.copyOf(ikePacket, ikePacket.length);
            this.uikeFrame = Arrays.copyOf(uikeFrame, uikeFrame.length);
        }
    }

    static final class IkePacketSummary {
        final byte[] responderSpi;
        final int version;
        final int exchange;
        final int flags;
        final long messageId;
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;

        IkePacketSummary(
                byte[] responderSpi,
                int version,
                int exchange,
                int flags,
                long messageId,
                List<Integer> payloadTypes,
                List<Integer> notifyTypes) {
            this.responderSpi =
                    Arrays.copyOf(responderSpi, responderSpi.length);
            this.version = version;
            this.exchange = exchange;
            this.flags = flags;
            this.messageId = messageId;
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
        }
    }
}
