package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * PBBridge protobufs required by fresh Watch setup on iOS/watchOS 26.6.
 *
 * <p>Activation message type 2 is deliberately represented by two separate
 * classes because Apple sends it as a non-response in both directions. The
 * wire type alone cannot identify which endpoint produced it.</p>
 */
final class PbBridgeCodec {
    static final String SERVICE =
            IdsApplicationRoute.PB_BRIDGE_SERVICE;
    static final int MESSAGE_PRIORITY = 300;

    static final int TYPE_PROXY_ACTIVATION = 2;
    // Type 3 is direction-dependent: Watch reports BeganActivating;
    // phone sends PBBProtoPushControllerType.
    static final int TYPE_BEGAN_ACTIVATING = 3;
    static final int TYPE_PUSH_CONTROLLER = 3;
    static final int CONTROLLER_BUDDY_FINISHED = 10;
    static final int TYPE_ACTIVATION_SUCCEEDED = 4;
    static final int TYPE_CAN_BEGIN_ACTIVATION = 11;
    static final int TYPE_ACTIVATION_FAILED = 12;
    // iOS 26.6 PBBridgeSupport -[PBBridgeCompanionController
    // tellGizmoToRetryActivation] sends _sendRemoteCommandWithMessageID:0xf
    // withArguments:nil; on the Watch this drives
    // -[ActivationController retryActivation:] which resets the activation
    // state machine to Idle and starts activation unconditionally.
    static final int TYPE_RETRY_ACTIVATION = 15;
    static final int TYPE_PREPARE_INITIAL_SYNC_RESPONSE = 18;
    /**
     * Phone-to-Watch PBBProtoInitialSyncStateUpdate (watchOS 26.6 gizmo
     * message 19 -> updateSynchProgress: -> delegate setSyncProgress:andState:).
     * Wire: field 1 = progress (double), field 2 = state (uint32). SetupController only logs this update; final UI progress comes from
     * PSYSyncStateObserver and the BuddyFinished controller.
     */
    static final int TYPE_UPDATE_SYNC_PROGRESS = 19;
    static final int TYPE_LANGUAGE_AND_LOCALE_STATUS = 20;
    static final int TYPE_PREPARE_INITIAL_SYNC = 21;
    static final int TYPE_LANGUAGE_AND_LOCALE = 25;
    static final int TYPE_UPDATE_NANO_REGISTRY_NORMAL = 36;
    static final int TYPE_COMPUTED_TIME_ZONE = 111;

    static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    static final int MAX_STRING_LENGTH = 64 * 1024;
    static final int MAX_LANGUAGES = 64;

    /**
     * Optional live-session diagnostics hook (set by the root HAL host).
     * Never required for correctness; must never throw.
     */
    static volatile java.util.function.Consumer<String> diagnosticLogger;

