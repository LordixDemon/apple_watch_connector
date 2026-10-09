package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-scoped coordinator for a fresh Ultra 2 setup.
 *
 * <p>This class owns no PIN, OOB key, Bluetooth UUID, IDS key, or pairing
 * session identifier. Secret-bearing stores and transports act on the
 * returned commands and report only success/failure events back here.</p>
 */
final class AppleWatchSetupCoordinator {
    static final String ULTRA_2_PRODUCT_TYPE =
            "Watch7,5";
    static final long ULTRA_2_CHIP_ID =
            0x8310L;
    static final String ULTRA_2_DEFAULT_MODEL_NUMBER =
            "A2986";
    static final String PAIRING_SESSION_ID_CAPABILITY =
            "0b75afac-6373-41d2-a4f3-d4c1e9295a07";

    static Set<String> requiredCapabilities() {
        return Set.of(PAIRING_SESSION_ID_CAPABILITY);
    }

    enum Phase {
        IDLE,
        CANDIDATE_READY,
        AUTH_DATA_READY,
        LOCAL_PAIRING_STORE_PENDING,
        IDS_PEER_ADD_PENDING,
        IDS_CHANNELS_PENDING,
        WAITING_FOR_READINESS,
        TRANSPORT_COMPLETE,
        NORMAL,
        FAILED
    }

    enum ActionType {
        START_NETWORK_RELAY_AUTH,
        CREATE_LOCAL_PAIRING_STORE,
        ADD_IDS_PAIRED_DEVICE,
        INITIALIZE_IDS_CHANNELS,
        PAIRING_TRANSPORT_COMPLETE,
        SEND_COMPATIBILITY_STATE,
        PERSIST_PAIRING_SESSION_ID,
        SEND_PAIRING_SESSION_ID,
        REPORT_FAILURE,
        RESET_PAIRING_GENERATION
    }

    enum Failure {
        AUTH_DATA_MISSING,
        NETWORK_RELAY_PAIRING,
        BLUETOOTH_IDENTIFIER_MISSING,
        LOCAL_PAIRING_STORE,
        IDS_PEER_ADD,
        IDS_CHANNEL_INITIALIZATION,
        TARGET_IDENTITY_MISMATCH,
        RUNTIME_VERSION_MISMATCH,
        PAIRING_SESSION_ID_PERSISTENCE,
        UNEXPECTED_ORDER
    }

    enum PairingSessionRetryTrigger {
        ACTIVE_DEVICE_CHANGED,
        SYSTEM_BUILD_VERSION_CHANGED,
        NEW_WATCH_PAIRING_SUCCEEDED,
        COMPANION_PASSCODE_SUCCEEDED,
        DEVICE_SWITCH_SUCCEEDED,
        CLASS_D_RECONNECTED
    }

    private long generation;
    private Phase phase =
            Phase.IDLE;
    private int advertisedPairingVersion = -1;

    private boolean authDataReady;
    private boolean networkRelayComplete;
    private boolean localPairingStoreRequested;
    private boolean localPairingStoreCreated;
    private boolean idsPeerAddRequested;
    private boolean idsPeerAdded;
    private boolean idsChannelsRequested;
    private boolean idsChannelsInitialized;

    private boolean idsAccountAndDevicePresent;
    private boolean initialPropertiesReceived;
    private boolean transportCompletionIssued;

    private boolean active;
    private boolean paired;
    private boolean archived;
    private int statusCode;
    private boolean altAccount;
    private Integer idsMaxPairingVersion;
    private String modelNumber;
    private boolean identityValidated;
    private boolean pairingSessionCapabilityPresent;
    private Integer compatibilityState;
    private Integer lastSentCompatibilityState;

    private boolean pairingSessionPropertyObserved;
    private boolean pairingSessionIdPresent;
    private boolean pairingSessionPersistRequested;
    private boolean pairingSessionNeedsSend;
    private boolean pairingSessionSendInFlight;

