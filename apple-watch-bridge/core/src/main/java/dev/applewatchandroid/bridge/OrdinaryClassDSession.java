package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Offline-complete ordinary Class-D/Class-C state machine after the Bluetooth
 * prelude.
 *
 * <p>Every outbound object contains both the raw IKE packet and its exact uIKE
 * type-0x04 frame. ERTM sequencing remains the responsibility of the normal
 * pipe transport.</p>
 */
final class OrdinaryClassDSession implements AutoCloseable {
    enum Phase {
        INITIATOR_READY,
        WAITING_FOR_SA_INIT_REQUEST,
        WAITING_FOR_SA_INIT_RESPONSE,
        WAITING_FOR_INTERMEDIATE_REQUEST,
        WAITING_FOR_INTERMEDIATE_RESPONSE,
        WAITING_FOR_IKE_AUTH_REQUEST,
        WAITING_FOR_IKE_AUTH_RESPONSE,
        WAITING_FOR_INFORMATIONAL_REQUEST,
        WAITING_FOR_INFORMATIONAL_RESPONSE,
        READY,
        DESTROYED
    }

    enum Exchange {
        IKE_SA_INIT,
        IKE_INTERMEDIATE,
        IKE_AUTH,
        INFORMATIONAL
    }

    private final SecureRandom random;
    private final OrdinaryIkeAuth.DataClass dataClass;
    private final NrLinkBluetoothPrelude.LocalRole localRole;
    private final AppleNetworkRelayPairingMaterial localMaterial;
    private final OrdinaryIkeAuth.PeerMaterial peerMaterial;
    private final OrdinaryIkeAuth.ResponderProfile responderProfile;