    private PbBridgeCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        return PbBridgeWireCodec.encode(
                message);
    }

    static IdsSocketPairCodec.ProtobufMessage envelope(
            long sequence,
            int streamId,
            int flags,
            String peerResponseIdentifier,
            String messageUuid,
            String topic,
            ApplicationMessage message,
            Long expirySeconds) {
        return PbBridgeWireCodec.envelope(
                sequence,
                streamId,
                flags,
                peerResponseIdentifier,
                messageUuid,
                topic,
                message,
                expirySeconds);
    }

    static void validateEnvelope(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        PbBridgeWireCodec.validateEnvelope(
                envelope);
    }

    static ActivationFetchRequest decodeActivationFetch(
            byte[] payload) {
        return PbBridgeWireCodec.decodeActivationFetch(
                payload);
    }

    static ActivationData decodeActivationData(
            byte[] payload) {
        return PbBridgeWireCodec.decodeActivationData(
                payload);
    }

    static ActivationFailed decodeActivationFailed(
            byte[] payload) {
        return PbBridgeWireCodec.decodeActivationFailed(
                payload);
    }

    static LanguageAndLocaleStatus decodeLanguageAndLocaleStatus(
            byte[] payload) {
        return PbBridgeWireCodec.decodeLanguageAndLocaleStatus(
                payload);
    }

    static LanguageAndLocale decodeLanguageAndLocale(
            byte[] payload) {
        return PbBridgeWireCodec.decodeLanguageAndLocale(
                payload);
    }

    static ComputedTimeZone decodeComputedTimeZone(
            byte[] payload) {
        return PbBridgeWireCodec.decodeComputedTimeZone(
                payload);
    }

    abstract static class ApplicationMessage {
        abstract int protobufType();

        abstract boolean response();

        abstract void requireValid();

        void destroy() {
        }
    }

    /** Watch-to-phone MobileActivation began event. */
    static final class BeganActivating
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_BEGAN_ACTIVATING;
        }
    }

    /** Watch-to-phone MobileActivation success event. */
    static final class ActivationSucceeded
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_ACTIVATION_SUCCEEDED;
        }
    }

    /** Watch-to-phone MobileActivation failure event. */
    static final class ActivationFailed
            extends ApplicationMessage {
        final String failureDescription;

        ActivationFailed(
                String failureDescription) {
            this.failureDescription =
                    failureDescription;
            requireValid();
        }

        String failureDescription() {
            return failureDescription;
        }

        @Override
        int protobufType() {
            return TYPE_ACTIVATION_FAILED;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            PbBridgeWireCodec.requireString(
                    failureDescription,
                    true,
                    "activation failure description");
        }
    }

    static final class CanBeginActivation
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_CAN_BEGIN_ACTIVATION;
        }
    }

    static final class RetryActivation
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_RETRY_ACTIVATION;
        }
    }

    /** Watch-to-phone status for the independent type-25 operation. */
    static final class LanguageAndLocaleStatus
            extends ApplicationMessage {
        final int status;

        LanguageAndLocaleStatus(
                int status) {
            this.status =
                    status;
            requireValid();
        }

        int status() {
            return status;
        }

        boolean completedWithoutRelaunch() {
            return status == 1;
        }

        boolean completedAfterRelaunch() {
            return status == 2;
        }

        boolean recognizedCompletion() {
            return completedWithoutRelaunch()
                    || completedAfterRelaunch();
        }

        @Override
        int protobufType() {
            return TYPE_LANGUAGE_AND_LOCALE_STATUS;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (status < 0
                    || status > 0xffff) {
                throw new IllegalArgumentException(
                        "PBBridge language-and-locale status is invalid");
            }
        }
    }

    /** watchOS 26.6 Setup FUN_100019854 maps controller 10 to BuddyFinished. */
    static final class PushBuddyFinished extends ApplicationMessage {
        @Override int protobufType() { return TYPE_PUSH_CONTROLLER; }
        @Override boolean response() { return false; }
        @Override void requireValid() { }
    }

    /** Phone-to-Watch request; Watch answers with type 18. */
    static final class PrepareInitialSync
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_PREPARE_INITIAL_SYNC;
        }
    }

    /** Watch-to-phone response correlated to the type-21 request UUID. */
    static final class PrepareInitialSyncResponse
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_PREPARE_INITIAL_SYNC_RESPONSE;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    /** Phone-to-Watch locale update; field 3 is an opaque secure archive. */
    static final class LanguageAndLocale
            extends ApplicationMessage {
        List<String> appleLanguages;
        final String appleLocale;
        byte[] archivedPreferences;

        LanguageAndLocale(
                List<String> appleLanguages,
                String appleLocale,
                byte[] archivedPreferences) {
            this.appleLanguages =
                    appleLanguages == null
                            ? null
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            appleLanguages));
            this.appleLocale =
                    appleLocale;
            this.archivedPreferences =
                    archivedPreferences == null
                            ? null
                            : archivedPreferences.clone();
            requireValid();
        }

        List<String> appleLanguages() {
            requireValid();
            return appleLanguages;
        }

        String appleLocale() {
            requireValid();
            return appleLocale;
        }

        byte[] archivedPreferences() {
            requireValid();
            return archivedPreferences.clone();
        }

        @Override
        int protobufType() {
            return TYPE_LANGUAGE_AND_LOCALE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (appleLanguages == null
                    || appleLanguages.isEmpty()
                    || appleLanguages.size()
                    > MAX_LANGUAGES) {
                throw new IllegalArgumentException(
                        "PBBridge Apple languages are invalid");
            }
            long stringBytes = 0;
            for (String language :
                    appleLanguages) {
                PbBridgeWireCodec.requireString(
                        language,
                        false,
                        "Apple language");
                stringBytes +=
                        language.getBytes(
                                StandardCharsets.UTF_8).length;
            }
            PbBridgeWireCodec.requireString(
                    appleLocale,
                    true,
                    "Apple locale");
            stringBytes +=
                    appleLocale.getBytes(
                            StandardCharsets.UTF_8).length;
            if (archivedPreferences == null
                    || archivedPreferences.length == 0
                    || archivedPreferences.length
                    > MAX_PAYLOAD_LENGTH
                    || stringBytes
                    + archivedPreferences.length
                    > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived locale preferences are invalid");
            }
            LocalePreferencesArchiveCodec.Preferences decoded =
                    LocalePreferencesArchiveCodec.decode(
                            archivedPreferences);
            try {
                if (!appleLanguages.equals(
                        decoded.appleLanguages())
                        || !appleLocale.equals(
                        decoded.appleLocale())) {
                    throw new IllegalArgumentException(
                            "PBBridge locale fields do not match the archive");
                }
            } finally {
                decoded.destroy();
            }
        }

        @Override
        void destroy() {
            appleLanguages = null;
            PbBridgeWireCodec.wipe(
                    archivedPreferences);
            archivedPreferences = null;
        }
    }

    static final class UpdateNanoRegistryNormal
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_UPDATE_NANO_REGISTRY_NORMAL;
        }
    }

    /** Phone-to-Watch one-way initial-sync progress/state update. */
    static final class UpdateSyncProgress
            extends ApplicationMessage {
        final double progress;
        final int state;

        UpdateSyncProgress(
                double progress,
                int state) {
            this.progress =
                    progress;
            this.state =
                    state;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_UPDATE_SYNC_PROGRESS;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (Double.isNaN(progress)
                    || Double.isInfinite(progress)
                    || progress < 0.0
                    || progress > 1.0
                    || state < 0) {
                throw new IllegalArgumentException(
                        "PBBridge sync progress update is invalid");
            }
        }
    }

    /** Phone-to-Watch one-way timezone update. */
    static final class ComputedTimeZone
            extends ApplicationMessage {
        final String computedTimeZone;

        ComputedTimeZone(
                String computedTimeZone) {
            this.computedTimeZone =
                    computedTimeZone;
            requireValid();
        }

        String computedTimeZone() {
            return computedTimeZone;
        }

        @Override
        int protobufType() {
            return TYPE_COMPUTED_TIME_ZONE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            PbBridgeWireCodec.requireString(
                    computedTimeZone,
                    false,
                    "computed time zone");
        }
    }

    abstract static class EmptyRequest
            extends ApplicationMessage {
        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

    static final class ActivationFetchRequest
            extends ApplicationMessage {
        byte[] archivedRequest;

        ActivationFetchRequest(
                byte[] archivedRequest) {
            this.archivedRequest =
                    archivedRequest == null
                            ? null
                            : archivedRequest.clone();
            requireValid();
        }

        byte[] archivedRequest() {
            requireValid();
            return archivedRequest.clone();
        }

        @Override
        int protobufType() {
            return TYPE_PROXY_ACTIVATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (archivedRequest == null
                    || archivedRequest.length == 0
                    || archivedRequest.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived activation request is invalid");
            }
        }

        @Override
        void destroy() {
            PbBridgeWireCodec.wipe(
                    archivedRequest);
            archivedRequest =
                    null;
        }
    }

    static final class ActivationData
            extends ApplicationMessage {
        byte[] activationData;
        byte[] archivedResponseHeaders;

        ActivationData(
                byte[] activationData,
                byte[] archivedResponseHeaders) {
            this.activationData =
                    activationData == null
                            ? null
                            : activationData.clone();
            this.archivedResponseHeaders =
                    archivedResponseHeaders == null
                            ? null
                            : archivedResponseHeaders.clone();
            requireValid();
        }

        byte[] activationData() {
            requireValid();
            return activationData.clone();
        }

        byte[] archivedResponseHeaders() {
            requireValid();
            return archivedResponseHeaders.clone();
        }

        @Override
        int protobufType() {
            return TYPE_PROXY_ACTIVATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (activationData == null
                    || activationData.length == 0
                    || activationData.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge activation data is invalid");
            }
            if (archivedResponseHeaders == null
                    || archivedResponseHeaders.length == 0
                    || archivedResponseHeaders.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived response headers are invalid");
            }
            if ((long) activationData.length
                    + archivedResponseHeaders.length
                    > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge activation transfer is too large");
            }
        }

        @Override
        void destroy() {
            PbBridgeWireCodec.wipe(
                    activationData);
            activationData =
                    null;
            PbBridgeWireCodec.wipe(
                    archivedResponseHeaders);
            archivedResponseHeaders =
                    null;
        }
    }

}
