package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Connects the proven pre-commit setup state machine to live NanoRegistry.
 *
 * <p>The adapter starts only after IDS control plus the initial Class-D and
 * Class-C lanes are joined. It emits the phone full-property snapshot and
 * compatibility-state requests, validates the first full Watch snapshot,
 * and stops at {@code PAIRING_TRANSPORT_COMPLETE}. It has no action capable
 * of writing IsPaired, activating the Watch, or completing setup.</p>
 */
final class AppleWatchInitialSetupIdsAdapter implements AutoCloseable {
    private static final String PRODUCT_TYPE =
            "productType";
    private static final String MODEL_NUMBER =
            "modelNumber";
    private static final String CHIP_ID =
            "chipID";
    private static final String CAPABILITIES =
            "capabilities";

    /**
     * Optional live-session diagnostics hook (set by the root HAL host).
     * Never required for correctness; must never throw.
     */
    static volatile java.util.function.Consumer<String> diagnosticLogger;

    private static void logDiagnostic(
            String message) {
        java.util.function.Consumer<String> logger =
                diagnosticLogger;
        if (logger == null) {
            return;
        }
        try {
            logger.accept(
                    message);
        } catch (RuntimeException ignored) {
            // Diagnostics must never affect the session.
        }
    }

    enum ActionType {
        SEND_COMPATIBILITY_STATE,
        SEND_PHONE_FULL_SNAPSHOT,
        SEND_PHONE_PROPERTY_RESPONSE,
        SEND_PROPERTY_REQUEST,
        PAIRING_TRANSPORT_COMPLETE
    }

    private final AppleWatchSetupCoordinator coordinator =
            new AppleWatchSetupCoordinator();
    private final long generation;
    private final WatchSetupMetadataCodec.Identifier identifier;
    private final WatchSetupMetadataCodec.ExtendedMetadata metadata;
    private final int effectiveRemoteMaxPairingVersion;

    private boolean started;
    private boolean closed;
    private boolean snapshotSent;
    private boolean phoneSnapshotAckObserved;
    private boolean watchCredentialsReceived;
    private Long phoneCheckSequence;
    private Long phoneSnapshotSequence;
    private final Map<String, NanoRegistryPropertyCodec.Property> accumulatedWatchProperties =
            new HashMap<>();

    AppleWatchInitialSetupIdsAdapter(
            long generation,
            WatchSetupMetadataCodec.Identifier identifier,
            WatchSetupMetadataCodec.ExtendedMetadata metadata,
            int effectiveRemoteMaxPairingVersion) {
        if (generation <= 0
                || identifier == null
                || metadata == null
                || effectiveRemoteMaxPairingVersion < 0
                || effectiveRemoteMaxPairingVersion > 0xffff) {
            throw new IllegalArgumentException(
                    "Initial IDS setup evidence is incomplete");
        }
        this.generation = generation;
        this.identifier = identifier;
        this.metadata = metadata;
        this.effectiveRemoteMaxPairingVersion =
                effectiveRemoteMaxPairingVersion;
    }

