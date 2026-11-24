package dev.applewatchandroid.bridge;

import java.util.UUID;

/**
 * NanoRegistry Class D protobufs recovered from iOS/watchOS 26.6.
 *
 * <p>This class only encodes and decodes application messages. It does not
 * choose a pairing-mode state, send anything to a peer, or expose destructive
 * unpair operations through the Android UI.
 */
final class NanoRegistryClassDCodec {
    static final String SERVICE =
            NanoRegistryPropertyCodec.CLASS_D_SERVICE;
    static final int MESSAGE_PRIORITY = 300;

    static final int TYPE_DEVICE_WILL_UNPAIR = 1;
    static final int TYPE_PAIRING_MODE = 3;
    static final int TYPE_PING = 5;
    static final int TYPE_WATCH_MIGRATION_COMPLETION = 6;
    static final int TYPE_RTC_MIGRATION_METRIC_SESSION_ID = 9;
    static final int TYPE_PAIRING_SESSION_ID = 10;
    static final int TYPE_GRADUATION = 11;

    static final int COMPATIBILITY_STATE_INVALID = 0;
    /**
     * Transitional state while the phone can check protocol compatibility.
     *
     * <p>During fresh setup this state is only materialized when the remote
     * maximum protocol version is present before the remote model number.
     */
    static final int COMPATIBILITY_STATE_CHECK = 1;
    static final int COMPATIBILITY_STATE_UPDATE = 2;
    /**
     * Compatible, identified, not-yet-paired device.
     */
    static final int COMPATIBILITY_STATE_CONFIGURE = 3;
    /**
     * Fully paired, normal device; maps to the unrestricted IDS traffic
     * class.
     */
    static final int COMPATIBILITY_STATE_NORMAL = 4;
    static final int COMPATIBILITY_STATE_ALT_ACCOUNT = 5;

    static final int IOS_26_6_PHONE_MAX_VERSION = 26;
    static final int WATCHOS_26_6_REFERENCE_VERSION = 26;
    static final int IOS_26_6_PHONE_MIN_VERSION = 25;

    static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    static final int MAX_STRING_LENGTH = 64 * 1024;
    static final int MAX_ADVERTISED_NAME_LENGTH = 1024;
    static final int MAX_SESSION_ID_LENGTH = 1024;

