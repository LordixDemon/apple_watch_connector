package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Production watchOS 26.2 SetupEncryptedChannel exchange for one IDS
 * local-delivery data connection.
 *
 * <p>The session owns its local random contribution, SSRC, and (for an
 * outgoing attempt) dynamically allocated port. It supports both a normal
 * request/reply exchange and simultaneous opens. Runtime material is never
 * exposed except through the short-lived control message that the caller
 * must destroy after encoding.</p>
 *
 * <p>The type-6 exchange still initializes the legacy UTun SRTP-v1 state,
 * but the modern {@code shouldUseServiceConnector} path does not put that
 * {@code e0/SSRC} envelope around application data. After NWSC acceptance,
 * {@link IdsSocketPairCodec} frames travel directly on the Network.framework
 * connection inside ESP. The explicitly legacy methods below exist only for
 * the non-service-connector fallback.</p>
 */
final class IdsEncryptedDataChannelSession
        implements AutoCloseable {
    enum EstablishAction {
        LOCAL_INITIATED_REPLY,
        SIMULTANEOUS_LOCAL_WINS,
        SIMULTANEOUS_REMOTE_WINS,
        INCOMING_REQUEST_REPLIED,
        IGNORED
    }

    private final IdsPortMap portMap;
    private final IdsSsrcMap ssrcMap;
    private final IdsServiceConnectorName connectorName;
    private final UUID localGuid;
    private final int localSsrc;
    private final int localStartSequence;

    private byte[] localContribution;
    private int localPort;
    private int remotePort;
    private boolean ownsDynamicPort;
    private boolean localSetupEmitted;
    private UUID remoteGuid;
    private IdsStreamEncryption encryption;
    private boolean plaintextEstablished;
    private EstablishAction establishAction;
    private boolean closed;

    private IdsEncryptedDataChannelSession(
            IdsPortMap portMap,
            IdsSsrcMap ssrcMap,
            IdsServiceConnectorName connectorName,
            UUID localGuid,
            int localSsrc,
            int localStartSequence,
            byte[] localContribution,
            int localPort,
            int remotePort,
            boolean ownsDynamicPort) {
        this.portMap = portMap;
        this.ssrcMap = ssrcMap;
        this.connectorName = connectorName;
        this.localGuid = localGuid;
        this.localSsrc = localSsrc;
        this.localStartSequence =
                localStartSequence;
        this.localContribution =
                localContribution;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.ownsDynamicPort =
                ownsDynamicPort;
    }

    static IdsEncryptedDataChannelSession openOutgoing(
            IdsPortMap portMap,
            IdsSsrcMap ssrcMap,
            SecureRandom random,
            IdsServiceConnectorName connectorName) {
        requireDependencies(
                ssrcMap,
                random,
                connectorName);
        if (portMap == null) {
            throw new IllegalArgumentException(
                    "IDS port map is absent");
        }
        requireTargetRoute(
                connectorName);

        int dynamicPort =
                portMap.allocate();
        int ssrc = 0;
        boolean ssrcAllocated = false;
        byte[] contribution = null;
        try {
            ssrc =
                    ssrcMap.allocate(
                            random);
            ssrcAllocated = true;
            contribution =
                    randomContribution(
                            random);
            return new IdsEncryptedDataChannelSession(
                    portMap,
                    ssrcMap,
                    connectorName,
                    randomUuid(
                            random),
                    ssrc,
                    random.nextInt() & 0xffff,
                    contribution,
                    dynamicPort,
                    IdsControlChannelCodec.DATA_PORT,
                    true);
        } catch (RuntimeException failure) {
            wipe(
                    contribution);
            if (ssrcAllocated) {
                ssrcMap.release(
                        ssrc);
            }
            portMap.release(
                    dynamicPort);
            throw failure;
        }
    }

    static ResponderStart respondToInitialRequest(
            IdsSsrcMap ssrcMap,
            SecureRandom random,
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Incoming IDS encrypted Setup is absent");
        }
        IdsServiceConnectorName connectorName =
                connectorName(
                        request);
        requireDependencies(
                ssrcMap,
                random,
                connectorName);
        requireTargetRoute(
                connectorName);
        requireInitialRequest(
                request);

        int ssrc =
                ssrcMap.allocate(
                        random);
        byte[] contribution = null;
        IdsEncryptedDataChannelSession session = null;
        IdsControlChannelCodec.SetupEncryptedChannelMessage reply =
                null;
        try {
            contribution =
                    randomContribution(
                            random);
            session =
                    new IdsEncryptedDataChannelSession(
                            null,
                            ssrcMap,
                            connectorName,
                            randomUuid(
                                    random),
                            ssrc,
                            random.nextInt() & 0xffff,
                            contribution,
                            request.remotePort,
                            request.localPort,
                            false);
            session.localSetupEmitted =
                    true;
            reply =
                    session.setupMessage(
                            request.remoteConnectionGuid);
            session.establish(
                    request,
                    EstablishAction
                            .INCOMING_REQUEST_REPLIED,
                    false);
            return new ResponderStart(
                    session,
                    reply);
        } catch (RuntimeException failure) {
            if (reply != null) {
                reply.destroy();
            }
            if (session != null) {
                session.close();
            } else {
                wipe(
                        contribution);
                ssrcMap.release(
                        ssrc);
            }
            throw failure;
        }
    }

    synchronized IdsControlChannelCodec
            .SetupEncryptedChannelMessage startSetup() {
        requireUsable();
        if (localSetupEmitted) {
            throw new IllegalStateException(
                    "IDS encrypted Setup was already emitted");
        }
        if (encryption != null) {
            throw new IllegalStateException(
                    "Established IDS connection cannot emit initial Setup");
        }
        localSetupEmitted =
                true;
        return setupMessage(
                null);
    }

    synchronized EstablishAction acceptPeerSetup(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage incoming) {
        requireUsable();
        if (!localSetupEmitted) {
            throw new IllegalStateException(
                    "Local IDS Setup was not emitted");
        }
        if (encryption != null) {
            throw new IllegalStateException(
                    "IDS encrypted data channel is already established");
        }
        requireMatchingRoute(
                incoming);

        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        localGuid.toString(),
                        false,
                        incoming);
        EstablishAction action;
        boolean adoptIncoming;
        switch (decision.action) {
            case COMPLETE_LOCAL_INITIATED_SETUP -> {
                requireReciprocalReplyPorts(
                        incoming);
                action =
                        EstablishAction
                                .LOCAL_INITIATED_REPLY;
                adoptIncoming =
                        false;
            }
            case COMPLETE_SIMULTANEOUS_LOCAL_WINS -> {
                requireInitialRequest(
                        incoming);
                action =
                        EstablishAction
                                .SIMULTANEOUS_LOCAL_WINS;
                adoptIncoming =
                        false;
            }
            case COMPLETE_SIMULTANEOUS_REMOTE_WINS -> {
                requireInitialRequest(
                        incoming);
                action =
                        EstablishAction
                                .SIMULTANEOUS_REMOTE_WINS;
                adoptIncoming =
                        true;
            }
            case IGNORE_REPLY_TO_CLEANED_CONNECTION,
                    IGNORE_REPEATED_CURRENT_SETUP,
                    SEND_CLOSE_FOR_REMOTE_ATTEMPT -> {
                return EstablishAction.IGNORED;
            }
            default -> throw new IllegalArgumentException(
                    "Peer IDS Setup does not complete the current "
                            + "encrypted connection");
        }

        establish(
                incoming,
                action,
                adoptIncoming);
        return action;
    }

    /**
     * Opens a type-2 responder that is already established for the modern
     * service-connector path. Live Watch sends unencrypted SetupChannel after
     * Hello; NWSC does not need the type-6 SRTP contribution.
     */
    static IdsEncryptedDataChannelSession openPlainEstablished(
            SecureRandom random,
            IdsControlChannelCodec.SetupChannelMessage request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Incoming IDS SetupChannel is absent");
        }
        if (request.forLocalGuid != null) {
            throw new IllegalArgumentException(
                    "IDS SetupChannel reply cannot open a responder");
        }
        IdsServiceConnectorName connectorName =
                IdsServiceConnectorName.of(
                        request.account,
                        request.service,
                        request.name);
        requireTargetRoute(
                connectorName);
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS encrypted connection dependency is absent");
        }
        IdsEncryptedDataChannelSession session =
                new IdsEncryptedDataChannelSession(
                        null,
                        null,
                        connectorName,
                        randomUuid(
                                random),
                        0,
                        0,
                        null,
                        request.localPort,
                        request.remotePort,
                        false);
        session.localSetupEmitted =
                true;
        session.plaintextEstablished =
                true;
        session.establishAction =
                EstablishAction.INCOMING_REQUEST_REPLIED;
        return session;
    }

    static IdsEncryptedDataChannelSession openPlainAdopted(
            SecureRandom random,
            IdsServiceConnectorName connectorName,
            int localPort,
            int remotePort) {
        if (connectorName == null) {
            throw new IllegalArgumentException(
                    "Connector name is absent");
        }
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS encrypted connection dependency is absent");
        }
        IdsEncryptedDataChannelSession session =
                new IdsEncryptedDataChannelSession(
                        null,
                        null,
                        connectorName,
                        randomUuid(random),
                        0,
                        0,
                        null,
                        localPort,
                        remotePort,
                        false);
        session.localSetupEmitted = true;
        session.plaintextEstablished = true;
        session.establishAction =
                EstablishAction.INCOMING_REQUEST_REPLIED;
        return session;
    }


    synchronized void markPlaintextEstablished() {
        requireUsable();
        plaintextEstablished =
                true;
        if (establishAction == null) {
            establishAction =
                    EstablishAction.INCOMING_REQUEST_REPLIED;
        }
        wipe(
                localContribution);
        localContribution =
                null;
    }

    synchronized String localGuid() {
        requireUsable();
        return localGuid.toString();
    }

    synchronized boolean established() {
        requireUsable();
        return encryption != null
                || plaintextEstablished;
    }

    synchronized EstablishAction establishAction() {
        requireUsable();
        if (establishAction == null) {
            throw new IllegalStateException(
                    "IDS encrypted data channel is not established");
        }
        return establishAction;
    }

    synchronized int localPort() {
        requireUsable();
        return localPort;
    }

    /**
     * True when this side allocated the type-6 listen port and sent the
     * initial Setup. Live Watch still expects the phone to SYN 61314 after
     * Hello; this flag only records who owns the Setup port.
     */
    synchronized boolean ownsListenPort() {
        requireUsable();
        return ownsDynamicPort;
    }

    synchronized int remotePort() {
        requireUsable();
        return remotePort;
    }

    synchronized String connectorService() {
        requireUsable();
        return connectorName.encode();
    }

    synchronized byte[] encryptLegacyUtunTcp(
            byte[] tcpSegment) {
        return requireEncryption()
                .encryptTcp(
                        tcpSegment);
    }

    synchronized IdsStreamEncryption.DecryptedPacket decryptLegacyUtun(
            byte[] frame) {
        return requireEncryption()
                .decrypt(
                        frame);
    }

    private void establish(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage incoming,
            EstablishAction action,
            boolean adoptIncoming) {
        byte[] combined =
                IdsStreamEncryption
                        .combineContributions(
                                localContribution,
                                incoming.keyMaterial);
        UUID parsedRemote = null;
        if (incoming.remoteConnectionGuid != null
                && !incoming.remoteConnectionGuid.isEmpty()) {
            try {
                parsedRemote =
                        UUID.fromString(
                                incoming.remoteConnectionGuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (parsedRemote == null) {
            parsedRemote =
                    UUID.randomUUID();
        }
        IdsStreamEncryption created = null;
        try {
            created =
                    IdsStreamEncryption.create(
                            combined,
                            localGuid,
                            parsedRemote,
                            localSsrc,
                            localStartSequence,
                            (int) incoming.ssrc,
                            incoming.startSequence);
            if (adoptIncoming) {
                releaseDynamicPort();
                localPort =
                        incoming.remotePort;
                remotePort =
                        incoming.localPort;
            }
            remoteGuid =
                    parsedRemote;
            encryption =
                    created;
            created = null;
            establishAction =
                    action;
            wipe(
                    localContribution);
            localContribution =
                    null;
        } finally {
            wipe(
                    combined);
            if (created != null) {
                created.close();
            }
        }
    }

    private IdsControlChannelCodec
            .SetupEncryptedChannelMessage setupMessage(
                    String forLocalGuid) {
        if (localContribution == null) {
            throw new IllegalStateException(
                    "IDS local contribution is no longer available");
        }
        return new IdsControlChannelCodec
                .SetupEncryptedChannelMessage(
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        localPort,
                        remotePort,
                        localGuid.toString(),
                        forLocalGuid,
                        connectorName.account,
                        connectorName.service,
                        connectorName.name,
                        localSsrc & 0xffffffffL,
                        localStartSequence,
                        localContribution);
    }

    private void requireMatchingRoute(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException(
                    "Incoming IDS encrypted Setup is absent");
        }
        if (incoming.type == IdsControlChannelCodec.TYPE_SETUP_ENCRYPTED_CHANNEL) {
            if (incoming.protocol
                    != IdsControlChannelCodec.PROTOCOL_TCP
                    || !connectorName.equals(
                            connectorName(
                                    incoming))) {
                throw new IllegalArgumentException(
                        "Incoming IDS encrypted Setup route does not match");
            }
        }
    }

    private void requireReciprocalReplyPorts(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage incoming) {
        if (incoming.localPort != remotePort
                || incoming.remotePort != localPort) {
            throw new IllegalArgumentException(
                    "IDS encrypted Setup reply ports are not reciprocal");
        }
    }

    private static void requireInitialRequest(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage request) {
        if (request.forLocalGuid != null
                || request.protocol
                != IdsControlChannelCodec.PROTOCOL_TCP
                || (request.remotePort
                != IdsControlChannelCodec.DATA_PORT
                && request.remotePort != 0)
                || request.localPort
                < IdsPortMap.FIRST_DYNAMIC_PORT) {
            throw new IllegalArgumentException(
                    "IDS encrypted Setup is not a modern initial request");
        }
    }

    private static IdsServiceConnectorName connectorName(
            IdsControlChannelCodec
                    .SetupEncryptedChannelMessage setup) {
        return IdsServiceConnectorName.of(
                setup.account,
                setup.service,
                setup.name);
    }

    private static void requireTargetRoute(
            IdsServiceConnectorName connectorName) {
        IdsIpsecServiceRoute.localDelivery(
                connectorName);
    }

    private static void requireDependencies(
            IdsSsrcMap ssrcMap,
            SecureRandom random,
            IdsServiceConnectorName connectorName) {
        if (ssrcMap == null
                || random == null
                || connectorName == null) {
            throw new IllegalArgumentException(
                    "IDS encrypted connection dependency is absent");
        }
    }

    private static byte[] randomContribution(
            SecureRandom random) {
        byte[] contribution =
                new byte[
                        IdsStreamEncryption
                                .CONTRIBUTION_LENGTH];
        random.nextBytes(
                contribution);
        return contribution;
    }

    private static UUID randomUuid(
            SecureRandom random) {
        byte[] bytes =
                new byte[16];
        random.nextBytes(
                bytes);
        try {
            bytes[6] =
                    (byte) ((bytes[6] & 0x0f)
                            | 0x40);
            bytes[8] =
                    (byte) ((bytes[8] & 0x3f)
                            | 0x80);
            return new UUID(
                    readU64(
                            bytes,
                            0),
                    readU64(
                            bytes,
                            8));
        } finally {
            wipe(
                    bytes);
        }
    }

    private static long readU64(
            byte[] bytes,
            int offset) {
        long value = 0;
        for (int index = 0;
                index < 8;
                index++) {
            value =
                    (value << 8)
                            | (bytes[offset + index] & 0xffL);
        }
        return value;
    }

    private IdsStreamEncryption requireEncryption() {
        requireUsable();
        if (encryption == null) {
            throw new IllegalStateException(
                    "IDS encrypted data channel is not established");
        }
        return encryption;
    }

    private void releaseDynamicPort() {
        if (!ownsDynamicPort
                || portMap == null) {
            return;
        }
        portMap.release(
                localPort);
        ownsDynamicPort =
                false;
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS encrypted data-channel session is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (encryption != null) {
            encryption.close();
            encryption = null;
        }
        wipe(
                localContribution);
        localContribution =
                null;
        remoteGuid =
                null;

        RuntimeException failure = null;
        try {
            releaseDynamicPort();
        } catch (RuntimeException releaseFailure) {
            failure =
                    releaseFailure;
        }
        if (ssrcMap != null
                && localSsrc != 0) {
            try {
                ssrcMap.release(
                        localSsrc);
            } catch (RuntimeException releaseFailure) {
                if (failure == null) {
                    failure =
                            releaseFailure;
                } else {
                    failure.addSuppressed(
                            releaseFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static final class ResponderStart
            implements AutoCloseable {
        IdsEncryptedDataChannelSession session;
        final IdsControlChannelCodec
                .SetupEncryptedChannelMessage reply;
        private boolean closed;

        private ResponderStart(
                IdsEncryptedDataChannelSession session,
                IdsControlChannelCodec
                        .SetupEncryptedChannelMessage reply) {
            this.session = session;
            this.reply = reply;
        }

        synchronized IdsEncryptedDataChannelSession takeSession() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS responder start is closed");
            }
            if (session == null) {
                throw new IllegalStateException(
                        "IDS responder session was already transferred");
            }
            IdsEncryptedDataChannelSession transferred =
                    session;
            session = null;
            return transferred;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            reply.destroy();
            if (session != null) {
                session.close();
                session = null;
            }
        }
    }
}