    synchronized List<Action> startAfterIdsDataReady() {
        if (started) {
            return List.of();
        }
        started = true;

        requireOnly(
                coordinator.beginUltra2Generation(
                        generation,
                        identifier,
                        metadata),
                AppleWatchSetupCoordinator.ActionType
                        .START_NETWORK_RELAY_AUTH);
        requireEmpty(
                coordinator.onActiveChanged(
                        generation,
                        true));
        requireEmpty(
                coordinator.onAuthDataReady(
                        generation));
        requireOnly(
                coordinator.onNetworkRelayCompleted(
                        generation,
                        true,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .CREATE_LOCAL_PAIRING_STORE);
        requireOnly(
                coordinator.onLocalPairingStoreCreated(
                        generation,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .ADD_IDS_PAIRED_DEVICE);
        requireOnly(
                coordinator.onIdsPeerAdded(
                        generation,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .INITIALIZE_IDS_CHANNELS);
        requireEmpty(
                coordinator.onIdsChannelsInitialized(
                        generation,
                        true));
        // Channel handshakes and device-info delivery do not prove Watch NR
        // registration. Promote this observation only with validated Watch properties.

        List<Action> actions =
                new ArrayList<>();
        actions.addAll(
                mapActions(
                        coordinator.onIdsCompatibilityVersion(
                                generation,
                                effectiveRemoteMaxPairingVersion)));
        // Live watchOS 26.2 SREJ'd TxSeq 1/2/5 when Check and the ~1.5 KiB
        // snapshot shared one ERTM window. iOS sends Check from
        // enterCompatibilityState and the snapshot from idsSendProperties;
        // they are not one burst. Snapshot waits for the Check Alloy ACK.
        return immutable(
                actions);
    }

    /**
     * Publishes the phone full snapshot only after Class-D Check is ACK'd.
     */
    synchronized List<Action> onPhoneCheckAcknowledged() {
        requireStarted();
        if (snapshotSent
                || coordinator.snapshot().transportCompletionIssued) {
            return List.of();
        }
        snapshotSent = true;
        return List.of(
                Action.simple(
                        generation,
                        ActionType.SEND_PHONE_FULL_SNAPSHOT));
    }

    /**
     * Observes the Class-C Alloy ACK of the phone snapshot.
     *
     * <p>After publishing our own state, explicitly request the Watch's full
     * properties via the native type-4 request/response API. Root starts this
     * adapter only after the mutual IDS device-info exchange. The old 0.2.125
     * poll ran before that exchange existed; its later disconnect did not
     * establish that polling itself was invalid. Neither ACK advances setup.</p>
     */
    synchronized List<Action> onPhoneSnapshotAcknowledged() {
        requireStarted();
        if (!snapshotSent || phoneSnapshotAckObserved
                || coordinator.snapshot().transportCompletionIssued) {
            return List.of();
        }
        phoneSnapshotAckObserved = true;
        return List.of(Action.simple(generation, ActionType.SEND_PROPERTY_REQUEST));
    }

    synchronized boolean snapshotSent() {
        return snapshotSent;
    }

    synchronized boolean phoneSnapshotAckObserved() {
        return phoneSnapshotAckObserved;
    }

    synchronized boolean watchCredentialsReceived() {
        return watchCredentialsReceived;
    }

    synchronized List<Action> onWatchCredentialsReceived() {
        requireStarted();
        watchCredentialsReceived = true;
        return List.of();
    }

    /** Records the controller-allocated sequence, before the response can arrive. */
    synchronized void onPhoneMessageQueued(ActionType type, long sequence) {
        requireStarted();
        if (sequence < 0 || sequence > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid initial-setup sequence");
        }
        if (type == ActionType.SEND_COMPATIBILITY_STATE && !snapshotSent) {
            phoneCheckSequence = sequence;
        } else if (type == ActionType.SEND_PHONE_FULL_SNAPSHOT) {
            phoneSnapshotSequence = sequence;
        }
    }

    synchronized List<Action> onAcknowledgement(String service, long sequence) {
        requireStarted();
        if (phoneCheckSequence != null && phoneCheckSequence == sequence
                && (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(service)
                || IdsUtunConnectionName.isClassDIdentifier(service))) {
            phoneCheckSequence = null;
            return onPhoneCheckAcknowledged();
        }
        if (phoneSnapshotSequence != null && phoneSnapshotSequence == sequence
                && (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(service)
                || IdsUtunConnectionName.isClassCIdentifier(service))) {
            phoneSnapshotSequence = null;
            return onPhoneSnapshotAcknowledged();
        }
        return List.of();
    }

    /** Consumes Watch NanoRegistry dumps, including DataMessage payloads. */
    synchronized List<Action> accept(
            IdsModernSessionCoordinator.SessionEvent event) {
        requireStarted();
        if (event != null && event.type
                == IdsModernSessionCoordinator.EventType.ACK_RECEIVED) {
            return onAcknowledgement(event.service, event.sequence);
        }
        if (event == null
                || !isNanoRegistryLane(
                        event)) {
            return List.of();
        }
        if ("idscredentials".equals(event.topic)
                || (event.topic != null && event.topic.contains("credential"))) {
            return onWatchCredentialsReceived();
        }
        // PBBridge and PairedSync share the Class-C IDS service with
        // NanoRegistry. Protobuf type 4 is gizmoDidFinishActivating on
        // PBBridge and PropertyRequest on NanoRegistry. Live 0.2.206
        // answered each CanBegin with that empty type 4 and this adapter
        // sent a phone property response (actions=1) for it.
        if (!isNanoRegistryTopic(event.topic)) {
            return List.of();
        }
        byte[] payload =
                event.payload();
        try {
            if (event.type
                    == IdsModernSessionCoordinator.EventType
                    .DATA_RECEIVED) {
                NanoRegistryPropertyCodec.PropertiesChanged changed =
                        decodePropertiesChangedAllowingPrefix(
                                payload);
                if (changed != null) {
                    try {
                        return acceptClassC(
                                changed);
                    } catch (RuntimeException ignored) {
                        return List.of();
                    } finally {
                        changed.destroy();
                    }
                }
                NanoRegistryPropertyCodec.PropertyResponse response =
                        decodePropertyResponseAllowingPrefix(
                                payload);
                if (response != null) {
                    try {
                        return acceptPropertyResponse(
                                response);
                    } catch (RuntimeException ignored) {
                        return List.of();
                    } finally {
                        response.destroy();
                    }
                }
                return List.of();
            }
            if (event.type
                    != IdsModernSessionCoordinator.EventType
                    .PROTOBUF_RECEIVED) {
                return List.of();
            }
            if (event.protobufType
                    == NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED) {
                NanoRegistryPropertyCodec.PropertiesChanged changed =
                        decodePropertiesChangedAllowingPrefix(
                                payload);
                if (changed == null) {
                    try {
                        changed =
                                NanoRegistryPropertyCodec
                                        .decodePropertiesChanged(
                                                payload);
                    } catch (IllegalArgumentException decodeFailed) {
                        logDiagnostic(
                                "INITIAL NANO DIAG: Class-C type=2 "
                                        + "payloadBytes="
                                        + payload.length
                                        + " decode FAILED: "
                                        + decodeFailed.getMessage());
                        return List.of();
                    }
                }
                logDiagnostic(
                        "INITIAL NANO DIAG: Class-C type=2 decoded: "
                                + "payloadBytes="
                                + payload.length
                                + " properties="
                                + changed.properties.size()
                                + " thisIsAllOfThem="
                                + changed.thisIsAllOfThem);
                try {
                    java.util.List<Action> produced =
                            acceptClassC(
                                    changed);
                    logDiagnostic(
                            "INITIAL NANO DIAG: Class-C type=2 acceptClassC "
                                    + "actions="
                                    + produced.size()
                                    + " relevantAccumulated="
                                    + accumulatedWatchProperties.size()
                                    + " relevantNames="
                                    + accumulatedWatchProperties.keySet()
                                    + " identityValidated="
                                    + coordinator.snapshot().identityValidated
                                    + " phase="
                                    + coordinator.snapshot().phase);
                    return produced;
                } catch (RuntimeException acceptFailed) {
                    logDiagnostic(
                            "INITIAL NANO DIAG: Class-C type=2 acceptClassC "
                                    + "THREW: "
                                    + acceptFailed.getMessage()
                                    + " relevantAccumulated="
                                    + accumulatedWatchProperties.size()
                                    + " relevantNames="
                                    + accumulatedWatchProperties.keySet());
                    return List.of();
                } finally {
                    if (changed != null) {
                        changed.destroy();
                    }
                }
            }
            if (event.protobufType
                    == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                    && !event.response) {
                String requestIdentifier =
                        event.messageUuid;
                if (requestIdentifier == null
                        || requestIdentifier.isBlank()) {
                    return List.of();
                }
                try {
                    return onWatchPropertyRequest(
                            requestIdentifier);
                } catch (IllegalArgumentException ignored) {
                    return List.of();
                }
            }
            if (event.protobufType
                    == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                    && event.response) {
                NanoRegistryPropertyCodec.PropertyResponse response =
                        decodePropertyResponseAllowingPrefix(
                                payload);
                if (response == null) {
                    try {
                        response =
                                NanoRegistryPropertyCodec
                                        .decodePropertyResponse(
                                                payload);
                    } catch (IllegalArgumentException ignored) {
                        return List.of();
                    }
                }
                try {
                    return acceptPropertyResponse(
                            response);
                } catch (IllegalArgumentException ignored) {
                    return List.of();
                } finally {
                    if (response != null) {
                        response.destroy();
                    }
                }
            }
            return List.of();
        } finally {
            java.util.Arrays.fill(
                    payload,
                    (byte) 0);
        }
    }

    /** Package-visible deterministic entry point for offline tests. */
    synchronized List<Action> acceptClassC(
            NanoRegistryPropertyCodec.PropertiesChanged changed) {
        requireStarted();
        if (changed == null) {
            throw new IllegalArgumentException(
                    "Watch Class-C property change is absent");
        }
        rememberProperties(changed.properties, changed.thisIsAllOfThem);
        try {
            return acceptWatchIdentityProperties(
                    new ArrayList<>(accumulatedWatchProperties.values()));
        } catch (IllegalArgumentException incomplete) {
            if (!changed.thisIsAllOfThem) {
                return List.of();
            }
            throw incomplete;
        }
    }

    synchronized List<Action> acceptPropertyResponse(
            NanoRegistryPropertyCodec.PropertyResponse response) {
        requireStarted();
        if (response == null) {
            throw new IllegalArgumentException(
                    "Watch Class-C property response is absent");
        }
        rememberProperties(response.properties, true);
        return acceptWatchIdentityProperties(
                new ArrayList<>(accumulatedWatchProperties.values()));
    }

    /**
     * iOS 26.6 {@code idsHandlePropertyRequest} replies with
     * {@code sendPropertyResponse} of the local MiniStore, echoing the
     * incoming request UUID.
     */
    synchronized List<Action> onWatchPropertyRequest(
            String requestIdentifier) {
        requireStarted();
        if (requestIdentifier == null
                || requestIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "Watch PropertyRequest identifier is absent");
        }
        return List.of(
                Action.propertyResponse(
                        generation,
                        requestIdentifier));
    }

    private List<Action> acceptWatchIdentityProperties(
            List<NanoRegistryPropertyCodec.Property> properties) {
        if (!accumulatedWatchProperties.keySet().containsAll(List.of(PRODUCT_TYPE, MODEL_NUMBER, CHIP_ID, CAPABILITIES))) {
            return List.of();
        }
        WatchIdentity identity =
                decodeWatchIdentity(
                        properties);
        List<Action> actions = new ArrayList<>(mapActions(
                coordinator.onFullWatchClassCSnapshot(
                        generation,
                        identity.productType,
                        identity.modelNumber,
                        identity.chipId,
                        identity.capabilities)));
        if (coordinator.snapshot().identityValidated) {
            actions.addAll(mapActions(coordinator.onIdsAccountAndDeviceReady(generation)));
        }
        return immutable(actions);
    }

    private void rememberProperties(List<NanoRegistryPropertyCodec.Property> properties, boolean full) {
        if (full) {
            accumulatedWatchProperties.values().forEach(NanoRegistryPropertyCodec.Property::destroy);
            accumulatedWatchProperties.clear();
        }
        for (NanoRegistryPropertyCodec.Property property : properties) {
            if (property == null || !isRelevant(property.name)) continue;
            NanoRegistryPropertyCodec.Property previous = accumulatedWatchProperties.remove(property.name);
            if (previous != null) previous.destroy();
            if (property.value != null && !property.value.isError) {
                // The received message is destroyed by the caller immediately
                // after accept(). Retain our own capabilities/UUID byte arrays.
                // isSet describes NSSet versus NSArray, not property presence.
                accumulatedWatchProperties.put(property.name, property.copy());
            }
        }
    }

    synchronized AppleWatchSetupCoordinator.Snapshot snapshot() {
        return coordinator.snapshot();
    }

    synchronized WatchLocaleSnapshot watchLocaleSnapshot() {
        requireStarted();
        if (!coordinator.snapshot().identityValidated) {
            throw new IllegalStateException("Watch identity must be validated before using its language");
        }
        var languages = accumulatedWatchProperties.get("preferredLanguages");
        var locale = accumulatedWatchProperties.get("currentUserLocale");
        return WatchLocaleSnapshot.fromProperties(languages == null ? null : languages.value,
                locale == null ? null : locale.value);
    }

    synchronized PairedSyncCapabilityState.Status pairedSyncCapabilityStatus() {
        requireStarted();
        NanoRegistryPropertyCodec.Property capabilities = accumulatedWatchProperties.get(CAPABILITIES);
        if (capabilities == null || !coordinator.snapshot().identityValidated) {
            return PairedSyncCapabilityState.Status.UNKNOWN;
        }
        var snapshot = new NanoRegistryPropertyCodec.PropertiesChanged(false,
                List.of(capabilities.copy()), null);
        try {
            return new PairedSyncCapabilityState().apply(snapshot);
        } finally {
            snapshot.destroy();
        }
    }

    private List<Action> mapActions(
            List<AppleWatchSetupCoordinator.Action> source) {
        List<Action> mapped =
                new ArrayList<>();
        for (AppleWatchSetupCoordinator.Action action : source) {
            switch (action.type) {
                case SEND_COMPATIBILITY_STATE ->
                        mapped.add(
                                Action.compatibility(
                                        generation,
                                        action.compatibilityState,
                                        action.runtimeWatchPairingVersion));
                case PAIRING_TRANSPORT_COMPLETE ->
                        mapped.add(
                                Action.simple(
                                        generation,
                                        ActionType
                                                .PAIRING_TRANSPORT_COMPLETE));
                case REPORT_FAILURE -> throw new IllegalStateException(
                        "Initial IDS setup rejected evidence: "
                                + action.failure);
                default -> throw new IllegalStateException(
                        "Initial IDS setup produced an unexpected action: "
                                + action.type);
            }
        }
        return immutable(
                mapped);
    }

    private WatchIdentity decodeWatchIdentity(
            List<NanoRegistryPropertyCodec.Property> properties) {
        Map<String, NanoRegistryPropertyCodec.PropertyValue> relevant =
                new HashMap<>();
        for (NanoRegistryPropertyCodec.Property property :
                properties) {
            if (property == null || !isRelevant(
                    property.name)) {
                continue;
            }
            relevant.put(
                    property.name,
                    property.value);
        }

        String productType = requireString(relevant.get(PRODUCT_TYPE), PRODUCT_TYPE);
        String modelNumber = requireString(relevant.get(MODEL_NUMBER), MODEL_NUMBER);
        long chipId = requireInteger(relevant.get(CHIP_ID), CHIP_ID);
        Set<String> capabilities = NanoRegistryUuidSetCodec.decode(relevant.get(CAPABILITIES));

        return new WatchIdentity(
                productType,
                modelNumber,
                chipId,
                capabilities);
    }

    private static boolean isRelevant(
            String name) {
        return PRODUCT_TYPE.equals(
                name)
                || MODEL_NUMBER.equals(
                name)
                || CHIP_ID.equals(
                name)
                || CAPABILITIES.equals(
                name)
                || "preferredLanguages".equals(name)
                || "currentUserLocale".equals(name);
    }

    private static String requireString(
            NanoRegistryPropertyCodec.PropertyValue value,
            String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Watch Class-C "
                            + name
                            + " is absent");
        }
        if (value.stringValue != null && !value.stringValue.isBlank()) {
            return value.stringValue;
        }
        if (value.dataValue != null && value.dataValue.length > 0) {
            String decoded =
                    new String(
                            value.dataValue,
                            java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!decoded.isBlank()) {
                return decoded;
            }
        }
        if (value.numberValue != null) {
            if (value.numberValue.int32Value != null) {
                return String.valueOf(value.numberValue.int32Value);
            }
            if (value.numberValue.int64Value != null) {
                return String.valueOf(value.numberValue.int64Value);
            }
        }
        if (value.uuidValue != null && value.uuidValue.length == 16) {
            ByteBuffer bytes = ByteBuffer.wrap(value.uuidValue);
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        }
        throw new IllegalArgumentException(
                "Watch Class-C "
                        + name
                        + " is not a canonical string");
    }