    private NanoRegistryClassDCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        return NanoRegistryClassDWireCodec.encode(
                message);
    }

    static ApplicationMessage decode(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        return NanoRegistryClassDWireCodec.decode(
                envelope);
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
        return NanoRegistryClassDWireCodec.envelope(
                sequence,
                streamId,
                flags,
                peerResponseIdentifier,
                messageUuid,
                topic,
                message,
                expirySeconds);
    }

    static byte[] encodeDeviceWillUnpairRequest(
            DeviceWillUnpairRequest request) {
        return NanoRegistryClassDWireCodec.encodeDeviceWillUnpairRequest(
                request);
    }

    static DeviceWillUnpairRequest decodeDeviceWillUnpairRequest(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodeDeviceWillUnpairRequest(
                payload);
    }

    static DeviceWillUnpairResponse decodeDeviceWillUnpairResponse(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodeDeviceWillUnpairResponse(
                payload);
    }

    static byte[] encodePairingModeRequest(
            PairingModeRequest request) {
        return NanoRegistryClassDWireCodec.encodePairingModeRequest(
                request);
    }

    static PairingModeRequest decodePairingModeRequest(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodePairingModeRequest(
                payload);
    }

    static byte[] encodePairingModeResponse(
            PairingModeResponse response) {
        return NanoRegistryClassDWireCodec.encodePairingModeResponse(
                response);
    }

    static PairingModeResponse decodePairingModeResponse(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodePairingModeResponse(
                payload);
    }

    static byte[] encodePingRequest(
            PingRequest request) {
        return NanoRegistryClassDWireCodec.encodePingRequest(
                request);
    }

    static PingRequest decodePingRequest(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodePingRequest(
                payload);
    }

    static byte[] encodePingResponse(
            PingResponse response) {
        return NanoRegistryClassDWireCodec.encodePingResponse(
                response);
    }

    static PingResponse decodePingResponse(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodePingResponse(
                payload);
    }

    static byte[] encodeWatchMigrationCompletionRequest(
            WatchMigrationCompletionRequest request) {
        return NanoRegistryClassDWireCodec.encodeWatchMigrationCompletionRequest(
                request);
    }

    static WatchMigrationCompletionRequest
            decodeWatchMigrationCompletionRequest(
                    byte[] payload) {
        return NanoRegistryClassDWireCodec
                .decodeWatchMigrationCompletionRequest(
                        payload);
    }

    static RtcMigrationMetricSessionId
            decodeRtcMigrationMetricSessionId(
                    byte[] payload) {
        return NanoRegistryClassDWireCodec
                .decodeRtcMigrationMetricSessionId(
                        payload);
    }

    static PairingSessionId decodePairingSessionId(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodePairingSessionId(
                payload);
    }

    static GraduationRequest decodeGraduationRequest(
            byte[] payload) {
        return NanoRegistryClassDWireCodec.decodeGraduationRequest(
                payload);
    }

    abstract static class ApplicationMessage {
        abstract int protobufType();

        abstract boolean response();

        abstract void requireValid();

        void destroy() {
        }
    }

    static final class DeviceWillUnpairRequest
            extends ApplicationMessage {
        final String advertisedName;
        final Boolean shouldObliterate;
        final Integer pairingFailureCode;
        final String abortReason;
        final Boolean shouldBrick;
        final Boolean shouldPreserveEsim;

        DeviceWillUnpairRequest(
                String advertisedName,
                Boolean shouldObliterate,
                Integer pairingFailureCode,
                String abortReason,
                Boolean shouldBrick,
                Boolean shouldPreserveEsim) {
            this.advertisedName = advertisedName;
            this.shouldObliterate = shouldObliterate;
            this.pairingFailureCode = pairingFailureCode;
            this.abortReason = abortReason;
            this.shouldBrick = shouldBrick;
            this.shouldPreserveEsim = shouldPreserveEsim;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_DEVICE_WILL_UNPAIR;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            NanoRegistryClassDWireCodec.validateString(
                    advertisedName,
                    MAX_ADVERTISED_NAME_LENGTH,
                    "advertised name",
                    false);
            if (abortReason != null) {
                NanoRegistryClassDWireCodec.validateString(
                        abortReason,
                        MAX_STRING_LENGTH,
                        "unpair abort reason",
                        true);
            }
        }
    }

    static final class DeviceWillUnpairResponse
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_DEVICE_WILL_UNPAIR;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    static final class PairingModeRequest
            extends ApplicationMessage {
        final int pairingMode;
        final Integer phonePairingProtocolVersionMax;
        final Integer watchPairingProtocolVersion;
        final Integer phonePairingProtocolVersionMin;

        PairingModeRequest(
                int pairingMode,
                Integer phonePairingProtocolVersionMax,
                Integer watchPairingProtocolVersion,
                Integer phonePairingProtocolVersionMin) {
            this.pairingMode = pairingMode;
            this.phonePairingProtocolVersionMax =
                    phonePairingProtocolVersionMax;
            this.watchPairingProtocolVersion =
                    watchPairingProtocolVersion;
            this.phonePairingProtocolVersionMin =
                    phonePairingProtocolVersionMin;
            requireValid();
        }

        static PairingModeRequest modernIos26_6(
                int pairingMode) {
            return modernIos26_6(
                    pairingMode,
                    WATCHOS_26_6_REFERENCE_VERSION);
        }

        static PairingModeRequest forProfile(int pairingMode, int peer, CompanionProtocolProfile profile) {
            if (profile == null || !profile.accepts(peer)) throw new IllegalArgumentException("Incompatible Watch pairing version");
            return new PairingModeRequest(pairingMode, profile.maximum, peer, profile.minimum);
        }

        static PairingModeRequest modernIos26_6(
                int pairingMode,
                int runtimeWatchPairingVersion) {
            if (runtimeWatchPairingVersion
                    < IOS_26_6_PHONE_MIN_VERSION
                    || runtimeWatchPairingVersion
                    > IOS_26_6_PHONE_MAX_VERSION) {
                throw new IllegalArgumentException(
                        "Watch runtime pairing version is incompatible");
            }
            return new PairingModeRequest(
                    pairingMode,
                    IOS_26_6_PHONE_MAX_VERSION,
                    runtimeWatchPairingVersion,
                    IOS_26_6_PHONE_MIN_VERSION);
        }

        boolean hasAllVersionMetadata() {
            return phonePairingProtocolVersionMax != null
                    && watchPairingProtocolVersion != null
                    && phonePairingProtocolVersionMin != null;
        }

        boolean matchesIos26_6VersionTuple() {
            return Integer.valueOf(
                    IOS_26_6_PHONE_MAX_VERSION).equals(
                    phonePairingProtocolVersionMax)
                    && Integer.valueOf(
                            WATCHOS_26_6_REFERENCE_VERSION).equals(
                            watchPairingProtocolVersion)
                    && Integer.valueOf(
                            IOS_26_6_PHONE_MIN_VERSION).equals(
                            phonePairingProtocolVersionMin);
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_MODE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (pairingMode < COMPATIBILITY_STATE_INVALID
                    || pairingMode > COMPATIBILITY_STATE_ALT_ACCOUNT) {
                throw new IllegalArgumentException(
                        "Unknown NanoRegistry compatibility state");
            }
        }
    }

    /**
     * Generated by Apple, but no current iOS/watchOS 26.6 non-generated
     * sender was found. The current type-3 request is effectively one-way.
     */
    static final class PairingModeResponse
            extends ApplicationMessage {
        final boolean success;

        PairingModeResponse(
                boolean success) {
            this.success = success;
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_MODE;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    static final class PingRequest
            extends ApplicationMessage {
        final int responseIdsPriority;
        final double timeout;
        final int pingType;
        final byte[] payload;

        PingRequest(
                int responseIdsPriority,
                double timeout,
                int pingType,
                byte[] payload) {
            this.responseIdsPriority = responseIdsPriority;
            this.timeout = timeout;
            this.pingType = pingType;
            this.payload =
                    NanoRegistryClassDWireCodec.clonePayload(
                            payload,
                            "ping payload");
        }

        @Override
        int protobufType() {
            return TYPE_PING;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (payload != null
                    && payload.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping payload is too large");
            }
        }

        @Override
        void destroy() {
            NanoRegistryClassDWireCodec.wipe(
                    payload);
        }
    }

    static final class PingResponse
            extends ApplicationMessage {
        final double responseDate;
        final byte[] payload;

        PingResponse(
                double responseDate,
                byte[] payload) {
            this.responseDate = responseDate;
            this.payload =
                    NanoRegistryClassDWireCodec.clonePayload(
                            payload,
                            "ping response payload");
        }

        @Override
        int protobufType() {
            return TYPE_PING;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
            if (payload != null
                    && payload.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping response payload is too large");
            }
        }

        @Override
        void destroy() {
            NanoRegistryClassDWireCodec.wipe(
                    payload);
        }
    }

    static final class WatchMigrationCompletionRequest
            extends ApplicationMessage {
        final Integer status;

        WatchMigrationCompletionRequest(
                Integer status) {
            this.status = status;
        }

        @Override
        int protobufType() {
            return TYPE_WATCH_MIGRATION_COMPLETION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

    static final class RtcMigrationMetricSessionId
            extends ApplicationMessage {
        final String sessionId;

        RtcMigrationMetricSessionId(
                String sessionId) {
            this.sessionId = sessionId;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_RTC_MIGRATION_METRIC_SESSION_ID;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (sessionId != null) {
                NanoRegistryClassDWireCodec.validateString(
                        sessionId,
                        MAX_SESSION_ID_LENGTH,
                        "RTC migration metric session ID",
                        true);
            }
        }
    }

    static final class PairingSessionId
            extends ApplicationMessage {
        final String pairingSessionId;

        PairingSessionId(
                String pairingSessionId) {
            this.pairingSessionId = pairingSessionId;
            requireValid();
        }

        static PairingSessionId fromUuid(
                UUID value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry pairing session UUID is absent");
            }
            return new PairingSessionId(
                    value.toString());
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_SESSION_ID;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (pairingSessionId == null) {
                return;
            }
            NanoRegistryClassDWireCodec.validateString(
                    pairingSessionId,
                    MAX_SESSION_ID_LENGTH,
                    "pairing session ID",
                    false);
            if (!NanoRegistryClassDWireCodec.isCanonicalUuid(
                    pairingSessionId)) {
                throw new IllegalArgumentException(
                        "NanoRegistry pairing session ID is not a canonical UUID");
            }
        }
    }

    static final class GraduationRequest
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_GRADUATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

}
