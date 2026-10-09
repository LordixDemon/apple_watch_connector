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

/**
 * Versioned plaintext owner for one Apple Watch pairing generation.
 *
 * <p>The serialized form must be sealed before persistence. It contains
 * long-lived NetworkRelay private keys and narrowly scoped pending
 * authentication material, so this class deliberately has no textual
 * formatter. All byte-array accessors return defensive copies.</p>
 */
final class PairingSessionRecord {
    static final int FORMAT_VERSION = 2;
    static final int MAX_SERIALIZED_LENGTH = 16 * 1024;

    private static final byte[] MAGIC =
            new byte[]{'A', 'W', 'P', '2'};
    private static final int HEADER_LENGTH = 26;
    private static final int DIGEST_LENGTH = 32;

    private static final int FLAG_MODERN_PAIRING = 1;
    private static final int FLAG_ADDRESSES_CONFIRMED = 1 << 1;
    private static final int FLAG_IDS_AUTH_CONSUMED = 1 << 2;
    private static final int FLAG_IS_PAIRED_COMMIT_INTENT = 1 << 3;
    private static final int FLAG_OBSERVED_SETUP_EVIDENCE = 1 << 4;
    private static final int FLAG_IDS_DEVICE_INFO_EXCHANGED = 1 << 5;
    private static final int KNOWN_FLAGS =
            FLAG_MODERN_PAIRING
                    | FLAG_ADDRESSES_CONFIRMED
                    | FLAG_IDS_AUTH_CONSUMED
                    | FLAG_IS_PAIRED_COMMIT_INTENT
                    | FLAG_OBSERVED_SETUP_EVIDENCE
                    | FLAG_IDS_DEVICE_INFO_EXCHANGED;

    private static final int FIELD_GENERATION_UUID = 1;
    private static final int FIELD_BLUETOOTH_BOND = 2;
    private static final int FIELD_PENDING_LOCAL_C547 = 3;
    private static final int FIELD_PENDING_REMOTE_C547 = 4;
    private static final int FIELD_PENDING_IDS_AUTH_DATA = 5;
    private static final int FIELD_LOCAL_NETWORK_MATERIAL = 10;
    private static final int FIELD_REMOTE_IDENTITY = 11;
    private static final int FIELD_REMOTE_CLASS_D = 12;
    private static final int FIELD_REMOTE_CLASS_C = 13;
    private static final int FIELD_INITIATOR_CLASS_D_ADDRESS = 20;
    private static final int FIELD_RESPONDER_CLASS_D_ADDRESS = 21;
    private static final int FIELD_INITIATOR_CLASS_C_ADDRESS = 22;
    private static final int FIELD_RESPONDER_CLASS_C_ADDRESS = 23;
    private static final int FIELD_NETWORK_RELAY_VERSION = 30;
    private static final int FIELD_DEVICE_TYPE = 31;
    private static final int FIELD_ALWAYS_ON_WIFI = 32;
    private static final int FIELD_DEVICE_NAME = 33;
    private static final int FIELD_BUILD_VERSION = 34;
    private static final int FIELD_IDS_DEVICE_ID = 35;
    private static final int FIELD_PRODUCT_TYPE = 36;
    private static final int FIELD_BOARD_ID = 37;
    private static final int FIELD_BLUETOOTH_CB_UUID = 38;
    private static final int FIELD_NR_UUID = 39;
    private static final int FIELD_REMOTE_INSTANCE_UUID = 40;
    private static final int FIELD_LOCAL_IDS_DEVICE_UUID = 41;
    private static final int FIELD_PEER_SPS_METADATA = 42;

    private static final int MAX_TEXT_BYTES = 1024;

    enum DurableState {
        PAIRING_MATERIAL_PERSISTED(1),
        SMP_BONDED_RAW(2),
        STOCK_BOND_IMPORTED(3),
        STOCK_ENCRYPTED_RECONNECT(4),
        NETWORK_RELAY_PRELUDE_NEGOTIATED(5),
        CLASS_D_ESTABLISHED(6),
        CLASS_C_ESTABLISHED(7),
        IDS_CONTROL_READY(8),
        IDS_DATA_READY(9),
        INITIAL_PROPERTIES_RECEIVED(10),
        READY_TO_COMMIT_IS_PAIRED(11),
        IS_PAIRED_COMMITTED(12),
        ACTIVATION_CONFIRMED(13),
        IS_SETUP_CONFIRMED(14),
        PAIRED_SYNC_COMPLETE(15),
        SETUP_COMPLETE(16),
        CLOCK_VISIBLE_CONFIRMED(17),
        OPERATIONAL_HEALTH_CONFIRMED(18);

        private final int wireValue;

        DurableState(int wireValue) {
            this.wireValue = wireValue;
        }

        int wireValue() {
            return wireValue;
        }

        static DurableState fromWireValue(int value) {
            for (DurableState state : values()) {
                if (state.wireValue == value) {
                    return state;
                }
            }
            throw new IllegalArgumentException(
                    "Pairing session durable state is unknown");
        }
    }

    private final byte[] generationUuid;
    private final DurableState state;
    private final long transitionCounter;
    private final boolean modernPairing;
    private final boolean addressesConfirmed;
    private final boolean idsAuthConsumed;
    private final boolean isPairedCommitIntentPersisted;
    private final NrLinkBluetoothPrelude.LocalRole localRole;

    private final byte[] bluetoothBond;
    private final byte[] pendingLocalC547;
    private final byte[] pendingRemoteC547;
    private final byte[] pendingIdsAuthData;

    private final byte[] localNetworkMaterial;
    private final byte[] remoteIdentity;
    private final byte[] remoteClassD;
    private final byte[] remoteClassC;

    private final byte[] initiatorClassDAddress;
    private final byte[] responderClassDAddress;
    private final byte[] initiatorClassCAddress;
    private final byte[] responderClassCAddress;

    private final int networkRelayVersion;
    private final int deviceType;
    private final boolean alwaysOnWifi;
    private final String deviceName;
    private final String buildVersion;
    private final String idsDeviceId;
    private final String productType;
    private final String boardId;
    private final String bluetoothCbUuid;
    private final String nrUuid;
    /**
     * Android's installation-stable IDSCurrentDevice equivalent. This is
     * advertised as Hello.deviceUniqueID and, when available, as BDDF. It is
     * independent from the local CoreBluetooth and NetworkRelay database
     * identifiers.
     */
    private final String localIdsDeviceUuid;
    /**
     * Reserved only for rejecting stale version-2 records. An IDS remote
     * instance UUID is generated per live connection and must remain in
     * {@link IdsServiceMapState}, not in durable pairing state.
     */
    private final String remoteInstanceUuid;
    private boolean destroyed;
    private boolean observedSetupEvidence;
    private boolean idsDeviceInfoExchanged;
    private byte[] peerSpsMetadata;

