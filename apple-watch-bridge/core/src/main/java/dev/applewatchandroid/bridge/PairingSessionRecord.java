package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
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

    static final byte[] MAGIC =
            new byte[]{'A', 'W', 'P', '2'};
    static final int HEADER_LENGTH = 26;
    static final int DIGEST_LENGTH = 32;

    static final int FLAG_MODERN_PAIRING = 1;
    static final int FLAG_ADDRESSES_CONFIRMED = 1 << 1;
    static final int FLAG_IDS_AUTH_CONSUMED = 1 << 2;
    static final int FLAG_IS_PAIRED_COMMIT_INTENT = 1 << 3;
    static final int FLAG_OBSERVED_SETUP_EVIDENCE = 1 << 4;
    static final int FLAG_IDS_DEVICE_INFO_EXCHANGED = 1 << 5;
    static final int KNOWN_FLAGS =
            FLAG_MODERN_PAIRING
                    | FLAG_ADDRESSES_CONFIRMED
                    | FLAG_IDS_AUTH_CONSUMED
                    | FLAG_IS_PAIRED_COMMIT_INTENT
                    | FLAG_OBSERVED_SETUP_EVIDENCE
                    | FLAG_IDS_DEVICE_INFO_EXCHANGED;

    static final int FIELD_GENERATION_UUID = 1;
    static final int FIELD_BLUETOOTH_BOND = 2;
    static final int FIELD_PENDING_LOCAL_C547 = 3;
    static final int FIELD_PENDING_REMOTE_C547 = 4;
    static final int FIELD_PENDING_IDS_AUTH_DATA = 5;
    static final int FIELD_LOCAL_NETWORK_MATERIAL = 10;
    static final int FIELD_REMOTE_IDENTITY = 11;
    static final int FIELD_REMOTE_CLASS_D = 12;
    static final int FIELD_REMOTE_CLASS_C = 13;
    static final int FIELD_INITIATOR_CLASS_D_ADDRESS = 20;
    static final int FIELD_RESPONDER_CLASS_D_ADDRESS = 21;
    static final int FIELD_INITIATOR_CLASS_C_ADDRESS = 22;
    static final int FIELD_RESPONDER_CLASS_C_ADDRESS = 23;
    static final int FIELD_NETWORK_RELAY_VERSION = 30;
    static final int FIELD_DEVICE_TYPE = 31;
    static final int FIELD_ALWAYS_ON_WIFI = 32;
    static final int FIELD_DEVICE_NAME = 33;
    static final int FIELD_BUILD_VERSION = 34;
    static final int FIELD_IDS_DEVICE_ID = 35;
    static final int FIELD_PRODUCT_TYPE = 36;
    static final int FIELD_BOARD_ID = 37;
    static final int FIELD_BLUETOOTH_CB_UUID = 38;
    static final int FIELD_NR_UUID = 39;
    static final int FIELD_REMOTE_INSTANCE_UUID = 40;
    static final int FIELD_LOCAL_IDS_DEVICE_UUID = 41;
    static final int FIELD_PEER_SPS_METADATA = 42;
    static final int FIELD_OBSERVED_PEER_MAXIMUM = 43;
    static final int FIELD_OBSERVED_PEER_BUILD = 44;

    static final int MAX_TEXT_BYTES = 1024;

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

    final byte[] generationUuid;
    final DurableState state;
    final long transitionCounter;
    final boolean modernPairing;
    final boolean addressesConfirmed;
    final boolean idsAuthConsumed;
    final boolean isPairedCommitIntentPersisted;
    final NrLinkBluetoothPrelude.LocalRole localRole;

    final byte[] bluetoothBond;
    final byte[] pendingLocalC547;
    final byte[] pendingRemoteC547;
    final byte[] pendingIdsAuthData;

    final byte[] localNetworkMaterial;
    final byte[] remoteIdentity;
    final byte[] remoteClassD;
    final byte[] remoteClassC;

    final byte[] initiatorClassDAddress;
    final byte[] responderClassDAddress;
    final byte[] initiatorClassCAddress;
    final byte[] responderClassCAddress;

    final int networkRelayVersion;
    final int deviceType;
    final boolean alwaysOnWifi;
    final String deviceName;
    final String buildVersion;
    final String idsDeviceId;
    final String productType;
    final String boardId;
    final String bluetoothCbUuid;
    final String nrUuid;
    /**
     * Android's installation-stable IDSCurrentDevice equivalent. This is
     * advertised as Hello.deviceUniqueID and, when available, as BDDF. It is
     * independent from the local CoreBluetooth and NetworkRelay database
     * identifiers.
     */
    final String localIdsDeviceUuid;
    /**
     * Reserved only for rejecting stale version-2 records. An IDS remote
     * instance UUID is generated per live connection and must remain in
     * {@link IdsServiceMapState}, not in durable pairing state.
     */
    final String remoteInstanceUuid;
    boolean destroyed;
    boolean observedSetupEvidence;
    boolean idsDeviceInfoExchanged;
    byte[] peerSpsMetadata;
    Integer observedPeerMaximum;
    String observedPeerBuild;

    PairingSessionRecord(
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
        this.generationUuid = PairingSessionRecordCodec.cloneOrNull(generationUuid);
        this.state = state;
        this.transitionCounter = transitionCounter;
        this.modernPairing = modernPairing;
        this.addressesConfirmed = addressesConfirmed;
        this.idsAuthConsumed = idsAuthConsumed;
        this.isPairedCommitIntentPersisted =
                isPairedCommitIntentPersisted;
        this.localRole = localRole;
        this.bluetoothBond = PairingSessionRecordCodec.cloneOrNull(bluetoothBond);
        this.pendingLocalC547 = PairingSessionRecordCodec.cloneOrNull(pendingLocalC547);
        this.pendingRemoteC547 = PairingSessionRecordCodec.cloneOrNull(pendingRemoteC547);
        this.pendingIdsAuthData =
                PairingSessionRecordCodec.cloneOrNull(pendingIdsAuthData);
        this.localNetworkMaterial =
                PairingSessionRecordCodec.cloneOrNull(localNetworkMaterial);
        this.remoteIdentity = PairingSessionRecordCodec.cloneOrNull(remoteIdentity);
        this.remoteClassD = PairingSessionRecordCodec.cloneOrNull(remoteClassD);
        this.remoteClassC = PairingSessionRecordCodec.cloneOrNull(remoteClassC);
        this.initiatorClassDAddress =
                PairingSessionRecordCodec.cloneOrNull(initiatorClassDAddress);
        this.responderClassDAddress =
                PairingSessionRecordCodec.cloneOrNull(responderClassDAddress);
        this.initiatorClassCAddress =
                PairingSessionRecordCodec.cloneOrNull(initiatorClassCAddress);
        this.responderClassCAddress =
                PairingSessionRecordCodec.cloneOrNull(responderClassCAddress);
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
        PairingSessionRecordCodec.validateCanonicalUuid(
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
            PairingSessionRecordCodec.wipe(localC547);
            PairingSessionRecordCodec.wipe(remoteC547);
            PairingSessionRecordCodec.wipe(localSnapshot);
            PairingSessionRecordCodec.wipe(remoteIdentity);
            PairingSessionRecordCodec.wipe(remoteClassD);
            PairingSessionRecordCodec.wipe(remoteClassC);
            PairingSessionRecordCodec.wipe(initiatorD);
            PairingSessionRecordCodec.wipe(responderD);
            PairingSessionRecordCodec.wipe(initiatorC);
            PairingSessionRecordCodec.wipe(responderC);
        }
    }

    static PairingSessionRecord parse(byte[] serialized) {
        return PairingSessionRecordCodec.parse(serialized);
    }

    byte[] serialize() {
        requireLive();
        return PairingSessionRecordCodec.serialize(this);
    }

    void validate() {
        PairingSessionRecordCodec.validate(this);
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "Pairing session record has been destroyed");
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
            PairingSessionRecordCodec.wipe(initiatorD);
            PairingSessionRecordCodec.wipe(responderD);
            PairingSessionRecordCodec.wipe(initiatorC);
            PairingSessionRecordCodec.wipe(responderC);
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
            PairingSessionRecordCodec.wipe(initiatorD);
            PairingSessionRecordCodec.wipe(responderD);
            PairingSessionRecordCodec.wipe(initiatorC);
            PairingSessionRecordCodec.wipe(responderC);
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
    String peerBuildVersion() { requireLive(); return observedPeerBuild == null ? buildVersion : observedPeerBuild; }
    Integer observedPeerMaximum() { requireLive(); return observedPeerMaximum; }
    PairingSessionRecord withObservedPeerSystem(String authenticatedIdsIdentifier, int maximum, String build) {
        requireLive();
        if (state.wireValue() < DurableState.IDS_CONTROL_READY.wireValue() || idsDeviceId == null
                || !idsDeviceId.equalsIgnoreCase(authenticatedIdsIdentifier) || maximum < 1 || maximum > 0xffff
                || build == null || !build.matches("[0-9]{1,3}[A-Za-z][0-9]{1,6}[a-z]?"))
            throw new IllegalArgumentException("Observed Watch system has no authenticated owner");
        var next = copy(state, Math.addExact(transitionCounter, 1), localRole, addressesConfirmed,
                idsAuthConsumed, bluetoothBond, pendingLocalC547, pendingRemoteC547, pendingIdsAuthData,
                initiatorClassDAddress, responderClassDAddress, initiatorClassCAddress, responderClassCAddress);
        next.observedPeerMaximum = maximum;
        next.observedPeerBuild = build;
        next.validate();
        return next;
    }
    String peerProductType() { requireLive(); return productType; }

    String peerNetworkRelayIdentifier() {
        requireLive();
        if (remoteIdentity == null || remoteIdentity.length < 16) return null;
        ByteBuffer identity = ByteBuffer.wrap(remoteIdentity);
        return new UUID(identity.getLong(), identity.getLong()).toString();
    }
    boolean hasExchangedIdsDeviceInfo() { requireLive(); return idsDeviceInfoExchanged; }

    byte[] peerSpsMetadata() { requireLive(); return PairingSessionRecordCodec.cloneOrNull(peerSpsMetadata); }

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
            PairingSessionRecordCodec.wipe(next.peerSpsMetadata);
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
        return PairingSessionRecordCodec.cloneOrNull(pendingIdsAuthData);
    }

    byte[] bluetoothBond() {
        requireLive();
        return PairingSessionRecordCodec.cloneOrNull(bluetoothBond);
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
        return PairingSessionRecordCodec.cloneOrNull(remoteClassD);
    }

    byte[] remoteClassC() {
        requireLive();
        return PairingSessionRecordCodec.cloneOrNull(remoteClassC);
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        PairingSessionRecordCodec.wipe(generationUuid);
        PairingSessionRecordCodec.wipe(bluetoothBond);
        PairingSessionRecordCodec.wipe(pendingLocalC547);
        PairingSessionRecordCodec.wipe(pendingRemoteC547);
        PairingSessionRecordCodec.wipe(pendingIdsAuthData);
        PairingSessionRecordCodec.wipe(localNetworkMaterial);
        PairingSessionRecordCodec.wipe(remoteIdentity);
        PairingSessionRecordCodec.wipe(remoteClassD);
        PairingSessionRecordCodec.wipe(remoteClassC);
        PairingSessionRecordCodec.wipe(initiatorClassDAddress);
        PairingSessionRecordCodec.wipe(responderClassDAddress);
        PairingSessionRecordCodec.wipe(initiatorClassCAddress);
        PairingSessionRecordCodec.wipe(responderClassCAddress);
        PairingSessionRecordCodec.wipe(peerSpsMetadata);
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
        copied.peerSpsMetadata = PairingSessionRecordCodec.cloneOrNull(peerSpsMetadata);
        if (newState.wireValue() >= DurableState.IDS_CONTROL_READY.wireValue()) {
            copied.observedPeerMaximum = observedPeerMaximum;
            copied.observedPeerBuild = observedPeerBuild;
        }
        return copied;
    }


    int flags() {
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

























}
