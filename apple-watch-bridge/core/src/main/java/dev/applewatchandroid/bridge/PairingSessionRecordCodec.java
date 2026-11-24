package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Binary AWP2 wire serialization, parsing, and field validation for {@link PairingSessionRecord}. */
final class PairingSessionRecordCodec {
    private PairingSessionRecordCodec() {}

    static PairingSessionRecord parse(byte[] serialized) {
        if (serialized == null
                || serialized.length
                < PairingSessionRecord.HEADER_LENGTH + PairingSessionRecord.DIGEST_LENGTH
                || serialized.length > PairingSessionRecord.MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing session record size is invalid");
        }
        int digestOffset =
                serialized.length - PairingSessionRecord.DIGEST_LENGTH;
        byte[] expectedDigest = Arrays.copyOfRange(
                serialized,
                digestOffset,
                serialized.length);
        byte[] actualDigest = sha256(
                serialized,
                0,
                digestOffset);
        try {
            if (!MessageDigest.isEqual(
                    expectedDigest,
                    actualDigest)) {
                throw new IllegalArgumentException(
                        "Pairing session record digest is invalid");
            }
        } finally {
            wipe(expectedDigest);
            wipe(actualDigest);
        }

        ByteBuffer header = ByteBuffer.wrap(
                        serialized,
                        0,
                        digestOffset)
                .order(ByteOrder.BIG_ENDIAN);
        for (byte expected : PairingSessionRecord.MAGIC) {
            if (header.get() != expected) {
                throw new IllegalArgumentException(
                        "Pairing session record magic is invalid");
            }
        }
        int version = header.getShort() & 0xffff;
        if (version != PairingSessionRecord.FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Pairing session record version is unsupported");
        }
        int headerLength = header.getShort() & 0xffff;
        if (headerLength != PairingSessionRecord.HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing session record header is invalid");
        }
        PairingSessionRecord.DurableState state =
                PairingSessionRecord.DurableState.fromWireValue(
                        header.getShort() & 0xffff);
        int roleCode = header.get() & 0xff;
        NrLinkBluetoothPrelude.LocalRole role =
                roleFromCode(roleCode);
        int flags = header.get() & 0xff;
        if ((flags & ~PairingSessionRecord.KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "Pairing session record flags are invalid");
        }
        long transitionCounter = header.getLong();
        int fieldCount = header.getShort() & 0xffff;
        int payloadLength = header.getInt();
        if (payloadLength < 0
                || PairingSessionRecord.HEADER_LENGTH + payloadLength
                != digestOffset) {
            throw new IllegalArgumentException(
                    "Pairing session record payload length is invalid");
        }

        Map<Integer, byte[]> fields = new HashMap<>();
        int previousType = -1;
        try {
            for (int index = 0; index < fieldCount; index++) {
                if (header.remaining() < 6) {
                    throw new IllegalArgumentException(
                            "Pairing session field header is truncated");
                }
                int type = header.getShort() & 0xffff;
                int length = header.getInt();
                if (type <= previousType
                        || !isKnownField(type)
                        || length < 0
                        || length > header.remaining()) {
                    throw new IllegalArgumentException(
                            "Pairing session field layout is invalid");
                }
                byte[] value = new byte[length];
                header.get(value);
                fields.put(type, value);
                previousType = type;
            }
            if (header.position() != digestOffset) {
                throw new IllegalArgumentException(
                        "Pairing session record has trailing payload bytes");
            }

            PairingSessionRecord decoded = new PairingSessionRecord(
                    required(fields, PairingSessionRecord.FIELD_GENERATION_UUID),
                    state,
                    transitionCounter,
                    (flags & PairingSessionRecord.FLAG_MODERN_PAIRING) != 0,
                    (flags & PairingSessionRecord.FLAG_ADDRESSES_CONFIRMED) != 0,
                    (flags & PairingSessionRecord.FLAG_IDS_AUTH_CONSUMED) != 0,
                    (flags & PairingSessionRecord.FLAG_IS_PAIRED_COMMIT_INTENT) != 0,
                    role,
                    fields.get(PairingSessionRecord.FIELD_BLUETOOTH_BOND),
                    fields.get(PairingSessionRecord.FIELD_PENDING_LOCAL_C547),
                    fields.get(PairingSessionRecord.FIELD_PENDING_REMOTE_C547),
                    fields.get(PairingSessionRecord.FIELD_PENDING_IDS_AUTH_DATA),
                    required(
                            fields,
                            PairingSessionRecord.FIELD_LOCAL_NETWORK_MATERIAL),
                    required(fields, PairingSessionRecord.FIELD_REMOTE_IDENTITY),
                    required(fields, PairingSessionRecord.FIELD_REMOTE_CLASS_D),
                    required(fields, PairingSessionRecord.FIELD_REMOTE_CLASS_C),
                    required(
                            fields,
                            PairingSessionRecord.FIELD_INITIATOR_CLASS_D_ADDRESS),
                    required(
                            fields,
                            PairingSessionRecord.FIELD_RESPONDER_CLASS_D_ADDRESS),
                    required(
                            fields,
                            PairingSessionRecord.FIELD_INITIATOR_CLASS_C_ADDRESS),
                    required(
                            fields,
                            PairingSessionRecord.FIELD_RESPONDER_CLASS_C_ADDRESS),
                    parseU16(
                            required(
                                    fields,
                                    PairingSessionRecord.FIELD_NETWORK_RELAY_VERSION),
                            "NetworkRelay version"),
                    parseU8(
                            required(
                                    fields,
                                    PairingSessionRecord.FIELD_DEVICE_TYPE),
                            "device type"),
                    parseBoolean(
                            required(
                                    fields,
                                    PairingSessionRecord.FIELD_ALWAYS_ON_WIFI),
                            "always-on Wi-Fi"),
                    decodeRequiredText(
                            fields,
                            PairingSessionRecord.FIELD_DEVICE_NAME,
                            "device name"),
                    decodeRequiredText(
                            fields,
                            PairingSessionRecord.FIELD_BUILD_VERSION,
                            "build version"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_IDS_DEVICE_ID,
                            "IDS device ID"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_PRODUCT_TYPE,
                            "product type"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_BOARD_ID,
                            "board ID"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_BLUETOOTH_CB_UUID,
                            "Bluetooth CBUUID"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_NR_UUID,
                            "NRUUID"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_LOCAL_IDS_DEVICE_UUID,
                            "local IDS device UUID"),
                    decodeOptionalText(
                            fields,
                            PairingSessionRecord.FIELD_REMOTE_INSTANCE_UUID,
                            "remote instance UUID"));
            try {
                decoded.observedSetupEvidence = (flags & PairingSessionRecord.FLAG_OBSERVED_SETUP_EVIDENCE) != 0;
                decoded.idsDeviceInfoExchanged = (flags & PairingSessionRecord.FLAG_IDS_DEVICE_INFO_EXCHANGED) != 0;
                decoded.peerSpsMetadata = cloneOrNull(fields.get(PairingSessionRecord.FIELD_PEER_SPS_METADATA));
                byte[] maximum = fields.get(PairingSessionRecord.FIELD_OBSERVED_PEER_MAXIMUM);
                if (maximum != null) {
                    if (maximum.length != 2) throw new IllegalArgumentException("Invalid observed peer maximum");
                    decoded.observedPeerMaximum = (maximum[0] & 255) << 8 | maximum[1] & 255;
                }
                decoded.observedPeerBuild = decodeOptionalText(fields, PairingSessionRecord.FIELD_OBSERVED_PEER_BUILD, "observed Watch build");
                validate(decoded);
                return decoded;
            } catch (RuntimeException error) {
                decoded.destroy();
                throw error;
            }

        } finally {
            for (byte[] value : fields.values()) {
                wipe(value);
            }
        }
    }