    synchronized List<Action> beginUltra2Generation(
            long newGeneration,
            WatchSetupMetadataCodec.Identifier identifier,
            WatchSetupMetadataCodec.ExtendedMetadata metadata) {
        if (newGeneration <= 0) {
            throw new IllegalArgumentException(
                    "Pairing generation must be positive");
        }
        if (!WatchSetupMetadataCodec
                .isUltra2NetworkRelayCandidate(
                        identifier,
                        metadata,
                        NanoRegistryClassDCodec
                                .IOS_26_6_ULTRA2_PHONE_MIN_VERSION,
                        NanoRegistryClassDCodec
                                .IOS_26_6_PHONE_MAX_VERSION)) {
            throw new IllegalArgumentException(
                    "Candidate is not a compatible Ultra 2 NetworkRelay setup");
        }
        if (phase != Phase.IDLE
                && newGeneration < generation) {
            return List.of();
        }
        if (phase != Phase.IDLE
                && newGeneration == generation) {
            return List.of();
        }

        List<Action> actions =
                new ArrayList<>();
        if (phase != Phase.IDLE) {
            actions.add(
                    Action.simple(
                            generation,
                            ActionType
                                    .RESET_PAIRING_GENERATION));
        }
        clearGenerationState();
        generation =
                newGeneration;
        advertisedPairingVersion =
                metadata.pairingVersion;
        phase =
                Phase.CANDIDATE_READY;
        actions.add(
                Action.simple(
                        generation,
                        ActionType
                                .START_NETWORK_RELAY_AUTH));
        return immutable(
                actions);
    }

    synchronized List<Action> onAuthDataReady(
            long eventGeneration) {
        if (!accepts(
                eventGeneration)
                || authDataReady) {
            return List.of();
        }
        authDataReady = true;
        if (phase
                == Phase.CANDIDATE_READY) {
            phase =
                    Phase.AUTH_DATA_READY;
        }
        return List.of();
    }

