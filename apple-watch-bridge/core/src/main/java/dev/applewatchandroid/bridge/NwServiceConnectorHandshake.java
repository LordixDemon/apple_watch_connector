package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One Network.framework service-connector handshake endpoint.
 *
 * <p>This class owns the connector-lifetime Ed25519 key, remote-key
 * bootstrap, monotonic request sequence, and simultaneous-open UUID
 * arbitration. TCP connection creation remains outside this class: a caller
 * sends the returned frames over the connection identified by that request.
 * Apple indexes active outgoing requests by endpoint plus service, so
 * unrelated services may be pending concurrently. A larger unsigned raw
 * request UUID wins a simultaneous normal request for the same endpoint and
 * service, exactly matching {@code nwsc_process_incoming_request} and
 * {@code nwsc_compare_uuid} in iOS 26.6.</p>
 */
final class NwServiceConnectorHandshake
        implements AutoCloseable {
    enum IncomingDisposition {
        ACCEPTED,
        WAITING_FOR_REMOTE_KEY,
        REJECTED_TRANSIENT,
        REJECTED_BY_POLICY
    }

    enum OutgoingDisposition {
        ACCEPTED,
        WAITING,
        REJECTED_BY_POLICY
    }

    private final String defaultEndpointIdentifier;
    private final int localPort;
    private final NwServiceConnectorSequenceAllocator sequences;
    private final byte[] localPrivateEd25519;
    private final byte[] localPublicEd25519;
    private final NwServiceConnectorCodec.RemotePublicKeyStore
            remoteKeys =
            new NwServiceConnectorCodec.RemotePublicKeyStore();
    private final Map<EndpointServiceKey, OutgoingNormal>
            outgoingNormalByEndpointAndService =
            new LinkedHashMap<>();
    private final List<PendingIncoming>
            pendingIncoming =
            new ArrayList<>();

    private final Map<String, Long> keyProbeSequences =
            new LinkedHashMap<>();
    private boolean closed;

    private NwServiceConnectorHandshake(
            String endpointIdentifier,
            int localPort,
            byte[] localPrivateEd25519,
            NwServiceConnectorSequenceAllocator sequences) {
        requireEndpointIdentifier(
                endpointIdentifier);
        if (localPort < 1
                || localPort > 0xffff
                || localPrivateEd25519 == null
                || localPrivateEd25519.length
                != NwServiceConnectorCodec.PRIVATE_KEY_LENGTH
                || sequences == null) {
            throw new IllegalArgumentException(
                    "Service-connector local identity is invalid");
        }
        this.defaultEndpointIdentifier = endpointIdentifier;
        this.localPort = localPort;
        this.sequences = sequences;
        this.localPrivateEd25519 =
                localPrivateEd25519.clone();
        this.localPublicEd25519 =
                new Ed25519PrivateKeyParameters(
                        localPrivateEd25519,
                        0)
                        .generatePublicKey()
                        .getEncoded();
    }

    static NwServiceConnectorHandshake create(
            SecureRandom random,
            String endpointIdentifier,
            int localPort,
            long sequenceSeed) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "Service-connector random source is absent");
        }
        byte[] privateKey =
                new byte[NwServiceConnectorCodec.PRIVATE_KEY_LENGTH];
        random.nextBytes(
                privateKey);
        try {
            return new NwServiceConnectorHandshake(
                    endpointIdentifier,
                    localPort,
                    privateKey,
                    new NwServiceConnectorSequenceAllocator(
                            sequenceSeed));
        } finally {
            wipe(
                    privateKey);
        }
    }

    static NwServiceConnectorHandshake create(
            SecureRandom random,
            String endpointIdentifier,
            int localPort,
            NwServiceConnectorSequenceAllocator sequences) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "Service-connector random source is absent");
        }
        byte[] privateKey =
                new byte[NwServiceConnectorCodec.PRIVATE_KEY_LENGTH];
        random.nextBytes(
                privateKey);
        try {
            return new NwServiceConnectorHandshake(
                    endpointIdentifier,
                    localPort,
                    privateKey,
                    sequences);
        } finally {
            wipe(
                    privateKey);
        }
    }

    static NwServiceConnectorHandshake withPrivateKeyForTest(
            String endpointIdentifier,
            int localPort,
            byte[] localPrivateEd25519,
            long sequenceSeed) {
        return new NwServiceConnectorHandshake(
                endpointIdentifier,
                localPort,
                localPrivateEd25519,
                new NwServiceConnectorSequenceAllocator(
                        sequenceSeed));
    }

    static NwServiceConnectorHandshake withPrivateKeyForTest(
            String endpointIdentifier,
            int localPort,
            byte[] localPrivateEd25519,
            NwServiceConnectorSequenceAllocator sequences) {
        return new NwServiceConnectorHandshake(
                endpointIdentifier,
                localPort,
                localPrivateEd25519,
                sequences);
    }

    synchronized OutgoingStart startNormalRequest(
            byte[] requestUuid,
            String serviceName) {
        return startNormalRequest(
                defaultEndpointIdentifier,
                requestUuid,
                serviceName);
    }

    synchronized OutgoingStart startNormalRequest(
            String endpointIdentifier,
            byte[] requestUuid,
            String serviceName) {
        return startNormalRequest(
                endpointIdentifier,
                localPort,
                requestUuid,
                serviceName);
    }

    synchronized OutgoingStart startNormalRequest(
            String endpointIdentifier,
            int port,
            byte[] requestUuid,
            String serviceName) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        if (port <= 0 || port > 0xffff) {
            throw new IllegalArgumentException(
                    "Service-connector local port is invalid");
        }
        if (serviceName == null
                || serviceName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Service-connector service name is absent");
        }
        EndpointServiceKey key =
                new EndpointServiceKey(
                        endpointIdentifier,
                        serviceName);
        if (outgoingNormalByEndpointAndService.containsKey(
                key)) {
            throw new IllegalStateException(
                    "A normal request for this service is already pending");
        }
        long requestSequence =
                sequences.next();
        byte[] frame =
                NwServiceConnectorCodec
                        .encodeNormalStartRequest(
                                port,
                                requestSequence,
                                requestUuid,
                                serviceName,
                                localPrivateEd25519);
        try {
            outgoingNormalByEndpointAndService.put(
                    key,
                    new OutgoingNormal(
                            endpointIdentifier,
                            requestSequence,
                            requestUuid,
                            serviceName));
            return new OutgoingStart(
                    requestSequence,
                    frame);
        } finally {
            wipe(
                    frame);
        }
    }

    /**
     * Creates the reverse no-op request used to obtain the peer public key.
     */
    synchronized OutgoingStart startKeyProbe() {
        return startKeyProbe(
                defaultEndpointIdentifier,
                localPort);
    }

    synchronized OutgoingStart startKeyProbe(
            String endpointIdentifier) {
        return startKeyProbe(
                endpointIdentifier,
                localPort);
    }

    synchronized OutgoingStart startKeyProbe(
            String endpointIdentifier,
            int port) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        if (port <= 0 || port > 0xffff) {
            throw new IllegalArgumentException(
                    "Service-connector local port is invalid");
        }
        if (keyProbeSequences.containsKey(
                endpointIdentifier)) {
            throw new IllegalStateException(
                    "A service-connector key probe is already pending");
        }
        long requestSequence =
                sequences.next();
        byte[] frame =
                NwServiceConnectorCodec
                        .encodeNoOpRequest(
                                port,
                                requestSequence,
                                localPrivateEd25519);
        keyProbeSequences.put(
                endpointIdentifier,
                requestSequence);
        try {
            return new OutgoingStart(
                    requestSequence,
                    frame);
        } finally {
            wipe(
                    frame);
        }
    }

    synchronized IncomingResult receiveIncomingRequest(
            NwServiceConnectorCodec.SignedRequest request,
            boolean serviceAllowed) {
        return receiveIncomingRequest(
                defaultEndpointIdentifier,
                request,
                serviceAllowed);
    }

    synchronized IncomingResult receiveIncomingRequest(
            String endpointIdentifier,
            NwServiceConnectorCodec.SignedRequest request,
            boolean serviceAllowed) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        if (request == null) {
            throw new IllegalArgumentException(
                    "Incoming service-connector request is absent");
        }
        if (request
                instanceof NwServiceConnectorCodec
                .OperationRequest operation) {
            // Native NoOp/Retry feedback publishes the key with flags zero.
            // It does not accept a service on this key-probe connection.
            byte[] feedback =
                    feedback(
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .TRANSIENT_REJECTION);
            try {
                return new IncomingResult(
                        IncomingDisposition.REJECTED_TRANSIENT,
                        feedback,
                        null,
                        false,
                        operation.operation
                                == NwServiceConnectorCodec
                                .OPERATION_RETRY);
            } finally {
                wipe(
                        feedback);
            }
        }
        if (!(request
                instanceof NwServiceConnectorCodec
                .NormalStartRequest normal)) {
            throw new IllegalArgumentException(
                    "Unsupported incoming service-connector request");
        }

        NwServiceConnectorCodec.VerificationDecision verification =
                remoteKeys.evaluate(
                        endpointIdentifier,
                        normal);
        if (verification
                == NwServiceConnectorCodec
                .VerificationDecision.NEEDS_NEWER_KEY) {
            boolean startReverseKeyProbe =
                    !hasPendingIncomingRequest(
                            endpointIdentifier)
                            && !keyProbeSequences.containsKey(
                            endpointIdentifier);
            addPendingIncoming(
                    endpointIdentifier,
                    normal,
                    serviceAllowed);
            return new IncomingResult(
                    IncomingDisposition.WAITING_FOR_REMOTE_KEY,
                    null,
                    null,
                    startReverseKeyProbe,
                    false);
        }
        if (verification
                == NwServiceConnectorCodec
                .VerificationDecision.REJECT_STALE_OR_FORGED) {
            return rejectIncoming(
                    false);
        }
        return decideVerifiedIncoming(
                endpointIdentifier,
                normal,
                serviceAllowed);
    }

    synchronized IncomingBatch receiveKeyProbeFeedback(
            NwServiceConnectorCodec.Feedback feedback) {
        return receiveKeyProbeFeedback(
                defaultEndpointIdentifier,
                feedback);
    }

    synchronized IncomingBatch receiveKeyProbeFeedback(
            String endpointIdentifier,
            NwServiceConnectorCodec.Feedback feedback) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        if (feedback == null) {
            throw new IllegalArgumentException(
                    "Service-connector key-probe feedback is absent");
        }
        remoteKeys.observeFeedback(
                endpointIdentifier,
                feedback);
        keyProbeSequences.remove(
                endpointIdentifier);
        List<PendingIncoming> pending =
                removePendingIncoming(
                        endpointIdentifier);
        List<IncomingResult> results =
                new ArrayList<>(
                        pending.size());
        try {
            for (PendingIncoming item : pending) {
                NwServiceConnectorCodec.VerificationDecision
                        verification =
                        remoteKeys.evaluate(
                                endpointIdentifier,
                                item.request);
                if (verification
                        == NwServiceConnectorCodec
                        .VerificationDecision.VERIFIED) {
                    results.add(
                            decideVerifiedIncoming(
                                    endpointIdentifier,
                                    item.request,
                                    item.serviceAllowed));
                } else {
                    results.add(
                            rejectIncoming(
                                    false));
                }
            }
            return new IncomingBatch(
                    results);
        } catch (RuntimeException exception) {
            closeResults(
                    results);
            throw exception;
        } finally {
            for (PendingIncoming item : pending) {
                item.destroy();
            }
        }
    }

    synchronized OutgoingDisposition receiveNormalFeedback(
            NwServiceConnectorCodec.Feedback feedback) {
        requireUsable();
        OutgoingNormal outgoingNormal =
                feedback == null
                        ? null
                        : findOutgoingNormal(
                                feedback.sequence);
        if (outgoingNormal == null
                && feedback != null
                && !outgoingNormalByEndpointAndService.isEmpty()) {
            outgoingNormal =
                    outgoingNormalByEndpointAndService.values().iterator().next();
        }
        if (feedback == null
                || outgoingNormal == null) {
            throw new IllegalArgumentException(
                    "Service-connector normal feedback does not match");
        }
        remoteKeys.observeFeedback(
                outgoingNormal.endpointIdentifier,
                feedback);
        return switch (feedback.disposition) {
            case ACCEPTED -> {
                removeOutgoingNormal(
                        outgoingNormal.endpointIdentifier,
                        outgoingNormal.serviceName);
                yield OutgoingDisposition.ACCEPTED;
            }
            case REJECTED_BY_POLICY -> {
                removeOutgoingNormal(
                        outgoingNormal.endpointIdentifier,
                        outgoingNormal.serviceName);
                yield OutgoingDisposition.REJECTED_BY_POLICY;
            }
            case TRANSIENT_REJECTION ->
                    OutgoingDisposition.WAITING;
        };
    }

    /**
     * Cancels one still-pending normal request.
     *
     * <p>The transport coordinator uses this only when the corresponding TCP
     * attempt cannot continue or loses simultaneous-open arbitration. Keeping
     * this operation in the handshake prevents the signed-request registry
     * from outliving its transport.</p>
     */
    synchronized boolean cancelNormalRequest(
            String serviceName) {
        return cancelNormalRequest(
                defaultEndpointIdentifier,
                serviceName);
    }

    synchronized boolean cancelNormalRequest(
            String endpointIdentifier,
            String serviceName) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        EndpointServiceKey key =
                new EndpointServiceKey(
                        endpointIdentifier,
                        serviceName);
        if (serviceName == null
                || !outgoingNormalByEndpointAndService.containsKey(
                        key)) {
            return false;
        }
        removeOutgoingNormal(
                endpointIdentifier,
                serviceName);
        return true;
    }

    /**
     * Cancels an outstanding no-op key probe after transport setup fails.
     */
    synchronized boolean cancelKeyProbe() {
        return cancelKeyProbe(
                defaultEndpointIdentifier);
    }

    synchronized boolean cancelKeyProbe(
            String endpointIdentifier) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        if (!keyProbeSequences.containsKey(
                endpointIdentifier)) {
            return false;
        }
        keyProbeSequences.remove(
                endpointIdentifier);
        return true;
    }

    /**
     * Builds a signed-endpoint feedback rejection without changing request
     * queues.
     *
     * <p>This is used after the coordinator has rejected a cross-connector
     * port claim or has arbitrated against an already-active connection.
     * Signature verification, when applicable, remains the caller's
     * responsibility.</p>
     */
    synchronized IncomingResult createIncomingRejection(
            boolean byPolicy) {
        requireUsable();
        return rejectIncoming(
                byPolicy);
    }

    synchronized boolean hasPendingNormalRequest() {
        requireUsable();
        return !outgoingNormalByEndpointAndService.isEmpty();
    }

    synchronized boolean hasPendingNormalRequest(
            String serviceName) {
        return hasPendingNormalRequest(
                defaultEndpointIdentifier,
                serviceName);
    }

    synchronized boolean hasPendingNormalRequest(
            String endpointIdentifier,
            String serviceName) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        return outgoingNormalByEndpointAndService.containsKey(
                new EndpointServiceKey(
                        endpointIdentifier,
                        serviceName));
    }

    synchronized int pendingNormalRequestCount() {
        requireUsable();
        return outgoingNormalByEndpointAndService.size();
    }

    synchronized boolean hasPendingIncomingRequest() {
        requireUsable();
        return !pendingIncoming.isEmpty();
    }

    synchronized boolean hasPendingIncomingRequest(
            String endpointIdentifier) {
        requireUsable();
        requireEndpointIdentifier(
                endpointIdentifier);
        for (PendingIncoming pending : pendingIncoming) {
            if (pending.endpointIdentifier.equals(
                    endpointIdentifier)) {
                return true;
            }
        }
        return false;
    }

    synchronized int pendingIncomingRequestCount() {
        requireUsable();
        return pendingIncoming.size();
    }

    private IncomingResult decideVerifiedIncoming(
            String endpointIdentifier,
            NwServiceConnectorCodec.NormalStartRequest request,
            boolean serviceAllowed) {
        if (!serviceAllowed) {
            return rejectIncoming(
                    true);
        }
        OutgoingNormal outgoingNormal =
                outgoingNormalByEndpointAndService.get(
                        new EndpointServiceKey(
                                endpointIdentifier,
                                request.serviceName));
        boolean cancelOutgoing =
                outgoingNormal != null
                        && compareUnsignedUuid(
                        outgoingNormal.requestUuid,
                        request.requestUuid) <= 0;
        if (outgoingNormal != null
                && !cancelOutgoing) {
            return rejectIncoming(
                    false);
        }
        if (cancelOutgoing) {
            removeOutgoingNormal(
                    endpointIdentifier,
                    request.serviceName);
        }
        byte[] feedback =
                feedback(
                        NwServiceConnectorCodec
                                .FeedbackDisposition.ACCEPTED);
        try {
            return new IncomingResult(
                    IncomingDisposition.ACCEPTED,
                    feedback,
                    cancelOutgoing
                            ? request.serviceName
                            : null,
                    false,
                    false);
        } finally {
            wipe(
                    feedback);
        }
    }

    private IncomingResult rejectIncoming(
            boolean byPolicy) {
        byte[] feedback =
                feedback(
                        byPolicy
                                ? NwServiceConnectorCodec
                                .FeedbackDisposition
                                .REJECTED_BY_POLICY
                                : NwServiceConnectorCodec
                                .FeedbackDisposition
                                .TRANSIENT_REJECTION);
        try {
            return new IncomingResult(
                    byPolicy
                            ? IncomingDisposition.REJECTED_BY_POLICY
                            : IncomingDisposition.REJECTED_TRANSIENT,
                    feedback,
                    null,
                    false,
                    false);
        } finally {
            wipe(
                    feedback);
        }
    }

    private byte[] feedback(
            NwServiceConnectorCodec.FeedbackDisposition disposition) {
        // Network.framework allocates a fresh local sequence for every
        // feedback. This versions our public key; it is not a request echo.
        return NwServiceConnectorCodec.encodeFeedback(
                sequences.next(),
                disposition,
                localPublicEd25519);
    }

    private void addPendingIncoming(
            String endpointIdentifier,
            NwServiceConnectorCodec.NormalStartRequest request,
            boolean serviceAllowed) {
        pendingIncoming.add(
                new PendingIncoming(
                        endpointIdentifier,
                        request,
                        serviceAllowed));
    }

    private List<PendingIncoming> removePendingIncoming(
            String endpointIdentifier) {
        List<PendingIncoming> matching =
                new ArrayList<>();
        for (int index = 0;
                index < pendingIncoming.size();) {
            PendingIncoming item =
                    pendingIncoming.get(
                            index);
            if (item.endpointIdentifier.equals(
                    endpointIdentifier)) {
                matching.add(
                        item);
                pendingIncoming.remove(
                        index);
            } else {
                index++;
            }
        }
        return matching;
    }

    static int compareUnsignedUuid(
            byte[] first,
            byte[] second) {
        if (first == null
                || second == null
                || first.length
                != NwServiceConnectorCodec.UUID_LENGTH
                || second.length
                != NwServiceConnectorCodec.UUID_LENGTH) {
            throw new IllegalArgumentException(
                    "Service-connector UUID is invalid");
        }
        for (int index = 0;
                index < NwServiceConnectorCodec.UUID_LENGTH;
                index++) {
            int comparison =
                    Integer.compare(
                            first[index] & 0xff,
                            second[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * Returns true when an incoming request supersedes an already-active
     * connection for the same endpoint and service.
     *
     * <p>Both sequence numbers are unsigned 64-bit values. The incoming
     * request wins on a larger sequence; equal sequences are broken by the
     * unsigned raw UUID bytes. Keeping the existing connection on an exact
     * tie is the conservative duplicate-request behaviour.</p>
     */
    static boolean incomingSupersedesActive(
            long incomingSequence,
            byte[] incomingUuid,
            long activeSequence,
            byte[] activeUuid) {
        int sequenceComparison =
                Long.compareUnsigned(
                        incomingSequence,
                        activeSequence);
        if (sequenceComparison != 0) {
            return sequenceComparison > 0;
        }
        return compareUnsignedUuid(
                incomingUuid,
                activeUuid) > 0;
    }

    synchronized boolean isKeyProbeInProgress(
            String endpointIdentifier) {
        requireUsable();
        return hasPendingIncomingRequest(
                endpointIdentifier)
                || keyProbeSequences.containsKey(
                endpointIdentifier);
    }

    synchronized void seedRemoteKey(
            String endpointIdentifier,
            byte[] publicEd25519) {
        requireUsable();
        remoteKeys.seedKey(
                endpointIdentifier,
                publicEd25519);
    }

    private OutgoingNormal findOutgoingNormal(
            long requestSequence) {
        for (OutgoingNormal outgoing :
                outgoingNormalByEndpointAndService.values()) {
            if (outgoing.sequence == requestSequence) {
                return outgoing;
            }
        }
        return null;
    }

    private void removeOutgoingNormal(
            String endpointIdentifier,
            String serviceName) {
        OutgoingNormal outgoing =
                outgoingNormalByEndpointAndService.remove(
                        new EndpointServiceKey(
                                endpointIdentifier,
                                serviceName));
        if (outgoing != null) {
            outgoing.destroy();
        }
    }

    private void clearOutgoingNormals() {
        for (OutgoingNormal outgoing :
                outgoingNormalByEndpointAndService.values()) {
            outgoing.destroy();
        }
        outgoingNormalByEndpointAndService.clear();
    }

    private static void requireEndpointIdentifier(
            String endpointIdentifier) {
        if (endpointIdentifier == null
                || endpointIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "Service-connector endpoint identifier is absent");
        }
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "Service-connector handshake is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearOutgoingNormals();
        for (PendingIncoming pending : pendingIncoming) {
            pending.destroy();
        }
        pendingIncoming.clear();
        keyProbeSequences.clear();
        remoteKeys.close();
        wipe(
                localPrivateEd25519);
        wipe(
                localPublicEd25519);
    }

    static final class OutgoingStart
            implements AutoCloseable {
        final long sequence;
        private final byte[] frame;
        private boolean closed;

        OutgoingStart(
                long sequence,
                byte[] frame) {
            this.sequence = sequence;
            this.frame = frame.clone();
        }

        byte[] frame() {
            if (closed) {
                throw new IllegalStateException(
                        "Service-connector outgoing start is closed");
            }
            return frame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    frame);
        }
    }

    static final class IncomingResult
            implements AutoCloseable {
        final IncomingDisposition disposition;
        final boolean cancelOutgoingNormal;
        final String cancelOutgoingServiceName;
        final boolean startReverseKeyProbe;
        final boolean retryPendingOutgoing;
        private final byte[] feedbackFrame;
        private boolean closed;

        IncomingResult(
                IncomingDisposition disposition,
                byte[] feedbackFrame,
                String cancelOutgoingServiceName,
                boolean startReverseKeyProbe,
                boolean retryPendingOutgoing) {
            this.disposition = disposition;
            this.feedbackFrame =
                    feedbackFrame == null
                            ? null
                            : feedbackFrame.clone();
            this.cancelOutgoingServiceName =
                    cancelOutgoingServiceName;
            this.cancelOutgoingNormal =
                    cancelOutgoingServiceName != null;
            this.startReverseKeyProbe =
                    startReverseKeyProbe;
            this.retryPendingOutgoing =
                    retryPendingOutgoing;
        }

        byte[] feedbackFrame() {
            if (closed) {
                throw new IllegalStateException(
                        "Service-connector incoming result is closed");
            }
            return feedbackFrame == null
                    ? null
                    : feedbackFrame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    feedbackFrame);
        }
    }

    static final class IncomingBatch
            implements AutoCloseable {
        private final List<IncomingResult> results;
        private boolean closed;

        IncomingBatch(
                List<IncomingResult> results) {
            this.results =
                    List.copyOf(
                            results);
        }

        int size() {
            requireOpen();
            return results.size();
        }

        IncomingResult get(
                int index) {
            requireOpen();
            return results.get(
                    index);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Service-connector incoming batch is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeResults(
                    results);
        }
    }

    private static final class OutgoingNormal {
        final String endpointIdentifier;
        final long sequence;
        final byte[] requestUuid;
        final String serviceName;

        OutgoingNormal(
                String endpointIdentifier,
                long sequence,
                byte[] requestUuid,
                String serviceName) {
            requireEndpointIdentifier(
                    endpointIdentifier);
            if (requestUuid == null
                    || requestUuid.length
                    != NwServiceConnectorCodec.UUID_LENGTH
                    || serviceName == null
                    || serviceName.isEmpty()) {
                throw new IllegalArgumentException(
                        "Pending normal request is invalid");
            }
            this.endpointIdentifier =
                    endpointIdentifier;
            this.sequence = sequence;
            this.requestUuid =
                    requestUuid.clone();
            this.serviceName = serviceName;
        }

        void destroy() {
            wipe(
                    requestUuid);
        }
    }

    private static final class PendingIncoming {
        final String endpointIdentifier;
        final NwServiceConnectorCodec.NormalStartRequest request;
        final boolean serviceAllowed;

        PendingIncoming(
                String endpointIdentifier,
                NwServiceConnectorCodec.NormalStartRequest request,
                boolean serviceAllowed) {
            requireEndpointIdentifier(
                    endpointIdentifier);
            this.endpointIdentifier =
                    endpointIdentifier;
            this.request =
                    new NwServiceConnectorCodec
                            .NormalStartRequest(
                            request.localPort,
                            request.sequence,
                            request.requestUuid,
                            request.serviceName,
                            request.signature);
            this.serviceAllowed =
                    serviceAllowed;
        }

        void destroy() {
            request.destroy();
        }
    }

    private static final class EndpointServiceKey {
        final String endpointIdentifier;
        final String serviceName;

        EndpointServiceKey(
                String endpointIdentifier,
                String serviceName) {
            requireEndpointIdentifier(
                    endpointIdentifier);
            if (serviceName == null
                    || serviceName.isEmpty()) {
                throw new IllegalArgumentException(
                        "Service-connector service name is absent");
            }
            this.endpointIdentifier =
                    endpointIdentifier;
            this.serviceName =
                    serviceName;
        }

        @Override
        public boolean equals(
                Object other) {
            if (this == other) {
                return true;
            }
            if (!(other
                    instanceof EndpointServiceKey key)) {
                return false;
            }
            return endpointIdentifier.equals(
                    key.endpointIdentifier)
                    && serviceName.equals(
                    key.serviceName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    endpointIdentifier,
                    serviceName);
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

    private static void closeResults(
            List<IncomingResult> results) {
        for (IncomingResult result : results) {
            result.close();
        }
    }
}
