package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Codec and negotiation rules for the unencrypted NRLinkBluetooth prelude.
 *
 * <p>The encoder emits the exact 38-byte layout used by iOS/watchOS 26.6.
 * The parser also tolerates additional well-formed NRTLV fields, matching
 * Apple's length-delimited parser, while requiring the UUID and flags
 * fields used for role election.</p>
 */
final class NrLinkBluetoothPrelude {
    static final int VERSION = 1;
    static final int EXACT_ENCODED_LENGTH = 38;
    static final int UUID_LENGTH = 16;

    static final int FLAG_COMPANION_APL = 1;
    static final int FLAG_USES_TLS = 1 << 1;

    private static final byte[] MAGIC =
            "TERMINUS".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_LENGTH = 12;
    private static final int CHECKSUM_LENGTH = 2;
    private static final int UUID_TLV_TYPE = 4;
    private static final int FLAGS_TLV_TYPE = 5;
    private static final int UUID_TLV_ENCODED_LENGTH = 19;
    private static final int FLAGS_TLV_ENCODED_LENGTH = 5;
    private static final int EXACT_TLV_REGION_LENGTH =
            UUID_TLV_ENCODED_LENGTH + FLAGS_TLV_ENCODED_LENGTH;

    private static final int INCOMPATIBLE = -1;
    private static final int[][] RECONCILIATION = {
            {0, INCOMPATIBLE, 0, 0, INCOMPATIBLE, 0},
            {INCOMPATIBLE, 10, INCOMPATIBLE, 10,
                    INCOMPATIBLE, INCOMPATIBLE},
            {INCOMPATIBLE, INCOMPATIBLE, 11, 11,
                    INCOMPATIBLE, 11},
            {INCOMPATIBLE, 10, 11, 12,
                    INCOMPATIBLE, 12},
            {INCOMPATIBLE, INCOMPATIBLE, 11, 12, 13, 13},
            {INCOMPATIBLE, INCOMPATIBLE, 11, 12,
                    INCOMPATIBLE, 20}
    };

    private NrLinkBluetoothPrelude() {
    }

    enum PairingState {
        INVALID(0),
        PAIR_WITH_OOB_KEY(10),
        PAIR_WITH_IDS_KEYS(11),
        PAIR_WITH_OOB_KEY_OR_IDS_KEYS(12),
        MODERN_PAIRING_KEY_CONFIRMATION(13),
        HAS_COMPLETED_PAIRING(20);

        private final int wireValue;

        PairingState(int wireValue) {
            this.wireValue = wireValue;
        }

        int wireValue() {
            return wireValue;
        }