    synchronized List<Action> onNetworkRelayCompleted(
            long eventGeneration,
            boolean success,
            boolean bluetoothIdentifierPresent) {
        if (!accepts(
                eventGeneration)
                || networkRelayComplete) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.NETWORK_RELAY_PAIRING);
        }
        if (!authDataReady) {
            return fail(
                    Failure.AUTH_DATA_MISSING);
        }
        if (!bluetoothIdentifierPresent) {
            return fail(
                    Failure.BLUETOOTH_IDENTIFIER_MISSING);
        }

        networkRelayComplete = true;
        localPairingStoreRequested = true;
        phase =
                Phase.LOCAL_PAIRING_STORE_PENDING;
        List<Action> actions =
                new ArrayList<>();
        actions.add(
                Action.simple(
                        generation,
                        ActionType
                                .CREATE_LOCAL_PAIRING_STORE));
        appendReadinessActions(
                actions);
        return immutable(
                actions);
    }

    synchronized List<Action> onLocalPairingStoreCreated(
            long eventGeneration,
            boolean success) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (localPairingStoreCreated) {
            return List.of();
        }
        if (!localPairingStoreRequested) {
            return fail(
                    Failure.UNEXPECTED_ORDER);
        }
        if (!success) {
            return fail(
                    Failure.LOCAL_PAIRING_STORE);
        }

        localPairingStoreCreated = true;
        idsPeerAddRequested = true;
        phase =
                Phase.IDS_PEER_ADD_PENDING;
        return List.of(
                Action.simple(
                        generation,
                        ActionType.ADD_IDS_PAIRED_DEVICE));
    }

    synchronized List<Action> onIdsPeerAdded(
            long eventGeneration,
            boolean success) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (idsPeerAdded) {
            return List.of();
        }
        if (!idsPeerAddRequested
                || !localPairingStoreCreated) {
            return fail(
                    Failure.UNEXPECTED_ORDER);
        }
        if (!success) {
            return fail(
                    Failure.IDS_PEER_ADD);
        }

        idsPeerAdded = true;
        idsChannelsRequested = true;
        phase =
                Phase.IDS_CHANNELS_PENDING;
        return List.of(
                Action.simple(
                        generation,
                        ActionType.INITIALIZE_IDS_CHANNELS));
    }

    synchronized List<Action> onIdsChannelsInitialized(
            long eventGeneration,
            boolean success) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (idsChannelsInitialized) {
            return List.of();
        }
        if (!idsChannelsRequested
                || !idsPeerAdded) {
            return fail(
                    Failure.UNEXPECTED_ORDER);
        }
        if (!success) {
            return fail(
                    Failure.IDS_CHANNEL_INITIALIZATION);
        }

        idsChannelsInitialized = true;
        phase =
                Phase.WAITING_FOR_READINESS;
        List<Action> actions =
                new ArrayList<>();
        appendReadinessActions(
                actions);
        return immutable(
                actions);
    }

    synchronized List<Action> onIdsAccountAndDeviceReady(
            long eventGeneration) {
        if (!accepts(
                eventGeneration)
                || idsAccountAndDevicePresent) {
            return List.of();
        }
        idsAccountAndDevicePresent = true;
        List<Action> actions =
                new ArrayList<>();
        appendReadinessActions(
                actions);
        return immutable(
                actions);
    }

    synchronized List<Action> onIdsCompatibilityVersion(
            long eventGeneration,
            int maxPairingVersion) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (maxPairingVersion < 0
                || maxPairingVersion > 0xffff) {
            throw new IllegalArgumentException(
                    "IDS pairing version is outside uint16");
        }
        if (maxPairingVersion
                != advertisedPairingVersion) {
            return fail(
                    Failure.RUNTIME_VERSION_MISMATCH);
        }
        idsMaxPairingVersion =
                maxPairingVersion;
        return reevaluateCompatibility(
                false);
    }

    synchronized List<Action> onFullWatchClassCSnapshot(
            long eventGeneration,
            String productType,
            String runtimeModelNumber,
            long chipId,
            Set<String> capabilities) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (!ULTRA_2_PRODUCT_TYPE.equals(
                productType)
                || runtimeModelNumber == null
                || runtimeModelNumber.isBlank()
                || chipId != ULTRA_2_CHIP_ID
                || capabilities == null
                || !containsCapability(
                        capabilities,
                        PAIRING_SESSION_ID_CAPABILITY)) {
            return fail(
                    Failure.TARGET_IDENTITY_MISMATCH);
        }

        modelNumber =
                runtimeModelNumber;
        identityValidated = true;
        pairingSessionCapabilityPresent = true;
        initialPropertiesReceived = true;

        List<Action> actions =
                new ArrayList<>();
        actions.addAll(
                reevaluateCompatibility(
                        false));
        appendReadinessActions(
                actions);
        return immutable(
                actions);
    }

    synchronized List<Action> onActiveChanged(
            long eventGeneration,
            boolean isActive) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        boolean becameActive =
                isActive && !active;
        active =
                isActive;
        List<Action> actions =
                new ArrayList<>();
        actions.addAll(
                reevaluateCompatibility(
                        becameActive));
        if (isActive) {
            appendPairingSessionActions(
                    actions,
                    PairingSessionRetryTrigger
                            .ACTIVE_DEVICE_CHANGED);
        }
        return immutable(
                actions);
    }

    synchronized List<Action> onIsPairedChanged(
            long eventGeneration,
            boolean isPaired) {
        if (!accepts(
                eventGeneration)
                || paired == isPaired) {
            return List.of();
        }
        paired =
                isPaired;
        List<Action> actions =
                new ArrayList<>(
                        reevaluateCompatibility(
                                false));
        if (paired
                && transportCompletionIssued
                && compatibilityState != null
                && compatibilityState
                == NanoRegistryClassDCodec
                        .COMPATIBILITY_STATE_NORMAL) {
            phase =
                    Phase.NORMAL;
        }
        return immutable(
                actions);
    }

    synchronized List<Action> onAltAccountChanged(
            long eventGeneration,
            boolean isAltAccount) {
        if (!accepts(
                eventGeneration)
                || altAccount == isAltAccount) {
            return List.of();
        }
        altAccount =
                isAltAccount;
        return reevaluateCompatibility(
                false);
    }

    synchronized List<Action> onArchiveStatusChanged(
            long eventGeneration,
            boolean isArchived,
            int newStatusCode) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        archived =
                isArchived;
        statusCode =
                newStatusCode;
        return reevaluateCompatibility(
                false);
    }

    synchronized List<Action> onClassDReconnect(
            long eventGeneration) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        List<Action> actions =
                new ArrayList<>(
                        reevaluateCompatibility(
                                true));
        appendPairingSessionActions(
                actions,
                PairingSessionRetryTrigger
                        .CLASS_D_RECONNECTED);
        return immutable(
                actions);
    }

    synchronized List<Action> onPairingSessionIdProperty(
            long eventGeneration,
            boolean present) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        pairingSessionPropertyObserved = true;
        pairingSessionIdPresent =
                present;
        if (present) {
            pairingSessionPersistRequested = false;
            pairingSessionNeedsSend = false;
            pairingSessionSendInFlight = false;
        } else if (!pairingSessionPersistRequested) {
            pairingSessionNeedsSend = false;
            pairingSessionSendInFlight = false;
        }
        return List.of();
    }

    synchronized List<Action> onPairingSessionRetryTrigger(
            long eventGeneration,
            PairingSessionRetryTrigger trigger) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (trigger == null) {
            throw new IllegalArgumentException(
                    "Pairing-session retry trigger is absent");
        }
        List<Action> actions =
                new ArrayList<>();
        appendPairingSessionActions(
                actions,
                trigger);
        return immutable(
                actions);
    }

    synchronized List<Action> onPairingSessionIdPersisted(
            long eventGeneration,
            boolean success) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (pairingSessionIdPresent
                && !pairingSessionPersistRequested) {
            return List.of();
        }
        if (!pairingSessionPersistRequested) {
            return fail(
                    Failure.UNEXPECTED_ORDER);
        }
        if (!success) {
            return fail(
                    Failure
                            .PAIRING_SESSION_ID_PERSISTENCE);
        }

        pairingSessionPersistRequested = false;
        pairingSessionPropertyObserved = true;
        pairingSessionIdPresent = true;
        pairingSessionNeedsSend = true;
        pairingSessionSendInFlight = true;
        return List.of(
                Action.simple(
                        generation,
                        ActionType
                                .SEND_PAIRING_SESSION_ID));
    }

    synchronized List<Action> onPairingSessionIdSendResult(
            long eventGeneration,
            boolean success) {
        if (!accepts(
                eventGeneration)) {
            return List.of();
        }
        if (!pairingSessionIdPresent
                || !pairingSessionSendInFlight) {
            return fail(
                    Failure.UNEXPECTED_ORDER);
        }
        pairingSessionSendInFlight = false;
        pairingSessionNeedsSend =
                !success;
        return List.of();
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                generation,
                phase,
                advertisedPairingVersion,
                networkRelayComplete,
                idsAccountAndDevicePresent,
                initialPropertiesReceived,
                transportCompletionIssued,
                identityValidated,
                compatibilityState,
                pairingSessionCapabilityPresent,
                pairingSessionIdPresent,
                pairingSessionPersistRequested,
                pairingSessionNeedsSend);
    }

    private boolean accepts(
            long eventGeneration) {
        return phase != Phase.IDLE
                && phase != Phase.FAILED
                && generation == eventGeneration;
    }

    private List<Action> reevaluateCompatibility(
            boolean forceReplay) {
        Integer next =
                calculateCompatibilityState();
        compatibilityState =
                next;
        if (!active
                || next == null
                || (!forceReplay
                && Objects.equals(
                        lastSentCompatibilityState,
                        next))) {
            return List.of();
        }
        lastSentCompatibilityState =
                next;
        return List.of(
                Action.compatibility(
                        generation,
                        next,
                        advertisedPairingVersion));
    }

    private Integer calculateCompatibilityState() {
        boolean compatible =
                idsMaxPairingVersion == null
                        || (idsMaxPairingVersion
                        >= NanoRegistryClassDCodec
                        .IOS_26_6_ULTRA2_PHONE_MIN_VERSION
                        && idsMaxPairingVersion
                        <= NanoRegistryClassDCodec
                        .IOS_26_6_PHONE_MAX_VERSION);
        if (paired) {
            if (!identityValidated) {
                return null;
            }
            if (archived) {
                return NanoRegistryClassDCodec
                        .COMPATIBILITY_STATE_INVALID;
            }
            if (!compatible) {
                return NanoRegistryClassDCodec
                        .COMPATIBILITY_STATE_UPDATE;
            }
            return altAccount
                    ? NanoRegistryClassDCodec
                    .COMPATIBILITY_STATE_ALT_ACCOUNT
                    : NanoRegistryClassDCodec
                    .COMPATIBILITY_STATE_NORMAL;
        }
        if (archived
                && statusCode != 4) {
            return NanoRegistryClassDCodec
                    .COMPATIBILITY_STATE_INVALID;
        }
        if (idsMaxPairingVersion == null) {
            return null;
        }
        if (modelNumber == null) {
            return NanoRegistryClassDCodec
                    .COMPATIBILITY_STATE_CHECK;
        }
        if (!compatible
                || !identityValidated) {
            return NanoRegistryClassDCodec
                    .COMPATIBILITY_STATE_UPDATE;
        }
        return NanoRegistryClassDCodec
                .COMPATIBILITY_STATE_CONFIGURE;
    }

    private void appendReadinessActions(
            List<Action> actions) {
        if (transportCompletionIssued
                || !networkRelayComplete
                || !idsChannelsInitialized
                || !idsAccountAndDevicePresent
                || !initialPropertiesReceived) {
            return;
        }
        transportCompletionIssued = true;
        phase =
                Phase.TRANSPORT_COMPLETE;
        actions.add(
                Action.simple(
                        generation,
                        ActionType
                                .PAIRING_TRANSPORT_COMPLETE));
        if (paired
                && compatibilityState != null
                && compatibilityState
                == NanoRegistryClassDCodec
                        .COMPATIBILITY_STATE_NORMAL) {
            phase =
                    Phase.NORMAL;
        }
    }

    private void appendPairingSessionActions(
            List<Action> actions,
            PairingSessionRetryTrigger trigger) {
        if (trigger == null
                || !pairingSessionCapabilityPresent
                || !pairingSessionPropertyObserved) {
            return;
        }
        if (!pairingSessionIdPresent) {
            if (!pairingSessionPersistRequested) {
                pairingSessionPersistRequested = true;
                actions.add(
                        Action.simple(
                                generation,
                                ActionType
                                        .PERSIST_PAIRING_SESSION_ID));
            }
            return;
        }
        if (pairingSessionNeedsSend
                && !pairingSessionSendInFlight) {
            pairingSessionSendInFlight = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType
                                    .SEND_PAIRING_SESSION_ID));
        }
    }

    private List<Action> fail(
            Failure failure) {
        phase =
                Phase.FAILED;
        return List.of(
                Action.failure(
                        generation,
                        failure),
                Action.simple(
                        generation,
                        ActionType
                                .RESET_PAIRING_GENERATION));
    }

    private void clearGenerationState() {
        advertisedPairingVersion = -1;
        authDataReady = false;
        networkRelayComplete = false;
        localPairingStoreRequested = false;
        localPairingStoreCreated = false;
        idsPeerAddRequested = false;
        idsPeerAdded = false;
        idsChannelsRequested = false;
        idsChannelsInitialized = false;
        idsAccountAndDevicePresent = false;
        initialPropertiesReceived = false;
        transportCompletionIssued = false;
        active = false;
        paired = false;
        archived = false;
        statusCode = 0;
        altAccount = false;
        idsMaxPairingVersion = null;
        modelNumber = null;
        identityValidated = false;
        pairingSessionCapabilityPresent = false;
        compatibilityState = null;
        lastSentCompatibilityState = null;
        pairingSessionPropertyObserved = false;
        pairingSessionIdPresent = false;
        pairingSessionPersistRequested = false;
        pairingSessionNeedsSend = false;
        pairingSessionSendInFlight = false;
    }

    private static boolean containsCapability(
            Set<String> capabilities,
            String required) {
        for (String capability : capabilities) {
            if (required.equalsIgnoreCase(
                    capability)) {
                return true;
            }
        }
        return false;
    }

    private static List<Action> immutable(
            List<Action> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(
                new ArrayList<>(
                        actions));
    }

    static final class Action {
        final long generation;
        final ActionType type;
        final Integer compatibilityState;
        final Integer runtimeWatchPairingVersion;
        final Failure failure;

        private Action(
                long generation,
                ActionType type,
                Integer compatibilityState,
                Integer runtimeWatchPairingVersion,
                Failure failure) {
            this.generation =
                    generation;
            this.type =
                    type;
            this.compatibilityState =
                    compatibilityState;
            this.runtimeWatchPairingVersion =
                    runtimeWatchPairingVersion;
            this.failure =
                    failure;
        }

        private static Action simple(
                long generation,
                ActionType type) {
            return new Action(
                    generation,
                    type,
                    null,
                    null,
                    null);
        }

        private static Action compatibility(
                long generation,
                int state,
                int runtimeWatchPairingVersion) {
            return new Action(
                    generation,
                    ActionType
                            .SEND_COMPATIBILITY_STATE,
                    state,
                    runtimeWatchPairingVersion,
                    null);
        }

        private static Action failure(
                long generation,
                Failure failure) {
            return new Action(
                    generation,
                    ActionType.REPORT_FAILURE,
                    null,
                    null,
                    failure);
        }

        NanoRegistryClassDCodec.PairingModeRequest
                pairingModeRequest() {
            if (type
                    != ActionType
                            .SEND_COMPATIBILITY_STATE
                    || compatibilityState == null
                    || runtimeWatchPairingVersion == null) {
                throw new IllegalStateException(
                        "Action does not contain a pairing-mode request");
            }
            return NanoRegistryClassDCodec
                    .PairingModeRequest
                    .modernIos26_6Ultra2(
                            compatibilityState,
                            runtimeWatchPairingVersion);
        }
    }

    static final class Snapshot {
        final long generation;
        final Phase phase;
        final int advertisedPairingVersion;
        final boolean networkRelayComplete;
        final boolean idsAccountAndDevicePresent;
        final boolean initialPropertiesReceived;
        final boolean transportCompletionIssued;
        final boolean identityValidated;
        final Integer compatibilityState;
        final boolean pairingSessionCapabilityPresent;
        final boolean pairingSessionIdPresent;
        final boolean pairingSessionPersistRequested;
        final boolean pairingSessionNeedsSend;

        private Snapshot(
                long generation,
                Phase phase,
                int advertisedPairingVersion,
                boolean networkRelayComplete,
                boolean idsAccountAndDevicePresent,
                boolean initialPropertiesReceived,
                boolean transportCompletionIssued,
                boolean identityValidated,
                Integer compatibilityState,
                boolean pairingSessionCapabilityPresent,
                boolean pairingSessionIdPresent,
                boolean pairingSessionPersistRequested,
                boolean pairingSessionNeedsSend) {
            this.generation =
                    generation;
            this.phase =
                    phase;
            this.advertisedPairingVersion =
                    advertisedPairingVersion;
            this.networkRelayComplete =
                    networkRelayComplete;
            this.idsAccountAndDevicePresent =
                    idsAccountAndDevicePresent;
            this.initialPropertiesReceived =
                    initialPropertiesReceived;
            this.transportCompletionIssued =
                    transportCompletionIssued;
            this.identityValidated =
                    identityValidated;
            this.compatibilityState =
                    compatibilityState;
            this.pairingSessionCapabilityPresent =
                    pairingSessionCapabilityPresent;
            this.pairingSessionIdPresent =
                    pairingSessionIdPresent;
            this.pairingSessionPersistRequested =
                    pairingSessionPersistRequested;
            this.pairingSessionNeedsSend =
                    pairingSessionNeedsSend;
        }
    }
}