    private static long requireInteger(
            NanoRegistryPropertyCodec.PropertyValue value,
            String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Watch Class-C "
                            + name
                            + " is absent");
        }
        if (value.numberValue != null) {
            NanoRegistryPropertyCodec.NumberValue number =
                    value.numberValue;
            if (number.int32Value != null) {
                if (number.hasIsShortOrChar && number.isShortOrChar) {
                    return number.hasIsUnsigned && number.isUnsigned
                            ? (number.int32Value & 0xffffL)
                            : (long) number.int32Value.shortValue();
                }
                return number.hasIsUnsigned && number.isUnsigned
                        ? Integer.toUnsignedLong(number.int32Value)
                        : number.int32Value.longValue();
            }
            if (number.int64Value != null) {
                return number.int64Value;
            }
            if (number.doubleValue != null) {
                return (long) number.doubleValue.doubleValue();
            }
            if (number.floatValue != null) {
                return (long) number.floatValue.floatValue();
            }
        }
        if (value.stringValue != null && !value.stringValue.isBlank()) {
            try {
                String str = value.stringValue.trim();
                if (str.startsWith("0x") || str.startsWith("0X")) {
                    return Long.parseUnsignedLong(str.substring(2), 16);
                }
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
            }
        }
        if (value.dataValue != null && value.dataValue.length > 0) {
            long parsed = 0;
            for (byte b : value.dataValue) {
                parsed = (parsed << 8) | (b & 0xffL);
            }
            return parsed;
        }
        throw new IllegalArgumentException(
                "Watch Class-C "
                        + name
                        + " is not an integer scalar");
    }

    private static boolean isNanoRegistryTopic(String topic) {
        return NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(topic)
                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(topic)
                || "nano-class-c".equals(topic)
                || "nano-class-d".equals(topic);
    }

    private static boolean isNanoRegistryLane(
            IdsModernSessionCoordinator.SessionEvent event) {
        String topic =
                event.topic;
        String service =
                event.service;
        return NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(
                topic)
                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(
                        topic)
                || "nano-class-c".equals(
                        topic)
                || "nano-class-d".equals(
                        topic)
                || containsClassLane(
                        topic)
                || containsClassLane(
                        service);
    }

    private static boolean containsClassLane(
            String value) {
        if (value == null) {
            return false;
        }
        String lower =
                value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(
                "class-c")
                || lower.contains(
                        "class-d")
                || value.endsWith(
                        "-C")
                || value.endsWith(
                        "-D");
    }

    private static NanoRegistryPropertyCodec.PropertiesChanged
            decodePropertiesChangedAllowingPrefix(
                    byte[] payload) {
        if (payload == null
                || payload.length == 0) {
            return null;
        }
        int[] offsets =
                new int[]{
                        0,
                        2,
                        3};
        for (int offset : offsets) {
            if (payload.length <= offset) {
                continue;
            }
            byte[] slice =
                    offset == 0
                            ? payload
                            : java.util.Arrays.copyOfRange(
                                    payload,
                                    offset,
                                    payload.length);
            try {
                return NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                slice);
            } catch (IllegalArgumentException ignored) {
                // WatchWitch: DataMessage protobufs may have a 2- or 3-byte
                // prefix. Keep scanning.
            } finally {
                if (offset != 0) {
                    java.util.Arrays.fill(
                            slice,
                            (byte) 0);
                }
            }
        }
        return null;
    }

    private static NanoRegistryPropertyCodec.PropertyResponse
            decodePropertyResponseAllowingPrefix(
                    byte[] payload) {
        if (payload == null
                || payload.length == 0) {
            return null;
        }
        int[] offsets =
                new int[]{
                        0,
                        2,
                        3};
        for (int offset : offsets) {
            if (payload.length <= offset) {
                continue;
            }
            byte[] slice =
                    offset == 0
                            ? payload
                            : java.util.Arrays.copyOfRange(
                                    payload,
                                    offset,
                                    payload.length);
            try {
                return NanoRegistryPropertyCodec
                        .decodePropertyResponse(
                                slice);
            } catch (IllegalArgumentException ignored) {
                // WatchWitch: DataMessage protobufs may have a 2- or 3-byte
                // prefix. Keep scanning.
            } finally {
                if (offset != 0) {
                    java.util.Arrays.fill(
                            slice,
                            (byte) 0);
                }
            }
        }
        return null;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        accumulatedWatchProperties.values().forEach(NanoRegistryPropertyCodec.Property::destroy);
        accumulatedWatchProperties.clear();
    }

    private void requireStarted() {
        if (!started || closed) {
            throw new IllegalStateException(
                    "Initial IDS setup has not reached the data lanes");
        }
    }

    private static void requireOnly(
            List<AppleWatchSetupCoordinator.Action> actions,
            AppleWatchSetupCoordinator.ActionType expected) {
        if (actions.size() != 1
                || actions.get(
                0).type != expected) {
            throw new IllegalStateException(
                    "Initial setup prerequisite ordering changed");
        }
    }

    private static void requireEmpty(
            List<AppleWatchSetupCoordinator.Action> actions) {
        if (!actions.isEmpty()) {
            throw new IllegalStateException(
                    "Initial setup prerequisite unexpectedly emitted work");
        }
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
        final String outgoingResponseIdentifier;

        private Action(
                long generation,
                ActionType type,
                Integer compatibilityState,
                Integer runtimeWatchPairingVersion,
                String outgoingResponseIdentifier) {
            this.generation = generation;
            this.type = type;
            this.compatibilityState = compatibilityState;
            this.runtimeWatchPairingVersion =
                    runtimeWatchPairingVersion;
            this.outgoingResponseIdentifier =
                    outgoingResponseIdentifier;
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
                Integer state,
                Integer runtimeWatchPairingVersion) {
            if (state == null
                    || runtimeWatchPairingVersion == null) {
                throw new IllegalArgumentException(
                        "Compatibility action is incomplete");
            }
            return new Action(
                    generation,
                    ActionType.SEND_COMPATIBILITY_STATE,
                    state,
                    runtimeWatchPairingVersion,
                    null);
        }

        private static Action propertyResponse(
                long generation,
                String outgoingResponseIdentifier) {
            if (outgoingResponseIdentifier == null
                    || outgoingResponseIdentifier.isBlank()) {
                throw new IllegalArgumentException(
                        "Watch PropertyRequest identifier is absent");
            }
            return new Action(
                    generation,
                    ActionType.SEND_PHONE_PROPERTY_RESPONSE,
                    null,
                    null,
                    outgoingResponseIdentifier);
        }

        NanoRegistryClassDCodec.PairingModeRequest
                compatibilityMessage() {
            if (type
                    != ActionType.SEND_COMPATIBILITY_STATE
                    || compatibilityState == null
                    || runtimeWatchPairingVersion == null) {
                throw new IllegalStateException(
                        "Initial action is not a compatibility send");
            }
            return NanoRegistryClassDCodec
                    .PairingModeRequest
                    .modernIos26_6Ultra2(
                            compatibilityState,
                            runtimeWatchPairingVersion);
        }

        NanoRegistryPropertyCodec.PropertiesChanged phoneFullSnapshot(
                long unixMilliseconds,
                byte[] bluetoothMacAddress) {
            if (type
                    != ActionType.SEND_PHONE_FULL_SNAPSHOT) {
                throw new IllegalStateException(
                        "Initial action is not a phone full snapshot");
            }
            BluetoothLocalIdentity.requireStaticRandomAddress(
                    bluetoothMacAddress);
            return PhonePropertySnapshot26_6.fullSnapshot(
                    companionPropertyValues(
                            bluetoothMacAddress),
                    PhonePropertySnapshot26_6
                            .bornOnFromUnixMilliseconds(
                                    unixMilliseconds));
        }

        /**
         * Phone MiniStore for a paired link that already finished initial
         * setup. The first-setup adapter is not running, but the Watch still
         * asks for these properties on a later connection.
         */
        static NanoRegistryPropertyCodec.PropertyResponse livePhonePropertyResponse(
                byte[] bluetoothMacAddress) {
            BluetoothLocalIdentity.requireStaticRandomAddress(
                    bluetoothMacAddress);
            return PhonePropertySnapshot26_6.propertyResponse(
                    companionPropertyValues(
                            bluetoothMacAddress));
        }

        static NanoRegistryPropertyCodec.PropertiesChanged livePhoneFullSnapshot(
                long unixMilliseconds,
                byte[] bluetoothMacAddress) {
            BluetoothLocalIdentity.requireStaticRandomAddress(
                    bluetoothMacAddress);
            return PhonePropertySnapshot26_6.fullSnapshot(
                    companionPropertyValues(
                            bluetoothMacAddress),
                    PhonePropertySnapshot26_6.bornOnFromUnixMilliseconds(
                            unixMilliseconds));
        }

        NanoRegistryPropertyCodec.PropertyResponse propertyResponse(
                byte[] bluetoothMacAddress) {
            if (type
                    != ActionType.SEND_PHONE_PROPERTY_RESPONSE
                    || outgoingResponseIdentifier == null
                    || outgoingResponseIdentifier.isBlank()) {
                throw new IllegalStateException(
                        "Initial action is not a phone property response");
            }
            BluetoothLocalIdentity.requireStaticRandomAddress(
                    bluetoothMacAddress);
            return PhonePropertySnapshot26_6.propertyResponse(
                    companionPropertyValues(
                            bluetoothMacAddress));
        }

        NanoRegistryPropertyCodec.PropertyRequest propertyRequest() {
            if (type
                    != ActionType.SEND_PROPERTY_REQUEST) {
                throw new IllegalStateException(
                        "Initial action is not a property request");
            }
            return new NanoRegistryPropertyCodec.PropertyRequest();
        }
    }

    private static Map<String, NanoRegistryPropertyCodec.PropertyValue>
            companionPropertyValues(
                    byte[] bluetoothMacAddress) {
        Map<String, NanoRegistryPropertyCodec.PropertyValue> props =
                new HashMap<>();
        props.put(
                "bluetoothMACAddress",
                NanoRegistryPropertyCodec.PropertyValue.data(
                        bluetoothMacAddress));
        props.put(
                PRODUCT_TYPE,
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.MODEL));
        props.put(
                "localizedModel",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.LOCALIZED_MODEL));
        props.put(
                "hwModelStr",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.HW_MODEL_STRING));
        props.put(
                "marketingProductName",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.MARKETING_PRODUCT_NAME));
        props.put(
                "class",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        "iPhone"));
        props.put(
                "deviceNameString",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.DEVICE_NAME));
        props.put(
                "marketingVersion",
                NanoRegistryPropertyCodec.PropertyValue.string(
                        IosCompanionProfile26_6.PRODUCT_VERSION));
        props.put(
                "buildString",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                IosCompanionProfile26_6.RELEASE_TYPE,
                                false,
                                null)));
        props.put(
                "CPUType",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                IosCompanionProfile26_6.CPU_TYPE_ARM64,
                                false,
                                null)));
        props.put(
                "CPUSubType",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                IosCompanionProfile26_6.CPU_SUBTYPE_ARM64E,
                                false,
                                null)));
        props.put(
                "chipID",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt64(
                                IosCompanionProfile26_6.CHIP_ID,
                                true)));
        props.put(
                "screenScale",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                IosCompanionProfile26_6.SCREEN_SCALE,
                                false,
                                null)));
        props.put(
                "greenTea",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                false)));
        props.put(
                "isAltAccount",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                false)));
        props.put(
                "deviceInDemoMode",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                false)));
        props.put(
                "isInternalInstall",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                false)));
        props.put(
                "hasSEP",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                true)));
        props.put(
                "hasSecureElement",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                true)));
        props.put(
                "signingFuse",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(
                                true)));
        props.put(
                "pairedDeviceCount",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                1,
                                false,
                                null)));
        props.put(
                "_supportedPairingStrategy",
                NanoRegistryPropertyCodec.PropertyValue.number(
                        NanoRegistryPropertyCodec.NumberValue.ofInt32(
                                4,
                                false,
                                null)));
        props.put(
                CAPABILITIES,
                NanoRegistryUuidSetCodec.encode(
                        IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS));
        return props;
    }

    private static final class WatchIdentity {
        final String productType;
        final String modelNumber;
        final long chipId;
        final Set<String> capabilities;

        WatchIdentity(
                String productType,
                String modelNumber,
                long chipId,
                Set<String> capabilities) {
            this.productType = productType;
            this.modelNumber = modelNumber;
            this.chipId = chipId;
            this.capabilities = capabilities;
        }
    }
}