    private PairingSessionRecord(
            byte[] generationUuid,
            DurableState state,
            long transitionCounter,
            boolean modernPairing,
            boolean addressesConfirmed,
            boolean idsAuthConsumed,
            boolean isPairedCommitIntentPersisted,
            NrLinkBluetoothPrelude.LocalRole localRole,
            byte[] bluetoothBond,
            byte[] pendingLocalC547,
            byte[] pendingRemoteC547,
            byte[] pendingIdsAuthData,
            byte[] localNetworkMaterial,
            byte[] remoteIdentity,
            byte[] remoteClassD,
            byte[] remoteClassC,
            byte[] initiatorClassDAddress,
            byte[] responderClassDAddress,
            byte[] initiatorClassCAddress,
            byte[] responderClassCAddress,
            int networkRelayVersion,
            int deviceType,
            boolean alwaysOnWifi,
            String deviceName,
            String buildVersion,
            String idsDeviceId,
            String productType,
            String boardId,
            String bluetoothCbUuid,
            String nrUuid,
            String localIdsDeviceUuid,
            String remoteInstanceUuid) {
        this.generationUuid = cloneOrNull(generationUuid);
        this.state = state;
        this.transitionCounter = transitionCounter;
        this.modernPairing = modernPairing;
        this.addressesConfirmed = addressesConfirmed;
        this.idsAuthConsumed = idsAuthConsumed;
        this.isPairedCommitIntentPersisted =
                isPairedCommitIntentPersisted;
        this.localRole = localRole;
        this.bluetoothBond = cloneOrNull(bluetoothBond);
        this.pendingLocalC547 = cloneOrNull(pendingLocalC547);
        this.pendingRemoteC547 = cloneOrNull(pendingRemoteC547);
        this.pendingIdsAuthData =
                cloneOrNull(pendingIdsAuthData);
        this.localNetworkMaterial =
                cloneOrNull(localNetworkMaterial);
        this.remoteIdentity = cloneOrNull(remoteIdentity);
        this.remoteClassD = cloneOrNull(remoteClassD);
        this.remoteClassC = cloneOrNull(remoteClassC);
        this.initiatorClassDAddress =
                cloneOrNull(initiatorClassDAddress);
        this.responderClassDAddress =
                cloneOrNull(responderClassDAddress);
        this.initiatorClassCAddress =
                cloneOrNull(initiatorClassCAddress);
        this.responderClassCAddress =
                cloneOrNull(responderClassCAddress);
        this.networkRelayVersion = networkRelayVersion;
        this.deviceType = deviceType;
        this.alwaysOnWifi = alwaysOnWifi;
        this.deviceName = deviceName;
        this.buildVersion = buildVersion;
        this.idsDeviceId = idsDeviceId;
        this.productType = productType;
        this.boardId = boardId;
        this.bluetoothCbUuid = bluetoothCbUuid;
        this.nrUuid = nrUuid;
        this.localIdsDeviceUuid = localIdsDeviceUuid;
        this.remoteInstanceUuid = remoteInstanceUuid;
        validate();
    }