        static PairingState fromWireValue(int value) {
            for (PairingState state : values()) {
                if (state.wireValue == value) {
                    return state;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown NRLinkBluetooth pairing state " + value);
        }
    }

    enum LocalRole {
        INITIATOR,
        RESPONDER
    }

    static byte[] encodeFreshModern(
            byte[] localUuid,
            boolean companionApl,
            boolean usesTls) {
        int flags = 0;
        if (companionApl) {
            flags |= FLAG_COMPANION_APL;
        }
        if (usesTls) {
            flags |= FLAG_USES_TLS;
        }
        return encode(
                PairingState.MODERN_PAIRING_KEY_CONFIRMATION,
                localUuid,
                flags);
    }

    static byte[] encodePairedModern(
            byte[] localUuid,
            boolean companionApl,
            boolean usesTls) {
        int flags = 0;
        if (companionApl) {
            flags |= FLAG_COMPANION_APL;
        }
        if (usesTls) {
            flags |= FLAG_USES_TLS;
        }
        return encode(
                PairingState.HAS_COMPLETED_PAIRING,
                localUuid,
                flags);
    }

    static byte[] encode(
            PairingState state,
            byte[] localUuid,
            int flags) {
        if (state == null) {
            throw new IllegalArgumentException(
                    "Pairing state is required");
        }
        requireUuid(localUuid, "Local UUID");
        if ((flags & ~0xffff) != 0) {
            throw new IllegalArgumentException(
                    "Prelude flags must fit in an unsigned 16-bit value");
        }

        byte[] output = new byte[EXACT_ENCODED_LENGTH];
        System.arraycopy(MAGIC, 0, output, 0, MAGIC.length);
        output[8] = (byte) VERSION;
        output[9] = (byte) state.wireValue();
        writeU16Be(output, 10, EXACT_TLV_REGION_LENGTH);

        int cursor = HEADER_LENGTH;
        output[cursor++] = UUID_TLV_TYPE;
        writeU16Be(output, cursor, UUID_LENGTH);
        cursor += 2;
        System.arraycopy(
                localUuid,
                0,
                output,
                cursor,
                UUID_LENGTH);
        cursor += UUID_LENGTH;

        output[cursor++] = FLAGS_TLV_TYPE;
        writeU16Be(output, cursor, 2);
        cursor += 2;
        writeU16Be(output, cursor, flags);
        cursor += 2;

        int checksum = internetChecksum(output, cursor);
        writeU16Be(output, cursor, checksum);
        return output;
    }

    static Parsed parse(byte[] encoded) {
        if (encoded == null
                || encoded.length
                < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new IllegalArgumentException(
                    "NRLinkBluetooth prelude is too short");
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (encoded[index] != MAGIC[index]) {
                throw new IllegalArgumentException(
                        "NRLinkBluetooth prelude magic is invalid");
            }
        }

        int tlvRegionLength = readU16Be(encoded, 10);
        int fullLength = tlvRegionLength
                + HEADER_LENGTH
                + CHECKSUM_LENGTH;
        if (fullLength != encoded.length) {
            throw new IllegalArgumentException(
                    "NRLinkBluetooth prelude length is inconsistent");
        }

        int checksumOffset = encoded.length - CHECKSUM_LENGTH;
        int expectedChecksum =
                readU16Be(encoded, checksumOffset);
        int actualChecksum =
                internetChecksum(encoded, checksumOffset);
        if (expectedChecksum != actualChecksum) {
            throw new IllegalArgumentException(
                    "NRLinkBluetooth prelude checksum is invalid");
        }

        byte[] uuid = null;
        Integer flags = null;
        int cursor = HEADER_LENGTH;
        while (cursor < checksumOffset) {
            if (checksumOffset - cursor < 3) {
                throw new IllegalArgumentException(
                        "NRLinkBluetooth prelude has a truncated TLV");
            }
            int type = encoded[cursor++] & 0xff;
            int length = readU16Be(encoded, cursor);
            cursor += 2;
            if (length > checksumOffset - cursor) {
                throw new IllegalArgumentException(
                        "NRLinkBluetooth prelude TLV exceeds its region");
            }
            if (type == UUID_TLV_TYPE) {
                if (uuid != null || length != UUID_LENGTH) {
                    throw new IllegalArgumentException(
                            "NRLinkBluetooth UUID TLV is invalid");
                }
                uuid = Arrays.copyOfRange(
                        encoded,
                        cursor,
                        cursor + length);
            } else if (type == FLAGS_TLV_TYPE) {
                if (flags != null || length != 2) {
                    throw new IllegalArgumentException(
                            "NRLinkBluetooth flags TLV is invalid");
                }
                flags = readU16Be(encoded, cursor);
            }
            cursor += length;
        }
        if (cursor != checksumOffset
                || uuid == null
                || flags == null) {
            throw new IllegalArgumentException(
                    "NRLinkBluetooth prelude lacks required TLVs");
        }

        return new Parsed(
                encoded[8] & 0xff,
                PairingState.fromWireValue(encoded[9] & 0xff),
                uuid,
                flags);
    }

    static LocalRole electLocalRole(
            byte[] localUuid,
            byte[] remoteUuid) {
        requireUuid(localUuid, "Local UUID");
        requireUuid(remoteUuid, "Remote UUID");
        int comparison = compareUnsigned(
                localUuid,
                remoteUuid);
        if (comparison == 0) {
            throw new IllegalArgumentException(
                    "NRLinkBluetooth UUIDs must be distinct");
        }
        return comparison > 0
                ? LocalRole.RESPONDER
                : LocalRole.INITIATOR;
    }

    static PairingState reconcile(
            PairingState local,
            PairingState remote) {
        if (local == null || remote == null) {
            throw new IllegalArgumentException(
                    "Both pairing states are required");
        }
        int result = RECONCILIATION[
                stateIndex(local)][stateIndex(remote)];
        if (result == INCOMPATIBLE) {
            throw new IllegalArgumentException(
                    "Incompatible NRLinkBluetooth pairing states "
                            + local.wireValue()
                            + "/"
                            + remote.wireValue());
        }
        return PairingState.fromWireValue(result);
    }

    static boolean isBilaterallyCompatible(
            PairingState first,
            PairingState second) {
        try {
            reconcile(first, second);
            reconcile(second, first);
            return true;
        } catch (IllegalArgumentException incompatible) {
            return false;
        }
    }

    static String jointUuidHash(
            byte[] localUuid,
            byte[] remoteUuid) {
        requireUuid(localUuid, "Local UUID");
        requireUuid(remoteUuid, "Remote UUID");
        byte[] joint = new byte[UUID_LENGTH];
        for (int index = 0; index < UUID_LENGTH; index++) {
            joint[index] =
                    (byte) (localUuid[index] ^ remoteUuid[index]);
        }
        for (int index = 0; index < 4; index++) {
            joint[index] = (byte) (
                    joint[index]
                            ^ joint[index + 4]
                            ^ joint[index + 8]
                            ^ joint[index + 12]);
        }
        try {
            return Base64.getEncoder()
                    .encodeToString(joint)
                    .substring(0, 6);
        } finally {
            Arrays.fill(joint, (byte) 0);
        }
    }

    static int internetChecksum(
            byte[] bytes,
            int length) {
        if (bytes == null
                || length < 0
                || length > bytes.length) {
            throw new IllegalArgumentException(
                    "Checksum range is invalid");
        }
        long sum = 0;
        int cursor = 0;
        while (cursor + 1 < length) {
            sum += ((bytes[cursor] & 0xff) << 8)
                    | (bytes[cursor + 1] & 0xff);
            sum = (sum & 0xffff) + (sum >>> 16);
            cursor += 2;
        }
        if (cursor < length) {
            sum += (bytes[cursor] & 0xff) << 8;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static int stateIndex(
            PairingState state) {
        return switch (state) {
            case INVALID -> 0;
            case PAIR_WITH_OOB_KEY -> 1;
            case PAIR_WITH_IDS_KEYS -> 2;
            case PAIR_WITH_OOB_KEY_OR_IDS_KEYS -> 3;
            case MODERN_PAIRING_KEY_CONFIRMATION -> 4;
            case HAS_COMPLETED_PAIRING -> 5;
        };
    }

    private static int compareUnsigned(
            byte[] first,
            byte[] second) {
        for (int index = 0; index < first.length; index++) {
            int comparison = Integer.compare(
                    first[index] & 0xff,
                    second[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static void requireUuid(
            byte[] uuid,
            String label) {
        if (uuid == null || uuid.length != UUID_LENGTH) {
            throw new IllegalArgumentException(
                    label + " must be exactly 16 bytes");
        }
    }

    private static int readU16Be(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static void writeU16Be(
            byte[] bytes,
            int offset,
            int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    static final class Parsed {
        final int version;
        final PairingState state;
        final int flags;
        private final byte[] uuid;

        private Parsed(
                int version,
                PairingState state,
                byte[] uuid,
                int flags) {
            this.version = version;
            this.state = state;
            this.uuid = uuid.clone();
            this.flags = flags;
            Arrays.fill(uuid, (byte) 0);
        }

        byte[] uuid() {
            return uuid.clone();
        }

        boolean companionApl() {
            return (flags & FLAG_COMPANION_APL) != 0;
        }

        boolean usesTls() {
            return (flags & FLAG_USES_TLS) != 0;
        }
    }
}