    String localApplicationDeviceName() {
        byte[] bytes = responderProfile.deviceNameData();
        try {
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] == 0) end--;
            return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } finally { wipe(bytes); }
    }
    private final byte[] localPrelude;
    private final byte[] remotePrelude;

    private Phase phase;

    private OrdinaryIkeSaInit.InitiatorState saInitiator;
    private OrdinaryIkeSaInit.Request saRequest;
    private OrdinaryIkeSaInit.ResponderState saResponder;
    private OrdinaryIkeSaInit.Response saResponse;
    private IkeV2SessionCrypto.IkeSaKeys initialKeys;

    private OrdinaryIkeIntermediate.InitiatorRequest
            intermediateRequest;
    private OrdinaryIkeIntermediate.InitiatorResult
            intermediateInitiatorResult;
    private OrdinaryIkeIntermediate.ResponderResult
            intermediateResponderResult;

    private OrdinaryIkeAuth.InitiatorRequest authRequest;
    private OrdinaryIkeAuth.InitiatorResult authInitiatorResult;
    private OrdinaryIkeAuth.ResponderResult authResponderResult;
    private OrdinaryIkeInformational.Request informationalRequest;
    private OrdinaryIkeInformational.Response informationalResponse;
    private OrdinaryIkeInformational.UnlockRequest
            pendingUnlockRequest;
    private OrdinaryIkeInformational.PostConnectResponse
            lastPostConnectResponse;
    private byte[] firstInformationalInboundPacket;
    private byte[] firstInformationalResponsePacket;
    private byte[] lastRemotePostConnectRequestPacket;
    private byte[] lastAcceptedInboundPacket;
    private Exchange lastCachedResponseExchange;
    private byte[] lastCachedResponsePacket;
    private boolean lastInboundWasRetransmission;
    private int pendingSaInitCapabilityFlags;
    private int nextLocalRequestMessageId = -1;
    private int nextRemoteRequestMessageId = -1;
    private int pendingDeleteMessageId = -1;
    private int lastRemotePostConnectMessageId = -1;
    private String pendingLinkDirectorObservation;
    private long nextLinkDirectorIdentifier;
    private OrdinaryIkeInformational.LinkDirectorRequest pendingLinkDirectorRequest;
    private int completedLinkDirectorAnnouncements;
    private final int[] acknowledgedLinkDirectorIds = {-1, -1};
    private long lastLinkDirectorSendMillis;
    private int linkDirectorAttempts;
    private boolean linkDirectorRetryLimitLogged;
    private java.util.function.Consumer<String> linkDirectorLogger;
    private byte[] applicationServiceRequest;
    private OrdinaryIkeInformational.LinkDirectorRequest pendingApplicationServiceRequest;
    private java.util.function.Consumer<byte[]> applicationServiceListener;
    private int acknowledgedApplicationServiceId = -1;
    private long lastApplicationServiceSendMillis;
    private int applicationServiceAttempts;

    private OrdinaryChildSaCrypto.ChildSaKeys childSaKeys;
    private OrdinaryChildSaCrypto.EspSession espSession;
    private AppleNetworkRelayInnerAddresses
            authoritativeAddresses;
    private boolean remoteClassCUnlocked;

    /**
     * Ownership of key material, peer material, and responder profile (when
     * present) transfers to this session.
     */
    static OrdinaryClassDSession create(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.PeerMaterial peerMaterial,
            byte[] localPrelude,
            byte[] remotePrelude,
            OrdinaryIkeAuth.ResponderProfile responderProfile) {
        return create(
                OrdinaryIkeAuth.DataClass.CLASS_D,
                random,
                localRole,
                localMaterial,
                peerMaterial,
                localPrelude,
                remotePrelude,
                responderProfile);
    }

    static OrdinaryClassDSession createClassC(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.PeerMaterial peerMaterial,
            byte[] localPrelude,
            byte[] remotePrelude,
            OrdinaryIkeAuth.ResponderProfile responderProfile) {
        return create(
                OrdinaryIkeAuth.DataClass.CLASS_C,
                random,
                localRole,
                localMaterial,
                peerMaterial,
                localPrelude,
                remotePrelude,
                responderProfile);
    }

    private static OrdinaryClassDSession create(
            OrdinaryIkeAuth.DataClass dataClass,
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.PeerMaterial peerMaterial,
            byte[] localPrelude,
            byte[] remotePrelude,
            OrdinaryIkeAuth.ResponderProfile responderProfile) {
        return new OrdinaryClassDSession(
                dataClass,
                random,
                localRole,
                localMaterial,
                peerMaterial,
                localPrelude,
                remotePrelude,
                responderProfile);
    }

    private OrdinaryClassDSession(
            OrdinaryIkeAuth.DataClass dataClass,
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole localRole,
            AppleNetworkRelayPairingMaterial localMaterial,
            OrdinaryIkeAuth.PeerMaterial peerMaterial,
            byte[] localPrelude,
            byte[] remotePrelude,
            OrdinaryIkeAuth.ResponderProfile responderProfile) {
        if (dataClass == null
                || random == null
                || localRole == null
                || localMaterial == null
                || peerMaterial == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary data-class session "
                            + "material is required");
        }
        requirePrelude(
                "local",
                localPrelude);
        requirePrelude(
                "remote",
                remotePrelude);
        NrLinkBluetoothPrelude.Parsed local =
                NrLinkBluetoothPrelude.parse(
                        localPrelude);
        NrLinkBluetoothPrelude.Parsed remote =
                NrLinkBluetoothPrelude.parse(
                        remotePrelude);
        byte[] localUuid = local.uuid();
        byte[] remoteUuid = remote.uuid();
        try {
            NrLinkBluetoothPrelude.LocalRole elected =
                    NrLinkBluetoothPrelude.electLocalRole(
                            localUuid,
                            remoteUuid);
            if (elected != localRole
                    || local.version
                    != NrLinkBluetoothPrelude.VERSION
                    || remote.version
                    != NrLinkBluetoothPrelude.VERSION
                    || (local.state
                    != NrLinkBluetoothPrelude.PairingState
                    .MODERN_PAIRING_KEY_CONFIRMATION
                    && local.state
                    != NrLinkBluetoothPrelude.PairingState
                    .HAS_COMPLETED_PAIRING)
                    || (remote.state
                    != NrLinkBluetoothPrelude.PairingState
                    .MODERN_PAIRING_KEY_CONFIRMATION
                    && remote.state
                    != NrLinkBluetoothPrelude.PairingState
                    .HAS_COMPLETED_PAIRING)
                    || local.flags != 0
                    || (remote.flags
                    & ~(NrLinkBluetoothPrelude
                    .FLAG_COMPANION_APL
                    | NrLinkBluetoothPrelude
                    .FLAG_USES_TLS)) != 0
                    || remote.usesTls()) {
                throw new IllegalArgumentException(
                        "Prelude pair does not match the "
                                + "elected ordinary-IPsec role");
            }
        } finally {
            wipe(localUuid);
            wipe(remoteUuid);
        }
        if (responderProfile == null) {
            throw new IllegalArgumentException(
                    "Each ordinary endpoint requires a local "
                            + "private-notify profile");
        }
        {
            byte[] profilePrelude =
                    responderProfile.localPrelude();
            try {
                if (!Arrays.equals(
                        localPrelude,
                        profilePrelude)) {
                    throw new IllegalArgumentException(
                            "Responder profile prelude does not "
                                    + "match this pipe");
                }
            } finally {
                wipe(profilePrelude);
            }
        }

        this.random = random;
        this.dataClass = dataClass;
        this.localRole = localRole;
        this.localMaterial = localMaterial;
        this.peerMaterial = peerMaterial;
        this.responderProfile = responderProfile;
        this.localPrelude = localPrelude.clone();
        this.remotePrelude = remotePrelude.clone();
        phase = localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? Phase.INITIATOR_READY
                : Phase.WAITING_FOR_SA_INIT_REQUEST;
    }

    Outbound start() {
        requirePhase(Phase.INITIATOR_READY);
        saInitiator =
                OrdinaryIkeSaInit.createInitiator(
                        random);
        phase = Phase.WAITING_FOR_SA_INIT_RESPONSE;
        return new Outbound(
                Exchange.IKE_SA_INIT,
                saInitiator.packet);
    }

    Outbound acceptInboundIke(
            byte[] packet) {
        requireLive();
        lastInboundWasRetransmission = false;
        if (lastAcceptedInboundPacket != null) {
            if (Arrays.equals(
                    lastAcceptedInboundPacket,
                    packet)) {
                lastInboundWasRetransmission = true;
                if (isResponsePacket(
                        packet)) {
                    // A repeated response does not advance the current
                    // request. Apple may deliver it in a fresh ERTM I-frame.
                    return null;
                }
                if (lastCachedResponseExchange == null
                        || lastCachedResponsePacket == null) {
                    throw new IllegalStateException(
                            "Exact IKE request retransmission has no "
                                    + "cached response");
                }
                return new Outbound(
                        lastCachedResponseExchange,
                        lastCachedResponsePacket);
            }
            if (sameIkeMessage(
                    lastAcceptedInboundPacket,
                    packet)) {
                throw new IllegalArgumentException(
                        "IKE retransmission changed");
            }
        }

        Outbound outbound = switch (phase) {
            case WAITING_FOR_SA_INIT_REQUEST ->
                    acceptSaInitRequest(packet);
            case WAITING_FOR_SA_INIT_RESPONSE ->
                    acceptSaInitResponse(packet);
            case WAITING_FOR_INTERMEDIATE_REQUEST ->
                    acceptIntermediateRequest(packet);
            case WAITING_FOR_INTERMEDIATE_RESPONSE ->
                    acceptIntermediateResponse(packet);
            case WAITING_FOR_IKE_AUTH_REQUEST ->
                    acceptIkeAuthRequest(packet);
            case WAITING_FOR_IKE_AUTH_RESPONSE ->
                    acceptIkeAuthResponse(packet);
            case WAITING_FOR_INFORMATIONAL_REQUEST ->
                    acceptInformationalRequest(packet);
            case WAITING_FOR_INFORMATIONAL_RESPONSE -> {
                acceptInformationalResponse(packet);
                yield null;
            }
            case READY ->
                    acceptReadyClassDInformational(packet);
            default -> throw new IllegalStateException(
                    "Ordinary data-class session cannot accept IKE in "
                            + phase);
        };
        rememberAcceptedInbound(
                packet,
                outbound);
        return outbound;
    }

    boolean lastInboundWasRetransmission() {
        requireLive();
        return lastInboundWasRetransmission;
    }

    int consumeSaInitCapabilityFlags() {
        requireLive();
        int result = pendingSaInitCapabilityFlags;
        pendingSaInitCapabilityFlags = 0;
        return result;
    }

    String consumeLinkDirectorObservation() {
        requireLive();
        String result = pendingLinkDirectorObservation;
        pendingLinkDirectorObservation = null;
        return result;
    }

    Phase phase() {
        return phase;
    }

    NrLinkBluetoothPrelude.LocalRole localRole() {
        requireLive();
        return localRole;
    }

    OrdinaryIkeAuth.DataClass dataClass() {
        requireLive();
        return dataClass;
    }

    boolean waitingForFreshSaInitRequest() {
        requireLive();
        return phase == Phase.WAITING_FOR_SA_INIT_REQUEST;
    }

    /**
     * Returns whether the IKE header belongs to the SA currently owned by
     * this data-class session. Before a responder receives its first
     * IKE_SA_INIT request there is intentionally no SPI to match.
     */
    boolean matchesIkePacket(
            byte[] packet) {
        requireLive();
        if (packet == null
                || packet.length < 28
                || be32(packet, 24) != packet.length) {
            return false;
        }
        byte[] initiatorSpi =
                knownInitiatorSpi();
        if (initiatorSpi == null
                || !matchesRange(
                        packet,
                        0,
                        initiatorSpi)) {
            return false;
        }
        byte[] responderSpi =
                knownResponderSpi();
        return responderSpi == null
                || matchesRange(
                        packet,
                        8,
                        responderSpi);
    }

    Outbound announceLocalClassCUnlocked() {
        requireReady();
        if (dataClass
                != OrdinaryIkeAuth.DataClass.CLASS_D) {
            throw new IllegalStateException(
                    "Class-C unlock is announced over Class D");
        }
        if (pendingUnlockRequest != null) {
            return new Outbound(
                    Exchange.INFORMATIONAL,
                    pendingUnlockRequest.packet);
        }
        if (pendingLinkDirectorRequest != null || pendingApplicationServiceRequest != null) {
            throw new IllegalStateException("Another Class-D INFORMATIONAL request is pending");
        }
        pendingUnlockRequest =
                OrdinaryIkeInformational
                        .createClassCUnlockRequest(
                                random,
                                localRole,
                                activeKeys(),
                                nextLocalRequestMessageId,
                                true);
        nextLocalRequestMessageId =
                incrementMessageId(
                        nextLocalRequestMessageId);
        return new Outbound(
                Exchange.INFORMATIONAL,
                pendingUnlockRequest.packet);
    }

    boolean established() {
        return phase == Phase.READY;
    }

    Outbound requestIkeDelete() {
        requireReady();
        if (pendingUnlockRequest != null || pendingLinkDirectorRequest != null || pendingApplicationServiceRequest != null
                || pendingDeleteMessageId >= 0) {
            throw new IllegalStateException("IKE Delete cannot start with another request pending");
        }
        if (nextLocalRequestMessageId < 0) {
            // Class C has no post-AUTH metadata exchange. Initiator used 0..2;
            // responder has not originated any request on this SA yet.
            nextLocalRequestMessageId = localRole == NrLinkBluetoothPrelude.LocalRole.INITIATOR ? 3 : 0;
        }
        byte[] packet = OrdinaryIkeInformational.createIkeDeleteRequest(
                random, localRole, activeKeys(), nextLocalRequestMessageId);
        try {
            pendingDeleteMessageId = nextLocalRequestMessageId;
            nextLocalRequestMessageId = incrementMessageId(nextLocalRequestMessageId);
            return new Outbound(Exchange.INFORMATIONAL, packet);
        } finally { wipe(packet); }
    }

    boolean linkDirectorHelloAcknowledged() {
        return completedLinkDirectorAnnouncements > 0;
    }

    boolean linkDirectorStateAcknowledged() {
        return completedLinkDirectorAnnouncements >= 2;
    }

    void enableLinkDirectorAnnouncements(long firstIdentifier, java.util.function.Consumer<String> logger) {
        requireLive();
        if (dataClass != OrdinaryIkeAuth.DataClass.CLASS_D || firstIdentifier <= 0
                || nextLinkDirectorIdentifier != 0) throw new IllegalStateException("Invalid LinkDirector configuration");
        nextLinkDirectorIdentifier = firstIdentifier;
        linkDirectorLogger = logger;
    }

    /** One outstanding IKE request at a time; replies do not advance setup. */
    Outbound pollLinkDirectorAnnouncement(long nowMillis) {
        requireLive();
        if (!established() || nextLinkDirectorIdentifier == 0 || pendingUnlockRequest != null
                || pendingDeleteMessageId >= 0) return null;
        if (completedLinkDirectorAnnouncements == 2) return pollApplicationServiceRequest(nowMillis);
        if (pendingLinkDirectorRequest != null) {
            if (nowMillis - lastLinkDirectorSendMillis < 10_000L) return null;
            // Native sendPrivateNotifies uses ten retries after the initial send.
            if (linkDirectorAttempts >= 11) {
                if (!linkDirectorRetryLimitLogged) {
                    linkDirectorRetryLimitLogged = true;
                    logLinkDirector("LINK DIRECTOR TX: retry limit; announcement unconfirmed; setup state unchanged.");
                }
                return null;
            }
        } else {
            int type = completedLinkDirectorAnnouncements == 0
                    ? LinkDirectorMessageCodec.HELLO : LinkDirectorMessageCodec.DEVICE_LINK_STATE;
            pendingLinkDirectorRequest = OrdinaryIkeInformational.createLinkDirectorRequest(
                    random, localRole, activeKeys(), nextLocalRequestMessageId,
                    nextLinkDirectorIdentifier++, type);
            nextLocalRequestMessageId = incrementMessageId(nextLocalRequestMessageId);
            linkDirectorAttempts = 0;
            linkDirectorRetryLimitLogged = false;
        }
        lastLinkDirectorSendMillis = nowMillis;
        linkDirectorAttempts++;
        logLinkDirector("LINK DIRECTOR TX: " + LinkDirectorMessageCodec.name(pendingLinkDirectorRequest.type)
                + " attempt=" + linkDirectorAttempts + "; authenticated C60E; identifier logged=false.");
        return new Outbound(Exchange.INFORMATIONAL, pendingLinkDirectorRequest.packet);
    }

    void requestApplicationService(byte[] request, java.util.function.Consumer<byte[]> listener) {
        requireLive();
        if (dataClass != OrdinaryIkeAuth.DataClass.CLASS_D || !established()
                || nextLinkDirectorIdentifier == 0 || applicationServiceRequest != null
                || pendingApplicationServiceRequest != null || applicationServiceListener != null
                || request == null || request.length == 0 || request.length > 1200 || listener == null)
            throw new IllegalStateException("Application service discovery unavailable on this session");
        applicationServiceRequest = request.clone();
        applicationServiceListener = listener;
    }

    void setApplicationServiceListener(java.util.function.Consumer<byte[]> listener) {
        requireLive();
        if (!established() || listener == null || applicationServiceListener != null)
            throw new IllegalStateException("Application service receiver unavailable");
        applicationServiceListener = listener;
    }

    private Outbound pollApplicationServiceRequest(long nowMillis) {
        if (pendingApplicationServiceRequest != null) {
            if (applicationServiceAttempts >= 11 || nowMillis - lastApplicationServiceSendMillis < 10_000L) return null;
        } else {
            if (applicationServiceRequest == null) return null;
            pendingApplicationServiceRequest = OrdinaryIkeInformational.createApplicationServiceRequest(
                    random, localRole, activeKeys(), nextLocalRequestMessageId,
                    nextLinkDirectorIdentifier++, applicationServiceRequest);
            nextLocalRequestMessageId = incrementMessageId(nextLocalRequestMessageId);
            wipe(applicationServiceRequest);
            applicationServiceRequest = null;
        }
        lastApplicationServiceSendMillis = nowMillis;
        applicationServiceAttempts++;
        logLinkDirector("APPLICATION SERVICE DISCOVERY: request sent; attempt=" + applicationServiceAttempts
                + "; keys/token/identifier logged=false; no snapshot or setup confirmation.");
        return new Outbound(Exchange.INFORMATIONAL, pendingApplicationServiceRequest.packet);
    }

    private void logLinkDirector(String message) {
        if (linkDirectorLogger == null) return;
        try { linkDirectorLogger.accept(message); }
        catch (RuntimeException failure) { linkDirectorLogger = null; }
    }

    boolean childSaEstablished() {
        requireLive();
        return espSession != null;
    }

    /**
     * Returns whether a complete IPv6/ESP packet names this session's
     * inbound Child SA. This is needed for valid 6LoWPAN link-local and
     * multicast outer addresses, which intentionally do not equal the
     * ordinary Class-C/Class-D unicast address quartet.
     */
    boolean matchesInboundEspPacket(
            byte[] ipv6Packet) {
        requireLive();
        return phase == Phase.READY
                && espSession != null
                && ipv6Packet != null
                && ipv6Packet.length
                >= 40 + OrdinaryChildSaCrypto.CHILD_SPI_LENGTH
                && ((ipv6Packet[0] & 0xf0) >>> 4) == 6
                && (ipv6Packet[6] & 0xff)
                == OrdinaryChildSaCrypto.ESP_PROTOCOL_NUMBER
                && espSession.matchesInboundSpi(
                        ipv6Packet,
                        40);
    }

    boolean remoteClassCUnlocked() {
        requirePhase(Phase.READY);
        return remoteClassCUnlocked;
    }

    AppleNetworkRelayInnerAddresses
            copyAuthoritativeAddresses() {
        requireReady();
        return copyAddresses(
                authoritativeAddresses);
    }

    private void requireReady() {
        requirePhase(Phase.READY);
    }

    byte[] encryptIpv6Transport(
            byte[] ipv6Packet) {
        requireReady();
        return espSession.encryptIpv6Transport(
                ipv6Packet);
    }

    byte[] decryptIpv6Transport(
            byte[] ipv6Packet) {
        requireReady();
        return espSession.decryptIpv6Transport(
                ipv6Packet);
    }

    byte[] encryptEsp(
            byte[] upperLayerPayload,
            int nextHeader,
            int trafficClassIndex) {
        requireReady();
        return espSession.encrypt(
                upperLayerPayload,
                nextHeader,
                trafficClassIndex);
    }

    OrdinaryChildSaCrypto.DecryptedEsp decryptEsp(
            byte[] packet) {
        requireReady();
        return espSession.decrypt(packet);
    }

    private Outbound acceptSaInitRequest(
            byte[] packet) {
        saRequest =
                OrdinaryIkeSaInit.parseRequest(
                        packet);
        saResponder =
                OrdinaryIkeSaInit.createResponder(
                        random,
                        saRequest);
        initialKeys =
                OrdinaryIkeSaInit.deriveResponderKeys(
                        saRequest,
                        saResponder);
        phase = Phase.WAITING_FOR_INTERMEDIATE_REQUEST;
        return new Outbound(
                Exchange.IKE_SA_INIT,
                saResponder.packet);
    }

    private Outbound acceptSaInitResponse(
            byte[] packet) {
        saResponse =
                OrdinaryIkeSaInit.parseResponse(
                        packet,
                        saInitiator.initiatorSpi);
        pendingSaInitCapabilityFlags =
                saResponse.capabilityFlags;
        initialKeys =
                OrdinaryIkeSaInit.deriveInitiatorKeys(
                        saInitiator,
                        saResponse);
        intermediateRequest =
                OrdinaryIkeIntermediate
                        .createInitiatorRequest(
                                random,
                                initialKeys);
        phase = Phase.WAITING_FOR_INTERMEDIATE_RESPONSE;
        return new Outbound(
                Exchange.IKE_INTERMEDIATE,
                intermediateRequest.packet);
    }

    private Outbound acceptIntermediateRequest(
            byte[] packet) {
        intermediateResponderResult =
                OrdinaryIkeIntermediate.respond(
                        random,
                        initialKeys,
                        packet);
        phase = Phase.WAITING_FOR_IKE_AUTH_REQUEST;
        return new Outbound(
                Exchange.IKE_INTERMEDIATE,
                intermediateResponderResult.packet);
    }

    private Outbound acceptIntermediateResponse(
            byte[] packet) {
        intermediateInitiatorResult =
                OrdinaryIkeIntermediate
                        .completeInitiator(
                                intermediateRequest,
                                initialKeys,
                                packet);
        authRequest =
                OrdinaryIkeAuth.createInitiatorRequest(
                        dataClass,
                        random,
                        localMaterial,
                        peerMaterial,
                        saInitiator.packet,
                        intermediateInitiatorResult.updatedKeys,
                        intermediateRequest.intAuthI,
                        intermediateInitiatorResult.intAuthR);
        phase = Phase.WAITING_FOR_IKE_AUTH_RESPONSE;
        return new Outbound(
                Exchange.IKE_AUTH,
                authRequest.packet);
    }

    private Outbound acceptIkeAuthRequest(
            byte[] packet) {
        authResponderResult =
                OrdinaryIkeAuth.authenticateAndRespond(
                        dataClass,
                        random,
                        localMaterial,
                        peerMaterial,
                        responderProfile,
                        saRequest.packet,
                        saResponder.packet,
                        intermediateResponderResult.updatedKeys,
                        intermediateResponderResult.intAuthI,
                        intermediateResponderResult.intAuthR,
                        packet);
        establish(
                intermediateResponderResult.updatedKeys,
                authResponderResult.initiatorChildSpi,
                authResponderResult.responderChildSpi,
                responderProfile.copyAddresses());
        phase = dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                ? Phase.WAITING_FOR_INFORMATIONAL_REQUEST
                : Phase.READY;
        return new Outbound(
                Exchange.IKE_AUTH,
                authResponderResult.packet);
    }

    private Outbound acceptIkeAuthResponse(
            byte[] packet) {
        authInitiatorResult =
                OrdinaryIkeAuth.authenticateResponder(
                        dataClass,
                        localMaterial,
                        peerMaterial,
                        remotePrelude,
                        saInitiator.packet,
                        saResponse.packet,
                        intermediateInitiatorResult.updatedKeys,
                        intermediateRequest.intAuthI,
                        intermediateInitiatorResult.intAuthR,
                        authRequest,
                        packet);
        establish(
                intermediateInitiatorResult.updatedKeys,
                authInitiatorResult.initiatorChildSpi,
                authInitiatorResult.responderChildSpi,
                copyAddresses(
                        authInitiatorResult.addresses));
        remoteClassCUnlocked =
                authInitiatorResult
                        .metadata
                        .keysAfterFirstUnlock;
        if (dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_C) {
            phase = Phase.READY;
            return null;
        }
        informationalRequest =
                OrdinaryIkeInformational.createRequest(
                        random,
                        responderProfile,
                        intermediateInitiatorResult.updatedKeys);
        phase = Phase.WAITING_FOR_INFORMATIONAL_RESPONSE;
        return new Outbound(
                Exchange.INFORMATIONAL,
                informationalRequest.packet);
    }

    private Outbound acceptInformationalRequest(
            byte[] packet) {
        informationalResponse =
                OrdinaryIkeInformational
                        .authenticateRequestAndCreateResponse(
                                random,
                                remotePrelude,
                                intermediateResponderResult.updatedKeys,
                                packet);
        firstInformationalInboundPacket =
                packet.clone();
        remoteClassCUnlocked =
                informationalResponse
                        .peerMetadata
                        .keysAfterFirstUnlock;
        markClassDReady();
        return new Outbound(
                Exchange.INFORMATIONAL,
                informationalResponse.packet);
    }

    private void acceptInformationalResponse(
            byte[] packet) {
        OrdinaryIkeInformational
                .authenticateEmptyResponse(
                        intermediateInitiatorResult.updatedKeys,
                        packet);
        firstInformationalResponsePacket =
                packet.clone();
        markClassDReady();
    }

    private Outbound acceptReadyClassDInformational(
            byte[] packet) {
        if (pendingDeleteMessageId >= 0
                && readInformationalMessageId(packet) == pendingDeleteMessageId
                && (packet[19] & IkeV2Codec.IKE_FLAG_RESPONSE) != 0) {
            OrdinaryIkeInformational.authenticatePostConnectResponse(
                    localRole, activeKeys(), packet, pendingDeleteMessageId);
            logLinkDirector("IKE DELETE ACK: authenticated " + dataClass + " session closure; pairing keys retained.");
            return null;
        }
        if (dataClass
                != OrdinaryIkeAuth.DataClass.CLASS_D) {
            throw new IllegalStateException(
                    "Class C has no post-connect metadata "
                            + "INFORMATIONAL in this chain");
        }
        int messageId = readInformationalMessageId(
                packet);
        boolean response =
                (packet[19]
                        & IkeV2Codec.IKE_FLAG_RESPONSE) != 0;

        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.RESPONDER
                && !response
                && messageId
                == OrdinaryIkeInformational.MESSAGE_ID) {
            if (firstInformationalInboundPacket == null
                    || informationalResponse == null
                    || !Arrays.equals(
                    firstInformationalInboundPacket,
                    packet)) {
                throw new IllegalArgumentException(
                        "Metadata INFORMATIONAL Message-ID "
                                + "reuse changed the request");
            }
            return new Outbound(
                    Exchange.INFORMATIONAL,
                    informationalResponse.packet);
        }
        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                && response
                && messageId
                == OrdinaryIkeInformational.MESSAGE_ID) {
            if (firstInformationalResponsePacket == null
                    || !Arrays.equals(
                    firstInformationalResponsePacket,
                    packet)) {
                throw new IllegalArgumentException(
                        "Metadata INFORMATIONAL response "
                                + "retransmission changed");
            }
            return null;
        }

        if (response) {
            if (pendingApplicationServiceRequest != null && messageId == pendingApplicationServiceRequest.messageId) {
                OrdinaryIkeInformational.authenticatePostConnectResponse(localRole, activeKeys(), packet, messageId);
                acknowledgedApplicationServiceId = messageId;
                pendingApplicationServiceRequest.destroy();
                pendingApplicationServiceRequest = null;
                logLinkDirector("APPLICATION SERVICE DISCOVERY: authenticated IKE receipt; awaiting endpoint response.");
                return null;
            }
            if (messageId == acknowledgedApplicationServiceId && acknowledgedApplicationServiceId >= 0) {
                OrdinaryIkeInformational.authenticatePostConnectResponse(localRole, activeKeys(), packet, messageId);
                return null;
            }
            if (pendingLinkDirectorRequest != null && messageId == pendingLinkDirectorRequest.messageId) {
                OrdinaryIkeInformational.authenticatePostConnectResponse(localRole, activeKeys(), packet, messageId);
                logLinkDirector("LINK DIRECTOR ACK: " + LinkDirectorMessageCodec.name(pendingLinkDirectorRequest.type)
                        + "; peer IKE receipt authenticated; setup state unchanged.");
                acknowledgedLinkDirectorIds[completedLinkDirectorAnnouncements++] = messageId;
                pendingLinkDirectorRequest.destroy();
                pendingLinkDirectorRequest = null;
                return null;
            }
            for (int acknowledgedId : acknowledgedLinkDirectorIds) {
                if (acknowledgedId >= 0 && messageId == acknowledgedId) {
                    OrdinaryIkeInformational.authenticatePostConnectResponse(localRole, activeKeys(), packet, messageId);
                    return null;
                }
            }
            if (pendingUnlockRequest == null
                    || messageId
                    != pendingUnlockRequest.messageId) {
                throw new IllegalArgumentException(
                        "Unexpected Class-C unlock "
                                + "INFORMATIONAL response");
            }
            OrdinaryIkeInformational
                    .authenticatePostConnectResponse(
                            localRole,
                            activeKeys(),
                            packet,
                            messageId);
            pendingUnlockRequest.destroy();
            pendingUnlockRequest = null;
            return null;
        }

        if (messageId == lastRemotePostConnectMessageId
                && lastPostConnectResponse != null) {
            if (!Arrays.equals(
                    lastRemotePostConnectRequestPacket,
                    packet)) {
                throw new IllegalArgumentException(
                        "Post-connect Message-ID reuse "
                                + "changed the request");
            }
            return new Outbound(
                    Exchange.INFORMATIONAL,
                    lastPostConnectResponse.packet);
        }
        if (messageId != nextRemoteRequestMessageId) {
            throw new IllegalArgumentException(
                    "Post-connect INFORMATIONAL Message ID "
                            + "is out of sequence");
        }
        OrdinaryIkeInformational.PostConnectResponse responseResult =
                OrdinaryIkeInformational
                        .authenticatePostConnectRequestAndCreateResponse(
                                random,
                                localRole,
                                activeKeys(),
                                packet,
                                messageId);
        if (lastPostConnectResponse != null) {
            lastPostConnectResponse.destroy();
        }
        wipe(lastRemotePostConnectRequestPacket);
        lastRemotePostConnectRequestPacket =
                packet.clone();
        lastPostConnectResponse = responseResult;
        lastRemotePostConnectMessageId = messageId;
        nextRemoteRequestMessageId =
                incrementMessageId(
                        nextRemoteRequestMessageId);
        if (responseResult.requestKind
                == OrdinaryIkeInformational
                .PostConnectRequestKind.CLASS_C_UNLOCK) {
            remoteClassCUnlocked =
                    responseResult.peerClassCUnlocked;
        } else {
            pendingLinkDirectorObservation =
                    responseResult.linkDirectorStructure;
            if (applicationServiceListener != null) {
                for (byte[] message : responseResult.linkDirectorMessages) {
                    byte[] copy = message.clone();
                    try { applicationServiceListener.accept(copy); }
                    catch (RuntimeException invalid) {
                        logLinkDirector("APPLICATION SERVICE DISCOVERY: invalid endpoint response ignored; link retained.");
                    } finally { wipe(copy); }
                }
            }
        }
        return new Outbound(
                Exchange.INFORMATIONAL,
                responseResult.packet);
    }

    private void markClassDReady() {
        if (dataClass
                != OrdinaryIkeAuth.DataClass.CLASS_D) {
            throw new IllegalStateException(
                    "Only Class D has the metadata-ready gate");
        }
        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR) {
            nextLocalRequestMessageId = 4;
            nextRemoteRequestMessageId = 0;
        } else {
            nextLocalRequestMessageId = 0;
            nextRemoteRequestMessageId = 4;
        }
        phase = Phase.READY;
    }

    private IkeV2SessionCrypto.IkeSaKeys activeKeys() {
        return localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                ? intermediateInitiatorResult.updatedKeys
                : intermediateResponderResult.updatedKeys;
    }

    private static int readInformationalMessageId(
            byte[] packet) {
        if (packet == null
                || packet.length < 28
                || (packet[17] & 0xff) != 0x20
                || (packet[18] & 0xff)
                != IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL
                || be32(packet, 24) != packet.length) {
            throw new IllegalArgumentException(
                    "Post-connect packet is not a complete "
                            + "IKEv2 INFORMATIONAL");
        }
        int messageId = be32(
                packet,
                20);
        if (messageId < 0) {
            throw new IllegalArgumentException(
                    "IKE Message ID exceeds the supported range");
        }
        return messageId;
    }

    private static int incrementMessageId(
            int current) {
        if (current < 0
                || current == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "IKE Message-ID counter exhausted");
        }
        return current + 1;
    }

    private static int be32(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private byte[] knownInitiatorSpi() {
        if (saInitiator != null) {
            return saInitiator.initiatorSpi;
        }
        if (saRequest != null) {
            return saRequest.initiatorSpi;
        }
        return null;
    }

    private byte[] knownResponderSpi() {
        if (saResponse != null) {
            return saResponse.responderSpi;
        }
        if (saResponder != null) {
            return saResponder.responderSpi;
        }
        return null;
    }

    private static boolean matchesRange(
            byte[] packet,
            int offset,
            byte[] expected) {
        for (int index = 0;
                index < expected.length;
                index++) {
            if (packet[offset + index]
                    != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private void establish(
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            byte[] initiatorChildSpi,
            byte[] responderChildSpi,
            AppleNetworkRelayInnerAddresses addresses) {
        childSaKeys =
                OrdinaryChildSaCrypto.derive(
                        updatedKeys,
                        initiatorChildSpi,
                        responderChildSpi);
        espSession =
                childSaKeys.newSession(
                        localRole);
        authoritativeAddresses = addresses;
    }

    @Override
    public void close() {
        if (phase == Phase.DESTROYED) {
            return;
        }
        if (espSession != null) {
            espSession.destroy();
        }
        if (childSaKeys != null) {
            childSaKeys.destroy();
        }
        if (authoritativeAddresses != null) {
            authoritativeAddresses.destroy();
        }
        if (authInitiatorResult != null) {
            authInitiatorResult.destroy();
        }
        if (authResponderResult != null) {
            authResponderResult.destroy();
        }
        if (authRequest != null) {
            authRequest.destroy();
        }
        if (informationalResponse != null) {
            informationalResponse.destroy();
        }
        if (informationalRequest != null) {
            informationalRequest.destroy();
        }
        if (pendingUnlockRequest != null) {
            pendingUnlockRequest.destroy();
        }
        if (pendingLinkDirectorRequest != null) pendingLinkDirectorRequest.destroy();
        if (pendingApplicationServiceRequest != null) pendingApplicationServiceRequest.destroy();
        wipe(applicationServiceRequest);
        applicationServiceListener = null;
        linkDirectorLogger = null;
        if (lastPostConnectResponse != null) {
            lastPostConnectResponse.destroy();
        }
        if (intermediateInitiatorResult != null) {
            intermediateInitiatorResult.destroy();
        }
        if (intermediateResponderResult != null) {
            intermediateResponderResult.destroy();
        }
        if (intermediateRequest != null) {
            intermediateRequest.destroy();
        }
        if (initialKeys != null) {
            initialKeys.destroy();
        }
        if (saInitiator != null) {
            saInitiator.destroy();
        }
        if (saResponder != null) {
            saResponder.destroy();
        }
        if (saResponse != null) {
            saResponse.destroy();
        }
        if (saRequest != null) {
            saRequest.destroy();
        }
        if (responderProfile != null) {
            responderProfile.destroy();
        }
        localMaterial.destroy();
        peerMaterial.destroy();
        wipe(localPrelude);
        wipe(remotePrelude);
        wipe(firstInformationalInboundPacket);
        wipe(firstInformationalResponsePacket);
        wipe(lastRemotePostConnectRequestPacket);
        wipe(lastAcceptedInboundPacket);
        wipe(lastCachedResponsePacket);
        pendingLinkDirectorObservation = null;
        phase = Phase.DESTROYED;
    }

    private void rememberAcceptedInbound(
            byte[] packet,
            Outbound outbound) {
        if (packet == null
                || packet.length < 28
                || be32(packet, 24) != packet.length) {
            throw new IllegalStateException(
                    "Accepted IKE packet has no complete header");
        }
        wipe(lastAcceptedInboundPacket);
        lastAcceptedInboundPacket = packet.clone();
        wipe(lastCachedResponsePacket);
        lastCachedResponsePacket = null;
        lastCachedResponseExchange = null;
        if (!isResponsePacket(packet)
                && outbound != null
                && isResponsePacket(outbound.ikePacket)) {
            lastCachedResponseExchange = outbound.exchange;
            lastCachedResponsePacket =
                    outbound.ikePacket.clone();
        }
    }

    private static boolean sameIkeMessage(
            byte[] first,
            byte[] second) {
        if (first == null
                || second == null
                || first.length < 28
                || second.length < 28) {
            return false;
        }
        for (int index = 0; index < 24; index++) {
            if (first[index] != second[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isResponsePacket(
            byte[] packet) {
        return packet != null
                && packet.length >= 28
                && be32(packet, 24) == packet.length
                && (packet[19] & 0x20) != 0;
    }

    private void requireLive() {
        if (phase == Phase.DESTROYED) {
            throw new IllegalStateException(
                    "Ordinary data-class session is destroyed");
        }
    }

    private void requirePhase(
            Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException(
                    "Ordinary data-class session is "
                            + phase
                            + ", expected "
                            + expected);
        }
    }

    private static AppleNetworkRelayInnerAddresses
            copyAddresses(
            AppleNetworkRelayInnerAddresses source) {
        byte[] initiatorD = source.initiatorClassD();
        byte[] responderD = source.responderClassD();
        byte[] initiatorC = source.initiatorClassC();
        byte[] responderC = source.responderClassC();
        try {
            return AppleNetworkRelayInnerAddresses
                    .fromAuthoritative(
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

    private static void requirePrelude(
            String label,
            byte[] value) {
        if (value == null
                || value.length
                != NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    label + " prelude must contain exactly "
                            + NrLinkBluetoothPrelude
                            .EXACT_ENCODED_LENGTH
                            + " bytes");
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class Outbound {
        final Exchange exchange;
        final byte[] ikePacket;
        final byte[] uikeFrame;
        private boolean destroyed;

        Outbound(
                Exchange exchange,
                byte[] ikePacket) {
            this.exchange = exchange;
            this.ikePacket = ikePacket.clone();
            this.uikeFrame =
                    IkeV2Codec.encodeUikeFrame(
                            ikePacket);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(ikePacket);
            wipe(uikeFrame);
            destroyed = true;
        }
    }
}