    static PairingSessionRecord createPreSmp(
            byte[] generationUuid,
            byte[] pendingIdsAuthData,
            BleSecureConnectionsCrypto.LocalOobMaterial localOob,
            AppleNetworkRelayPairingMaterial localMaterial,
            AppleNetworkRelayInnerAddresses provisionalAddresses,
            ApplePairingNotifyPayloads.PeerBatch peer,
            String productType,
            String boardId,
            String bluetoothCbUuid,
            String nrUuid,
            String localIdsDeviceUuid) {
        if (localOob == null
                || localMaterial == null
                || provisionalAddresses == null
                || peer == null) {
            throw new IllegalArgumentException(
                    "Complete pairing material is required");
        }
        validateCanonicalUuid(
                "local IDS device UUID",
                localIdsDeviceUuid,
                true);
        byte[] localC547 = null;
        byte[] remoteC547 = null;
        byte[] localSnapshot = null;
        byte[] remoteIdentity = null;
        byte[] remoteClassD = null;
        byte[] remoteClassC = null;
        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            localC547 = localOob.appleOobData();
            remoteC547 = peer.appleOobData();
            localSnapshot = localMaterial.privateSnapshot();
            remoteIdentity = peer.identity();
            remoteClassD = peer.classDPublicKeys();
            remoteClassC = peer.classCPublicKeys();
            initiatorD =
                    provisionalAddresses.initiatorClassD();
            responderD =
                    provisionalAddresses.responderClassD();
            initiatorC =
                    provisionalAddresses.initiatorClassC();
            responderC =
                    provisionalAddresses.responderClassC();
            return new PairingSessionRecord(
                    generationUuid,
                    DurableState.PAIRING_MATERIAL_PERSISTED,
                    1,
                    true,
                    false,
                    false,
                    false,
                    null,
                    null,
                    localC547,
                    remoteC547,
                    pendingIdsAuthData,
                    localSnapshot,
                    remoteIdentity,
                    remoteClassD,
                    remoteClassC,
                    initiatorD,
                    responderD,
                    initiatorC,
                    responderC,
                    peer.protocolVersion,
                    peer.deviceType,
                    peer.alwaysOnWifi,
                    peer.deviceName,
                    peer.buildVersion,
                    peer.idsDeviceId,
                    productType,
                    boardId,
                    bluetoothCbUuid,
                    nrUuid,
                    localIdsDeviceUuid,
                    null);
        } finally {
            wipe(localC547);
            wipe(remoteC547);
            wipe(localSnapshot);
            wipe(remoteIdentity);
            wipe(remoteClassD);
            wipe(remoteClassC);
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    static PairingSessionRecord parse(byte[] serialized) {
        if (serialized == null
                || serialized.length
                < HEADER_LENGTH + DIGEST_LENGTH
                || serialized.length > MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing session record size is invalid");
        }
        int digestOffset =
                serialized.length - DIGEST_LENGTH;
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
        for (byte expected : MAGIC) {
            if (header.get() != expected) {
                throw new IllegalArgumentException(
                        "Pairing session record magic is invalid");
            }
        }
        int version = header.getShort() & 0xffff;
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Pairing session record version is unsupported");
        }
        int headerLength = header.getShort() & 0xffff;
        if (headerLength != HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing session record header is invalid");
        }
        DurableState state =
                DurableState.fromWireValue(
                        header.getShort() & 0xffff);
        int roleCode = header.get() & 0xff;
        NrLinkBluetoothPrelude.LocalRole role =
                roleFromCode(roleCode);
        int flags = header.get() & 0xff;
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "Pairing session record flags are invalid");
        }
        long transitionCounter = header.getLong();
        int fieldCount = header.getShort() & 0xffff;
        int payloadLength = header.getInt();
        if (payloadLength < 0
                || HEADER_LENGTH + payloadLength
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
                    required(fields, FIELD_GENERATION_UUID),
                    state,
                    transitionCounter,
                    (flags & FLAG_MODERN_PAIRING) != 0,
                    (flags & FLAG_ADDRESSES_CONFIRMED) != 0,
                    (flags & FLAG_IDS_AUTH_CONSUMED) != 0,
                    (flags & FLAG_IS_PAIRED_COMMIT_INTENT) != 0,
                    role,
                    fields.get(FIELD_BLUETOOTH_BOND),
                    fields.get(FIELD_PENDING_LOCAL_C547),
                    fields.get(FIELD_PENDING_REMOTE_C547),
                    fields.get(FIELD_PENDING_IDS_AUTH_DATA),
                    required(
                            fields,
                            FIELD_LOCAL_NETWORK_MATERIAL),
                    required(fields, FIELD_REMOTE_IDENTITY),
                    required(fields, FIELD_REMOTE_CLASS_D),
                    required(fields, FIELD_REMOTE_CLASS_C),
                    required(
                            fields,
                            FIELD_INITIATOR_CLASS_D_ADDRESS),
                    required(
                            fields,
                            FIELD_RESPONDER_CLASS_D_ADDRESS),
                    required(
                            fields,
                            FIELD_INITIATOR_CLASS_C_ADDRESS),
                    required(
                            fields,
                            FIELD_RESPONDER_CLASS_C_ADDRESS),
                    parseU16(
                            required(
                                    fields,
                                    FIELD_NETWORK_RELAY_VERSION),
                            "NetworkRelay version"),
                    parseU8(
                            required(
                                    fields,
                                    FIELD_DEVICE_TYPE),
                            "device type"),
                    parseBoolean(
                            required(
                                    fields,
                                    FIELD_ALWAYS_ON_WIFI),
                            "always-on Wi-Fi"),
                    decodeRequiredText(
                            fields,
                            FIELD_DEVICE_NAME,
                            "device name"),
                    decodeRequiredText(
                            fields,
                            FIELD_BUILD_VERSION,
                            "build version"),
                    decodeOptionalText(
                            fields,
                            FIELD_IDS_DEVICE_ID,
                            "IDS device ID"),
                    decodeOptionalText(
                            fields,
                            FIELD_PRODUCT_TYPE,
                            "product type"),
                    decodeOptionalText(
                            fields,
                            FIELD_BOARD_ID,
                            "board ID"),
                    decodeOptionalText(
                            fields,
                            FIELD_BLUETOOTH_CB_UUID,
                            "Bluetooth CBUUID"),
                    decodeOptionalText(
                            fields,
                            FIELD_NR_UUID,
                            "NRUUID"),
                    decodeOptionalText(
                            fields,
                            FIELD_LOCAL_IDS_DEVICE_UUID,
                            "local IDS device UUID"),
                    decodeOptionalText(
                            fields,
                            FIELD_REMOTE_INSTANCE_UUID,
                            "remote instance UUID"));
            try {
                decoded.observedSetupEvidence = (flags & FLAG_OBSERVED_SETUP_EVIDENCE) != 0;
                decoded.idsDeviceInfoExchanged = (flags & FLAG_IDS_DEVICE_INFO_EXCHANGED) != 0;
                decoded.peerSpsMetadata = cloneOrNull(fields.get(FIELD_PEER_SPS_METADATA));
                decoded.validate();
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

    byte[] serialize() {
        requireLive();
        validate();
        List<Field> fields = buildFields();
        try {
            int payloadLength = 0;
            for (Field field : fields) {
                payloadLength += 6 + field.value.length;
            }
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(
                            HEADER_LENGTH
                                    + payloadLength
                                    + DIGEST_LENGTH);
            try (DataOutputStream output =
                         new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeShort(FORMAT_VERSION);
                output.writeShort(HEADER_LENGTH);
                output.writeShort(state.wireValue());
                output.writeByte(roleCode(localRole));
                output.writeByte(flags());
                output.writeLong(transitionCounter);
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
            if (serialized.length > MAX_SERIALIZED_LENGTH) {
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

    PairingSessionRecord withBluetoothBond(
            byte[] bondRecord) {
        requireLive();
        if (state
                != DurableState.PAIRING_MATERIAL_PERSISTED) {
            throw new IllegalStateException(
                    "Bluetooth bond may only commit after "
                            + "pairing material");
        }
        BluetoothBondSecretRecord.validateSerialized(
                bondRecord);
        return copy(
                DurableState.SMP_BONDED_RAW,
                transitionCounter + 1,
                localRole,
                false,
                idsAuthConsumed,
                bondRecord,
                null,
                null,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress);
    }

    PairingSessionRecord markIdsAuthenticationAccepted() {
        requireLive();
        if (state
                != DurableState.CLASS_C_ESTABLISHED
                || !addressesConfirmed
                || idsAuthConsumed
                || pendingIdsAuthData == null) {
            throw new IllegalStateException(
                    "IDS authentication may only commit after the "
                            + "ordinary Class-D/Class-C barrier");
        }
        return copy(
                state,
                transitionCounter + 1,
                localRole,
                addressesConfirmed,
                true,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                null,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress);
    }

    /**
     * Commits the role elected by the normal NRLinkBluetooth prelude.
     *
     * <p>The initial pairing quartet assumes that the Watch will be the
     * ordinary-IKE initiator. It is immediately authoritative when this
     * companion is elected responder. If this companion is elected
     * initiator, the quartet remains provisional until the Watch responder
     * returns its authenticated ordinary-IKE address notifies.</p>
     */
    PairingSessionRecord withPreludeNegotiated(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireLive();
        boolean validSource =
                state == DurableState.SMP_BONDED_RAW
                        || state == DurableState.STOCK_BOND_IMPORTED
                        || state == DurableState.STOCK_ENCRYPTED_RECONNECT
                        || state.wireValue() >= DurableState.NETWORK_RELAY_PRELUDE_NEGOTIATED.wireValue();
        if (bluetoothBond == null
                || !validSource
                || role == null) {
            throw new IllegalStateException(
                    "Prelude negotiation prerequisites are incomplete");
        }
        DurableState targetState =
                state.wireValue() >= DurableState.NETWORK_RELAY_PRELUDE_NEGOTIATED.wireValue()
                        ? state
                        : DurableState.NETWORK_RELAY_PRELUDE_NEGOTIATED;
        // A confirmed quartet is projected through this role. A later
        // prelude must not retarget it: that swaps the phone and the Watch.
        NrLinkBluetoothPrelude.LocalRole committedRole =
                addressesConfirmed && localRole != null
                        ? localRole
                        : role;
        return copy(
                targetState,
                transitionCounter + 1,
                committedRole,
                role
                        == NrLinkBluetoothPrelude
                        .LocalRole.RESPONDER || addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress);
    }

    /**
     * Projects the confirmed quartet through a different role and keeps
     * that role. A responder projection that no longer matches the setup
     * socket is pinned back once; later preludes do not flip it again.
     */
    PairingSessionRecord pinLocalRole(
            NrLinkBluetoothPrelude.LocalRole role) {
        requireLive();
        if (role == null) {
            throw new IllegalArgumentException(
                    "Pinned prelude role is required");
        }
        if (!addressesConfirmed
                || localRole == null) {
            throw new IllegalStateException(
                    "Prelude role can be pinned only after the quartet is confirmed");
        }
        if (role == localRole) {
            return this;
        }
        return copy(
                state,
                transitionCounter + 1,
                role,
                addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress);
    }

    /**
     * Replaces the persisted initiator/responder quartet with the one the
     * Watch just assigned. Role pinning kept the old slot order, so the
     * saved pair is the swap of the live Child SA.
     */
    PairingSessionRecord withReconciledQuartet(
            AppleNetworkRelayInnerAddresses authoritative) {
        requireLive();
        if (authoritative == null || !addressesConfirmed || localRole == null) {
            throw new IllegalArgumentException(
                    "A confirmed quartet is required to reconcile addresses");
        }
        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            initiatorD = authoritative.initiatorClassD();
            responderD = authoritative.responderClassD();
            initiatorC = authoritative.initiatorClassC();
            responderC = authoritative.responderClassC();
            return copy(
                    state,
                    transitionCounter + 1,
                    localRole,
                    true,
                    idsAuthConsumed,
                    bluetoothBond,
                    pendingLocalC547,
                    pendingRemoteC547,
                    pendingIdsAuthData,
                    initiatorD,
                    responderD,
                    initiatorC,
                    responderC);
        } finally {
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    /**
     * Commits an authenticated ordinary Class D IKE/Child SA.
     *
     * <p>A local initiator must supply the complete quartet returned by the
     * remote responder. A local responder owns the already persisted
     * provisional quartet and must not accept a peer replacement.</p>
     */
    PairingSessionRecord withClassDEstablished(
            AppleNetworkRelayInnerAddresses
                    responderAuthoritativeAddresses) {
        requireLive();
        if (state.wireValue() >= DurableState.CLASS_D_ESTABLISHED.wireValue()) {
            return this;
        }
        if (state
                != DurableState
                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                || localRole == null) {
            throw new IllegalStateException(
                    "Class D establishment requires a negotiated prelude");
        }
        boolean localInitiator =
                localRole
                        == NrLinkBluetoothPrelude
                        .LocalRole.INITIATOR;
        if (localInitiator
                && responderAuthoritativeAddresses == null
                && !addressesConfirmed) {
            throw new IllegalArgumentException(
                    "An IKE initiator requires the responder "
                            + "address quartet");
        }
        if (!localInitiator
                && responderAuthoritativeAddresses != null) {
            throw new IllegalArgumentException(
                    "An IKE responder must not accept a peer "
                            + "address quartet");
        }

        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            if (responderAuthoritativeAddresses == null) {
                initiatorD = initiatorClassDAddress.clone();
                responderD = responderClassDAddress.clone();
                initiatorC = initiatorClassCAddress.clone();
                responderC = responderClassCAddress.clone();
            } else {
                initiatorD =
                        responderAuthoritativeAddresses
                                .initiatorClassD();
                responderD =
                        responderAuthoritativeAddresses
                                .responderClassD();
                initiatorC =
                        responderAuthoritativeAddresses
                                .initiatorClassC();
                responderC =
                        responderAuthoritativeAddresses
                                .responderClassC();
            }
            return copy(
                    DurableState.CLASS_D_ESTABLISHED,
                    transitionCounter + 1,
                    localRole,
                    true,
                    idsAuthConsumed,
                    bluetoothBond,
                    pendingLocalC547,
                    pendingRemoteC547,
                    pendingIdsAuthData,
                    initiatorD,
                    responderD,
                    initiatorC,
                    responderC);
        } finally {
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    PairingSessionRecord advanceTo(
            DurableState next) {
        requireLive();
        if (next == null
                || next.wireValue()
                != state.wireValue() + 1
                || next
                == DurableState.SMP_BONDED_RAW
                || next
                == DurableState
                        .NETWORK_RELAY_PRELUDE_NEGOTIATED
                || next
                == DurableState.CLASS_D_ESTABLISHED
                || next
                == DurableState.IS_PAIRED_COMMITTED) {
            throw new IllegalArgumentException(
                    "Pairing session transition is invalid");
        }
        if (next.wireValue()
                >= DurableState
                        .NETWORK_RELAY_PRELUDE_NEGOTIATED
                        .wireValue()
                && !addressesConfirmed) {
            throw new IllegalStateException(
                    "NetworkRelay addresses are not authoritative");
        }
        if (next.wireValue()
                >= DurableState.IDS_CONTROL_READY.wireValue()
                && !idsAuthConsumed) {
            throw new IllegalStateException(
                    "IDS control cannot become durable before local "
                            + "paired-device registration");
        }
        return copy(
                next,
                transitionCounter + 1,
                localRole,
                addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress);
    }

    PairingSessionRecord confirmIsPairedCommit(
            boolean explicitLocalConfirmation) {
        requireLive();
        if (!explicitLocalConfirmation
                || state
                != DurableState.READY_TO_COMMIT_IS_PAIRED
                || !isPairedCommitIntentPersisted) {
            throw new IllegalStateException(
                    "IS_PAIRED commit requires a persisted explicit intent");
        }
        return copyWithCommitIntent(
                DurableState.IS_PAIRED_COMMITTED,
                transitionCounter + 1,
                localRole,
                addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress,
                false);
    }

    /**
     * Writes the local intent before atomically finalizing the phone-local
     * IsPaired state. A restored record carrying this flag requires another
     * explicit local authorization; no Watch-side IsPaired readback exists.
     */
    PairingSessionRecord prepareIsPairedCommit(
            boolean explicitLocalConfirmation) {
        requireLive();
        if (!explicitLocalConfirmation
                || state
                != DurableState.READY_TO_COMMIT_IS_PAIRED
                || isPairedCommitIntentPersisted) {
            throw new IllegalStateException(
                    "IS_PAIRED write-ahead intent requires an explicit gate");
        }
        return copyWithCommitIntent(
                state,
                transitionCounter + 1,
                localRole,
                addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress,
                true);
    }

    /**
     * Cancels an unresolved local write-ahead intent after an explicit local
     * decision. This changes no Watch or transport state.
     */
    PairingSessionRecord clearIsPairedCommitIntentForExplicitRetry(
            boolean explicitRetryAuthorization) {
        requireLive();
        if (!explicitRetryAuthorization
                || state
                != DurableState.READY_TO_COMMIT_IS_PAIRED
                || !isPairedCommitIntentPersisted) {
            throw new IllegalStateException(
                    "IS_PAIRED intent cancellation requires an explicit gate");
        }
        return copyWithCommitIntent(
                state,
                transitionCounter + 1,
                localRole,
                addressesConfirmed,
                idsAuthConsumed,
                bluetoothBond,
                pendingLocalC547,
                pendingRemoteC547,
                pendingIdsAuthData,
                initiatorClassDAddress,
                responderClassDAddress,
                initiatorClassCAddress,
                responderClassCAddress,
                false);
    }

    byte[] generationUuid() {
        requireLive();
        return generationUuid.clone();
    }

    String bluetoothCbUuid() {
        requireLive();
        return bluetoothCbUuid;
    }

    String nrUuid() {
        requireLive();
        return nrUuid;
    }

    String localIdsDeviceUuid() {
        requireLive();
        return localIdsDeviceUuid;
    }

    String peerIdsDeviceId() { requireLive(); return idsDeviceId; }
    String peerBuildVersion() { requireLive(); return buildVersion; }
    String peerProductType() { requireLive(); return productType; }

    String peerNetworkRelayIdentifier() {
        requireLive();
        if (remoteIdentity == null || remoteIdentity.length < 16) return null;
        ByteBuffer identity = ByteBuffer.wrap(remoteIdentity);
        return new UUID(identity.getLong(), identity.getLong()).toString();
    }
    boolean hasExchangedIdsDeviceInfo() { requireLive(); return idsDeviceInfoExchanged; }

    byte[] peerSpsMetadata() { requireLive(); return cloneOrNull(peerSpsMetadata); }

    PairingSessionRecord withPeerSpsMetadata(byte[] encoded) {
        requireLive();
        if (state.wireValue() < DurableState.CLASS_C_ESTABLISHED.wireValue()) {
            throw new IllegalStateException("SPS metadata precedes authenticated peer registration");
        }
        try (IdsSpsCompanionInfo info = IdsSpsCompanionInfo.parse(encoded)) {
            PairingSessionRecord next = copy(state, Math.addExact(transitionCounter, 1), localRole,
                    addressesConfirmed, idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547,
                    pendingIdsAuthData, initiatorClassDAddress, responderClassDAddress,
                    initiatorClassCAddress, responderClassCAddress);
            wipe(next.peerSpsMetadata);
            next.peerSpsMetadata = info.serialize();
            next.validate();
            return next;
        }
    }

    PairingSessionRecord markIdsDeviceInfoExchanged() {
        requireLive();
        if (idsDeviceInfoExchanged || state.wireValue() < DurableState.IDS_CONTROL_READY.wireValue()) {
            throw new IllegalStateException("IDS device-info checkpoint is out of order");
        }
        PairingSessionRecord next = copy(state, Math.addExact(transitionCounter, 1), localRole,
                addressesConfirmed, idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547,
                pendingIdsAuthData, initiatorClassDAddress, responderClassDAddress, initiatorClassCAddress, responderClassCAddress);
        next.idsDeviceInfoExchanged = true;
        return next;
    }

    DurableState state() {
        requireLive();
        return state;
    }

    long transitionCounter() {
        requireLive();
        return transitionCounter;
    }

    NrLinkBluetoothPrelude.LocalRole localRole() {
        requireLive();
        return localRole;
    }

    boolean addressesConfirmed() {
        requireLive();
        return addressesConfirmed;
    }

    byte[] copyConfirmedLocalAddress(boolean classD) {
        return copyConfirmedAddress(classD, true);
    }

    byte[] copyConfirmedRemoteAddress(boolean classD) {
        return copyConfirmedAddress(classD, false);
    }

    private byte[] copyConfirmedAddress(boolean classD, boolean local) {
        requireLive();
        if (localRole == null) {
            throw new IllegalStateException("Confirmed inner address has no role");
        }
        boolean initiatorSide = localRole == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? local
                : !local;
        byte[] source = classD
                ? (initiatorSide ? initiatorClassDAddress : responderClassDAddress)
                : (initiatorSide ? initiatorClassCAddress : responderClassCAddress);
        if (source == null) {
            throw new IllegalStateException("Confirmed inner address is absent");
        }
        return source.clone();
    }

    boolean hasObservedSetupEvidence() {
        requireLive();
        return observedSetupEvidence;
    }

    /** Older builds advanced these states without Watch responses. Preserve the
     * irreversible paired commit and keys, then re-observe the post-commit steps. */
    PairingSessionRecord revalidateLegacySetupEvidence() {
        requireLive();
        if (observedSetupEvidence || state.wireValue() < DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            throw new IllegalStateException("Pairing record does not need legacy setup revalidation");
        }
        return copyWithCommitIntent(DurableState.IS_PAIRED_COMMITTED,
                Math.addExact(transitionCounter, 1), localRole, addressesConfirmed,
                idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547,
                pendingIdsAuthData, initiatorClassDAddress, responderClassDAddress,
                initiatorClassCAddress, responderClassCAddress, false);
    }

    /** Owner-driven activation redrive: the Watch was factory-reset after a
     * synthetic (owner-forced) ACTIVATION_CONFIRMED checkpoint, so it sits
     * unactivated while we believe activation is done and never re-assert
     * CanBeginActivation. Keep the irreversible paired commit, bond and keys;
     * drop only the setup checkpoints so the activation phase runs again and
     * fresh Watch evidence re-advances the record. */
    PairingSessionRecord redriveActivationCheckpoint() {
        requireLive();
        if (state.wireValue() < DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            throw new IllegalStateException("Pairing record has no activation checkpoint to redrive");
        }
        return copyWithCommitIntent(DurableState.IS_PAIRED_COMMITTED,
                Math.addExact(transitionCounter, 1), localRole, addressesConfirmed,
                idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547,
                pendingIdsAuthData, initiatorClassDAddress, responderClassDAddress,
                initiatorClassCAddress, responderClassCAddress, false);
    }

    /** Replay initial sync while retaining independently confirmed activation and all pair secrets. */
    PairingSessionRecord redriveInitialSyncCheckpoint() {
        requireLive();
        if (state.wireValue() < DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            throw new IllegalStateException("Pairing record has no confirmed activation");
        }
        return copyWithCommitIntent(DurableState.ACTIVATION_CONFIRMED,
                Math.addExact(transitionCounter, 1), localRole, addressesConfirmed,
                idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547,
                pendingIdsAuthData, initiatorClassDAddress, responderClassDAddress,
                initiatorClassCAddress, responderClassCAddress, false);
    }

    PairingSessionRecord advanceObservedSetupTo(DurableState nextState) {
        requireLive();
        if (nextState.wireValue() < DurableState.ACTIVATION_CONFIRMED.wireValue()
                || (nextState != DurableState.ACTIVATION_CONFIRMED && !observedSetupEvidence)) {
            throw new IllegalStateException("Observed activation must precede subsequent setup evidence");
        }
        PairingSessionRecord next = advanceTo(nextState);
        next.observedSetupEvidence = true;
        return next;
    }

    boolean isPairedCommitIntentPersisted() {
        requireLive();
        return isPairedCommitIntentPersisted;
    }

    boolean hasBluetoothBond() {
        requireLive();
        return bluetoothBond != null;
    }

    boolean hasPendingSmpOob() {
        requireLive();
        return pendingLocalC547 != null
                || pendingRemoteC547 != null;
    }

    boolean hasPendingIdsAuthenticationData() {
        requireLive();
        return pendingIdsAuthData != null;
    }

    boolean idsAuthenticationAccepted() {
        requireLive();
        return idsAuthConsumed;
    }

    byte[] pendingIdsAuthenticationData() {
        requireLive();
        return cloneOrNull(pendingIdsAuthData);
    }

    byte[] bluetoothBond() {
        requireLive();
        return cloneOrNull(bluetoothBond);
    }

    AppleNetworkRelayPairingMaterial restoreLocalMaterial() {
        requireLive();
        return AppleNetworkRelayPairingMaterial
                .restorePrivateSnapshot(
                        localNetworkMaterial);
    }

    OrdinaryIkeAuth.PeerMaterial restoreRemoteClassDPeerMaterial() {
        requireLive();
        return OrdinaryIkeAuth.PeerMaterial
                .fromPairingPayloads(
                        remoteIdentity,
                        remoteClassD);
    }

    OrdinaryIkeAuth.PeerMaterial restoreRemotePeerMaterial() {
        requireLive();
        return OrdinaryIkeAuth.PeerMaterial
                .fromPairingPayloads(
                        remoteIdentity,
                        remoteClassD,
                        remoteClassC);
    }

    AppleNetworkRelayInnerAddresses innerAddresses() {
        requireLive();
        return AppleNetworkRelayInnerAddresses
                .fromAuthoritative(
                        initiatorClassDAddress,
                        responderClassDAddress,
                        initiatorClassCAddress,
                        responderClassCAddress);
    }

    byte[] remoteClassD() {
        requireLive();
        return cloneOrNull(remoteClassD);
    }

    byte[] remoteClassC() {
        requireLive();
        return cloneOrNull(remoteClassC);
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        wipe(generationUuid);
        wipe(bluetoothBond);
        wipe(pendingLocalC547);
        wipe(pendingRemoteC547);
        wipe(pendingIdsAuthData);
        wipe(localNetworkMaterial);
        wipe(remoteIdentity);
        wipe(remoteClassD);
        wipe(remoteClassC);
        wipe(initiatorClassDAddress);
        wipe(responderClassDAddress);
        wipe(initiatorClassCAddress);
        wipe(responderClassCAddress);
        wipe(peerSpsMetadata);
        destroyed = true;
    }

    private PairingSessionRecord copy(
            DurableState newState,
            long newCounter,
            NrLinkBluetoothPrelude.LocalRole newRole,
            boolean newAddressesConfirmed,
            boolean newIdsAuthConsumed,
            byte[] newBluetoothBond,
            byte[] newPendingLocalC547,
            byte[] newPendingRemoteC547,
            byte[] newPendingIdsAuthData,
            byte[] newInitiatorD,
            byte[] newResponderD,
            byte[] newInitiatorC,
            byte[] newResponderC) {
        return copyWithCommitIntent(
                newState,
                newCounter,
                newRole,
                newAddressesConfirmed,
                newIdsAuthConsumed,
                newBluetoothBond,
                newPendingLocalC547,
                newPendingRemoteC547,
                newPendingIdsAuthData,
                newInitiatorD,
                newResponderD,
                newInitiatorC,
                newResponderC,
                isPairedCommitIntentPersisted);
    }

    private PairingSessionRecord copyWithCommitIntent(
            DurableState newState,
            long newCounter,
            NrLinkBluetoothPrelude.LocalRole newRole,
            boolean newAddressesConfirmed,
            boolean newIdsAuthConsumed,
            byte[] newBluetoothBond,
            byte[] newPendingLocalC547,
            byte[] newPendingRemoteC547,
            byte[] newPendingIdsAuthData,
            byte[] newInitiatorD,
            byte[] newResponderD,
            byte[] newInitiatorC,
            byte[] newResponderC,
            boolean newIsPairedCommitIntentPersisted) {
        PairingSessionRecord copied = new PairingSessionRecord(
                generationUuid,
                newState,
                newCounter,
                modernPairing,
                newAddressesConfirmed,
                newIdsAuthConsumed,
                newIsPairedCommitIntentPersisted,
                newRole,
                newBluetoothBond,
                newPendingLocalC547,
                newPendingRemoteC547,
                newPendingIdsAuthData,
                localNetworkMaterial,
                remoteIdentity,
                remoteClassD,
                remoteClassC,
                newInitiatorD,
                newResponderD,
                newInitiatorC,
                newResponderC,
                networkRelayVersion,
                deviceType,
                alwaysOnWifi,
                deviceName,
                buildVersion,
                idsDeviceId,
                productType,
                boardId,
                bluetoothCbUuid,
                nrUuid,
                localIdsDeviceUuid,
                remoteInstanceUuid);
        copied.observedSetupEvidence = observedSetupEvidence
                && newState.wireValue() >= DurableState.ACTIVATION_CONFIRMED.wireValue();
        copied.idsDeviceInfoExchanged = idsDeviceInfoExchanged
                && newState.wireValue() >= DurableState.IDS_CONTROL_READY.wireValue();
        copied.peerSpsMetadata = cloneOrNull(peerSpsMetadata);
        return copied;
    }

    private List<Field> buildFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field(
                FIELD_GENERATION_UUID,
                generationUuid));
        addOptional(
                fields,
                FIELD_BLUETOOTH_BOND,
                bluetoothBond);
        addOptional(
                fields,
                FIELD_PENDING_LOCAL_C547,
                pendingLocalC547);
        addOptional(
                fields,
                FIELD_PENDING_REMOTE_C547,
                pendingRemoteC547);
        addOptional(
                fields,
                FIELD_PENDING_IDS_AUTH_DATA,
                pendingIdsAuthData);
        fields.add(new Field(
                FIELD_LOCAL_NETWORK_MATERIAL,
                localNetworkMaterial));
        fields.add(new Field(
                FIELD_REMOTE_IDENTITY,
                remoteIdentity));
        fields.add(new Field(
                FIELD_REMOTE_CLASS_D,
                remoteClassD));
        fields.add(new Field(
                FIELD_REMOTE_CLASS_C,
                remoteClassC));
        fields.add(new Field(
                FIELD_INITIATOR_CLASS_D_ADDRESS,
                initiatorClassDAddress));
        fields.add(new Field(
                FIELD_RESPONDER_CLASS_D_ADDRESS,
                responderClassDAddress));
        fields.add(new Field(
                FIELD_INITIATOR_CLASS_C_ADDRESS,
                initiatorClassCAddress));
        fields.add(new Field(
                FIELD_RESPONDER_CLASS_C_ADDRESS,
                responderClassCAddress));
        fields.add(new Field(
                FIELD_NETWORK_RELAY_VERSION,
                u16(networkRelayVersion)));
        fields.add(new Field(
                FIELD_DEVICE_TYPE,
                new byte[]{(byte) deviceType}));
        fields.add(new Field(
                FIELD_ALWAYS_ON_WIFI,
                new byte[]{alwaysOnWifi ? (byte) 1 : 0}));
        addText(
                fields,
                FIELD_DEVICE_NAME,
                deviceName);
        addText(
                fields,
                FIELD_BUILD_VERSION,
                buildVersion);
        addText(
                fields,
                FIELD_IDS_DEVICE_ID,
                idsDeviceId);
        addText(
                fields,
                FIELD_PRODUCT_TYPE,
                productType);
        addText(
                fields,
                FIELD_BOARD_ID,
                boardId);
        addText(
                fields,
                FIELD_BLUETOOTH_CB_UUID,
                bluetoothCbUuid);
        addText(
                fields,
                FIELD_NR_UUID,
                nrUuid);
        addText(
                fields,
                FIELD_LOCAL_IDS_DEVICE_UUID,
                localIdsDeviceUuid);
        if (peerSpsMetadata != null) fields.add(new Field(FIELD_PEER_SPS_METADATA, peerSpsMetadata));
        return fields;
    }

    private int flags() {
        int flags = 0;
        if (modernPairing) {
            flags |= FLAG_MODERN_PAIRING;
        }
        if (addressesConfirmed) {
            flags |= FLAG_ADDRESSES_CONFIRMED;
        }
        if (idsAuthConsumed) {
            flags |= FLAG_IDS_AUTH_CONSUMED;
        }
        if (isPairedCommitIntentPersisted) {
            flags |= FLAG_IS_PAIRED_COMMIT_INTENT;
        }
        if (observedSetupEvidence) flags |= FLAG_OBSERVED_SETUP_EVIDENCE;
        if (idsDeviceInfoExchanged) flags |= FLAG_IDS_DEVICE_INFO_EXCHANGED;
        return flags;
    }

    private void validate() {
        if (peerSpsMetadata != null) {
            if (state == null || state.wireValue() < DurableState.SMP_BONDED_RAW.wireValue()) {
                throw new IllegalArgumentException("SPS metadata has no authenticated pairing owner");
            }
            try (IdsSpsCompanionInfo ignored = IdsSpsCompanionInfo.parse(peerSpsMetadata)) { }
        }
        if (idsDeviceInfoExchanged && state.wireValue() < DurableState.IDS_CONTROL_READY.wireValue()) {
            throw new IllegalArgumentException("IDS device-info evidence precedes control registration");
        }
        if (observedSetupEvidence && state.wireValue() < DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            throw new IllegalArgumentException("Observed setup evidence predates activation");
        }
        requireLength(
                "pairing generation UUID",
                generationUuid,
                16);
        requireNonzero(
                "pairing generation UUID",
                generationUuid);
        if (state == null
                || transitionCounter < 1
                || !modernPairing) {
            throw new IllegalArgumentException(
                    "Pairing session state metadata is invalid");
        }
        if (networkRelayVersion < 0
                || networkRelayVersion > 0xffff
                || deviceType < 0
                || deviceType > 0xff) {
            throw new IllegalArgumentException(
                    "Pairing peer metadata is invalid");
        }

        validateText("device name", deviceName, true);
        validateText("build version", buildVersion, true);
        validateText("IDS device ID", idsDeviceId, false);
        validateText("product type", productType, false);
        validateText("board ID", boardId, false);
        validateCanonicalUuid(
                "local Bluetooth peer UUID",
                bluetoothCbUuid,
                true);
        validateCanonicalUuid(
                "local NRDevice UUID",
                nrUuid,
                true);
        validateCanonicalUuid(
                "local IDS device UUID",
                localIdsDeviceUuid,
                false);
        if (remoteInstanceUuid != null) {
            throw new IllegalArgumentException(
                    "Remote IDS instance UUID is transient and "
                            + "must not be persisted");
        }

        AppleNetworkRelayPairingMaterial local = null;
        AppleNetworkRelayInnerAddresses addresses = null;
        try {
            local = AppleNetworkRelayPairingMaterial
                    .restorePrivateSnapshot(
                            localNetworkMaterial);
            addresses =
                    AppleNetworkRelayInnerAddresses
                            .fromAuthoritative(
                                    initiatorClassDAddress,
                                    responderClassDAddress,
                                    initiatorClassCAddress,
                                    responderClassCAddress);
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
                remoteIdentity,
                AppleNetworkRelayPairingMaterial
                        .IDENTITY_PAYLOAD_LENGTH);
        requireNonzero(
                "remote identity",
                remoteIdentity);
        requireModernPublicKeys(
                "remote Class D",
                remoteClassD);
        requireModernPublicKeys(
                "remote Class C",
                remoteClassC);

        if (state
                == DurableState.PAIRING_MATERIAL_PERSISTED) {
            if (bluetoothBond != null
                    || pendingLocalC547 == null
                    || pendingRemoteC547 == null) {
                throw new IllegalArgumentException(
                        "Pre-SMP session material is inconsistent");
            }
        } else {
            if (bluetoothBond == null
                    || pendingLocalC547 != null
                    || pendingRemoteC547 != null) {
                throw new IllegalArgumentException(
                        "Post-SMP session material is inconsistent");
            }
            BluetoothBondSecretRecord
                    .validateSerialized(
                            bluetoothBond);
        }
        validateC547(
                "local pending C547",
                pendingLocalC547);
        validateC547(
                "remote pending C547",
                pendingRemoteC547);

        if (idsAuthConsumed) {
            if (pendingIdsAuthData != null) {
                throw new IllegalArgumentException(
                        "Consumed IDS authentication data remains present");
            }
        } else if (pendingIdsAuthData == null
                || (pendingIdsAuthData.length != 4
                && pendingIdsAuthData.length != 32)) {
            throw new IllegalArgumentException(
                    "Pending IDS authentication data has "
                            + "an invalid length");
        }
        if (state.wireValue()
                >= DurableState.IDS_CONTROL_READY.wireValue()
                && !idsAuthConsumed) {
            throw new IllegalArgumentException(
                    "IDS durable state lacks local paired-device "
                            + "registration");
        }

        if (isPairedCommitIntentPersisted
                && state
                != DurableState.READY_TO_COMMIT_IS_PAIRED) {
            throw new IllegalArgumentException(
                    "IS_PAIRED commit intent is attached to the wrong state");
        }

        boolean stateRequiresRole =
                state.wireValue()
                        >= DurableState
                                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                                .wireValue();
        boolean stateRequiresConfirmedAddresses =
                state.wireValue()
                        >= DurableState
                                .CLASS_D_ESTABLISHED
                                .wireValue();
        if ((localRole != null) != stateRequiresRole
                || (stateRequiresConfirmedAddresses
                && !addressesConfirmed)
                || (!stateRequiresRole
                && addressesConfirmed)
                || (state
                == DurableState
                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                && localRole
                == NrLinkBluetoothPrelude.LocalRole.RESPONDER
                && !addressesConfirmed)) {
            throw new IllegalArgumentException(
                    "Pairing role/address authority is inconsistent");
        }
    }

    private static void validateC547(
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

    private static void requireModernPublicKeys(
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

    private static void addOptional(
            List<Field> fields,
            int type,
            byte[] value) {
        if (value != null) {
            fields.add(new Field(type, value));
        }
    }

    private static void addText(
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

    private static byte[] required(
            Map<Integer, byte[]> fields,
            int type) {
        byte[] value = fields.get(type);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Pairing session record lacks a required field");
        }
        return value;
    }

    private static String decodeRequiredText(
            Map<Integer, byte[]> fields,
            int type,
            String label) {
        byte[] value = required(fields, type);
        return decodeText(value, label, true);
    }

    private static String decodeOptionalText(
            Map<Integer, byte[]> fields,
            int type,
            String label) {
        byte[] value = fields.get(type);
        return value == null
                ? null
                : decodeText(value, label, false);
    }

    private static String decodeText(
            byte[] encoded,
            String label,
            boolean required) {
        if (encoded.length > MAX_TEXT_BYTES
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

    private static void validateText(
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
                    || encoded.length > MAX_TEXT_BYTES
                    || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Pairing session " + label + " is invalid");
            }
        } finally {
            wipe(encoded);
        }
    }

    private static void validateCanonicalUuid(
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

    private static int parseU16(
            byte[] value,
            String label) {
        if (value.length != 2) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return ((value[0] & 0xff) << 8)
                | (value[1] & 0xff);
    }

    private static int parseU8(
            byte[] value,
            String label) {
        if (value.length != 1) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return value[0] & 0xff;
    }

    private static boolean parseBoolean(
            byte[] value,
            String label) {
        int parsed = parseU8(value, label);
        if (parsed > 1) {
            throw new IllegalArgumentException(
                    "Pairing session " + label + " is invalid");
        }
        return parsed != 0;
    }

    private static byte[] u16(int value) {
        return new byte[]{
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static boolean isKnownField(int type) {
        return type == FIELD_GENERATION_UUID
                || type == FIELD_BLUETOOTH_BOND
                || type == FIELD_PENDING_LOCAL_C547
                || type == FIELD_PENDING_REMOTE_C547
                || type == FIELD_PENDING_IDS_AUTH_DATA
                || type == FIELD_LOCAL_NETWORK_MATERIAL
                || type == FIELD_REMOTE_IDENTITY
                || type == FIELD_REMOTE_CLASS_D
                || type == FIELD_REMOTE_CLASS_C
                || type == FIELD_INITIATOR_CLASS_D_ADDRESS
                || type == FIELD_RESPONDER_CLASS_D_ADDRESS
                || type == FIELD_INITIATOR_CLASS_C_ADDRESS
                || type == FIELD_RESPONDER_CLASS_C_ADDRESS
                || type == FIELD_NETWORK_RELAY_VERSION
                || type == FIELD_DEVICE_TYPE
                || type == FIELD_ALWAYS_ON_WIFI
                || type == FIELD_DEVICE_NAME
                || type == FIELD_BUILD_VERSION
                || type == FIELD_IDS_DEVICE_ID
                || type == FIELD_PRODUCT_TYPE
                || type == FIELD_BOARD_ID
                || type == FIELD_BLUETOOTH_CB_UUID
                || type == FIELD_NR_UUID
                || type == FIELD_REMOTE_INSTANCE_UUID
                || type == FIELD_LOCAL_IDS_DEVICE_UUID
                || type == FIELD_PEER_SPS_METADATA;
    }

    private static int roleCode(
            NrLinkBluetoothPrelude.LocalRole role) {
        if (role == null) {
            return 0;
        }
        return role
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? 1
                : 2;
    }

    private static NrLinkBluetoothPrelude.LocalRole roleFromCode(
            int code) {
        return switch (code) {
            case 0 -> null;
            case 1 -> NrLinkBluetoothPrelude.LocalRole.INITIATOR;
            case 2 -> NrLinkBluetoothPrelude.LocalRole.RESPONDER;
            default -> throw new IllegalArgumentException(
                    "Pairing session IKE role is invalid");
        };
    }

    private static byte[] sha256(
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

    private static byte[] cloneOrNull(
            byte[] value) {
        return value == null ? null : value.clone();
    }

    private static void requireLength(
            String label,
            byte[] value,
            int expected) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(
                    label + " must be " + expected + " bytes");
        }
    }

    private static void requireNonzero(
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

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "Pairing session record has been destroyed");
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class Field {
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