    static byte[] serialize(PairingSessionRecord record) {
        validate(record);
        List<Field> fields = buildFields(record);
        try {
            int payloadLength = 0;
            for (Field field : fields) {
                payloadLength += 6 + field.value.length;
            }
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(
                            PairingSessionRecord.HEADER_LENGTH
                                    + payloadLength
                                    + PairingSessionRecord.DIGEST_LENGTH);
            try (DataOutputStream output =
                         new DataOutputStream(bytes)) {
                output.write(PairingSessionRecord.MAGIC);
                output.writeShort(PairingSessionRecord.FORMAT_VERSION);
                output.writeShort(PairingSessionRecord.HEADER_LENGTH);
                output.writeShort(record.state.wireValue());
                output.writeByte(roleCode(record.localRole));
                output.writeByte(record.flags());
                output.writeLong(record.transitionCounter);
                output.writeShort(fields.size());
                output.writeInt(payloadLength);
                for (Field field : fields) {
                    output.writeShort(field.type);
                    output.writeInt(field.value.length);
                    output.write(field.value);
                }
                output.flush();
                byte[] withoutDigest = bytes.toByteArray();
                byte[] digest = sha256(
                        withoutDigest,
                        0,
                        withoutDigest.length);
                try {
                    output.write(digest);
                    output.flush();
                } finally {
                    wipe(withoutDigest);
                    wipe(digest);
                }
            }
            byte[] serialized = bytes.toByteArray();
            if (serialized.length > PairingSessionRecord.MAX_SERIALIZED_LENGTH) {
                wipe(serialized);
                throw new IllegalStateException(
                        "Pairing session record is too large");
            }
            return serialized;
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "Cannot serialize pairing session record",
                    impossible);
        } finally {
            for (Field field : fields) {
                field.destroy();
            }
        }
    }

    static List<Field> buildFields(PairingSessionRecord record) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field(
                PairingSessionRecord.FIELD_GENERATION_UUID,
                record.generationUuid));
        addOptional(
                fields,
                PairingSessionRecord.FIELD_BLUETOOTH_BOND,
                record.bluetoothBond);
        addOptional(
                fields,
                PairingSessionRecord.FIELD_PENDING_LOCAL_C547,
                record.pendingLocalC547);
        addOptional(
                fields,
                PairingSessionRecord.FIELD_PENDING_REMOTE_C547,
                record.pendingRemoteC547);
        addOptional(
                fields,
                PairingSessionRecord.FIELD_PENDING_IDS_AUTH_DATA,
                record.pendingIdsAuthData);
        fields.add(new Field(
                PairingSessionRecord.FIELD_LOCAL_NETWORK_MATERIAL,
                record.localNetworkMaterial));
        fields.add(new Field(
                PairingSessionRecord.FIELD_REMOTE_IDENTITY,
                record.remoteIdentity));
        fields.add(new Field(
                PairingSessionRecord.FIELD_REMOTE_CLASS_D,
                record.remoteClassD));
        fields.add(new Field(
                PairingSessionRecord.FIELD_REMOTE_CLASS_C,
                record.remoteClassC));
        fields.add(new Field(
                PairingSessionRecord.FIELD_INITIATOR_CLASS_D_ADDRESS,
                record.initiatorClassDAddress));
        fields.add(new Field(
                PairingSessionRecord.FIELD_RESPONDER_CLASS_D_ADDRESS,
                record.responderClassDAddress));
        fields.add(new Field(
                PairingSessionRecord.FIELD_INITIATOR_CLASS_C_ADDRESS,
                record.initiatorClassCAddress));
        fields.add(new Field(
                PairingSessionRecord.FIELD_RESPONDER_CLASS_C_ADDRESS,
                record.responderClassCAddress));
        fields.add(new Field(
                PairingSessionRecord.FIELD_NETWORK_RELAY_VERSION,
                u16(record.networkRelayVersion)));
        fields.add(new Field(
                PairingSessionRecord.FIELD_DEVICE_TYPE,
                new byte[]{(byte) record.deviceType}));
        fields.add(new Field(
                PairingSessionRecord.FIELD_ALWAYS_ON_WIFI,
                new byte[]{record.alwaysOnWifi ? (byte) 1 : 0}));
        addText(
                fields,
                PairingSessionRecord.FIELD_DEVICE_NAME,
                record.deviceName);
        addText(
                fields,
                PairingSessionRecord.FIELD_BUILD_VERSION,
                record.buildVersion);
        addText(
                fields,
                PairingSessionRecord.FIELD_IDS_DEVICE_ID,
                record.idsDeviceId);
        addText(
                fields,
                PairingSessionRecord.FIELD_PRODUCT_TYPE,
                record.productType);
        addText(
                fields,
                PairingSessionRecord.FIELD_BOARD_ID,
                record.boardId);
        addText(
                fields,
                PairingSessionRecord.FIELD_BLUETOOTH_CB_UUID,
                record.bluetoothCbUuid);
        addText(
                fields,
                PairingSessionRecord.FIELD_NR_UUID,
                record.nrUuid);
        addText(
                fields,
                PairingSessionRecord.FIELD_LOCAL_IDS_DEVICE_UUID,
                record.localIdsDeviceUuid);
        if (record.peerSpsMetadata != null) fields.add(new Field(PairingSessionRecord.FIELD_PEER_SPS_METADATA, record.peerSpsMetadata));
        if (record.observedPeerMaximum != null) fields.add(new Field(PairingSessionRecord.FIELD_OBSERVED_PEER_MAXIMUM,
                new byte[]{(byte) (record.observedPeerMaximum >>> 8), (byte) (int) record.observedPeerMaximum}));
        addText(fields, PairingSessionRecord.FIELD_OBSERVED_PEER_BUILD, record.observedPeerBuild);
        return fields;
    }

    static void validate(PairingSessionRecord record) {
        if (record.observedPeerMaximum != null || record.observedPeerBuild != null) {
            if (record.observedPeerMaximum == null || record.observedPeerMaximum < 1 || record.observedPeerMaximum > 0xffff
                    || record.observedPeerBuild == null || !record.observedPeerBuild.matches("[0-9]{1,3}[A-Za-z][0-9]{1,6}[a-z]?")
                    || record.state == null || record.state.wireValue() < PairingSessionRecord.DurableState.IDS_CONTROL_READY.wireValue())
                throw new IllegalArgumentException("Invalid authenticated Watch system observation");
        }
        if (record.peerSpsMetadata != null) {
            if (record.state == null || record.state.wireValue() < PairingSessionRecord.DurableState.SMP_BONDED_RAW.wireValue()) {
                throw new IllegalArgumentException("SPS metadata has no authenticated pairing owner");
            }
            try (IdsSpsCompanionInfo ignored = IdsSpsCompanionInfo.parse(record.peerSpsMetadata)) { }
        }
        if (record.idsDeviceInfoExchanged && record.state.wireValue() < PairingSessionRecord.DurableState.IDS_CONTROL_READY.wireValue()) {
            throw new IllegalArgumentException("IDS device-info evidence precedes control registration");
        }
        if (record.observedSetupEvidence && record.state.wireValue() < PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            throw new IllegalArgumentException("Observed setup evidence predates activation");
        }
        requireLength(
                "pairing generation UUID",
                record.generationUuid,
                16);
        requireNonzero(
                "pairing generation UUID",
                record.generationUuid);
        if (record.state == null
                || record.transitionCounter < 1
                || !record.modernPairing) {
            throw new IllegalArgumentException(
                    "Pairing session state metadata is invalid");
        }
        if (record.networkRelayVersion < 0
                || record.networkRelayVersion > 0xffff
                || record.deviceType < 0
                || record.deviceType > 0xff) {
            throw new IllegalArgumentException(
                    "Pairing peer metadata is invalid");
        }

        validateText("device name", record.deviceName, true);
        validateText("build version", record.buildVersion, true);
        validateText("IDS device ID", record.idsDeviceId, false);
        validateText("product type", record.productType, false);
        validateText("board ID", record.boardId, false);
        validateCanonicalUuid(
                "local Bluetooth peer UUID",
                record.bluetoothCbUuid,
                true);
        validateCanonicalUuid(
                "local NRDevice UUID",
                record.nrUuid,
                true);
        validateCanonicalUuid(
                "local IDS device UUID",
                record.localIdsDeviceUuid,
                false);
        if (record.remoteInstanceUuid != null) {
            throw new IllegalArgumentException(
                    "Remote IDS instance UUID is transient and "
                            + "must not be persisted");
        }

        AppleNetworkRelayPairingMaterial local = null;
        AppleNetworkRelayInnerAddresses addresses = null;
        try {
            local = AppleNetworkRelayPairingMaterial
                    .restorePrivateSnapshot(
                            record.localNetworkMaterial);
            addresses =
                    AppleNetworkRelayInnerAddresses
                            .fromAuthoritative(
                                    record.initiatorClassDAddress,
                                    record.responderClassDAddress,
                                    record.initiatorClassCAddress,
                                    record.responderClassCAddress);
        } finally {
            if (local != null) {
                local.destroy();
            }
            if (addresses != null) {
                addresses.destroy();
            }
        }
        requireLength(
                "remote identity",
                record.remoteIdentity,
                AppleNetworkRelayPairingMaterial
                        .IDENTITY_PAYLOAD_LENGTH);
        requireNonzero(
                "remote identity",
                record.remoteIdentity);
        requireModernPublicKeys(
                "remote Class D",
                record.remoteClassD);
        requireModernPublicKeys(
                "remote Class C",
                record.remoteClassC);

        if (record.state
                == PairingSessionRecord.DurableState.PAIRING_MATERIAL_PERSISTED) {
            if (record.bluetoothBond != null
                    || record.pendingLocalC547 == null
                    || record.pendingRemoteC547 == null) {
                throw new IllegalArgumentException(
                        "Pre-SMP session material is inconsistent");
            }
        } else {
            if (record.bluetoothBond == null
                    || record.pendingLocalC547 != null
                    || record.pendingRemoteC547 != null) {
                throw new IllegalArgumentException(
                        "Post-SMP session material is inconsistent");
            }
            BluetoothBondSecretRecord
                    .validateSerialized(
                            record.bluetoothBond);
        }
        validateC547(
                "local pending C547",
                record.pendingLocalC547);
        validateC547(
                "remote pending C547",
                record.pendingRemoteC547);

        if (record.idsAuthConsumed) {
            if (record.pendingIdsAuthData != null) {
                throw new IllegalArgumentException(
                        "Consumed IDS authentication data remains present");
            }
        } else if (record.pendingIdsAuthData == null
                || (record.pendingIdsAuthData.length != 4
                && record.pendingIdsAuthData.length != 32)) {
            throw new IllegalArgumentException(
                    "Pending IDS authentication data has "
                            + "an invalid length");
        }
        if (record.state.wireValue()
                >= PairingSessionRecord.DurableState.IDS_CONTROL_READY.wireValue()
                && !record.idsAuthConsumed) {
            throw new IllegalArgumentException(
                    "IDS durable state lacks local paired-device "
                            + "registration");
        }

        if (record.isPairedCommitIntentPersisted
                && record.state
                != PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED) {
            throw new IllegalArgumentException(
                    "IS_PAIRED commit intent is attached to the wrong state");
        }

        boolean stateRequiresRole =
                record.state.wireValue()
                        >= PairingSessionRecord.DurableState
                                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                                .wireValue();
        boolean stateRequiresConfirmedAddresses =
                record.state.wireValue()
                        >= PairingSessionRecord.DurableState
                                .CLASS_D_ESTABLISHED
                                .wireValue();
        if ((record.localRole != null) != stateRequiresRole
                || (stateRequiresConfirmedAddresses
                && !record.addressesConfirmed)
                || (!stateRequiresRole
                && record.addressesConfirmed)
                || (record.state
                == PairingSessionRecord.DurableState
                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                && record.localRole
                == NrLinkBluetoothPrelude.LocalRole.RESPONDER
                && !record.addressesConfirmed)) {
            throw new IllegalArgumentException(
                    "Pairing role/address authority is inconsistent");
        }
    }

    static void validateC547(
            String label,
            byte[] value) {
        if (value == null) {
            return;
        }
        if (value.length
                != AppleLeScOobData.SERIALIZED_LENGTH) {
            throw new IllegalArgumentException(
                    label + " has an invalid length");
        }
        AppleLeScOobData parsed = null;
        try {
            parsed = AppleLeScOobData.parse(value);
        } finally {
            if (parsed != null) {
                parsed.destroy();
            }
        }
    }

    static void requireModernPublicKeys(
            String label,
            byte[] payload) {
        if (payload == null
                || payload.length
                != AppleNetworkRelayPairingMaterial
                        .MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH
                || payload[0] != 1
                || payload[1] != 0
                || payload[2] != 32
                || payload[35] != 2
                || payload[36] != 0
                || payload[37] != 32) {
            throw new IllegalArgumentException(
                    label + " public keys have an invalid layout");
        }
    }

    static void addOptional(
            List<Field> fields,
            int type,
            byte[] value) {
        if (value != null) {
            fields.add(new Field(type, value));
        }
    }

    static void addText(
            List<Field> fields,
            int type,
            String value) {
        if (value == null) {
            return;
        }
        byte[] encoded =
                value.getBytes(StandardCharsets.UTF_8);
        try {
            fields.add(new Field(type, encoded));
        } finally {
            wipe(encoded);
        }
    }

    static byte[] required(
            Map<Integer, byte[]> fields,
            int type) {
        byte[] value = fields.get(type);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Pairing session record lacks a required field");
        }
        return value;
    }

    static String decodeRequiredText(
            Map<Integer, byte[]> fields,
            int type,
            String label) {
        byte[] value = required(fields, type);
        return decodeText(value, label, true);
    }

    static String decodeOptionalText(
            Map<Integer, byte[]> fields,
            int type,
            String label) {
        byte[] value = fields.get(type);
        return value == null
                ? null
                : decodeText(value, label, false);
    }

    static String decodeText(
            byte[] encoded,
            String label,
            boolean required) {
        if (encoded.length > PairingSessionRecord.MAX_TEXT_BYTES
                || (required && encoded.length == 0)) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        try {
            String decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
            validateText(label, decoded, required);
            return decoded;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    "Pairing session " + label
                            + " is not valid UTF-8",
                    error);
        }
    }

    static void validateText(
            String label,
            String value,
            boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(
                        "Pairing session " + label + " is required");
            }
            return;
        }
        byte[] encoded =
                value.getBytes(StandardCharsets.UTF_8);
        try {
            if ((required && encoded.length == 0)
                    || encoded.length > PairingSessionRecord.MAX_TEXT_BYTES
                    || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Pairing session " + label + " is invalid");
            }
        } finally {
            wipe(encoded);
        }
    }

    static void validateCanonicalUuid(
            String label,
            String value,
            boolean required) {
        validateText(
                label,
                value,
                required);
        if (value == null) {
            return;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Pairing session " + label
                            + " is not a UUID",
                    error);
        }
        if (value.length() != 36
                || !parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(
                    "Pairing session " + label
                            + " is not canonical");
        }
    }

    static int parseU16(
            byte[] value,
            String label) {
        if (value.length != 2) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return ((value[0] & 0xff) << 8)
                | (value[1] & 0xff);
    }

    static int parseU8(
            byte[] value,
            String label) {
        if (value.length != 1) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return value[0] & 0xff;
    }

    static boolean parseBoolean(
            byte[] value,
            String label) {
        int parsed = parseU8(value, label);
        if (parsed > 1) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return parsed != 0;
    }

    static byte[] u16(int value) {
        return new byte[]{
                (byte) (value >>> 8),
                (byte) value
        };
    }

    static boolean isKnownField(int type) {
        return type == PairingSessionRecord.FIELD_GENERATION_UUID
                || type == PairingSessionRecord.FIELD_BLUETOOTH_BOND
                || type == PairingSessionRecord.FIELD_PENDING_LOCAL_C547
                || type == PairingSessionRecord.FIELD_PENDING_REMOTE_C547
                || type == PairingSessionRecord.FIELD_PENDING_IDS_AUTH_DATA
                || type == PairingSessionRecord.FIELD_LOCAL_NETWORK_MATERIAL
                || type == PairingSessionRecord.FIELD_REMOTE_IDENTITY
                || type == PairingSessionRecord.FIELD_REMOTE_CLASS_D
                || type == PairingSessionRecord.FIELD_REMOTE_CLASS_C
                || type == PairingSessionRecord.FIELD_INITIATOR_CLASS_D_ADDRESS
                || type == PairingSessionRecord.FIELD_RESPONDER_CLASS_D_ADDRESS
                || type == PairingSessionRecord.FIELD_INITIATOR_CLASS_C_ADDRESS
                || type == PairingSessionRecord.FIELD_RESPONDER_CLASS_C_ADDRESS
                || type == PairingSessionRecord.FIELD_NETWORK_RELAY_VERSION
                || type == PairingSessionRecord.FIELD_DEVICE_TYPE
                || type == PairingSessionRecord.FIELD_ALWAYS_ON_WIFI
                || type == PairingSessionRecord.FIELD_DEVICE_NAME
                || type == PairingSessionRecord.FIELD_BUILD_VERSION
                || type == PairingSessionRecord.FIELD_IDS_DEVICE_ID
                || type == PairingSessionRecord.FIELD_PRODUCT_TYPE
                || type == PairingSessionRecord.FIELD_BOARD_ID
                || type == PairingSessionRecord.FIELD_BLUETOOTH_CB_UUID
                || type == PairingSessionRecord.FIELD_NR_UUID
                || type == PairingSessionRecord.FIELD_REMOTE_INSTANCE_UUID
                || type == PairingSessionRecord.FIELD_LOCAL_IDS_DEVICE_UUID
                || type == PairingSessionRecord.FIELD_PEER_SPS_METADATA
                || type == PairingSessionRecord.FIELD_OBSERVED_PEER_MAXIMUM || type == PairingSessionRecord.FIELD_OBSERVED_PEER_BUILD;
    }

    static int roleCode(
            NrLinkBluetoothPrelude.LocalRole role) {
        if (role == null) {
            return 0;
        }
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? 1
                : 2;
    }

    static NrLinkBluetoothPrelude.LocalRole roleFromCode(
            int code) {
        return switch (code) {
            case 0 -> null;
            case 1 -> NrLinkBluetoothPrelude.LocalRole.INITIATOR;
            case 2 -> NrLinkBluetoothPrelude.LocalRole.RESPONDER;
            default -> throw new IllegalArgumentException(
                    "Pairing session IKE role is invalid");
        };
    }

    static byte[] sha256(
            byte[] bytes,
            int offset,
            int length) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    impossible);
        }
    }

    static byte[] cloneOrNull(
            byte[] value) {
        return value == null ? null : value.clone();
    }

    static void requireLength(
            String label,
            byte[] value,
            int expected) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(
                    label + " must be " + expected + " bytes");
        }
    }

    static void requireNonzero(
            String label,
            byte[] value) {
        int combined = 0;
        for (byte item : value) {
            combined |= item & 0xff;
        }
        if (combined == 0) {
            throw new IllegalArgumentException(
                    label + " must not be all zero");
        }
    }

    static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class Field {
        final int type;
        final byte[] value;

        Field(int type, byte[] value) {
            this.type = type;
            this.value = value.clone();
        }

        void destroy() {
            wipe(value);
        }
    }
}
